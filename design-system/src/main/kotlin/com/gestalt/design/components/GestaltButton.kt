/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.design.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.gestalt.design.icons.ChromeIcon
import com.gestalt.design.theme.ButtonStyle
import com.gestalt.design.theme.GestaltTheme

/**
 * A themed button. Defaults to the skin's [ButtonStyle] (the theme variant); pass [style] to override.
 * Filled uses the primary color; Outlined draws a primary hairline; Ghost is text-only. Shape,
 * height, padding, and colors all come from tokens.
 */
@Composable
fun GestaltButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: ButtonStyle = GestaltTheme.variants.button,
    enabled: Boolean = true,
    leadingIcon: ChromeIcon? = null,
) {
    val colors = GestaltTheme.colors
    val spacing = GestaltTheme.spacing
    val shape = RoundedCornerShape(GestaltTheme.shapes.md)
    val interaction = remember { MutableInteractionSource() }

    val background: Color
    val foreground: Color
    val border: BorderStroke?
    when (style) {
        ButtonStyle.Filled -> {
            background = colors.primary; foreground = colors.onPrimary; border = null
        }
        ButtonStyle.Outlined -> {
            background = Color.Transparent
            foreground = colors.primary
            border = BorderStroke(GestaltTheme.shapes.borderWidth, colors.primary)
        }
        ButtonStyle.Ghost -> {
            background = Color.Transparent; foreground = colors.primary; border = null
        }
    }

    Row(
        modifier = modifier
            .height(spacing.controlHeight)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(shape)
            .background(background, shape)
            .then(if (border != null) Modifier.border(border, shape) else Modifier)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = spacing.padding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.paddingSmall),
    ) {
        if (leadingIcon != null) GestaltIcon(leadingIcon, tint = foreground, size = 18.dp)
        GestaltText(text, role = TextRole.Label, color = foreground)
    }
}
