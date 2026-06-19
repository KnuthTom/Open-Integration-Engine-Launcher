/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.gestalt.design.icons.ChromeIcon
import com.gestalt.design.theme.GestaltTheme
import com.gestalt.design.theme.NavStyle

/** A navigation destination: stable [key], display [label], and chrome [icon]. */
data class NavItem(val key: String, val label: String, val icon: ChromeIcon)

/**
 * App shell with navigation that re-skins by the active [NavStyle]: Sidebar puts nav in a left rail;
 * TopTabs puts it in a top bar. The same [items]/[selectedKey]/[onSelect] drive both. [header] is an
 * optional brand/logo slot.
 */
@Composable
fun GestaltScaffold(
    items: List<NavItem>,
    selectedKey: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    style: NavStyle = GestaltTheme.variants.nav,
    header: @Composable (() -> Unit)? = null,
    /** Optional extra content rendered below the nav in the (scrollable) sidebar rail — e.g. screen tools. */
    sidebarExtra: @Composable (() -> Unit)? = null,
    footer: @Composable (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val colors = GestaltTheme.colors
    val spacing = GestaltTheme.spacing
    val density = LocalDensity.current
    val borderPx = with(density) { GestaltTheme.shapes.borderWidth.toPx() }

    when (style) {
        NavStyle.Sidebar -> Row(modifier.fillMaxSize().background(colors.background)) {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(236.dp)
                    .background(colors.sidebar)
                    .padding(spacing.padding),
                verticalArrangement = Arrangement.spacedBy(spacing.paddingSmall),
            ) {
                if (header != null) {
                    header()
                    Spacer(Modifier.height(spacing.padding))
                }
                // Nav + optional tools scroll together so a tall tools section can't overflow a short window.
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(spacing.paddingSmall),
                ) {
                    items.forEach { item ->
                        NavEntry(item, item.key == selectedKey, onSelect, fillWidth = true, onSidebar = true)
                    }
                    if (sidebarExtra != null) {
                        Spacer(Modifier.height(spacing.padding))
                        sidebarExtra()
                    }
                }
                if (footer != null) footer()
            }
            Box(Modifier.weight(1f).fillMaxHeight()) { content() }
        }

        NavStyle.TopTabs -> Column(modifier.fillMaxSize().background(colors.background)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surface)
                    .drawBehind {
                        drawLine(colors.outline, Offset(0f, size.height), Offset(size.width, size.height), borderPx)
                    }
                    .padding(horizontal = spacing.padding, vertical = spacing.paddingSmall),
                verticalArrangement = Arrangement.spacedBy(spacing.paddingSmall),
            ) {
                if (header != null) header()
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.paddingSmall)) {
                    items.forEach { item ->
                        NavEntry(item, item.key == selectedKey, onSelect, fillWidth = false, onSidebar = false)
                    }
                    if (footer != null) {
                        Spacer(Modifier.weight(1f))
                        footer()
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) { content() }
        }
    }
}

@Composable
private fun NavEntry(item: NavItem, selected: Boolean, onSelect: (String) -> Unit, fillWidth: Boolean, onSidebar: Boolean) {
    val colors = GestaltTheme.colors
    val spacing = GestaltTheme.spacing
    val shape = RoundedCornerShape(GestaltTheme.shapes.sm)
    val interaction = remember { MutableInteractionSource() }
    val foreground = when {
        onSidebar && selected -> colors.onSidebarActive
        onSidebar -> colors.onSidebar
        selected -> colors.primary
        else -> colors.textSecondary
    }
    val background = when {
        onSidebar && selected -> colors.sidebarActive
        !onSidebar && selected -> colors.primary.copy(alpha = 0.12f)
        else -> Color.Transparent
    }

    Row(
        modifier = (if (fillWidth) Modifier.fillMaxWidth() else Modifier)
            .clip(shape)
            .background(background, shape)
            .clickable(interactionSource = interaction, indication = null) { onSelect(item.key) }
            .padding(horizontal = spacing.padding, vertical = spacing.paddingSmall),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.paddingSmall),
    ) {
        GestaltIcon(item.icon, tint = foreground, size = 18.dp)
        GestaltText(item.label, role = TextRole.Label, color = foreground)
    }
}
