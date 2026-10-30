/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.fx.ui

import com.gestalt.fx.model.LauncherModel
import com.gestalt.fx.model.SortMode
import com.gestalt.fx.model.TreeRow
import com.gestalt.launcher.icon.CustomIconStore
import javafx.collections.ListChangeListener
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.ContextMenu
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.control.ListView
import javafx.scene.control.MenuItem
import javafx.scene.control.TextField
import javafx.scene.control.Tooltip
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox

/** What the sidebar asks of the window (prompts, guards, the launch). */
interface ShellActions {
    /** Make [id] the active connection, after settling unsaved edits; [then] runs once it is active. */
    fun requestSelect(id: String, then: () -> Unit = {})
    fun launch(id: String)
    fun newConnection(folderId: String?)
    fun newFolder(parentId: String?)
    fun renameConnection(id: String)
    fun renameFolder(id: String)
    fun deleteConnection(id: String)
    fun deleteFolder(id: String)
    fun importConnections()
    fun exportConnections()
}

/**
 * The left pane: header with the toolbar, the filter field, and the tree — a ListView over
 * [LauncherModel.rows] with fixed 24 px cells (recycled, so scrolling stays flat), 16 px indent per
 * level, a chevron and a count on folders, the connection's icon as a launch button, and the keyboard
 * map from the design (↑↓ move, ←→ collapse/expand, Enter launch, F2 rename, Del delete).
 */
class Sidebar(private val model: LauncherModel, private val host: ShellActions, private val icons: CustomIconStore?) : VBox() {

    private val list = ListView<TreeRow>()
    private val countLabel = Label().apply { styleClass += "sidebar-count" }
    private val filterField = TextField().apply { promptText = "Filter connections" }
    private val emptyLabel = Label().apply { styleClass += "tree-empty"; isWrapText = true; isVisible = false; isManaged = false }
    private val toggleAllButton = Controls.iconButton("chevrons-down-up", "Collapse all folders") { model.setAllExpanded(!model.anyFolderExpanded) }
    private val sortButton = Controls.iconButton("arrow-up-down", "Sort") {}

    /** The row the keyboard cursor is on, kept across row rebuilds. */
    private var focusKey: String? = null
    private var restoring = false

    init {
        styleClass += "sidebar"
        minWidth = 240.0

        // --- header: title, count, toolbar ---
        val header = HBox().apply {
            styleClass += "sidebar-header"
            alignment = Pos.CENTER_LEFT
            children += Label("Connections").apply { styleClass += "sidebar-title"; minWidth = 0.0 }
            children += countLabel
            children += Controls.spacer()
            children += Controls.iconButton("import", "Import connections") { host.importConnections() }
            children += Controls.iconButton("share", "Export connections") { host.exportConnections() }
            children += Controls.iconButton("plus", "New connection") { host.newConnection(focusedFolderId()) }
            children += Controls.iconButton("folder-plus", "New folder") { host.newFolder(null) }
            children += sortButton
            children += toggleAllButton
        }
        sortButton.setOnAction { showSortMenu() }

        // --- filter ---
        val filterWrap = StackPane().apply {
            styleClass += "filter-wrap"
            children += filterField
            children += Icons.glyph("search", 14.0).apply { isMouseTransparent = true; StackPane.setAlignment(this, Pos.CENTER_LEFT); StackPane.setMargin(this, Insets(0.0, 0.0, 8.0, 9.0)) }
            val clear = Controls.iconButton("x", "Clear filter", 12.0) { filterField.clear(); list.requestFocus() }.apply {
                minWidth = 20.0; prefWidth = 20.0; maxWidth = 20.0; minHeight = 20.0; prefHeight = 20.0; maxHeight = 20.0
                isVisible = false; isManaged = false
                StackPane.setAlignment(this, Pos.CENTER_RIGHT); StackPane.setMargin(this, Insets(0.0, 5.0, 8.0, 0.0))
            }
            children += clear
            filterField.textProperty().addListener { _, _, v -> val on = !v.isNullOrEmpty(); clear.isVisible = on; clear.isManaged = on }
        }
        filterField.textProperty().bindBidirectional(model.filter)
        filterField.addEventFilter(KeyEvent.KEY_PRESSED) { e ->
            when (e.code) {
                KeyCode.ESCAPE -> { filterField.clear(); e.consume() }
                KeyCode.DOWN, KeyCode.ENTER -> {
                    model.rows.firstOrNull { it is TreeRow.ConnectionRow }?.let { row -> selectRow(row.key); host.requestSelect((row as TreeRow.ConnectionRow).connection.id) }
                    list.requestFocus(); e.consume()
                }
                else -> Unit
            }
        }

        // --- tree ---
        list.styleClass += "tree-list"
        list.items = model.rows
        list.fixedCellSize = 24.0
        list.setCellFactory { RowCell() }
        list.placeholder = Region()
        VBox.setVgrow(list, Priority.ALWAYS)
        list.selectionModel.selectedItemProperty().addListener { _, _, row ->
            if (row == null || restoring) return@addListener
            focusKey = row.key
            if (row is TreeRow.ConnectionRow && row.connection.id != model.activeId.get()) host.requestSelect(row.connection.id)
        }
        model.rows.addListener(ListChangeListener { restoreCursor(); updateEmptyState() })
        model.connections.addListener(ListChangeListener { countLabel.text = model.connections.size.toString() })
        model.folders.addListener(ListChangeListener { refreshToggleAll() })
        model.launches.addListener(javafx.collections.MapChangeListener { list.refresh() })
        model.activeId.addListener { _, _, _ -> list.refresh() }
        list.addEventFilter(KeyEvent.KEY_PRESSED) { e -> onKey(e) }
        list.setOnContextMenuRequested { e -> showMenuFor(null, e.screenX, e.screenY); e.consume() }

        children.addAll(header, filterWrap, emptyLabel, list)
        countLabel.text = model.connections.size.toString()
        refreshToggleAll()
        updateEmptyState()
    }

    /** The folder a new connection should land in: the cursor row's folder (or the folder itself). */
    private fun focusedFolderId(): String? = when (val row = list.selectionModel.selectedItem) {
        is TreeRow.FolderRow -> row.folder.id
        is TreeRow.ConnectionRow -> row.connection.folderId
        null -> null
    }

    /** Put the cursor on [key] without triggering a selection request. */
    fun selectRow(key: String?) {
        focusKey = key
        restoreCursor()
    }

    fun focusTree() = list.requestFocus()

    private fun restoreCursor() {
        val key = focusKey ?: return
        val idx = model.rows.indexOfFirst { it.key == key }
        restoring = true
        try {
            if (idx >= 0) {
                if (list.selectionModel.selectedIndex != idx) list.selectionModel.clearAndSelect(idx)
                list.focusModel.focus(idx)
            } else {
                list.selectionModel.clearSelection()
            }
        } finally {
            restoring = false
        }
    }

    private fun updateEmptyState() {
        val q = model.filter.get().orEmpty().trim()
        val empty = model.rows.isEmpty()
        emptyLabel.text = if (q.isNotEmpty()) "No connections match “$q”." else "No connections yet. Add one with + or import a file."
        emptyLabel.isVisible = empty
        emptyLabel.isManaged = empty
    }

    private fun refreshToggleAll() {
        val anyOpen = model.anyFolderExpanded
        Controls.setGlyph(toggleAllButton, if (anyOpen) "chevrons-down-up" else "chevrons-up-down")
        toggleAllButton.tooltip = Tooltip(if (anyOpen) "Collapse all folders" else "Expand all folders")
    }

    // --- keyboard ---------------------------------------------------------------------------------

    private fun onKey(e: KeyEvent) {
        val row = list.selectionModel.selectedItem
        when (e.code) {
            KeyCode.ENTER -> {
                when (row) {
                    is TreeRow.ConnectionRow -> host.requestSelect(row.connection.id) { host.launch(row.connection.id) }
                    is TreeRow.FolderRow -> model.toggleFolder(row.folder.id)
                    null -> Unit
                }
                e.consume()
            }
            KeyCode.LEFT -> {
                when (row) {
                    is TreeRow.FolderRow -> if (row.expanded && model.filter.get().isNullOrBlank()) model.setFolderExpanded(row.folder.id, false) else moveToParent(row.folder.parentId)
                    is TreeRow.ConnectionRow -> moveToParent(row.connection.folderId)
                    null -> Unit
                }
                e.consume()
            }
            KeyCode.RIGHT -> {
                if (row is TreeRow.FolderRow) {
                    if (!row.expanded) model.setFolderExpanded(row.folder.id, true)
                    else {
                        val idx = list.selectionModel.selectedIndex
                        if (idx + 1 < model.rows.size && model.rows[idx + 1].depth > row.depth) list.selectionModel.clearAndSelect(idx + 1)
                    }
                }
                e.consume()
            }
            KeyCode.F2 -> {
                when (row) {
                    is TreeRow.ConnectionRow -> host.renameConnection(row.connection.id)
                    is TreeRow.FolderRow -> host.renameFolder(row.folder.id)
                    null -> Unit
                }
                e.consume()
            }
            KeyCode.DELETE -> {
                when (row) {
                    is TreeRow.ConnectionRow -> host.deleteConnection(row.connection.id)
                    is TreeRow.FolderRow -> host.deleteFolder(row.folder.id)
                    null -> Unit
                }
                e.consume()
            }
            else -> Unit
        }
    }

    private fun moveToParent(folderId: String?) {
        if (folderId == null) return
        val idx = model.rows.indexOfFirst { it is TreeRow.FolderRow && it.folder.id == folderId }
        if (idx >= 0) list.selectionModel.clearAndSelect(idx)
    }

    // --- menus ------------------------------------------------------------------------------------

    private fun showSortMenu() {
        val menu = ContextMenu()
        menu.items += Controls.menuHeader("Sort by")
        for (mode in SortMode.values()) {
            menu.items += Controls.menuItem(mode.label, checked = model.sort.get() == mode, hint = mode.hint) { model.sort.set(mode) }
        }
        menu.items += Controls.menuSeparator()
        menu.items += Controls.menuItem("Folders first", checked = model.foldersFirst.get()) { model.foldersFirst.set(!model.foldersFirst.get()) }
        menu.show(sortButton, javafx.geometry.Side.BOTTOM, 0.0, 4.0)
    }

    private fun showMenuFor(row: TreeRow?, x: Double, y: Double) {
        val menu = ContextMenu()
        val items: List<MenuItem> = when (row) {
            is TreeRow.ConnectionRow -> {
                val c = row.connection
                buildList {
                    add(Controls.menuItem("Launch", "play", hint = "Enter") { host.requestSelect(c.id) { host.launch(c.id) } })
                    add(Controls.menuItem("Rename…", "pencil", hint = "F2") { host.renameConnection(c.id) })
                    add(Controls.menuItem("Duplicate", "copy") { model.duplicate(c.id)?.let { host.requestSelect(it.id) } })
                    if (c.folderId != null) add(Controls.menuItem("Move to top level", "corner-left-up") { model.moveConnection(c.id, null) })
                    add(Controls.menuSeparator())
                    add(Controls.menuItem("Delete", "trash-2", hint = "Del", danger = true) { host.deleteConnection(c.id) })
                }
            }
            is TreeRow.FolderRow -> {
                val f = row.folder
                listOf(
                    Controls.menuItem("New connection here", "plus") { host.newConnection(f.id) },
                    Controls.menuItem("New subfolder", "folder-plus") { host.newFolder(f.id) },
                    Controls.menuItem("Rename…", "pencil", hint = "F2") { host.renameFolder(f.id) },
                    Controls.menuItem(if (f.expanded) "Collapse" else "Expand", if (f.expanded) "chevrons-down-up" else "chevrons-up-down") { model.toggleFolder(f.id) },
                    Controls.menuSeparator(),
                    Controls.menuItem("Delete folder", "trash-2", hint = "Del", danger = true) { host.deleteFolder(f.id) },
                )
            }
            null -> listOf(
                Controls.menuItem("New connection", "plus") { host.newConnection(null) },
                Controls.menuItem("New folder", "folder-plus") { host.newFolder(null) },
                Controls.menuSeparator(),
                Controls.menuItem("Expand all", "chevrons-up-down") { model.setAllExpanded(true) },
                Controls.menuItem("Collapse all", "chevrons-down-up") { model.setAllExpanded(false) },
            )
        }
        menu.items.setAll(items)
        menu.show(list, x, y)
    }

    // --- cells ------------------------------------------------------------------------------------

    private inner class RowCell : ListCell<TreeRow>() {
        private val chevron = StackPane().apply { minWidth = 14.0; prefWidth = 14.0; maxWidth = 14.0; minHeight = 16.0; prefHeight = 16.0 }
        private val iconSlot = StackPane().apply { styleClass += "row-icon-button"; minWidth = 16.0; prefWidth = 16.0; maxWidth = 16.0; minHeight = 16.0; prefHeight = 16.0; maxHeight = 16.0 }
        private val name = Label().apply { styleClass += "row-name"; minWidth = 0.0; maxWidth = Double.MAX_VALUE; HBox.setHgrow(this, Priority.ALWAYS) }
        private val count = Label().apply { styleClass += "row-count" }
        private val row = HBox(chevron, iconSlot, name, count).apply { styleClass += "tree-row"; alignment = Pos.CENTER_LEFT }
        private var current: TreeRow? = null

        init {
            styleClass += "tree-cell"
            row.setOnMouseClicked { e ->
                val r = current ?: return@setOnMouseClicked
                if (e.button != MouseButton.PRIMARY) return@setOnMouseClicked
                when (r) {
                    is TreeRow.FolderRow -> if (e.clickCount == 1) model.toggleFolder(r.folder.id)
                    is TreeRow.ConnectionRow -> if (e.clickCount == 2) host.requestSelect(r.connection.id) { host.launch(r.connection.id) }
                }
            }
            iconSlot.setOnMouseClicked { e ->
                val r = current as? TreeRow.ConnectionRow ?: return@setOnMouseClicked
                if (e.button == MouseButton.PRIMARY) { host.requestSelect(r.connection.id) { host.launch(r.connection.id) }; e.consume() }
            }
            setOnContextMenuRequested { e ->
                current?.let { r -> showMenuFor(r, e.screenX, e.screenY); e.consume() }
            }
        }

        override fun updateItem(item: TreeRow?, empty: Boolean) {
            super.updateItem(item, empty)
            current = item
            if (empty || item == null) {
                graphic = null
                text = null
                tooltip = null
                return
            }
            row.padding = Insets(0.0, 8.0, 0.0, 4.0 + item.depth * 16.0)
            name.text = item.name
            row.styleClass.removeAll("folder", "active")
            when (item) {
                is TreeRow.FolderRow -> {
                    row.styleClass += "folder"
                    chevron.children.setAll(Icons.glyph(if (item.expanded) "chevron-down" else "chevron-right", 12.0, "-text-muted", 2.0))
                    iconSlot.children.setAll(Icons.glyph(if (item.expanded) "folder-open" else "folder", 16.0, "-text-muted"))
                    iconSlot.styleClass.remove("row-icon-button")
                    count.text = item.count.toString()
                    count.isVisible = true; count.isManaged = true
                    tooltip = null
                }
                is TreeRow.ConnectionRow -> {
                    val c = item.connection
                    if (c.id == model.activeId.get()) row.styleClass += "active"
                    chevron.children.clear()
                    iconSlot.children.setAll(iconFor(c.id, c.icon))
                    if ("row-icon-button" !in iconSlot.styleClass) iconSlot.styleClass += "row-icon-button"
                    count.isVisible = false; count.isManaged = false
                    tooltip = Tooltip(c.serverUrl)
                }
            }
            graphic = row
            text = null
        }

        private fun iconFor(id: String, key: String): Node {
            val state = model.launchState(id)
            return when {
                state?.inFlight == true -> Icons.glyph("loader-circle", 16.0, "-text-muted")
                else -> Controls.connectionIcon(key, 16.0, icons)
            }
        }
    }
}
