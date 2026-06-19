/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.design.branding

import androidx.compose.ui.graphics.Color
import com.gestalt.design.theme.Mode
import com.gestalt.design.theme.Skin
import com.gestalt.design.theme.gestaltTheme
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class BrandingTest {

    @Test
    @DisplayName("the built-in Gestalt brand loads with the expected name, logo, and palette")
    fun gestaltDefaultLoads() {
        val b = Branding.Gestalt
        assertEquals("Gestalt Cloud Connect", b.appName)
        assertTrue(b.logo is LogoAsset.Resource)
        assertEquals(Skin.Dashboard, b.defaultSkin)
        assertEquals(Mode.Light, b.defaultMode)
        assertEquals(Color(0xFF2E3178), b.palette.primary) // Indigo
        assertEquals(Color(0xFF9E3F91), b.palette.accent1) // Magenta
        assertEquals(Color(0xFFEDDD63), b.palette.accent2) // Gold
    }

    @Test
    @DisplayName("theme primary/accent derive from Branding.palette")
    fun themeDerivesFromBranding() {
        val b = Branding.Gestalt
        val light = gestaltTheme(b.defaultSkin, Mode.Light, b)
        assertEquals(b.palette.primary, light.colors.primary)
        assertEquals(b.palette.accent1, light.colors.accent)
    }

    @Test
    @DisplayName("a different brand palette restyles the theme without touching structure")
    fun customBrandReseedsPalette() {
        val custom = Branding.Gestalt.copy(
            appName = "Acme Connect",
            palette = BrandPalette(
                primary = Color(0xFF0B6E4F),
                accent1 = Color(0xFFD17A22),
                accent2 = Color(0xFF8AB17D),
                neutral = Color(0xFF5B6B7B),
            ),
        )
        val theme = gestaltTheme(Skin.Sharp, Mode.Light, custom)
        assertEquals(Color(0xFF0B6E4F), theme.colors.primary)
        assertEquals(Color(0xFFD17A22), theme.colors.accent)
        // Structure is the same Sharp preset regardless of brand.
        assertEquals(gestaltTheme(Skin.Sharp, Mode.Light).variants, theme.variants)
    }
}
