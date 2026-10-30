/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.diag

import com.gestalt.launcher.jnlp.JnlpParser
import com.gestalt.launcher.tls.CertTrustStatus
import com.gestalt.launcher.tls.TrustedCertStore
import com.gestalt.launcher.model.Connection
import com.gestalt.launcher.net.LocalhostResolver
import com.gestalt.launcher.tls.CertFingerprint
import com.gestalt.launcher.tls.HttpClientFactory
import com.gestalt.launcher.tls.ServerCertProbe
import org.apache.hc.client5.http.classic.methods.HttpGet
import org.apache.hc.client5.http.config.RequestConfig
import org.apache.hc.client5.http.ssl.DefaultHostnameVerifier
import org.apache.hc.core5.http.io.entity.EntityUtils
import org.apache.hc.core5.util.Timeout
import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.time.Instant
import java.time.format.DateTimeFormatter
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** PASS/FAIL (+ WARN for informational, SKIP for not-applicable) for one diagnostic check. */
enum class CheckStatus { PASS, FAIL, WARN, SKIP }

/**
 * How a TLS/transport failure was classified, so the user is told *which* problem it is (and so the
 * UI can route an untrusted cert to the trust-on-first-connect dialog rather than a dead end).
 */
enum class TlsFailureClass {
    PKIX_PATH,            // untrusted chain — self-signed / private CA / unable to find valid cert path
    HOSTNAME_MISMATCH,    // chain is fine but the cert name does not match the host
    PROTOCOL_OR_CIPHER,   // handshake_failure / no cipher suites in common / protocol_version
    REJECTED,             // the presented leaf is one the user explicitly rejected
    OTHER;

    val label: String
        get() = when (this) {
            PKIX_PATH -> "untrusted certificate chain (PKIX path)"
            HOSTNAME_MISMATCH -> "hostname mismatch"
            PROTOCOL_OR_CIPHER -> "TLS protocol / cipher mismatch"
            REJECTED -> "certificate rejected by the user"
            OTHER -> "transport error"
        }
}

/** The leaf certificate, as captured for human review (no trust decision implied). */
data class LeafCertInfo(
    val subject: String,
    val subjectCn: String?,
    val issuer: String,
    val sans: List<String>,
    val notBefore: String,
    val notAfter: String,
    val sha256: String,        // lowercase hex, no separators (pin form)
    val sha256Display: String, // AA:BB:.. for human reading
) {
    companion object {
        fun from(cert: X509Certificate): LeafCertInfo {
            val hex = CertFingerprint.sha256Hex(cert)
            return LeafCertInfo(
                subject = cert.subjectX500Principal.name,
                subjectCn = cnOf(cert.subjectX500Principal.name),
                issuer = cert.issuerX500Principal.name,
                sans = sansOf(cert),
                notBefore = cert.notBefore.toInstant().toString(),
                notAfter = cert.notAfter.toInstant().toString(),
                sha256 = hex,
                sha256Display = hex.chunked(2).joinToString(":").uppercase(),
            )
        }

        private fun cnOf(dn: String): String? =
            dn.split(',').map { it.trim() }.firstOrNull { it.startsWith("CN=", ignoreCase = true) }?.substring(3)

        private fun sansOf(cert: X509Certificate): List<String> =
            runCatching {
                cert.subjectAlternativeNames?.mapNotNull { entry ->
                    val type = entry.getOrNull(0) as? Int
                    val value = entry.getOrNull(1)?.toString()
                    when (type) {
                        2 -> "DNS:$value"
                        7 -> "IP:$value"
                        else -> value?.let { "OTHER:$it" }
                    }
                }
            }.getOrNull().orEmpty()
    }
}

/**
 * One staged check's outcome. [classification] is set only when a TLS/transport failure was classified.
 * [plain] is an explicit human-readable one-liner for cases that don't map from a [classification] (e.g. a
 * connection-refused); [plainLanguage] is what the UI shows — the explicit [plain] or one derived from the
 * classification. The raw [detail] is always kept (and written verbatim to diagnostics.txt).
 */
data class CheckResult(
    val name: String,
    val status: CheckStatus,
    val detail: String,
    val classification: TlsFailureClass? = null,
    val plain: String? = null,
) {
    /** The plain-language line for the panel: the explicit [plain], else derived from [classification]. */
    val plainLanguage: String? get() = plain ?: ConnectionDiagnostic.plainFor(classification)
}

/** The full per-connection diagnostic, human-readable in the UI and written verbatim to diagnostics.txt. */
data class ConnectionTestReport(
    val connectionName: String,
    val target: String,
    val host: String,
    val port: Int,
    val https: Boolean,
    val checks: List<CheckResult>,
    val leaf: LeafCertInfo?,
    val timestamp: String,
) {
    /** Overall pass when no check failed (WARN/SKIP do not fail the report). */
    val ok: Boolean get() = checks.none { it.status == CheckStatus.FAIL }

    /** The TLS classification, if a TLS/transport check classified a failure — drives the trust prompt. */
    val tlsFailure: TlsFailureClass? get() = checks.firstNotNullOfOrNull { it.classification }

    /** True when the only thing wrong is an untrusted/mismatched server cert the user could pin. */
    val needsTrustDecision: Boolean
        get() = tlsFailure == TlsFailureClass.PKIX_PATH || tlsFailure == TlsFailureClass.HOSTNAME_MISMATCH

    fun summaryLine(): String = "${if (ok) "PASS" else "FAIL"} — $connectionName ($target)"

    /**
     * A plain-language headline for the panel. A pin-able trust problem (untrusted self-signed leaf or a
     * name mismatch) reads as an actionable "pin to trust" line — never a bare "checks failed" — so the
     * user is pointed at the trust-on-first-connect action rather than a dead end.
     */
    fun headline(): String = when {
        ok -> "All checks passed"
        tlsFailure == TlsFailureClass.PKIX_PATH -> "Untrusted self-signed certificate — review and trust it to use this server"
        tlsFailure == TlsFailureClass.HOSTNAME_MISMATCH -> "Certificate name doesn't match the address — review and trust the cert to proceed"
        tlsFailure == TlsFailureClass.REJECTED -> "The server's certificate is one you rejected"
        tlsFailure == TlsFailureClass.PROTOCOL_OR_CIPHER -> "TLS handshake failed — the server may need legacy protocols/ciphers"
        else -> "Some checks failed"
    }

    /** Full detail for logs/diagnostics.txt. No secrets: only host/port/cert/HTTP-status detail. */
    fun toText(): String = buildString {
        appendLine("Open Integration Engine Launcher — connection diagnostic")
        appendLine("time:       $timestamp")
        appendLine("connection: $connectionName")
        appendLine("target:     $target  (host=$host port=$port https=$https)")
        appendLine("result:     ${if (ok) "PASS" else "FAIL"}")
        appendLine()
        checks.forEach { c ->
            appendLine("[${c.status}] ${c.name}")
            c.detail.lines().forEach { appendLine("    $it") }
            c.classification?.let { appendLine("    classification: ${it.name} (${it.label})") }
        }
        leaf?.let { l ->
            appendLine()
            appendLine("server leaf certificate:")
            appendLine("    subject:   ${l.subject}")
            appendLine("    issuer:    ${l.issuer}")
            appendLine("    SANs:      ${if (l.sans.isEmpty()) "(none)" else l.sans.joinToString(", ")}")
            appendLine("    validity:  ${l.notBefore} .. ${l.notAfter}")
            appendLine("    SHA-256:   ${l.sha256Display}")
        }
    }
}

/**
 * Runs the four-step Test-connection diagnostic for a [Connection] and reports PASS/FAIL with the exact
 * detail. It deliberately reuses the SAME transport the real launch uses ([HttpClientFactory]) so its
 * verdict matches what an actual launch would do, and reproduces the handshake with the connection's own
 * TLS settings. It performs NO trust decision of its own and never weakens verification — an untrusted
 * leaf is surfaced (with its SHA-256) so the caller can route to the managed trust-on-first-connect flow.
 */
object ConnectionDiagnostic {

    fun run(
        connection: Connection,
        timeoutMs: Int = 8_000,
        now: Instant = Instant.now(),
        trustedCerts: TrustedCertStore = TrustedCertStore.shared,
    ): ConnectionTestReport {
        // Parse defensively: a malformed serverUrl must yield a clean FAIL report, never a thrown exception
        // (the diagnostic always returns a report — the CLI and UI both rely on that).
        val uri = runCatching { connection.baseUri() }.getOrNull()
        val host = uri?.host?.takeIf { it.isNotBlank() }
        val port = when {
            uri == null -> 0
            uri.port != -1 -> uri.port
            connection.isHttps -> 443
            else -> 80
        }
        val checks = ArrayList<CheckResult>()
        var leaf: LeafCertInfo? = null

        if (uri == null || host == null) {
            checks += CheckResult("DNS + TCP", CheckStatus.FAIL, "the connection URL has no host: '${connection.serverUrl}'")
            return report(connection, host ?: "", port, checks, leaf, now)
        }

        try {
            // --- 1. DNS + TCP -----------------------------------------------------------------------
            val tcp = checkDnsAndTcp(host, port, timeoutMs)
            checks += tcp
            if (tcp.status == CheckStatus.PASS) {
                // --- 2. TLS handshake + leaf capture + validation (https only) ----------------------
                if (connection.isHttps) {
                    val (tlsCheck, capturedLeaf) = checkTls(connection, host, port, timeoutMs, trustedCerts)
                    checks += tlsCheck
                    leaf = capturedLeaf
                } else {
                    checks += CheckResult("TLS handshake", CheckStatus.SKIP, "plain http — no transport security (no TLS handshake)")
                }

                // --- 3. JNLP fetch (<baseUrl>/webstart) ---------------------------------------------
                val (jnlpCheck, body) = runCatching { checkHttp(connection, connection.webstartUri(), "JNLP (/webstart)", timeoutMs, trustedCerts) }
                    .getOrElse { CheckResult("JNLP (/webstart)", CheckStatus.FAIL, "could not run the /webstart request: ${shortMessage(it)}", classify(it)) to null }
                checks += jnlpCheck

                // --- 4. One digest entry ------------------------------------------------------------
                checks += checkDigest(connection, body, timeoutMs, trustedCerts)
            }
        } catch (t: Throwable) {
            // The diagnostic must always produce a report; an unexpected failure becomes a FAIL line.
            checks += CheckResult("Diagnostic", CheckStatus.FAIL, "unexpected error while testing: ${shortMessage(t)}", classify(t))
        }

        return report(connection, host, port, checks, leaf, now)
    }

    // --- step 1 -------------------------------------------------------------------------------------

    private fun checkDnsAndTcp(host: String, port: Int, timeoutMs: Int): CheckResult {
        val addresses = try {
            // IPv4-first for localhost (the IPv4-only SSM forward), and EVERY resolved address is tried —
            // so a refused IPv6 ::1 falls back to 127.0.0.1, exactly as the real launcher fetch now does.
            LocalhostResolver.resolveOrdered(host).toList()
        } catch (e: UnknownHostException) {
            return CheckResult(
                "DNS + TCP", CheckStatus.FAIL, "host not found — DNS could not resolve '$host'",
                plain = "Host not found — the address '$host' couldn't be resolved. Check it's spelled correctly.",
            )
        }
        val tried = describeAddresses(addresses)
        // Report the failure of the FIRST (most-preferred) address we tried — for loopback that's the
        // IPv4 address the user actually cares about — rather than whichever address happened to be last.
        var primaryError: Exception? = null
        for (addr in addresses) {
            try {
                Socket().use { s -> s.connect(InetSocketAddress(addr, port), timeoutMs) }
                return CheckResult("DNS + TCP", CheckStatus.PASS, "$host resolved to $tried; TCP connect to ${addr.hostAddress}:$port OK")
            } catch (e: Exception) {
                if (primaryError == null) primaryError = e
            }
        }
        return when (primaryError) {
            is ConnectException -> CheckResult(
                "DNS + TCP", CheckStatus.FAIL, "connection refused by $host:$port (tried $tried)",
                plain = "Connection refused — the server isn't accepting connections (tried $tried). Check the port-forward / server is up.",
            )
            is SocketTimeoutException -> CheckResult(
                "DNS + TCP", CheckStatus.FAIL, "timed out connecting to $host:$port (tried $tried)",
                plain = "Timed out reaching $host:$port (tried $tried). Check the network, VPN, or tunnel.",
            )
            else -> CheckResult(
                "DNS + TCP", CheckStatus.FAIL, "cannot reach $host:$port — ${shortMessage(primaryError ?: ConnectException("unreachable"))} (tried $tried)",
                plain = "Couldn't reach $host:$port (tried $tried). Check the server is up and reachable.",
            )
        }
    }

    /** Describe resolved addresses for a human: "IPv4 127.0.0.1 and IPv6 ::1". */
    private fun describeAddresses(addresses: List<InetAddress>): String =
        if (addresses.isEmpty()) "(no address)" else addresses.joinToString(" and ") {
            "${if (it is Inet4Address) "IPv4" else "IPv6"} ${it.hostAddress}"
        }

    // --- step 2 -------------------------------------------------------------------------------------

    private fun checkTls(connection: Connection, host: String, port: Int, timeoutMs: Int, trustedCerts: TrustedCertStore): Pair<CheckResult, LeafCertInfo?> {
        val protocols = HttpClientFactory.resolveProtocols(connection)
        val ciphers = HttpClientFactory.resolveCiphers(connection)

        val capture = try {
            ServerCertProbe.handshake(host, port, protocols, ciphers, timeoutMs)
        } catch (e: Exception) {
            // No leaf was obtained — almost always a protocol/cipher problem (or a non-TLS port).
            val cls = classify(e)
            return CheckResult(
                "TLS handshake", CheckStatus.FAIL,
                "TLS handshake failed before a certificate was presented: ${shortMessage(e)}", cls,
            ) to null
        }

        val cert = capture.leaf
            ?: return CheckResult("TLS handshake", CheckStatus.FAIL, "server presented no certificate") to null
        val info = runCatching { LeafCertInfo.from(cert) }.getOrElse {
            return CheckResult("TLS handshake", CheckStatus.FAIL, "the server certificate could not be parsed: ${shortMessage(it)}") to null
        }
        val negotiated = "negotiated ${capture.protocol ?: "?"} / ${capture.cipher ?: "?"}"

        val systemTrusted = systemTrust(capture.chain)
        val hostnameOk = runCatching { DefaultHostnameVerifier().verify(host, cert) }.isSuccess
        // The user's decision for this exact leaf (a not-yet-migrated legacy pin counts as trusted).
        val decision = trustedCerts.statusOf(cert)
            ?: if (connection.normalizedPin != null && info.sha256 == connection.normalizedPin) CertTrustStatus.TRUSTED else null
        val trustedEntry = trustedCerts.get(info.sha256)
        val expiredNote = if (decision == CertTrustStatus.TRUSTED && trustedEntry?.isExpired() == true) " (EXPIRED — still trusted, review it)" else ""

        val detail = buildString {
            appendLine(negotiated)
            appendLine("leaf CN: ${info.subjectCn ?: "(none)"}   issuer: ${cnOfDn(info.issuer) ?: info.issuer}")
            appendLine("SANs: ${if (info.sans.isEmpty()) "(none)" else info.sans.joinToString(", ")}")
            appendLine("SHA-256: ${info.sha256Display}")
            appendLine("validity: ${info.notBefore} .. ${info.notAfter}")
            appendLine("trusted by system CAs: ${if (systemTrusted.first) "yes" else "no"}${systemTrusted.second?.let { " ($it)" } ?: ""}")
            appendLine("hostname matches '$host': ${if (hostnameOk) "yes" else "no"}")
            append("your decision: ${when (decision) { CertTrustStatus.TRUSTED -> "trusted$expiredNote"; CertTrustStatus.REJECTED -> "REJECTED"; CertTrustStatus.PENDING -> "awaiting review"; null -> "none yet" }}")
        }.trim()

        return when {
            decision == CertTrustStatus.REJECTED ->
                CheckResult("TLS handshake", CheckStatus.FAIL,
                    "$detail\nyou rejected this certificate — failing closed until that decision is changed", TlsFailureClass.REJECTED) to info
            decision == CertTrustStatus.TRUSTED ->
                CheckResult("TLS handshake", if (expiredNote.isEmpty()) CheckStatus.PASS else CheckStatus.WARN,
                    "$detail\naccepted by your trust decision (exact certificate; hostname verification not applied)$expiredNote") to info
            systemTrusted.first && hostnameOk ->
                CheckResult("TLS handshake", CheckStatus.PASS,
                    "$detail\nverified by the system trust store and hostname (no decision needed)") to info
            !systemTrusted.first ->
                CheckResult("TLS handshake", CheckStatus.FAIL,
                    "$detail\nthe certificate chain is not trusted by the system CAs — review the SHA-256 and trust it to proceed",
                    TlsFailureClass.PKIX_PATH) to info
            else ->
                CheckResult("TLS handshake", CheckStatus.FAIL,
                    "$detail\nthe certificate is trusted but its name does not match '$host' — trust this exact certificate to proceed",
                    TlsFailureClass.HOSTNAME_MISMATCH) to info
        }
    }

    // --- step 3 -------------------------------------------------------------------------------------

    /** A GET request bounded by [timeoutMs] for connect + response, so a stalled server can't hang the probe. */
    private fun boundedGet(uri: java.net.URI, timeoutMs: Int): HttpGet = HttpGet(uri).apply {
        val t = Timeout.ofMilliseconds(timeoutMs.toLong())
        config = RequestConfig.custom()
            .setConnectTimeout(t)
            .setConnectionRequestTimeout(t)
            .setResponseTimeout(t)
            .build()
    }

    /** Returns the check plus the fetched body bytes (null on failure) so step 4 can read the version. */
    private fun checkHttp(connection: Connection, uri: java.net.URI, label: String, timeoutMs: Int, trustedCerts: TrustedCertStore): Pair<CheckResult, ByteArray?> =
        try {
            HttpClientFactory.forConnection(connection, trustedCerts).use { client ->
                client.execute(boundedGet(uri, timeoutMs)) { response ->
                    val code = response.code
                    val bytes = response.entity?.let { EntityUtils.toByteArray(it) } ?: ByteArray(0)
                    val ok = code in 200..299
                    CheckResult(
                        label,
                        if (ok) CheckStatus.PASS else CheckStatus.FAIL,
                        "GET $uri -> HTTP $code, ${bytes.size} bytes",
                    ) to (if (ok) bytes else null)
                }
            }
        } catch (e: Exception) {
            val cls = classify(e)
            CheckResult(label, CheckStatus.FAIL, "GET $uri failed: ${shortMessage(e)}", cls) to null
        }

    // --- step 4 -------------------------------------------------------------------------------------

    private fun checkDigest(connection: Connection, jnlpBytes: ByteArray?, timeoutMs: Int, trustedCerts: TrustedCertStore): CheckResult {
        if (jnlpBytes == null) {
            return CheckResult("Digest entry", CheckStatus.SKIP, "skipped — the JNLP fetch above did not succeed")
        }
        val jnlp = runCatching { JnlpParser.parse(String(jnlpBytes, Charsets.UTF_8)) }.getOrNull()
            ?: return CheckResult("Digest entry", CheckStatus.WARN, "could not parse the JNLP to locate a version for the /digest probe")
        val inlineDigests = jnlp.jars.isNotEmpty() && jnlp.jars.all { !it.sha256.isNullOrBlank() }
        val version = jnlp.version ?: "current"
        val uri = connection.digestUri(version)
        return try {
            HttpClientFactory.forConnection(connection, trustedCerts).use { client ->
                client.execute(boundedGet(uri, timeoutMs)) { response ->
                    val code = response.code
                    val ok = code in 200..299
                    when {
                        ok -> CheckResult("Digest entry", CheckStatus.PASS, "GET $uri -> HTTP $code (external digest manifest present)")
                        inlineDigests -> CheckResult("Digest entry", CheckStatus.WARN,
                            "GET $uri -> HTTP $code; not required — this server publishes per-jar SHA-256 inline in the JNLP")
                        else -> CheckResult("Digest entry", CheckStatus.FAIL,
                            "GET $uri -> HTTP $code and the JNLP carries no inline digests — integrity source is missing")
                    }
                }
            }
        } catch (e: Exception) {
            if (inlineDigests) {
                CheckResult("Digest entry", CheckStatus.WARN, "GET $uri failed (${shortMessage(e)}); not required — inline JNLP digests present")
            } else {
                CheckResult("Digest entry", CheckStatus.FAIL, "GET $uri failed: ${shortMessage(e)}", classify(e))
            }
        }
    }

    // --- helpers ------------------------------------------------------------------------------------

    private fun report(c: Connection, host: String, port: Int, checks: List<CheckResult>, leaf: LeafCertInfo?, now: Instant) =
        ConnectionTestReport(
            connectionName = c.name,
            target = c.serverUrl,
            host = host,
            port = port,
            https = c.isHttps,
            checks = checks,
            leaf = leaf,
            timestamp = DateTimeFormatter.ISO_INSTANT.format(now),
        )

    /** Validate [chain] against the platform default trust store. Returns (trusted, failureMessage?). */
    private fun systemTrust(chain: List<X509Certificate>): Pair<Boolean, String?> {
        if (chain.isEmpty()) return false to "no chain presented"
        return try {
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(null as KeyStore?)
            val tm = tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
            // authType must be a non-empty key-exchange token, not the cert's key algorithm ("EC" is not a
            // valid authType). PKIX path building — the actual trust decision — does not depend on it.
            tm.checkServerTrusted(chain.toTypedArray(), "GENERIC")
            true to null
        } catch (e: Exception) {
            false to shortMessage(e)
        }
    }

    /**
     * Plain-language one-liner for a classified TLS failure — what the panel shows. The raw exception
     * detail is preserved on the check (and in diagnostics.txt); this is only the human-readable summary.
     */
    internal fun plainFor(cls: TlsFailureClass?): String? = when (cls) {
        TlsFailureClass.PKIX_PATH -> "Untrusted self-signed certificate — review and trust it to use this server."
        TlsFailureClass.HOSTNAME_MISMATCH -> "Certificate name doesn't match the address — review and trust the cert to proceed."
        TlsFailureClass.PROTOCOL_OR_CIPHER -> "TLS handshake failed — the server may need legacy protocols/ciphers (Security section)."
        TlsFailureClass.REJECTED -> "The server's certificate is one you rejected — failing closed."
        TlsFailureClass.OTHER, null -> null
    }

    /** Classify a TLS/transport throwable into the bucket the user needs to see. */
    internal fun classify(t: Throwable): TlsFailureClass {
        val text = generateSequence(t) { it.cause }
            .joinToString(" | ") { "${it.javaClass.simpleName}: ${it.message}" }
            .lowercase()
        return when {
            "no appropriate protocol" in text || "protocol_version" in text || "no cipher suites in common" in text ||
                "handshake_failure" in text || "unsupported" in text && "protocol" in text ->
                TlsFailureClass.PROTOCOL_OR_CIPHER
            "rejected by the user" in text -> TlsFailureClass.REJECTED
            "unable to find valid certification path" in text || "pkix path" in text ||
                "self-signed" in text || "self signed" in text || "certpathbuilderexception" in text ||
                "trustanchor" in text -> TlsFailureClass.PKIX_PATH
            "no subject alternative" in text || "does not match" in text || "doesn't match" in text ||
                "no name matching" in text || "hostname" in text -> TlsFailureClass.HOSTNAME_MISMATCH
            else -> TlsFailureClass.OTHER
        }
    }

    private fun cnOfDn(dn: String): String? =
        dn.split(',').map { it.trim() }.firstOrNull { it.startsWith("CN=", true) }?.substring(3)

    private fun shortMessage(t: Throwable): String =
        "${t.javaClass.simpleName}: ${t.message ?: "(no message)"}"
}
