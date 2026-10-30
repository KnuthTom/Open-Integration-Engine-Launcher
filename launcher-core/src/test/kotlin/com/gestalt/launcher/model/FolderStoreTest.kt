/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.model

import com.gestalt.launcher.importer.ConnectionImport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * Schema 2: the sidebar's folder tree and the two timestamps the sort modes need, persisted beside the
 * connections. The old flat `group` migrates into a top-level folder once; nothing the user filed is
 * ever lost by a folder operation (deleting a folder lifts its contents to the parent).
 */
class FolderStoreTest {

    private fun conn(id: String, folderId: String? = null, group: String? = null) =
        Connection(id = id, name = id.uppercase(), serverUrl = "https://$id.example.internal:8443", folderId = folderId, group = group)

    @Test
    @DisplayName("a schema-1 file: each distinct `group` becomes one top-level folder, connections land in it, the legacy field is consumed")
    fun legacyGroupsMigrate(@TempDir dir: Path) {
        val file = dir.resolve("connections.json")
        Files.writeString(
            file,
            """
            {"version":1,"connections":[
              {"id":"a","name":"A","serverUrl":"https://a:8443","group":"Clients"},
              {"id":"b","name":"B","serverUrl":"https://b:8443","group":"clients "},
              {"id":"c","name":"C","serverUrl":"https://c:8443","group":"Cloud"},
              {"id":"d","name":"D","serverUrl":"https://d:8443"}
            ]}
            """.trimIndent(),
        )

        val store = ConnectionStore(file)

        val folders = store.folders()
        assertEquals(listOf("Clients", "Cloud"), folders.map { it.name }) { "one folder per distinct group, case-insensitively" }
        assertTrue(folders.all { it.parentId == null })
        val clients = folders.first { it.name == "Clients" }.id
        assertEquals(clients, store.get("a")!!.folderId)
        assertEquals(clients, store.get("b")!!.folderId)
        assertNull(store.get("d")!!.folderId)
        assertNull(store.get("a")!!.group) { "the legacy field is consumed so a later move to the top level sticks" }

        // Persisted as schema 2 with the folders beside the connections, and stable across a reload.
        store.save()
        val text = Files.readString(file)
        assertEquals(2, ConnectionStore.declaredVersion(text))
        assertTrue(text.contains("\"folders\""))
        val again = ConnectionStore(file)
        assertEquals(2, again.folders().size) { "a second load creates no duplicate folders" }
        assertEquals(clients, again.get("a")!!.folderId)
    }

    @Test
    @DisplayName("add stamps addedAt; markLaunched stamps lastLaunchedAt; both survive a reload")
    fun timestamps(@TempDir dir: Path) {
        val file = dir.resolve("connections.json")
        val store = ConnectionStore(file)
        val before = Instant.now()

        val added = store.add(conn("a"))
        assertNotNull(added.addedAt)
        assertFalse(Instant.parse(added.addedAt!!).isBefore(before))
        assertNull(added.lastLaunchedAt)

        val at = Instant.parse("2026-10-30T06:00:00Z")
        assertEquals(at.toString(), store.markLaunched("a", at)!!.lastLaunchedAt)
        assertNull(store.markLaunched("missing", at))

        val reloaded = ConnectionStore(file).get("a")!!
        assertEquals(added.addedAt, reloaded.addedAt)
        assertEquals(at.toString(), reloaded.lastLaunchedAt)
    }

    @Test
    @DisplayName("folders nest, rename, move and collapse; a move into a descendant is refused")
    fun folderTree(@TempDir dir: Path) {
        val store = ConnectionStore(dir.resolve("connections.json"))
        store.addFolder(Folder("f1", "Clients"))
        store.addFolder(Folder("f2", "St Louis University", parentId = "f1"))
        store.addFolder(Folder("f3", "Test", parentId = "f2"))
        assertThrows(IllegalArgumentException::class.java) { store.addFolder(Folder("f4", "Orphan", parentId = "nope")) }
        assertThrows(IllegalArgumentException::class.java) { store.addFolder(Folder("f1", "Dup")) }

        assertEquals(setOf("f2", "f3"), store.descendantFolderIds("f1"))
        assertEquals(emptySet<String>(), store.descendantFolderIds("f3"))

        store.updateFolder(store.getFolder("f2")!!.copy(name = "SLU", expanded = false))
        assertEquals("SLU", store.getFolder("f2")!!.name)
        assertFalse(store.getFolder("f2")!!.expanded)

        assertThrows(IllegalArgumentException::class.java) { store.updateFolder(store.getFolder("f1")!!.copy(parentId = "f3")) }
        assertThrows(IllegalArgumentException::class.java) { store.updateFolder(store.getFolder("f1")!!.copy(parentId = "f1")) }
        store.updateFolder(store.getFolder("f3")!!.copy(parentId = null)) // lift to the top level: fine
        assertNull(store.getFolder("f3")!!.parentId)

        assertThrows(IllegalArgumentException::class.java) { store.update(store.add(conn("a")).copy(folderId = "nope")) }
    }

    @Test
    @DisplayName("deleting a folder lifts its connections and subfolders to its parent — nothing is lost")
    fun deleteFolderLiftsContents(@TempDir dir: Path) {
        val file = dir.resolve("connections.json")
        val store = ConnectionStore(file)
        store.addFolder(Folder("top", "Clients"))
        store.addFolder(Folder("mid", "SLU", parentId = "top"))
        store.addFolder(Folder("leaf", "Test", parentId = "mid"))
        store.add(conn("a", folderId = "mid"))
        store.add(conn("b", folderId = "leaf"))
        store.add(conn("c", folderId = "top"))

        store.deleteFolder("mid")

        assertNull(store.getFolder("mid"))
        assertEquals("top", store.getFolder("leaf")!!.parentId) { "the subfolder moved up" }
        assertEquals("top", store.get("a")!!.folderId) { "the connection moved up" }
        assertEquals("leaf", store.get("b")!!.folderId) { "a connection deeper down is untouched" }
        assertEquals("top", store.get("c")!!.folderId)

        store.deleteFolder("top")
        assertNull(store.get("a")!!.folderId) { "deleting a top-level folder lifts to the top level" }
        assertNull(store.getFolder("leaf")!!.parentId)
        assertEquals(3, ConnectionStore(file).list().size)
    }

    @Test
    @DisplayName("a file whose folder parent is missing lifts that folder to the top level; a connection in a missing folder goes top level")
    fun danglingReferencesHeal(@TempDir dir: Path) {
        val file = dir.resolve("connections.json")
        Files.writeString(
            file,
            """
            {"version":2,
             "folders":[{"id":"f1","name":"Orphan","parentId":"gone"}],
             "connections":[{"id":"a","name":"A","serverUrl":"https://a:8443","folderId":"nowhere"}]}
            """.trimIndent(),
        )
        val store = ConnectionStore(file)
        assertNull(store.getFolder("f1")!!.parentId)
        assertNull(store.get("a")!!.folderId)
    }

    @Test
    @DisplayName("export carries the folders of the exported connections (with ancestors); import brings them back under fresh ids")
    fun exportImportRoundTripsFolders(@TempDir dir: Path) {
        val store = ConnectionStore(dir.resolve("connections.json"))
        store.addFolder(Folder("top", "Clients"))
        store.addFolder(Folder("mid", "SLU", parentId = "top"))
        store.addFolder(Folder("other", "Cloud"))
        store.add(conn("a", folderId = "mid"))
        store.add(conn("b", folderId = "other"))
        store.add(conn("c"))

        // A subset export of only `a` carries SLU and its ancestor Clients, not Cloud.
        val subset = dir.resolve("subset.json")
        store.exportTo(subset, setOf("a"))
        val parsed = ConnectionImport.parseAll(Files.readString(subset))
        assertEquals(listOf("a"), parsed.connections.map { it.id })
        assertEquals(setOf("Clients", "SLU"), parsed.folders.map { it.name }.toSet())

        // Import that subset into an empty store: the tree is rebuilt under fresh ids, `a` lands in SLU.
        val target = ConnectionStore(dir.resolve("target/connections.json"))
        val summary = ConnectionImport.importFile(target, subset)
        assertEquals(1, summary.added)
        val slu = target.folders().first { it.name == "SLU" }
        val clients = target.folders().first { it.name == "Clients" }
        assertEquals(clients.id, slu.parentId)
        assertTrue(slu.id != "mid" && clients.id != "top") { "imported folders get fresh ids" }
        val imported = target.list().single()
        assertEquals(slu.id, imported.folderId)
        assertTrue(imported.id != "a")

        // Importing the same file again adds a second, separate subtree (no folder dedupe) and skips/merges the connection.
        ConnectionImport.importFile(target, subset, ConnectionImport.DedupePolicy.Skip)
        assertEquals(4, target.folders().size)
        assertEquals(1, target.list().size)

        // A full export is the whole tree, Cloud included.
        val all = dir.resolve("all.json")
        store.exportTo(all)
        assertEquals(setOf("Clients", "SLU", "Cloud"), ConnectionImport.parseAll(Files.readString(all)).folders.map { it.name }.toSet())
    }

    @Test
    @DisplayName("importing a schema-1 export of this launcher (flat `group`s) files each entry in a folder named after its group")
    fun schemaOneImportUsesFolders(@TempDir dir: Path) {
        val store = ConnectionStore(dir.resolve("connections.json"))
        val v1 = """
            {"version":1,"connections":[
              {"id":"x","name":"Lab A","serverUrl":"https://a.example.internal:8443","group":"Clients"},
              {"id":"y","name":"Lab B","serverUrl":"https://b.example.internal:8443","group":"Clients"},
              {"id":"z","name":"Local","serverUrl":"https://localhost:8443"}]}
        """.trimIndent()

        ConnectionImport.import(store, v1)

        val clients = store.folders().single()
        assertEquals("Clients", clients.name)
        assertEquals(2, store.list().count { it.folderId == clients.id })
        assertEquals(1, store.list().count { it.folderId == null })
        assertTrue(store.list().all { it.group == null && it.addedAt != null })
    }
}
