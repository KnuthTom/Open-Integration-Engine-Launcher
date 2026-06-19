/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** Crash-safe persistence: atomic temp+rename write, a .bak of the previous good file, and recovery from a torn main file. */
class ConnectionStoreAtomicWriteTest {

    private fun conn(id: String) = Connection(id = id, name = id.uppercase(), serverUrl = "https://$id:8443")
    private fun Path.sib(suffix: String): Path = resolveSibling("$fileName$suffix")

    @Test
    @DisplayName("save is atomic: no stray .tmp remains, a .bak holds the previous good file, and the data round-trips")
    fun atomicSaveLeavesNoTempAndKeepsBak(@TempDir dir: Path) {
        val file = dir.resolve("connections.json")
        val store = ConnectionStore(file)
        store.add(conn("a")) // first save (no prior file → no .bak yet)
        assertTrue(Files.isRegularFile(file))
        assertFalse(Files.exists(file.sib(".tmp"))) { "no temp file may be left behind" }

        store.add(conn("b")) // second save → the previous good file is copied to .bak
        assertTrue(Files.isRegularFile(file.sib(".bak"))) { ".bak must hold the previous good version" }
        assertFalse(Files.exists(file.sib(".tmp")))

        assertEquals(listOf("a", "b"), ConnectionStore(file).list().map { it.id }) { "data must round-trip" }
        // .bak holds the state before the last save (just {a}).
        assertEquals(listOf("a"), parseIds(file.sib(".bak")))
    }

    @Test
    @DisplayName("a corrupt main file recovers from .bak and preserves the corrupt file as .corrupt")
    fun corruptMainRecoversFromBak(@TempDir dir: Path) {
        val file = dir.resolve("connections.json")
        ConnectionStore(file).apply { add(conn("a")); add(conn("b")) } // main={a,b}, .bak={a}

        Files.writeString(file, "{ this is not valid json")
        val recovered = ConnectionStore(file)

        assertEquals(listOf("a"), recovered.list().map { it.id }) { "must fall back to the previous-good .bak" }
        assertTrue(Files.exists(file.sib(".corrupt"))) { "the corrupt main file must be preserved for forensics" }
    }

    @Test
    @DisplayName("a corrupt main file with no usable .bak starts clean and preserves the corrupt file")
    fun corruptMainNoBakStartsClean(@TempDir dir: Path) {
        val file = dir.resolve("connections.json")
        Files.writeString(file, "garbage{{{ not json")

        val store = ConnectionStore(file)

        assertTrue(store.list().isEmpty()) { "no usable data → start clean, never throw" }
        assertTrue(Files.exists(file.sib(".corrupt")))
    }

    private fun parseIds(p: Path): List<String> =
        Regex(""""id"\s*:\s*"([^"]+)"""").findAll(Files.readString(p)).map { it.groupValues[1] }.toList()
}
