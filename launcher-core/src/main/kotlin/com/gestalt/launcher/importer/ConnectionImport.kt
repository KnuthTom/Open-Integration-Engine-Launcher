/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.importer

import com.gestalt.launcher.audit.AuditLog
import com.gestalt.launcher.model.Connection
import com.gestalt.launcher.model.ConnectionStore
import com.gestalt.launcher.model.ConnectionUrl
import com.gestalt.launcher.model.JavaHomeConfig
import com.gestalt.launcher.model.JavaHomeMode
import com.gestalt.launcher.model.SslOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * Imports connections from a legacy MCAL ("Mirth Connect Administrator Launcher") export or a Gestalt
 * `connections.json`, auto-detecting which. Pure mapping logic (no UI); the file picker lives in :app.
 *
 * Security/discipline rules:
 *  - **Never imports secrets.** Legacy `username`/`password` are dropped; the connection's username is
 *    left null and credentials are (re-)entered in the client and stored only in the OS keychain.
 *  - **Regenerates ids** on legacy import (legacy ids are not unique).
 *  - **Preserves file order** as the initial [Connection.sortOrder].
 */
object ConnectionImport {

    /** Which on-disk format a file is. */
    enum class SourceFormat { LegacyMcal, Gestalt, Unknown }

    /** What to do when an incoming connection matches an existing one by name + base URL. */
    enum class DedupePolicy { Merge, Replace, Skip }

    /** Outcome of an import run. */
    data class ImportSummary(
        val format: SourceFormat,
        val incoming: Int,
        val added: Int,
        val replaced: Int,
        val merged: Int,
        val skipped: Int,
    )

    private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

    // --- detection ---------------------------------------------------------------------------------

    /**
     * Detect the format by the ROOT JSON node type (never by guessing a key):
     *  - a top-level **array** is a legacy MCAL export (the real legacy `connections.json` shape);
     *  - a top-level **object** carrying a `connections` array is a Gestalt export;
     *  - anything else is unrecognized.
     */
    fun detectFormat(text: String): SourceFormat {
        val root = runCatching { Json.parseToJsonElement(text) }.getOrNull() ?: return SourceFormat.Unknown
        return when (root) {
            is JsonArray -> SourceFormat.LegacyMcal
            is JsonObject -> if (root["connections"] is JsonArray) SourceFormat.Gestalt else SourceFormat.Unknown
            else -> SourceFormat.Unknown
        }
    }

    // --- parsing -----------------------------------------------------------------------------------

    /**
     * Parse + map a file into connections. Legacy entries get fresh ids and mode/icon/javaHome
     * derivation; Gestalt entries are decoded as-is. Throws [IllegalArgumentException] if the format is
     * not recognized.
     */
    fun parse(text: String, idGenerator: () -> String = { UUID.randomUUID().toString() }): List<Connection> =
        when (detectFormat(text)) {
            SourceFormat.LegacyMcal -> parseLegacy(text, idGenerator)
            SourceFormat.Gestalt -> parseGestalt(text)
            SourceFormat.Unknown -> throw IllegalArgumentException("unrecognized connections file format")
        }

    /**
     * Parse a SINGLE pasted connection entry — a legacy MCAL object, a Gestalt connection object, or the
     * first entry of a pasted whole file — into a [Connection] for prefilling the form. Returns null when
     * the text isn't a recognizable connection object. Never imports credentials (same discipline as
     * [parse]): a username/password on the pasted entry is dropped.
     */
    fun parseSingleEntry(text: String, idGenerator: () -> String = { UUID.randomUUID().toString() }): Connection? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        // A whole pasted file (legacy array or Gestalt object) → take its first entry.
        when (detectFormat(trimmed)) {
            SourceFormat.LegacyMcal, SourceFormat.Gestalt ->
                return runCatching { parse(trimmed, idGenerator).firstOrNull() }.getOrNull()
            SourceFormat.Unknown -> Unit // fall through: maybe a bare single object
        }
        val root = runCatching { Json.parseToJsonElement(trimmed) }.getOrNull() as? JsonObject ?: return null
        // A bare Gestalt connection object carries serverUrl; a legacy entry carries address.
        return if (root["serverUrl"] != null) {
            runCatching { lenient.decodeFromString<Connection>(trimmed) }.getOrNull()
                ?.copy(username = null) // never adopt a pasted credential association
        } else {
            runCatching { parseLegacy("[$trimmed]", idGenerator).firstOrNull() }.getOrNull()
        }
    }

    private fun parseLegacy(text: String, idGenerator: () -> String): List<Connection> =
        lenient.decodeFromString<List<LegacyConnection>>(text)
            .mapIndexed { index, entry -> mapLegacy(entry, index, idGenerator()) }

    // Our own export format. Ids are regenerated on import (see import), so a username would be a
    // dangling keychain reference — drop it like the legacy path does; and never let `user:pw@` userinfo
    // from a hand-edited file into the store.
    private fun parseGestalt(text: String): List<Connection> =
        lenient.decodeFromString<GestaltFile>(text).connections.map {
            it.copy(username = null, serverUrl = ConnectionUrl.stripUserInfo(it.serverUrl))
        }

    @Serializable
    private data class GestaltFile(val version: Int = 1, val connections: List<Connection> = emptyList())

    /**
     * The legacy MCAL element schema — the exact 14 fields the real export carries, all defaulted so a
     * missing field never fails the parse (paired with `ignoreUnknownKeys`/`isLenient`). `customJavaHome`
     * is an optional extra honored when a CUSTOM java home is present. Any `username`/`password` an export
     * may carry is deliberately NOT declared here, so [lenient] drops it — credentials are never imported.
     */
    @Serializable
    private data class LegacyConnection(
        val id: String = "",
        val name: String = "",
        val address: String = "",
        val javaHome: String = "DEFAULT",
        val javaHomeBundledValue: String = "Java 17",
        val javaFxHome: String = "",
        val heapSize: String = "",
        val icon: String = "",
        val showJavaConsole: Boolean = false,
        val sslProtocolsCustom: Boolean = false,
        val sslProtocols: String = "",
        val sslCipherSuitesCustom: Boolean = false,
        val sslCipherSuites: String = "",
        val useLegacyDHSettings: Boolean = false,
        val customJavaHome: String = "",
    )

    private fun mapLegacy(entry: LegacyConnection, index: Int, id: String): Connection {
        // Preserves scheme (http or https); strips a trailing /webstart and any user:pw@ userinfo.
        val baseUrl = ConnectionUrl.stripUserInfo(ConnectionUrl.stripWebstart(entry.address))
        return Connection(
            id = id,
            name = entry.name.ifBlank { "Imported ${index + 1}" },
            serverUrl = baseUrl,
            username = null,             // never import credentials
            pinnedCertSha256 = null,
            legacyTlsCompatibility = entry.useLegacyDHSettings,
            group = null,
            sortOrder = index,           // preserve file order
            connectionMode = ConnectionUrl.deriveMode(baseUrl),
            icon = iconKeyFor(entry.icon),
            javaHome = mapJavaHome(entry),
            javaFxHome = entry.javaFxHome.ifBlank { null },
            heapSize = entry.heapSize.ifBlank { null },
            showJavaConsole = entry.showJavaConsole,
            sslProtocols = SslOption(custom = entry.sslProtocolsCustom, value = entry.sslProtocols),
            sslCipherSuites = SslOption(custom = entry.sslCipherSuitesCustom, value = entry.sslCipherSuites),
        )
    }

    /**
     * Map a legacy icon filename to a design-system ConnectionIcons semantic key (kept as a string so
     * launcher-core stays UI-free). Lab glyphs map directly; general infrastructure icons (cloud,
     * network, org-chart) map to the data/server glyph; anything unknown falls back to the default.
     * Public so a cross-module test can confirm every result resolves to a real ConnectionIcon.
     */
    fun iconKeyFor(legacyFilename: String?): String =
        legacyFilename?.trim()?.lowercase()?.let { ICON_MAP[it] } ?: Connection.DEFAULT_ICON_KEY

    private val ICON_MAP: Map<String, String> = mapOf(
        "optical-microscope" to "microscope",
        "microscope" to "microscope",
        "test-tube" to "test-tubes",
        "test-tubes" to "test-tubes",
        "cloud-computing" to "database",
        "cloud" to "database",
        "network" to "database",
        "stacked-organizational-chart" to "database",
        "eukaryotic" to "cell-nuclei",
        "eukaryotic-cell" to "cell-nuclei",
        "body-cells" to "blood-cells",
        "cells" to "cell-nuclei",
        "dna" to "dna",
        "bacteria" to "bacteria",
        "biopsy" to "biopsy",
    )

    private fun mapJavaHome(entry: LegacyConnection): JavaHomeConfig {
        val raw = entry.javaHome.trim()
        // "Java 17" / "Java 8" -> "17" / "8" (tolerate a bare "17"/"8"); default to 17.
        val version = entry.javaHomeBundledValue.filter { it.isDigit() }.ifBlank { "17" }
        val customPath = entry.customJavaHome.ifBlank { null }
        return when {
            raw.isBlank() || raw.equals("DEFAULT", ignoreCase = true) ->
                JavaHomeConfig(JavaHomeMode.Default, version)
            raw.equals("BUNDLED", ignoreCase = true) ->
                JavaHomeConfig(JavaHomeMode.Bundled, version)
            raw.equals("CUSTOM", ignoreCase = true) ->
                JavaHomeConfig(JavaHomeMode.Custom, version, customPath)
            else ->
                JavaHomeConfig(JavaHomeMode.Custom, version, raw) // tolerate a raw path string
        }
    }

    // --- import (dedupe into a store) --------------------------------------------------------------

    /** Read [path] and import into [store]. */
    fun importFile(store: ConnectionStore, path: Path, policy: DedupePolicy = DedupePolicy.Merge): ImportSummary =
        import(store, Files.readString(path), policy)

    /** Parse [text] and merge it into [store] under [policy]; persists once. */
    fun import(store: ConnectionStore, text: String, policy: DedupePolicy = DedupePolicy.Merge): ImportSummary {
        val format = detectFormat(text)
        val incoming = parse(text)
        val result = store.list().toMutableList()
        val byKey = HashMap<String, Connection>()
        result.forEach { byKey[dedupeKey(it)] = it }

        var added = 0; var replaced = 0; var merged = 0; var skipped = 0
        for (candidate in incoming) {
            val key = dedupeKey(candidate)
            val match = byKey[key]
            if (match == null) {
                val placed = candidate.copy(id = freshId(result), sortOrder = result.size)
                result += placed
                byKey[key] = placed
                added++
            } else {
                when (policy) {
                    DedupePolicy.Skip -> skipped++
                    DedupePolicy.Replace -> {
                        val replacement = candidate.copy(id = match.id, sortOrder = match.sortOrder, group = match.group)
                        result[result.indexOfFirst { it.id == match.id }] = replacement
                        byKey[key] = replacement
                        replaced++
                    }
                    DedupePolicy.Merge -> {
                        val mergedConn = merge(match, candidate)
                        result[result.indexOfFirst { it.id == match.id }] = mergedConn
                        byKey[key] = mergedConn
                        merged++
                    }
                }
            }
        }
        store.replaceAll(result)
        AuditLog.event(
            "connection.import",
            "format" to format.name, "incoming" to incoming.size,
            "added" to added, "replaced" to replaced, "merged" to merged, "skipped" to skipped,
        )
        return ImportSummary(format, incoming.size, added, replaced, merged, skipped)
    }

    // `https://h:8443` and `https://h:8443/` are the same server.
    private fun dedupeKey(c: Connection): String =
        c.name.trim().lowercase() + "|" + c.serverUrl.trim().trimEnd('/').lowercase()

    private fun freshId(existing: List<Connection>): String {
        val taken = existing.mapTo(HashSet()) { it.id }
        var id = UUID.randomUUID().toString()
        while (id in taken) id = UUID.randomUUID().toString()
        return id
    }

    /** Incoming config wins; existing id/sortOrder/group and existing username/pin are preserved. */
    private fun merge(existing: Connection, incoming: Connection): Connection = existing.copy(
        serverUrl = incoming.serverUrl,
        connectionMode = incoming.connectionMode,
        icon = incoming.icon,
        javaHome = incoming.javaHome,
        javaFxHome = incoming.javaFxHome ?: existing.javaFxHome,
        heapSize = incoming.heapSize ?: existing.heapSize,
        showJavaConsole = incoming.showJavaConsole,
        sslProtocols = incoming.sslProtocols,
        sslCipherSuites = incoming.sslCipherSuites,
        legacyTlsCompatibility = incoming.legacyTlsCompatibility,
    )
}
