/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.tls

import com.gestalt.launcher.AppPaths
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.cert.X509Certificate

/**
 * Writes a per-connection PKCS12 **truststore** holding only a connection's pinned server leaf, so the
 * leaf the launcher pinned can be handed to the launched Administrator's child JVM
 * (`-Djavax.net.ssl.trustStore`). The Administrator opens its own TLS connection to the same server, so
 * without this it would hit the very PKIX failure the launcher just resolved.
 *
 * This store holds **public certificates only** — no private keys, no secrets. Its password guards
 * integrity, not confidentiality (a truststore's contents are public), which is why a fixed,
 * non-secret password is fine and is passed openly to the child. It is a **separate** store from the
 * code-signing anchors in [com.gestalt.launcher.gate.GestaltTrust]; a server TLS leaf must never be
 * added there or it would silently become a jar-signing anchor.
 */
object ServerTrustStore {

    /** Non-secret: a truststore of public certs needs no confidentiality. Passed openly to the child. */
    const val PASSWORD = "gestalt-truststore"
    const val TYPE = "PKCS12"

    /** Location of a connection's server truststore, deterministic per connection id. */
    fun pathFor(connectionId: String, dir: Path = AppPaths.truststoresDir): Path =
        dir.resolve(sanitize(connectionId) + ".p12")

    /** Best-effort delete of a connection's truststore (on connection removal). Safe if absent. */
    fun delete(connectionId: String, dir: Path = AppPaths.truststoresDir) {
        runCatching { Files.deleteIfExists(pathFor(connectionId, dir)) }
    }

    /**
     * Write [leaf] (the pinned server certificate) into a fresh PKCS12 truststore for [connectionId] and
     * return its path. Overwrites any previous store for the connection so a re-pin propagates.
     */
    fun write(connectionId: String, leaf: X509Certificate, dir: Path = AppPaths.truststoresDir): Path {
        val store = KeyStore.getInstance(TYPE).apply {
            load(null, null)
            setCertificateEntry("server", leaf)
        }
        Files.createDirectories(dir)
        val out = pathFor(connectionId, dir)
        val tmp = out.resolveSibling(out.fileName.toString() + ".tmp")
        Files.newOutputStream(tmp).use { store.store(it, PASSWORD.toCharArray()) }
        Files.move(tmp, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        return out
    }

    private fun sanitize(s: String): String = s.replace(Regex("[^A-Za-z0-9._-]"), "_")
}
