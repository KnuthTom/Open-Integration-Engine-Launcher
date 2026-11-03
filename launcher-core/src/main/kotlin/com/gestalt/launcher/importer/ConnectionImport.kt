/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.importer

import com.gestalt.launcher.audit.AuditLog
import com.gestalt.launcher.icon.CustomIconStore
import com.gestalt.launcher.icon.ExportedIcon
import com.gestalt.launcher.model.Connection
import com.gestalt.launcher.model.Folder
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
 * `connections.json`, auto-detecting which. Pure mapping logic (no UI); the file pickers live in the UIs.
 *
 * Security/discipline rules:
 *  - **Never imports secrets.** A legacy `password` is dropped; passwords are (re-)entered in the
 *    launcher and stored only in the OS keychain. The username is a setting (it pre-fills the login)
 *    and comes along.
 *  - **Every other setting round-trips** from our own export: Java runtime, heap, SSL options, display
 *    scale, close-after-launch, certificate auto-approve, custom paths, the folder tree and the custom
 *    icons the connections use. A port-forward command is the one setting the launcher *runs*, so the
 *    caller decides under a [TunnelPolicy] — after showing the user what the file carries.
 *  - **Regenerates ids** (legacy ids are not unique; ours would collide with the machine's own).
 *  - **Preserves file order** as the initial [Connection.sortOrder].
 */
object ConnectionImport {

    /** Which on-disk format a file is. */
    enum class SourceFormat { LegacyMcal, Gestalt, Unknown }

    /** What to do when an incoming connection matches an existing one by name + base URL. */
    enum class DedupePolicy { Merge, Replace, Skip }

    /**
     * What to do with the port-forward commands a file carries — command lines the launcher would run
     * before a launch, so a file from elsewhere must not be able to plant one unseen:
     *  - [Keep]: as the file has them, switch included — for a file the user has reviewed;
     *  - [Disarm] (the default): the command is kept but its switch is off, to be turned on by hand;
     *  - [Drop]: command and switch are not imported.
     */
    enum class TunnelPolicy { Keep, Disarm, Drop }

    /** Outcome of an import run. */
    data class ImportSummary(
        val format: SourceFormat,
        val incoming: Int,
        val added: Int,
        val replaced: Int,
        val merged: Int,
        val skipped: Int,
        /** Incoming connections that carried a port-forward command (whatever the [TunnelPolicy] did with it). */
        val forwards: Int = 0,
        /** Custom icons the file carried that were filed (or matched) here. */
        val icons: Int = 0,
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
        parseAll(text, idGenerator).connections

    /** What a file carries: its connections and (our own format only) the folder tree they sit in and the custom icons they use. */
    data class Parsed(val connections: List<Connection>, val folders: List<Folder>, val icons: List<ExportedIcon> = emptyList()) {
        /** The connections that carry a port-forward command — what a UI shows for review before choosing a [TunnelPolicy]. */
        val forwards: List<Connection> get() = connections.filter { !it.tunnelCommand.isNullOrBlank() }
    }

    /** [parse], plus the folders and icons of a Gestalt export. A legacy file has neither — its `group`s become folders in the store. */
    fun parseAll(text: String, idGenerator: () -> String = { UUID.randomUUID().toString() }): Parsed =
        when (detectFormat(text)) {
            SourceFormat.LegacyMcal -> Parsed(parseLegacy(text, idGenerator), emptyList())
            SourceFormat.Gestalt -> parseGestalt(text)
            SourceFormat.Unknown -> throw IllegalArgumentException("unrecognized connections file format")
        }

    /**
     * Parse a SINGLE pasted connection entry — a legacy MCAL object, a Gestalt connection object, or the
     * first entry of a pasted whole file — into a [Connection] for prefilling the form. Returns null when
     * the text isn't a recognizable connection object. Never imports a password (same discipline as
     * [parse]); a port-forward command is kept with its switch off, for the user to turn on in the form.
     */
    fun parseSingleEntry(text: String, idGenerator: () -> String = { UUID.randomUUID().toString() }): Connection? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        // A whole pasted file (legacy array or Gestalt object) → take its first entry.
        when (detectFormat(trimmed)) {
            SourceFormat.LegacyMcal, SourceFormat.Gestalt ->
                return runCatching { parse(trimmed, idGenerator).firstOrNull() }.getOrNull()?.let { disarmed(it) }
            SourceFormat.Unknown -> Unit // fall through: maybe a bare single object
        }
        val root = runCatching { Json.parseToJsonElement(trimmed) }.getOrNull() as? JsonObject ?: return null
        // A bare Gestalt connection object carries serverUrl; a legacy entry carries address.
        return if (root["serverUrl"] != null) {
            runCatching { lenient.decodeFromString<Connection>(trimmed) }.getOrNull()?.let { disarmed(clean(it)) }
        } else {
            runCatching { parseLegacy("[$trimmed]", idGenerator).firstOrNull() }.getOrNull()
        }
    }

    /** Our own format, as the file has it, minus what must never come in: `user:pw@` userinfo in the address, a blank username. */
    private fun clean(c: Connection): Connection =
        c.copy(username = c.username?.trim()?.ifBlank { null }, serverUrl = ConnectionUrl.stripUserInfo(c.serverUrl))

    /** The port-forward switch off when there is a command (the command stays, visible, for the user to arm); nothing to switch otherwise. */
    private fun disarmed(c: Connection): Connection =
        if (c.tunnelCommand.isNullOrBlank()) c.copy(tunnelCommand = null, tunnelEnabled = null) else c.copy(tunnelEnabled = false)

    private fun applyTunnelPolicy(c: Connection, policy: TunnelPolicy): Connection = when (policy) {
        TunnelPolicy.Keep -> if (c.tunnelCommand.isNullOrBlank()) c.copy(tunnelCommand = null, tunnelEnabled = null) else c
        TunnelPolicy.Disarm -> disarmed(c)
        TunnelPolicy.Drop -> c.copy(tunnelCommand = null, tunnelEnabled = null)
    }

    private fun parseLegacy(text: String, idGenerator: () -> String): List<Connection> =
        lenient.decodeFromString<List<LegacyConnection>>(text)
            .mapIndexed { index, entry -> mapLegacy(entry, index, idGenerator()) }

    // Our own export format, every setting as the file has it. Never let `user:pw@` userinfo from a
    // hand-edited file into the store. Ids are regenerated on import (see import); a username then has no
    // saved password here (the keychain entry is per connection), which only means the password is
    // entered once more. Port-forward commands come through as parsed — [import] applies the caller's
    // TunnelPolicy to them.
    private fun parseGestalt(text: String): Parsed {
        val file = lenient.decodeFromString<GestaltFile>(text)
        return Parsed(file.connections.map { clean(it) }, file.folders, file.icons)
    }

    @Serializable
    private data class GestaltFile(
        val version: Int = 1,
        val connections: List<Connection> = emptyList(),
        val folders: List<Folder> = emptyList(),
        val icons: List<ExportedIcon> = emptyList(),
    )

    /**
     * The legacy MCAL element schema — the exact 14 fields the real export carries, all defaulted so a
     * missing field never fails the parse (paired with `ignoreUnknownKeys`/`isLenient`). `customJavaHome`
     * is an optional extra honored when a CUSTOM java home is present; `username` pre-fills the login the
     * way it did there. A `password` an export may carry is deliberately NOT declared here, so [lenient]
     * drops it — a secret is never imported.
     */
    @Serializable
    private data class LegacyConnection(
        val id: String = "",
        val name: String = "",
        val address: String = "",
        val username: String = "",
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
            username = entry.username.trim().ifBlank { null }, // the login name is a setting; the password never comes along
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
    fun importFile(
        store: ConnectionStore,
        path: Path,
        policy: DedupePolicy = DedupePolicy.Merge,
        tunnels: TunnelPolicy = TunnelPolicy.Disarm,
        icons: CustomIconStore? = null,
    ): ImportSummary = import(store, Files.readString(path), policy, tunnels, icons)

    /**
     * Parse [text] and merge it into [store] under [policy]; persists once. The file's folders come
     * along under fresh ids (a folder is never deduplicated against an existing one — an import adds a
     * subtree at the top level, so nothing already in the tree is disturbed); an incoming connection
     * that lands in one keeps that placement, while a connection that matches an existing one keeps the
     * existing folder. Port-forward commands are handled under [tunnels] (audited). Given [icons], the
     * custom icons the file carries are filed there (through the sanitizer) and the connections
     * re-pointed at them; a custom icon that neither the file nor this machine has falls back to the
     * default glyph.
     */
    fun import(
        store: ConnectionStore,
        text: String,
        policy: DedupePolicy = DedupePolicy.Merge,
        tunnels: TunnelPolicy = TunnelPolicy.Disarm,
        icons: CustomIconStore? = null,
    ): ImportSummary {
        val format = detectFormat(text)
        val parsed = parseAll(text)
        val forwards = parsed.forwards.size
        if (forwards > 0) AuditLog.event("import.tunnelCommand", "connections" to forwards, "policy" to tunnels.name.uppercase())

        // Custom icons: file what the export carries, then re-point `custom:<id>` keys at what they landed on.
        var filedIcons = 0
        val iconKeyMap = HashMap<String, String>()
        if (icons != null) {
            parsed.icons.forEach { exported ->
                val filed = icons.import(exported) ?: return@forEach
                filedIcons++
                iconKeyMap[CustomIconStore.KEY_PREFIX + exported.id] = filed.key
            }
        }
        fun iconOf(c: Connection): String {
            val id = CustomIconStore.idFromKey(c.icon) ?: return c.icon
            iconKeyMap[c.icon]?.let { return it }
            if (icons == null || icons.get(id) != null) return c.icon // not managing icons here, or already on this machine
            return Connection.DEFAULT_ICON_KEY
        }

        val incoming = parsed.connections.map { applyTunnelPolicy(it, tunnels).let { c -> c.copy(icon = iconOf(c)) } }
        val result = store.list().toMutableList()
        val byKey = HashMap<String, Connection>()
        result.forEach { byKey[dedupeKey(it)] = it }

        // Fresh ids for every imported folder; a parent missing from the file lifts to the top level.
        val existingFolders = store.folders()
        val folderIdMap = HashMap<String, String>()
        parsed.folders.forEach { f -> folderIdMap[f.id] = freshFolderId(existingFolders, folderIdMap.values) }
        val topLevelBase = existingFolders.count { it.parentId == null }
        val newFolders = parsed.folders.mapIndexed { i, f ->
            val parent = f.parentId?.let { folderIdMap[it] }
            Folder(
                id = folderIdMap.getValue(f.id),
                name = f.name.ifBlank { "Folder" },
                parentId = parent,
                sortOrder = if (parent == null) topLevelBase + i else f.sortOrder,
                expanded = f.expanded,
            )
        }
        fun placement(c: Connection): String? = c.folderId?.let { folderIdMap[it] }

        var added = 0; var replaced = 0; var merged = 0; var skipped = 0
        for (candidate in incoming) {
            val key = dedupeKey(candidate)
            val match = byKey[key]
            if (match == null) {
                val placed = candidate.copy(id = freshId(result), sortOrder = result.size, folderId = placement(candidate), lastLaunchedAt = null)
                result += placed
                byKey[key] = placed
                added++
            } else {
                when (policy) {
                    DedupePolicy.Skip -> skipped++
                    DedupePolicy.Replace -> {
                        val replacement = candidate.copy(
                            id = match.id, sortOrder = match.sortOrder, group = match.group,
                            folderId = match.folderId, addedAt = match.addedAt, lastLaunchedAt = match.lastLaunchedAt,
                        )
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
        store.replaceAll(result, newFolders)
        AuditLog.event(
            "connection.import",
            "format" to format.name, "incoming" to incoming.size, "folders" to newFolders.size, "icons" to filedIcons,
            "added" to added, "replaced" to replaced, "merged" to merged, "skipped" to skipped,
        )
        return ImportSummary(format, incoming.size, added, replaced, merged, skipped, forwards, filedIcons)
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

    private fun freshFolderId(existing: List<Folder>, alsoTaken: Collection<String>): String {
        val taken = existing.mapTo(HashSet()) { it.id } + alsoTaken
        var id = UUID.randomUUID().toString()
        while (id in taken) id = UUID.randomUUID().toString()
        return id
    }

    /**
     * Incoming settings win; the existing id, placement (sortOrder/group/folder), timestamps and
     * certificate pin — this machine's trust state — are preserved, and an optional setting the file
     * lacks keeps its existing value. The port-forward switch is this machine's decision: when the
     * command is unchanged the existing switch stands, so re-importing one's own export never disarms
     * (or arms) a forward that was already set up here.
     */
    private fun merge(existing: Connection, incoming: Connection): Connection = existing.copy(
        serverUrl = incoming.serverUrl,
        username = incoming.username ?: existing.username,
        connectionMode = incoming.connectionMode,
        icon = incoming.icon,
        javaHome = incoming.javaHome,
        javaFxHome = incoming.javaFxHome ?: existing.javaFxHome,
        heapSize = incoming.heapSize ?: existing.heapSize,
        showJavaConsole = incoming.showJavaConsole,
        sslProtocols = incoming.sslProtocols,
        sslCipherSuites = incoming.sslCipherSuites,
        legacyTlsCompatibility = incoming.legacyTlsCompatibility,
        webstartPath = incoming.webstartPath,
        digestPath = incoming.digestPath,
        closeAfterLaunch = incoming.closeAfterLaunch,
        autoApproveCertificates = incoming.autoApproveCertificates,
        uiScale = incoming.uiScale ?: existing.uiScale,
        tunnelCommand = incoming.tunnelCommand ?: existing.tunnelCommand,
        tunnelEnabled = when {
            incoming.tunnelCommand == null -> existing.tunnelEnabled
            incoming.tunnelCommand.trim() == existing.tunnelCommand?.trim() -> existing.tunnelEnabled
            else -> incoming.tunnelEnabled
        },
    )
}
