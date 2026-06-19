/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gestalt.app.AppState
import com.gestalt.design.components.GestaltConnectionIcon
import com.gestalt.design.components.GestaltCustomIcon
import com.gestalt.design.components.GestaltPackIcon
import com.gestalt.design.icons.ConnectionIcon
import com.gestalt.design.icons.PackIcons
import com.gestalt.design.theme.GestaltTheme
import com.gestalt.launcher.icon.CustomIconStore

/**
 * Renders a connection's icon from its stored key, resolving across all three sources:
 *  - `custom:<id>` → a user-uploaded icon from [AppState] (recolored only when it is a `currentColor` SVG);
 *  - `pack:<key>`  → a build-time licensed pack icon ([PackIcons]);
 *  - any other key → a built-in [ConnectionIcon].
 *
 * Any key that no longer resolves (a deleted custom icon, an absent pack, an unknown key) falls back to
 * the default built-in glyph. Reads [AppState.customIconNonce] so add/rename/delete recompose live.
 */
@Composable
fun ConnectionGlyph(
    appState: AppState,
    iconKey: String,
    modifier: Modifier = Modifier,
    tint: Color = GestaltTheme.colors.primary,
    size: Dp = 24.dp,
) {
    val nonce = appState.customIconNonce // observed so custom-icon changes recompose
    when {
        iconKey.startsWith(CustomIconStore.KEY_PREFIX) -> {
            val id = iconKey.removePrefix(CustomIconStore.KEY_PREFIX)
            val meta = remember(id, nonce) { appState.customIcon(id) }
            val bytes = remember(id, nonce) { appState.customIconBytes(id) }
            if (meta != null && bytes != null) {
                GestaltCustomIcon(bytes, meta.ext, meta.label, meta.tintable, modifier, tint, size)
            } else {
                GestaltConnectionIcon(ConnectionIcon.default, modifier, tint, size)
            }
        }
        iconKey.startsWith(PackIcons.KEY_PREFIX) -> {
            val entry = remember(iconKey) { PackIcons.byKey(iconKey) }
            if (entry != null) {
                GestaltPackIcon(entry, modifier, tint, size)
            } else {
                GestaltConnectionIcon(ConnectionIcon.default, modifier, tint, size)
            }
        }
        else -> GestaltConnectionIcon(ConnectionIcon.byKey(iconKey) ?: ConnectionIcon.default, modifier, tint, size)
    }
}
