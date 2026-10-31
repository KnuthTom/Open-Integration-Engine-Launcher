/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.diag

import com.gestalt.launcher.model.Connection
import com.gestalt.launcher.model.JavaHomeMode

/**
 * A fix the user can take after a failure. Each action either opens a screen/dialog or the logs — none
 * of them weakens verification. There is deliberately **no** "ignore digest", "skip signature", or
 * "trust anyway" action: a verification failure is a hard stop, and [weakensVerification] stays `false`
 * for every member so the [Diagnosis.offersBypass] invariant (and its test) can never silently break.
 */
enum class FixAction(val weakensVerification: Boolean = false) {
    /** Open the trust-on-first-connect dialog to review the server cert and (if recognized) pin it. */
    ReviewAndPinCert,

    /** Open the connection form (URL / SSL protocols / ciphers / legacy DH / Java runtime). */
    EditConnection,

    /** Open the code-signing trust screen (posture + pinned signers). Not a per-launch bypass. */
    ReviewTrustPosture,

    /**
     * Trust the publisher(s) recorded for review on this connection and relaunch. Not a bypass: the jar's
     * integrity was proven and the decision is an explicit, audited, per-signer one — the same shape as
     * trusting a server certificate.
     */
    TrustPublisher,

    /** Open the logs folder (audit.log + launcher logs). */
    ViewLogs,

    /** No actionable fix beyond dismissing. */
    None,
}

/** The class of failure, used to pick the right explanation and fix. */
enum class FailureKind {
    ServerCertUntrusted,
    TlsHandshake,
    NonHttps,
    DigestMismatch,
    SignatureUntrusted,
    PublisherUntrusted,
    HostUnreachable,
    JnlpInvalid,
    JavaRuntimeMissing,
    /** The connection's tunnel command didn't bring the address's port up. */
    TunnelFailed,
    Unknown,
}

/**
 * A human-readable, actionable explanation of a launch/connection failure — never a raw stack trace.
 * Carries the likely cause, the next step, and a short list of safe [actions].
 */
data class Diagnosis(
    val kind: FailureKind,
    val title: String,
    val explanation: String,
    val nextStep: String,
    val actions: List<FixAction>,
) {
    /** True only if a verify-weakening action is offered. Must always be false — see [FixAction]. */
    val offersBypass: Boolean get() = actions.any { it.weakensVerification }
}

/**
 * Classifies a [Throwable] thrown by the verify+launch pipeline into a [Diagnosis]. Pure and
 * side-effect-free, so the full failure matrix can be unit-tested by feeding representative throwables.
 *
 * Verification failures (digest mismatch, untrusted signer) are classified as **hard stops** — their
 * only offered action is to view the logs (and, for signer trust, to review the global posture). No
 * branch ever produces a bypass.
 */
object FailureDiagnosis {

    fun classify(error: Throwable, connection: Connection? = null): Diagnosis {
        val chain = causeChain(error)
        val types = chain.map { it.javaClass.name }
        val text = chain.mapNotNull { it.message }.joinToString(" | ")
        val lower = text.lowercase()
        val target = targetOf(connection)

        fun anyType(vararg names: String) = types.any { t -> names.any { t.contains(it) } }
        fun msg(vararg subs: String) = subs.any { lower.contains(it) }

        // 0. The port-forward didn't come up. Its message carries the command's own output, which would
        //    otherwise trip the connect / runtime keyword rules below about the wrong thing.
        if (anyType("TunnelFailedException")) {
            return tunnelFailed(text)
        }

        // 1. Our own controlled abort (verification + URL-policy failures). Branch on the message the
        //    pipeline already composed — these are the deliberate fail-closed stops.
        if (anyType("LaunchAbortedException")) {
            return when {
                // Trust-on-first-use stop: the server cert is unknown (needs review) or was rejected.
                msg("certificate needs review", "certificate was rejected") -> serverCertUntrusted(target)
                // A jar whose integrity is proven but whose publisher is unknown: offer the review.
                msg("publisher needs review") -> publisherUntrusted(text)
                // Integrity failures: a digest mismatch, or a signature check that proves the content was
                // altered after signing (manifest edited, or an entry not covered by the signed manifest).
                msg("digest mismatch", "altered after signing", "not covered by the signed manifest", "tampered", "integrity") ->
                    digestMismatch()
                msg("non-https", "downgrade", "unsupported url scheme") -> nonHttps()
                // A missing/empty/foreign descriptor — including a digest manifest with no entry for a jar.
                msg("no digest in manifest", "did not specify a main-class", "listed no jars", "jar url host", "launch descriptor") ->
                    jnlpInvalid(target)
                // The only remaining controlled abort is a signature/publisher-trust rejection.
                else -> signatureUntrusted()
            }
        }

        // 2. Java runtime can't be spawned (ProcessBuilder). "cannot run program" is Java's wording on
        //    every platform; the createprocess form is a Windows-specific backstop.
        if (msg("cannot run program", "createprocess error=")) {
            return javaRuntimeMissing(connection)
        }

        // 3. Server certificate not trusted / changed (TLS trust failure, incl. our pin mismatch).
        val certPath = msg(
            "pin mismatch", "unable to find valid certification path", "pkix path",
            "self-signed", "self signed", "no trusted certificate", "no subject alternative",
            "certificate chain", "presented no certificate",
        )
        if (anyType("CertificateException", "CertPathValidatorException", "CertPathBuilderException", "ValidatorException") ||
            (anyType("SSLHandshakeException", "SSLException") && certPath)
        ) {
            return serverCertUntrusted(target)
        }

        // 4. Other TLS handshake problems (protocol / cipher mismatch).
        if (anyType("SSLProtocolException", "SSLHandshakeException", "SSLKeyException", "SSLException") ||
            msg("no cipher suites in common", "no appropriate protocol", "handshake_failure", "protocol_version")
        ) {
            return tlsHandshake(target)
        }

        // 5. Couldn't reach the host (connect-level). Checked before HTTP-status errors below.
        if (anyType("ConnectException", "SocketTimeoutException", "NoRouteToHostException", "UnknownHostException", "PortUnreachableException") ||
            msg("connection refused", "connect timed out", "timed out", "no route to host", "connection reset", "network is unreachable", "failed to connect")
        ) {
            return hostUnreachable(target)
        }

        // 6. Server reachable but didn't serve a valid launch descriptor. Scoped to the web-start endpoint
        //    and descriptor/manifest parse errors — a bare "HTTP 4xx" on some other artifact is NOT a
        //    descriptor problem and must not be mislabeled as one (it falls through to the honest default).
        if (anyType("SAXException", "SAXParseException", "ParserConfigurationException", "DOMException", "XMLStreamException") ||
            msg("webstart", "no body", "body too large", "malformed digest")
        ) {
            return jnlpInvalid(target)
        }

        return unknown(text)
    }

    // --- diagnosis builders -----------------------------------------------------------------------

    private fun serverCertUntrusted(target: String) = Diagnosis(
        kind = FailureKind.ServerCertUntrusted,
        title = "Server certificate isn't trusted",
        explanation = "The server at $target presented a certificate that isn't trusted — it may be " +
            "self-signed, or it may have changed since you pinned it. The launch was stopped before any code ran.",
        nextStep = "Review the certificate's fingerprint and, if you recognize it, trust it (or turn on auto-approve for this connection).",
        actions = listOf(FixAction.ReviewAndPinCert, FixAction.ViewLogs),
    )

    private fun tlsHandshake(target: String) = Diagnosis(
        kind = FailureKind.TlsHandshake,
        title = "Secure connection failed",
        explanation = "The TLS handshake with $target failed — the launcher and server couldn't agree on a " +
            "protocol version or cipher suite.",
        nextStep = "Open this connection and adjust the SSL Protocols / Cipher Suites, or enable " +
            "“Use legacy DH settings” if the server is older.",
        actions = listOf(FixAction.EditConnection, FixAction.ViewLogs),
    )

    private fun nonHttps() = Diagnosis(
        kind = FailureKind.NonHttps,
        title = "Refused an insecure transport",
        explanation = "A resource tried to load over plain http while this connection is https (a silent " +
            "downgrade), or used an unsupported URL scheme. Plain-http connections are allowed but flagged " +
            "as having no transport security; a downgrade of an https connection is not.",
        nextStep = "Open this connection and check the server URL and scheme.",
        actions = listOf(FixAction.EditConnection, FixAction.ViewLogs),
    )

    private fun digestMismatch() = Diagnosis(
        kind = FailureKind.DigestMismatch,
        title = "Downloaded file failed its integrity check",
        explanation = "A downloaded file's contents didn't match the signed digest the server published. " +
            "This can mean corruption in transit or tampering. The launch was stopped and the file discarded.",
        nextStep = "This is a hard failure with no override. Try again later; if it persists, contact the " +
            "server administrator. The affected file is named in the logs.",
        actions = listOf(FixAction.ViewLogs),
    )

    private fun signatureUntrusted() = Diagnosis(
        kind = FailureKind.SignatureUntrusted,
        title = "Jars aren't signed by a trusted signer",
        explanation = "The downloaded code isn't signed by a publisher the launcher trusts under the current " +
            "code-signing posture (or the signature didn't verify). The launch was stopped.",
        nextStep = "Review the code-signing trust posture and pinned signers. Don't lower verification unless " +
            "you genuinely trust the publisher.",
        actions = listOf(FixAction.ReviewTrustPosture, FixAction.ViewLogs),
    )

    private fun publisherUntrusted(detail: String) = Diagnosis(
        kind = FailureKind.PublisherUntrusted,
        title = "A jar is signed by a publisher you haven't approved",
        explanation = "The code is intact — it is exactly what its signer signed — but that signer isn't one the " +
            "launcher trusts yet (an in-house or third-party extension, or a publisher whose certificate changed). " +
            "The launch was stopped before any code ran.\n" + detail.substringAfter("publisher needs review before launching: ").substringBefore(" | "),
        nextStep = "If you recognise the publisher, trust it and launch again. Trust is per signing certificate and is audited.",
        actions = listOf(FixAction.TrustPublisher, FixAction.ReviewTrustPosture, FixAction.ViewLogs),
    )

    private fun tunnelFailed(text: String) = if (text.contains("Connection to destination port failed")) Diagnosis(
        kind = FailureKind.TunnelFailed,
        title = "The tunnel is up, but the server didn't answer",
        explanation = "The port-forward opened and accepted the launcher's connection, but on the server side nothing " +
            "answered the forwarded port — the session reported \"Connection to destination port failed\".",
        nextStep = "Check that Mirth / OIE is running on that server, and that the destination port in the tunnel " +
            "command is the engine's https port (the port in the address is only the local end of the forward).",
        actions = listOf(FixAction.EditConnection, FixAction.ViewLogs),
    ) else Diagnosis(
        kind = FailureKind.TunnelFailed,
        title = "The tunnel didn't open",
        explanation = "This connection's tunnel command ran, but the address's port never started listening. " +
            text.substringAfter(": ", text).trim().take(400),
        nextStep = "Check the tunnel command and its log (logs/client/…-tunnel.log): the AWS/SSH session may " +
            "need you to sign in first, or the local port in the address may not match the forward.",
        actions = listOf(FixAction.EditConnection, FixAction.ViewLogs),
    )

    private fun hostUnreachable(target: String) = Diagnosis(
        kind = FailureKind.HostUnreachable,
        title = "Couldn't reach $target",
        explanation = "The launcher couldn't open a connection to $target.",
        nextStep = "Check the host and port are correct and reachable. You may need a VPN, or an SSH/SSM " +
            "tunnel to be running, before this connection will work.",
        actions = listOf(FixAction.EditConnection, FixAction.ViewLogs),
    )

    private fun jnlpInvalid(target: String) = Diagnosis(
        kind = FailureKind.JnlpInvalid,
        title = "Server didn't return a valid launch descriptor",
        explanation = "The server at $target didn't return a valid launch descriptor at its /webstart path " +
            "(or it referenced an unexpected location).",
        nextStep = "Confirm the server URL and that the OIE/Mirth web-start endpoint is available.",
        actions = listOf(FixAction.EditConnection, FixAction.ViewLogs),
    )

    private fun javaRuntimeMissing(connection: Connection?) = Diagnosis(
        kind = FailureKind.JavaRuntimeMissing,
        title = "Java runtime not found",
        explanation = "The launcher couldn't start the Java runtime configured for this connection" +
            runtimeDetail(connection) + ".",
        nextStep = "Open this connection and set a valid Java runtime — Default (the launcher's JVM), a " +
            "bundled version, or a custom path.",
        actions = listOf(FixAction.EditConnection, FixAction.ViewLogs),
    )

    private fun unknown(text: String) = Diagnosis(
        kind = FailureKind.Unknown,
        title = "Launch failed",
        explanation = if (text.isBlank()) "The launch failed for an unexpected reason." else "The launch failed: $text",
        nextStep = "Check the logs for details.",
        actions = listOf(FixAction.ViewLogs),
    )

    // --- helpers ----------------------------------------------------------------------------------

    private fun runtimeDetail(connection: Connection?): String = when (connection?.javaHome?.mode) {
        JavaHomeMode.Bundled -> " (bundled Java ${connection.javaHome.bundledVersion})"
        JavaHomeMode.Custom -> connection.javaHome.customPath?.let { " (custom path $it)" } ?: " (custom path)"
        else -> ""
    }

    private fun targetOf(connection: Connection?): String {
        if (connection == null) return "the server"
        val uri = runCatching { connection.baseUri() }.getOrNull() ?: return connection.serverUrl
        val host = uri.host ?: return connection.serverUrl
        val port = if (uri.port > 0) ":${uri.port}" else ""
        return "$host$port"
    }

    private fun causeChain(error: Throwable, max: Int = 12): List<Throwable> {
        val out = ArrayList<Throwable>()
        var cur: Throwable? = error
        while (cur != null && out.size < max && cur !in out) {
            out += cur
            cur = cur.cause
        }
        return out
    }
}
