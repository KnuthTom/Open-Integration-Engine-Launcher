/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.model

import com.gestalt.launcher.net.LocalhostResolver
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
     * surfaced separately by [detect], never by forcing Custom mode). The ONE classifier for the form
     * and the importer, so a connection reopens in the mode it was imported with:
     *  - a non-web scheme (not http/https) → [ConnectionMode.Custom];
     *  - a non-root path → [ConnectionMode.Custom];
     *  - no explicit (valid) port → [ConnectionMode.Custom];
     *  - a loopback host (`localhost`, `127.x`, `[::1]`) with a port → [ConnectionMode.LocalhostPortForward];
     *  - otherwise `host:port` → [ConnectionMode.Standard].
     */
    fun deriveMode(baseUrl: String): ConnectionMode =
        classify(runCatching { URI.create(baseUrl.trim()) }.getOrNull())

    private fun classify(uri: URI?): ConnectionMode {
        if (uri == null) return ConnectionMode.Custom
        val scheme = uri.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") return ConnectionMode.Custom
        val path = uri.path.orEmpty()
        if (path.isNotEmpty() && path != "/") return ConnectionMode.Custom
        if (uri.port !in VALID_PORTS) return ConnectionMode.Custom
        return if (LocalhostResolver.isLoopbackHost(uri.host)) ConnectionMode.LocalhostPortForward else ConnectionMode.Standard
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
        /** Why the address is not [valid], in words the form can show; null when valid or blank. */
        val problem: String? = null,
    )

    private val SCHEME_PREFIX = Regex("^[a-zA-Z][a-zA-Z0-9+.\\-]*://")
    private val VALID_PORTS = 1..65535

    /**
     * Auto-detect scheme / host / port / [ConnectionMode] / insecurity from whatever a user types or
     * pastes into the single address field. A bare `host:port` (no scheme) is assumed https. Classifies
     * exactly like [deriveMode] (plain http is a first-class transport, flagged `insecure` rather than
     * forced to Custom). An address carrying userinfo (`https://user:secret@host`) is refused: credentials
     * belong in the OS keychain, never in a URL that is persisted and logged. Never throws — a
     * partial/garbage address yields `valid = false` with a [Detected.problem].
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
        val insecure = scheme == "http"
        val problem = when {
            uri == null -> "That isn't a valid address."
            scheme != "http" && scheme != "https" -> "Only http:// and https:// addresses are supported."
            host.isNullOrBlank() -> "The address needs a host name or IP."
            uri?.rawUserInfo != null -> "Don't put a username or password in the address — use the credential fields."
            port != null && port !in VALID_PORTS -> "The port must be between 1 and 65535."
            else -> null
        }
        val valid = problem == null
        return Detected(if (valid) base else raw, scheme, host, port, classify(uri), insecure, valid, problem)
    }

    /** [url] with any `user:password@` userinfo removed — for logs and for imported addresses. */
    fun stripUserInfo(url: String): String {
        val trimmed = url.trim()
        val uri = runCatching { URI.create(trimmed) }.getOrNull() ?: return trimmed
        if (uri.rawUserInfo == null) return trimmed
        return runCatching {
            URI(uri.scheme, null, uri.host, uri.port, uri.path, uri.query, uri.fragment).toString()
        }.getOrDefault(trimmed)
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
