/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gestalt.design.theme.GestaltTheme

/** Semantic status level — maps to the success/warning/error tokens (Neutral = muted). */
enum class StatusLevel { Success, Warning, Error, Neutral }

@Composable
@ReadOnlyComposable
private fun colorFor(level: StatusLevel): Color = when (level) {
    StatusLevel.Success -> GestaltTheme.colors.success
    StatusLevel.Warning -> GestaltTheme.colors.warning
    StatusLevel.Error -> GestaltTheme.colors.error
    StatusLevel.Neutral -> GestaltTheme.colors.textMuted
}

/** A solid status dot. */
@Composable
fun GestaltStatusDot(level: StatusLevel, modifier: Modifier = Modifier, size: Dp = 8.dp) {
    androidx.compose.foundation.layout.Box(modifier.size(size).background(colorFor(level), CircleShape))
}

/** A pill: a colored dot + label on a faint tint of the status color. Readable on light and dark. */
@Composable
fun GestaltStatusPill(text: String, level: StatusLevel, modifier: Modifier = Modifier) {
    val spacing = GestaltTheme.spacing
    val color = colorFor(level)
    Row(
        modifier = modifier
            .background(color.copy(alpha = 0.16f), RoundedCornerShape(percent = 50))
            .padding(horizontal = spacing.padding, vertical = spacing.paddingSmall),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.paddingSmall),
    ) {
        GestaltStatusDot(level)
        GestaltText(text, role = TextRole.Label, color = GestaltTheme.colors.textPrimary)
    }
}
