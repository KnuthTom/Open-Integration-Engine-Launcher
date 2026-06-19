/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.tls

import com.gestalt.launcher.net.LocalhostResolver
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Retrieves a server's certificate chain so a human can review the leaf fingerprint and decide whether
 * to pin it, and so the Test-connection diagnostic can report and classify the handshake.
 *
 * This is **not** a trust decision. The captured certificate is only surfaced to the user (its SHA-256
 * is shown for confirmation); pinning then happens as a separate, explicit, audited action via
 * [ServerCertPinning]. The capturing trust manager below is private and is never wired into request
 * execution — real requests go exclusively through [HttpClientFactory], which has no trust-all path.
 */
object ServerCertProbe {

    private val MODERN_TLS = arrayOf("TLSv1.3", "TLSv1.2")

    /** What a capture handshake observed: the presented chain (leaf first) and what was negotiated. */
    data class Handshake(
        val chain: List<X509Certificate>,
        val protocol: String?,
        val cipher: String?,
    ) {
        val leaf: X509Certificate? get() = chain.firstOrNull()
    }

    /** The leaf certificate the server presents (modern TLS), for the trust-on-first-connect dialog. */
    fun fetchLeaf(host: String, port: Int, timeoutMs: Int = 10_000): X509Certificate =
        handshake(host, port, MODERN_TLS, null, timeoutMs).leaf
            ?: error("no certificate presented by $host:$port")

    /**
     * Open a TLS connection trusting NOTHING, capturing the presented chain plus the negotiated protocol
     * and cipher. [protocols]/[ciphers] mirror the connection's own settings so the diagnostic reproduces
     * the real handshake (a protocol/cipher mismatch surfaces here as a handshake exception). Makes no
     * trust decision: the capturing trust manager accepts any chain so the certificate can be inspected.
     *
     * Addresses are tried IPv4-first for a loopback host (the SSM-forward case); the IPv4-first ordering is
     * loopback-only. Connect-level fall-back across every resolved address applies to ALL hosts (so a refused
     * `::1` retries `127.0.0.1`, and a multi-homed remote host's next A/AAAA record is tried) — this only
     * affects which reachable endpoint answers, never trust. A handshake or certificate error is NOT retried
     * (it's a TLS fact about the reachable endpoint, not connectivity).
     */
    fun handshake(
        host: String,
        port: Int,
        protocols: Array<String> = MODERN_TLS,
        ciphers: Array<String>? = null,
        timeoutMs: Int = 10_000,
    ): Handshake {
        val addresses = LocalhostResolver.resolveOrdered(host)
        var lastConnectError: IOException? = null
        for (address in addresses) {
            val captor = Captor()
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf<TrustManager>(captor), null)
            val socket = ctx.socketFactory.createSocket() as SSLSocket
            try {
                socket.enabledProtocols = protocols
                if (ciphers != null && ciphers.isNotEmpty()) socket.enabledCipherSuites = ciphers
                try {
                    socket.connect(InetSocketAddress(address, port), timeoutMs)
                } catch (e: IOException) {
                    lastConnectError = e // connect refused/timed out on this address — try the next
                    continue
                }
                socket.soTimeout = timeoutMs
                socket.startHandshake()
                val session = socket.session
                return Handshake(captor.chain, session?.protocol, session?.cipherSuite)
            } finally {
                runCatching { socket.close() }
            }
        }
        throw lastConnectError ?: ConnectException("could not connect to $host:$port")
    }

    /** Captures the presented chain without making any trust decision. */
    private class Captor : X509TrustManager {
        var chain: List<X509Certificate> = emptyList()
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            this.chain = chain?.toList() ?: emptyList()
        }
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
