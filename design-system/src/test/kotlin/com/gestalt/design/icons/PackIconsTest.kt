/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.design.icons

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class PackIconsTest {

    @Test
    @DisplayName("index.txt lines parse to pack: entries; comments/blank/malformed lines are skipped")
    fun parsesIndex() {
        val entries = PackIcons.parseIndex(
            """
            # a comment
            foo|Foo Icon|foo.svg

            bar||bar.png
            incomplete|only-two-parts
            """.trimIndent(),
        )
        assertEquals(2, entries.size) { "the 2-field line is dropped" }

        assertEquals("pack:foo", entries[0].key)
        assertEquals("Foo Icon", entries[0].label)
        assertEquals("icons/pack/foo.svg", entries[0].resourcePath)
        assertTrue(entries[0].tintable) { "an SVG pack icon is tintable" }

        assertEquals("pack:bar", entries[1].key)
        assertEquals("bar", entries[1].label) { "an empty label falls back to the key" }
        assertFalse(entries[1].tintable) { "a raster pack icon is not tintable" }
    }

    @Test
    @DisplayName("no pack is bundled by default (no icons/pack/index.txt on the classpath)")
    fun noPackByDefault() {
        assertTrue(PackIcons.all.isEmpty())
    }
}
