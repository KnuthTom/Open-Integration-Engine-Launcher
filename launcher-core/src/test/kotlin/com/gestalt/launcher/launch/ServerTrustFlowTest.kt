/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.launch

import com.gestalt.launcher.gate.GestaltTrust
import com.gestalt.launcher.model.Connection
import com.gestalt.launcher.net.ArtifactFetcher
import com.gestalt.launcher.testutil.TestArtifacts
import com.gestalt.launcher.tls.CertFingerprint
import com.gestalt.launcher.tls.CertTrustStatus
import com.gestalt.launcher.tls.TrustedCertStore
import com.gestalt.launcher.tls.UnknownServerCertificateException
import com.gestalt.trust.TrustMode
import com.gestalt.trust.TrustStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.cert.X509Certificate
import javax.net.ssl.SSLHandshakeException

/**
 * Trust on first use at the pipeline level: an unknown server certificate stops the launch for review
 * (recorded PENDING), a rejected one fails closed, and a connection with auto-approve on trusts it by
 * itself and proceeds. The TLS layer is simulated by a fetcher that raises the policy's own exception
 * until the certificate is trusted — the same exception the real trust manager throws.
 */
class ServerTrustFlowTest {

    private val base = "https://mirth.example.internal:8443/"

    /** Serves canned responses only once [trusted] accepts [leaf]; otherwise fails the TLS handshake. */
    private class TlsGatedFetcher(
        private val trusted: TrustedCertStore,
        private val leaf: X509Certificate,
        private val responses: Map<String, ByteArray>,
    ) : ArtifactFetcher {
        private val counter = java.util.concurrent.atomic.AtomicInteger()
        val attempts: Int get() = counter.get()
        override fun fetchBytes(uri: URI): ByteArray {
            counter.incrementAndGet()
            if (trusted.statusOf(leaf) != CertTrustStatus.TRUSTED) {
                throw SSLHandshakeException("PKIX path building failed").apply { initCause(UnknownServerCertificateException(listOf(leaf))) }
            }
            return responses[uri.toString()] ?: throw java.io.IOException("no canned response for $uri")
        }
    }

    private fun jnlp(sha256B64: String) = """
        <?xml version="1.0" encoding="UTF-8"?>
        <jnlp spec="1.0+">
          <resources><jar href="core.jar" main="true" sha256="$sha256B64"/></resources>
          <application-desc main-class="com.mirth.connect.client.ui.Mirth">
            <argument>https://localhost:8443</argument><argument>4.4.2</argument>
          </application-desc>
        </jnlp>
    """.trimIndent()

    private fun fixture(dir: Path): Pair<TestArtifacts.Ca, Map<String, ByteArray>> {
        val ca = TestArtifacts.selfSignedCa("CN=Pretend Root,O=Pretend CA,C=GB")
        val signer = TestArtifacts.codeSigningLeaf("CN=OIE,O=BrightCodeCompany OÜ,C=EE", ca)
        val jar = dir.resolve("core.jar")
        TestArtifacts.writeSignedJar(jar, mapOf("mirth/Main.class" to "main".toByteArray()), signer.keyPair.private, signer.chain)
        val bytes = Files.readAllBytes(jar)
        val sha = java.util.Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(bytes))
        return ca to mapOf("${base}webstart" to jnlp(sha).toByteArray(), "${base}core.jar" to bytes)
    }

    private fun launcher(dir: Path, ca: TestArtifacts.Ca, trusted: TrustedCertStore, fetcher: ArtifactFetcher, leaf: X509Certificate) = LauncherCore(
        trustStore = TrustStore.of(ca.cert),
        signerPins = GestaltTrust.OFFICIAL_SIGNER_PINS,
        gateMode = TrustMode.PIN_KNOWN_SIGNERS,
        cacheDir = dir.resolve("cache"),
        trustedCerts = trusted,
        fetcherFactory = { fetcher },
        serverLeafProbe = { leaf },
        sleeper = {},
    )

    @Test
    @DisplayName("unknown certificate, no auto-approve: launch stops for review and the leaf is recorded PENDING")
    fun unknownStopsForReview(@TempDir dir: Path) {
        val (ca, responses) = fixture(dir)
        val trusted = TrustedCertStore(dir.resolve("trusted-certs.json"))
        val leaf = TestArtifacts.serverCert("mirth-connect", "mirth-connect").second
        val fetcher = TlsGatedFetcher(trusted, leaf, responses)
        val conn = Connection(id = "c", name = "C", serverUrl = base)

        val ex = assertThrows(ServerCertificateReviewRequiredException::class.java) {
            launcher(dir, ca, trusted, fetcher, leaf).prepareLaunch(conn)
        }

        assertEquals(leaf, ex.leaf)
        assertEquals("mirth.example.internal:8443", ex.hostPort)
        assertEquals(CertTrustStatus.PENDING, trusted.statusOf(leaf)) { "recorded for the Certificates review list" }
        assertTrue(trusted.get(CertFingerprint.sha256Hex(leaf))!!.connectionIds.contains("c"))
        assertEquals(1, fetcher.attempts) { "no retry without a trust decision" }
    }

    @Test
    @DisplayName("rejected certificate: launch fails closed with the rejection, no retry, no change of status")
    fun rejectedFailsClosed(@TempDir dir: Path) {
        val (ca, responses) = fixture(dir)
        val trusted = TrustedCertStore(dir.resolve("trusted-certs.json"))
        val leaf = TestArtifacts.serverCert("mirth-connect", "mirth-connect").second
        trusted.reject(leaf, "mirth.example.internal:8443", "c")
        val fetcher = object : ArtifactFetcher {
            override fun fetchBytes(uri: URI): ByteArray =
                throw SSLHandshakeException("rejected").apply { initCause(com.gestalt.launcher.tls.RejectedServerCertificateException(listOf(leaf))) }
        }
        val conn = Connection(id = "c", name = "C", serverUrl = base, autoApproveCertificates = true) // even auto-approve can't override a rejection

        assertThrows(ServerCertificateRejectedException::class.java) { launcher(dir, ca, trusted, fetcher, leaf).prepareLaunch(conn) }
        assertEquals(CertTrustStatus.REJECTED, trusted.statusOf(leaf))
    }

    @Test
    @DisplayName("auto-approve on: the unknown certificate is trusted (flagged auto) and the launch proceeds in one call")
    fun autoApproveProceeds(@TempDir dir: Path) {
        val (ca, responses) = fixture(dir)
        val trusted = TrustedCertStore(dir.resolve("trusted-certs.json"))
        val leaf = TestArtifacts.serverCert("mirth-connect", "mirth-connect").second
        val fetcher = TlsGatedFetcher(trusted, leaf, responses)
        val conn = Connection(id = "c", name = "C", serverUrl = base, autoApproveCertificates = true)

        val plan = launcher(dir, ca, trusted, fetcher, leaf).prepareLaunch(conn)

        assertEquals(1, plan.admitted.size)
        val entry = trusted.get(CertFingerprint.sha256Hex(leaf))!!
        assertEquals(CertTrustStatus.TRUSTED, entry.status)
        assertTrue(entry.autoApproved)
        assertEquals(listOf("mirth.example.internal:8443"), entry.hosts)
        assertTrue(fetcher.attempts >= 2) { "the first fetch failed on trust, the retry succeeded" }
        assertNull(plan.command.firstOrNull { it.contains("javax.net.ssl.trustStore") }) { "no child truststore override any more" }
    }

    @Test
    @DisplayName("a connect failure is not a trust failure: no review, no auto-approve, the original error propagates")
    fun connectFailureIsNotTrust(@TempDir dir: Path) {
        val (ca, _) = fixture(dir)
        val trusted = TrustedCertStore(dir.resolve("trusted-certs.json"))
        val leaf = TestArtifacts.serverCert("mirth-connect", "mirth-connect").second
        val fetcher = object : ArtifactFetcher {
            override fun fetchBytes(uri: URI): ByteArray = throw java.net.ConnectException("Connection refused")
        }
        val conn = Connection(id = "c", name = "C", serverUrl = base, autoApproveCertificates = true)

        assertThrows(java.net.ConnectException::class.java) { launcher(dir, ca, trusted, fetcher, leaf).prepareLaunch(conn) }
        assertTrue(trusted.list().isEmpty()) { "nothing recorded" }
    }
}
