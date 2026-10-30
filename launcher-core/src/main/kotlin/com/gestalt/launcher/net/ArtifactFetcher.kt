/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.net

import org.apache.hc.client5.http.classic.methods.HttpGet
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient
import org.apache.hc.client5.http.protocol.HttpClientContext
import org.apache.hc.core5.http.io.entity.EntityUtils
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI

/** Fetches bytes/text over https. Behind an interface so the launch pipeline is testable offline. */
interface ArtifactFetcher {
    fun fetchBytes(uri: URI): ByteArray
    fun fetchText(uri: URI): String = String(fetchBytes(uri), Charsets.UTF_8)

    /**
     * The remote address the most recent successful fetch was served from, when the transport can tell
     * (null when it can't — a test fake, or before the first fetch). For a loopback connection this is
     * which loopback literal the port-forward actually answers on, 127.0.0.1 or ::1, so the launched
     * client can be pointed at the same one rather than left to guess.
     */
    val lastRemoteAddress: InetAddress? get() = null
}

/**
 * [ArtifactFetcher] backed by an Apache HttpClient 5 client (built by `HttpClientFactory`, so all
 * the pinning / hostname-verification policy applies here too). Enforces a content-length sanity cap.
 */
class HttpArtifactFetcher(
    private val client: CloseableHttpClient,
    private val maxBytes: Long = 256L * 1024 * 1024,
) : ArtifactFetcher, AutoCloseable {

    @Volatile private var remote: InetAddress? = null

    /** Whichever address the latest completed fetch used (fetches run in parallel; any of them will do). */
    override val lastRemoteAddress: InetAddress? get() = remote

    override fun fetchBytes(uri: URI): ByteArray {
        val context = HttpClientContext.create()
        val bytes = client.execute(HttpGet(uri), context) { response ->
            val code = response.code
            if (code !in 200..299) throw IOException("GET $uri failed: HTTP $code")
            val entity = response.entity ?: throw IOException("GET $uri returned no body")
            if (entity.contentLength > maxBytes) {
                throw IOException("GET $uri body too large: ${entity.contentLength} > $maxBytes")
            }
            val body = EntityUtils.toByteArray(entity)
            if (body.size.toLong() > maxBytes) throw IOException("GET $uri body exceeded cap")
            body
        }
        // The connection the response came over (the pool records it per exchange); which resolved
        // address it reached is what a loopback launch needs to know.
        (context.endpointDetails?.remoteAddress as? InetSocketAddress)?.address?.let { remote = it }
        return bytes
    }

    override fun close() = client.close()
}
