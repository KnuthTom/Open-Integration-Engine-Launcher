/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.gestalt.app.AppState
import com.gestalt.app.Route
import com.gestalt.app.ui.ConnectionGlyph
import com.gestalt.app.ui.FilePickers
import com.gestalt.app.ui.GestaltIconButton
import com.gestalt.app.ui.InlineNote
import com.gestalt.app.ui.LabeledToggle
import com.gestalt.app.ui.SegmentedToggle
import com.gestalt.design.components.GestaltButton
import com.gestalt.design.components.GestaltIcon
import com.gestalt.design.components.GestaltLabel
import com.gestalt.design.components.GestaltRadioGroup
import com.gestalt.design.components.GestaltText
import com.gestalt.design.components.GestaltTextField
import com.gestalt.design.components.StatusLevel
import com.gestalt.design.components.TextRole
import com.gestalt.design.icons.ChromeIcon
import com.gestalt.design.icons.ConnectionIcon
import com.gestalt.design.icons.PackIcons
import com.gestalt.design.theme.ButtonStyle
import com.gestalt.design.theme.GestaltTheme
import com.gestalt.launcher.diag.CheckStatus
import com.gestalt.launcher.diag.ConnectionTestReport
import com.gestalt.launcher.diag.TlsFailureClass
import com.gestalt.launcher.icon.CustomIcon
import com.gestalt.launcher.model.CloseAfterLaunch
import com.gestalt.launcher.model.ConnectionMode
import com.gestalt.launcher.model.JavaHomeMode
import java.util.UUID

/**
 * Add/edit a connection — redesigned around a single smart Address field (§6.3.1). Only Name + Address
 * are required; the mode/scheme/security are auto-detected and shown inline; advanced runtime and TLS
 * settings live behind collapsed sections that surface a summary chip when they hold a non-default value.
 */
@Composable
fun ConnectionFormScreen(appState: AppState, connectionId: String?) {
    val spacing = GestaltTheme.spacing
    val existing = remember(connectionId, appState.formNonce) { connectionId?.let { appState.get(it) } }
    val form = remember(connectionId, appState.formNonce) {
        ConnectionFormState(existing, if (existing == null) appState.launchDefaults() else null)
    }
    var error by remember(connectionId, appState.formNonce) { mutableStateOf<String?>(null) }
    // Drop any stale inline test result when (re)entering the form.
    LaunchedEffect(connectionId, appState.formNonce) { appState.clearFormTest() }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(spacing.paddingLarge),
        verticalArrangement = Arrangement.spacedBy(spacing.padding),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.paddingSmall)) {
            GestaltIconButton(ChromeIcon.ChevronRight, onClick = { appState.route = Route.List })
            GestaltText(if (form.isNew) "New connection" else "Edit connection", role = TextRole.Display)
        }

        // --- The two required fields ----------------------------------------------------------------
        Field("Name") {
            GestaltTextField(form.name, { form.name = it }, modifier = Modifier.fillMaxWidth(), placeholder = "Production OIE")
        }
        Field("Address") {
            GestaltTextField(
                form.address,
                { form.onAddressInput(it); appState.clearFormTest() },
                modifier = Modifier.fillMaxWidth(),
                placeholder = "oie.lab:8443  ·  https://host:8443  ·  paste a URL or a legacy entry",
                leadingIcon = ChromeIcon.Server,
            )
            AddressHint(form)
        }

        // --- In-form test (inline PASS/FAIL, before saving) -----------------------------------------
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.padding)) {
            GestaltButton("Test connection", style = ButtonStyle.Outlined, leadingIcon = ChromeIcon.Connect, onClick = {
                error = form.validate()
                if (error == null) appState.testConnectionForForm(form.toConnection(form.id ?: "draft-test", existing?.sortOrder ?: 0))
            })
            if (appState.formTesting) GestaltText("Testing…", role = TextRole.Label, color = GestaltTheme.colors.primary)
        }
        appState.formTestReport?.let { InlineTestResult(it) }

        if (error != null) {
            InlineNote(StatusLevel.Error, error!!)
        }

        Field("Group (optional)") {
            GestaltTextField(form.group, { form.group = it }, modifier = Modifier.fillMaxWidth(), placeholder = "Production")
        }

        // --- Progressive disclosure: advanced settings collapsed by default -------------------------
        CollapsibleSection("Runtime (JVM)", form.runtimeSummary) { RuntimeSection(form) }
        CollapsibleSection("Security (TLS)", form.securitySummary) { SecuritySection(form) }
        CollapsibleSection("Icon & credentials", iconCredsSummary(form)) { IconAndCredentials(appState, form) }

        // --- Footer ---------------------------------------------------------------------------------
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.padding)) {
            GestaltButton("Save", leadingIcon = ChromeIcon.CheckCircle, onClick = {
                error = form.validate()
                if (error == null) {
                    val id = if (form.isNew) appState.newId() else form.id!!
                    val sortOrder = existing?.sortOrder ?: appState.nextSortOrder()
                    appState.upsert(form.toConnection(id, sortOrder), form.passwordChars())
                    appState.route = Route.List
                }
            })
            Spacer(Modifier.weight(1f))
            if (!form.isNew) {
                GestaltButton("Delete", style = ButtonStyle.Outlined, leadingIcon = ChromeIcon.Trash, onClick = {
                    appState.delete(form.id!!); appState.route = Route.List
                }, )
            }
            GestaltButton("Cancel", style = ButtonStyle.Ghost, onClick = { appState.route = Route.List })
        }
    }
}

/** The inline detection hint + insecure flag + mode override under the smart address field. */
@Composable
private fun AddressHint(form: ConnectionFormState) {
    if (form.address.isBlank()) return
    val colors = GestaltTheme.colors
    val spacing = GestaltTheme.spacing
    val d = form.detected
    Column(verticalArrangement = Arrangement.spacedBy(spacing.paddingSmall)) {
        if (d.valid) {
            val target = "${d.scheme}://${d.host}${d.port?.let { ":$it" } ?: ""}"
            GestaltText(
                "Detected ${modeLabel(form.effectiveMode)} · $target",
                role = TextRole.Label,
                color = colors.textSecondary,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.paddingSmall)) {
                GestaltLabel("Mode")
                SegmentedToggle(ConnectionMode.entries.toList(), form.effectiveMode, { form.modeOverride = it }, ::modeLabel)
            }
        } else {
            GestaltText("Enter a host:port or full URL — e.g. oie.lab:8443.", role = TextRole.Label, color = colors.textMuted)
        }
        if (form.isPlainHttp) {
            InlineNote(
                StatusLevel.Warning,
                "No transport security: plain http isn't encrypted. Jar digests + signatures are still verified.",
            )
        }
    }
}

private fun modeLabel(mode: ConnectionMode): String = when (mode) {
    ConnectionMode.Standard -> "Standard"
    ConnectionMode.LocalhostPortForward -> "Localhost"
    ConnectionMode.Custom -> "Custom"
}

/** Compact, inline rendering of an in-form Test-connection report (per-step PASS/FAIL). */
@Composable
private fun InlineTestResult(report: ConnectionTestReport) {
    val spacing = GestaltTheme.spacing
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(GestaltTheme.shapes.md))
            .background(GestaltTheme.colors.surfaceVariant)
            .padding(spacing.padding),
        verticalArrangement = Arrangement.spacedBy(spacing.paddingSmall),
    ) {
        val headlineColor = when {
            report.ok -> GestaltTheme.colors.success
            report.needsTrustDecision -> GestaltTheme.colors.warning
            else -> GestaltTheme.colors.error
        }
        GestaltText(
            "${report.headline()} — ${report.target}",
            role = TextRole.Heading,
            color = headlineColor,
        )
        report.checks.forEach { c ->
            // A pin-able trust decision (untrusted self-signed / name mismatch) shows amber, not red.
            val trustDecision = c.status == CheckStatus.FAIL &&
                (c.classification == TlsFailureClass.PKIX_PATH || c.classification == TlsFailureClass.HOSTNAME_MISMATCH)
            val color = when {
                trustDecision -> GestaltTheme.colors.warning
                c.status == CheckStatus.PASS -> GestaltTheme.colors.success
                c.status == CheckStatus.FAIL -> GestaltTheme.colors.error
                c.status == CheckStatus.WARN -> GestaltTheme.colors.warning
                else -> GestaltTheme.colors.textMuted
            }
            // Lead with the plain-language line; fall back to the first line of raw detail.
            val line = c.plainLanguage ?: c.detail.lineSequence().firstOrNull().orEmpty()
            // A pin-able trust decision reads "REVIEW" (amber), not the raw red "FAIL".
            val badge = if (trustDecision) "REVIEW" else "${c.status}"
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.paddingSmall)) {
                GestaltText(badge, role = TextRole.Label, color = color, modifier = Modifier.width(52.dp))
                GestaltText("${c.name} — $line", role = TextRole.Label, color = GestaltTheme.colors.textSecondary, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** A collapsible advanced section with a summary chip shown when it holds a non-default value. */
@Composable
private fun CollapsibleSection(title: String, summary: String?, content: @Composable ColumnScope.() -> Unit) {
    val spacing = GestaltTheme.spacing
    val colors = GestaltTheme.colors
    var expanded by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(GestaltTheme.shapes.md))
            .border(GestaltTheme.shapes.borderWidth, colors.outline, RoundedCornerShape(GestaltTheme.shapes.md)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(interactionSource = interaction, indication = null) { expanded = !expanded }
                .padding(spacing.padding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.paddingSmall),
        ) {
            GestaltIcon(if (expanded) ChromeIcon.ChevronDown else ChromeIcon.ChevronRight, tint = colors.textSecondary, size = 18.dp)
            GestaltText(title, role = TextRole.Heading, modifier = Modifier.weight(1f))
            summary?.let { SummaryChip(it) }
        }
        if (expanded) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(start = spacing.padding, end = spacing.padding, bottom = spacing.padding),
                verticalArrangement = Arrangement.spacedBy(spacing.padding),
                content = content,
            )
        }
    }
}

@Composable
private fun SummaryChip(text: String) {
    val colors = GestaltTheme.colors
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(GestaltTheme.shapes.lg))
            .background(colors.accent.copy(alpha = 0.14f))
            .padding(horizontal = GestaltTheme.spacing.paddingSmall, vertical = 2.dp),
    ) {
        GestaltText(text, role = TextRole.Label, color = colors.accent)
    }
}

@Composable
private fun RuntimeSection(form: ConnectionFormState) {
    val spacing = GestaltTheme.spacing
    Field("Java runtime") {
        GestaltRadioGroup(
            options = JavaHomeMode.entries.toList(),
            selected = form.javaHomeMode,
            onSelect = { form.javaHomeMode = it },
            label = {
                when (it) {
                    JavaHomeMode.Default -> "Default (launcher's JVM)"
                    JavaHomeMode.Bundled -> "Bundled runtime"
                    JavaHomeMode.Custom -> "Custom path"
                }
            },
        )
        when (form.javaHomeMode) {
            JavaHomeMode.Bundled -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.paddingSmall)) {
                GestaltLabel("Version")
                SegmentedToggle(listOf("8", "17"), form.bundledVersion, { form.bundledVersion = it }, { it })
            }
            JavaHomeMode.Custom -> GestaltTextField(form.customJavaHome, { form.customJavaHome = it }, modifier = Modifier.fillMaxWidth(), placeholder = "C:\\Java\\jdk-17", leadingIcon = ChromeIcon.Folder)
            JavaHomeMode.Default -> {}
        }
    }
    Field("Custom JavaFX home (optional)") {
        GestaltTextField(form.javaFxHome, { form.javaFxHome = it }, modifier = Modifier.fillMaxWidth(), leadingIcon = ChromeIcon.Folder)
    }
    Field("Max heap size") {
        GestaltTextField(form.heap, { form.heap = it }, modifier = Modifier.width(200.dp), placeholder = "512m")
    }
    LabeledToggle("Show Java console", form.showConsole, { form.showConsole = it })
    Field("Close launcher after this connection launches") {
        SegmentedToggle(
            CloseAfterLaunch.entries.toList(),
            form.closeAfterLaunch,
            { form.closeAfterLaunch = it },
            { closeAfterLaunchLabel(it) },
        )
        GestaltText(
            "Inherit follows the global Settings → Launch toggle. Closes only after a successful launch.",
            role = TextRole.Label,
            color = GestaltTheme.colors.textMuted,
        )
    }
}

private fun closeAfterLaunchLabel(value: CloseAfterLaunch): String = when (value) {
    CloseAfterLaunch.Inherit -> "Inherit"
    CloseAfterLaunch.On -> "Always"
    CloseAfterLaunch.Off -> "Never"
}

@Composable
private fun SecuritySection(form: ConnectionFormState) {
    Field("SSL protocols") {
        LabeledToggle("Custom", form.sslProtocolsCustom, { form.sslProtocolsCustom = it })
        if (form.sslProtocolsCustom) {
            GestaltTextField(form.sslProtocolsValue, { form.sslProtocolsValue = it }, modifier = Modifier.fillMaxWidth(), placeholder = "TLSv1.2,TLSv1.3")
        }
    }
    Field("SSL cipher suites") {
        LabeledToggle("Custom", form.sslCiphersCustom, { form.sslCiphersCustom = it })
        if (form.sslCiphersCustom) {
            GestaltTextField(form.sslCiphersValue, { form.sslCiphersValue = it }, modifier = Modifier.fillMaxWidth(), placeholder = "TLS_AES_256_GCM_SHA384")
        }
    }
    LabeledToggle("Use legacy DH settings", form.legacyDh, { form.legacyDh = it })
    Field("Pinned server cert SHA-256 (optional)") {
        GestaltTextField(form.pin, { form.pin = it }, modifier = Modifier.fillMaxWidth(), placeholder = "ab12… (enables exact-cert pinning)", leadingIcon = ChromeIcon.ShieldCheck)
    }
}

@Composable
private fun IconAndCredentials(appState: AppState, form: ConnectionFormState) {
    val spacing = GestaltTheme.spacing
    Field("Icon") { IconPicker(appState, form.icon) { form.icon = it } }
    Field("Credentials (optional — stored only in the OS keychain, never in connections.json)") {
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.padding)) {
            GestaltTextField(form.username, { form.username = it }, modifier = Modifier.weight(1f), label = "Username", placeholder = "alice")
            GestaltTextField(form.password, { form.password = it }, modifier = Modifier.weight(1f), label = "Password", visualTransformation = PasswordVisualTransformation())
        }
    }
}

private fun iconCredsSummary(form: ConnectionFormState): String? = buildList {
    if (form.icon != com.gestalt.launcher.model.Connection.DEFAULT_ICON_KEY) add("custom icon")
    if (form.username.isNotBlank()) add(form.username.trim())
}.takeIf { it.isNotEmpty() }?.joinToString(" · ")

@Composable
private fun Field(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(GestaltTheme.spacing.paddingSmall)) {
        GestaltLabel(label)
        content()
    }
}

/**
 * The §6.6 icon picker: built-in glyphs, an optional bundled icon pack, and the user's own custom icons,
 * each selectable. Custom icons can be uploaded (sanitized on import) and managed (rename / remove) inline.
 */
@Composable
private fun IconPicker(appState: AppState, selectedKey: String, onSelect: (String) -> Unit) {
    val spacing = GestaltTheme.spacing
    var managing by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(spacing.paddingSmall)) {
        IconSection("Built-in", appState, ConnectionIcon.all.map { it.key }, selectedKey, onSelect)
        if (PackIcons.all.isNotEmpty()) {
            IconSection("Icon pack", appState, PackIcons.all.map { it.key }, selectedKey, onSelect)
        }

        GestaltLabel("Custom")
        val customKeys = appState.customIcons.map { it.key }
        if (customKeys.isNotEmpty()) {
            IconGrid(appState, customKeys, selectedKey, onSelect)
        } else {
            GestaltText("No custom icons yet — upload a .svg, .png, or .jpg.", role = TextRole.Label, color = GestaltTheme.colors.textMuted)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.paddingSmall)) {
            GestaltButton("Upload icon…", style = ButtonStyle.Outlined, leadingIcon = ChromeIcon.Plus, onClick = {
                FilePickers.openImageFile()?.let { appState.addCustomIcon(it) }
            })
            if (appState.customIcons.isNotEmpty()) {
                GestaltButton(if (managing) "Done" else "Manage icons", style = ButtonStyle.Ghost, onClick = { managing = !managing })
            }
        }
        if (managing && appState.customIcons.isNotEmpty()) ManageCustomIcons(appState)
    }
}

@Composable
private fun IconSection(title: String, appState: AppState, keys: List<String>, selectedKey: String, onSelect: (String) -> Unit) {
    GestaltLabel(title)
    IconGrid(appState, keys, selectedKey, onSelect)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IconGrid(appState: AppState, keys: List<String>, selectedKey: String, onSelect: (String) -> Unit) {
    val colors = GestaltTheme.colors
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(GestaltTheme.spacing.paddingSmall),
        verticalArrangement = Arrangement.spacedBy(GestaltTheme.spacing.paddingSmall),
    ) {
        keys.forEach { key ->
            val selected = key == selectedKey
            val interaction = remember(key) { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(GestaltTheme.shapes.md))
                    .background(if (selected) colors.primary.copy(alpha = 0.14f) else colors.surfaceVariant)
                    .then(if (selected) Modifier.border(GestaltTheme.shapes.borderWidth, colors.accent, RoundedCornerShape(GestaltTheme.shapes.md)) else Modifier)
                    .clickable(interactionSource = interaction, indication = null) { onSelect(key) },
                contentAlignment = Alignment.Center,
            ) {
                ConnectionGlyph(appState, key, tint = if (selected) colors.primary else colors.textSecondary, size = 24.dp)
            }
        }
    }
}

/** Inline rename / remove list for the user's custom icons. */
@Composable
private fun ManageCustomIcons(appState: AppState) {
    val spacing = GestaltTheme.spacing
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(GestaltTheme.shapes.sm))
            .background(GestaltTheme.colors.surfaceVariant)
            .padding(spacing.padding),
        verticalArrangement = Arrangement.spacedBy(spacing.paddingSmall),
    ) {
        GestaltText("Manage custom icons", role = TextRole.Label, color = GestaltTheme.colors.textSecondary)
        appState.customIcons.forEach { icon -> ManageIconRow(appState, icon) }
    }
}

@Composable
private fun ManageIconRow(appState: AppState, icon: CustomIcon) {
    val spacing = GestaltTheme.spacing
    var label by remember(icon.id) { mutableStateOf(icon.label) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.paddingSmall)) {
        ConnectionGlyph(appState, icon.key, tint = GestaltTheme.colors.textSecondary, size = 24.dp)
        GestaltTextField(label, { label = it }, modifier = Modifier.weight(1f))
        GestaltButton("Rename", style = ButtonStyle.Ghost, onClick = { appState.renameCustomIcon(icon.id, label) })
        GestaltIconButton(ChromeIcon.Trash, onClick = { appState.deleteCustomIcon(icon.id) }, tint = GestaltTheme.colors.error)
    }
}
