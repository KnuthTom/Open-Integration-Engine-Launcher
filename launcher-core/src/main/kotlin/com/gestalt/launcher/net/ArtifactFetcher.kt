/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.net

import org.apache.hc.client5.http.classic.methods.HttpGet
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient
import org.apache.hc.core5.http.io.entity.EntityUtils
import java.io.IOException
import java.net.URI

/** Fetches bytes/text over https. Behind an interface so the launch pipeline is testable offline. */
interface ArtifactFetcher {
    fun fetchBytes(uri: URI): ByteArray
    fun fetchText(uri: URI): String = String(fetchBytes(uri), Charsets.UTF_8)
}

/**
 * [ArtifactFetcher] backed by an Apache HttpClient 5 client (built by `HttpClientFactory`, so all
 * the pinning / hostname-verification policy applies here too). Enforces a content-length sanity cap.
 */
class HttpArtifactFetcher(
    private val client: CloseableHttpClient,
    private val maxBytes: Long = 256L * 1024 * 1024,
) : ArtifactFetcher, AutoCloseable {

    override fun fetchBytes(uri: URI): ByteArray =
        client.execute(HttpGet(uri)) { response ->
            val code = response.code
            if (code !in 200..299) throw IOException("GET $uri failed: HTTP $code")
            val entity = response.entity ?: throw IOException("GET $uri returned no body")
            if (entity.contentLength > maxBytes) {
                throw IOException("GET $uri body too large: ${entity.contentLength} > $maxBytes")
            }
            val bytes = EntityUtils.toByteArray(entity)
            if (bytes.size.toLong() > maxBytes) throw IOException("GET $uri body exceeded cap")
            bytes
        }

    override fun close() = client.close()
}
