/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.diag

import com.gestalt.launcher.launch.LaunchAbortedException
import com.gestalt.launcher.model.Connection
import com.gestalt.launcher.model.JavaHomeConfig
import com.gestalt.launcher.model.JavaHomeMode
import com.gestalt.launcher.tunnel.TunnelFailedException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLProtocolException
import org.xml.sax.SAXException

/**
 * The diagnosis classifier maps each failure class to a human-readable explanation + safe fix. The
 * security-critical invariant — **no branch ever offers a verify-weakening action** — is asserted both
 * structurally (the [FixAction] enum) and per-diagnosis.
 */
class FailureDiagnosisTest {

    private val conn = Connection(id = "c", name = "OIE", serverUrl = "https://oie.lab:8443")

    private val SAFE_ACTIONS = setOf(
        FixAction.ReviewAndPinCert, FixAction.EditConnection,
        FixAction.ReviewTrustPosture, FixAction.ViewLogs, FixAction.None,
    )

    @Test
    @DisplayName("tunnel failures: the port never listening, and the server side refusing the forwarded port, are told apart")
    fun tunnelFailures() {
        val conn = Connection(id = "t", name = "Forwarded", serverUrl = "https://localhost:31065", tunnelCommand = "forward.bat")
        val never = FailureDiagnosis.classify(TunnelFailedException("the tunnel command did not open localhost:31065 within 60 s"), conn)
        assertEquals(FailureKind.TunnelFailed, never.kind)
        assertEquals("The tunnel didn't open", never.title)

        val refused = FailureDiagnosis.classify(
            TunnelFailedException("the port-forward opened and accepted the connection, but nothing answered on the server side on ports 8000, 8443 (the session reported \"Connection to destination port failed\"): …", IOException("Connection reset")),
            conn,
        )
        assertEquals(FailureKind.TunnelFailed, refused.kind)
        assertEquals("The tunnel is up, but the server didn't answer", refused.title)
        assertTrue(refused.explanation.contains("on ports 8000, 8443")) { refused.explanation }
        assertTrue(refused.nextStep.contains("8443, 8000")) { refused.nextStep }
        assertTrue(refused.actions.contains(FixAction.ViewLogs))
    }

    @Test
    @DisplayName("no FixAction weakens verification and no diagnosis offers a bypass")
    fun noBypassAnywhere() {
        assertTrue(FixAction.entries.none { it.weakensVerification }) { "a bypass action exists" }

        allDiagnoses().forEach { d ->
            assertFalse(d.offersBypass) { "${d.kind} offered a bypass" }
            assertTrue(SAFE_ACTIONS.containsAll(d.actions)) { "${d.kind} offered an unexpected action: ${d.actions}" }
        }
    }

    @Test
    @DisplayName("server cert untrusted / self-signed -> review & pin")
    fun serverCertUntrusted() {
        val pin = FailureDiagnosis.classify(CertificateException("server certificate pin mismatch (expected aa, got bb)"), conn)
        assertEquals(FailureKind.ServerCertUntrusted, pin.kind)
        assertTrue(FixAction.ReviewAndPinCert in pin.actions)

        val selfSigned = FailureDiagnosis.classify(
            SSLHandshakeException("PKIX path building failed: unable to find valid certification path to requested target"),
            conn,
        )
        assertEquals(FailureKind.ServerCertUntrusted, selfSigned.kind)
        assertTrue(FixAction.ReviewAndPinCert in selfSigned.actions)
    }

    @Test
    @DisplayName("TLS handshake / cipher-protocol mismatch -> edit SSL settings")
    fun tlsHandshake() {
        val d = FailureDiagnosis.classify(SSLHandshakeException("Received fatal alert: handshake_failure"), conn)
        assertEquals(FailureKind.TlsHandshake, d.kind)
        assertTrue(FixAction.EditConnection in d.actions)

        val proto = FailureDiagnosis.classify(SSLProtocolException("No appropriate protocol (protocol is disabled or cipher suites are inappropriate)"), conn)
        assertEquals(FailureKind.TlsHandshake, proto.kind)
    }

    @Test
    @DisplayName("digest mismatch -> hard fail, only View logs, no bypass")
    fun digestMismatch() {
        val d = FailureDiagnosis.classify(
            LaunchAbortedException("verification failed for 'mirth-client-core.jar': digest mismatch (expected aa, got bb)"),
            conn,
        )
        assertEquals(FailureKind.DigestMismatch, d.kind)
        assertEquals(listOf(FixAction.ViewLogs), d.actions)
        assertFalse(d.offersBypass)
    }

    @Test
    @DisplayName("manifest tamper is an integrity failure (not a signer-trust issue)")
    fun manifestTamperIsIntegrity() {
        // JarSignatureVerifier reports these as the signature leg, but they prove the CONTENT was altered.
        val altered = FailureDiagnosis.classify(
            LaunchAbortedException("verification failed for 'core.jar': signature file (SHA-256) does not match MANIFEST.MF — manifest was altered after signing"),
            conn,
        )
        assertEquals(FailureKind.DigestMismatch, altered.kind)
        assertEquals(listOf(FixAction.ViewLogs), altered.actions)

        val uncovered = FailureDiagnosis.classify(
            LaunchAbortedException("verification failed for 'core.jar': jar contains an entry not covered by the signed manifest: evil.class"),
            conn,
        )
        assertEquals(FailureKind.DigestMismatch, uncovered.kind)

        // A server digest manifest that simply lacks an entry is an invalid descriptor, not tamper.
        val noEntry = FailureDiagnosis.classify(
            LaunchAbortedException("verification failed for 'plugin.jar': no digest in manifest for 'plugin.jar'"),
            conn,
        )
        assertEquals(FailureKind.JnlpInvalid, noEntry.kind)
    }

    @Test
    @DisplayName("signature not trusted -> point to trust posture, no bypass")
    fun signatureUntrusted() {
        val pin = FailureDiagnosis.classify(
            LaunchAbortedException("verification failed for 'plugin.jar': signer is not on the pinned allow-list: CN=Evil Corp"),
            conn,
        )
        assertEquals(FailureKind.SignatureUntrusted, pin.kind)
        assertTrue(FixAction.ReviewTrustPosture in pin.actions)
        assertTrue(FixAction.ViewLogs in pin.actions)
        assertFalse(FixAction.ReviewAndPinCert in pin.actions) { "signer trust is not pinned by re-pinning a server cert" }

        val path = FailureDiagnosis.classify(
            LaunchAbortedException("verification failed for 'plugin.jar': no certification path could be built from the signer to a trust anchor"),
            conn,
        )
        assertEquals(FailureKind.SignatureUntrusted, path.kind)
    }

    @Test
    @DisplayName("host unreachable / refused -> names host:port")
    fun hostUnreachable() {
        listOf(
            ConnectException("Connection refused: connect"),
            SocketTimeoutException("connect timed out"),
            UnknownHostException("oie.lab"),
        ).forEach { t ->
            val d = FailureDiagnosis.classify(t, conn)
            assertEquals(FailureKind.HostUnreachable, d.kind) { "for ${t.javaClass.simpleName}" }
            assertTrue(d.title.contains("oie.lab:8443")) { "title was ${d.title}" }
        }
    }

    @Test
    @DisplayName("JNLP fetch/parse error -> invalid launch descriptor")
    fun jnlpInvalid() {
        val http = FailureDiagnosis.classify(IOException("GET https://oie.lab:8443/webstart failed: HTTP 404"), conn)
        assertEquals(FailureKind.JnlpInvalid, http.kind)

        val parse = FailureDiagnosis.classify(SAXException("Element type \"jnlp\" must be followed by..."), conn)
        assertEquals(FailureKind.JnlpInvalid, parse.kind)

        val missingMain = FailureDiagnosis.classify(LaunchAbortedException("JNLP did not specify a main-class"), conn)
        assertEquals(FailureKind.JnlpInvalid, missingMain.kind)
    }

    @Test
    @DisplayName("Java runtime missing -> names the configured runtime")
    fun javaRuntimeMissing() {
        val bundled8 = conn.copy(javaHome = JavaHomeConfig(JavaHomeMode.Bundled, "8", null))
        val d = FailureDiagnosis.classify(
            IOException("Cannot run program \"java\": CreateProcess error=2, The system cannot find the file specified"),
            bundled8,
        )
        assertEquals(FailureKind.JavaRuntimeMissing, d.kind)
        assertTrue(d.explanation.contains("Java 8")) { "explanation was ${d.explanation}" }
        assertTrue(FixAction.EditConnection in d.actions)
    }

    @Test
    @DisplayName("transport downgrade / unsupported scheme -> edit connection (plain http itself is allowed)")
    fun nonHttps() {
        val downgrade = FailureDiagnosis.classify(
            LaunchAbortedException("refusing to downgrade jar transport from https to http: http://oie.lab/core.jar"), conn,
        )
        assertEquals(FailureKind.NonHttps, downgrade.kind)
        assertTrue(FixAction.EditConnection in downgrade.actions)

        val unsupported = FailureDiagnosis.classify(
            LaunchAbortedException("refusing unsupported URL scheme 'file': file:///etc/passwd"), conn,
        )
        assertEquals(FailureKind.NonHttps, unsupported.kind)
    }

    private fun allDiagnoses(): List<Diagnosis> = listOf(
        FailureDiagnosis.classify(CertificateException("server certificate pin mismatch (expected aa, got bb)"), conn),
        FailureDiagnosis.classify(SSLHandshakeException("Received fatal alert: handshake_failure"), conn),
        FailureDiagnosis.classify(LaunchAbortedException("verification failed for 'x.jar': digest mismatch (expected a, got b)"), conn),
        FailureDiagnosis.classify(LaunchAbortedException("verification failed for 'x.jar': signer is not on the pinned allow-list: CN=Evil"), conn),
        FailureDiagnosis.classify(ConnectException("Connection refused: connect"), conn),
        FailureDiagnosis.classify(IOException("GET https://oie.lab:8443/webstart failed: HTTP 404"), conn),
        FailureDiagnosis.classify(IOException("Cannot run program \"java\": CreateProcess error=2"), conn),
        FailureDiagnosis.classify(LaunchAbortedException("refusing to downgrade jar transport from https to http: http://x/core.jar"), conn),
        FailureDiagnosis.classify(RuntimeException("something unexpected"), conn),
    )
}
