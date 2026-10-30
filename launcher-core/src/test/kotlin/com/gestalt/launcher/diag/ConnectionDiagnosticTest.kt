/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.diag

import com.gestalt.launcher.model.Connection
import com.gestalt.launcher.testutil.TestArtifacts
import com.gestalt.launcher.tls.CertFingerprint
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.nio.file.Path
import java.net.ServerSocket
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * The Test-connection diagnostic against a local self-signed HTTPS server whose leaf is `CN=mirth-connect`
 * with no localhost SAN — exactly the live OIE/Mirth shape. Proves it captures + classifies the leaf, that
 * an exact pin makes it pass (hostname bypassed), and that unreachable/DNS cases report cleanly.
 */
class ConnectionDiagnosticTest {

    private lateinit var server: HttpsServer
    private lateinit var serverCert: X509Certificate
    private var port = 0

    private val jnlp = """
        <?xml version="1.0" encoding="UTF-8"?>
        <jnlp spec="1.0+" version="4.5.2">
          <resources><jar href="core.jar" main="true" sha256="qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqo="/></resources>
          <application-desc main-class="com.mirth.connect.client.ui.Mirth"/>
        </jnlp>
    """.trimIndent()

    @BeforeEach
    fun start() {
        val (kp, cert) = TestArtifacts.serverCert(commonName = "mirth-connect", sanDns = "mirth-connect")
        serverCert = cert
        val ks = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("server", kp.private, "x".toCharArray(), arrayOf(cert))
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, "x".toCharArray()) }
        val ctx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        server = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            httpsConfigurator = HttpsConfigurator(ctx)
            createContext("/webstart") { ex ->
                val b = jnlp.toByteArray()
                ex.sendResponseHeaders(200, b.size.toLong())
                ex.responseBody.use { it.write(b) }
            }
            start()
        }
        port = server.address.port
    }

    @AfterEach
    fun stop() = server.stop(0)

    private fun conn(pin: String? = null) =
        Connection(id = "d", name = "Sandbox", serverUrl = "https://localhost:$port", pinnedCertSha256 = pin)

    @Test
    @DisplayName("untrusted self-signed leaf: TLS check FAILS, is classified, and the leaf is captured")
    fun untrustedSelfSignedClassifiesAndCapturesLeaf() {
        val report = ConnectionDiagnostic.run(conn(), timeoutMs = 4_000)

        assertEquals(CheckStatus.PASS, report.checks.first { it.name == "DNS + TCP" }.status)
        val tls = report.checks.first { it.name == "TLS handshake" }
        assertEquals(CheckStatus.FAIL, tls.status)
        assertTrue(tls.classification == TlsFailureClass.PKIX_PATH || tls.classification == TlsFailureClass.HOSTNAME_MISMATCH) {
            "expected an untrusted/hostname classification, got ${tls.classification}"
        }
        assertTrue(report.needsTrustDecision) { "an untrusted leaf must route to the managed trust prompt" }
        val leaf = requireNotNull(report.leaf) { "a leaf certificate must be captured even on failure" }
        assertEquals(CertFingerprint.sha256Hex(serverCert), leaf.sha256)
        assertEquals("mirth-connect", leaf.subjectCn)
        assertFalse(report.ok)
        assertTrue(report.toText().contains(leaf.sha256Display)) { "diagnostics text must carry the fingerprint" }

        // Plain language: an untrusted self-signed leaf reads as a review-and-trust prompt, not a bare failure.
        if (tls.classification == TlsFailureClass.PKIX_PATH) {
            assertTrue(report.headline().startsWith("Untrusted self-signed certificate")) { report.headline() }
            assertTrue(tls.plainLanguage!!.contains("review and trust it", ignoreCase = true)) { tls.plainLanguage!! }
        } else {
            assertTrue(report.headline().contains("name doesn't match", ignoreCase = true)) { report.headline() }
            assertTrue(tls.plainLanguage!!.contains("trust the cert", ignoreCase = true)) { tls.plainLanguage!! }
        }
    }

    @Test
    @DisplayName("a legacy exact pin still counts as trusted: TLS PASSES (hostname bypassed) and the JNLP fetch succeeds")
    fun pinnedConnectionPasses() {
        val report = ConnectionDiagnostic.run(conn(CertFingerprint.sha256Hex(serverCert)), timeoutMs = 4_000)

        assertEquals(CheckStatus.PASS, report.checks.first { it.name == "TLS handshake" }.status) { report.toText() }
        assertEquals(CheckStatus.PASS, report.checks.first { it.name.startsWith("JNLP") }.status)
        // /digest is not served, but the JNLP carries inline digests, so it is a WARN (not required), not a FAIL.
        assertEquals(CheckStatus.WARN, report.checks.first { it.name == "Digest entry" }.status)
        assertTrue(report.ok) { report.toText() }
    }

    @Test
    @DisplayName("a certificate trusted in the store PASSES; a rejected one FAILS as REJECTED")
    fun trustStoreDecisionsDriveTheTlsCheck(@TempDir dir: Path) {
        val trusted = com.gestalt.launcher.tls.TrustedCertStore(dir.resolve("trusted-certs.json"))
        trusted.trust(serverCert, "localhost:$port", "d")
        val pass = ConnectionDiagnostic.run(conn(), timeoutMs = 4_000, trustedCerts = trusted)
        assertEquals(CheckStatus.PASS, pass.checks.first { it.name == "TLS handshake" }.status) { pass.toText() }
        assertTrue(pass.ok) { pass.toText() }

        trusted.reject(serverCert, "localhost:$port", "d")
        val fail = ConnectionDiagnostic.run(conn(), timeoutMs = 4_000, trustedCerts = trusted)
        val tls = fail.checks.first { it.name == "TLS handshake" }
        assertEquals(CheckStatus.FAIL, tls.status) { fail.toText() }
        assertEquals(TlsFailureClass.REJECTED, tls.classification)
        assertTrue(fail.headline().contains("rejected", ignoreCase = true)) { fail.headline() }
    }

    @Test
    @DisplayName("connection refused reports cleanly (no TLS error, no crash)")
    fun connectionRefusedIsClean() {
        val closedPort = ServerSocket(0).use { it.localPort } // closed as soon as the block exits
        val report = ConnectionDiagnostic.run(
            Connection(id = "x", name = "Closed", serverUrl = "https://127.0.0.1:$closedPort"), timeoutMs = 2_000,
        )
        val tcp = report.checks.first()
        assertEquals(CheckStatus.FAIL, tcp.status)
        assertEquals(1, report.checks.size) { "nothing downstream runs once the socket is unreachable" }
        assertFalse(report.ok)
        // Plain language for the panel: connection-refused names the addresses tried + the likely fix.
        val plain = requireNotNull(tcp.plainLanguage) { "a refused connection must carry plain language" }
        assertTrue(plain.contains("Connection refused", ignoreCase = true)) { plain }
        assertTrue(plain.contains("port-forward", ignoreCase = true)) { plain }
        assertTrue(plain.contains("127.0.0.1")) { "the tried IPv4 address should be named: $plain" }
    }

    @Test
    @DisplayName("a malformed URL yields a clean FAIL report instead of throwing")
    fun malformedUrlReturnsReport() {
        // A space is an illegal URI character: URI.create throws — run() must still return a report.
        val report = ConnectionDiagnostic.run(
            Connection(id = "x", name = "Bad", serverUrl = "https://bad host:8443/x"), timeoutMs = 1_000,
        )
        assertEquals(CheckStatus.FAIL, report.checks.first().status)
        assertFalse(report.ok)
        assertTrue(report.checks.first().detail.contains("no host", ignoreCase = true))
    }

    @Test
    @DisplayName("an unresolvable host is reported as a DNS failure")
    fun unknownHostIsDnsFail() {
        val report = ConnectionDiagnostic.run(
            Connection(id = "x", name = "Bad", serverUrl = "https://no-such-host.invalid:8443"), timeoutMs = 2_000,
        )
        val tcp = report.checks.first()
        assertEquals(CheckStatus.FAIL, tcp.status)
        assertTrue(tcp.detail.contains("DNS", ignoreCase = true) || tcp.detail.contains("not found", ignoreCase = true))
    }
}
