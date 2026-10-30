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
 * digest manifest, jar download, and any OCSP/CRL fetch). The product path is ONE policy — the
 * [TrustedOrPkixTrustManager] rule: a user-rejected leaf fails, a user-trusted leaf is accepted by exact
 * fingerprint, anything else must pass full PKIX validation against the platform trust store plus the
 * standard hostname check. There is no trust-all path and `NoopHostnameVerifier` is never used.
 *
 * [standardClient] (PKIX only) and [pinnedClient] (exact pin only) remain as the two pure building
 * blocks, used directly by tests and tooling.
 */
object HttpClientFactory {

    /**
     * The client for a connection: [trustAwareClient] over the shared [TrustedCertStore] (a legacy
     * per-connection pin, if one hasn't been migrated yet, counts as trusted). For a plain-http
     * connection no TLS handshake happens at all (HttpClient 5 chooses transport by URI scheme).
     */
    fun forConnection(connection: Connection, trustedCerts: TrustedCertStore = TrustedCertStore.shared): CloseableHttpClient {
        val trusted = trustedCerts.trustedFingerprints() + listOfNotNull(connection.normalizedPin)
        return trustAwareClient(trusted, trustedCerts.rejectedFingerprints(), resolveProtocols(connection), resolveCiphers(connection))
    }

    /**
     * The product policy: rejected fingerprints fail, trusted fingerprints pass by exact match (hostname
     * not consulted), everything else is full PKIX + hostname. Hostname policy is CLIENT because the
     * TLS layer's own endpoint identification would veto a trusted self-signed leaf on a port-forward;
     * [TrustedOrDefaultHostnameVerifier] applies the standard check to every non-trusted leaf instead.
     */
    fun trustAwareClient(
        trusted: Set<String>,
        rejected: Set<String>,
        protocols: Array<String> = MODERN_TLS,
        ciphers: Array<String>? = null,
    ): CloseableHttpClient {
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf<TrustManager>(TrustedOrPkixTrustManager(trusted, rejected)), null)
        return build(ctx, HostnameVerificationPolicy.CLIENT, TrustedOrDefaultHostnameVerifier(trusted), protocols, ciphers, propagateTrustFailures = true)
    }

    /** True when the connection is https AND carries a (legacy, not yet migrated) 64-hex pin. */
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
        propagateTrustFailures: Boolean = false,
    ): CloseableHttpClient {
        // DefaultClientTlsStrategy IS a TlsSocketStrategy. Protocols/ciphers come from the connection
        // (modern-only defaults when unset); a null cipher list means the JDK defaults (strong).
        val defaultStrategy = DefaultClientTlsStrategy(
            sslContext,
            protocols,
            ciphers,
            null, // SSLBufferMode: default
            policy,
            hostnameVerifier,
        )
        // A trust failure must stop the connect loop (see ServerTrustFailureSignal), or on a dual-stack
        // localhost it is masked by the next address's "connection refused".
        val tlsSocketStrategy = if (propagateTrustFailures) TrustFailurePropagatingTlsStrategy(defaultStrategy) else defaultStrategy
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
