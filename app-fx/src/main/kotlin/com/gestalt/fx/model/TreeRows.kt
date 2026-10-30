/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.fx.model

import com.gestalt.launcher.model.Connection
import com.gestalt.launcher.model.Folder
import java.text.Collator
import java.time.Instant

/** The sidebar's sort menu. */
enum class SortMode(val label: String, val hint: String = "") {
    Manual("Manual order", "drag"),
    NameAsc("Name (A–Z)"),
    NameDesc("Name (Z–A)"),
    Recent("Last launched"),
    Added("Date added"),
}

/** One row of the flattened tree the sidebar list shows: a folder or a connection at a depth. */
sealed class TreeRow {
    abstract val key: String
    abstract val depth: Int
    abstract val name: String

    data class FolderRow(
        val folder: Folder,
        override val depth: Int,
        /** Connections anywhere below this folder (the count badge). */
        val count: Int,
        /** Shown open: the folder's own state, or forced open while a filter is active. */
        val expanded: Boolean,
    ) : TreeRow() {
        override val key get() = "f:${folder.id}"
        override val name get() = folder.name
    }

    data class ConnectionRow(val connection: Connection, override val depth: Int) : TreeRow() {
        override val key get() = "c:${connection.id}"
        override val name get() = connection.name
    }
}

/**
 * Builds the sidebar rows from the store's connections and folders — the design's `walk`: siblings
 * sorted by [SortMode] (folders and connections in one sequence, or folders first), collapsed folders
 * hide their contents, and a filter keeps the connections whose name or address contains it plus the
 * folders that hold a match (or whose own name matches), all shown open. Pure, so it is unit-tested
 * without a toolkit.
 */
object TreeRows {

    private val collator: Collator = Collator.getInstance().apply { strength = Collator.SECONDARY }

    fun build(
        connections: List<Connection>,
        folders: List<Folder>,
        sort: SortMode = SortMode.Manual,
        foldersFirst: Boolean = false,
        filter: String = "",
    ): List<TreeRow> {
        val q = filter.trim().lowercase()
        val ctx = Ctx(connections, folders, sort, foldersFirst, q)
        val out = ArrayList<TreeRow>()
        ctx.walk(null, 0, out)
        return out
    }

    /** Connections anywhere below [folderId] (direct and in subfolders). */
    fun descendantConnections(folderId: String, connections: List<Connection>, folders: List<Folder>): List<Connection> {
        val ids = HashSet<String>().apply { add(folderId) }
        var grew = true
        while (grew) {
            grew = false
            for (f in folders) if (f.parentId in ids && ids.add(f.id)) grew = true
        }
        return connections.filter { it.folderId in ids }
    }

    /** The folder path as the header shows it: `Clients / St Louis University`, or `Top level`. */
    fun pathOf(folderId: String?, folders: List<Folder>): String {
        if (folderId == null) return "Top level"
        val byId = folders.associateBy { it.id }
        val names = ArrayList<String>()
        var cur = byId[folderId]
        var guard = 0
        while (cur != null && guard++ < 64) {
            names += cur.name
            cur = cur.parentId?.let { byId[it] }
        }
        return if (names.isEmpty()) "Top level" else names.asReversed().joinToString(" / ")
    }

    /** `Launched 12 min ago` … `Never launched`, relative to [now]. */
    fun launchedLabel(lastLaunchedAt: String?, now: Instant = Instant.now()): String {
        val at = lastLaunchedAt?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return "Never launched"
        val minutes = java.time.Duration.between(at, now).toMinutes()
        return when {
            minutes < 1 -> "Launched just now"
            minutes < 60 -> "Launched $minutes min ago"
            minutes < 1440 -> "Launched ${Math.round(minutes / 60.0)} hr ago"
            minutes < 2880 -> "Launched yesterday"
            minutes < 10080 -> "Launched ${Math.round(minutes / 1440.0)} days ago"
            else -> {
                val date = at.atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                "Launched " + date.format(java.time.format.DateTimeFormatter.ofPattern("MMM d"))
            }
        }
    }

    private class Ctx(
        val connections: List<Connection>,
        val folders: List<Folder>,
        val sort: SortMode,
        val foldersFirst: Boolean,
        val q: String,
    ) {
        private sealed class Node {
            abstract val name: String
            abstract val order: Int
            class F(val folder: Folder) : Node() { override val name get() = folder.name; override val order get() = folder.sortOrder }
            class C(val conn: Connection) : Node() { override val name get() = conn.name; override val order get() = conn.sortOrder }
        }

        private val descendants = HashMap<String, List<Connection>>()
        private fun desc(folderId: String): List<Connection> =
            descendants.getOrPut(folderId) { descendantConnections(folderId, connections, folders) }

        /** An instant as a key that sorts newest-first; absent (never launched / unstamped) sorts last. */
        private fun newestFirst(s: String?): Long =
            s?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }?.let { -it } ?: Long.MAX_VALUE

        /** Most recent launch first; a folder sorts by its most recently launched connection. */
        private fun recentKey(n: Node): Long = when (n) {
            is Node.C -> newestFirst(n.conn.lastLaunchedAt)
            is Node.F -> desc(n.folder.id).minOfOrNull { newestFirst(it.lastLaunchedAt) } ?: Long.MAX_VALUE
        }

        /** Newest first; a folder sorts by its newest connection. */
        private fun addedKey(n: Node): Long = when (n) {
            is Node.C -> newestFirst(n.conn.addedAt)
            is Node.F -> desc(n.folder.id).minOfOrNull { newestFirst(it.addedAt) } ?: Long.MAX_VALUE
        }

        private val byName = Comparator<Node> { a, b -> collator.compare(a.name, b.name) }
        private val comparator: Comparator<Node> = when (sort) {
            SortMode.Manual -> compareBy<Node> { it.order }.then(byName)
            SortMode.NameAsc -> byName
            SortMode.NameDesc -> byName.reversed()
            SortMode.Recent -> compareBy<Node> { recentKey(it) }.then(byName)
            SortMode.Added -> compareBy<Node> { addedKey(it) }.then(byName)
        }

        private fun kids(parentId: String?): List<Node> {
            val fs = folders.filter { it.parentId == parentId }.map { Node.F(it) }
            val cs = connections.filter { it.folderId == parentId }.map { Node.C(it) }
            return if (foldersFirst) fs.sortedWith(comparator) + cs.sortedWith(comparator)
            else (fs + cs).sortedWith(comparator)
        }

        fun walk(parentId: String?, depth: Int, out: MutableList<TreeRow>) {
            for (n in kids(parentId)) {
                when (n) {
                    is Node.F -> {
                        val f = n.folder
                        val row = TreeRow.FolderRow(f, depth, desc(f.id).size, expanded = q.isNotEmpty() || f.expanded)
                        if (q.isNotEmpty()) {
                            val sub = ArrayList<TreeRow>()
                            walk(f.id, depth + 1, sub)
                            if (sub.isEmpty() && !f.name.lowercase().contains(q)) continue
                            out += row
                            out += sub
                        } else {
                            out += row
                            if (f.expanded) walk(f.id, depth + 1, out)
                        }
                    }
                    is Node.C -> {
                        val c = n.conn
                        if (q.isNotEmpty() && !(c.name.lowercase().contains(q) || c.serverUrl.lowercase().contains(q))) continue
                        out += TreeRow.ConnectionRow(c, depth)
                    }
                }
            }
        }
    }
}
