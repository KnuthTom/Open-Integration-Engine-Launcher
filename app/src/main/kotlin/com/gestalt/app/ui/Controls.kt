/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gestalt.app.EnvCue
import com.gestalt.design.components.GestaltButton
import com.gestalt.design.components.GestaltIcon
import com.gestalt.design.components.GestaltLabel
import com.gestalt.design.components.GestaltStatusPill
import com.gestalt.design.components.StatusLevel
import com.gestalt.design.icons.ChromeIcon
import com.gestalt.design.theme.ButtonStyle
import com.gestalt.design.theme.GestaltTheme

/** A segmented single-select control built from themed buttons (active = Filled, others = Ghost). */
@Composable
fun <T> SegmentedToggle(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(GestaltTheme.spacing.paddingSmall)) {
        options.forEach { option ->
            GestaltButton(
                text = label(option),
                onClick = { onSelect(option) },
                style = if (option == selected) ButtonStyle.Filled else ButtonStyle.Ghost,
                enabled = enabled,
            )
        }
    }
}

/** A labeled boolean toggle rendered as an Off/On segmented control. */
@Composable
fun LabeledToggle(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GestaltTheme.spacing.paddingSmall)) {
        GestaltLabel(label)
        SegmentedToggle(
            options = listOf(false, true),
            selected = checked,
            onSelect = onChange,
            label = { if (it) "On" else "Off" },
        )
    }
}

/** A compact clickable icon button (token-tinted, no ripple). */
@Composable
fun GestaltIconButton(
    icon: ChromeIcon,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = GestaltTheme.colors.textSecondary,
    enabled: Boolean = true,
    size: Dp = 18.dp,
) {
    val interaction = remember { MutableInteractionSource() }
    GestaltIcon(
        icon = icon,
        tint = if (enabled) tint else GestaltTheme.colors.textMuted,
        size = size,
        modifier = modifier
            .clip(RoundedCornerShape(GestaltTheme.shapes.sm))
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .padding(GestaltTheme.spacing.paddingSmall),
    )
}

/** A square multi-select checkbox (token-tinted): outlined when unchecked, primary-filled with a tick when checked. */
@Composable
fun SelectCheckbox(checked: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val colors = GestaltTheme.colors
    val shape = RoundedCornerShape(GestaltTheme.shapes.sm)
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(18.dp)
            .clip(shape)
            .background(if (checked) colors.primary else Color.Transparent, shape)
            .border(1.5.dp, if (checked) colors.primary else colors.outline, shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) GestaltIcon(ChromeIcon.CheckCircle, tint = colors.onPrimary, size = 13.dp)
    }
}

/** A small environment-hint pill (Prod highlighted, others neutral). */
@Composable
fun EnvChip(cue: EnvCue) {
    val (text, level) = when (cue) {
        EnvCue.Prod -> "PROD" to StatusLevel.Warning
        EnvCue.Staging -> "STAGE" to StatusLevel.Warning
        EnvCue.Test -> "TEST" to StatusLevel.Neutral
        EnvCue.Dev -> "DEV" to StatusLevel.Neutral
    }
    GestaltStatusPill(text = text, level = level)
}

/** A subtle "no transport security" pill for plain-http connections (allowed, but flagged). */
@Composable
fun InsecureTransportChip() {
    GestaltStatusPill(text = "NO TLS", level = StatusLevel.Warning)
}

/** A thin themed divider line color (for ad-hoc separators where a token-colored background is wanted). */
@Composable
fun dividerColor(): Color = GestaltTheme.colors.outline

/** Convenience: a subtle banner background. */
@Composable
fun bannerBackground(modifier: Modifier): Modifier =
    modifier.clip(RoundedCornerShape(GestaltTheme.shapes.sm)).background(GestaltTheme.colors.surfaceVariant)
