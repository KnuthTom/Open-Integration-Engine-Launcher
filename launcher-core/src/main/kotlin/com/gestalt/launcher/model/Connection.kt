/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.model

import kotlinx.serialization.Serializable
import java.net.URI

/**
 * A saved server connection. This is **non-secret metadata only** — it is what persists to
 * `connections.json`. There is deliberately no password field: the password lives exclusively in the
 * OS keychain via the CredentialStore, keyed by [id] + [username]. Adding a secret field here would
 * be caught at save time by [ConnectionStore]'s secret scan.
 *
 * @property serverUrl the base URL (`https://host:port`). The legacy launcher called this the address;
 *   [connectionMode] records how it was composed so the form can reopen in the right editor.
 * @property pinnedCertSha256 lowercase hex SHA-256 of the pinned server leaf certificate, or null if
 *   this connection is not pinned. A non-null pin switches TLS to exact-cert matching (which is what
 *   allows the localhost/SSM-tunnel case to work without weakening verification).
 * @property legacyTlsCompatibility opt-in, per-connection. When true the child JVM is allowed weaker
 *   TLS (e.g. `-Djdk.tls.ephemeralDHKeySize=legacy`). Default false = modern TLS only. (This is the
 *   "Use Legacy DH Settings" toggle in the form / legacy `useLegacyDHSettings`.)
 * @property icon semantic key into the design-system ConnectionIcons library (stored as a plain string
 *   so this module stays UI-framework-free; the UI resolves it to a glyph and falls back to the default).
 */
@Serializable
data class Connection(
    val id: String,
    val name: String,
    val serverUrl: String,
    val username: String? = null,
    val pinnedCertSha256: String? = null,
    val legacyTlsCompatibility: Boolean = false,
    val webstartPath: String = "/webstart",
    val digestPath: String = "/digest",
    // --- Phase 3b: full parity with the legacy launcher (all NON-SECRET metadata) ---
    val group: String? = null,
    val sortOrder: Int = 0,
    val connectionMode: ConnectionMode = ConnectionMode.Standard,
    val icon: String = DEFAULT_ICON_KEY,
    val javaHome: JavaHomeConfig = JavaHomeConfig(),
    val javaFxHome: String? = null,
    val heapSize: String? = null,
    val showJavaConsole: Boolean = false,
    val sslProtocols: SslOption = SslOption(),
    val sslCipherSuites: SslOption = SslOption(),
    /** Per-connection override of the global close-after-launch setting (defaults to inheriting it). */
    val closeAfterLaunch: CloseAfterLaunch = CloseAfterLaunch.Inherit,
) {
    val isPinned: Boolean get() = !pinnedCertSha256.isNullOrBlank()

    private val scheme: String? get() = runCatching { URI.create(serverUrl).scheme?.lowercase() }.getOrNull()

    /** True when the transport is TLS (https). */
    val isHttps: Boolean get() = scheme == "https"

    /** True when the transport is plain, UNENCRYPTED http — surfaced in the UI as "no transport security". */
    val isPlainHttp: Boolean get() = scheme == "http"

    fun baseUri(): URI = URI.create(serverUrl)

    fun webstartUri(): URI = baseUri().resolve(webstartPath)

    fun digestUri(version: String): URI =
        baseUri().resolve(digestPath.trimEnd('/') + "/" + version)

    /** Normalized pin for comparison (lowercase, no separators), or null. */
    val normalizedPin: String?
        get() = pinnedCertSha256?.lowercase()?.replace(":", "")?.replace(" ", "")?.takeIf { it.isNotBlank() }

    companion object {
        /** Default connection-icon semantic key (matches the design-system ConnectionIcons default). */
        const val DEFAULT_ICON_KEY = "microscope"
    }
}
