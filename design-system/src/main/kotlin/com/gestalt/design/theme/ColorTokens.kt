/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.design.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * The full color surface of a theme. Every color a component can draw comes from here — components
 * never hardcode a [Color]. A [GestaltThemeData] carries exactly one [ColorTokens] set; the
 * [Mode] (Light/Dark) selects which set the factory builds, and [com.gestalt.design.branding.Branding]
 * seeds [primary] and [accent].
 */
@Immutable
data class ColorTokens(
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val primary: Color,
    val onPrimary: Color,
    val accent: Color,
    val onAccent: Color,
    val success: Color,
    val warning: Color,
    val error: Color,
    val onError: Color,
    val outline: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    // The navigation rail is its own surface (a deep-indigo rail in the dashboard look), distinct from
    // the light content surfaces, so it carries its own background + foreground + active-item tokens.
    val sidebar: Color,
    val onSidebar: Color,
    val onSidebarActive: Color,
    val sidebarActive: Color,
)
