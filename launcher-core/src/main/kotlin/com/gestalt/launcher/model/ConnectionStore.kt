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
import java.time.Instant
import java.util.UUID

@Serializable
private data class ConnectionsFile(
    val version: Int = ConnectionStore.SCHEMA_VERSION,
    val connections: List<Connection> = emptyList(),
    val folders: List<Folder> = emptyList(),
)

/**
 * Persists the [Connection]s and their [Folder] tree to `connections.json` — **non-secret fields only**.
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
    private val folders = LinkedHashMap<String, Folder>()
    private val clock: () -> Instant = { Instant.now() }

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
    /** Adds [connection], stamping [Connection.addedAt] when unset and placing a legacy `group` in its folder. */
    fun add(connection: Connection, persist: Boolean = true): Connection {
        val placed = synchronized(lock) {
            require(connection.id !in connections) { "connection already exists: ${connection.id}" }
            val c = placeInFolderLocked(connection).let { if (it.addedAt == null) it.copy(addedAt = clock().toString()) else it }
            connections[c.id] = c
            if (persist) save()
            c
        }
        AuditLog.connectionAdded(placed.id, placed.name, placed.serverUrl)
        return placed
    }

    fun update(connection: Connection, persist: Boolean = true): Connection {
        synchronized(lock) {
            require(connection.id in connections) { "no such connection: ${connection.id}" }
            val folderId = connection.folderId
            require(folderId == null || folderId in folders) { "no such folder: $folderId" }
            connections[connection.id] = connection
            if (persist) save()
        }
        AuditLog.connectionEdited(connection.id, connection.name, connection.serverUrl)
        return connection
    }

    /** Record that a launch of [id] started now (the "Last launched" sort and the header line). */
    fun markLaunched(id: String, at: Instant = clock(), persist: Boolean = true): Connection? = synchronized(lock) {
        val existing = connections[id] ?: return@synchronized null
        val updated = existing.copy(lastLaunchedAt = at.toString())
        connections[id] = updated
        if (persist) save()
        updated
    }

    // --- folders ----------------------------------------------------------------------------------

    fun folders(): List<Folder> = synchronized(lock) { folders.values.toList() }

    fun getFolder(id: String): Folder? = synchronized(lock) { folders[id] }

    fun addFolder(folder: Folder, persist: Boolean = true): Folder {
        synchronized(lock) {
            require(folder.id !in folders) { "folder already exists: ${folder.id}" }
            val parent = folder.parentId
            require(parent == null || parent in folders) { "no such parent folder: $parent" }
            folders[folder.id] = folder
            if (persist) save()
        }
        AuditLog.event("folder.add", "id" to folder.id, "name" to folder.name)
        return folder
    }

    /** Rename, move, reorder or expand/collapse a folder. A move that would make a cycle is refused. */
    fun updateFolder(folder: Folder, persist: Boolean = true): Folder {
        synchronized(lock) {
            require(folder.id in folders) { "no such folder: ${folder.id}" }
            val parent = folder.parentId
            if (parent != null) {
                require(parent in folders) { "no such parent folder: $parent" }
                require(parent != folder.id && parent !in descendantFolderIdsLocked(folder.id)) {
                    "a folder cannot be moved into itself: ${folder.id}"
                }
            }
            folders[folder.id] = folder
            if (persist) save()
        }
        return folder
    }

    /**
     * Delete a folder; its connections and subfolders move up to its parent (the design's "Its N
     * connections and any subfolders move to <parent>"). Nothing but the folder itself is lost.
     */
    fun deleteFolder(id: String, persist: Boolean = true) {
        val removed = synchronized(lock) {
            val folder = folders.remove(id) ?: return@synchronized false
            val parent = folder.parentId
            folders.values.filter { it.parentId == id }.forEach { folders[it.id] = it.copy(parentId = parent) }
            connections.values.filter { it.folderId == id }.forEach { connections[it.id] = it.copy(folderId = parent) }
            if (persist) save()
            true
        }
        if (removed) AuditLog.event("folder.delete", "id" to id)
    }

    /** Every folder below [id] (children, grandchildren, …), not including [id] itself. */
    fun descendantFolderIds(id: String): Set<String> = synchronized(lock) { descendantFolderIdsLocked(id) }

    private fun descendantFolderIdsLocked(id: String): Set<String> {
        val out = LinkedHashSet<String>()
        var frontier = folders.values.filter { it.parentId == id }.map { it.id }
        while (frontier.isNotEmpty()) {
            val next = ArrayList<String>()
            for (f in frontier) if (out.add(f)) next += folders.values.filter { it.parentId == f }.map { it.id }
            frontier = next
        }
        return out
    }

    /**
     * A connection that still carries only the legacy flat `group` is placed in a top-level folder of
     * that name (created on first sight) and the legacy field is consumed, so a later move to the top
     * level sticks. Idempotent: a connection with a [Connection.folderId] is returned unchanged (or
     * lifted to the top level if its folder no longer exists).
     */
    private fun placeInFolderLocked(c: Connection): Connection {
        val current = c.folderId
        if (current != null) return if (current in folders) c else c.copy(folderId = null)
        val name = c.group?.trim()?.takeIf { it.isNotEmpty() } ?: return c
        val existing = folders.values.firstOrNull { it.parentId == null && it.name.equals(name, ignoreCase = true) }
        val folder = existing ?: Folder(
            id = freshFolderIdLocked(),
            name = name,
            parentId = null,
            sortOrder = folders.values.count { it.parentId == null },
        ).also { folders[it.id] = it }
        return c.copy(folderId = folder.id, group = null)
    }

    private fun freshFolderIdLocked(): String {
        var id = UUID.randomUUID().toString()
        while (id in folders) id = UUID.randomUUID().toString()
        return id
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

    /**
     * Replace the entire set in one transaction (used by import). Saves once. Folders not listed in
     * [newFolders] are kept — an import adds to the tree, it never prunes it — and a connection that
     * still carries only a legacy `group` is placed in a folder of that name.
     */
    fun replaceAll(newConnections: List<Connection>, newFolders: List<Folder> = emptyList()) {
        synchronized(lock) {
            newFolders.forEach { folders[it.id] = it }
            connections.clear()
            newConnections.forEach { c ->
                val placed = placeInFolderLocked(c).let { if (it.addedAt == null) it.copy(addedAt = clock().toString()) else it }
                connections[placed.id] = placed
            }
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
            // The folders those connections live in, with their ancestors, so the tree re-imports intact.
            val wanted = LinkedHashSet<String>()
            ordered.forEach { c ->
                var fid = c.folderId
                while (fid != null && wanted.add(fid)) fid = folders[fid]?.parentId
            }
            val exportedFolders = folders.values.filter { ids == null || it.id in wanted }
            json.encodeToString(ConnectionsFile(connections = ordered, folders = exportedFolders))
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
        folders.clear()
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
        parsed?.folders?.forEach { folders[it.id] = it }
        // A folder whose parent is missing (a hand-edited file) is lifted to the top level rather than lost.
        folders.values.filter { it.parentId != null && it.parentId !in folders }.forEach { folders[it.id] = it.copy(parentId = null) }
        // Schema 1 rows carry a flat `group`; each becomes a top-level folder on the way in.
        parsed?.connections?.forEach { connections[it.id] = placeInFolderLocked(it) }
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
        val text = json.encodeToString(ConnectionsFile(connections = connections.values.toList(), folders = folders.values.toList()))
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
        /**
         * Schema version this build writes. Bump when a field is added or its meaning changes.
         *  1 — flat list with a `group` name per connection.
         *  2 — `folders` tree; connections carry `folderId`, `addedAt`, `lastLaunchedAt`.
         */
        const val SCHEMA_VERSION = 2

        private const val TEMP_SUFFIX = ".tmp"

        // Structural allow-list (NOT name-guessing): the exhaustive set of non-secret keys that may
        // appear in connections.json. Any unexpected key fails the save, so a future field added to
        // Connection — whatever it is named — cannot silently smuggle a secret into config. A unit test
        // pins these sets to the serializers' descriptors, so adding a field without updating the list
        // fails the build rather than every save at runtime.
        internal val ALLOWED_TOP_LEVEL = setOf("version", "connections", "folders")
        internal val ALLOWED_FOLDER_KEYS = setOf("id", "name", "parentId", "sortOrder", "expanded")
        internal val ALLOWED_CONNECTION_KEYS = setOf(
            "id", "name", "serverUrl", "username", "pinnedCertSha256",
            "legacyTlsCompatibility", "webstartPath", "digestPath",
            // Phase 3b non-secret parity fields:
            "group", "sortOrder", "connectionMode", "icon", "javaHome", "javaFxHome",
            "heapSize", "showJavaConsole", "sslProtocols", "sslCipherSuites",
            // Per-connection close-after-launch override (an enum, non-secret):
            "closeAfterLaunch",
            // Trust-on-first-use auto-approve toggle (a boolean, non-secret):
            "autoApproveCertificates",
            // Schema 2: the folder tree and the two timestamps (non-secret):
            "folderId", "addedAt", "lastLaunchedAt",
            // The Administrator's display scale (a number as text, non-secret):
            "uiScale",
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
            (root["folders"] as? JsonArray)?.forEachIndexed { i, element ->
                check(element is JsonObject) { "folders[$i] must be a JSON object" }
                val unknown = element.keys - ALLOWED_FOLDER_KEYS
                check(unknown.isEmpty()) {
                    "refusing to persist unexpected field(s) on folders[$i] — possible secret leak: $unknown"
                }
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
