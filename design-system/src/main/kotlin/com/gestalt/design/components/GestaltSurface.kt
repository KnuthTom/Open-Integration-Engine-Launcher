/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.design.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gestalt.design.theme.GestaltTheme

/**
 * The base themed container: a background fill, the token corner shape, an optional border, and
 * optional shadow elevation. Everything visual comes from tokens or the caller.
 */
@Composable
fun GestaltSurface(
    modifier: Modifier = Modifier,
    color: Color = GestaltTheme.colors.surface,
    shape: Shape = RoundedCornerShape(GestaltTheme.shapes.md),
    border: BorderStroke? = null,
    elevation: Dp = GestaltTheme.elevation.level0,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .shadow(elevation, shape, clip = false)
            .clip(shape)
            .background(color, shape)
            .then(if (border != null) Modifier.border(border, shape) else Modifier),
    ) { content() }
}

/**
 * A content card. On a flat (Sharp) skin it separates with a hairline border; on an elevated (Soft)
 * skin it casts a shadow instead — the same component, re-skinned by the active elevation tokens.
 */
@Composable
fun GestaltCard(
    modifier: Modifier = Modifier,
    color: Color = GestaltTheme.colors.surface,
    content: @Composable () -> Unit,
) {
    val shapes = GestaltTheme.shapes
    val elevation = GestaltTheme.elevation
    val flat = elevation.isFlat
    // A hairline border always; an elevated skin (dashboard/soft) adds a soft shadow on top of it.
    GestaltSurface(
        modifier = modifier,
        color = color,
        shape = RoundedCornerShape(shapes.md),
        border = BorderStroke(shapes.borderWidth, GestaltTheme.colors.outline),
        elevation = if (flat) 0.dp else elevation.level2,
    ) {
        Box(Modifier.padding(GestaltTheme.spacing.padding)) { content() }
    }
}
