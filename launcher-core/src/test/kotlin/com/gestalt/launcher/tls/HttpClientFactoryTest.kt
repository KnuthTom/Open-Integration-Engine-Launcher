/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.tls

import com.gestalt.launcher.model.Connection
import com.gestalt.launcher.model.SslOption
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** Scheme routing: server-cert pinning is a TLS concept and must apply ONLY to https connections. */
class HttpClientFactoryTest {

    private fun conn(url: String, pin: String? = null) =
        Connection(id = "c", name = "c", serverUrl = url, pinnedCertSha256 = pin)

    @Test
    @DisplayName("pins only for https + a configured pin; never for plain http")
    fun pinsOnlyForHttps() {
        val pin = "ab".repeat(32)
        assertTrue(HttpClientFactory.shouldPin(conn("https://h:8443", pin))) { "https + pin -> pinned client" }
        assertFalse(HttpClientFactory.shouldPin(conn("http://h:8080", pin))) { "http -> no pinning (no TLS)" }
        assertFalse(HttpClientFactory.shouldPin(conn("https://h:8443", null))) { "https, no pin -> standard client" }
        assertFalse(HttpClientFactory.shouldPin(conn("http://localhost:8080", null)))
    }

    @Test
    @DisplayName("the launcher's own client honors per-connection sslProtocols/cipherSuites (else modern defaults)")
    fun honorsPerConnectionTlsSettings() {
        val custom = conn("https://h:8443").copy(
            sslProtocols = SslOption(custom = true, value = "TLSv1.2, TLSv1.1"),
            sslCipherSuites = SslOption(custom = true, value = "TLS_AES_256_GCM_SHA384"),
        )
        assertArrayEquals(arrayOf("TLSv1.2", "TLSv1.1"), HttpClientFactory.resolveProtocols(custom))
        assertArrayEquals(arrayOf("TLS_AES_256_GCM_SHA384"), HttpClientFactory.resolveCiphers(custom))

        val default = conn("https://h:8443")
        assertArrayEquals(arrayOf("TLSv1.3", "TLSv1.2"), HttpClientFactory.resolveProtocols(default))
        assertNull(HttpClientFactory.resolveCiphers(default)) { "no custom ciphers -> JDK defaults (null)" }
    }
}
