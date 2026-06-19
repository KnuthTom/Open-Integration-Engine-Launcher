/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.gestalt.design.icons.ChromeIcon
import com.gestalt.design.theme.FieldStyle
import com.gestalt.design.theme.GestaltTheme

/**
 * A themed single-line (by default) text field. Defaults to the skin's [FieldStyle]: Outlined draws a
 * full bordered box with the token corner radius; Underline is a flush field with only a bottom rule.
 */
@Composable
fun GestaltTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    style: FieldStyle = GestaltTheme.variants.field,
    leadingIcon: ChromeIcon? = null,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    val colors = GestaltTheme.colors
    val spacing = GestaltTheme.spacing
    val shape = RoundedCornerShape(GestaltTheme.shapes.sm)
    val density = LocalDensity.current
    val borderPx = with(density) { GestaltTheme.shapes.borderWidth.toPx() }

    val decoration: Modifier = when (style) {
        FieldStyle.Outlined -> Modifier
            .clip(shape)
            .background(colors.surface, shape)
            .border(GestaltTheme.shapes.borderWidth, colors.outline, shape)
        FieldStyle.Underline -> Modifier
            .background(colors.surfaceVariant)
            .drawBehind {
                drawLine(colors.outline, Offset(0f, size.height), Offset(size.width, size.height), borderPx)
            }
    }

    Column(modifier = modifier) {
        if (label != null) {
            GestaltLabel(label)
            Spacer(Modifier.height(spacing.paddingSmall))
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().heightIn(min = spacing.controlHeight).then(decoration),
            enabled = enabled,
            singleLine = singleLine,
            textStyle = GestaltTheme.typography.body.copy(color = colors.textPrimary),
            cursorBrush = SolidColor(colors.accent),
            visualTransformation = visualTransformation,
            decorationBox = { innerTextField ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.padding, vertical = spacing.paddingSmall),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.paddingSmall),
                ) {
                    if (leadingIcon != null) GestaltIcon(leadingIcon, tint = colors.textMuted, size = 18.dp)
                    Box(Modifier.weight(1f)) {
                        if (value.isEmpty() && placeholder != null) {
                            GestaltText(placeholder, role = TextRole.Body, color = colors.textMuted)
                        }
                        innerTextField()
                    }
                }
            },
        )
    }
}
