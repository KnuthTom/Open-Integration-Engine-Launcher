/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.gestalt.app.screens.AppDialogs
import com.gestalt.app.screens.ConnectionFormScreen
import com.gestalt.app.screens.ConnectionListScreen
import com.gestalt.app.screens.ConnectionTools
import com.gestalt.app.screens.SettingsScreen
import com.gestalt.app.screens.TrustScreen
import com.gestalt.app.ui.GestaltIconButton
import com.gestalt.app.ui.KeyValueRow
import com.gestalt.app.ui.ModalOverlay
import com.gestalt.design.branding.Branding
import com.gestalt.design.components.GestaltButton
import com.gestalt.design.components.GestaltScaffold
import com.gestalt.design.components.GestaltText
import com.gestalt.design.components.NavItem
import com.gestalt.design.components.TextRole
import com.gestalt.design.icons.ChromeIcon
import com.gestalt.design.theme.GestaltTheme

/**
 * The product window: a deep-indigo sidebar (logo + nav + theme switcher) beside light-grey content.
 * The whole UI is built from :design-system components and re-skins live via the theme switcher; every
 * surface reads the active tokens, so flipping the preset/mode/density restyles the whole app.
 */
@Composable
fun ProductApp(appState: AppState, branding: Branding = Branding.Default) {
    GestaltTheme(skin = appState.skin, mode = appState.mode, branding = branding, density = appState.density) {
        Box(Modifier.fillMaxSize()) {
            GestaltScaffold(
                items = listOf(
                    NavItem("connections", "Connections", ChromeIcon.Server),
                    NavItem("trust", "Trust", ChromeIcon.Shield),
                    NavItem("settings", "Settings", ChromeIcon.Settings),
                ),
                selectedKey = when (appState.route) {
                    is Route.Trust -> "trust"
                    is Route.Settings -> "settings"
                    else -> "connections"
                },
                onSelect = { key ->
                    when (key) {
                        "trust" -> appState.route = Route.Trust
                        "settings" -> appState.route = Route.Settings // Logs live under Settings → Logging & data
                        else -> appState.route = Route.List
                    }
                },
                modifier = Modifier.fillMaxSize(),
                header = {
                    GestaltText(branding.appName, role = TextRole.Heading, color = GestaltTheme.colors.onSidebarActive)
                },
                // The Connections-screen tools (import/export, sort, density, on-import, close-after-launch)
                // live in the rail with the nav; the theme switcher moved to Settings → Appearance.
                sidebarExtra = if (appState.route is Route.List) {
                    { ConnectionTools(appState) }
                } else {
                    null
                },
            ) {
                Box(Modifier.fillMaxSize()) {
                    when (val route = appState.route) {
                        is Route.List -> ConnectionListScreen(appState)
                        is Route.Edit -> ConnectionFormScreen(appState, route.connectionId)
                        is Route.Trust -> TrustScreen(appState)
                        is Route.Settings -> SettingsScreen(appState, branding)
                    }
                }
            }
            // Modals (diagnosis / trust-on-first-connect / cert view / test result) render over the shell.
            AppDialogs(appState)
            if (appState.showShortcuts) ShortcutsOverlay(appState)
        }
    }
}

/** The discoverable keyboard-shortcuts overlay ("?"). */
@Composable
private fun ShortcutsOverlay(appState: AppState) {
    ModalOverlay(
        title = "Keyboard shortcuts",
        onDismiss = { appState.toggleShortcuts() },
        actions = { GestaltButton("Close", style = com.gestalt.design.theme.ButtonStyle.Ghost, onClick = { appState.toggleShortcuts() }) },
    ) {
        KeyValueRow("Ctrl / Cmd + K", "Focus search", mono = true)
        KeyValueRow("Ctrl / Cmd + F", "Focus search", mono = true)
        KeyValueRow("Ctrl / Cmd + N", "New connection", mono = true)
        KeyValueRow("Ctrl / Cmd + A", "Select all (in the list)", mono = true)
        KeyValueRow("↑ / ↓", "Move the row cursor", mono = true)
        KeyValueRow("Enter", "Launch the cursor row", mono = true)
        KeyValueRow("Space", "Select / deselect the cursor row", mono = true)
        KeyValueRow("Double-click", "Launch a connection", mono = true)
        KeyValueRow("Esc", "Close dialog / clear selection / leave the form", mono = true)
        KeyValueRow("?", "Show or hide this overlay", mono = true)
        GestaltText(
            "Tip: Tab moves between controls; the ↑/↓ cursor and Enter/Space act on the list once a row is highlighted.",
            role = TextRole.Label,
            color = GestaltTheme.colors.textMuted,
        )
    }
}

/** A dismissible status banner used by the list/form screens. */
@Composable
fun StatusBanner(appState: AppState) {
    val message = appState.banner ?: return
    val spacing = GestaltTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(GestaltTheme.colors.surfaceVariant)
            .padding(horizontal = spacing.padding, vertical = spacing.paddingSmall),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.paddingSmall),
    ) {
        GestaltText(message, role = TextRole.Body, color = GestaltTheme.colors.textSecondary, modifier = Modifier.weight(1f))
        GestaltIconButton(ChromeIcon.XCircle, onClick = { appState.banner = null })
    }
}
