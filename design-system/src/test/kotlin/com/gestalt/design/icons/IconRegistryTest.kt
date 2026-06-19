/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.design.icons

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class IconRegistryTest {

    @Test
    @DisplayName("every chrome icon name resolves to a bundled SVG resource")
    fun everyChromeIconResolves() {
        assertTrue(ChromeIcon.all.isNotEmpty())
        ChromeIcon.all.forEach { icon ->
            assertTrue(IconResources.exists(icon.resourcePath)) { "missing chrome asset: ${icon.resourcePath}" }
        }
    }

    @Test
    @DisplayName("ConnectionIcons.all is non-empty, listable, and every key resolves to a bundled SVG")
    fun connectionIconsAreListableAndResolve() {
        assertTrue(ConnectionIcon.all.size >= 15) { "expected a ~15–25 glyph pathology set" }
        // Ordered + listable, with unique semantic keys (a connection persists the key).
        assertEquals(ConnectionIcon.all.size, ConnectionIcon.all.map { it.key }.distinct().size)
        ConnectionIcon.all.forEach { icon ->
            assertTrue(IconResources.exists(icon.resourcePath)) { "missing connection asset: ${icon.resourcePath}" }
        }
    }

    @Test
    @DisplayName("icons resolve back from their stored semantic key")
    fun keysRoundTrip() {
        assertEquals(ChromeIcon.Connect, ChromeIcon.byKey("connect"))
        assertEquals(ChromeIcon.ShieldCheck, ChromeIcon.byKey("shield-check"))
        assertEquals(ConnectionIcon.Microscope, ConnectionIcon.byKey("microscope"))
        assertNotNull(ConnectionIcon.byKey(ConnectionIcon.default.key))
    }
}
