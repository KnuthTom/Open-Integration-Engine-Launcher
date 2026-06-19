/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** A `custom:<id>` icon key is opaque metadata: it must persist, export, and pass the secret scan. */
class CustomIconConnectionTest {

    @Test
    @DisplayName("a custom:<id> icon key round-trips through save/load + export, with no image bytes embedded")
    fun customIconKeyRoundTrips(@TempDir dir: Path) {
        val file = dir.resolve("connections.json")
        val store = ConnectionStore(file)
        store.add(Connection(id = "1", name = "Lab", serverUrl = "https://h:8443", icon = "custom:deadbeefcafe1234"))

        val reloaded = ConnectionStore(file)
        assertEquals("custom:deadbeefcafe1234", reloaded.get("1")?.icon) { "the key survives a reload" }

        val export = dir.resolve("export.json")
        store.exportTo(export) // would throw if the secret scan rejected the icon value
        val text = Files.readString(export)
        assertTrue(text.contains("custom:deadbeefcafe1234"))
        assertTrue(text.contains("\"icon\""))
    }
}
