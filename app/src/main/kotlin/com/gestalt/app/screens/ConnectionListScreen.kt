/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.app.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import com.gestalt.app.AppState
import com.gestalt.app.LaunchView
import com.gestalt.app.Reachability
import com.gestalt.app.Route
import com.gestalt.app.SortMode
import com.gestalt.app.StatusBanner
import com.gestalt.app.ui.ConnectionGlyph
import com.gestalt.app.ui.EnvChip
import com.gestalt.app.ui.GestaltIconButton
import com.gestalt.app.ui.InsecureTransportChip
import com.gestalt.app.ui.LabeledToggle
import com.gestalt.app.ui.SegmentedToggle
import com.gestalt.app.ui.SelectCheckbox
import com.gestalt.app.ui.FilePickers
import com.gestalt.design.components.GestaltButton
import com.gestalt.design.components.GestaltCard
import com.gestalt.design.components.GestaltIcon
import com.gestalt.design.components.GestaltLabel
import com.gestalt.design.components.GestaltStatusDot
import com.gestalt.design.components.GestaltStatusPill
import com.gestalt.design.components.GestaltText
import com.gestalt.design.components.GestaltTextField
import com.gestalt.design.components.StatusLevel
import com.gestalt.design.components.TextRole
import com.gestalt.design.icons.ChromeIcon
import com.gestalt.design.theme.ButtonStyle
import com.gestalt.design.theme.Density
import com.gestalt.design.theme.GestaltTheme
import com.gestalt.launcher.importer.ConnectionImport.DedupePolicy
import com.gestalt.launcher.launch.LaunchPhase
import com.gestalt.launcher.model.Connection
import com.gestalt.launcher.model.JavaHomeMode
import kotlin.math.roundToInt

/** The primary screen: the connection list with search, sort, drag-reorder, status, and row actions. */
@Composable
fun ConnectionListScreen(appState: AppState) {
    val spacing = GestaltTheme.spacing
    // Ctrl/Cmd-K or -F focuses the search field (the signal is bumped by the global key handler).
    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(appState.searchFocusSignal) {
        if (appState.searchFocusSignal > 0) runCatching { searchFocus.requestFocus() }
    }

    Column(Modifier.fillMaxSize()) {
        StatusBanner(appState)

        Column(
            modifier = Modifier.padding(spacing.paddingLarge),
            verticalArrangement = Arrangement.spacedBy(spacing.padding),
        ) {
            // The primary "Add connection" CTA lives in the content header, beside the title.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.padding)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    GestaltText("Connections", role = TextRole.Display)
                    GestaltText(
                        "${appState.connections.size} server(s) — manage, test, and launch the OIE / Mirth Administrator",
                        role = TextRole.Body, color = GestaltTheme.colors.textSecondary,
                    )
                }
                GestaltButton("Add connection", onClick = { appState.startNewConnection() }, leadingIcon = ChromeIcon.Plus)
            }

            SummaryCards(appState)

            // Search + the only list-scoped quick action that stays in the content (reachability sweep).
            // Sort / density / import / export / on-import / close-after-launch all live in the sidebar rail.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.padding)) {
                GestaltTextField(
                    value = appState.search,
                    onValueChange = { appState.search = it },
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(searchFocus)
                        .onFocusChanged { appState.searchFocused = it.isFocused },
                    placeholder = "Search name, URL, or group…  (Ctrl-K)",
                    leadingIcon = ChromeIcon.Search,
                )
                GestaltButton("Check all", onClick = { appState.probeAll() }, style = ButtonStyle.Ghost, leadingIcon = ChromeIcon.Refresh)
            }

            // Contextual bulk-action bar — only present while one or more rows are selected.
            if (appState.selectionCount > 0) SelectionToolbar(appState)
        }

        // Memoized: the filter+sort only recomputes when the list, search, or sort actually change —
        // not on every recomposition/scroll.
        val visible by remember { derivedStateOf { appState.visibleConnections() } }
        // Auto-refresh stale reachability once the list appears (debounced, TTL-cached, off-thread).
        LaunchedEffect(Unit) { delay(400); appState.refreshStaleReachability() }

        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = spacing.paddingLarge)) {
            when {
                visible.isEmpty() -> EmptyState()
                appState.sort == SortMode.Group -> GroupedLazyList(appState, visible)
                // Manual drag-reorder only when the full list is shown — reordering a filtered subset
                // would renumber only the visible rows and scramble the hidden ones' sortOrder.
                appState.sort == SortMode.Manual && appState.search.isBlank() -> ReorderableLazyList(appState, visible)
                else -> LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(spacing.paddingSmall)) {
                    items(visible, key = { it.id }, contentType = { "connection" }) { ConnectionRow(appState, it, dragHandle = null) }
                }
            }
        }
    }
}

/**
 * The Connections-screen tools, rendered in the sidebar rail beneath the nav: Import / Export, and the
 * Sort / Density / On-import / Close-after-launch options. Styled for the indigo rail (sidebar tokens).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConnectionTools(appState: AppState) {
    val spacing = GestaltTheme.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.padding), modifier = Modifier.fillMaxWidth()) {
        // "Add connection" is the primary CTA in the content header; the rail holds the secondary tools.
        RailGroupLabel("Tools")
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.paddingSmall), modifier = Modifier.fillMaxWidth()) {
            RailButton("Import", ChromeIcon.Folder, Modifier.weight(1f)) {
                FilePickers.openFile()?.let { appState.importAsync(it.toPath(), appState.importPolicy) }
            }
            RailButton("Export", ChromeIcon.Folder, Modifier.weight(1f)) {
                FilePickers.saveFile()?.let { appState.exportAsync(it.toPath()) }
            }
        }
        RailGroup("Sort") {
            SortMode.entries.forEach { s -> RailChip(s.name, appState.sort == s) { appState.sort = s } }
        }
        RailGroup("Density") {
            listOf(Density.Comfortable, Density.Compact).forEach { d ->
                RailChip(d.name, appState.density == d) { appState.updateDensity(d) }
            }
        }
        RailGroup("On import") {
            DedupePolicy.entries.forEach { p ->
                RailChip(p.name, appState.importPolicy == p) { appState.updateImportPolicy(p) }
            }
        }
        RailGroup("Close after launch") {
            listOf(false, true).forEach { on ->
                RailChip(if (on) "On" else "Off", appState.closeAfterLaunch == on) { appState.updateCloseAfterLaunch(on) }
            }
        }
    }
}

/** A rail section: a muted label over a wrapping row of selectable chips. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RailGroup(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(GestaltTheme.spacing.paddingSmall)) {
        RailGroupLabel(label)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(GestaltTheme.spacing.paddingSmall),
            verticalArrangement = Arrangement.spacedBy(GestaltTheme.spacing.paddingSmall),
        ) { content() }
    }
}

@Composable
private fun RailGroupLabel(label: String) {
    GestaltText(label.uppercase(), role = TextRole.Label, color = GestaltTheme.colors.onSidebar)
}

/** A selectable rail chip: filled (active nav colour) when selected, faint-outlined otherwise. */
@Composable
private fun RailChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = GestaltTheme.colors
    val shape = RoundedCornerShape(GestaltTheme.shapes.sm)
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .clip(shape)
            .background(if (selected) colors.sidebarActive else Color.Transparent, shape)
            .then(if (selected) Modifier else Modifier.border(1.dp, colors.onSidebar.copy(alpha = 0.30f), shape))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = GestaltTheme.spacing.paddingSmall, vertical = 4.dp),
    ) {
        GestaltText(label, role = TextRole.Label, color = if (selected) colors.onSidebarActive else colors.onSidebar)
    }
}

/** A rail action button (Import / Export), filled in the active-nav colour for contrast on the indigo rail. */
@Composable
private fun RailButton(label: String, icon: ChromeIcon, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = GestaltTheme.colors
    val shape = RoundedCornerShape(GestaltTheme.shapes.sm)
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .clip(shape)
            .background(colors.sidebarActive, shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = GestaltTheme.spacing.paddingSmall, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GestaltTheme.spacing.paddingSmall),
    ) {
        GestaltIcon(icon, tint = colors.onSidebarActive, size = 16.dp)
        GestaltText(label, role = TextRole.Label, color = colors.onSidebarActive)
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(GestaltTheme.spacing.paddingLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(GestaltTheme.spacing.padding),
    ) {
        GestaltIcon(ChromeIcon.Server, tint = GestaltTheme.colors.textMuted, size = 40.dp)
        GestaltText("No connections yet", role = TextRole.Heading, color = GestaltTheme.colors.textSecondary)
        GestaltText("Use “New” to add one, or “Import” to bring in a legacy launcher file.", role = TextRole.Body, color = GestaltTheme.colors.textMuted)
    }
}

@Composable
private fun GroupedLazyList(appState: AppState, connections: List<Connection>) {
    val groups = remember(connections) {
        connections.groupBy { it.group?.takeIf { g -> g.isNotBlank() } ?: "Ungrouped" }.toSortedMap()
    }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(GestaltTheme.spacing.paddingSmall)) {
        groups.forEach { (group, rows) ->
            item(key = "group:$group") {
                GestaltText(
                    group,
                    role = TextRole.Heading,
                    color = GestaltTheme.colors.textSecondary,
                    modifier = Modifier.padding(top = GestaltTheme.spacing.padding, bottom = GestaltTheme.spacing.paddingSmall),
                )
            }
            items(rows, key = { it.id }, contentType = { "connection" }) { ConnectionRow(appState, it, dragHandle = null) }
        }
    }
}

/** A manually-reorderable lazy list (stable keys). The drag handle reorders; on drop the order persists. */
@Composable
private fun ReorderableLazyList(appState: AppState, connections: List<Connection>) {
    var order by remember(connections.joinToString { it.id }) { mutableStateOf(connections) }
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragBy by remember { mutableStateOf(0f) }
    var rowHeight by remember { mutableStateOf(0f) }

    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(GestaltTheme.spacing.paddingSmall)) {
        items(order, key = { it.id }, contentType = { "connection" }) { conn ->
            val isDragging = conn.id == draggingId
            Box(
                modifier = Modifier
                    .graphicsLayer { translationY = if (isDragging) dragBy else 0f }
                    .onGloballyPositioned { if (rowHeight == 0f) rowHeight = it.size.height.toFloat() },
            ) {
                val handle: @Composable () -> Unit = {
                    GestaltIcon(
                        ChromeIcon.Drag,
                        tint = GestaltTheme.colors.textMuted,
                        size = 18.dp,
                        modifier = Modifier.pointerInput(conn.id) {
                            detectDragGestures(
                                onDragStart = { draggingId = conn.id; dragBy = 0f },
                                onDragEnd = {
                                    draggingId = null
                                    dragBy = 0f
                                    appState.commitOrder(order.map { it.id })
                                },
                                onDragCancel = { draggingId = null; dragBy = 0f },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    dragBy += dragAmount.y
                                    if (rowHeight > 0f) {
                                        val current = order.indexOfFirst { it.id == conn.id }
                                        val target = (current + (dragBy / rowHeight).roundToInt())
                                            .coerceIn(0, order.size - 1)
                                        if (target != current) {
                                            val mutable = order.toMutableList()
                                            mutable.add(target, mutable.removeAt(current))
                                            order = mutable
                                            dragBy -= (target - current) * rowHeight
                                        }
                                    }
                                },
                            )
                        },
                    )
                }
                ConnectionRow(appState, conn, dragHandle = handle)
            }
        }
    }
}

/** The four summary cards (Total / Reachable / Environments / Runtimes), computed from real data. */
@Composable
private fun SummaryCards(appState: AppState) {
    val conns = appState.connections
    val total = conns.size
    val reachable = conns.count { appState.reachability[it.id] == Reachability.Ok }
    val envs = conns.mapNotNull { AppState.envCue(it) }.distinct().size
    val java17 = conns.count { it.javaHome.mode == JavaHomeMode.Bundled && it.javaHome.bundledVersion == "17" }
    val java8 = conns.count { it.javaHome.mode == JavaHomeMode.Bundled && it.javaHome.bundledVersion == "8" }
    Row(horizontalArrangement = Arrangement.spacedBy(GestaltTheme.spacing.padding)) {
        SummaryCard("Total", "$total", Modifier.weight(1f))
        SummaryCard("Reachable", "$reachable of $total", Modifier.weight(1f))
        SummaryCard("Environments", "$envs", Modifier.weight(1f))
        SummaryCard("Runtimes", "Java 17·$java17   8·$java8", Modifier.weight(1f))
    }
}

@Composable
private fun SummaryCard(label: String, value: String, modifier: Modifier) {
    GestaltCard(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(GestaltTheme.spacing.paddingSmall)) {
            GestaltText(label.uppercase(), role = TextRole.Label, color = GestaltTheme.colors.textSecondary)
            GestaltText(value, role = TextRole.Title, color = GestaltTheme.colors.textPrimary)
        }
    }
}

/** The contextual bulk-action bar shown while rows are selected: select-all, group, export, delete. */
@Composable
private fun SelectionToolbar(appState: AppState) {
    val spacing = GestaltTheme.spacing
    val colors = GestaltTheme.colors
    var groupOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    GestaltCard(
        modifier = Modifier.fillMaxWidth(),
        color = lerp(colors.surface, colors.primary, 0.06f),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.padding)) {
            SelectCheckbox(checked = appState.allVisibleSelected(), onToggle = { appState.toggleSelectAllVisible() })
            GestaltText(
                "${appState.selectionCount} selected",
                role = TextRole.Body,
                color = colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            if (confirmDelete) {
                GestaltText("Delete ${appState.selectionCount}?", role = TextRole.Label, color = colors.error)
                GestaltButton("Confirm", style = ButtonStyle.Filled, leadingIcon = ChromeIcon.Trash, onClick = {
                    appState.deleteSelected(); confirmDelete = false
                })
                GestaltButton("Cancel", style = ButtonStyle.Ghost, onClick = { confirmDelete = false })
            } else {
                Box {
                    GestaltButton("Group…", style = ButtonStyle.Outlined, leadingIcon = ChromeIcon.Folder, onClick = { groupOpen = true })
                    if (groupOpen) GroupPopup(appState) { groupOpen = false }
                }
                GestaltButton("Export", style = ButtonStyle.Ghost, leadingIcon = ChromeIcon.Folder, onClick = {
                    FilePickers.saveFile()?.let { appState.exportSelectedAsync(it.toPath()) }
                })
                GestaltButton("Delete", style = ButtonStyle.Outlined, leadingIcon = ChromeIcon.Trash, onClick = { confirmDelete = true })
                GestaltButton("Clear", style = ButtonStyle.Ghost, onClick = { appState.clearSelection() })
            }
        }
    }
}

/** The "Group…" popup: pick an existing group, type a new one, or clear the group on the selection. */
@Composable
private fun GroupPopup(appState: AppState, onClose: () -> Unit) {
    val spacing = GestaltTheme.spacing
    val colors = GestaltTheme.colors
    var text by remember { mutableStateOf("") }
    Popup(
        alignment = Alignment.TopStart,
        onDismissRequest = onClose,
        properties = PopupProperties(focusable = true),
    ) {
        GestaltCard(Modifier.width(280.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.paddingSmall)) {
                GestaltText("Move ${appState.selectionCount} to a group", role = TextRole.Label, color = colors.textSecondary)
                appState.existingGroups().forEach { g ->
                    OverflowItem(g, ChromeIcon.Folder) { appState.groupSelected(g); onClose() }
                }
                GestaltTextField(value = text, onValueChange = { text = it }, placeholder = "New group name…")
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.paddingSmall)) {
                    GestaltButton("Apply", enabled = text.isNotBlank(), onClick = { appState.groupSelected(text); onClose() })
                    GestaltButton("Clear group", style = ButtonStyle.Ghost, onClick = { appState.groupSelected(null); onClose() })
                }
            }
        }
    }
}

/**
 * One connection rendered as a data-table row inside a card. A leading checkbox toggles multi-select; the
 * trailing actions are Launch (▶ icon) and Edit (✏ icon) buttons plus the ⋮ overflow. Single click sets the
 * keyboard cursor, double-click launches, and the cursor draws a focus ring. Selected rows tint toward the
 * primary colour; the Launch icon is disabled while a launch for that connection is already in flight.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConnectionRow(appState: AppState, conn: Connection, dragHandle: (@Composable () -> Unit)?) {
    val spacing = GestaltTheme.spacing
    val colors = GestaltTheme.colors
    val interaction = remember { MutableInteractionSource() }
    // Per-row derived reads: a cursor move or a selection toggle then recomposes only the row(s) whose
    // boolean actually flips, not every visible row (both `selection` and `focusedId` are shared state).
    val selected by remember(conn.id) { derivedStateOf { conn.id in appState.selection } }
    val focused by remember(conn.id) { derivedStateOf { appState.focusedId == conn.id } }
    // Reading this row's launch entry (a per-key SnapshotStateMap read) recomposes only this row on change.
    val phase = appState.launches[conn.id]?.phase
    val launching = phase == LaunchPhase.Downloading || phase == LaunchPhase.Verifying ||
        phase == LaunchPhase.Launching || phase == LaunchPhase.Running
    val ringShape = RoundedCornerShape(GestaltTheme.shapes.md + 2.dp)
    // The focus-ring space is ALWAYS reserved (transparent when unfocused), so landing the cursor on a row
    // only changes the ring colour — never its geometry — keeping a constant row height and no layout jump.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .border(2.dp, if (focused) colors.primary else Color.Transparent, ringShape)
            .padding(3.dp),
    ) {
    GestaltCard(
        color = if (selected) lerp(colors.surface, colors.primary, 0.07f) else colors.surface,
        modifier = Modifier
            .fillMaxWidth()
            // Single click sets the keyboard cursor (instant); double click launches. The explicit Launch /
            // Edit buttons are the primary affordances for heavy use, so the row click no longer opens edit.
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = { appState.setFocused(conn.id) },
                onDoubleClick = { appState.launch(conn.id) },
            ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.padding)) {
            SelectCheckbox(checked = selected, onToggle = { appState.toggleSelected(conn.id) })
            dragHandle?.invoke()
            ConnectionGlyph(appState, conn.icon)
            Column(Modifier.weight(1.5f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                GestaltText(conn.name, role = TextRole.Body, color = colors.textPrimary)
                conn.group?.takeIf { it.isNotBlank() }?.let {
                    GestaltText(it, role = TextRole.Label, color = colors.textMuted)
                }
            }
            GestaltText(conn.serverUrl, role = TextRole.Mono, color = colors.textMuted, modifier = Modifier.weight(1.6f))
            if (conn.isPlainHttp) InsecureTransportChip()
            AppState.envCue(conn)?.let { EnvChip(it) }
            RuntimePill(conn)
            RowStatus(appState, conn)
            // Launch (▶ icon), disabled while a launch is already in flight; double-click on the row also launches.
            GestaltIconButton(ChromeIcon.Play, onClick = { appState.launch(conn.id) }, tint = colors.primary, enabled = !launching, size = 20.dp)
            GestaltIconButton(ChromeIcon.Pencil, onClick = { appState.route = Route.Edit(conn.id) }, tint = colors.textSecondary, size = 18.dp)
            OverflowMenu(appState, conn)
        }
    }
    }
}

/** Bundled-runtime pill (Java 8/17), or the default-JVM hint. */
@Composable
private fun RuntimePill(conn: Connection) {
    val text = when (conn.javaHome.mode) {
        JavaHomeMode.Bundled -> "Java ${conn.javaHome.bundledVersion}"
        JavaHomeMode.Custom -> "Custom JDK"
        JavaHomeMode.Default -> "Default JVM"
    }
    GestaltStatusPill(text = text, level = StatusLevel.Neutral)
}

/** The ⋮ row overflow: Duplicate / Test / Export / Delete. (Edit + Launch are dedicated row buttons.) */
@Composable
private fun OverflowMenu(appState: AppState, conn: Connection) {
    var open by remember { mutableStateOf(false) }
    Box {
        GestaltIconButton(ChromeIcon.Sliders, onClick = { open = true })
        if (open) {
            Popup(
                alignment = Alignment.TopEnd,
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                GestaltCard(Modifier.width(190.dp)) {
                    Column {
                        OverflowItem("Duplicate", ChromeIcon.Plus) { open = false; appState.duplicate(conn) }
                        OverflowItem("Test connection", ChromeIcon.Connect) { open = false; appState.testConnection(conn.id) }
                        OverflowItem("Export…", ChromeIcon.Folder) {
                            open = false; FilePickers.saveFile()?.let { appState.exportAsync(it.toPath()) }
                        }
                        OverflowItem("Delete", ChromeIcon.Trash, danger = true) { open = false; appState.delete(conn.id) }
                    }
                }
            }
        }
    }
}

@Composable
private fun OverflowItem(label: String, icon: ChromeIcon, danger: Boolean = false, onClick: () -> Unit) {
    val colors = GestaltTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val tint = if (danger) colors.error else colors.textSecondary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(vertical = GestaltTheme.spacing.paddingSmall, horizontal = GestaltTheme.spacing.paddingSmall),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GestaltTheme.spacing.paddingSmall),
    ) {
        GestaltIcon(icon, tint = tint, size = 16.dp)
        GestaltText(label, role = TextRole.Body, color = if (danger) colors.error else colors.textPrimary)
    }
}

/** The status affordance: launch progress when launching, otherwise a clickable reachability dot. */
@Composable
private fun RowStatus(appState: AppState, conn: Connection) {
    val launch: LaunchView? = appState.launches[conn.id]
    when (launch?.phase) {
        LaunchPhase.Downloading -> GestaltText("Verifying…", role = TextRole.Label, color = GestaltTheme.colors.primary)
        LaunchPhase.Verifying -> GestaltText("Verifying…", role = TextRole.Label, color = GestaltTheme.colors.primary)
        LaunchPhase.Launching -> GestaltText("Launching…", role = TextRole.Label, color = GestaltTheme.colors.primary)
        LaunchPhase.Running -> {
            val interaction = remember { MutableInteractionSource() }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(GestaltTheme.spacing.paddingSmall),
                modifier = Modifier
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(GestaltTheme.shapes.sm))
                    .clickable(interactionSource = interaction, indication = null) { appState.clearLaunch(conn.id) },
            ) {
                GestaltStatusDot(StatusLevel.Success)
                GestaltText("Running (dismiss)", role = TextRole.Label, color = GestaltTheme.colors.success)
            }
        }
        LaunchPhase.Failed -> {
            val interaction = remember { MutableInteractionSource() }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(GestaltTheme.spacing.paddingSmall),
                modifier = Modifier
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(GestaltTheme.shapes.sm))
                    .clickable(interactionSource = interaction, indication = null) { appState.showDiagnosis(conn.id) },
            ) {
                GestaltIcon(ChromeIcon.AlertTriangle, tint = GestaltTheme.colors.error, size = 16.dp)
                GestaltText("Failed (details)", role = TextRole.Label, color = GestaltTheme.colors.error)
            }
        }
        null -> {
            val level = when (appState.reachability[conn.id]) {
                Reachability.Ok -> StatusLevel.Success
                Reachability.Unreachable -> StatusLevel.Error
                else -> StatusLevel.Neutral
            }
            val interaction = remember { MutableInteractionSource() }
            GestaltStatusDot(
                level = level,
                modifier = Modifier
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .clickable(interactionSource = interaction, indication = null) { appState.probe(conn.id) }
                    .padding(GestaltTheme.spacing.paddingSmall),
            )
        }
    }
}
