/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.tls

import com.gestalt.launcher.model.Connection
import com.gestalt.launcher.net.LocalhostResolver
import org.apache.hc.client5.http.config.ConnectionConfig
import org.apache.hc.client5.http.config.RequestConfig
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient
import org.apache.hc.client5.http.impl.classic.HttpClients
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder
import org.apache.hc.client5.http.ssl.DefaultClientTlsStrategy
import org.apache.hc.client5.http.ssl.DefaultHostnameVerifier
import org.apache.hc.client5.http.ssl.HostnameVerificationPolicy
import org.apache.hc.core5.util.Timeout
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager

/**
 * Builds the Apache HttpClient 5 clients used for **all** https the launcher performs (JNLP fetch,
 * digest manifest, jar download, and any OCSP/CRL fetch). There are exactly two policies and no
 * trust-all path:
 *
 *  - **non-pinned** ([standardClient]): full PKIX chain validation against the platform trust store
 *    plus [DefaultHostnameVerifier] hostname verification. No exceptions.
 *  - **pinned** ([pinnedClient]): exact-cert pinning via [PinnedTrustManager] + [PinnedHostnameVerifier].
 *    A hostname mismatch is tolerated only because the exact certificate is cryptographically pinned.
 *
 * `NoopHostnameVerifier` is never used anywhere — not even on the pinned path.
 */
object HttpClientFactory {

    /**
     * The client for a connection. Server-cert **pinning is a TLS concept and applies only to https** —
     * a plain-http connection (or an https one without a pin) gets the [standardClient], which performs
     * no TLS handshake at all for http:// requests (HttpClient 5 chooses transport by URI scheme).
     */
    fun forConnection(connection: Connection): CloseableHttpClient {
        val protocols = resolveProtocols(connection)
        val ciphers = resolveCiphers(connection)
        return if (shouldPin(connection)) {
            pinnedClient(connection.normalizedPin!!, protocols, ciphers)
        } else {
            standardClient(protocols, ciphers)
        }
    }

    /**
     * Pin only when the transport is https AND a *valid* pin is configured (normalizes to 64 hex chars).
     * A malformed pin (e.g. only separators) is not treated as pinned here — it falls to [standardClient]
     * (full PKIX), and [com.gestalt.launcher.launch.LauncherCore] additionally fails the launch closed.
     */
    internal fun shouldPin(connection: Connection): Boolean = connection.isHttps && connection.normalizedPin != null

    /**
     * The enabled TLS protocols for [connection]'s own launcher fetches: the per-connection
     * `sslProtocols` override when set, else [MODERN_TLS]. (The `legacyTlsCompatibility` DH downgrade is
     * a JVM-wide system property that only makes sense for the launched child JVM — see
     * [com.gestalt.launcher.launch.ChildJvmCommandBuilder] — so it does not change the launcher's own
     * modern-JDK handshake here.)
     */
    internal fun resolveProtocols(connection: Connection): Array<String> =
        connection.sslProtocols.takeIf { it.custom && it.value.isNotBlank() }
            ?.let { splitList(it.value) } ?: MODERN_TLS

    /** The enabled cipher suites for [connection], or null for the JDK defaults (strong). */
    internal fun resolveCiphers(connection: Connection): Array<String>? =
        connection.sslCipherSuites.takeIf { it.custom && it.value.isNotBlank() }?.let { splitList(it.value) }

    private fun splitList(value: String): Array<String> =
        value.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toTypedArray()

    /**
     * Full chain + hostname verification against the platform trust store. Hostname is enforced both
     * by the TLS layer (endpoint identification) and by [DefaultHostnameVerifier] — policy [BOTH].
     */
    fun standardClient(
        protocols: Array<String> = MODERN_TLS,
        ciphers: Array<String>? = null,
    ): CloseableHttpClient =
        build(SSLContext.getDefault(), HostnameVerificationPolicy.BOTH, DefaultHostnameVerifier(), protocols, ciphers)

    /**
     * Exact-cert pinning: accept iff the server leaf SHA-256 == [pin]. Uses policy [CLIENT] so the
     * TLS layer does NOT enforce hostname; [PinnedHostnameVerifier] alone decides, returning true iff
     * the leaf matches the pin. That is what lets a hostname mismatch through — but only for the
     * exactly-pinned certificate.
     */
    fun pinnedClient(
        pin: String,
        protocols: Array<String> = MODERN_TLS,
        ciphers: Array<String>? = null,
    ): CloseableHttpClient {
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf<TrustManager>(PinnedTrustManager(pin)), null)
        return build(ctx, HostnameVerificationPolicy.CLIENT, PinnedHostnameVerifier(pin), protocols, ciphers)
    }

    private val MODERN_TLS = arrayOf("TLSv1.3", "TLSv1.2")

    /**
     * Bounded waits so a server that accepts the connection and then stalls can't hang a launch (or the
     * diagnostic) forever: the HttpClient defaults are a 3-minute connect timeout and NO socket timeout.
     * A jar download over a slow tunnel keeps flowing, so a per-read socket timeout of a minute is safe.
     */
    private val CONNECT_TIMEOUT: Timeout = Timeout.ofSeconds(15)
    private val SOCKET_TIMEOUT: Timeout = Timeout.ofSeconds(60)

    private fun build(
        sslContext: SSLContext,
        policy: HostnameVerificationPolicy,
        hostnameVerifier: HostnameVerifier,
        protocols: Array<String>,
        ciphers: Array<String>?,
    ): CloseableHttpClient {
        // DefaultClientTlsStrategy IS a TlsSocketStrategy. Protocols/ciphers come from the connection
        // (modern-only defaults when unset); a null cipher list means the JDK defaults (strong).
        val tlsSocketStrategy = DefaultClientTlsStrategy(
            sslContext,
            protocols,
            ciphers,
            null, // SSLBufferMode: default
            policy,
            hostnameVerifier,
        )
        val connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
            .setTlsSocketStrategy(tlsSocketStrategy)
            // IPv4-first for localhost (with fall-back across all resolved addresses); identical to the
            // platform default for every non-loopback host. Applies to the JNLP, digest and jar fetches.
            .setDnsResolver(LocalhostResolver.dnsResolver)
            .setDefaultConnectionConfig(
                ConnectionConfig.custom()
                    .setConnectTimeout(CONNECT_TIMEOUT)
                    .setSocketTimeout(SOCKET_TIMEOUT)
                    .build(),
            )
            .build()
        return HttpClients.custom()
            .setConnectionManager(connectionManager)
            .setDefaultRequestConfig(
                RequestConfig.custom()
                    .setConnectionRequestTimeout(CONNECT_TIMEOUT)
                    .setResponseTimeout(SOCKET_TIMEOUT)
                    .build(),
            )
            .build()
    }
}
