/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.model

import com.gestalt.launcher.AppPaths
import com.gestalt.launcher.audit.AuditLog
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

@Serializable
private data class ConnectionsFile(
    val version: Int = 1,
    val connections: List<Connection> = emptyList(),
)

/**
 * Persists the list of [Connection]s to `connections.json` — **non-secret fields only**.
 *
 * Every save runs a [secretScan]: if the serialized JSON ever contains a credential-looking key the
 * save aborts, so a future change that accidentally adds a secret field to [Connection] fails loudly
 * instead of silently writing a password to disk. Passwords are never handled here at all — they go
 * through the CredentialStore.
 */
class ConnectionStore(
    private val file: Path = AppPaths.connectionsFile,
) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val connections = LinkedHashMap<String, Connection>()

    init {
        load()
    }

    fun list(): List<Connection> = connections.values.toList()

    fun get(id: String): Connection? = connections[id]

    /**
     * Most mutators take [persist]: the default `true` writes `connections.json` synchronously (the
     * simple, test-friendly behavior). The UI passes `false` so the in-memory model updates immediately
     * on the UI thread while the (slower) disk write is scheduled off-thread + debounced by `AppState`,
     * then committed with an explicit [save].
     */
    fun add(connection: Connection, persist: Boolean = true): Connection {
        require(connection.id !in connections) { "connection already exists: ${connection.id}" }
        connections[connection.id] = connection
        if (persist) save()
        AuditLog.connectionAdded(connection.id, connection.name, connection.serverUrl)
        return connection
    }

    fun update(connection: Connection, persist: Boolean = true): Connection {
        require(connection.id in connections) { "no such connection: ${connection.id}" }
        connections[connection.id] = connection
        if (persist) save()
        AuditLog.connectionEdited(connection.id, connection.name, connection.serverUrl)
        return connection
    }

    fun delete(id: String, persist: Boolean = true) {
        if (connections.remove(id) != null) {
            if (persist) save()
            AuditLog.connectionDeleted(id)
        }
    }

    /**
     * Persist a new manual ordering: assigns each listed id a [Connection.sortOrder] equal to its
     * position, ignoring ids that are not present. Used by drag-to-reorder. Returns true if anything
     * changed (so a deferred caller knows whether a [save] is needed).
     */
    fun reorder(orderedIds: List<String>, persist: Boolean = true): Boolean {
        var changed = false
        orderedIds.forEachIndexed { index, id ->
            val existing = connections[id] ?: return@forEachIndexed
            if (existing.sortOrder != index) {
                connections[id] = existing.copy(sortOrder = index)
                changed = true
            }
        }
        if (changed && persist) save()
        return changed
    }

    /** Replace the entire set in one transaction (used by import). Saves once. */
    fun replaceAll(newConnections: List<Connection>) {
        connections.clear()
        newConnections.forEach { connections[it.id] = it }
        save()
    }

    /**
     * Write connections to [target] as a schema-versioned Gestalt `connections.json` (ordered by
     * [Connection.sortOrder]). When [ids] is null all connections are written; otherwise only those
     * whose id is in [ids] (used by multi-select "Export selected"). The same [secretScan] runs before
     * the write, so an export can never carry a secret either.
     */
    fun exportTo(target: Path, ids: Set<String>? = null) {
        val selected = if (ids == null) connections.values else connections.values.filter { it.id in ids }
        val ordered = selected.sortedWith(compareBy({ it.sortOrder }, { it.name.lowercase() }))
        val text = json.encodeToString(ConnectionsFile(connections = ordered))
        secretScan(text)
        target.parent?.let { Files.createDirectories(it) }
        Files.writeString(target, text)
    }

    /**
     * Load connections, recovering from a torn/corrupt main file:
     *  - main file parses → use it;
     *  - main file missing → fresh start (empty);
     *  - main file present but unparseable → preserve it as `connections.json.corrupt` (audited) and fall
     *    back to the previous-good `connections.json.bak` if that parses, else start clean.
     * A non-atomic crash mid-[save] can therefore never lose the whole connection list.
     */
    fun load() {
        connections.clear()
        val parsed = parseOrNull(file)
            ?: run {
                if (Files.isRegularFile(file)) {
                    runCatching { Files.move(file, sibling(".corrupt"), StandardCopyOption.REPLACE_EXISTING) }
                    AuditLog.event("connections.corrupt", "file" to file.fileName.toString(), "result" to "PRESERVED")
                }
                parseOrNull(sibling(".bak"))
            }
        parsed?.connections?.forEach { connections[it.id] = it }
    }

    /**
     * Atomic, crash-safe write: serialize → secret-scan (abort before touching disk if a secret slipped
     * in) → write a sibling `.tmp` → keep the previous-good file as `.bak` → atomically rename the temp
     * into place. A crash leaves at most a stale `.tmp` (ignored on load) or the previous-good file —
     * never a half-written `connections.json`.
     */
    fun save() {
        val text = json.encodeToString(ConnectionsFile(connections = connections.values.toList()))
        secretScan(text)
        Files.createDirectories(file.parent)
        val tmp = sibling(".tmp")
        Files.writeString(tmp, text)
        if (Files.exists(file)) runCatching { Files.copy(file, sibling(".bak"), StandardCopyOption.REPLACE_EXISTING) }
        runCatching { Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE) }
            .recoverCatching { Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING) }
            .getOrThrow()
    }

    private fun parseOrNull(p: Path): ConnectionsFile? =
        if (!Files.isRegularFile(p)) null
        else runCatching { json.decodeFromString<ConnectionsFile>(Files.readString(p)) }.getOrNull()

    private fun sibling(suffix: String): Path = file.resolveSibling(file.fileName.toString() + suffix)

    companion object {
        // Structural allow-list (NOT name-guessing): the exhaustive set of non-secret keys that may
        // appear in connections.json. Any unexpected key fails the save, so a future field added to
        // Connection — whatever it is named — cannot silently smuggle a secret into config.
        private val ALLOWED_TOP_LEVEL = setOf("version", "connections")
        private val ALLOWED_CONNECTION_KEYS = setOf(
            "id", "name", "serverUrl", "username", "pinnedCertSha256",
            "legacyTlsCompatibility", "webstartPath", "digestPath",
            // Phase 3b non-secret parity fields:
            "group", "sortOrder", "connectionMode", "icon", "javaHome", "javaFxHome",
            "heapSize", "showJavaConsole", "sslProtocols", "sslCipherSuites",
            // Per-connection close-after-launch override (an enum, non-secret):
            "closeAfterLaunch",
        )

        // Nested config objects are also allow-listed one level deep, so a secret cannot be smuggled
        // into a sub-key as the nested types evolve. Any unexpected sub-key fails the save loudly.
        private val ALLOWED_NESTED_KEYS = mapOf(
            "javaHome" to setOf("mode", "bundledVersion", "customPath"),
            "sslProtocols" to setOf("custom", "value"),
            "sslCipherSuites" to setOf("custom", "value"),
        )

        internal fun secretScan(serialized: String) {
            val root = Json.parseToJsonElement(serialized)
            check(root is JsonObject) { "connections.json must be a JSON object" }
            val unknownTop = root.keys - ALLOWED_TOP_LEVEL
            check(unknownTop.isEmpty()) {
                "refusing to persist unexpected top-level key(s) in connections.json: $unknownTop"
            }
            val connections = root["connections"] as? JsonArray ?: return
            connections.forEachIndexed { i, element ->
                check(element is JsonObject) { "connections[$i] must be a JSON object" }
                val unknown = element.keys - ALLOWED_CONNECTION_KEYS
                check(unknown.isEmpty()) {
                    "refusing to persist unexpected field(s) on connections[$i] — possible secret leak: $unknown"
                }
                ALLOWED_NESTED_KEYS.forEach { (key, allowedSub) ->
                    val nested = element[key] as? JsonObject ?: return@forEach
                    val unknownSub = nested.keys - allowedSub
                    check(unknownSub.isEmpty()) {
                        "refusing to persist unexpected field(s) in connections[$i].$key — possible secret leak: $unknownSub"
                    }
                }
            }
        }
    }
}
