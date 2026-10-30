/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.app

import com.gestalt.launcher.credential.InMemoryCredentialStore
import com.gestalt.launcher.launch.LaunchPhase
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

/** Headless tests for multi-select bulk actions and keyboard row navigation (no Compose window). */
class SelectionAndKeyboardTest {

    private fun newState(dir: Path): AppState {
        val store = ConnectionStore(dir.resolve("connections.json"))
        return AppState(
            store = store,
            prefs = AppPreferences(dir.resolve("prefs.properties")),
            credentials = InMemoryCredentialStore(),
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
    }

    private fun seed(state: AppState) {
        state.upsert(Connection(id = "A", name = "Alpha prod", serverUrl = "https://a:8443", sortOrder = 0))
        state.upsert(Connection(id = "B", name = "Bravo", serverUrl = "https://b:8443", sortOrder = 1))
        state.upsert(Connection(id = "C", name = "Charlie prod", serverUrl = "https://c:8443", sortOrder = 2))
    }

    @Test
    @DisplayName("toggleSelected adds then removes; clearSelection empties the set")
    fun toggleAndClear(@TempDir dir: Path) {
        val state = newState(dir)
        seed(state)

        assertFalse(state.isSelected("A"))
        state.toggleSelected("A")
        assertTrue(state.isSelected("A"))
        assertEquals(1, state.selectionCount)
        state.toggleSelected("A")
        assertFalse(state.isSelected("A"))

        state.toggleSelected("A"); state.toggleSelected("B")
        state.clearSelection()
        assertEquals(0, state.selectionCount)
    }

    @Test
    @DisplayName("select-all is scoped to the filtered (visible) rows")
    fun selectAllVisibleScoped(@TempDir dir: Path) {
        val state = newState(dir)
        seed(state)
        state.search = "prod" // visible: A, C — not B

        state.toggleSelectAllVisible()
        assertTrue(state.allVisibleSelected())
        assertTrue(state.isSelected("A") && state.isSelected("C"))
        assertFalse(state.isSelected("B")) { "the filtered-out row must not be selected" }

        state.toggleSelectAllVisible() // toggles back off
        assertEquals(0, state.selectionCount)
    }

    @Test
    @DisplayName("deleteSelected removes every selected row, clears the selection, and drops a stale cursor")
    fun deleteSelected(@TempDir dir: Path) {
        val state = newState(dir)
        seed(state)
        state.toggleSelected("A"); state.toggleSelected("C")
        state.setFocused("C")

        state.deleteSelected()

        assertEquals(listOf("B"), state.connections.map { it.id })
        assertEquals(0, state.selectionCount)
        assertNull(state.focusedId) { "a cursor on a deleted row is cleared" }
    }

    @Test
    @DisplayName("delete prunes the per-connection in-memory status maps")
    fun deletePrunesPerConnectionMaps(@TempDir dir: Path) {
        val state = newState(dir)
        seed(state)
        state.reachability["A"] = Reachability.Ok
        state.launches["A"] = LaunchView(LaunchPhase.Running)
        state.testing["A"] = true

        state.delete("A")

        assertNull(state.reachability["A"]) { "reachability entry must be pruned" }
        assertNull(state.launches["A"]) { "launch view must be pruned" }
        assertNull(state.testing["A"]) { "in-flight test flag must be pruned" }
        assertFalse(state.connections.any { it.id == "A" })
    }

    @Test
    @DisplayName("groupSelected sets and clears the group on the whole selection")
    fun groupSelected(@TempDir dir: Path) {
        val state = newState(dir)
        seed(state)
        state.toggleSelected("A"); state.toggleSelected("B")

        state.groupSelected("Lab")
        assertEquals("Lab", state.get("A")!!.group)
        assertEquals("Lab", state.get("B")!!.group)
        assertNull(state.get("C")!!.group) { "an unselected row is untouched" }

        state.groupSelected(null)
        assertNull(state.get("A")!!.group)
        assertNull(state.get("B")!!.group)
    }

    @Test
    @DisplayName("exportSelected writes only the selected connections")
    fun exportSelected(@TempDir dir: Path) {
        val state = newState(dir)
        seed(state)
        state.toggleSelected("A"); state.toggleSelected("C")
        val out = dir.resolve("subset.json")

        state.exportSelected(out)

        val text = Files.readString(out)
        assertTrue(text.contains("Alpha prod"))
        assertTrue(text.contains("Charlie prod"))
        assertFalse(text.contains("Bravo")) { "an unselected connection must not be exported" }
    }

    @Test
    @DisplayName("moveFocus jumps from no cursor, then steps and clamps within the visible order")
    fun moveFocusClamps(@TempDir dir: Path) {
        val state = newState(dir)
        seed(state)
        state.sort = SortMode.Manual

        assertNull(state.focusedId)
        state.moveFocus(1); assertEquals("A", state.focusedId) // from null, Down -> first
        state.moveFocus(1); assertEquals("B", state.focusedId)
        state.moveFocus(1); assertEquals("C", state.focusedId)
        state.moveFocus(1); assertEquals("C", state.focusedId) { "clamped at the end" }
        state.moveFocus(-1); assertEquals("B", state.focusedId)

        state.setFocused(null)
        state.moveFocus(-1); assertEquals("C", state.focusedId) { "from null, Up -> last" }
    }

    @Test
    @DisplayName("moveFocus re-anchors when the cursor row is filtered out of view")
    fun moveFocusRespectsFilter(@TempDir dir: Path) {
        val state = newState(dir)
        seed(state)
        state.setFocused("B")
        state.search = "alpha" // visible: A only

        state.moveFocus(1)
        assertEquals("A", state.focusedId) { "a cursor that's no longer visible jumps to the first visible row" }
    }

    @Test
    @DisplayName("launchFocused is a no-op when nothing is focused")
    fun launchFocusedNoop(@TempDir dir: Path) {
        val state = newState(dir)
        seed(state)
        state.launchFocused() // must not throw with focusedId == null
        assertTrue(state.launches.isEmpty())
    }

    @Test
    @DisplayName("launch is idempotent while a launch for that connection is already in flight")
    fun launchIdempotentWhileInFlight(@TempDir dir: Path) {
        val state = newState(dir)
        seed(state)
        // Simulate a launch still being prepared; a second launch must NOT restart it or kick off work.
        // (A RUNNING connection is different: it may be launched again — see LaunchTrackingTest.)
        state.launches["A"] = LaunchView(LaunchPhase.Verifying)

        state.launch("A")

        assertEquals(LaunchPhase.Verifying, state.launches["A"]?.phase) { "the in-flight launch is left untouched" }
        assertNull(state.dialog) { "no relaunch was started (so no failure dialog could open)" }
    }

    @Test
    @DisplayName("grouped sort: the cursor order matches the rendered section order (Ungrouped by its label)")
    fun groupedCursorMatchesRenderOrder(@TempDir dir: Path) {
        val state = newState(dir)
        state.upsert(Connection(id = "Z", name = "Zed", serverUrl = "https://z:8443", group = "Zoo", sortOrder = 0))
        state.upsert(Connection(id = "U", name = "Ungeralded", serverUrl = "https://u:8443", sortOrder = 1)) // no group
        state.upsert(Connection(id = "A", name = "Ann", serverUrl = "https://a:8443", group = "Alpha", sortOrder = 2))
        state.sort = SortMode.Group

        // GroupedLazyList renders sections by label (case-sensitive): "Alpha" < "Ungrouped" < "Zoo".
        assertEquals(listOf("A", "U", "Z"), state.visibleConnections().map { it.id })

        state.moveFocus(1); assertEquals("A", state.focusedId)
        state.moveFocus(1); assertEquals("U", state.focusedId)
        state.moveFocus(1); assertEquals("Z", state.focusedId)
    }

    @Test
    @DisplayName("Esc clears a multi-select before it leaves the form")
    fun escapeClearsSelectionFirst(@TempDir dir: Path) {
        val state = newState(dir)
        seed(state)
        state.startNewConnection() // route -> Edit
        state.toggleSelected("A")

        assertTrue(state.onEscape()) { "consumed" }
        assertEquals(0, state.selectionCount)
        assertTrue(state.route is Route.Edit) { "selection clears first; the form stays open" }

        assertTrue(state.onEscape())
        assertTrue(state.route is Route.List) { "a second Esc then leaves the form" }
    }
}
