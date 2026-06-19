/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.tls

import com.gestalt.launcher.net.HttpArtifactFetcher
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URI

/**
 * Proves the standard client really does PLAIN http for an `http://` URL — no TLS handshake is attempted
 * (a TLS handshake against this plain-HTTP server would fail). This is the transport half of Issue 2:
 * an http connection fetches its JNLP/jars over HTTP.
 */
class PlainHttpFetchTest {

    @Test
    @DisplayName("standardClient fetches an http:// URL over plain HTTP (no TLS handshake)")
    fun fetchesPlainHttp() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/webstart") { exchange ->
            val body = "<jnlp/>".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val port = server.address.port
            HttpArtifactFetcher(HttpClientFactory.standardClient()).use { fetcher ->
                assertEquals("<jnlp/>", fetcher.fetchText(URI.create("http://127.0.0.1:$port/webstart")))
            }
        } finally {
            server.stop(0)
        }
    }
}
