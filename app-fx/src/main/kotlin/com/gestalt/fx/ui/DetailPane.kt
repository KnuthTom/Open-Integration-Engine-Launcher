/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.fx.ui

import com.gestalt.fx.model.LaunchState
import com.gestalt.fx.model.LauncherModel
import com.gestalt.fx.model.TreeRows
import com.gestalt.launcher.icon.CustomIconStore
import com.gestalt.launcher.launch.LaunchPhase
import com.gestalt.launcher.model.Connection
import com.gestalt.launcher.model.JavaHomeConfig
import com.gestalt.launcher.model.JavaHomeMode
import com.gestalt.launcher.tls.CertFingerprint
import com.gestalt.launcher.tls.CertTrustStatus
import com.gestalt.launcher.tls.TrustedCert
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.MenuButton
import javafx.scene.control.ScrollPane
import javafx.scene.control.TextField
import javafx.scene.layout.BorderPane
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.stage.DirectoryChooser
import javafx.stage.FileChooser
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** What the detail pane asks of the window. */
interface DetailActions {
    fun launchActive()
    fun saveActive()
    fun saveActiveAs()
    fun deleteActive()
    fun newConnection()
    fun openCertificates()
    fun changeIcon()
    fun toggleTheme()
    /** Auto-approve is a trust switch, not a form field: it applies to the saved connection at once. */
    fun setAutoApprove(on: Boolean)
}

/**
 * The right pane: the header (icon as a launch button, the name, folder · last launched, Launch), the
 * four cards (Connection, Java runtime, Certificates, TLS / SSL) in two columns when there is room and
 * one column below 700 px, and the footer (close-after-launch, unsaved marker, Delete / Save as… /
 * Save). Every field edits [LauncherModel.draft]; the pane re-reads the draft after each change.
 */
class DetailPane(private val model: LauncherModel, private val host: DetailActions, private val icons: CustomIconStore?) : BorderPane() {

    // header
    private val iconButton = Button().apply { styleClass += "header-icon-button" }
    private val nameField = TextField().apply { styleClass += "name-field"; promptText = "Connection name" }
    private val pathLabel = Label()
    private val lastLabel = Label()
    private val launchButton = Controls.button("Launch", Controls.ButtonKind.Primary, Icons.glyph("play", 14.0, "-accent-contrast", 2.0)) { host.launchActive() }
        .apply { styleClass += "launch-button" }
    private val themeButton = Controls.iconButton("moon", "Switch to dark theme") { host.toggleTheme() }.apply {
        minWidth = 32.0; prefWidth = 32.0; maxWidth = 32.0; minHeight = 32.0; prefHeight = 32.0; maxHeight = 32.0
    }
    private val header = HBox().apply { styleClass += "detail-header" }

    // connection card
    private val addressField = TextField().apply { styleClass += "mono-field"; promptText = "https://host:8443" }
    private val tunnelField = TextField().apply { styleClass += "mono-field"; promptText = "None — or a batch file / command that opens the address's port" }
    private val browseTunnel = Controls.button("Browse…") { browseFileInto(tunnelField) { path -> update { it.copy(tunnelCommand = path) } } }
    private val tunnelNote = Label("Runs before each launch when the port isn't already listening (e.g. a batch file that starts an SSM port-forward); closed when the Administrator exits.").apply { styleClass += "field-note"; isWrapText = true; minWidth = 0.0 }
    private val folderButton: MenuButton = Controls.menuButton("Top level", Icons.glyph("folder", 14.0)) { folderMenuItems() }

    // java card
    private val javaButton: MenuButton = Controls.menuButton("Bundled Java 17") { javaMenuItems() }
    private val javaNote = Label("Bundled Java 8 for Mirth / OIE up to 4.6, Java 17 from 4.7").apply { styleClass += "field-note" }
    private val customJavaField = TextField().apply { styleClass += "mono-field"; promptText = "Path to a JDK or JRE" }
    private val browseJava = Controls.button("Browse…") { browseInto(customJavaField) { path -> update { it.copy(javaHome = it.javaHome.copy(mode = JavaHomeMode.Custom, customPath = path)) } } }
    private val javaFxField = TextField().apply { styleClass += "mono-field"; promptText = "Bundled with the launcher" }
    private val browseFx = Controls.button("Browse…") { browseInto(javaFxField) { path -> update { it.copy(javaFxHome = path) } } }
    private val heapField = TextField().apply { styleClass += "mono-field"; minWidth = 84.0; prefWidth = 84.0; maxWidth = 84.0 }
    private val heapSeg = Controls.Segmented(listOf("512m" to "512m", "1g" to "1g", "2g" to "2g", "4g" to "4g"), mono = true) { v -> update { it.copy(heapSize = v) } }
    private val heapError = Label("Use a size like 1024m or 2g").apply { styleClass += "field-error"; isVisible = false; isManaged = false }
    private val consoleSwitch = Controls.Switch { on -> update { it.copy(showJavaConsole = on) } }
    private val scaleSeg = Controls.Segmented<String?>(listOf(null to "System", "1.0" to "100%", "1.25" to "125%", "1.5" to "150%", "2.0" to "200%")) { v -> update { it.copy(uiScale = v) } }

    // certificates card
    private val reviewBadge = Label().apply { styleClass += "badge-warn"; isVisible = false; isManaged = false }
    private val reviewButton = Controls.button("Review", Controls.ButtonKind.Outline, Icons.glyph("shield-check", 14.0)) { host.openCertificates() }
    private val certIcon = StackPane()
    private val certLabel = Label().apply { styleClass += "strong" }
    private val certDetail = Label().apply { styleClass += "small"; styleClass += "muted"; minWidth = 0.0 }
    private val autoApprove = Controls.CheckRow("Auto-approve new certificates") { on -> host.setAutoApprove(on) }

    // tls card
    private val tlsBody = VBox()
    private val tlsChevron = StackPane()
    private val tlsSummary = Label().apply { styleClass += "card-summary"; minWidth = 0.0 }
    private var tlsOpen: Boolean? = null
    private val sslSeg = Controls.Segmented(listOf(false to "Default", true to "Custom")) { custom -> update { it.copy(sslProtocols = it.sslProtocols.copy(custom = custom, value = if (custom && it.sslProtocols.value.isBlank()) SSL_DEFAULT else it.sslProtocols.value)) } }
    private val sslField = TextField().apply { styleClass += "mono-field" }
    private val cipherSeg = Controls.Segmented(listOf(false to "Default", true to "Custom")) { custom -> update { it.copy(sslCipherSuites = it.sslCipherSuites.copy(custom = custom, value = if (custom && it.sslCipherSuites.value.isBlank()) CIPHER_DEFAULT else it.sslCipherSuites.value)) } }
    private val cipherField = TextField().apply { styleClass += "mono-field" }
    private val dhSwitch = Controls.Switch { on -> update { it.copy(legacyTlsCompatibility = on) } }

    // footer
    private val closeAfter = Controls.CheckRow("Close this window after launching") { on -> model.closeAfterLaunch.set(on) }
    private val unsaved = HBox(Region().apply { styleClass += "dot" }, Label("Unsaved changes")).apply { styleClass += "unsaved"; isVisible = false; isManaged = false }
    private val deleteButton = Controls.button("Delete", Controls.ButtonKind.Ghost) { host.deleteActive() }
    private val saveAsButton = Controls.button("Save as…") { host.saveActiveAs() }
    private val saveButton = Controls.button("Save") { host.saveActive() }.apply { isDisable = true }
    private val footer = HBox().apply { styleClass += "footer" }

    private val body = GridPane().apply { styleClass += "detail-body" }
    private val scroll = ScrollPane().apply { styleClass += "detail-scroll"; isFitToWidth = true; hbarPolicy = ScrollPane.ScrollBarPolicy.NEVER }
    private val empty = VBox().apply { styleClass += "detail-empty" }
    private val content = BorderPane()

    private val cards: List<VBox>
    private var twoColumns = true
    private var updating = false

    init {
        // --- header ---
        val iconHolder = StackPane().apply {
            children += iconButton
            val pencil = Button().apply { styleClass += "header-pencil"; graphic = Icons.pencilBadge(13.0, "-text"); tooltip = javafx.scene.control.Tooltip("Change icon"); setOnAction { host.changeIcon() } }
            children += pencil
            StackPane.setAlignment(pencil, Pos.BOTTOM_RIGHT)
            StackPane.setMargin(pencil, Insets(0.0, -7.0, -7.0, 0.0))
            minWidth = 40.0; prefWidth = 40.0; maxWidth = 40.0; minHeight = 40.0; prefHeight = 40.0; maxHeight = 40.0
        }
        iconButton.setOnAction { host.launchActive() }
        val sub = HBox(Icons.glyph("folder", 12.0), pathLabel, Label("·"), lastLabel).apply { styleClass += "header-sub" }
        val titles = VBox(4.0, nameField, sub).apply { minWidth = 0.0; HBox.setHgrow(this, Priority.ALWAYS) }
        nameField.textProperty().addListener { _, _, v -> if (!updating) update { it.copy(name = v.orEmpty()) } }
        header.children.addAll(iconHolder, titles, themeButton, launchButton)
        header.alignment = Pos.CENTER_LEFT

        // --- cards ---
        val tunnelRow = VBox(
            4.0,
            HBox(8.0, tunnelField, browseTunnel).apply { alignment = Pos.CENTER_LEFT; HBox.setHgrow(tunnelField, Priority.ALWAYS); tunnelField.minWidth = 0.0 },
            tunnelNote,
        ).apply { minWidth = 0.0 }
        val connectionCard = Controls.card("Connection", Controls.fieldGrid().apply {
            row(0, "Address", addressField)
            row(1, "Folder", HBox(folderButton).apply { alignment = Pos.CENTER_LEFT })
            row(2, "Tunnel", tunnelRow)
        })
        addressField.textProperty().addListener { _, _, v -> if (!updating) update { it.copy(serverUrl = v.orEmpty()) } }
        tunnelField.textProperty().addListener { _, _, v -> if (!updating) update { it.copy(tunnelCommand = v?.trim()?.takeIf { s -> s.isNotEmpty() }) } }

        val javaRow = HBox(8.0, javaButton, javaNote, customJavaField, browseJava).apply { alignment = Pos.CENTER_LEFT; HBox.setHgrow(customJavaField, Priority.ALWAYS); customJavaField.minWidth = 0.0 }
        val fxRow = HBox(8.0, javaFxField, browseFx).apply { alignment = Pos.CENTER_LEFT; HBox.setHgrow(javaFxField, Priority.ALWAYS); javaFxField.minWidth = 0.0 }
        val heapRow = VBox(4.0, HBox(8.0, heapField, heapSeg).apply { alignment = Pos.CENTER_LEFT }, heapError)
        val consoleRow = HBox(10.0, consoleSwitch, Label("Open a console window alongside the Administrator").apply { styleClass += "field-note"; minWidth = 0.0 }).apply { alignment = Pos.CENTER_LEFT }
        val scaleRow = HBox(8.0, scaleSeg, Label("100% is the crisp, unscaled look of Java 8").apply { styleClass += "field-note"; minWidth = 0.0 }).apply { alignment = Pos.CENTER_LEFT }
        val javaCard = Controls.card("Java runtime", Controls.fieldGrid().apply {
            row(0, "Java home", javaRow)
            row(1, "JavaFX home", fxRow)
            row(2, "Max heap", heapRow)
            row(3, "Java console", consoleRow)
            row(4, "Display scale", scaleRow)
        })
        customJavaField.textProperty().addListener { _, _, v -> if (!updating) update { it.copy(javaHome = it.javaHome.copy(customPath = v)) } }
        javaFxField.textProperty().addListener { _, _, v -> if (!updating) update { it.copy(javaFxHome = v?.takeIf { s -> s.isNotBlank() }) } }
        heapField.textProperty().addListener { _, _, v -> if (!updating) update { it.copy(heapSize = v?.trim()?.takeIf { s -> s.isNotEmpty() }) } }

        val certHead = HBox(12.0, Label("Certificates").apply { styleClass += "card-title" }, Controls.spacer(), reviewButton).apply { styleClass += "card-head" }
        reviewButton.graphic = HBox(8.0, Icons.glyph("shield-check", 14.0), Label("Review"), reviewBadge).apply { alignment = Pos.CENTER_LEFT }
        reviewButton.text = ""
        val certRow = HBox(8.0, Label("This server").apply { styleClass += "muted" }, certIcon, certLabel, certDetail).apply { alignment = Pos.CENTER_LEFT; padding = Insets(12.0, 0.0, 0.0, 0.0); HBox.setHgrow(certDetail, Priority.ALWAYS) }
        val autoRow = HBox(10.0, autoApprove, Label("Skips the trust prompt").apply { styleClass += "field-note"; minWidth = 0.0 }).apply { alignment = Pos.CENTER_LEFT; padding = Insets(10.0, 0.0, 0.0, 0.0) }
        val certCard = Controls.card("Certificates", VBox(certRow, autoRow), head = certHead)

        val tlsHead = HBox(12.0, Label("TLS / SSL").apply { styleClass += "card-title" }, tlsSummary, tlsChevron).apply {
            styleClass.addAll("card-head", "clickable")
            HBox.setHgrow(tlsSummary, Priority.ALWAYS)
            tlsSummary.maxWidth = Double.MAX_VALUE
            tlsSummary.alignment = Pos.CENTER_RIGHT
            setOnMouseClicked { tlsOpen = !(tlsOpen ?: twoColumns); refreshTls() }
        }
        val sslRow = HBox(8.0, sslSeg, sslField).apply { alignment = Pos.CENTER_LEFT; HBox.setHgrow(sslField, Priority.ALWAYS); sslField.minWidth = 0.0 }
        val cipherRow = HBox(8.0, cipherSeg, cipherField).apply { alignment = Pos.CENTER_LEFT; HBox.setHgrow(cipherField, Priority.ALWAYS); cipherField.minWidth = 0.0 }
        val dhRow = HBox(10.0, dhSwitch, Label("Only for older servers that need legacy Diffie-Hellman key sizes").apply { styleClass += "field-note"; minWidth = 0.0 }).apply { alignment = Pos.CENTER_LEFT }
        tlsBody.children += Controls.fieldGrid().apply {
            row(0, "SSL protocols", sslRow)
            row(1, "Cipher suites", cipherRow)
            row(2, "Legacy DH", dhRow)
        }
        sslField.textProperty().addListener { _, _, v -> if (!updating) update { it.copy(sslProtocols = it.sslProtocols.copy(value = v.orEmpty())) } }
        cipherField.textProperty().addListener { _, _, v -> if (!updating) update { it.copy(sslCipherSuites = it.sslCipherSuites.copy(value = v.orEmpty())) } }
        val tlsCard = Controls.card("TLS / SSL", tlsBody, head = tlsHead)

        cards = listOf(connectionCard, javaCard, certCard, tlsCard)
        cards.forEach { GridPane.setHgrow(it, Priority.ALWAYS); it.maxWidth = Double.MAX_VALUE; GridPane.setValignment(it, javafx.geometry.VPos.TOP) }
        layoutCards(true)
        scroll.content = body
        body.widthProperty().addListener { _, _, w -> val two = w.toDouble() >= 700.0; if (two != twoColumns) { layoutCards(two); refreshTls() } }

        // --- footer ---
        closeAfter.on = model.closeAfterLaunch.get()
        footer.children.addAll(closeAfter, Controls.spacer(), unsaved, deleteButton, saveAsButton, saveButton)

        // --- empty state ---
        empty.children += Label("No connection selected")
        empty.children += Controls.button("New connection") { host.newConnection() }

        content.top = header
        content.center = scroll
        content.bottom = footer
        center = empty

        model.draft.addListener { _, _, d -> render(d) }
        model.dirty.addListener { _, _, dirty -> unsaved.isVisible = dirty; unsaved.isManaged = dirty; refreshSaveEnabled() }
        model.launches.addListener(javafx.collections.MapChangeListener { refreshLaunchButton() })
        model.activeId.addListener { _, _, _ -> refreshLaunchButton() }
        model.closeAfterLaunch.addListener { _, _, v -> closeAfter.on = v }
        model.folders.addListener(javafx.collections.ListChangeListener { model.draft.get()?.let { d -> pathLabel.text = model.pathOf(d.folderId); folderButton.setCaption(model.pathOf(d.folderId)) } })
        widthProperty().addListener { _, _, w -> val narrow = w.toDouble() < 600; setNarrow(narrow) }
        render(model.draft.get())
    }

    /** Called by the window when the trusted-certificate store changes (the launch prompts, the dialog). */
    fun refreshCertificates() {
        model.draft.get()?.let { renderCertificates(it) }
    }

    fun setThemeDark(dark: Boolean) {
        Controls.setGlyph(themeButton, if (dark) "sun" else "moon")
        themeButton.tooltip = javafx.scene.control.Tooltip(if (dark) "Switch to light theme" else "Switch to dark theme")
    }

    // --- rendering --------------------------------------------------------------------------------

    private fun update(transform: (Connection) -> Connection) {
        if (updating) return
        model.updateDraft(transform)
    }

    private fun render(d: Connection?) {
        if (d == null) {
            center = empty
            return
        }
        center = content
        updating = true
        try {
            iconButton.graphic = Controls.connectionIcon(d.icon, 24.0, icons)
            if (nameField.text != d.name) nameField.text = d.name
            pathLabel.text = model.pathOf(d.folderId)
            lastLabel.text = TreeRows.launchedLabel(d.lastLaunchedAt)
            if (addressField.text != d.serverUrl) addressField.text = d.serverUrl
            val tunnel = d.tunnelCommand.orEmpty()
            if (tunnelField.text.orEmpty().trim() != tunnel) tunnelField.text = tunnel
            folderButton.setCaption(model.pathOf(d.folderId))
            // Menus are rebuilt when they open; seed them here too so the first click never finds them empty.
            folderButton.items.setAll(folderMenuItems())
            javaButton.items.setAll(javaMenuItems())

            val jh = d.javaHome
            javaButton.setCaption(
                when (jh.mode) {
                    JavaHomeMode.Default -> "Automatic"
                    JavaHomeMode.Bundled -> "Bundled Java ${jh.bundledVersion}"
                    JavaHomeMode.Custom -> "Custom path"
                },
            )
            val custom = jh.mode == JavaHomeMode.Custom
            javaNote.isVisible = jh.mode == JavaHomeMode.Default; javaNote.isManaged = javaNote.isVisible
            customJavaField.isVisible = custom; customJavaField.isManaged = custom
            browseJava.isVisible = custom; browseJava.isManaged = custom
            if (customJavaField.text != (jh.customPath ?: "")) customJavaField.text = jh.customPath ?: ""
            if (javaFxField.text != (d.javaFxHome ?: "")) javaFxField.text = d.javaFxHome ?: ""
            val heap = d.heapSize ?: ""
            if (heapField.text != heap) heapField.text = heap
            heapSeg.value = heap.lowercase().takeIf { it in listOf("512m", "1g", "2g", "4g") }
            val heapOk = heap.isEmpty() || HEAP_PATTERN.matches(heap)
            heapError.isVisible = !heapOk; heapError.isManaged = !heapOk
            consoleSwitch.on = d.showJavaConsole
            scaleSeg.value = d.uiScale?.trim()?.takeIf { it.isNotEmpty() }

            renderCertificates(d)
            autoApprove.on = d.autoApproveCertificates

            sslSeg.value = d.sslProtocols.custom
            sslField.isDisable = !d.sslProtocols.custom
            val sslText = if (d.sslProtocols.custom) d.sslProtocols.value else SSL_DEFAULT
            if (sslField.text != sslText) sslField.text = sslText
            cipherSeg.value = d.sslCipherSuites.custom
            cipherField.isDisable = !d.sslCipherSuites.custom
            val cipherText = if (d.sslCipherSuites.custom) d.sslCipherSuites.value else CIPHER_DEFAULT
            if (cipherField.text != cipherText) cipherField.text = cipherText
            dhSwitch.on = d.legacyTlsCompatibility
            refreshTls()
            refreshSaveEnabled()
            refreshLaunchButton()
        } finally {
            updating = false
        }
    }

    private fun renderCertificates(d: Connection) {
        val pending = model.trustedCerts.pending().size + model.trustedSigners.pending().size
        reviewBadge.text = pending.toString()
        reviewBadge.isVisible = pending > 0; reviewBadge.isManaged = pending > 0

        val hostPort = d.hostPort
        val entry: TrustedCert? = if (d.isHttps && hostPort != null) model.trustedCerts.list().firstOrNull { hostPort in it.hosts && it.status != CertTrustStatus.REJECTED }
            ?: model.trustedCerts.list().firstOrNull { d.id in it.connectionIds && it.status != CertTrustStatus.REJECTED } else null
        val (glyph, color, label, detail) = when {
            !d.isHttps -> Quad("shield-off", "-text-muted", "Not used", "This address isn't HTTPS")
            entry == null -> Quad("shield", "-text-muted", "Not seen yet", if (d.autoApproveCertificates) "Trusted automatically on first launch" else "You'll be asked to trust it on first launch")
            entry.status == CertTrustStatus.PENDING -> Quad("shield-alert", "-warn", "Needs review", fingerprintShort(entry.sha256) + expiryText(entry))
            entry.isExpired() -> Quad("shield-x", "-danger", "Expired", fingerprintShort(entry.sha256) + expiryText(entry))
            else -> Quad("shield-check", "-ok", "Trusted", fingerprintShort(entry.sha256) + expiryText(entry))
        }
        certIcon.children.setAll(Icons.glyph(glyph, 16.0, color))
        certLabel.text = label
        certDetail.text = detail
    }

    private data class Quad(val glyph: String, val color: String, val label: String, val detail: String)

    private fun expiryText(entry: TrustedCert): String {
        val notAfter = entry.notAfter?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return ""
        val date = notAfter.atZone(ZoneId.systemDefault()).toLocalDate().format(DateTimeFormatter.ofPattern("MMM d, yyyy"))
        return if (entry.isExpired()) " · expired $date" else " · expires $date"
    }

    private fun fingerprintShort(sha256: String): String =
        CertFingerprint.normalize(sha256).uppercase().chunked(2).take(6).joinToString(":") + "…"

    private fun refreshTls() {
        val d = model.draft.get() ?: return
        val open = tlsOpen ?: twoColumns
        tlsBody.isVisible = open; tlsBody.isManaged = open
        tlsChevron.children.setAll(Icons.glyph(if (open) "chevron-up" else "chevron-down", 16.0))
        val parts = ArrayList<String>()
        if (d.sslProtocols.custom) parts += "custom protocols"
        if (d.sslCipherSuites.custom) parts += "custom ciphers"
        if (d.legacyTlsCompatibility) parts += "legacy DH"
        tlsSummary.text = if (open) "" else (if (parts.isEmpty()) "Defaults" else parts.joinToString(" · "))
    }

    private fun refreshSaveEnabled() {
        val d = model.draft.get()
        val heapOk = d?.heapSize.isNullOrEmpty() || HEAP_PATTERN.matches(d?.heapSize.orEmpty())
        saveButton.isDisable = !(model.dirty.get() && heapOk && d != null && d.name.isNotBlank())
    }

    private fun refreshLaunchButton() {
        val id = model.activeId.get()
        val state: LaunchState? = id?.let { model.launchState(it) }
        val inFlight = state?.inFlight == true
        launchButton.isDisable = inFlight
        val text = when {
            inFlight -> when (state?.phase) { LaunchPhase.Tunneling -> "Opening tunnel…"; LaunchPhase.Verifying -> "Verifying…"; LaunchPhase.Launching -> "Starting…"; else -> "Launching…" }
            state?.phase == LaunchPhase.Running && state.instances > 1 -> "Launch (${state.instances} running)"
            else -> "Launch"
        }
        launchButton.text = text
    }

    private fun layoutCards(two: Boolean) {
        twoColumns = two
        body.children.clear()
        body.columnConstraints.clear()
        if (two) {
            body.columnConstraints.addAll(
                javafx.scene.layout.ColumnConstraints().apply { percentWidth = 50.0; hgrow = Priority.ALWAYS },
                javafx.scene.layout.ColumnConstraints().apply { percentWidth = 50.0; hgrow = Priority.ALWAYS },
            )
            body.add(cards[0], 0, 0); body.add(cards[1], 0, 1)
            body.add(cards[2], 1, 0); body.add(cards[3], 1, 1)
        } else {
            body.columnConstraints.add(javafx.scene.layout.ColumnConstraints().apply { percentWidth = 100.0; hgrow = Priority.ALWAYS })
            cards.forEachIndexed { i, c -> body.add(c, 0, i) }
        }
    }

    private fun setNarrow(narrow: Boolean) {
        for (n in listOf<Region>(header, body, footer)) if (narrow) { if ("narrow" !in n.styleClass) n.styleClass += "narrow" } else n.styleClass.remove("narrow")
        closeAfter.label.text = if (narrow) "Close after launch" else "Close this window after launching"
    }

    // --- menus ------------------------------------------------------------------------------------

    private fun folderMenuItems(): List<javafx.scene.control.MenuItem> {
        val d = model.draft.get() ?: return emptyList()
        val items = ArrayList<javafx.scene.control.MenuItem>()
        items += Controls.menuItem("Top level", checked = d.folderId == null) { update { it.copy(folderId = null) } }
        fun walk(parentId: String?, depth: Int) {
            model.folders.filter { it.parentId == parentId }.sortedBy { it.name.lowercase() }.forEach { f ->
                items += Controls.menuItem(f.name, checked = d.folderId == f.id, indent = depth + 1) { update { it.copy(folderId = f.id) } }
                walk(f.id, depth + 1)
            }
        }
        walk(null, 0)
        return items
    }

    private fun javaMenuItems(): List<javafx.scene.control.MenuItem> {
        val d = model.draft.get() ?: return emptyList()
        val jh = d.javaHome
        return listOf(
            Controls.menuItem("Automatic", checked = jh.mode == JavaHomeMode.Default, hint = "by server version") { update { it.copy(javaHome = it.javaHome.copy(mode = JavaHomeMode.Default)) } },
            Controls.menuItem("Bundled Java 8", checked = jh.mode == JavaHomeMode.Bundled && jh.bundledVersion == "8", hint = "Zulu FX 8") { update { it.copy(javaHome = JavaHomeConfig(JavaHomeMode.Bundled, "8", it.javaHome.customPath)) } },
            Controls.menuItem("Bundled Java 17", checked = jh.mode == JavaHomeMode.Bundled && jh.bundledVersion == "17", hint = "Zulu FX 17") { update { it.copy(javaHome = JavaHomeConfig(JavaHomeMode.Bundled, "17", it.javaHome.customPath)) } },
            Controls.menuSeparator(),
            Controls.menuItem("Custom path…", checked = jh.mode == JavaHomeMode.Custom) { update { it.copy(javaHome = it.javaHome.copy(mode = JavaHomeMode.Custom)) } },
        )
    }

    /** Pick a script or program for the tunnel command (the current text, when it is a file, seeds the chooser). */
    private fun browseFileInto(field: TextField, apply: (String) -> Unit) {
        val chooser = FileChooser().apply {
            title = "Choose the tunnel script"
            extensionFilters.setAll(
                FileChooser.ExtensionFilter("Scripts and programs", "*.bat", "*.cmd", "*.ps1", "*.sh", "*.exe"),
                FileChooser.ExtensionFilter("All files", "*.*"),
            )
            field.text?.trim()?.trim('"')?.takeIf { it.isNotBlank() }?.let { File(it) }?.let { f ->
                if (f.isFile) { initialDirectory = f.parentFile; initialFileName = f.name } else if (f.isDirectory) initialDirectory = f
            }
        }
        val file = chooser.showOpenDialog(scene?.window) ?: return
        apply(file.absolutePath)
    }

    private fun browseInto(field: TextField, apply: (String) -> Unit) {
        val chooser = DirectoryChooser().apply {
            title = "Choose a folder"
            field.text?.takeIf { it.isNotBlank() }?.let { File(it) }?.takeIf { it.isDirectory }?.let { initialDirectory = it }
        }
        val dir = chooser.showDialog(scene?.window) ?: return
        apply(dir.absolutePath)
    }

    companion object {
        private val HEAP_PATTERN = Regex("^\\d+[kKmMgG]?$")
        const val SSL_DEFAULT = "TLSv1.3,TLSv1.2"
        const val CIPHER_DEFAULT = "TLS_CHACHA20_POLY1305_SHA256,TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256,TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256,TLS_AES_256_GCM_SHA384,TLS_AES_128_GCM_SHA256"
    }
}
