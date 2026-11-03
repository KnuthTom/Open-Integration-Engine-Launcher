/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.tls

import com.gestalt.launcher.model.Connection
import com.gestalt.launcher.model.ConnectionStore
import com.gestalt.launcher.testutil.TestArtifacts
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.time.Instant
import javax.net.ssl.X509TrustManager

/** The trust-on-first-use store, the TLS policy built on it, and the legacy-pin migration. */
class TrustedCertStoreTest {

    private fun leaf(cn: String = "mirth-connect"): X509Certificate = TestArtifacts.serverCert(cn, cn).second

    @Test
    @DisplayName("trust / reject / pending / remove round-trip through the file and are keyed by fingerprint")
    fun roundTrip(@TempDir dir: Path) {
        val file = dir.resolve("trusted-certs.json")
        val store = TrustedCertStore(file)
        val a = leaf("a"); val b = leaf("b"); val c = leaf("c")

        store.trust(a, "a.example.com:8443", "conn-a")
        store.reject(b, "b.example.com:8443", "conn-b")
        store.markPending(c, "c.example.com:8443", "conn-c")

        assertEquals(CertTrustStatus.TRUSTED, store.statusOf(a))
        assertEquals(CertTrustStatus.REJECTED, store.statusOf(b))
        assertEquals(CertTrustStatus.PENDING, store.statusOf(c))
        assertEquals(setOf(CertFingerprint.sha256Hex(a)), store.trustedFingerprints())
        assertEquals(setOf(CertFingerprint.sha256Hex(b)), store.rejectedFingerprints())
        assertEquals(listOf(CertFingerprint.sha256Hex(c)), store.pending().map { it.sha256 })

        val entry = store.get(CertFingerprint.sha256Hex(a))!!
        assertTrue(entry.subject!!.contains("CN=a")) { entry.subject }
        assertEquals(listOf("a.example.com:8443"), entry.hosts)
        assertEquals(listOf("conn-a"), entry.connectionIds)
        assertFalse(entry.autoApproved)
        assertFalse(entry.isExpired())

        // Reload from disk: same decisions.
        val reloaded = TrustedCertStore(file)
        assertEquals(CertTrustStatus.TRUSTED, reloaded.statusOf(a))
        assertEquals(CertTrustStatus.REJECTED, reloaded.statusOf(b))
        assertEquals(CertTrustStatus.PENDING, reloaded.statusOf(c))
        assertTrue(Files.list(dir).use { s -> s.noneMatch { it.fileName.toString().endsWith(".tmp") } })

        // A decision can change; PENDING never overrides a decision; remove forgets.
        reloaded.trust(b, "b.example.com:8443", "conn-b")
        assertEquals(CertTrustStatus.TRUSTED, reloaded.statusOf(b))
        reloaded.markPending(b, "other:8443", "conn-x")
        assertEquals(CertTrustStatus.TRUSTED, reloaded.statusOf(b)) { "markPending must not downgrade a decision" }
        assertEquals(listOf("conn-b", "conn-x"), reloaded.get(CertFingerprint.sha256Hex(b))!!.connectionIds)
        reloaded.remove(CertFingerprint.sha256Hex(b))
        assertNull(reloaded.statusOf(b))
    }

    @Test
    @DisplayName("auto-approved trust is flagged; a fingerprint-only (migrated) entry gains details when observed")
    fun autoApproveAndObserve(@TempDir dir: Path) {
        val store = TrustedCertStore(dir.resolve("trusted-certs.json"))
        val cert = leaf()
        val fp = CertFingerprint.sha256Hex(cert)

        store.trustFingerprint(fp.uppercase().chunked(2).joinToString(":"), "h:8443", "c1") // legacy display form normalizes
        val migrated = store.get(fp)!!
        assertEquals(CertTrustStatus.TRUSTED, migrated.status)
        assertNull(migrated.subject) { "no certificate was in hand — details unknown" }

        store.observed(cert, "h:8443", "c1")
        assertTrue(store.get(fp)!!.subject!!.contains("CN=mirth-connect"))
        assertEquals(CertTrustStatus.TRUSTED, store.get(fp)!!.status) { "observing never changes the decision" }

        val other = leaf("auto")
        store.trust(other, "x:8443", "c2", autoApproved = true)
        assertTrue(store.get(CertFingerprint.sha256Hex(other))!!.autoApproved)
    }

    @Test
    @DisplayName("forgetConnection drops the tag everywhere and removes undecided entries only that connection used")
    fun forgetConnection(@TempDir dir: Path) {
        val store = TrustedCertStore(dir.resolve("trusted-certs.json"))
        val shared = leaf("shared"); val pendingOnly = leaf("pending")
        store.trust(shared, "s:8443", "c1"); store.trust(shared, "s:8443", "c2")
        store.markPending(pendingOnly, "p:8443", "c1")

        store.forgetConnection("c1")

        assertEquals(listOf("c2"), store.get(CertFingerprint.sha256Hex(shared))!!.connectionIds) { "a decided entry keeps the other connection" }
        assertNull(store.statusOf(pendingOnly)) { "an undecided entry nobody else used is gone" }
    }

    @Test
    @DisplayName("a decision for a host retires that host's older undecided certificates — a rotated cert's PENDING entry is not a review item forever")
    fun decisionRetiresStalePending(@TempDir dir: Path) {
        val store = TrustedCertStore(dir.resolve("trusted-certs.json"))
        val old = leaf("old"); val new = leaf("new"); val elsewhere = leaf("elsewhere")
        store.markPending(old, "s:8443", "c1")
        store.markPending(elsewhere, "t:8443", "c1")

        store.trust(new, "s:8443", "c1")

        assertNull(store.statusOf(old)) { "the certificate s:8443 presented before, never decided, is gone" }
        assertEquals(CertTrustStatus.TRUSTED, store.statusOf(new))
        assertEquals(CertTrustStatus.PENDING, store.statusOf(elsewhere)) { "another host's undecided certificate is untouched" }
        assertTrue(store.pending().none { "s:8443" in it.hosts })

        // Deciding the same certificate again (idempotent) changes nothing else.
        store.trust(new, "s:8443", "c2")
        assertEquals(1, store.list().count { "s:8443" in it.hosts })
    }

    @Test
    @DisplayName("TLS policy: rejected fails, trusted passes by fingerprint, unknown surfaces with the chain, hostname follows suit")
    fun policy(@TempDir dir: Path) {
        val trusted = leaf("trusted"); val rejected = leaf("rejected"); val unknown = leaf("unknown")
        // A stand-in "system" trust manager that trusts nothing — so only our fingerprint rules decide.
        val noSystemTrust = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw CertificateException("no")
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw CertificateException("PKIX path building failed")
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val tm = TrustedOrPkixTrustManager(
            trusted = setOf(CertFingerprint.sha256Hex(trusted)),
            rejected = setOf(CertFingerprint.sha256Hex(rejected)),
            system = noSystemTrust,
        )
        tm.checkServerTrusted(arrayOf(trusted), "RSA") // passes without consulting the system

        val rej = runCatching { tm.checkServerTrusted(arrayOf(rejected), "RSA") }.exceptionOrNull()
        assertTrue(rej is RejectedServerCertificateException) { "got: $rej" }
        assertEquals(ServerTrustFailure.Kind.REJECTED, ServerTrustFailure.kindOf(RuntimeException("wrapped", rej)))

        val unk = runCatching { tm.checkServerTrusted(arrayOf(unknown), "RSA") }.exceptionOrNull()
        assertTrue(unk is UnknownServerCertificateException) { "got: $unk" }
        assertEquals(listOf(unknown), (unk as UnknownServerCertificateException).chain)
        assertEquals(ServerTrustFailure.Kind.UNKNOWN, ServerTrustFailure.kindOf(javax.net.ssl.SSLHandshakeException("h").apply { initCause(unk) }))
        assertNull(ServerTrustFailure.kindOf(java.net.ConnectException("refused"))) { "a connect failure is not a trust failure" }

        // Hostname: a trusted leaf passes any name; an untrusted one gets the standard check.
        val session = sessionPresenting(trusted)
        assertTrue(TrustedOrDefaultHostnameVerifier(setOf(CertFingerprint.sha256Hex(trusted))).verify("completely-different-host", session))
        assertFalse(TrustedOrDefaultHostnameVerifier(emptySet()).verify("completely-different-host", session))
        assertTrue(TrustedOrDefaultHostnameVerifier(emptySet()).verify("trusted", session)) { "an untrusted leaf still passes when the name matches its SAN" }
    }

    /** A minimal [javax.net.ssl.SSLSession] that presents [cert] (everything else is unused by the verifier). */
    private fun sessionPresenting(cert: X509Certificate): javax.net.ssl.SSLSession =
        java.lang.reflect.Proxy.newProxyInstance(
            javax.net.ssl.SSLSession::class.java.classLoader,
            arrayOf(javax.net.ssl.SSLSession::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getPeerCertificates" -> arrayOf<java.security.cert.Certificate>(cert)
                "getPeerHost" -> "peer"
                "getPeerPort" -> 8443
                "getProtocol" -> "TLSv1.3"
                "getCipherSuite" -> "TLS_AES_128_GCM_SHA256"
                "isValid" -> true
                else -> when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    else -> null
                }
            }
        } as javax.net.ssl.SSLSession

    @Test
    @DisplayName("expiry is reported, never enforced, for a trusted certificate")
    fun expiryIsAWarning(@TempDir dir: Path) {
        val store = TrustedCertStore(dir.resolve("trusted-certs.json"))
        val cert = leaf()
        val entry = store.trust(cert, "h:8443", "c")
        assertFalse(entry.isExpired())
        val farFuture = Instant.parse("2999-01-01T00:00:00Z")
        assertTrue(entry.isExpired(farFuture))
        assertTrue(entry.daysUntilExpiry(farFuture)!! < 0)
        assertEquals(setOf(entry.sha256), store.trustedFingerprints()) { "still trusted while expired — the UI warns" }
    }

    @Test
    @DisplayName("legacy per-connection pins migrate into the store once and are cleared from the connections")
    fun migration(@TempDir dir: Path) {
        val connections = ConnectionStore(dir.resolve("connections.json"))
        val trusted = TrustedCertStore(dir.resolve("trusted-certs.json"))
        val cert = leaf()
        val fp = CertFingerprint.sha256Hex(cert)
        connections.add(Connection(id = "p", name = "Pinned", serverUrl = "https://oie.example.internal:9912", pinnedCertSha256 = fp))
        connections.add(Connection(id = "u", name = "Unpinned", serverUrl = "https://other.example.internal:8443"))

        assertEquals(1, TrustMigration.migrateLegacyPins(connections, trusted))

        assertNull(connections.get("p")!!.pinnedCertSha256)
        assertEquals(CertTrustStatus.TRUSTED, trusted.get(fp)!!.status)
        assertEquals(listOf("oie.example.internal:9912"), trusted.get(fp)!!.hosts)
        assertEquals(listOf("p"), trusted.get(fp)!!.connectionIds)
        assertNull(ConnectionStore(dir.resolve("connections.json")).get("p")!!.pinnedCertSha256) { "the cleared pin is persisted" }
        assertEquals(0, TrustMigration.migrateLegacyPins(connections, trusted)) { "idempotent" }
    }
}
