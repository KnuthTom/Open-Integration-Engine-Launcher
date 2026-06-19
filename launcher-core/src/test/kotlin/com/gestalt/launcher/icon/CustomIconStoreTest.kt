/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.icon

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

class CustomIconStoreTest {

    private val svg =
        """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><path fill="currentColor" d="M2 2h20v20H2z"/></svg>"""
            .toByteArray()

    @Test
    @DisplayName("add stores the sanitized file + index under the data dir, and it reloads")
    fun addPersistsAndReloads(@TempDir dir: Path) {
        val store = CustomIconStore(dir)
        val icon = store.add("microscope.svg", svg)

        assertEquals("svg", icon.ext)
        assertTrue(icon.tintable)
        assertEquals("microscope", icon.label)
        assertTrue(Files.isRegularFile(dir.resolve("${icon.id}.svg"))) { "image bytes written beside the index" }
        assertTrue(Files.isRegularFile(dir.resolve(CustomIconStore.INDEX_FILE)))

        val reloaded = CustomIconStore(dir)
        assertEquals(1, reloaded.list().size)
        assertNotNull(reloaded.bytes(icon.id))
        assertEquals(icon.id, reloaded.get(icon.id)?.id)
    }

    @Test
    @DisplayName("the same image content de-duplicates to one entry (content-addressed id)")
    fun dedupesByContent(@TempDir dir: Path) {
        val store = CustomIconStore(dir)
        val a = store.add("a.svg", svg)
        val b = store.add("b.svg", svg)
        assertEquals(a.id, b.id)
        assertEquals(1, store.list().size)
    }

    @Test
    @DisplayName("rename updates the label; delete removes the row and the file")
    fun renameAndDelete(@TempDir dir: Path) {
        val store = CustomIconStore(dir)
        val icon = store.add("a.svg", svg)

        store.rename(icon.id, "My Lab")
        assertEquals("My Lab", store.get(icon.id)?.label)

        store.delete(icon.id)
        assertNull(store.get(icon.id))
        assertFalse(Files.exists(dir.resolve("${icon.id}.svg")))
        assertNull(store.bytes(icon.id))
    }

    @Test
    @DisplayName("a rejected upload writes nothing and is not registered")
    fun rejectedUploadWritesNothing(@TempDir dir: Path) {
        val store = CustomIconStore(dir)
        assertThrows(IconRejectedException::class.java) { store.add("x.bin", byteArrayOf(1, 2, 3, 4)) }
        assertEquals(0, store.list().size)
    }

    @Test
    @DisplayName("the connection key is custom:<id> and round-trips through idFromKey")
    fun keyFormat(@TempDir dir: Path) {
        val store = CustomIconStore(dir)
        val icon = store.add("a.svg", svg)
        assertEquals("custom:${icon.id}", icon.key)
        assertEquals(icon.id, CustomIconStore.idFromKey(icon.key))
        assertNull(CustomIconStore.idFromKey("microscope")) { "a built-in key is not a custom-icon key" }
    }

    @Test
    @DisplayName("a corrupt index.json is preserved (.corrupt), not silently wiped")
    fun corruptIndexPreserved(@TempDir dir: Path) {
        Files.createDirectories(dir)
        Files.writeString(dir.resolve(CustomIconStore.INDEX_FILE), "{ this is not valid json")

        val store = CustomIconStore(dir)
        assertEquals(0, store.list().size)
        assertTrue(Files.exists(dir.resolve("${CustomIconStore.INDEX_FILE}.corrupt"))) { "the damaged index is preserved" }
    }

    @Test
    @DisplayName("a tampered row with a non-hex id is dropped on load (no path traversal)")
    fun tamperedRowDropped(@TempDir dir: Path) {
        Files.createDirectories(dir)
        Files.writeString(
            dir.resolve(CustomIconStore.INDEX_FILE),
            """{"version":1,"icons":[
                {"id":"../evil","label":"x","ext":"svg","tintable":false,"createdAtEpochMs":0,"sha256":"x","sourceName":"x"},
                {"id":"deadbeef","label":"ok","ext":"svg","tintable":false,"createdAtEpochMs":0,"sha256":"x","sourceName":"x"}
            ]}""",
        )
        val store = CustomIconStore(dir)
        assertEquals(listOf("deadbeef"), store.list().map { it.id }) { "the traversal id row is dropped" }
    }

    @Test
    @DisplayName("orphaned image files (no matching row) are swept on load")
    fun orphansSwept(@TempDir dir: Path) {
        val icon = CustomIconStore(dir).add("a.svg", svg)
        Files.write(dir.resolve("orphan123.svg"), svg) // a stray file with no index row

        CustomIconStore(dir) // load() reconciles
        assertFalse(Files.exists(dir.resolve("orphan123.svg"))) { "the orphan is removed" }
        assertTrue(Files.exists(dir.resolve("${icon.id}.svg"))) { "the registered icon's file is kept" }
    }
}
