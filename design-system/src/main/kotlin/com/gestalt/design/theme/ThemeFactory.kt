/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.design.theme

import com.gestalt.design.branding.Branding

/**
 * Shape/density/elevation/variant axis. Dashboard (the default) is the §6.3.1 look: rounded ~14px
 * cards, soft shadow, a deep-indigo sidebar rail, comfortable density. Sharp is precise and
 * instrument-like (square, flat, compact); Soft is rounded with top tabs. All stay as presets.
 */
enum class Skin { Dashboard, Sharp, Soft }

/** Color axis. Selects the Light or Dark [ColorTokens] set. Independent of [Skin]. */
enum class Mode { Light, Dark }

/**
 * Build a complete [GestaltThemeData] from the independent axes plus a brand.
 *
 * - [skin] drives shape, elevation, and the component variants (the structural look).
 * - [mode] drives the color set (Light/Dark).
 * - [density] optionally overrides the skin's natural density (the Comfortable/Compact toggle); null
 *   keeps the skin default (Sharp → Compact, otherwise Comfortable).
 * - [branding] seeds the palette — `primary`/`accent` come from the brand.
 *
 * The axes are orthogonal: every combination is valid.
 */
fun gestaltTheme(
    skin: Skin = Skin.Dashboard,
    mode: Mode = Mode.Light,
    branding: Branding = Branding.Gestalt,
    density: Density? = null,
): GestaltThemeData {
    val colors = when (mode) {
        Mode.Light -> Presets.lightColors(branding.palette)
        Mode.Dark -> Presets.darkColors(branding.palette)
    }
    val spacing = Presets.spacing(density ?: if (skin == Skin.Sharp) Density.Compact else Density.Comfortable)
    val typography = Presets.typography()
    return when (skin) {
        Skin.Dashboard -> GestaltThemeData(colors, typography, Presets.dashboardShapes, spacing, Presets.softElevation, Presets.dashboardVariants)
        Skin.Sharp -> GestaltThemeData(colors, typography, Presets.sharpShapes, spacing, Presets.flatElevation, Presets.sharpVariants)
        Skin.Soft -> GestaltThemeData(colors, typography, Presets.softShapes, spacing, Presets.softElevation, Presets.softVariants)
    }
}
