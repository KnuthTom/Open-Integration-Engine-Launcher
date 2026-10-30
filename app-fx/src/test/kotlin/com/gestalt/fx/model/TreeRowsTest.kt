/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.fx.model

import com.gestalt.launcher.model.Connection
import com.gestalt.launcher.model.Folder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant

/** The sidebar's rows: sorting, folders-first, collapse, and the filter — the design's `walk`. */
class TreeRowsTest {

    private val folders = listOf(
        Folder("clients", "Clients", sortOrder = 1),
        Folder("slu", "St Louis University", parentId = "clients", sortOrder = 5),
        Folder("cloud", "Cloud", sortOrder = 2, expanded = false),
    )

    private fun c(id: String, name: String, folder: String?, order: Int, last: String? = null, added: String? = null, url: String = "https://$id.example.internal:8443") =
        Connection(id = id, name = name, serverUrl = url, folderId = folder, sortOrder = order, lastLaunchedAt = last, addedAt = added)

    private val connections = listOf(
        c("tk", "TK Sandbox", null, 0, last = "2026-10-30T05:00:00Z", added = "2026-01-01T00:00:00Z", url = "http://localhost:8080"),
        c("bak", "Example Pathology", "clients", 0, last = "2026-10-29T00:00:00Z", added = "2026-02-01T00:00:00Z"),
        c("q", "Quest Example", "clients", 1, added = "2026-03-01T00:00:00Z"),
        c("slu-t", "SLU Test", "slu", 0, last = "2026-10-30T04:00:00Z", added = "2026-04-01T00:00:00Z"),
        c("ship", "Cloud Ship", "cloud", 0, added = "2026-05-01T00:00:00Z"),
        c("appl", "9912 Appliance Test", null, 10, added = "2026-06-01T00:00:00Z"),
    )

    private fun keys(rows: List<TreeRow>) = rows.map { it.key }

    @Test
    @DisplayName("manual order interleaves folders and connections by sortOrder; a collapsed folder hides its contents; depth and counts are right")
    fun manualOrder() {
        val rows = TreeRows.build(connections, folders, SortMode.Manual)
        assertEquals(listOf("c:tk", "f:clients", "c:bak", "c:q", "f:slu", "c:slu-t", "f:cloud", "c:appl"), keys(rows))
        val clients = rows[1] as TreeRow.FolderRow
        assertEquals(3, clients.count) { "Clients holds two connections plus one in its subfolder" }
        assertEquals(0, clients.depth)
        assertEquals(1, (rows[2] as TreeRow.ConnectionRow).depth)
        assertEquals(2, (rows[5] as TreeRow.ConnectionRow).depth)
        assertTrue(rows.none { it.key == "c:ship" }) { "Cloud is collapsed" }
    }

    @Test
    @DisplayName("folders first puts the folders of each level ahead of its connections")
    fun foldersFirst() {
        val rows = TreeRows.build(connections, folders, SortMode.Manual, foldersFirst = true)
        assertEquals(listOf("f:clients", "f:slu", "c:slu-t", "c:bak", "c:q", "f:cloud", "c:tk", "c:appl"), keys(rows))
    }

    @Test
    @DisplayName("name sorts case-insensitively with numbers in order; Z–A reverses it")
    fun nameSort() {
        val asc = TreeRows.build(connections, folders, SortMode.NameAsc)
        assertEquals(listOf("c:appl", "f:clients", "c:bak", "c:q", "f:slu", "c:slu-t", "f:cloud", "c:tk"), keys(asc))
        val desc = TreeRows.build(connections, folders, SortMode.NameDesc)
        assertEquals(listOf("c:tk", "f:cloud", "f:clients", "f:slu", "c:slu-t", "c:q", "c:bak", "c:appl"), keys(desc))
    }

    @Test
    @DisplayName("last launched: most recent first, never-launched last; a folder ranks by its most recently launched connection")
    fun recentSort() {
        val rows = TreeRows.build(connections, folders, SortMode.Recent)
        // tk (05:00) before clients (its slu-t at 04:00) before cloud (never) and appl (never, by name)
        assertEquals(listOf("c:tk", "f:clients", "f:slu", "c:slu-t", "c:bak", "c:q", "c:appl", "f:cloud"), keys(rows))
    }

    @Test
    @DisplayName("date added: newest first; a folder ranks by its newest connection")
    fun addedSort() {
        val rows = TreeRows.build(connections, folders, SortMode.Added)
        assertEquals(listOf("c:appl", "f:cloud", "f:clients", "f:slu", "c:slu-t", "c:q", "c:bak", "c:tk"), keys(rows))
    }

    @Test
    @DisplayName("a filter keeps matching connections (name or address) and the folders that hold them, all open; a folder whose own name matches stays")
    fun filter() {
        val byName = TreeRows.build(connections, folders, SortMode.Manual, filter = "slu")
        assertEquals(listOf("f:clients", "f:slu", "c:slu-t"), keys(byName))
        assertTrue((byName[0] as TreeRow.FolderRow).expanded)

        val byAddress = TreeRows.build(connections, folders, SortMode.Manual, filter = "localhost")
        assertEquals(listOf("c:tk"), keys(byAddress))

        val byFolder = TreeRows.build(connections, folders, SortMode.Manual, filter = "cloud")
        assertEquals(listOf("f:cloud", "c:ship"), keys(byFolder)) { "the collapsed Cloud folder opens under a filter and shows its match" }

        assertEquals(emptyList<String>(), keys(TreeRows.build(connections, folders, SortMode.Manual, filter = "zzz")))
    }

    @Test
    @DisplayName("path and launched-ago labels read as the header shows them")
    fun labels() {
        assertEquals("Clients / St Louis University", TreeRows.pathOf("slu", folders))
        assertEquals("Top level", TreeRows.pathOf(null, folders))
        val now = Instant.parse("2026-10-30T06:00:00Z")
        assertEquals("Never launched", TreeRows.launchedLabel(null, now))
        assertEquals("Launched just now", TreeRows.launchedLabel("2026-10-30T05:59:40Z", now))
        assertEquals("Launched 12 min ago", TreeRows.launchedLabel("2026-10-30T05:48:00Z", now))
        assertEquals("Launched 3 hr ago", TreeRows.launchedLabel("2026-10-30T03:00:00Z", now))
        assertEquals("Launched yesterday", TreeRows.launchedLabel("2026-10-29T03:00:00Z", now))
        assertEquals("Launched 3 days ago", TreeRows.launchedLabel("2026-10-27T06:00:00Z", now))
    }
}
