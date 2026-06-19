/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.app.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.gestalt.design.branding.Branding
import com.gestalt.design.components.GestaltButton
import com.gestalt.design.components.GestaltLabel
import com.gestalt.design.components.GestaltLogo
import com.gestalt.design.components.GestaltScaffold
import com.gestalt.design.components.NavItem
import com.gestalt.design.icons.ChromeIcon
import com.gestalt.design.icons.ConnectionIcon
import com.gestalt.design.theme.ButtonStyle
import com.gestalt.design.theme.GestaltTheme
import com.gestalt.design.theme.Mode
import com.gestalt.design.theme.Skin

/**
 * The component gallery — the Phase 3a acceptance artifact. It renders every base component and both
 * icon sets, with top controls to flip the [Skin] (Sharp/Soft) and [Mode] (Light/Dark). Flipping
 * either re-skins the ENTIRE gallery: corner radius, density, elevation, the button/row/nav/field
 * variants, and colors all change at once — proving the look is data, not hardcoded.
 */
@Composable
fun GalleryApp(branding: Branding = Branding.Default) {
    var skin by remember { mutableStateOf(branding.defaultSkin) }
    var mode by remember { mutableStateOf(branding.defaultMode) }
    var section by remember { mutableStateOf("components") }
    // Shared by the connection-icon picker and the sample connection row.
    var connectionIcon by remember { mutableStateOf(ConnectionIcon.default) }

    GestaltTheme(skin = skin, mode = mode, branding = branding) {
        Column(Modifier.fillMaxSize().background(GestaltTheme.colors.background)) {
            TopControls(
                branding = branding,
                skin = skin,
                onSkin = { skin = it },
                mode = mode,
                onMode = { mode = it },
            )

            val navItems = listOf(
                NavItem("components", "Components", ChromeIcon.Sliders),
                NavItem("chrome", "Chrome icons", ChromeIcon.Search),
                NavItem("connection", "Connection icons", ChromeIcon.Server),
            )
            GestaltScaffold(
                items = navItems,
                selectedKey = section,
                onSelect = { section = it },
                modifier = Modifier.weight(1f),
                header = { GestaltLogo(showName = false, markSize = 22.dp) },
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(GestaltTheme.spacing.paddingLarge),
                ) {
                    when (section) {
                        "components" -> ComponentsPanel(connectionIcon)
                        "chrome" -> ChromeIconsPanel()
                        "connection" -> ConnectionIconsPanel(connectionIcon) { connectionIcon = it }
                    }
                }
            }
        }
    }
}

/** Top app bar: brand lockup from [Branding] on the left; Skin + Mode toggles on the right. */
@Composable
private fun TopControls(
    branding: Branding,
    skin: Skin,
    onSkin: (Skin) -> Unit,
    mode: Mode,
    onMode: (Mode) -> Unit,
) {
    val colors = GestaltTheme.colors
    val spacing = GestaltTheme.spacing
    val density = LocalDensity.current
    val borderPx = with(density) { GestaltTheme.shapes.borderWidth.toPx() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface)
            .drawBehind {
                drawLine(colors.outline, Offset(0f, size.height), Offset(size.width, size.height), borderPx)
            }
            .padding(horizontal = spacing.paddingLarge, vertical = spacing.padding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GestaltLogo()
        Spacer(Modifier.weight(1f))
        if (branding.exposedOptions.allowSkinToggle) {
            SegmentedToggle("Skin", listOf(Skin.Sharp, Skin.Soft), skin, onSkin) { it.name }
            Spacer(Modifier.width(spacing.paddingLarge))
        }
        if (branding.exposedOptions.allowModeToggle) {
            SegmentedToggle("Mode", listOf(Mode.Light, Mode.Dark), mode, onMode) { it.name }
        }
    }
}

/** A small segmented control built from themed buttons: the active option is Filled, others Ghost. */
@Composable
private fun <T> SegmentedToggle(
    label: String,
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    name: (T) -> String,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GestaltTheme.spacing.paddingSmall)) {
        GestaltLabel(label)
        options.forEach { option ->
            GestaltButton(
                text = name(option),
                onClick = { onSelect(option) },
                style = if (option == selected) ButtonStyle.Filled else ButtonStyle.Ghost,
            )
        }
    }
}
