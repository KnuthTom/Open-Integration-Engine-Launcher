/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.app

import com.gestalt.launcher.credential.InMemoryCredentialStore
import com.gestalt.launcher.importer.ConnectionImport.DedupePolicy
import com.gestalt.launcher.model.Connection
import com.gestalt.launcher.model.ConnectionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** Headless integration test of the app-state layer (no Compose window). */
class AppStateTest {

    // Real legacy shape: a top-level ARRAY of MCAL entries (a duplicate id + plaintext secrets to drop).
    private val legacyJson = """
        [
          {"id":"dup","name":"Beta","address":"https://b.lab:8443/webstart","icon":"test-tube","javaHome":"DEFAULT","javaHomeBundledValue":"Java 17","heapSize":"512m","username":"u","password":"sekret-pw"},
          {"id":"dup","name":"Alpha","address":"https://localhost:9912/webstart","icon":"optical-microscope","javaHome":"BUNDLED","javaHomeBundledValue":"Java 17"}
        ]
    """.trimIndent()

    private fun newState(dir: Path): AppState {
        val store = ConnectionStore(dir.resolve("connections.json"))
        return AppState(
            store = store,
            prefs = AppPreferences(dir.resolve("prefs.properties")),
            credentials = InMemoryCredentialStore(),
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
    }

    private fun importLegacy(state: AppState, dir: Path) {
        val file = dir.resolve("legacy.json")
        Files.writeString(file, legacyJson)
        state.import(file, DedupePolicy.Merge)
    }

    @Test
    @DisplayName("import populates the list with regenerated ids and drops secrets")
    fun importWiring(@TempDir dir: Path) {
        val state = newState(dir)
        importLegacy(state, dir)

        assertEquals(2, state.connections.size)
        assertEquals(2, state.connections.map { it.id }.distinct().size) { "duplicate legacy ids regenerated" }
        assertTrue(state.connections.all { it.username == null })
        assertFalse(Files.readString(dir.resolve("connections.json")).contains("sekret-pw"))
    }

    @Test
    @DisplayName("search filters and sort orders the visible list")
    fun searchAndSort(@TempDir dir: Path) {
        val state = newState(dir)
        importLegacy(state, dir)

        state.sort = SortMode.Name
        assertEquals(listOf("Alpha", "Beta"), state.visibleConnections().map { it.name })

        state.search = "alpha"
        assertEquals(listOf("Alpha"), state.visibleConnections().map { it.name })
    }

    @Test
    @DisplayName("reorder persists a new manual order")
    fun reorderWiring(@TempDir dir: Path) {
        val state = newState(dir)
        importLegacy(state, dir)
        state.sort = SortMode.Manual

        val reversed = state.visibleConnections().map { it.id }.reversed()
        state.commitOrder(reversed)
        assertEquals(reversed, state.visibleConnections().map { it.id })
    }

    @Test
    @DisplayName("duplicate, upsert, and delete keep the store and list in sync")
    fun mutationsWiring(@TempDir dir: Path) {
        val state = newState(dir)
        importLegacy(state, dir)
        val beta = state.connections.first { it.name == "Beta" }

        state.duplicate(beta)
        assertEquals(3, state.connections.size)
        assertTrue(state.connections.any { it.name == "Beta (copy)" })

        val id = state.newId()
        state.upsert(Connection(id = id, name = "Gamma", serverUrl = "https://g.lab:8443", sortOrder = state.nextSortOrder()))
        assertEquals(4, state.connections.size)

        state.upsert(state.get(id)!!.copy(name = "Gamma2"))
        assertEquals("Gamma2", state.get(id)!!.name)
        assertEquals(4, state.connections.size) { "update must not add a row" }

        state.delete(id)
        assertNull(state.get(id))
        assertEquals(3, state.connections.size)
    }

    @Test
    @DisplayName("reorder of a filtered subset preserves hidden connections (no sortOrder corruption)")
    fun reorderWithFilterPreservesHidden(@TempDir dir: Path) {
        val state = newState(dir)
        state.upsert(Connection(id = "A", name = "Alpha prod", serverUrl = "https://a:8443", sortOrder = 0))
        state.upsert(Connection(id = "B", name = "Bravo", serverUrl = "https://b:8443", sortOrder = 1))
        state.upsert(Connection(id = "C", name = "Charlie prod", serverUrl = "https://c:8443", sortOrder = 2))
        state.upsert(Connection(id = "D", name = "Delta", serverUrl = "https://d:8443", sortOrder = 3))

        state.sort = SortMode.Manual
        state.search = "prod"
        assertEquals(listOf("A", "C"), state.visibleConnections().map { it.id })

        // User reorders the filtered view [A, C] -> [C, A]; hidden B, D must keep their slots.
        state.commitOrder(listOf("C", "A"))

        state.search = ""
        assertEquals(listOf("C", "B", "A", "D"), state.visibleConnections().map { it.id })
        assertEquals(listOf(0, 1, 2, 3), state.connections.sortedBy { it.sortOrder }.map { it.sortOrder }) {
            "sortOrder values must stay unique and contiguous"
        }
    }

    @Test
    @DisplayName("export writes a Gestalt file with no secret")
    fun exportWiring(@TempDir dir: Path) {
        val state = newState(dir)
        importLegacy(state, dir)
        val out = dir.resolve("export.json")
        state.export(out)
        val text = Files.readString(out)
        assertTrue(text.contains("\"version\""))
        assertFalse(text.contains("sekret-pw"))
        assertFalse(text.contains("password", ignoreCase = true))
    }

    @Test
    @DisplayName("environment cue is inferred from name/group")
    fun envCue() {
        assertEquals(EnvCue.Prod, AppState.envCue(Connection(id = "1", name = "PROD OIE", serverUrl = "https://h:8443")))
        assertEquals(EnvCue.Dev, AppState.envCue(Connection(id = "2", name = "dev box", serverUrl = "https://h:8443")))
        assertNull(AppState.envCue(Connection(id = "3", name = "Histology", serverUrl = "https://h:8443")))
    }
}
