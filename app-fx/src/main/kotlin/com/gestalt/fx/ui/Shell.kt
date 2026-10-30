/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.fx.ui

import com.gestalt.fx.model.LaunchEvents
import com.gestalt.fx.model.LauncherModel
import com.gestalt.fx.model.TreeRow
import com.gestalt.launcher.diag.Diagnosis
import com.gestalt.launcher.icon.CustomIconStore
import com.gestalt.launcher.importer.ConnectionImport
import com.gestalt.launcher.launch.LaunchPhase
import com.gestalt.launcher.tls.CertDetails
import javafx.scene.Scene
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.KeyCombination
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseEvent
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.stage.FileChooser
import javafx.stage.Stage
import java.security.cert.X509Certificate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The launcher window: sidebar | handle | detail pane inside a StackPane that also hosts the modal
 * layer and the toast. Owns the prompts the design specifies (unsaved changes, delete, trust this
 * certificate, certificate expired, publisher review) and the keyboard shortcuts (Ctrl+F filter,
 * Ctrl+S save, Ctrl+Enter launch).
 */
class Shell(private val model: LauncherModel, private val stage: Stage, private val icons: CustomIconStore?) : ShellActions, DetailActions {

    val root = StackPane()
    private val modal = ModalHost(root)
    private val toast = ToastHost(root)
    private val sidebar = Sidebar(model, this, icons)
    private val detail = DetailPane(model, this, icons)
    private val certificates = CertificatesDialog(model, modal) { detail.refreshCertificates() }
    val scene: Scene

    init {
        val handle = HBox().apply {
            styleClass += "split-handle"
            alignment = javafx.geometry.Pos.CENTER
            children += Region().apply { styleClass += "split-line"; maxHeight = Double.MAX_VALUE }
        }
        sidebar.prefWidth = model.prefs.sidebarWidth.coerceIn(240.0, 420.0)
        sidebar.minWidth = 240.0
        sidebar.maxWidth = 420.0
        var dragX = 0.0
        var startW = 0.0
        handle.addEventHandler(MouseEvent.MOUSE_PRESSED) { e -> dragX = e.screenX; startW = sidebar.width; handle.styleClass += "dragging" }
        handle.addEventHandler(MouseEvent.MOUSE_DRAGGED) { e ->
            val w = (startW + (e.screenX - dragX)).coerceIn(240.0, minOf(420.0, root.width - 420.0))
            sidebar.prefWidth = w
        }
        handle.addEventHandler(MouseEvent.MOUSE_RELEASED) { handle.styleClass.remove("dragging"); model.prefs.sidebarWidth = sidebar.prefWidth }

        HBox.setHgrow(detail, Priority.ALWAYS)
        detail.minWidth = 0.0
        val row = HBox(sidebar, handle, detail)
        HBox.setMargin(handle, javafx.geometry.Insets(0.0, -3.0, 0.0, -3.0))
        root.children += row

        scene = Scene(root, 1120.0, 760.0)
        Theme.apply(scene, model.dark.get())
        detail.setThemeDark(model.dark.get())
        model.dark.addListener { _, _, dark -> Theme.apply(scene, dark); detail.setThemeDark(dark) }

        scene.addEventFilter(KeyEvent.KEY_PRESSED) { e -> onShortcut(e) }

        model.onRequestClose = { shutdown(); stage.close() }

        // First selection: the first connection, like the design opens on one.
        model.rows.firstOrNull { it is TreeRow.ConnectionRow }?.let { r ->
            val id = (r as TreeRow.ConnectionRow).connection.id
            model.select(id)
            sidebar.selectRow(r.key)
        }
    }

    // --- shortcuts ---------------------------------------------------------------------------------

    private val saveCombo = KeyCodeCombination(KeyCode.S, KeyCombination.SHORTCUT_DOWN)
    private val filterCombo = KeyCodeCombination(KeyCode.F, KeyCombination.SHORTCUT_DOWN)
    private val launchCombo = KeyCodeCombination(KeyCode.ENTER, KeyCombination.SHORTCUT_DOWN)

    private fun onShortcut(e: KeyEvent) {
        when {
            saveCombo.match(e) -> { saveActive(); e.consume() }
            filterCombo.match(e) -> { focusFilter(); e.consume() }
            launchCombo.match(e) -> { launchActive(); e.consume() }
        }
    }

    private fun focusFilter() {
        // The filter field is the sidebar's TextField; look it up by its prompt.
        sidebar.lookupAll(".text-input").firstOrNull()?.requestFocus()
    }

    // --- ShellActions ------------------------------------------------------------------------------

    override fun requestSelect(id: String, then: () -> Unit) {
        if (id == model.activeId.get()) { then(); return }
        guard(
            proceed = { model.select(id); sidebar.selectRow("c:$id"); then() },
            cancelled = { model.activeId.get()?.let { sidebar.selectRow("c:$it") } },
        )
    }

    /** Settle unsaved edits before [proceed]: the design's "Save changes to …?" with Discard / Cancel / Save. */
    private fun guard(proceed: () -> Unit, cancelled: () -> Unit = {}) {
        if (!model.dirty.get()) { proceed(); return }
        val name = model.draft.get()?.name.orEmpty().ifBlank { "this connection" }
        modal.confirm(
            "Save changes to “$name”?",
            "Your changes will be lost if you don’t save them.",
            listOf(
                ModalAction("Discard", Controls.ButtonKind.Ghost) { model.revert(); proceed() },
                ModalAction("Cancel", Controls.ButtonKind.Secondary) { cancelled() },
                ModalAction("Save", Controls.ButtonKind.Primary, isDefault = true) { if (model.save()) proceed() else cancelled() },
            ),
        )
    }

    override fun launch(id: String) {
        val conn = model.store.get(id) ?: return
        if (model.launchState(id)?.inFlight == true) return
        if (!conn.isHttps) { startLaunch(id); return }
        // An expired-but-trusted certificate warns first; everything else the launcher settles itself
        // (unknown → prompt via the launch events, auto-approve → trusted at once).
        val hostPort = conn.hostPort
        val expired = model.trustedCerts.list().firstOrNull { e ->
            e.status == com.gestalt.launcher.tls.CertTrustStatus.TRUSTED && e.isExpired() && ((hostPort != null && hostPort in e.hosts) || id in e.connectionIds)
        }
        if (expired != null) {
            val date = expired.notAfter?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() }
                ?.atZone(ZoneId.systemDefault())?.toLocalDate()?.format(DateTimeFormatter.ofPattern("MMM d, yyyy")) ?: "an earlier date"
            modal.confirm(
                "Certificate expired",
                "${hostPort ?: conn.serverUrl} is using a certificate that expired on $date. Ask the server owner to renew it, or launch anyway.",
                listOf(
                    ModalAction("Cancel", Controls.ButtonKind.Secondary) {},
                    ModalAction("Launch anyway", Controls.ButtonKind.Primary, isDefault = true) { startLaunch(id) },
                ),
            )
            return
        }
        startLaunch(id)
    }

    private fun startLaunch(id: String) {
        val conn = model.store.get(id) ?: return
        toast.show("Launching ${conn.name}", conn.serverUrl, ToastHost.Kind.Progress)
        model.launch(id)
    }

    override fun newConnection(folderId: String?) {
        guard(proceed = {
            val c = model.newConnection(folderId)
            sidebar.selectRow("c:${c.id}")
            renameConnection(c.id)
        })
    }

    override fun newFolder(parentId: String?) {
        val f = model.newFolder(parentId)
        sidebar.selectRow("f:${f.id}")
        sidebar.startRename("f:${f.id}")
    }

    override fun renameConnection(id: String) {
        if (model.store.get(id) == null) return
        sidebar.startRename("c:$id")
    }

    override fun renameFolder(id: String) {
        if (model.store.getFolder(id) == null) return
        sidebar.startRename("f:$id")
    }

    override fun deleteConnection(id: String) {
        val conn = model.store.get(id) ?: return
        modal.confirm(
            "Delete “${conn.name}”?",
            "This removes the saved connection from the launcher. It can’t be undone.",
            listOf(
                ModalAction("Cancel", Controls.ButtonKind.Secondary) {},
                ModalAction("Delete", Controls.ButtonKind.Danger) {
                    val next = nextAfter("c:$id")
                    model.deleteConnection(id)
                    next?.let { r -> if (r is TreeRow.ConnectionRow) { model.select(r.connection.id); sidebar.selectRow(r.key) } else sidebar.selectRow(r.key) }
                },
            ),
        )
    }

    override fun deleteFolder(id: String) {
        val folder = model.store.getFolder(id) ?: return
        val n = model.connectionsUnder(id).size
        val dest = folder.parentId?.let { model.store.getFolder(it)?.name } ?: "the top level"
        modal.confirm(
            "Delete folder “${folder.name}”?",
            if (n > 0) "Its $n connection${if (n == 1) "" else "s"} and any subfolders move to $dest." else "The folder is empty.",
            listOf(
                ModalAction("Cancel", Controls.ButtonKind.Secondary) {},
                ModalAction("Delete folder", Controls.ButtonKind.Danger) { model.deleteFolder(id) },
            ),
        )
    }

    /** The row after [key] (or before, at the end) — where the cursor goes after a delete. */
    private fun nextAfter(key: String): TreeRow? {
        val rows = model.rows.filter { it is TreeRow.ConnectionRow }
        val i = rows.indexOfFirst { it.key == key }
        if (i < 0) return null
        return rows.getOrNull(i + 1) ?: rows.getOrNull(i - 1)
    }

    override fun importConnections() {
        val chooser = FileChooser().apply {
            title = "Import connections"
            extensionFilters += FileChooser.ExtensionFilter("Connection files (*.json)", "*.json")
        }
        val file = chooser.showOpenDialog(stage) ?: return
        val result = runCatching { ConnectionImport.importFile(model.store, file.toPath()) }
        result.onSuccess { s ->
            model.refresh()
            toast.show("Imported ${s.added} connection${if (s.added == 1) "" else "s"}", listOfNotNull(if (s.merged > 0) "${s.merged} merged" else null, if (s.skipped > 0) "${s.skipped} skipped" else null).joinToString(" · ").ifEmpty { file.name }, ToastHost.Kind.Success)
        }.onFailure {
            toast.show("Couldn’t import", "${file.name} isn’t a launcher export", ToastHost.Kind.Error)
        }
    }

    override fun exportConnections() {
        val chooser = FileChooser().apply {
            title = "Export connections"
            initialFileName = "connections.json"
            extensionFilters += FileChooser.ExtensionFilter("Connection files (*.json)", "*.json")
        }
        val file = chooser.showSaveDialog(stage) ?: return
        runCatching { model.store.exportTo(file.toPath()) }
            .onSuccess { toast.show("Exported ${model.connections.size} connections", file.name, ToastHost.Kind.Success) }
            .onFailure { toast.show("Couldn’t export", it.message ?: it.javaClass.simpleName, ToastHost.Kind.Error) }
    }

    // --- DetailActions -----------------------------------------------------------------------------

    override fun launchActive() {
        val id = model.activeId.get() ?: return
        if (model.dirty.get()) guard(proceed = { launch(id) }) else launch(id)
    }

    override fun saveActive() {
        if (model.save()) toast.show("Saved", model.draft.get()?.name, ToastHost.Kind.Success, 1800)
    }

    override fun saveActiveAs() {
        val id = model.saveAs() ?: return
        sidebar.selectRow("c:$id")
        renameConnection(id)
    }

    override fun deleteActive() {
        model.activeId.get()?.let { deleteConnection(it) }
    }

    override fun newConnection() = newConnection(null)

    override fun openCertificates() = certificates.show(model.active)

    override fun changeIcon() {
        toast.show("Icon library", "Choosing icons arrives with the next patch", ToastHost.Kind.Info, 2400)
    }

    override fun toggleTheme() {
        model.dark.set(!model.dark.get())
    }

    // --- launch prompts ----------------------------------------------------------------------------

    private val launchPrompts: LaunchEvents = object : LaunchEvents {
        override fun onPhase(id: String, phase: LaunchPhase) = Unit

        override fun onRunning(id: String, instances: Int) {
            val conn = model.store.get(id) ?: return
            val java = when (conn.javaHome.mode) {
                com.gestalt.launcher.model.JavaHomeMode.Bundled -> "Java ${conn.javaHome.bundledVersion}"
                com.gestalt.launcher.model.JavaHomeMode.Custom -> "Custom Java"
                com.gestalt.launcher.model.JavaHomeMode.Default -> "System Java"
            }
            toast.show("Administrator started", listOfNotNull(java, conn.heapSize?.let { "$it heap" }).joinToString(" · "), ToastHost.Kind.Success)
        }

        override fun onExited(id: String, instances: Int) = Unit

        override fun onCancelled(id: String) {
            toast.dismiss()
        }

        override fun onNeedsServerTrust(id: String, leaf: X509Certificate, hostPort: String?) {
            toast.dismiss()
            val d = CertDetails.from(leaf)
            val short = d.fingerprintDisplay.split(":").take(6).joinToString(":") + "…"
            modal.confirm(
                "Trust this certificate?",
                "${hostPort ?: "The server"} presented a certificate the launcher hasn’t trusted yet. SHA-256 $short, issued by ${issuerName(d.issuer)}. Only trust it if you recognize this server.",
                listOf(
                    ModalAction("Reject", Controls.ButtonKind.Ghost) { model.rejectServerCertificate(id, leaf, hostPort); detail.refreshCertificates() },
                    ModalAction("Cancel", Controls.ButtonKind.Secondary) { detail.refreshCertificates() },
                    ModalAction("Trust and launch", Controls.ButtonKind.Primary, isDefault = true) { model.trustServerCertificate(id, leaf, hostPort); detail.refreshCertificates(); startLaunch(id) },
                ),
            )
        }

        override fun onServerRejected(id: String, leaf: X509Certificate) {
            toast.dismiss()
            modal.confirm(
                "Certificate rejected",
                "This server’s certificate was rejected earlier, so the launcher won’t connect to it. Review it under Certificates if that decision should change.",
                listOf(
                    ModalAction("Close", Controls.ButtonKind.Secondary) {},
                    ModalAction("Open Certificates", Controls.ButtonKind.Primary, isDefault = true) { openCertificates() },
                ),
            )
        }

        override fun onNeedsPublisherTrust(id: String, signer: X509Certificate, artifactName: String, reason: String) {
            toast.dismiss()
            val d = CertDetails.from(signer)
            val short = d.fingerprintDisplay.split(":").take(6).joinToString(":") + "…"
            modal.confirm(
                "Trust this publisher?",
                "The server’s client code is signed by ${subjectName(d.subject)} (${reason}). Its contents match the signature, but the launcher doesn’t know this publisher. SHA-256 $short, signed “$artifactName”. Trust it only if your Mirth server runs extensions from this publisher.",
                listOf(
                    ModalAction("Reject", Controls.ButtonKind.Ghost) { model.rejectPublisher(id, signer, artifactName); detail.refreshCertificates() },
                    ModalAction("Cancel", Controls.ButtonKind.Secondary) { detail.refreshCertificates() },
                    ModalAction("Trust and launch", Controls.ButtonKind.Primary, isDefault = true) { model.trustPublisher(id, signer, artifactName); detail.refreshCertificates(); startLaunch(id) },
                ),
            )
        }

        override fun onFailed(id: String, error: Throwable, diagnosis: Diagnosis) {
            toast.show(diagnosis.title, diagnosis.explanation, ToastHost.Kind.Error, 6000)
            modal.confirm(
                diagnosis.title,
                diagnosis.explanation + "\n\n" + diagnosis.nextStep,
                listOf(ModalAction("Close", Controls.ButtonKind.Secondary, isDefault = true) {}),
            )
        }
    }

    init {
        // After the prompts object exists (property initializers run in declaration order).
        model.launchListener = launchPrompts
    }

    private fun subjectName(dn: String): String = Regex("(?:^|,)\\s*CN=([^,]+)").find(dn)?.groupValues?.get(1)?.trim()
        ?: Regex("(?:^|,)\\s*O=([^,]+)").find(dn)?.groupValues?.get(1)?.trim() ?: dn

    private fun issuerName(dn: String): String = subjectName(dn)

    private var shutDown = false

    /** Save the window geometry, flush edits and stop background work; once. */
    fun shutdown() {
        if (shutDown) return
        shutDown = true
        model.prefs.batch {
            windowMaximized = stage.isMaximized
            if (!stage.isMaximized) {
                windowWidth = stage.width; windowHeight = stage.height
                windowX = stage.x; windowY = stage.y
            }
            sidebarWidth = sidebar.prefWidth
        }
        model.shutdown()
    }
}
