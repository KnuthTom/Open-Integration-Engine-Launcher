/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.design.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily

/**
 * The type scale. Roles run largest → smallest: [display], [title], [heading], [body], [label], and a
 * fixed-width [mono] used for console / technical text. [family] and [monoFamily] are kept on the
 * tokens so a branding pack can swap fonts wholesale later without touching components.
 */
@Immutable
data class TypographyTokens(
    val family: FontFamily,
    val monoFamily: FontFamily,
    val display: TextStyle,
    val title: TextStyle,
    val heading: TextStyle,
    val body: TextStyle,
    val label: TextStyle,
    val mono: TextStyle,
)
