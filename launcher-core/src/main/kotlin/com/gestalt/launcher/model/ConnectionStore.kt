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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

@Serializable
private data class ConnectionsFile(
    val version: Int = ConnectionStore.SCHEMA_VERSION,
    val connections: List<Connection> = emptyList(),
)

/**
 * Persists the list of [Connection]s to `connections.json` — **non-secret fields only**.
 *
 * Every save runs a [secretScan]: if the serialized JSON ever contains a credential-looking key the
 * save aborts, so a future change that accidentally adds a secret field to [Connection] fails loudly
 * instead of silently writing a password to disk. Passwords are never handled here at all — they go
 * through the CredentialStore.
 *
 * **Thread safety.** The store is reached from the UI thread (edits) and from IO coroutines (the
 * debounced save, import, export) at the same time, so every operation — reads, mutations and the
 * disk write — runs under one lock. A save therefore always writes a consistent snapshot, two saves
 * can never interleave, and an import can't drop an edit that landed mid-way.
 */
class ConnectionStore(
    private val file: Path = AppPaths.connectionsFile,
) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val lock = Any()
    private val connections = LinkedHashMap<String, Connection>()

    /**
     * Schema version the file on disk declared when loaded. When it is NEWER than [SCHEMA_VERSION] the
     * first save preserves that file as `connections.json.v<N>` before overwriting it with this (older)
     * schema — `ignoreUnknownKeys` means fields we don't know would otherwise be silently dropped, and the
     * one-generation `.bak` would lose them after a second save.
     */
    private var loadedVersion: Int = SCHEMA_VERSION
    private var newerSchemaPreserved = false

    init {
        load()
    }

    fun list(): List<Connection> = synchronized(lock) { connections.values.toList() }

    fun get(id: String): Connection? = synchronized(lock) { connections[id] }

    /**
     * Most mutators take [persist]: the default `true` writes `connections.json` synchronously (the
     * simple, test-friendly behavior). The UI passes `false` so the in-memory model updates immediately
     * on the UI thread while the (slower) disk write is scheduled off-thread + debounced by `AppState`,
     * then committed with an explicit [save].
     */
    fun add(connection: Connection, persist: Boolean = true): Connection {
        synchronized(lock) {
            require(connection.id !in connections) { "connection already exists: ${connection.id}" }
            connections[connection.id] = connection
            if (persist) save()
        }
        AuditLog.connectionAdded(connection.id, connection.name, connection.serverUrl)
        return connection
    }

    fun update(connection: Connection, persist: Boolean = true): Connection {
        synchronized(lock) {
            require(connection.id in connections) { "no such connection: ${connection.id}" }
            connections[connection.id] = connection
            if (persist) save()
        }
        AuditLog.connectionEdited(connection.id, connection.name, connection.serverUrl)
        return connection
    }

    fun delete(id: String, persist: Boolean = true) {
        val removed = synchronized(lock) {
            val r = connections.remove(id) != null
            if (r && persist) save()
            r
        }
        if (removed) AuditLog.connectionDeleted(id)
    }

    /**
     * Persist a new manual ordering: assigns each listed id a [Connection.sortOrder] equal to its
     * position, ignoring ids that are not present. Used by drag-to-reorder. Returns true if anything
     * changed (so a deferred caller knows whether a [save] is needed).
     */
    fun reorder(orderedIds: List<String>, persist: Boolean = true): Boolean = synchronized(lock) {
        var changed = false
        orderedIds.forEachIndexed { index, id ->
            val existing = connections[id] ?: return@forEachIndexed
            if (existing.sortOrder != index) {
                connections[id] = existing.copy(sortOrder = index)
                changed = true
            }
        }
        if (changed && persist) save()
        changed
    }

    /** Replace the entire set in one transaction (used by import). Saves once. */
    fun replaceAll(newConnections: List<Connection>) {
        synchronized(lock) {
            connections.clear()
            newConnections.forEach { connections[it.id] = it }
            save()
        }
    }

    /**
     * Write connections to [target] as a schema-versioned Gestalt `connections.json` (ordered by
     * [Connection.sortOrder]). When [ids] is null all connections are written; otherwise only those
     * whose id is in [ids] (used by multi-select "Export selected"). The same [secretScan] runs before
     * the write, so an export can never carry a secret either.
     */
    fun exportTo(target: Path, ids: Set<String>? = null) {
        val text = synchronized(lock) {
            val selected = if (ids == null) connections.values else connections.values.filter { it.id in ids }
            val ordered = selected.sortedWith(compareBy({ it.sortOrder }, { it.name.lowercase() }))
            json.encodeToString(ConnectionsFile(connections = ordered))
        }
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
     * A non-atomic crash mid-[save] can therefore never lose the whole connection list. Stale temp files
     * a crash may have left beside the main file are swept here (they are never read).
     */
    fun load() {
        synchronized(lock) { loadLocked() }
    }

    private fun loadLocked() {
        connections.clear()
        sweepStaleTemps()
        val parsed = parseOrNull(file)
            ?: run {
                if (Files.isRegularFile(file)) {
                    runCatching { Files.move(file, sibling(".corrupt"), StandardCopyOption.REPLACE_EXISTING) }
                    AuditLog.event("connections.corrupt", "file" to file.fileName.toString(), "result" to "PRESERVED")
                }
                parseOrNull(sibling(".bak"))
            }
        loadedVersion = parsed?.version ?: SCHEMA_VERSION
        newerSchemaPreserved = false
        parsed?.connections?.forEach { connections[it.id] = it }
    }

    /**
     * Atomic, crash-safe write: serialize → secret-scan (abort before touching disk if a secret slipped
     * in) → write a uniquely named sibling temp file and fsync it → keep the previous-good file as `.bak`
     * → atomically rename the temp into place. A crash leaves at most a stale temp (swept on the next
     * load) or the previous-good file — never a half-written `connections.json`. The temp name is unique
     * per call so two saves can never truncate each other's work.
     */
    fun save() {
        synchronized(lock) { saveLocked() }
    }

    private fun saveLocked() {
        val text = json.encodeToString(ConnectionsFile(connections = connections.values.toList()))
        secretScan(text)
        Files.createDirectories(file.parent)
        preserveNewerSchemaOnce()
        val tmp = Files.createTempFile(file.parent, file.fileName.toString() + ".", TEMP_SUFFIX)
        try {
            FileChannel.open(tmp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { ch ->
                val buf = ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8))
                while (buf.hasRemaining()) ch.write(buf)
                ch.force(true)
            }
            if (Files.exists(file)) runCatching { Files.copy(file, sibling(".bak"), StandardCopyOption.REPLACE_EXISTING) }
            runCatching { Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE) }
                .recoverCatching { Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING) }
                .getOrThrow()
        } finally {
            runCatching { Files.deleteIfExists(tmp) }
        }
    }

    /** Before the first overwrite of a file written by a NEWER schema, keep that file as `.v<N>`. */
    private fun preserveNewerSchemaOnce() {
        if (newerSchemaPreserved || loadedVersion <= SCHEMA_VERSION || !Files.isRegularFile(file)) return
        runCatching { Files.copy(file, sibling(".v$loadedVersion"), StandardCopyOption.REPLACE_EXISTING) }
            .onSuccess {
                AuditLog.event("connections.newer-schema", "version" to loadedVersion, "preserved" to sibling(".v$loadedVersion").fileName.toString())
            }
        newerSchemaPreserved = true
    }

    private fun sweepStaleTemps() {
        val dir = file.parent ?: return
        if (!Files.isDirectory(dir)) return
        val prefix = file.fileName.toString() + "."
        runCatching {
            Files.newDirectoryStream(dir) { p ->
                val n = p.fileName.toString()
                n.startsWith(prefix) && n.endsWith(TEMP_SUFFIX)
            }.use { stream -> stream.forEach { runCatching { Files.deleteIfExists(it) } } }
        }
    }

    private fun parseOrNull(p: Path): ConnectionsFile? =
        if (!Files.isRegularFile(p)) null
        else runCatching { json.decodeFromString<ConnectionsFile>(Files.readString(p)) }.getOrNull()

    private fun sibling(suffix: String): Path = file.resolveSibling(file.fileName.toString() + suffix)

    companion object {
        /** Schema version this build writes. Bump when a field is added or its meaning changes. */
        const val SCHEMA_VERSION = 1

        private const val TEMP_SUFFIX = ".tmp"

        // Structural allow-list (NOT name-guessing): the exhaustive set of non-secret keys that may
        // appear in connections.json. Any unexpected key fails the save, so a future field added to
        // Connection — whatever it is named — cannot silently smuggle a secret into config. A unit test
        // pins these sets to the serializers' descriptors, so adding a field without updating the list
        // fails the build rather than every save at runtime.
        internal val ALLOWED_TOP_LEVEL = setOf("version", "connections")
        internal val ALLOWED_CONNECTION_KEYS = setOf(
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
        internal val ALLOWED_NESTED_KEYS = mapOf(
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

        /** The schema version a connections file declares (1 when absent), or null if it isn't one. */
        internal fun declaredVersion(serialized: String): Int? = runCatching {
            Json.parseToJsonElement(serialized).jsonObject["version"]?.jsonPrimitive?.content?.toInt() ?: 1
        }.getOrNull()
    }
}
