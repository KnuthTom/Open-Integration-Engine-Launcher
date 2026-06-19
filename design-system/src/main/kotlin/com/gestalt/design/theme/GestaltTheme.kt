/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.design.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import com.gestalt.design.branding.Branding

/**
 * The resolved theme for the current subtree. Reading it outside a `GestaltTheme { }` is a
 * programming error and fails loudly rather than silently rendering an unthemed UI.
 */
val LocalGestaltTheme = staticCompositionLocalOf<GestaltThemeData> {
    error("No GestaltThemeData provided. Wrap your UI in GestaltTheme { … }.")
}

/** The active brand. Defaults to [Branding.Default] so components always have a brand to read. */
val LocalBranding = staticCompositionLocalOf { Branding.Default }

/**
 * Provide a theme built from the [skin]/[mode]/[branding] axes to [content]. This is the usual entry
 * point: flipping [skin] or [mode] rebuilds the [GestaltThemeData] and re-skins the whole subtree.
 */
@Composable
fun GestaltTheme(
    skin: Skin,
    mode: Mode,
    branding: Branding = Branding.Default,
    density: Density? = null,
    content: @Composable () -> Unit,
) {
    val theme = remember(skin, mode, branding, density) { gestaltTheme(skin, mode, branding, density) }
    GestaltTheme(theme = theme, branding = branding, content = content)
}

/** Provide an already-built [theme] (and [branding]) directly — handy for previews and tests. */
@Composable
fun GestaltTheme(
    theme: GestaltThemeData,
    branding: Branding = Branding.Default,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalGestaltTheme provides theme,
        LocalBranding provides branding,
        content = content,
    )
}

/**
 * Material3-style accessor: components read `GestaltTheme.colors`, `GestaltTheme.shapes`, etc. inside
 * a composition. Keeps call sites terse and ensures every value comes from the active theme.
 */
object GestaltTheme {
    val colors: ColorTokens
        @Composable @ReadOnlyComposable get() = LocalGestaltTheme.current.colors

    val typography: TypographyTokens
        @Composable @ReadOnlyComposable get() = LocalGestaltTheme.current.typography

    val shapes: ShapeTokens
        @Composable @ReadOnlyComposable get() = LocalGestaltTheme.current.shapes

    val spacing: SpacingTokens
        @Composable @ReadOnlyComposable get() = LocalGestaltTheme.current.spacing

    val elevation: ElevationTokens
        @Composable @ReadOnlyComposable get() = LocalGestaltTheme.current.elevation

    val variants: ComponentVariants
        @Composable @ReadOnlyComposable get() = LocalGestaltTheme.current.variants

    val branding: Branding
        @Composable @ReadOnlyComposable get() = LocalBranding.current
}
