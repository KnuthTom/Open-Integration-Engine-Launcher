/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gestalt.design.theme.GestaltTheme

/**
 * A vertical group of single-select options drawn from tokens (accent ring + fill when selected).
 * Generic over the option type; [label] renders each.
 */
@Composable
fun <T> GestaltRadioGroup(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
) {
    val colors = GestaltTheme.colors
    val spacing = GestaltTheme.spacing
    Column(modifier, verticalArrangement = Arrangement.spacedBy(spacing.paddingSmall)) {
        options.forEach { option ->
            val isSelected = option == selected
            val interaction = remember(option) { MutableInteractionSource() }
            Row(
                modifier = Modifier
                    .clickable(interactionSource = interaction, indication = null) { onSelect(option) }
                    .padding(vertical = spacing.paddingSmall),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.paddingSmall),
            ) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .border(GestaltTheme.shapes.borderWidth, if (isSelected) colors.accent else colors.outline, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isSelected) Box(Modifier.size(8.dp).background(colors.accent, CircleShape))
                }
                GestaltText(label(option), role = TextRole.Body, color = colors.textPrimary)
            }
        }
    }
}
