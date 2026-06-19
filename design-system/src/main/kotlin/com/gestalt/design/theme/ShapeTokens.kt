/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.design.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp

/**
 * Corner radii (none → lg) and the standard hairline [borderWidth]. The [Skin] drives these: Sharp
 * keeps radii at ~0–2dp for a precise, instrument-like feel; Soft rounds them off.
 */
@Immutable
data class ShapeTokens(
    val none: Dp,
    val sm: Dp,
    val md: Dp,
    val lg: Dp,
    val borderWidth: Dp,
)
