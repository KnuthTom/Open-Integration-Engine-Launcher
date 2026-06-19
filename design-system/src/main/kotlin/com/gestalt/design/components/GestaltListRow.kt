/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import com.gestalt.design.theme.GestaltTheme
import com.gestalt.design.theme.ListRowStyle

/**
 * A list row with optional [leading]/[trailing] slots. Defaults to the skin's [ListRowStyle]: Flat
 * rows sit flush on the surface separated by a hairline; Card rows become individual elevated/bordered
 * cards. The gallery composes a "connection row" from this (icon + title + status pill).
 */
@Composable
fun GestaltListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    style: ListRowStyle = GestaltTheme.variants.listRow,
    onClick: (() -> Unit)? = null,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val colors = GestaltTheme.colors
    val spacing = GestaltTheme.spacing
    val interaction = remember { MutableInteractionSource() }
    val density = LocalDensity.current
    val borderPx = with(density) { GestaltTheme.shapes.borderWidth.toPx() }

    val clickMod = if (onClick != null) {
        Modifier.clickable(interactionSource = interaction, indication = null, onClick = onClick)
    } else {
        Modifier
    }

    val rowContent: @Composable (Modifier) -> Unit = { rowModifier ->
        Row(
            modifier = rowModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.padding),
        ) {
            leading?.invoke()
            Column(Modifier.weight(1f)) {
                GestaltText(title, role = TextRole.Heading, color = colors.textPrimary, maxLines = 1)
                if (subtitle != null) {
                    GestaltText(subtitle, role = TextRole.Body, color = colors.textSecondary, maxLines = 1)
                }
            }
            trailing?.invoke()
        }
    }

    when (style) {
        ListRowStyle.Card -> GestaltCard(modifier = modifier.fillMaxWidth()) {
            rowContent(clickMod.heightIn(min = spacing.controlHeight))
        }
        ListRowStyle.Flat -> Box(
            modifier = modifier
                .fillMaxWidth()
                .background(colors.surface)
                .drawBehind {
                    drawLine(colors.outline, Offset(0f, size.height), Offset(size.width, size.height), borderPx)
                },
        ) {
            rowContent(
                clickMod
                    .heightIn(min = spacing.controlHeight + spacing.padding)
                    .padding(horizontal = spacing.padding, vertical = spacing.paddingSmall),
            )
        }
    }
}
