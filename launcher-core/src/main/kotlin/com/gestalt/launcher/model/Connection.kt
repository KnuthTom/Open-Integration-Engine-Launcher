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
 * @property pinnedCertSha256 LEGACY: lowercase hex SHA-256 of a server leaf certificate pinned under the
 *   old per-connection trust model. Migrated into the shared trusted-certificate store on start-up
 *   (`TrustMigration`) and cleared; still honoured as a trusted fingerprint until then.
 * @property autoApproveCertificates when true, an unknown server certificate this connection meets is
 *   trusted at once (recorded in the trusted-certificate store as auto-approved) instead of stopping the
 *   launch for review. Off by default — the "Auto-approve new certificates" checkbox.
 * @property legacyTlsCompatibility opt-in, per-connection. When true the child JVM is allowed weaker
 *   TLS (e.g. `-Djdk.tls.ephemeralDHKeySize=legacy`). Default false = modern TLS only. (This is the
 *   "Use Legacy DH Settings" toggle in the form / legacy `useLegacyDHSettings`.)
 * @property icon semantic key into the design-system ConnectionIcons library (stored as a plain string
 *   so this module stays UI-framework-free; the UI resolves it to a glyph and falls back to the default).
 * @property group LEGACY: the flat group name of the old launcher. The store turns it into a top-level
 *   [Folder] and sets [folderId]; new code reads [folderId] only.
 * @property folderId the [Folder] this connection sits in; null = top level.
 * @property addedAt ISO-8601 instant the connection was created (stamped by the store on add); the
 *   "Date added" sort. Null for rows that predate the field — sorted as oldest.
 * @property lastLaunchedAt ISO-8601 instant of the last launch this launcher started for it; the
 *   "Last launched" sort and the header's "Launched 12 min ago". Null = never launched from here.
 * @property uiScale the Administrator's display scale ("1.5", "1.25", "1.0", "0.75" — `sun.java2d.uiScale`),
 *   or null for the default, 1.0 (ChildJvmCommandBuilder.DEFAULT_UI_SCALE): the Administrator at its own
 *   size, drawn 1:1; or "windows" (ChildJvmCommandBuilder.WINDOWS_SCALING): the JVM draws at 100% and
 *   Windows scales the whole window as a bitmap, the child running DPI-unaware — uniform and correctly
 *   sized on a high-DPI desktop, a little soft.
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
    val autoApproveCertificates: Boolean = false,
    val folderId: String? = null,
    val addedAt: String? = null,
    val lastLaunchedAt: String? = null,
    val uiScale: String? = null,
    /**
     * A command the launcher runs before launching to open the address's port — a port-forward: a
     * batch file that starts an AWS SSM port-forwarding session, an `ssh -L`, anything that makes the
     * address's host:port listen. The launcher waits for the port, launches, and tears the command (and
     * what it spawned) down when the last Administrator launched through it exits. Trusted local config
     * like [javaHome]; never imported from a connection file. Null: no tunnel.
     */
    val tunnelCommand: String? = null,
    /**
     * The port-forward switch: true runs [tunnelCommand] before each launch, false keeps the command but
     * does not run it. Null — the value of files written before the switch existed — means "as the
     * command dictates": on when there is one.
     */
    val tunnelEnabled: Boolean? = null,
) {
    val isPinned: Boolean get() = !pinnedCertSha256.isNullOrBlank()

    /** True when this connection opens a port-forward before launching: the switch is on (or unset) and there is a command. */
    val hasTunnel: Boolean get() = (tunnelEnabled ?: true) && !tunnelCommand.isNullOrBlank()

    /** The command to run before launching, or null when the port-forward is off or there is none. */
    val activeTunnelCommand: String? get() = tunnelCommand?.trim()?.takeIf { it.isNotEmpty() && hasTunnel }

    /** `host:port` for trust-store bookkeeping (port defaults to 443 for https / 80 for http); null if unparseable. */
    val hostPort: String? get() = runCatching {
        val u = baseUri()
        val h = u.host ?: return@runCatching null
        val p = if (u.port > 0) u.port else if (isHttps) 443 else 80
        "$h:$p"
    }.getOrNull()

    private val scheme: String? get() = runCatching { URI.create(serverUrl).scheme?.lowercase() }.getOrNull()

    /** True when the transport is TLS (https). */
    val isHttps: Boolean get() = scheme == "https"

    /** True when the transport is plain, UNENCRYPTED http — surfaced in the UI as "no transport security". */
    val isPlainHttp: Boolean get() = scheme == "http"

    fun baseUri(): URI = URI.create(serverUrl)

    /**
     * The base URL as a *directory* (trailing slash, query/fragment dropped) so that relative paths resolve
     * UNDER it. [URI.resolve] treats the last path segment of a base as a file and an absolute path as a
     * replacement: `https://h/mirth` + `/webstart` gave `https://h/webstart`, silently discarding the
     * path of every Custom connection that has one. `https://h/mirth/` + `webstart` gives
     * `https://h/mirth/webstart`. A host-only URL (`http://h:8080`) becomes `http://h:8080/`.
     */
    fun baseDirUri(): URI {
        val trimmed = serverUrl.trim().substringBefore('?').substringBefore('#').trimEnd('/')
        return URI.create("$trimmed/")
    }

    fun webstartUri(): URI = baseDirUri().resolve(webstartPath.trim().trimStart('/'))

    fun digestUri(version: String): URI =
        baseDirUri().resolve(digestPath.trim().trim('/') + "/" + version)

    /** Normalized pin for comparison (lowercase, no separators), or null. */
    val normalizedPin: String?
        get() = pinnedCertSha256?.lowercase()?.replace(":", "")?.replace(" ", "")?.takeIf { it.isNotBlank() }

    companion object {
        /** Default connection-icon semantic key (matches the design-system ConnectionIcons default). */
        const val DEFAULT_ICON_KEY = "microscope"
    }
}
