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
import javafx.application.Platform
import javafx.scene.control.ContextMenu
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.control.ListView
import javafx.scene.control.Menu
import javafx.scene.control.MenuItem
import javafx.scene.control.SelectionMode
import javafx.scene.control.TextField
import javafx.scene.control.Tooltip
import javafx.scene.input.ClipboardContent
import javafx.scene.input.DataFormat
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.TransferMode
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
    /** End the launcher for real — port-forwards it holds for running Administrators included (asked first). */
    fun quit()
}

/**
 * The left pane: header with the toolbar, the filter field, and the tree — a ListView over
 * [LauncherModel.rows] with fixed 24 px cells (recycled, so scrolling stays flat), 16 px indent per
 * level, a chevron and a count on folders, the connection's icon as a launch button, and the keyboard
 * map from the design (↑↓ move, ←→ collapse/expand, Enter launch, F2 rename, Del delete). Rows
 * multi-select (Ctrl / Shift click, Ctrl+A); a drag or "Move to" carries every selected row at once,
 * folders included. The detail pane follows the row last clicked.
 */
class Sidebar(private val model: LauncherModel, private val host: ShellActions, private val icons: CustomIconStore?) : VBox() {

    private val list = ListView<TreeRow>()
    private val countLabel = Label().apply { styleClass += "sidebar-count" }
    private val filterField = TextField().apply { promptText = "Filter connections" }
    private val emptyLabel = Label().apply { styleClass += "tree-empty"; isWrapText = true; isVisible = false; isManaged = false }
    private val sortButton = Controls.iconButton("arrow-up-down", "Sort") {}
    private val moreButton = Controls.iconButton("ellipsis", "More") {}

    /** The row the keyboard cursor is on, kept across row rebuilds. */
    private var focusKey: String? = null
    private var restoring = false

    /** The row being renamed inline (its cell shows a text field), if any. */
    private var renamingKey: String? = null

    init {
        styleClass += "sidebar"
        minWidth = 240.0

        // --- header: title, count, toolbar (the two things done often, sort, and a "more" menu for the rest —
        // six icons did not fit beside the title at the sidebar's narrower widths) ---
        val header = HBox().apply {
            styleClass += "sidebar-header"
            alignment = Pos.CENTER_LEFT
            children += Label("Connections").apply { styleClass += "sidebar-title"; minWidth = 0.0 }
            children += countLabel
            children += Controls.spacer()
            children += Controls.iconButton("plus", "New connection") { host.newConnection(focusedFolderId()) }
            children += Controls.iconButton("folder-plus", "New folder") { host.newFolder(null) }
            children += sortButton
            children += moreButton
        }
        sortButton.setOnAction { showSortMenu() }
        moreButton.setOnAction { showMoreMenu() }

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
        list.selectionModel.selectionMode = SelectionMode.MULTIPLE
        VBox.setVgrow(list, Priority.ALWAYS)
        // The lead of the selection (the row last clicked) is the cursor; a connection there becomes the active one.
        list.selectionModel.selectedItemProperty().addListener { _, _, row ->
            if (row == null || restoring) return@addListener
            focusKey = row.key
            if (row is TreeRow.ConnectionRow && row.connection.id != model.activeId.get()) host.requestSelect(row.connection.id)
        }
        // Rebuilt rows keep only the cursor: a multi-selection's other rows would be at other indices now.
        model.rows.addListener(ListChangeListener { restoreCursor(collapse = true); updateEmptyState() })
        model.connections.addListener(ListChangeListener { countLabel.text = model.connections.size.toString() })
        model.launches.addListener(javafx.collections.MapChangeListener { list.refresh() })
        model.activeId.addListener { _, _, _ -> list.refresh() }
        list.addEventFilter(KeyEvent.KEY_PRESSED) { e -> onKey(e) }
        list.setOnContextMenuRequested { e -> showMenuFor(null, e.screenX, e.screenY); e.consume() }

        // A drag that ends on the empty part of the list drops at the top level.
        list.setOnDragOver { e ->
            if (e.dragboard.hasContent(ROW_FORMAT)) {
                e.acceptTransferModes(TransferMode.MOVE)
                if ("drop-root" !in list.styleClass) list.styleClass += "drop-root"
            }
            e.consume()
        }
        list.setOnDragExited { list.styleClass.remove("drop-root") }
        list.setOnDragDropped { e ->
            val keys = draggedKeys(e.dragboard)
            val ok = keys.isNotEmpty() && model.moveInto(keys, null)
            if (ok) selectRows(keys)
            list.styleClass.remove("drop-root")
            e.isDropCompleted = ok
            e.consume()
        }

        children.addAll(header, filterWrap, emptyLabel, list)
        countLabel.text = model.connections.size.toString()
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

    /**
     * Select every row of [keys] that is shown — the rows just moved together. The cursor stays on the
     * row it was on when that row moved too (so the active connection and the cursor agree), else the last.
     */
    private fun selectRows(keys: List<String>) {
        val indices = keys.mapNotNull { k -> model.rows.indexOfFirst { it.key == k }.takeIf { it >= 0 } }
        if (indices.isEmpty()) return
        val cursor = focusKey?.takeIf { it in keys }?.let { k -> model.rows.indexOfFirst { it.key == k } }?.takeIf { it >= 0 } ?: indices.last()
        focusKey = model.rows[cursor].key
        restoring = true
        try {
            list.selectionModel.clearSelection()
            // The last index given becomes the selection's lead (selectedItem), so the cursor goes last.
            val others = indices.filter { it != cursor }
            if (others.isEmpty()) list.selectionModel.select(cursor)
            else list.selectionModel.selectIndices(others.first(), *(others.drop(1) + cursor).toIntArray())
            list.focusModel.focus(cursor)
        } finally {
            restoring = false
        }
    }

    /** The keys selected, in display order — what a drag or "Move to" carries when [key] is one of them. */
    private fun selectionWith(key: String): List<String> {
        val selected = list.selectionModel.selectedItems.filterNotNull().map { it.key }
        return if (key in selected && selected.size > 1) model.rows.map { it.key }.filter { it in selected } else listOf(key)
    }

    private fun draggedKeys(db: javafx.scene.input.Dragboard): List<String> =
        (db.getContent(ROW_FORMAT) as? String)?.split('\n')?.filter { it.isNotEmpty() }.orEmpty()

    fun focusTree() = list.requestFocus()

    /** Rename [key] in place: its cell turns into a text field (Enter commits, Esc cancels). */
    fun startRename(key: String) {
        renamingKey = key
        focusKey = key
        restoreCursor()
        list.refresh()
        list.scrollTo(model.rows.indexOfFirst { it.key == key }.coerceAtLeast(0))
    }

    private fun commitRename(key: String, text: String) {
        if (renamingKey != key) return
        renamingKey = null
        if (key.startsWith("c:")) model.rename(key.substring(2), text) else if (key.startsWith("f:")) model.renameFolder(key.substring(2), text)
        list.refresh()
        list.requestFocus()
    }

    private fun cancelRename() {
        if (renamingKey == null) return
        renamingKey = null
        list.refresh()
        list.requestFocus()
    }

    /** Put the selection's cursor on [focusKey]; with [collapse], drop any other selected rows too. */
    private fun restoreCursor(collapse: Boolean = false) {
        val key = focusKey ?: return
        val idx = model.rows.indexOfFirst { it.key == key }
        restoring = true
        try {
            if (idx >= 0) {
                if (list.selectionModel.selectedIndex != idx || (collapse && list.selectionModel.selectedIndices.size > 1)) list.selectionModel.clearAndSelect(idx)
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

    // --- keyboard ---------------------------------------------------------------------------------

    private fun onKey(e: KeyEvent) {
        if (renamingKey != null) return // the rename field owns the keyboard
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

    /** Expand / collapse all, and import / export — the header's "more" menu; the same entries sit on the list's background menu. */
    private fun showMoreMenu() {
        val menu = ContextMenu()
        menu.items.setAll(libraryItems())
        menu.show(moreButton, javafx.geometry.Side.BOTTOM, 0.0, 4.0)
    }

    private fun libraryItems(): List<MenuItem> = listOf(
        Controls.menuItem("Expand all folders", "chevrons-up-down") { model.setAllExpanded(true) },
        Controls.menuItem("Collapse all folders", "chevrons-down-up") { model.setAllExpanded(false) },
        Controls.menuSeparator(),
        Controls.menuItem("Import connections…", "import") { host.importConnections() },
        Controls.menuItem("Export connections…", "share") { host.exportConnections() },
        Controls.menuSeparator(),
        // Closing the window keeps the launcher alive while an Administrator runs through its port-forward
        // (it lingers, hidden, and the next start brings it back); this is the way to end it for real.
        Controls.menuItem("Quit launcher", "x") { host.quit() },
    )

    private fun showMenuFor(row: TreeRow?, x: Double, y: Double) {
        val menu = ContextMenu()
        val items: List<MenuItem> = when (row) {
            is TreeRow.ConnectionRow -> {
                val c = row.connection
                buildList {
                    add(Controls.menuItem("Launch", "play", hint = "Enter") { host.requestSelect(c.id) { host.launch(c.id) } })
                    add(Controls.menuItem("Rename", "pencil", hint = "F2") { startRename(row.key) })
                    add(Controls.menuItem("Duplicate", "copy") { model.duplicate(c.id)?.let { host.requestSelect(it.id) } })
                    add(Controls.menuSeparator())
                    addAll(moveUpDown(row.key))
                    add(moveToMenu(row.key, c.folderId))
                    add(Controls.menuSeparator())
                    add(Controls.menuItem("Delete", "trash-2", hint = "Del", danger = true) { host.deleteConnection(c.id) })
                }
            }
            is TreeRow.FolderRow -> {
                val f = row.folder
                listOf(
                    Controls.menuItem("New connection here", "plus") { host.newConnection(f.id) },
                    Controls.menuItem("New subfolder", "folder-plus") { host.newFolder(f.id) },
                    Controls.menuItem("Rename", "pencil", hint = "F2") { startRename(row.key) },
                    Controls.menuItem(if (f.expanded) "Collapse" else "Expand", if (f.expanded) "chevrons-down-up" else "chevrons-up-down") { model.toggleFolder(f.id) },
                    Controls.menuSeparator(),
                ) + moveUpDown(row.key) + listOf(
                    moveToMenu(row.key, f.parentId),
                    Controls.menuSeparator(),
                    Controls.menuItem("Delete folder", "trash-2", hint = "Del", danger = true) { host.deleteFolder(f.id) },
                )
            }
            null -> listOf(
                Controls.menuItem("New connection", "plus") { host.newConnection(null) },
                Controls.menuItem("New folder", "folder-plus") { host.newFolder(null) },
                Controls.menuSeparator(),
            ) + libraryItems()
        }
        menu.items.setAll(items)
        menu.show(list, x, y)
    }

    /** "Move up" / "Move down" among the row's siblings; the one that can't move is greyed out. */
    private fun moveUpDown(key: String): List<MenuItem> {
        val siblings = model.siblingKeys(key)
        val i = siblings.indexOf(key)
        val up = Controls.menuItem("Move up", "chevron-up") { model.moveUp(key); selectRow(key) }.apply { isDisable = i <= 0 }
        val down = Controls.menuItem("Move down", "chevron-down") { model.moveDown(key); selectRow(key) }.apply { isDisable = i < 0 || i >= siblings.size - 1 }
        return listOf(up, down)
    }

    /**
     * "Move to ▸": the top level and every folder (indented), except any moved folder's own subtree.
     * When [key] is one of several selected rows, the whole selection moves ("Move 3 rows to").
     */
    private fun moveToMenu(key: String, currentParent: String?): Menu {
        val keys = selectionWith(key)
        val menu = Menu(if (keys.size > 1) "Move ${keys.size} rows to" else "Move to")
        val excluded = HashSet<String>()
        for (k in keys) if (k.startsWith("f:")) { val id = k.substring(2); excluded += id; excluded += model.store.descendantFolderIds(id) }
        fun move(folderId: String?) { if (model.moveInto(keys, folderId)) selectRows(keys) }
        menu.items += Controls.menuItem("Top level", checked = keys.size == 1 && currentParent == null) { move(null) }
        fun walk(parentId: String?, depth: Int) {
            model.folders.filter { it.parentId == parentId && it.id !in excluded }.sortedBy { it.name.lowercase() }.forEach { f ->
                menu.items += Controls.menuItem(f.name, checked = keys.size == 1 && currentParent == f.id, indent = depth + 1) { move(f.id) }
                walk(f.id, depth + 1)
            }
        }
        walk(null, 0)
        return menu
    }

    // --- cells ------------------------------------------------------------------------------------

    private enum class DropMode { Into, Before, After }

    private inner class RowCell : ListCell<TreeRow>() {
        private val chevron = StackPane().apply { minWidth = 14.0; prefWidth = 14.0; maxWidth = 14.0; minHeight = 16.0; prefHeight = 16.0 }
        private val iconSlot = StackPane().apply { styleClass += "row-icon-button"; minWidth = 16.0; prefWidth = 16.0; maxWidth = 16.0; minHeight = 16.0; prefHeight = 16.0; maxHeight = 16.0 }
        private val name = Label().apply { styleClass += "row-name"; minWidth = 0.0; maxWidth = Double.MAX_VALUE; HBox.setHgrow(this, Priority.ALWAYS) }
        private val renameField = TextField().apply { styleClass += "rename-field"; minWidth = 0.0; maxWidth = Double.MAX_VALUE; HBox.setHgrow(this, Priority.ALWAYS); isVisible = false; isManaged = false }
        private val count = Label().apply { styleClass += "row-count" }
        private val row = HBox(chevron, iconSlot, name, renameField, count).apply { styleClass += "tree-row"; alignment = Pos.CENTER_LEFT }
        private var current: TreeRow? = null
        private var renameArmedFor: String? = null

        init {
            styleClass += "tree-cell"
            // A cell's preferred width would be its full name, and the list grows a horizontal scrollbar
            // to fit the widest one. At 0 the cells take the list's own width and a long name ellipsizes
            // in place (the tooltip carries the whole of it).
            prefWidth = 0.0
            row.setOnMouseClicked { e ->
                val r = current ?: return@setOnMouseClicked
                if (e.button != MouseButton.PRIMARY) return@setOnMouseClicked
                val extending = e.isShortcutDown || e.isControlDown || e.isShiftDown // a selection gesture, not a click on the folder
                when (r) {
                    is TreeRow.FolderRow -> if (e.clickCount == 1 && !extending) model.toggleFolder(r.folder.id)
                    is TreeRow.ConnectionRow -> if (e.clickCount == 2 && !extending) host.requestSelect(r.connection.id) { host.launch(r.connection.id) }
                }
            }
            iconSlot.setOnMouseClicked { e ->
                val r = current as? TreeRow.ConnectionRow ?: return@setOnMouseClicked
                if (e.button == MouseButton.PRIMARY) { host.requestSelect(r.connection.id) { host.launch(r.connection.id) }; e.consume() }
            }
            setOnContextMenuRequested { e ->
                current?.let { r -> showMenuFor(r, e.screenX, e.screenY); e.consume() }
            }

            // Inline rename: Enter commits, Esc cancels, leaving the field commits.
            renameField.setOnAction { current?.let { r -> commitRename(r.key, renameField.text) } }
            renameField.addEventFilter(KeyEvent.KEY_PRESSED) { e -> if (e.code == KeyCode.ESCAPE) { cancelRename(); e.consume() } }
            renameField.focusedProperty().addListener { _, _, focused -> if (!focused) current?.let { r -> if (renamingKey == r.key) commitRename(r.key, renameField.text) } }
            renameField.setOnMouseClicked { it.consume() }

            // Drag a row — or the whole selection, when the row is part of one — to reorder (manual
            // order) or to move into a folder; the list's empty area means the top level.
            setOnDragDetected { e ->
                val r = current ?: return@setOnDragDetected
                if (renamingKey != null || e.button != MouseButton.PRIMARY) return@setOnDragDetected
                val keys = selectionWith(r.key)
                val db = startDragAndDrop(TransferMode.MOVE)
                db.setContent(ClipboardContent().apply { put(ROW_FORMAT, keys.joinToString("\n")) })
                db.dragView = dragImage(keys.size)
                e.consume()
            }
            setOnDragOver { e ->
                val r = current
                val keys = draggedKeys(e.dragboard)
                if (r != null && keys.isNotEmpty()) {
                    val mode = dropMode(keys, r, e.y)
                    if (mode != null) { e.acceptTransferModes(TransferMode.MOVE); showDrop(mode) } else clearDrop()
                }
                e.consume()
            }
            setOnDragExited { clearDrop() }
            setOnDragDropped { e ->
                val r = current
                val keys = draggedKeys(e.dragboard)
                var ok = false
                if (r != null && keys.isNotEmpty()) {
                    ok = when (dropMode(keys, r, e.y)) {
                        DropMode.Into -> model.moveInto(keys, (r as TreeRow.FolderRow).folder.id)
                        DropMode.Before -> model.moveBefore(keys, r.key)
                        DropMode.After -> model.moveAfter(keys, r.key)
                        null -> false
                    }
                    if (ok) selectRows(keys)
                }
                clearDrop()
                e.isDropCompleted = ok
                e.consume()
            }
        }

        /** The drag's picture: this row, with a count badge beside its name when it drags a selection. */
        private fun dragImage(n: Int): javafx.scene.image.Image {
            if (n <= 1) return row.snapshot(null, null)
            val badge = Label("$n rows").apply { styleClass += "drag-count" }
            row.children += badge // in the scene, so the badge is styled; gone again before the next layout pass
            try {
                return row.snapshot(null, null)
            } finally {
                row.children.remove(badge)
            }
        }

        /**
         * Where a drop on this row would land: into a folder (its lower three quarters), before / after
         * (connections, or a folder's top quarter) — or nowhere, when the row is among the dragged ones
         * or a dragged folder would land inside itself.
         */
        private fun dropMode(keys: List<String>, target: TreeRow, y: Double): DropMode? {
            if (target.key in keys) return null
            val h = height.takeIf { it > 0 } ?: 24.0
            val mode = when (target) {
                is TreeRow.FolderRow -> if (y < h * 0.25) DropMode.Before else DropMode.Into
                is TreeRow.ConnectionRow -> if (y < h * 0.5) DropMode.Before else DropMode.After
            }
            val landsIn = if (mode == DropMode.Into) (target as TreeRow.FolderRow).folder.id else when (target) {
                is TreeRow.FolderRow -> target.folder.parentId
                is TreeRow.ConnectionRow -> target.connection.folderId
            }
            return if (model.canMove(keys, landsIn)) mode else null
        }

        private fun showDrop(mode: DropMode) {
            clearDrop()
            row.styleClass += when (mode) { DropMode.Into -> "drop-into"; DropMode.Before -> "drop-before"; DropMode.After -> "drop-after" }
        }

        private fun clearDrop() {
            row.styleClass.removeAll("drop-into", "drop-before", "drop-after")
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
            val renaming = renamingKey == item.key
            name.isVisible = !renaming; name.isManaged = !renaming
            renameField.isVisible = renaming; renameField.isManaged = renaming
            if (renaming) {
                if (renameArmedFor != item.key) {
                    renameArmedFor = item.key
                    renameField.text = item.name
                    Platform.runLater { renameField.requestFocus(); renameField.selectAll() }
                }
            } else {
                renameArmedFor = null
            }
            when (item) {
                is TreeRow.FolderRow -> {
                    row.styleClass += "folder"
                    chevron.children.setAll(Icons.glyph(if (item.expanded) "chevron-down" else "chevron-right", 12.0, "-text-muted", 2.0))
                    iconSlot.children.setAll(Icons.glyph(if (item.expanded) "folder-open" else "folder", 16.0, "-text-muted"))
                    iconSlot.styleClass.remove("row-icon-button")
                    count.text = item.count.toString()
                    count.isVisible = true; count.isManaged = true
                    tooltip = Tooltip(item.name)
                }
                is TreeRow.ConnectionRow -> {
                    val c = item.connection
                    if (c.id == model.activeId.get()) row.styleClass += "active"
                    chevron.children.clear()
                    iconSlot.children.setAll(iconFor(c.id, c.icon))
                    if ("row-icon-button" !in iconSlot.styleClass) iconSlot.styleClass += "row-icon-button"
                    count.isVisible = false; count.isManaged = false
                    tooltip = Tooltip("${c.name}\n${c.serverUrl}")
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

private val ROW_FORMAT: DataFormat = DataFormat.lookupMimeType("application/x-oie-launcher-row") ?: DataFormat("application/x-oie-launcher-row")
