/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.gestalt.app.AppState
import com.gestalt.app.Route
import com.gestalt.app.StatusBanner
import com.gestalt.app.UpdateState
import com.gestalt.app.ui.FilePickers
import com.gestalt.app.ui.HDivider
import com.gestalt.app.ui.InlineNote
import com.gestalt.app.ui.KeyValueRow
import com.gestalt.app.ui.SectionCard
import com.gestalt.app.ui.SegmentedToggle
import com.gestalt.design.branding.Branding
import com.gestalt.design.components.GestaltButton
import com.gestalt.design.components.GestaltLabel
import com.gestalt.design.components.GestaltLogo
import com.gestalt.design.components.GestaltRadioGroup
import com.gestalt.design.components.GestaltText
import com.gestalt.design.components.StatusLevel
import com.gestalt.design.components.TextRole
import com.gestalt.design.icons.ChromeIcon
import com.gestalt.design.theme.ButtonStyle
import com.gestalt.design.theme.GestaltTheme
import com.gestalt.design.theme.Mode
import com.gestalt.design.theme.Skin
import com.gestalt.launcher.AppPaths
import com.gestalt.launcher.model.JavaHomeMode

/** Global settings + the developer Build & Signing capture. Persists per-user; never stores secrets. */
@Composable
fun SettingsScreen(appState: AppState, branding: Branding) {
    val spacing = GestaltTheme.spacing
    Column(Modifier.fillMaxSize()) {
        StatusBanner(appState)
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(spacing.paddingLarge),
            verticalArrangement = Arrangement.spacedBy(spacing.padding),
        ) {
            GestaltText("Settings", role = TextRole.Display)

            AppearanceSection(appState)
            LaunchSection(appState)
            LaunchDefaultsSection(appState)
            TrustDefaultSection(appState)
            LoggingDataSection(appState)
            UpdatesSection(appState)
            BuildSigningSection(appState, branding)
            AboutSection()
            CreditsSection(appState)
        }
    }
}

@Composable
private fun AboutSection() {
    SectionCard("About", "Version and build information.") {
        KeyValueRow("Application", com.gestalt.app.BuildInfo.appName)
        KeyValueRow("Version", com.gestalt.app.BuildInfo.displayVersion, mono = true)
        KeyValueRow("Vendor", com.gestalt.app.BuildInfo.vendor)
        KeyValueRow("Built", com.gestalt.app.BuildInfo.buildTime, mono = true)
        KeyValueRow("Bundled Java runtimes", com.gestalt.app.BuildInfo.bundledRuntimes)
        KeyValueRow(
            "Mode",
            if (AppPaths.isPortable) "Portable — state in ./data beside the app" else "Installed — per-user data dir",
        )
        KeyValueRow("License", "MPL-2.0 · community-maintained · unsigned · no warranty")
        KeyValueRow("Origin", "Originally built by Gestalt Diagnostics")
    }
}

@Composable
private fun CreditsSection(appState: AppState) {
    SectionCard("Credits", "Bundled icon sets and their licenses.") {
        KeyValueRow("Lucide", "Functional UI icons — ISC license")
        KeyValueRow("Health Icons", "Connection icons — CC0 (public domain)")
        if (com.gestalt.app.BuildInfo.hasIconPack) {
            HDivider()
            val name = com.gestalt.app.BuildInfo.iconPackName
            val license = com.gestalt.app.BuildInfo.iconPackLicense
            val attribution = com.gestalt.app.BuildInfo.iconPackAttribution
            val homepage = com.gestalt.app.BuildInfo.iconPackHomepage
            KeyValueRow("Icon pack", if (license.isNotBlank()) "$name — $license" else name)
            if (attribution.isNotBlank()) {
                GestaltText(attribution, role = TextRole.Body, color = GestaltTheme.colors.textSecondary)
            }
            if (homepage.isNotBlank()) {
                GestaltButton("Visit $homepage", style = ButtonStyle.Ghost, leadingIcon = ChromeIcon.Connect, onClick = { appState.openUrl(homepage) })
            }
        }
        GestaltText(
            "Custom icons you upload are stored only in your per-user data folder and are never bundled or redistributed.",
            role = TextRole.Label,
            color = GestaltTheme.colors.textMuted,
        )
    }
}

@Composable
private fun LaunchSection(appState: AppState) {
    SectionCard(
        "Launch",
        "Behavior when a connection launches. A connection can override this in its form (Runtime → Close after launch).",
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GestaltTheme.spacing.padding)) {
            GestaltLabel("Close launcher after a connection launches")
            SegmentedToggle(
                listOf(false, true),
                appState.closeAfterLaunch,
                appState::updateCloseAfterLaunch,
                { if (it) "On" else "Off" },
            )
        }
        GestaltText(
            "When on, the window closes once the Administrator has launched successfully — never on a launch failure.",
            role = TextRole.Label,
            color = GestaltTheme.colors.textMuted,
        )
    }
}

@Composable
private fun AppearanceSection(appState: AppState) {
    SectionCard("Appearance", "Theme — skin and light/dark. (Launch options live in the Launch section above and the Connections rail.)") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GestaltTheme.spacing.padding)) {
            GestaltLabel("Skin")
            SegmentedToggle(listOf(Skin.Dashboard, Skin.Sharp, Skin.Soft), appState.skin, appState::updateSkin, { it.name })
            GestaltLabel("Mode")
            SegmentedToggle(listOf(Mode.Light, Mode.Dark), appState.mode, appState::updateMode, { it.name })
        }
    }
}

@Composable
private fun LaunchDefaultsSection(appState: AppState) {
    val spacing = GestaltTheme.spacing
    val prefs = appState.prefs
    var javaMode by remember { mutableStateOf(prefs.defaultJavaHomeMode) }
    var version by remember { mutableStateOf(prefs.defaultBundledVersion) }
    var customPath by remember { mutableStateOf(prefs.defaultCustomJavaHome) }
    var heap by remember { mutableStateOf(prefs.defaultHeap) }

    SectionCard("Launch defaults", "Applied to new connections. Existing connections keep their own settings.") {
        GestaltLabel("Default Java runtime")
        GestaltRadioGroup(
            options = JavaHomeMode.entries.toList(),
            selected = javaMode,
            onSelect = { javaMode = it; prefs.defaultJavaHomeMode = it },
            label = {
                when (it) {
                    JavaHomeMode.Default -> "Default (launcher's JVM)"
                    JavaHomeMode.Bundled -> "Bundled runtime"
                    JavaHomeMode.Custom -> "Custom path"
                }
            },
        )
        when (javaMode) {
            JavaHomeMode.Bundled -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.paddingSmall)) {
                GestaltLabel("Version")
                SegmentedToggle(listOf("8", "17"), version, { version = it; prefs.defaultBundledVersion = it }, { it })
            }
            JavaHomeMode.Custom -> com.gestalt.design.components.GestaltTextField(
                value = customPath,
                onValueChange = { customPath = it; prefs.defaultCustomJavaHome = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = "C:\\Java\\jdk-17",
                leadingIcon = ChromeIcon.Folder,
            )
            JavaHomeMode.Default -> {}
        }
        GestaltLabel("Default max heap")
        com.gestalt.design.components.GestaltTextField(
            value = heap,
            onValueChange = { heap = it; prefs.defaultHeap = it },
            modifier = Modifier.width(200.dp),
            placeholder = "512m",
        )
    }
}

@Composable
private fun TrustDefaultSection(appState: AppState) {
    SectionCard("Trust", "Default code-signing posture for verifying launches.") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GestaltTheme.spacing.padding)) {
            GestaltLabel("Posture")
            SegmentedToggle(
                options = com.gestalt.trust.TrustMode.entries.toList(),
                selected = appState.trustPosture,
                onSelect = appState::updateTrustPosture,
                label = { if (it == com.gestalt.trust.TrustMode.PIN_KNOWN_SIGNERS) "Pinned signers" else "Any trusted CA" },
            )
        }
        GestaltButton("Manage trust…", style = ButtonStyle.Outlined, leadingIcon = ChromeIcon.Shield, onClick = { appState.route = Route.Trust })
    }
}

@Composable
private fun LoggingDataSection(appState: AppState) {
    val spacing = GestaltTheme.spacing
    val prefs = appState.prefs
    var logLevel by remember { mutableStateOf(prefs.logLevel) }

    SectionCard("Logging & data", "Diagnostics verbosity and on-disk locations.") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.padding)) {
            GestaltLabel("Log level")
            SegmentedToggle(listOf("ERROR", "WARN", "INFO", "DEBUG"), logLevel, { logLevel = it; prefs.logLevel = it }, { it })
        }
        GestaltText("Takes effect on the next launch of the app.", role = TextRole.Label, color = GestaltTheme.colors.textMuted)

        HDivider()
        PathRow("Data", AppPaths.dataDir.toString()) { appState.openDataFolder() }
        PathRow("Logs", AppPaths.logDir.toString()) { appState.openLogsFolder() }
        PathRow("Custom icons", AppPaths.iconsDir.toString()) { appState.openIconsFolder() }
        Column(verticalArrangement = Arrangement.spacedBy(spacing.paddingSmall)) {
            KeyValueRow("Cache", AppPaths.cacheDir.toString(), mono = true)
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.padding)) {
                GestaltButton("Open folder", style = ButtonStyle.Ghost, leadingIcon = ChromeIcon.Folder, onClick = { appState.openCacheFolder() })
                GestaltButton("Clear cache", style = ButtonStyle.Outlined, leadingIcon = ChromeIcon.Trash, onClick = { appState.clearCache() })
            }
        }
    }
}

@Composable
private fun PathRow(label: String, path: String, onOpen: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(GestaltTheme.spacing.paddingSmall)) {
        KeyValueRow(label, path, mono = true)
        GestaltButton("Open folder", style = ButtonStyle.Ghost, leadingIcon = ChromeIcon.Folder, onClick = onOpen)
    }
}

@Composable
private fun UpdatesSection(appState: AppState) {
    val spacing = GestaltTheme.spacing
    val prefs = appState.prefs
    var url by remember { mutableStateOf(prefs.updateChannelUrl) }
    SectionCard("Updates", "Signed, fail-closed self-update: the app verifies a signed manifest before trusting anything.") {
        GestaltLabel("Update channel URL")
        com.gestalt.design.components.GestaltTextField(
            value = url,
            onValueChange = { url = it; prefs.updateChannelUrl = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = "https://updates.example.com/gestalt/stable",
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.padding)) {
            GestaltButton("Check for updates", style = ButtonStyle.Outlined, leadingIcon = ChromeIcon.Refresh, onClick = { appState.checkForUpdates() })
            UpdateStatusInline(appState.updateState)
        }
        UpdateDetail(appState)
    }
}

@Composable
private fun UpdateStatusInline(state: UpdateState) {
    val text = when (state) {
        UpdateState.Idle -> ""
        UpdateState.Checking -> "Checking…"
        UpdateState.UpToDate -> "You're on the latest version."
        UpdateState.Applying -> "Applying update…"
        is UpdateState.Unavailable -> state.reason
        is UpdateState.Failed -> "" // shown as a note below
        is UpdateState.Available -> "Update available: ${state.outcome.manifest.version}"
    }
    if (text.isNotEmpty()) GestaltText(text, role = TextRole.Label, color = GestaltTheme.colors.textMuted)
}

@Composable
private fun UpdateDetail(appState: AppState) {
    val spacing = GestaltTheme.spacing
    when (val state = appState.updateState) {
        is UpdateState.Failed ->
            InlineNote(StatusLevel.Error, "Update check failed: ${state.reason}. Nothing was changed.")
        is UpdateState.Available -> {
            val manifest = state.outcome.manifest
            InlineNote(
                StatusLevel.Neutral,
                "Version ${manifest.version} is available." + if (manifest.notes.isNotBlank()) "\n\n${manifest.notes}" else "",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.padding)) {
                val applyLabel = if (state.outcome.portable) "Update now" else "Download & install"
                GestaltButton(applyLabel, leadingIcon = ChromeIcon.Connect, onClick = { appState.applyAvailableUpdate() })
                if (state.outcome.portable) {
                    GestaltButton("Open download", style = ButtonStyle.Outlined, onClick = { appState.openUpdateDownload() })
                }
            }
        }
        else -> {}
    }
}

@Composable
private fun BuildSigningSection(appState: AppState, branding: Branding) {
    val spacing = GestaltTheme.spacing
    val prefs = appState.prefs
    var keystore by remember { mutableStateOf(prefs.signingKeystorePath) }
    var alias by remember { mutableStateOf(prefs.signingKeyAlias) }
    var tsa by remember { mutableStateOf(prefs.signingTsaUrl) }

    SectionCard("Build & signing (developer)", "Capture code-signing configuration for producing new builds.") {
        InlineNote(
            StatusLevel.Neutral,
            "Only the keystore path, key alias and timestamp-authority URL are saved. The PKCS12 private key " +
                "and its passphrase are never stored — provide the passphrase from the OS keychain or at build " +
                "time. This screen captures configuration only; the signed / white-label build is Phase 4.",
        )

        GestaltLabel("Keystore (PKCS12)")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.padding)) {
            com.gestalt.design.components.GestaltTextField(
                value = keystore,
                onValueChange = { keystore = it; prefs.signingKeystorePath = it },
                modifier = Modifier.weight(1f),
                placeholder = "C:\\signing\\release.p12",
                leadingIcon = ChromeIcon.Folder,
            )
            GestaltButton("Browse…", style = ButtonStyle.Outlined, onClick = {
                FilePickers.openFile(title = "Select signing keystore")?.let { keystore = it.absolutePath; prefs.signingKeystorePath = it.absolutePath }
            })
        }
        GestaltLabel("Key alias")
        com.gestalt.design.components.GestaltTextField(
            value = alias,
            onValueChange = { alias = it; prefs.signingKeyAlias = it },
            modifier = Modifier.width(280.dp),
            placeholder = "gestalt-signing",
        )
        GestaltLabel("Timestamp authority (TSA) URL")
        com.gestalt.design.components.GestaltTextField(
            value = tsa,
            onValueChange = { tsa = it; prefs.signingTsaUrl = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = "http://timestamp.digicert.com",
        )

        HDivider()
        GestaltText("Active branding", role = TextRole.Heading)
        GestaltLogo()
        KeyValueRow("Name", branding.appName)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.paddingSmall)) {
            GestaltLabel("Palette")
            Swatch(branding.palette.primary)
            Swatch(branding.palette.accent1)
            Swatch(branding.palette.accent2)
            Swatch(branding.palette.neutral)
        }
    }
}

@Composable
private fun Swatch(color: Color) {
    androidx.compose.foundation.layout.Box(
        Modifier.size(24.dp).clip(RoundedCornerShape(GestaltTheme.shapes.sm)).background(color),
    )
}
