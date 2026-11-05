/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.fx.ui

import com.gestalt.launcher.icon.CustomIconStore
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
import javafx.scene.control.Tooltip
import javafx.scene.input.TransferMode
import javafx.scene.layout.FlowPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.stage.FileChooser
import javafx.stage.Window
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

/**
 * "Connection icon": drop or browse for images (PNG, JPG, GIF or BMP, several at once — each scaled to
 * at most 128 px and stored as PNG through the sanitizing icon store), a preview of the choice as the
 * sidebar shows it, the built-in library in its four categories plus "Yours" (imported images), Reset
 * to default, "Use for new connections" (the chosen icon becomes every new connection's), Cancel,
 * Apply. Apply hands the chosen key back; the window applies it to the saved connection at once.
 */
class IconDialog(
    private val modal: ModalHost,
    private val icons: CustomIconStore?,
    private val window: () -> Window?,
    /** The icon new connections start with, and how to change it (the launcher's preference). */
    private val defaultIcon: () -> String,
    private val setDefaultIcon: (String) -> Unit,
) {

    private var pending: String = IconLibrary.DEFAULT
    private var tab: String = "Healthcare"
    private var name: String = ""
    private var onApply: (String) -> Unit = {}

    private val preview = HBox(8.0).apply { styleClass += "icon-preview"; alignment = Pos.CENTER_LEFT }
    private val tabsHolder = HBox()
    private val tiles = FlowPane(6.0, 6.0)
    private val error = Label().apply { styleClass += "field-error"; isWrapText = true; isVisible = false; isManaged = false }
    private val defaultButton = Controls.button("Use for new connections", Controls.ButtonKind.Ghost) { setDefaultIcon(pending); render() }
    private val defaultMark = HBox(6.0, Icons.glyph("check", 13.0, "-ok", 2.5), Label("Default for new connections").apply { styleClass += "field-note" }).apply { alignment = Pos.CENTER_LEFT; padding = Insets(0.0, 0.0, 0.0, 4.0) }
    /** Holds [defaultButton], or [defaultMark] when the choice already is the default. */
    private val defaultHolder = StackPane().apply { alignment = Pos.CENTER_LEFT }

    fun show(connectionName: String, currentKey: String?, onApply: (String) -> Unit) {
        this.name = connectionName
        this.onApply = onApply
        pending = currentKey?.takeIf { it.isNotBlank() } ?: defaultIcon()
        tab = tabFor(pending)
        error.isVisible = false; error.isManaged = false

        val zone = VBox().apply {
            styleClass += "drop-zone"
            val row = HBox(14.0).apply {
                alignment = Pos.CENTER_LEFT
                children += StackPane(Icons.glyph("image-up", 20.0)).apply { styleClass += "drop-zone-icon" }
                children += VBox(2.0).apply {
                    minWidth = 0.0
                    HBox.setHgrow(this, Priority.ALWAYS)
                    children += HBox(4.0, Label("Drop images or").apply { styleClass += "strong" }, Label("browse…").apply { styleClass += "link" })
                    children += Label("PNG, JPG, GIF or BMP, one or several. Square images work best — scaled to 16 and 32 px.").apply { styleClass += "field-note"; isWrapText = true }
                    children += error
                }
            }
            children += row
            setOnMouseClicked { browse() }
            setOnDragOver { e -> if (e.dragboard.hasFiles()) { e.acceptTransferModes(TransferMode.COPY); if ("over" !in styleClass) styleClass += "over" }; e.consume() }
            setOnDragExited { styleClass.remove("over") }
            setOnDragDropped { e ->
                val files = e.dragboard.files.orEmpty().filter { it.isFile }
                styleClass.remove("over")
                e.isDropCompleted = files.isNotEmpty()
                e.consume()
                if (files.isNotEmpty()) importFiles(files)
            }
        }

        val tilesScroll = ScrollPane(tiles).apply {
            styleClass += "detail-scroll"
            isFitToWidth = true
            hbarPolicy = ScrollPane.ScrollBarPolicy.NEVER
            prefViewportHeight = 84.0
            minHeight = 84.0
            maxHeight = 84.0
        }
        tiles.padding = Insets(2.0)

        val card = VBox().apply {
            styleClass += "modal-card"
            children += HBox(8.0).apply {
                alignment = Pos.CENTER_LEFT
                children += Label("Connection icon").apply { styleClass += "card-title" }
                children += Controls.spacer()
                children += Controls.iconButton("x", "Close", 16.0) { modal.close() }.apply { minWidth = 28.0; prefWidth = 28.0; maxWidth = 28.0; minHeight = 28.0; prefHeight = 28.0; maxHeight = 28.0 }
            }
            children += zone.apply { VBox.setMargin(this, Insets(14.0, 0.0, 0.0, 0.0)) }
            children += preview.apply { VBox.setMargin(this, Insets(14.0, 0.0, 0.0, 0.0)) }
            children += tabsHolder.apply { VBox.setMargin(this, Insets(16.0, 0.0, 0.0, 0.0)) }
            children += tilesScroll.apply { VBox.setMargin(this, Insets(10.0, 0.0, 0.0, 0.0)) }
            children += HBox(8.0).apply {
                alignment = Pos.CENTER_LEFT
                padding = Insets(20.0, 0.0, 0.0, 0.0)
                children += Controls.button("Reset to default", Controls.ButtonKind.Ghost) { pending = defaultIcon(); tab = tabFor(pending); render() }
                children += defaultHolder
                children += Controls.spacer()
                children += Controls.button("Cancel", Controls.ButtonKind.Secondary) { modal.close() }
                children += Controls.button("Apply", Controls.ButtonKind.Primary) { val k = pending; modal.close(); this@IconDialog.onApply(k) }
            }
        }
        render()
        modal.show(card, 480.0, escape = { modal.close() }, enter = { val k = pending; modal.close(); this.onApply(k) })
    }

    /** The tab that holds [key]: "Yours" for an imported image, else its library category (Healthcare when unknown). */
    private fun tabFor(key: String): String = when {
        IconLibrary.isCustom(key) -> "Yours"
        else -> IconLibrary.categories.firstOrNull { (_, list) -> list.any { it.name == IconLibrary.glyphFor(key).name } }?.first ?: "Healthcare"
    }

    private fun render() {
        // preview — the icon as the sidebar and header draw it, in the launcher's own colours
        preview.children.setAll(Controls.connectionIcon(pending, 16.0, icons), Label(name).apply { styleClass += "strong"; minWidth = 0.0 })
        // "Use for new connections" — or, when the choice already is the default, just the fact
        defaultHolder.children.setAll(if (pending == defaultIcon()) defaultMark else defaultButton)
        // tabs
        val tabNames = listOf("Yours") + IconLibrary.categories.map { it.first }
        val seg = Controls.Segmented(tabNames.map { it to it }) { t -> tab = t; render() }
        seg.value = tab
        tabsHolder.children.setAll(seg)
        // tiles
        tiles.children.clear()
        if (tab == "Yours") {
            val custom = icons?.list().orEmpty()
            if (custom.isEmpty()) {
                tiles.children += Label("No imported images yet — drop some above.").apply { styleClass += "field-note"; padding = Insets(8.0, 4.0, 0.0, 4.0) }
            }
            for (icon in custom) tiles.children += tile(icon.key, icon.label.ifBlank { icon.sourceName }, Controls.connectionIcon(icon.key, 20.0, icons))
        } else {
            val list = IconLibrary.categories.firstOrNull { it.first == tab }?.second.orEmpty()
            for (g in list) tiles.children += tile(g.name, g.label, Icons.glyph(g.name, 18.0, g.colorVar))
        }
    }

    private fun tile(key: String, label: String, node: Node): Button = Button().apply {
        styleClass += "icon-tile"
        if (key == pending) styleClass += "on"
        graphic = node
        tooltip = Tooltip(label)
        isFocusTraversable = false
        setOnAction { pending = key; render() }
    }

    private fun browse() {
        val chooser = FileChooser().apply {
            title = "Choose images"
            extensionFilters += FileChooser.ExtensionFilter("Images (PNG, JPG, GIF, BMP)", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.bmp")
        }
        val files = chooser.showOpenMultipleDialog(window()) ?: return
        if (files.isNotEmpty()) importFiles(files)
    }

    /**
     * Import each file: decode with ImageIO (which proves it is an image), scale to at most 128 px, and
     * hand the PNG bytes to the sanitizing store — so GIF and BMP arrive as PNG, oversized images are
     * bounded, and the store's own checks still run. The last image imported is selected and the
     * "Yours" tab shown; files that could not be imported are named, the rest still land.
     */
    private fun importFiles(files: List<File>) {
        val store = icons
        if (store == null) { showError("Icons can't be stored right now."); return }
        val failed = ArrayList<String>()
        var last: String? = null
        var reason: String? = null
        for (file in files.take(MAX_FILES)) {
            try {
                val image = ImageIO.read(file) ?: throw IllegalArgumentException("not a PNG, JPG, GIF or BMP")
                val bytes = toPng(image, 128)
                last = store.add(file.nameWithoutExtension + ".png", bytes).key
            } catch (e: Exception) {
                failed += file.name
                if (reason == null) reason = e.message
            }
        }
        if (last != null) { pending = last; tab = "Yours" }
        if (failed.isEmpty()) {
            error.isVisible = false; error.isManaged = false
        } else {
            val names = failed.take(3).joinToString(", ") + if (failed.size > 3) " and ${failed.size - 3} more" else ""
            showError(if (failed.size == 1) "$names couldn’t be imported${reason?.let { ": $it" } ?: "."}" else "${failed.size} of ${files.size} couldn’t be imported: $names.")
        }
        if (files.size > MAX_FILES) showError("Only the first $MAX_FILES images were imported.")
        render()
    }

    private fun showError(text: String) {
        error.text = text
        error.isVisible = true; error.isManaged = true
    }

    companion object {
        /** How many images one drop or browse imports at most — a folder of hundreds is not what the card is for. */
        const val MAX_FILES = 64

        /** Scale [image] so its longer side is at most [max] px (never upscaled) and encode it as PNG. */
        fun toPng(image: BufferedImage, max: Int): ByteArray {
            val w = image.width
            val h = image.height
            if (w <= 0 || h <= 0) throw IllegalArgumentException("The image is empty.")
            val scale = minOf(1.0, max.toDouble() / maxOf(w, h))
            val tw = maxOf(1, Math.round(w * scale).toInt())
            val th = maxOf(1, Math.round(h * scale).toInt())
            val out = BufferedImage(tw, th, BufferedImage.TYPE_INT_ARGB)
            val g = out.createGraphics()
            try {
                g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                g.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING, java.awt.RenderingHints.VALUE_RENDER_QUALITY)
                g.drawImage(image, 0, 0, tw, th, null)
            } finally {
                g.dispose()
            }
            val buffer = ByteArrayOutputStream()
            ImageIO.write(out, "png", buffer)
            return buffer.toByteArray()
        }
    }
}
