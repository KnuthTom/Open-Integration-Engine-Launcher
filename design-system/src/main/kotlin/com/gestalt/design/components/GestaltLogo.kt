/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.design.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gestalt.design.branding.Branding
import com.gestalt.design.branding.LogoAsset
import com.gestalt.design.icons.IconResources
import com.gestalt.design.theme.GestaltTheme

/**
 * The brand header: the logo mark + (optionally) the app name, both read from [Branding] — never
 * hardcoded. A [LogoAsset.Resource] is loaded from the classpath (SVG/PNG); if it is missing it falls
 * back to a drawn mark so the header is never empty.
 */
@Composable
fun GestaltLogo(
    modifier: Modifier = Modifier,
    branding: Branding = GestaltTheme.branding,
    showName: Boolean = true,
    markSize: Dp = 28.dp,
) {
    val spacing = GestaltTheme.spacing
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.paddingSmall),
    ) {
        LogoMark(branding.logo, markSize)
        if (showName) GestaltText(branding.appName, role = TextRole.Title, color = GestaltTheme.colors.textPrimary)
    }
}

@Composable
private fun LogoMark(logo: LogoAsset, size: Dp) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    when (logo) {
        is LogoAsset.Resource -> {
            val painter = remember(logo.path, density) {
                runCatching { IconResources.painter(logo.path, density) }.getOrNull()
            }
            if (painter != null) {
                Image(painter = painter, contentDescription = "logo", modifier = Modifier.size(size))
            } else {
                DrawnMark(size)
            }
        }
        LogoAsset.Wordmark -> DrawnMark(size)
    }
}

/** Fallback brand mark: a primary rounded tile with an accent dot — uses only color/shape tokens. */
@Composable
private fun DrawnMark(size: Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(GestaltTheme.shapes.md))
            .background(GestaltTheme.colors.primary),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(size / 3).background(GestaltTheme.colors.accent, CircleShape))
    }
}
