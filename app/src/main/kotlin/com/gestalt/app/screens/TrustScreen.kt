/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.app.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.gestalt.app.AppState
import com.gestalt.app.StatusBanner
import com.gestalt.app.trust.CertDetails
import com.gestalt.app.ui.HDivider
import com.gestalt.app.ui.InlineNote
import com.gestalt.app.ui.SectionCard
import com.gestalt.app.ui.SegmentedToggle
import com.gestalt.app.ui.FilePickers
import com.gestalt.design.components.GestaltButton
import com.gestalt.design.components.GestaltIcon
import com.gestalt.design.components.GestaltLabel
import com.gestalt.design.components.GestaltListRow
import com.gestalt.design.components.GestaltStatusPill
import com.gestalt.design.components.GestaltText
import com.gestalt.design.components.GestaltTextField
import com.gestalt.design.components.StatusLevel
import com.gestalt.design.components.TextRole
import com.gestalt.design.icons.ChromeIcon
import com.gestalt.design.theme.ButtonStyle
import com.gestalt.design.theme.GestaltTheme
import com.gestalt.launcher.gate.SignerPin
import com.gestalt.trust.TrustMode
import java.security.cert.X509Certificate
import java.time.Instant

/** Trust-management UI: server-cert pins, the managed trust-on-first-connect flow, and code-signing trust. */
@Composable
fun TrustScreen(appState: AppState) {
    val spacing = GestaltTheme.spacing
    // Establish a recomposition dependency on the custom-trust lists.
    @Suppress("UNUSED_VARIABLE") val nonce = appState.trustNonce

    Column(Modifier.fillMaxSize()) {
        StatusBanner(appState)
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(spacing.paddingLarge),
            verticalArrangement = Arrangement.spacedBy(spacing.padding),
        ) {
            GestaltText("Trust", role = TextRole.Display)

            ServerCertSection(appState)
            FirstConnectSection()
            CodeSigningSection(appState)
        }
    }
}

@Composable
private fun ServerCertSection(appState: AppState) {
    val spacing = GestaltTheme.spacing
    SectionCard(
        title = "Server certificates",
        subtitle = "Connections with a pinned server certificate. The launcher requires the server to present " +
            "exactly this certificate; on a mismatch the launch is stopped.",
    ) {
        val pinned = appState.pinnedConnections
        if (pinned.isEmpty()) {
            GestaltText("No connections have a pinned certificate yet.", role = TextRole.Body, color = GestaltTheme.colors.textSecondary)
        } else {
            pinned.forEach { conn ->
                val host = runCatching { conn.baseUri().host }.getOrNull() ?: conn.serverUrl
                val fp = conn.normalizedPin?.let { it.take(16).chunked(2).joinToString(":").uppercase() + "…" } ?: "—"
                GestaltListRow(
                    title = conn.name,
                    subtitle = "$host  ·  SHA-256 $fp",
                    leading = { GestaltIcon(ChromeIcon.ShieldCheck, tint = GestaltTheme.colors.success) },
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.paddingSmall)) {
                            GestaltButton("Details", style = ButtonStyle.Ghost, onClick = { appState.viewServerCert(conn.id) })
                            GestaltButton("Re-pin", style = ButtonStyle.Outlined, onClick = { appState.beginTrustPrompt(conn.id) })
                            GestaltButton("Unpin", style = ButtonStyle.Outlined, onClick = { appState.unpinServerCert(conn.id) })
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun FirstConnectSection() {
    SectionCard(
        title = "Trust on first connect",
        subtitle = "How an unknown server certificate is handled.",
    ) {
        InlineNote(
            StatusLevel.Neutral,
            "When a launch reaches a server whose certificate isn't trusted, the launcher stops and offers a " +
                "managed review: you see the certificate's subject, issuer, fingerprint and validity, and must " +
                "explicitly choose “Trust & pin”. There is no silent click-through, and every pin is written to the audit log.",
        )
    }
}

@Composable
private fun CodeSigningSection(appState: AppState) {
    val spacing = GestaltTheme.spacing
    SectionCard(
        title = "Code-signing trust (Layer 2)",
        subtitle = "How downloaded jars must be signed before they can launch.",
    ) {
        GestaltLabel("Trust posture")
        SegmentedToggle(
            options = listOf(TrustMode.PIN_KNOWN_SIGNERS, TrustMode.CA_ROOT),
            selected = appState.trustPosture,
            onSelect = appState::updateTrustPosture,
            label = ::postureLabel,
        )
        GestaltText(postureDescription(appState.trustPosture), role = TextRole.Body, color = GestaltTheme.colors.textSecondary)

        HDivider()
        GestaltText("Pinned signers", role = TextRole.Heading)
        appState.officialSignerPins.forEach { SignerRow(it.label, rdnText(it), removable = false, onRemove = {}) }
        appState.customSignerDnStrings().forEach { dn ->
            SignerRow("Custom signer", dn, removable = true, onRemove = { appState.removeCustomSignerDn(dn) })
        }
        var newDn by remember { mutableStateOf("") }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.padding)) {
            GestaltTextField(
                value = newDn,
                onValueChange = { newDn = it },
                modifier = Modifier.weight(1f),
                placeholder = "CN=Acme Code Signing, O=Acme Inc",
            )
            GestaltButton("Add signer", style = ButtonStyle.Outlined, leadingIcon = ChromeIcon.Plus, onClick = {
                appState.addCustomSignerDn(newDn); newDn = ""
            })
        }

        HDivider()
        GestaltText("Trusted CA anchors", role = TextRole.Heading)
        appState.bundledAnchors().forEach { AnchorRow(it, path = null, onRemove = {}) }
        appState.customAnchorPaths().forEach { path ->
            val cert = runCatching {
                java.io.File(path).inputStream().use { CertDetails.readCert(it) }
            }.getOrNull()
            if (cert != null) AnchorRow(cert, path = path, onRemove = { appState.removeCustomAnchor(path) })
            else CustomAnchorErrorRow(path) { appState.removeCustomAnchor(path) }
        }
        GestaltButton("Add CA anchor (PEM)…", style = ButtonStyle.Outlined, leadingIcon = ChromeIcon.Folder, onClick = {
            FilePickers.openFile(title = "Add CA anchor (PEM)")?.let { appState.addCustomAnchor(it.absolutePath) }
        })
    }
}

@Composable
private fun SignerRow(title: String, subtitle: String, removable: Boolean, onRemove: () -> Unit) {
    GestaltListRow(
        title = title,
        subtitle = subtitle,
        leading = { GestaltIcon(ChromeIcon.Shield, tint = GestaltTheme.colors.primary) },
        trailing = {
            if (removable) GestaltButton("Remove", style = ButtonStyle.Ghost, onClick = onRemove)
            else GestaltStatusPill("Built-in", StatusLevel.Neutral)
        },
    )
}

@Composable
private fun AnchorRow(cert: X509Certificate, path: String?, onRemove: () -> Unit) {
    val details = remember(cert) { CertDetails.from(cert) }
    val now = remember { Instant.now() }
    val shortFp = details.fingerprintPlain.take(16).chunked(2).joinToString(":").uppercase() + "…"
    GestaltListRow(
        title = shortName(details.subject),
        subtitle = "SHA-256 $shortFp  ·  until ${com.gestalt.app.ui.formatDate(details.notAfter)}",
        leading = { GestaltIcon(ChromeIcon.ShieldCheck, tint = GestaltTheme.colors.primary) },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GestaltTheme.spacing.paddingSmall)) {
                when {
                    details.isExpired(now) -> GestaltStatusPill("Expired", StatusLevel.Error)
                    details.isNearExpiry(now) -> GestaltStatusPill("Expiring", StatusLevel.Warning)
                    else -> {}
                }
                if (path != null) GestaltButton("Remove", style = ButtonStyle.Ghost, onClick = onRemove)
                else GestaltStatusPill("Built-in", StatusLevel.Neutral)
            }
        },
    )
}

@Composable
private fun CustomAnchorErrorRow(path: String, onRemove: () -> Unit) {
    GestaltListRow(
        title = "Unreadable anchor",
        subtitle = path,
        leading = { GestaltIcon(ChromeIcon.AlertTriangle, tint = GestaltTheme.colors.error) },
        trailing = { GestaltButton("Remove", style = ButtonStyle.Ghost, onClick = onRemove) },
    )
}

private fun postureLabel(mode: TrustMode): String = when (mode) {
    TrustMode.PIN_KNOWN_SIGNERS -> "Pinned signers"
    TrustMode.CA_ROOT -> "Any trusted CA"
}

private fun postureDescription(mode: TrustMode): String = when (mode) {
    TrustMode.PIN_KNOWN_SIGNERS ->
        "Default and recommended. A jar is accepted only if its signer matches a pinned publisher AND its " +
            "certificate chains to a trusted root."
    TrustMode.CA_ROOT ->
        "Looser: any signer that chains to a trusted root and carries the code-signing usage is accepted. " +
            "Use only when the trust anchor is itself private (e.g. your own root)."
}

private fun rdnText(pin: SignerPin): String = pin.requiredRdns.entries.joinToString(", ") { "${it.key}=${it.value}" }

/** Best-effort short name: the CN value if present, else the whole DN. */
private fun shortName(dn: String): String {
    val cn = Regex("CN=([^,]+)", RegexOption.IGNORE_CASE).find(dn)?.groupValues?.getOrNull(1)?.trim()
    return cn?.takeIf { it.isNotBlank() } ?: dn
}
