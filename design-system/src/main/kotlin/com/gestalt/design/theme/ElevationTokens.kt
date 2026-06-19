/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.design.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp

/**
 * Shadow elevation levels. [level0] is always flat (0dp). A Sharp skin keeps every level at 0 and
 * relies on hairline borders to separate surfaces; a Soft skin gives the upper levels real shadows.
 */
@Immutable
data class ElevationTokens(
    val level0: Dp,
    val level1: Dp,
    val level2: Dp,
    val level3: Dp,
) {
    /** True when the skin is flat (no shadows) — components then fall back to borders for separation. */
    val isFlat: Boolean get() = level1.value == 0f
}
