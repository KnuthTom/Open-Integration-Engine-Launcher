/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.tls

import com.gestalt.launcher.testutil.TestArtifacts
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import org.apache.hc.client5.http.classic.methods.HttpGet
import org.apache.hc.client5.http.impl.classic.HttpClients
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder
import org.apache.hc.client5.http.ssl.DefaultClientTlsStrategy
import org.apache.hc.client5.http.ssl.DefaultHostnameVerifier
import org.apache.hc.client5.http.ssl.HostnameVerificationPolicy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URI
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/**
 * Real TLS behavior against a local HTTPS server whose certificate is valid but issued for the WRONG
 * host (`wrong-host.example.com`), while we connect to `localhost`:
 *
 *  - a non-pinned client FAILS (untrusted + hostname mismatch),
 *  - hostname verification specifically FAILS even when the chain is trusted,
 *  - and the connection SUCCEEDS only when that exact certificate is pinned (a wrong pin still fails).
 *
 * `NoopHostnameVerifier` is never used anywhere in production code; this proves the policy.
 */
class HostnameVerificationTest {

    private lateinit var server: HttpsServer
    private lateinit var serverCert: X509Certificate
    private var port = 0

    @BeforeEach
    fun start() {
        val (kp, cert) = TestArtifacts.serverCert(commonName = "wrong-host.example.com", sanDns = "wrong-host.example.com")
        serverCert = cert
        val ks = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("server", kp.private, "x".toCharArray(), arrayOf(cert))
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, "x".toCharArray()) }
        val ctx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        server = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            httpsConfigurator = HttpsConfigurator(ctx)
            createContext("/") { ex ->
                val body = "ok".toByteArray()
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
            start()
        }
        port = server.address.port
    }

    @AfterEach
    fun stop() = server.stop(0)

    private fun url() = URI.create("https://localhost:$port/")

    @Test
    @DisplayName("non-pinned connection to a wrong-host cert FAILS")
    fun nonPinnedFails() {
        HttpClientFactory.standardClient().use { client ->
            assertThrows(Exception::class.java) { client.execute(HttpGet(url())) { it.code } }
        }
    }

    @Test
    @DisplayName("hostname verification rejects even when the chain is trusted")
    fun hostnameRejectedEvenWhenChainTrusted() {
        // Trust the exact server cert so the chain is valid, but still verify hostname (policy BOTH).
        val trustKs = KeyStore.getInstance("PKCS12").apply { load(null, null); setCertificateEntry("ca", serverCert) }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trustKs) }
        val ctx = SSLContext.getInstance("TLS").apply { init(null, tmf.trustManagers, null) }
        val tls = DefaultClientTlsStrategy(
            ctx, arrayOf("TLSv1.3", "TLSv1.2"), null, null,
            HostnameVerificationPolicy.BOTH, DefaultHostnameVerifier(),
        )
        val cm = PoolingHttpClientConnectionManagerBuilder.create().setTlsSocketStrategy(tls).build()
        HttpClients.custom().setConnectionManager(cm).build().use { client ->
            assertThrows(Exception::class.java) { client.execute(HttpGet(url())) { it.code } }
        }
    }

    @Test
    @DisplayName("exact-cert pin SUCCEEDS despite the hostname mismatch")
    fun exactPinSucceeds() {
        val pin = CertFingerprint.sha256Hex(serverCert)
        HttpClientFactory.pinnedClient(pin).use { client ->
            val code = client.execute(HttpGet(url())) { it.code }
            assertEquals(200, code)
        }
    }

    @Test
    @DisplayName("trust-aware client: a trusted fingerprint SUCCEEDS despite the hostname mismatch")
    fun trustAwareTrustedSucceeds() {
        val fp = CertFingerprint.sha256Hex(serverCert)
        HttpClientFactory.trustAwareClient(trusted = setOf(fp), rejected = emptySet()).use { client ->
            assertEquals(200, client.execute(HttpGet(url())) { it.code })
        }
    }

    @Test
    @DisplayName("trust-aware client: an unknown self-signed cert FAILS and the failure is classified as reviewable")
    fun trustAwareUnknownIsReviewable() {
        HttpClientFactory.trustAwareClient(trusted = emptySet(), rejected = emptySet()).use { client ->
            val ex = assertThrows(Exception::class.java) { client.execute(HttpGet(url())) { it.code } }
            assertEquals(ServerTrustFailure.Kind.UNKNOWN, ServerTrustFailure.kindOf(ex)) { "cause chain: ${generateSequence(ex as Throwable) { it.cause }.map { it.javaClass.simpleName }.toList()}" }
        }
    }

    @Test
    @DisplayName("trust-aware client: a rejected fingerprint FAILS as rejected")
    fun trustAwareRejectedFails() {
        val fp = CertFingerprint.sha256Hex(serverCert)
        HttpClientFactory.trustAwareClient(trusted = emptySet(), rejected = setOf(fp)).use { client ->
            val ex = assertThrows(Exception::class.java) { client.execute(HttpGet(url())) { it.code } }
            assertEquals(ServerTrustFailure.Kind.REJECTED, ServerTrustFailure.kindOf(ex))
        }
    }

    @Test
    @DisplayName("a wrong pin FAILS")
    fun wrongPinFails() {
        val wrongPin = "ab".repeat(32) // 64 hex chars, not the server's
        HttpClientFactory.pinnedClient(wrongPin).use { client ->
            assertThrows(Exception::class.java) { client.execute(HttpGet(url())) { it.code } }
        }
    }
}
