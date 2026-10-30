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
 * "Connection icon": drop or browse for an image (PNG, JPG, GIF or BMP — scaled to at most 128 px and
 * stored as PNG through the sanitizing icon store), a light / dark preview of the choice, the built-in
 * library in its four categories plus "Yours" (imported images), Reset to default, Cancel, Apply.
 * Apply hands the chosen key back; the window applies it to the saved connection at once.
 */
class IconDialog(private val modal: ModalHost, private val icons: CustomIconStore?, private val window: () -> Window?) {

    private var pending: String = IconLibrary.DEFAULT
    private var tab: String = "Healthcare"
    private var name: String = ""
    private var onApply: (String) -> Unit = {}

    private val previewLight = HBox(6.0)
    private val previewDark = HBox(6.0)
    private val tabsHolder = HBox()
    private val tiles = FlowPane(6.0, 6.0)
    private val error = Label().apply { styleClass += "field-error"; isWrapText = true; isVisible = false; isManaged = false }

    fun show(connectionName: String, currentKey: String?, onApply: (String) -> Unit) {
        this.name = connectionName
        this.onApply = onApply
        pending = currentKey?.takeIf { it.isNotBlank() } ?: IconLibrary.DEFAULT
        tab = when {
            IconLibrary.isCustom(pending) -> "Yours"
            else -> IconLibrary.categories.firstOrNull { (_, list) -> list.any { it.name == IconLibrary.glyphFor(pending).name } }?.first ?: "Healthcare"
        }
        error.isVisible = false; error.isManaged = false

        val zone = VBox().apply {
            styleClass += "drop-zone"
            val row = HBox(14.0).apply {
                alignment = Pos.CENTER_LEFT
                children += StackPane(Icons.glyph("image-up", 20.0)).apply { styleClass += "drop-zone-icon" }
                children += VBox(2.0).apply {
                    minWidth = 0.0
                    HBox.setHgrow(this, Priority.ALWAYS)
                    children += HBox(4.0, Label("Drop an image or").apply { styleClass += "strong" }, Label("browse…").apply { styleClass += "link" })
                    children += Label("PNG, JPG, GIF or BMP. Square images work best — scaled to 16 and 32 px.").apply { styleClass += "field-note"; isWrapText = true }
                    children += error
                }
            }
            children += row
            setOnMouseClicked { browse() }
            setOnDragOver { e -> if (e.dragboard.hasFiles()) { e.acceptTransferModes(TransferMode.COPY); if ("over" !in styleClass) styleClass += "over" }; e.consume() }
            setOnDragExited { styleClass.remove("over") }
            setOnDragDropped { e ->
                val file = e.dragboard.files?.firstOrNull()
                styleClass.remove("over")
                e.isDropCompleted = file != null
                e.consume()
                file?.let { importFile(it) }
            }
        }

        val previews = HBox(8.0).apply {
            children += previewBox("Light theme", previewLight, dark = false)
            children += previewBox("Dark theme", previewDark, dark = true)
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
            children += previews.apply { VBox.setMargin(this, Insets(14.0, 0.0, 0.0, 0.0)) }
            children += tabsHolder.apply { VBox.setMargin(this, Insets(16.0, 0.0, 0.0, 0.0)) }
            children += tilesScroll.apply { VBox.setMargin(this, Insets(10.0, 0.0, 0.0, 0.0)) }
            children += HBox(8.0).apply {
                alignment = Pos.CENTER_LEFT
                padding = Insets(20.0, 0.0, 0.0, 0.0)
                children += Controls.button("Reset to default", Controls.ButtonKind.Ghost) { pending = IconLibrary.DEFAULT; tab = "Cloud"; render() }
                children += Controls.spacer()
                children += Controls.button("Cancel", Controls.ButtonKind.Secondary) { modal.close() }
                children += Controls.button("Apply", Controls.ButtonKind.Primary) { val k = pending; modal.close(); this@IconDialog.onApply(k) }
            }
        }
        render()
        modal.show(card, 480.0, escape = { modal.close() }, enter = { val k = pending; modal.close(); this.onApply(k) })
    }

    private fun previewBox(title: String, row: HBox, dark: Boolean): VBox {
        val bg = if (dark) "#0A0A0B" else "#F7F7F8"
        val fg = if (dark) "#F2F3F5" else "#1C1D20"
        val border = if (dark) "#2A2A2E" else "#E3E4E7"
        val rowBg = if (dark) "#2E2E2F" else "#D7D7D9"
        return VBox(6.0).apply {
            HBox.setHgrow(this, Priority.ALWAYS)
            maxWidth = Double.MAX_VALUE
            padding = Insets(8.0)
            style = "-fx-background-color: $bg; -fx-border-color: $border; -fx-border-width: 1; -fx-background-radius: 6; -fx-border-radius: 6;"
            children += Label(title).apply { style = "-fx-font-size: 11px; -fx-text-fill: ${if (dark) "#A6A7AD" else "#5F6168"};" }
            row.alignment = Pos.CENTER_LEFT
            row.minHeight = 24.0; row.prefHeight = 24.0
            row.padding = Insets(0.0, 8.0, 0.0, 8.0)
            row.style = "-fx-background-color: $rowBg; -fx-background-radius: 4;"
            row.properties["fg"] = fg
            children += row
        }
    }

    private fun render() {
        // previews
        for ((row, dark) in listOf(previewLight to false, previewDark to true)) {
            val fg = row.properties["fg"] as? String ?: "#1C1D20"
            row.children.setAll(iconNode(pending, 16.0, dark), Label(name).apply { style = "-fx-font-weight: bold; -fx-text-fill: $fg;"; minWidth = 0.0 })
        }
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
                tiles.children += Label("No imported images yet — drop one above.").apply { styleClass += "field-note"; padding = Insets(8.0, 4.0, 0.0, 4.0) }
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

    /** The icon for a key, as it looks in the given theme (literal colours so both previews are right at once). */
    private fun iconNode(key: String, size: Double, dark: Boolean): Node {
        if (IconLibrary.isCustom(key)) return Controls.connectionIcon(key, size, icons)
        val g = IconLibrary.glyphFor(key)
        return Icons.glyph(g.name, size, IconLibrary.tintHex(g.tint, dark))
    }

    private fun browse() {
        val chooser = FileChooser().apply {
            title = "Choose an image"
            extensionFilters += FileChooser.ExtensionFilter("Images (PNG, JPG, GIF, BMP)", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.bmp")
        }
        val file = chooser.showOpenDialog(window()) ?: return
        importFile(file)
    }

    /**
     * Decode with ImageIO (which proves it is an image), scale to at most 128 px, and hand the PNG bytes
     * to the sanitizing store — so GIF and BMP arrive as PNG, oversized images are bounded, and the
     * store's own checks still run. The new image is selected and the "Yours" tab shown.
     */
    private fun importFile(file: File) {
        val store = icons
        if (store == null) { showError("Icons can't be stored right now."); return }
        try {
            val image = ImageIO.read(file) ?: throw IllegalArgumentException("That file type isn’t supported. Use PNG, JPG, GIF or BMP.")
            val bytes = toPng(image, 128)
            val icon = store.add(file.nameWithoutExtension + ".png", bytes)
            pending = icon.key
            tab = "Yours"
            error.isVisible = false; error.isManaged = false
            render()
        } catch (e: Exception) {
            showError(e.message ?: "That image couldn’t be imported.")
        }
    }

    private fun showError(text: String) {
        error.text = text
        error.isVisible = true; error.isManaged = true
    }

    companion object {
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
