/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.model

import java.net.URI

/**
 * URL helpers shared by the legacy importer and the connection form, kept in one tested place.
 *
 * The base URL ([Connection.serverUrl]) is canonical; [deriveMode] only classifies how it should be
 * *edited*. The launch pipeline always uses the full URL, never the mode.
 */
object ConnectionUrl {

    private const val WEBSTART = "/webstart"

    /**
     * Strip a trailing `/webstart` (or `/webstart/`) suffix and any trailing slash, preserving the rest
     * of the URL. A mid-path `/webstart/` (e.g. `…/apps/webstart/extra`) is left untouched — only the
     * exact suffix is removed, and it is never doubled.
     */
    fun stripWebstart(address: String): String {
        val trimmed = address.trim().trimEnd('/')
        return if (trimmed.endsWith(WEBSTART, ignoreCase = true)) {
            trimmed.substring(0, trimmed.length - WEBSTART.length)
        } else {
            trimmed
        }
    }

    /**
     * Classify a base URL into an edit [ConnectionMode]. Both `https` and `http` are first-class (plain
     * http is allowed for servers like an OIE appliance on `http://localhost:8080`; the insecurity is
     * surfaced separately, not by forcing Custom mode):
     *  - a non-web scheme (not http/https) → [ConnectionMode.Custom];
     *  - a non-root path → [ConnectionMode.Custom];
     *  - no explicit port → [ConnectionMode.Custom];
     *  - `localhost`/`127.0.0.1` with a port → [ConnectionMode.LocalhostPortForward];
     *  - otherwise `host:port` → [ConnectionMode.Standard].
     */
    fun deriveMode(baseUrl: String): ConnectionMode {
        val uri = runCatching { URI.create(baseUrl.trim()) }.getOrNull() ?: return ConnectionMode.Custom
        val scheme = uri.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") return ConnectionMode.Custom
        val path = uri.path.orEmpty()
        if (path.isNotEmpty() && path != "/") return ConnectionMode.Custom
        if (uri.port == -1) return ConnectionMode.Custom
        val host = uri.host?.lowercase()
        return if (host == "localhost" || host == "127.0.0.1") {
            ConnectionMode.LocalhostPortForward
        } else {
            ConnectionMode.Standard
        }
    }

    /**
     * What a typed/pasted address resolves to, for the smart single-field connection form. This is a UI
     * convenience: the launch pipeline still uses only the canonical [normalized] base URL, never [mode].
     */
    data class Detected(
        /** The canonical base URL (scheme added if missing, `/webstart` stripped). Empty if input is blank. */
        val normalized: String,
        val scheme: String?,
        val host: String?,
        val port: Int?,
        /** The edit-mode the address classifies as (an overridable hint, not a prerequisite). */
        val mode: ConnectionMode,
        /** True when the transport is plain, unencrypted http. */
        val insecure: Boolean,
        /** True when the address parses to a usable http/https base URL with a host. */
        val valid: Boolean,
    )

    private val SCHEME_PREFIX = Regex("^[a-zA-Z][a-zA-Z0-9+.\\-]*://")

    /**
     * Auto-detect scheme / host / port / [ConnectionMode] / insecurity from whatever a user types or
     * pastes into the single address field. A bare `host:port` (no scheme) is assumed https. Classifies
     * per §6.3.1: `https://host:port` → Standard, `https://localhost:port` → LocalhostPortForward, and
     * plain `http`, a path-bearing URL, a non-web scheme, or a missing port → Custom (http also flags
     * insecure). Never throws — a partial/garbage address yields `valid = false`.
     */
    fun detect(input: String): Detected {
        val raw = input.trim()
        if (raw.isEmpty()) return Detected("", null, null, null, ConnectionMode.Standard, insecure = false, valid = false)
        // No explicit scheme → assume https so "oie:8443" classifies like "https://oie:8443".
        val withScheme = if (SCHEME_PREFIX.containsMatchIn(raw)) raw else "https://$raw"
        val base = stripWebstart(withScheme)
        val uri = runCatching { URI.create(base) }.getOrNull()
        val scheme = uri?.scheme?.lowercase()
        val host = uri?.host
        val port = uri?.port?.takeIf { it != -1 }
        val path = uri?.path.orEmpty()
        val hasPath = path.isNotEmpty() && path != "/"
        val insecure = scheme == "http"
        val valid = (scheme == "http" || scheme == "https") && !host.isNullOrBlank()
        val mode = when {
            scheme != "http" && scheme != "https" -> ConnectionMode.Custom
            hasPath -> ConnectionMode.Custom
            insecure -> ConnectionMode.Custom
            port == null -> ConnectionMode.Custom
            host?.lowercase() == "localhost" || host == "127.0.0.1" -> ConnectionMode.LocalhostPortForward
            else -> ConnectionMode.Standard
        }
        return Detected(if (valid) base else raw, scheme, host, port, mode, insecure, valid)
    }

    /** Scheme of a URL (lowercased), or null if unparseable. */
    fun scheme(baseUrl: String): String? = runCatching { URI.create(baseUrl.trim()).scheme?.lowercase() }.getOrNull()

    /** Host component of a URL, or null if unparseable. */
    fun host(baseUrl: String): String? = runCatching { URI.create(baseUrl.trim()).host }.getOrNull()

    /** Explicit port of a URL, or null if absent/unparseable. */
    fun port(baseUrl: String): Int? =
        runCatching { URI.create(baseUrl.trim()).port.takeIf { it != -1 } }.getOrNull()

    /** Compose a Standard-mode base URL with the chosen [scheme] (https by default). */
    fun composeStandard(host: String, port: Int, scheme: String = "https"): String =
        "${scheme}://${host.trim()}:$port"

    /** Compose a Localhost-port-forward base URL with the chosen [scheme] (https by default). */
    fun composeLocalhost(port: Int, scheme: String = "https"): String = "${scheme}://localhost:$port"
}
