/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.app.gallery

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.gestalt.design.components.GestaltButton
import com.gestalt.design.components.GestaltCard
import com.gestalt.design.components.GestaltConnectionIcon
import com.gestalt.design.components.GestaltIcon
import com.gestalt.design.components.GestaltListRow
import com.gestalt.design.components.GestaltRadioGroup
import com.gestalt.design.components.GestaltStatusDot
import com.gestalt.design.components.GestaltStatusPill
import com.gestalt.design.components.GestaltText
import com.gestalt.design.components.GestaltTextField
import com.gestalt.design.components.StatusLevel
import com.gestalt.design.components.TextRole
import com.gestalt.design.icons.ChromeIcon
import com.gestalt.design.icons.ConnectionIcon
import com.gestalt.design.theme.ButtonStyle
import com.gestalt.design.theme.GestaltTheme

// ---- Shared helpers ------------------------------------------------------------------------------

@Composable
private fun PanelColumn(content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(GestaltTheme.spacing.paddingLarge)) { content() }
}

@Composable
private fun Subsection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(GestaltTheme.spacing.padding)) {
        GestaltText(title, role = TextRole.Heading, color = GestaltTheme.colors.textSecondary)
        content()
    }
}

/** A small surface badge holding a connection icon — shows the icon recoloring with the theme. */
@Composable
private fun ConnectionIconBadge(icon: ConnectionIcon, size: Int = 36) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(RoundedCornerShape(GestaltTheme.shapes.md))
            .background(GestaltTheme.colors.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        GestaltConnectionIcon(icon, tint = GestaltTheme.colors.primary, size = (size - 14).dp)
    }
}

// ---- Components panel ----------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ComponentsPanel(connectionIcon: ConnectionIcon) {
    val colors = GestaltTheme.colors
    val gap = GestaltTheme.spacing.padding
    var serverUrl by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("") }
    var trustMode by remember { mutableStateOf("PIN_KNOWN_SIGNERS") }

    PanelColumn {
        GestaltText("Components", role = TextRole.Display)

        Subsection("Typography") {
            GestaltText("Display — diagnostic instrument", role = TextRole.Display)
            GestaltText("Title — connection overview", role = TextRole.Title)
            GestaltText("Heading — verified publishers", role = TextRole.Heading)
            GestaltText("Body — every visual value comes from design tokens.", role = TextRole.Body)
            GestaltText("LABEL / STATUS", role = TextRole.Label, color = colors.textSecondary)
            GestaltText("mono — sha256:9f86d0…", role = TextRole.Mono, color = colors.textSecondary)
        }

        Subsection("Buttons") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(gap), verticalArrangement = Arrangement.spacedBy(gap)) {
                GestaltButton("Primary (theme default)", onClick = {})
                GestaltButton("Filled", onClick = {}, style = ButtonStyle.Filled)
                GestaltButton("Outlined", onClick = {}, style = ButtonStyle.Outlined)
                GestaltButton("Ghost", onClick = {}, style = ButtonStyle.Ghost)
                GestaltButton("Launch", onClick = {}, leadingIcon = ChromeIcon.Play)
                GestaltButton("Disabled", onClick = {}, enabled = false)
            }
        }

        Subsection("Text fields") {
            GestaltTextField(serverUrl, { serverUrl = it }, label = "Server URL", placeholder = "https://host:8443/", leadingIcon = ChromeIcon.Server)
            GestaltTextField(filter, { filter = it }, label = "Search", placeholder = "Filter connections…", leadingIcon = ChromeIcon.Search)
        }

        Subsection("Card") {
            GestaltCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(GestaltTheme.spacing.paddingSmall)) {
                    GestaltText("Card surface", role = TextRole.Heading)
                    GestaltText(
                        "Separated by a hairline border on the Sharp skin and by a shadow on the Soft skin.",
                        role = TextRole.Body,
                        color = colors.textSecondary,
                    )
                }
            }
        }

        Subsection("List rows & connection row") {
            GestaltListRow(
                title = "Specimen tracking",
                subtitle = "Flat on Sharp · Card on Soft",
                leading = { GestaltIcon(ChromeIcon.Folder, tint = colors.textSecondary) },
                trailing = { GestaltIcon(ChromeIcon.ChevronRight, tint = colors.textMuted) },
                onClick = {},
            )
            GestaltListRow(
                title = "Production OIE",
                subtitle = "https://oie.example.internal:8443",
                leading = { ConnectionIconBadge(connectionIcon) },
                trailing = { GestaltStatusPill("Verified", StatusLevel.Success) },
                onClick = {},
            )
            GestaltListRow(
                title = "Staging Mirth",
                subtitle = "https://stage.example.internal:8443",
                leading = { ConnectionIconBadge(ConnectionIcon.TestTubes) },
                trailing = { GestaltStatusPill("Untrusted", StatusLevel.Error) },
                onClick = {},
            )
        }

        Subsection("Selection (signature enforcement mode)") {
            GestaltRadioGroup(
                options = listOf("PIN_KNOWN_SIGNERS", "CA_ROOT"),
                selected = trustMode,
                onSelect = { trustMode = it },
                label = { it },
            )
        }

        Subsection("Status") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(gap), verticalArrangement = Arrangement.spacedBy(gap)) {
                GestaltStatusPill("Verified", StatusLevel.Success)
                GestaltStatusPill("Renewing", StatusLevel.Warning)
                GestaltStatusPill("Untrusted", StatusLevel.Error)
                GestaltStatusPill("Unknown", StatusLevel.Neutral)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
                GestaltStatusDot(StatusLevel.Success)
                GestaltStatusDot(StatusLevel.Warning)
                GestaltStatusDot(StatusLevel.Error)
                GestaltStatusDot(StatusLevel.Neutral)
            }
        }
    }
}

// ---- Chrome icon grid ----------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChromeIconsPanel() {
    val colors = GestaltTheme.colors
    PanelColumn {
        GestaltText("Chrome icons", role = TextRole.Display)
        GestaltText("Lucide (ISC) — functional UI glyphs, tinted from the color tokens.", role = TextRole.Body, color = colors.textSecondary)
        GestaltCard(Modifier.fillMaxWidth()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(GestaltTheme.spacing.paddingLarge),
                verticalArrangement = Arrangement.spacedBy(GestaltTheme.spacing.padding),
            ) {
                ChromeIcon.all.forEach { icon ->
                    IconCell(icon.label) { GestaltIcon(icon, tint = colors.textPrimary, size = 24.dp) }
                }
            }
        }
    }
}

@Composable
private fun IconCell(label: String, icon: @Composable () -> Unit) {
    Column(
        modifier = Modifier.width(96.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(GestaltTheme.spacing.paddingSmall),
    ) {
        icon()
        GestaltText(label, role = TextRole.Label, color = GestaltTheme.colors.textSecondary, maxLines = 1)
    }
}

// ---- Connection icon picker ----------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConnectionIconsPanel(selected: ConnectionIcon, onSelect: (ConnectionIcon) -> Unit) {
    val colors = GestaltTheme.colors
    PanelColumn {
        GestaltText("Connection icons", role = TextRole.Display)
        GestaltText("Health Icons (CC0) — pathology-lab set. Click to pick.", role = TextRole.Body, color = colors.textSecondary)

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GestaltTheme.spacing.padding)) {
            ConnectionIconBadge(selected, size = 44)
            GestaltText("Selected: ${selected.label}   ·   key \"${selected.key}\"", role = TextRole.Body)
        }

        GestaltCard(Modifier.fillMaxWidth()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(GestaltTheme.spacing.padding),
                verticalArrangement = Arrangement.spacedBy(GestaltTheme.spacing.padding),
            ) {
                ConnectionIcon.all.forEach { icon ->
                    val isSelected = icon == selected
                    val interaction = remember(icon) { MutableInteractionSource() }
                    Column(
                        modifier = Modifier
                            .width(104.dp)
                            .clickable(interactionSource = interaction, indication = null) { onSelect(icon) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(GestaltTheme.spacing.paddingSmall),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(GestaltTheme.shapes.md))
                                .background(if (isSelected) colors.primary.copy(alpha = 0.14f) else colors.surfaceVariant)
                                .then(
                                    if (isSelected) {
                                        Modifier.border(GestaltTheme.shapes.borderWidth, colors.accent, RoundedCornerShape(GestaltTheme.shapes.md))
                                    } else {
                                        Modifier
                                    },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            GestaltConnectionIcon(icon, tint = if (isSelected) colors.primary else colors.textSecondary, size = 30.dp)
                        }
                        GestaltText(
                            icon.label,
                            role = TextRole.Label,
                            color = if (isSelected) colors.textPrimary else colors.textSecondary,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}
