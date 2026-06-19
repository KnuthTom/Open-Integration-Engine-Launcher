/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.design.theme

import androidx.compose.runtime.Immutable

/**
 * The complete, resolved look of the app as data. Produced by [gestaltTheme] from a [Skin], a [Mode],
 * and a [com.gestalt.design.branding.Branding], and provided to the tree by `GestaltTheme { }`.
 * Components read everything they draw from here — no value is hardcoded in a component.
 */
@Immutable
data class GestaltThemeData(
    val colors: ColorTokens,
    val typography: TypographyTokens,
    val shapes: ShapeTokens,
    val spacing: SpacingTokens,
    val elevation: ElevationTokens,
    val variants: ComponentVariants,
)
