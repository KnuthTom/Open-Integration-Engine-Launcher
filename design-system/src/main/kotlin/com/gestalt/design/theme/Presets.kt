/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.design.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gestalt.design.branding.BrandPalette

/**
 * Preset token sets the [gestaltTheme] factory assembles from. Color presets depend on the [Mode] and
 * are seeded by the brand palette; shape/spacing/elevation/variant presets depend on the [Skin]. The
 * two axes are independent — any [Skin] composes with any [Mode].
 */
internal object Presets {

    // ---- Type scale (shared across skins/modes) --------------------------------------------------

    fun typography(): TypographyTokens {
        val sans = FontFamily.SansSerif
        val mono = FontFamily.Monospace
        fun style(family: FontFamily, size: Int, weight: FontWeight, line: Int, tracking: Double = 0.0) =
            TextStyle(
                fontFamily = family,
                fontSize = size.sp,
                fontWeight = weight,
                lineHeight = line.sp,
                letterSpacing = tracking.sp,
            )
        return TypographyTokens(
            family = sans,
            monoFamily = mono,
            display = style(sans, 30, FontWeight.Bold, 36),
            title = style(sans, 22, FontWeight.SemiBold, 28),
            heading = style(sans, 16, FontWeight.SemiBold, 22, 0.1),
            body = style(sans, 14, FontWeight.Normal, 20),
            label = style(sans, 12, FontWeight.SemiBold, 16, 0.4),
            mono = style(mono, 13, FontWeight.Normal, 18),
        )
    }

    // ---- Color presets (by Mode, seeded by Branding) ---------------------------------------------

    fun lightColors(palette: BrandPalette): ColorTokens = ColorTokens(
        background = Color(0xFFF5F6F9),
        surface = Color(0xFFFFFFFF),
        surfaceVariant = Color(0xFFEDEFF4),
        primary = palette.primary,
        onPrimary = Color(0xFFFFFFFF),
        accent = palette.accent1,
        onAccent = Color(0xFFFFFFFF),
        success = Color(0xFF2E7D5B),
        warning = palette.accent2,
        error = Color(0xFFC2403D),
        onError = Color(0xFFFFFFFF),
        outline = Color(0xFFD3D8E2),
        textPrimary = Color(0xFF14161C),
        textSecondary = palette.neutral,
        textMuted = lerp(palette.neutral, Color.White, 0.40f),
        sidebar = lerp(palette.primary, Color.Black, 0.34f),
        onSidebar = lerp(palette.primary, Color.White, 0.64f),
        onSidebarActive = Color.White,
        sidebarActive = lerp(palette.primary, Color.White, 0.12f),
    )

    fun darkColors(palette: BrandPalette): ColorTokens = ColorTokens(
        background = Color(0xFF0E1116),
        surface = Color(0xFF161A22),
        surfaceVariant = Color(0xFF1F2530),
        primary = lerp(palette.primary, Color.White, 0.34f),
        onPrimary = Color(0xFFFFFFFF),
        accent = lerp(palette.accent1, Color.White, 0.22f),
        onAccent = Color(0xFF170A15),
        success = Color(0xFF4BBE8A),
        warning = palette.accent2,
        error = Color(0xFFE5706C),
        onError = Color(0xFF1A0707),
        outline = Color(0xFF2C3340),
        textPrimary = Color(0xFFEDF0F6),
        textSecondary = lerp(palette.neutral, Color.White, 0.45f),
        textMuted = lerp(palette.neutral, Color.White, 0.24f),
        sidebar = lerp(palette.primary, Color.Black, 0.62f),
        onSidebar = lerp(palette.primary, Color.White, 0.50f),
        onSidebarActive = Color.White,
        sidebarActive = lerp(palette.primary, Color.White, 0.10f),
    )

    // ---- Shape / spacing / elevation / variant presets (by Skin) ---------------------------------

    val sharpShapes = ShapeTokens(none = 0.dp, sm = 1.dp, md = 2.dp, lg = 3.dp, borderWidth = 1.dp)
    val softShapes = ShapeTokens(none = 0.dp, sm = 6.dp, md = 12.dp, lg = 20.dp, borderWidth = 1.dp)
    /** The dashboard look: ~14px cards (§6.3.1). */
    val dashboardShapes = ShapeTokens(none = 0.dp, sm = 8.dp, md = 14.dp, lg = 22.dp, borderWidth = 1.dp)

    val flatElevation = ElevationTokens(level0 = 0.dp, level1 = 0.dp, level2 = 0.dp, level3 = 0.dp)
    val softElevation = ElevationTokens(level0 = 0.dp, level1 = 2.dp, level2 = 6.dp, level3 = 12.dp)

    val sharpVariants = ComponentVariants(
        button = ButtonStyle.Filled,
        listRow = ListRowStyle.Flat,
        nav = NavStyle.Sidebar,
        field = FieldStyle.Outlined,
    )
    val softVariants = ComponentVariants(
        button = ButtonStyle.Outlined,
        listRow = ListRowStyle.Card,
        // Sidebar (like the other skins) so the Connections rail tools render on every skin; Soft still
        // differs in shape/elevation/density/rows/fields.
        nav = NavStyle.Sidebar,
        field = FieldStyle.Underline,
    )
    /** Dashboard: a left sidebar rail, filled primary buttons, card rows, outlined fields. */
    val dashboardVariants = ComponentVariants(
        button = ButtonStyle.Filled,
        listRow = ListRowStyle.Card,
        nav = NavStyle.Sidebar,
        field = FieldStyle.Outlined,
    )

    fun spacing(density: Density): SpacingTokens = when (density) {
        Density.Compact -> SpacingTokens(
            density = density, unit = 4.dp, controlHeight = 32.dp,
            paddingSmall = 4.dp, padding = 8.dp, paddingLarge = 12.dp, gap = 8.dp,
        )
        Density.Comfortable -> SpacingTokens(
            density = density, unit = 4.dp, controlHeight = 44.dp,
            paddingSmall = 8.dp, padding = 16.dp, paddingLarge = 24.dp, gap = 12.dp,
        )
        Density.Spacious -> SpacingTokens(
            density = density, unit = 4.dp, controlHeight = 52.dp,
            paddingSmall = 12.dp, padding = 24.dp, paddingLarge = 32.dp, gap = 16.dp,
        )
    }
}
