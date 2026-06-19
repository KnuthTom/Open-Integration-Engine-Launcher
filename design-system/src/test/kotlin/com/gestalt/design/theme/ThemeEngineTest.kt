/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.design.theme

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class ThemeEngineTest {

    @Test
    @DisplayName("Sharp vs Soft differ in shape/density/elevation/variants — not just color")
    fun skinChangesStructureNotColor() {
        val sharp = gestaltTheme(Skin.Sharp, Mode.Light)
        val soft = gestaltTheme(Skin.Soft, Mode.Light)

        // Same mode => identical colors, so any difference below is structural, not color.
        assertEquals(sharp.colors, soft.colors)

        assertNotEquals(sharp.shapes, soft.shapes)
        assertNotEquals(sharp.spacing.density, soft.spacing.density)
        assertNotEquals(sharp.elevation, soft.elevation)
        assertNotEquals(sharp.variants, soft.variants)
    }

    @Test
    @DisplayName("Light vs Dark differ in ColorTokens — and only color (skin is orthogonal)")
    fun modeChangesColorsOnly() {
        val light = gestaltTheme(Skin.Sharp, Mode.Light)
        val dark = gestaltTheme(Skin.Sharp, Mode.Dark)

        assertNotEquals(light.colors, dark.colors)

        // Skin held constant => structure is identical across modes.
        assertEquals(light.shapes, dark.shapes)
        assertEquals(light.spacing, dark.spacing)
        assertEquals(light.elevation, dark.elevation)
        assertEquals(light.variants, dark.variants)
    }

    @Test
    @DisplayName("Sharp preset: square corners, flat elevation, compact density, sidebar/flat/filled/outlined")
    fun sharpPreset() {
        val t = gestaltTheme(Skin.Sharp, Mode.Light)
        assertTrue(t.shapes.md.value <= 2f) { "Sharp corners should be ~0–2dp" }
        assertTrue(t.elevation.isFlat) { "Sharp should be flat" }
        assertEquals(Density.Compact, t.spacing.density)
        assertEquals(ButtonStyle.Filled, t.variants.button)
        assertEquals(ListRowStyle.Flat, t.variants.listRow)
        assertEquals(NavStyle.Sidebar, t.variants.nav)
        assertEquals(FieldStyle.Outlined, t.variants.field)
    }

    @Test
    @DisplayName("Soft preset: rounded corners, shadows, comfortable density, card rows")
    fun softPreset() {
        val sharp = gestaltTheme(Skin.Sharp, Mode.Light)
        val soft = gestaltTheme(Skin.Soft, Mode.Light)
        assertTrue(soft.shapes.md.value > sharp.shapes.md.value) { "Soft corners should be rounder" }
        assertFalse(soft.elevation.isFlat) { "Soft should cast shadows" }
        assertEquals(Density.Comfortable, soft.spacing.density)
        assertEquals(ListRowStyle.Card, soft.variants.listRow)
    }

    @Test
    @DisplayName("Dashboard preset (default): ~14px cards, soft shadow, comfortable density, indigo sidebar")
    fun dashboardPreset() {
        val t = gestaltTheme(Skin.Dashboard, Mode.Light)
        assertEquals(t, gestaltTheme()) { "Dashboard is the default skin/mode" }
        assertTrue(t.shapes.md.value in 12f..16f) { "Dashboard cards are ~14dp" }
        assertFalse(t.elevation.isFlat) { "Dashboard casts a soft shadow" }
        assertEquals(Density.Comfortable, t.spacing.density)
        assertEquals(NavStyle.Sidebar, t.variants.nav)
        assertNotEquals(t.colors.sidebar, t.colors.surface) { "the sidebar rail is its own (indigo) surface" }
    }

    @Test
    @DisplayName("density override changes spacing without touching the skin's shape/elevation/variants")
    fun densityOverride() {
        val comfy = gestaltTheme(Skin.Dashboard, Mode.Light, density = Density.Comfortable)
        val compact = gestaltTheme(Skin.Dashboard, Mode.Light, density = Density.Compact)
        assertEquals(Density.Compact, compact.spacing.density)
        assertNotEquals(comfy.spacing, compact.spacing)
        assertEquals(comfy.shapes, compact.shapes)
        assertEquals(comfy.variants, compact.variants)
    }

    @Test
    @DisplayName("Skin and Mode are independent axes — all four combinations are distinct themes")
    fun axesAreIndependent() {
        val combos = listOf(
            gestaltTheme(Skin.Sharp, Mode.Light),
            gestaltTheme(Skin.Sharp, Mode.Dark),
            gestaltTheme(Skin.Soft, Mode.Light),
            gestaltTheme(Skin.Soft, Mode.Dark),
        )
        assertEquals(4, combos.toSet().size) { "all four skin×mode combinations should differ" }
    }
}
