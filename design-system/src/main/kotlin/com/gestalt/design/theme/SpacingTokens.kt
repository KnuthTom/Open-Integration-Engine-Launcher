/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.design.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp

/** How tightly the UI is packed. The [Skin] picks one; [Spacious] is available for future branding. */
enum class Density { Compact, Comfortable, Spacious }

/**
 * Spacing and density. [unit] is the base rhythm; [controlHeight] is the height of interactive
 * controls (buttons, fields, rows). [paddingSmall]/[padding]/[paddingLarge] are the resolved content
 * paddings for the active [density], and [gap] is the default space between sibling elements.
 */
@Immutable
data class SpacingTokens(
    val density: Density,
    val unit: Dp,
    val controlHeight: Dp,
    val paddingSmall: Dp,
    val padding: Dp,
    val paddingLarge: Dp,
    val gap: Dp,
)
