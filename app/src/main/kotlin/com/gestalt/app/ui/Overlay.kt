/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.gestalt.app.trust.CertDetails
import com.gestalt.design.components.GestaltCard
import com.gestalt.design.components.GestaltIcon
import com.gestalt.design.components.GestaltText
import com.gestalt.design.components.StatusLevel
import com.gestalt.design.components.TextRole
import com.gestalt.design.icons.ChromeIcon
import com.gestalt.design.theme.GestaltTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * A modal dialog rendered as an in-window overlay (scrim + centered [GestaltCard]). Built from
 * design-system components and tokens so it re-skins with the theme; clicking the scrim dismisses.
 */
@Composable
fun ModalOverlay(
    title: String,
    onDismiss: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val spacing = GestaltTheme.spacing
    val scrim = remember { MutableInteractionSource() }
    val card = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(GestaltTheme.colors.textPrimary.copy(alpha = 0.32f))
            .clickable(interactionSource = scrim, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        GestaltCard(
            modifier = Modifier
                .widthIn(max = 600.dp)
                .fillMaxWidth(0.62f)
                .padding(spacing.padding)
                // Absorb clicks so they don't fall through to the scrim.
                .clickable(interactionSource = card, indication = null, onClick = {}),
        ) {
            Column(Modifier.padding(spacing.paddingLarge), verticalArrangement = Arrangement.spacedBy(spacing.padding)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GestaltText(title, role = TextRole.Title, modifier = Modifier.weight(1f))
                    GestaltIconButton(ChromeIcon.XCircle, onClick = onDismiss)
                }
                HDivider()
                Column(
                    modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(spacing.padding),
                ) { content() }
                HDivider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing.padding, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) { actions() }
            }
        }
    }
}

/** A titled card section used by the Trust and Settings screens. */
@Composable
fun SectionCard(title: String, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val spacing = GestaltTheme.spacing
    GestaltCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(spacing.paddingLarge), verticalArrangement = Arrangement.spacedBy(spacing.padding)) {
            GestaltText(title, role = TextRole.Heading)
            subtitle?.let { GestaltText(it, role = TextRole.Body, color = GestaltTheme.colors.textSecondary) }
            content()
        }
    }
}

/** A label/value row; [mono] renders the value in the monospace role (fingerprints, paths). */
@Composable
fun KeyValueRow(label: String, value: String, mono: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GestaltTheme.spacing.padding)) {
        GestaltText(label, role = TextRole.Label, color = GestaltTheme.colors.textSecondary, modifier = Modifier.widthIn(min = 120.dp))
        GestaltText(value, role = if (mono) TextRole.Mono else TextRole.Body, modifier = Modifier.weight(1f))
    }
}

/** A thin themed horizontal rule. */
@Composable
fun HDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = GestaltTheme.shapes.borderWidth)
            .background(GestaltTheme.colors.outline),
    )
}

/** An inline, status-tinted note (used for expiry warnings, mismatch alerts, guardrail callouts). */
@Composable
fun InlineNote(level: StatusLevel, text: String) {
    val color = when (level) {
        StatusLevel.Success -> GestaltTheme.colors.success
        StatusLevel.Warning -> GestaltTheme.colors.warning
        StatusLevel.Error -> GestaltTheme.colors.error
        StatusLevel.Neutral -> GestaltTheme.colors.textMuted
    }
    val icon = when (level) {
        StatusLevel.Success -> ChromeIcon.CheckCircle
        StatusLevel.Warning -> ChromeIcon.AlertTriangle
        StatusLevel.Error -> ChromeIcon.XCircle
        StatusLevel.Neutral -> ChromeIcon.Shield
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(GestaltTheme.shapes.sm))
            .background(color.copy(alpha = 0.14f))
            .padding(GestaltTheme.spacing.padding),
        horizontalArrangement = Arrangement.spacedBy(GestaltTheme.spacing.paddingSmall),
        verticalAlignment = Alignment.Top,
    ) {
        GestaltIcon(icon, tint = color, size = 18.dp)
        GestaltText(text, role = TextRole.Body, modifier = Modifier.weight(1f))
    }
}

/** Read-only certificate details with expiry/validity callouts. */
@Composable
fun CertDetailsView(details: CertDetails) {
    val now = remember { Instant.now() }
    Column(verticalArrangement = Arrangement.spacedBy(GestaltTheme.spacing.paddingSmall)) {
        KeyValueRow("Subject", details.subject)
        KeyValueRow("Issuer", details.issuer)
        KeyValueRow("SHA-256", details.fingerprintDisplay, mono = true)
        KeyValueRow("Valid from", formatDate(details.notBefore))
        KeyValueRow("Valid until", formatDate(details.notAfter))
        when {
            details.isExpired(now) -> InlineNote(StatusLevel.Error, "This certificate has expired.")
            details.isNotYetValid(now) -> InlineNote(StatusLevel.Warning, "This certificate is not valid yet.")
            details.isNearExpiry(now) -> InlineNote(StatusLevel.Warning, "Expires in ${details.daysUntilExpiry(now)} day(s).")
        }
    }
}

private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault())

fun formatDate(instant: Instant): String = DATE_FMT.format(instant)
