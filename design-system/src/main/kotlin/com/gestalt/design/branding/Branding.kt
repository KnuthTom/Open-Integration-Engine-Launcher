/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.design.branding

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import com.gestalt.design.theme.Mode
import com.gestalt.design.theme.Skin

/**
 * The brand palette that seeds a theme. [primary] and [accent1] flow into the theme's
 * `colors.primary` / `colors.accent`; [accent2] and [neutral] are secondary brand colors used
 * sparingly (highlights / status, and secondary text / outlines respectively).
 */
@Immutable
data class BrandPalette(
    val primary: Color,
    val accent1: Color,
    val accent2: Color,
    val neutral: Color,
)

/**
 * The header/identity asset for a brand. [Resource] points at a bundled classpath image (PNG today,
 * SVG once available — the loader handles either); [Wordmark] falls back to drawing the app name in
 * the brand display type. Routing the logo through this type means no component hardcodes an asset.
 */
sealed interface LogoAsset {
    data class Resource(val path: String) : LogoAsset
    data object Wordmark : LogoAsset
}

/** Which theme controls a branding chooses to expose to the end user (e.g. in the gallery header). */
@Immutable
data class ExposedOptions(
    val allowSkinToggle: Boolean = true,
    val allowModeToggle: Boolean = true,
)

/**
 * Everything brand-specific in one value: the [appName], the header [logo], the [palette] that seeds
 * the theme, the [defaultSkin]/[defaultMode] the app opens with, and the [exposedOptions]. All
 * name/logo/color access in the UI routes through a [Branding] — nothing is hardcoded — so a future
 * branding pack can restyle the whole app by supplying a different value.
 */
@Immutable
data class Branding(
    val appName: String,
    val logo: LogoAsset,
    val palette: BrandPalette,
    val defaultSkin: Skin,
    val defaultMode: Mode,
    val exposedOptions: ExposedOptions = ExposedOptions(),
) {
    companion object {
        /**
         * The vendor-neutral brand the public build ships with. It draws its name as a wordmark (no
         * bundled logo asset) over a neutral palette, so the default app carries no third-party or
         * originator branding. The [Gestalt] brand below is an OPTIONAL branding pack — pass it
         * explicitly (see `Main`) to restyle the app with the original Gestalt identity + logo.
         *
         * TODO(maintainer): choose the final product name/branding. This default mirrors the repository
         * name (Open Integration Engine Launcher); to rename, change [appName] here and the packaging
         * names in `app/build.gradle.kts`.
         */
        val Default: Branding = Branding(
            appName = "Open Integration Engine Launcher",
            logo = LogoAsset.Wordmark,
            palette = BrandPalette(
                primary = Color(0xFF21506E), // deep teal-blue
                accent1 = Color(0xFF4AA3C7), // cyan-blue
                accent2 = Color(0xFFE0A24A), // amber
                neutral = Color(0xFF5B6B7A), // blue-grey
            ),
            defaultSkin = Skin.Dashboard,
            defaultMode = Mode.Light,
        )

        /**
         * The original Gestalt brand — an OPTIONAL branding pack, not the default. Palette echoes H&E
         * histology staining — Indigo base (hematoxylin) with sparing Magenta (eosin) and Gold accents.
         * Opens on the Dashboard look in Light mode; Sharp/Soft and Dark remain selectable presets.
         */
        val Gestalt: Branding = Branding(
            appName = "Gestalt Cloud Connect",
            logo = LogoAsset.Resource("branding/gestalt-wordmark.svg"),
            palette = BrandPalette(
                primary = Color(0xFF2E3178), // Indigo
                accent1 = Color(0xFF9E3F91), // Magenta
                accent2 = Color(0xFFEDDD63), // Gold
                neutral = Color(0xFF546090), // blue-grey
            ),
            defaultSkin = Skin.Dashboard,
            defaultMode = Mode.Light,
        )
    }
}
