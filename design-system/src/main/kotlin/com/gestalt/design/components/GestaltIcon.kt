/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.design.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gestalt.design.icons.ChromeIcon
import com.gestalt.design.icons.ConnectionIcon
import com.gestalt.design.icons.IconResources
import com.gestalt.design.icons.PackIcons
import com.gestalt.design.theme.GestaltTheme

/** A functional chrome icon, tinted from the color tokens (defaults to primary text color). */
@Composable
fun GestaltIcon(
    icon: ChromeIcon,
    modifier: Modifier = Modifier,
    tint: Color = GestaltTheme.colors.textPrimary,
    size: Dp = 20.dp,
) = SvgIcon(icon.resourcePath, icon.label, modifier, tint, size)

/** A user-pickable connection icon, tinted from the color tokens (defaults to primary). */
@Composable
fun GestaltConnectionIcon(
    icon: ConnectionIcon,
    modifier: Modifier = Modifier,
    tint: Color = GestaltTheme.colors.primary,
    size: Dp = 24.dp,
) = SvgIcon(icon.resourcePath, icon.label, modifier, tint, size)

@Composable
private fun SvgIcon(resourcePath: String, contentDescription: String, modifier: Modifier, tint: Color, size: Dp) {
    val density = LocalDensity.current
    val painter = remember(resourcePath, density) { IconResources.painter(resourcePath, density) }
    Image(
        painter = painter,
        contentDescription = contentDescription,
        modifier = modifier.size(size),
        colorFilter = ColorFilter.tint(tint),
    )
}

/**
 * A user-uploaded custom icon, rendered from its (already-sanitized) [bytes]. A `currentColor` SVG is
 * [tintable] and recolored from the tokens; raster and fixed-color SVGs render as authored. Pass stable
 * [bytes] (e.g. cached by the caller) so the painter isn't rebuilt on every recomposition.
 */
@Composable
fun GestaltCustomIcon(
    bytes: ByteArray,
    ext: String,
    contentDescription: String,
    tintable: Boolean,
    modifier: Modifier = Modifier,
    tint: Color = GestaltTheme.colors.primary,
    size: Dp = 24.dp,
) {
    val density = LocalDensity.current
    val painter = remember(bytes, ext, density) { IconResources.painterFromBytes(bytes, ext, density) }
    Image(
        painter = painter,
        contentDescription = contentDescription,
        modifier = modifier.size(size),
        colorFilter = if (tintable) ColorFilter.tint(tint) else null,
    )
}

/** A build-time pack icon, loaded from its bundled classpath resource (SVGs tint; rasters render as authored). */
@Composable
fun GestaltPackIcon(
    entry: PackIcons.Entry,
    modifier: Modifier = Modifier,
    tint: Color = GestaltTheme.colors.primary,
    size: Dp = 24.dp,
) {
    val density = LocalDensity.current
    val painter = remember(entry.resourcePath, density) { IconResources.painterFromResource(entry.resourcePath, density) }
    Image(
        painter = painter,
        contentDescription = entry.label,
        modifier = modifier.size(size),
        colorFilter = if (entry.tintable) ColorFilter.tint(tint) else null,
    )
}
