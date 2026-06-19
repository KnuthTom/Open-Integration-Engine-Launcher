/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.app

import com.gestalt.launcher.credential.InMemoryCredentialStore
import com.gestalt.launcher.icon.CustomIconStore
import com.gestalt.launcher.icon.IconRejectedException
import com.gestalt.launcher.model.Connection
import com.gestalt.launcher.model.ConnectionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/** Headless wiring of the custom-icon picker state through [AppState] (no Compose window). */
class CustomIconWiringTest {

    private val svg =
        """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><path fill="currentColor" d="M2 2h20v20H2z"/></svg>"""
            .toByteArray()

    private fun newState(dir: Path): AppState = AppState(
        store = ConnectionStore(dir.resolve("connections.json")),
        prefs = AppPreferences(dir.resolve("prefs.properties")),
        credentials = InMemoryCredentialStore(),
        scope = CoroutineScope(Dispatchers.Unconfined),
        iconStore = CustomIconStore(dir.resolve("icons")),
    )

    @Test
    @DisplayName("uploading appears in the list, resolves to bytes, can be referenced, then deleted")
    fun uploadResolvesAndDeletes(@TempDir dir: Path) {
        val state = newState(dir)
        assertEquals(0, state.customIcons.size)

        val icon = state.addCustomIconNow("logo.svg", svg)
        assertEquals(1, state.customIcons.size)
        assertEquals(icon, state.customIcon(icon.id))
        assertNotNull(state.customIconBytes(icon.id))

        // A connection can store the custom key.
        state.upsert(Connection(id = "c", name = "C", serverUrl = "https://h:8443", icon = icon.key))
        assertEquals(icon.key, state.get("c")?.icon)

        state.deleteCustomIcon(icon.id)
        assertEquals(0, state.customIcons.size)
        assertNull(state.customIconBytes(icon.id))
    }

    @Test
    @DisplayName("a hostile upload is rejected and nothing is added")
    fun hostileUploadRejected(@TempDir dir: Path) {
        val state = newState(dir)
        assertThrows(IconRejectedException::class.java) { state.addCustomIconNow("x.bin", byteArrayOf(0, 1, 2, 3)) }
        assertEquals(0, state.customIcons.size)
    }
}
