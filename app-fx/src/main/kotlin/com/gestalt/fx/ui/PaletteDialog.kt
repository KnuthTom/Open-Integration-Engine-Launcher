/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.fx.ui

import com.gestalt.fx.model.LauncherModel
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.ColorPicker
import javafx.scene.control.Label
import javafx.scene.control.TextField
import javafx.scene.control.Tooltip
import javafx.scene.layout.HBox
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.scene.paint.Color

/**
 * The colours card behind the header's paintbrush. Two rows of round swatches — the **launcher
 * colour** (the accent: Launch, the selected row, switches, focus rings) and the **background** (the
 * canvas behind the cards) — each with a "custom" swatch that opens the colour chooser (a grid, and
 * "Custom Color…" with RGB/HSB sliders) and a hex field for a brand colour typed in. Every pick
 * applies at once, so the window is the preview. Cancel, the X and Esc put back the colours the card
 * opened with; Reset puts the design's defaults; Done keeps what is showing.
 */
class PaletteDialog(private val modal: ModalHost, private val model: LauncherModel) {

    private class Preset(val name: String, val hex: String)

    private var openedAccent = Theme.DEFAULT_ACCENT
    private var openedBackground = Theme.DEFAULT_BACKGROUND
    private var syncing = false

    private val accentRow = SwatchRow(ACCENTS, Theme.DEFAULT_ACCENT, { model.accent.get() }) { model.accent.set(it) }
    private val backgroundRow = SwatchRow(BACKGROUNDS, Theme.DEFAULT_BACKGROUND, { model.background.get() }) { model.background.set(it) }

    fun show() {
        openedAccent = Theme.normalizeAccent(model.accent.get())
        openedBackground = Theme.normalizeBackground(model.background.get())
        sync()
        val card = VBox().apply {
            styleClass += "modal-card"
            children += HBox(8.0).apply {
                alignment = Pos.CENTER_LEFT
                children += Label("Colors").apply { styleClass += "card-title" }
                children += Controls.spacer()
                children += Controls.iconButton("x", "Cancel", 16.0) { cancel() }.apply { minWidth = 28.0; prefWidth = 28.0; maxWidth = 28.0; minHeight = 28.0; prefHeight = 28.0; maxHeight = 28.0 }
            }
            children += Label("Picks apply as you make them — the window is the preview. Cancel puts back what you started with.")
                .apply { styleClass += "modal-body"; isWrapText = true; VBox.setMargin(this, Insets(6.0, 0.0, 0.0, 0.0)) }
            children += heading("Launcher color", "The Launch button, the selected row, switches and focus rings").apply { VBox.setMargin(this, Insets(18.0, 0.0, 0.0, 0.0)) }
            children += accentRow.apply { VBox.setMargin(this, Insets(8.0, 0.0, 0.0, 0.0)) }
            children += heading("Background", "The canvas behind the cards; the cards and fields keep their own").apply { VBox.setMargin(this, Insets(18.0, 0.0, 0.0, 0.0)) }
            children += backgroundRow.apply { VBox.setMargin(this, Insets(8.0, 0.0, 0.0, 0.0)) }
            children += HBox().apply {
                styleClass += "modal-actions"
                children += Controls.button("Reset to defaults", Controls.ButtonKind.Ghost) {
                    model.accent.set(Theme.DEFAULT_ACCENT)
                    model.background.set(Theme.DEFAULT_BACKGROUND)
                    sync()
                }
                children += Controls.spacer()
                children += Controls.button("Cancel", Controls.ButtonKind.Secondary) { cancel() }
                children += Controls.button("Done", Controls.ButtonKind.Primary) { modal.close() }
            }
        }
        modal.show(card, 520.0, escape = { cancel() }, enter = { modal.close() })
    }

    /** Put back the colours the card opened with and close (a hex typed but not entered is dropped, not applied by the blur). */
    private fun cancel() {
        cancelling = true
        try {
            model.accent.set(openedAccent)
            model.background.set(openedBackground)
            modal.close()
        } finally {
            cancelling = false
        }
    }

    private var cancelling = false

    private fun heading(title: String, note: String): VBox = VBox(2.0).apply {
        children += Label(title).apply { styleClass += "palette-heading" }
        children += Label(note).apply { styleClass += "field-note"; isWrapText = true; minWidth = 0.0 }
    }

    /** Show the model's colours in both rows without treating that as a pick. */
    private fun sync() {
        accentRow.sync()
        backgroundRow.sync()
    }

    /**
     * One row: the preset swatches, the custom swatch — a ColorPicker lies under it, unseen, so its
     * chooser opens right there — and the hex field. The lit swatch is the colour in force; when that
     * is no preset, the custom swatch wears it.
     */
    private inner class SwatchRow(presets: List<Preset>, private val default: String, private val current: () -> String, private val onPick: (String) -> Unit) : HBox(8.0) {
        private val swatches: List<Pair<Preset, Button>> = presets.map { p ->
            p to Button().apply {
                styleClass += "swatch"
                style = "-fx-background-color: ${p.hex};"
                tooltip = Tooltip(p.name)
                isFocusTraversable = false
                setOnAction { onPick(p.hex); sync() }
            }
        }
        private val picker = ColorPicker(Color.web(default)).apply {
            styleClass += "swatch-picker"
            customColors += Color.web(default)
            isFocusTraversable = false
            isMouseTransparent = true
            opacity = 0.0
            // Kept to the custom swatch's 30 px cell, so the chooser's popup opens under that swatch.
            minWidth = 0.0; prefWidth = 30.0; maxWidth = 30.0; minHeight = 0.0; prefHeight = 30.0; maxHeight = 30.0
            valueProperty().addListener { _, _, c -> if (!syncing && c != null) { onPick(Theme.hex(c)); sync() } }
        }
        private val plus = Icons.glyph("plus", 14.0, "-text-muted")
        private val custom = Button().apply {
            styleClass.addAll("swatch", "custom")
            graphic = plus
            tooltip = Tooltip("Custom color…")
            isFocusTraversable = false
            setOnAction { picker.show() }
        }
        private val hexField = TextField().apply { styleClass += "mono-field"; promptText = "#RRGGBB"; minWidth = 96.0; prefWidth = 96.0; maxWidth = 96.0 }

        init {
            alignment = Pos.CENTER_LEFT
            minWidth = 0.0
            for ((_, b) in swatches) children += b
            children += StackPane(picker, custom).apply { minWidth = 30.0; prefWidth = 30.0; maxWidth = 30.0; minHeight = 30.0; prefHeight = 30.0; maxHeight = 30.0 }
            children += Controls.spacer()
            children += hexField
            hexField.setOnAction { commitHex() }
            hexField.focusedProperty().addListener { _, _, focused -> if (!focused) commitHex() }
        }

        /** A typed `#RRGGBB` (the # optional) applies; anything else just snaps the field back to the colour in force. */
        private fun commitHex() {
            // JavaFX clears focus a pulse after the card leaves the scene, so the blur lands after Cancel/Done: nothing to commit then.
            if (cancelling || hexField.scene == null) return
            val typed = hexField.text?.trim()?.removePrefix("#")?.uppercase().orEmpty()
            val value = "#$typed"
            if (HEX.matches(value) && !value.equals(current(), ignoreCase = true)) onPick(value)
            sync()
        }

        fun sync() {
            val value = Theme.normalizeColor(current(), default)
            var preset = false
            for ((p, b) in swatches) {
                val on = p.hex.equals(value, ignoreCase = true)
                preset = preset || on
                if (on) { if ("on" !in b.styleClass) b.styleClass += "on" } else b.styleClass.removeAll("on")
            }
            if (preset) { custom.styleClass.removeAll("on"); custom.style = ""; custom.graphic = plus }
            else { if ("on" !in custom.styleClass) custom.styleClass += "on"; custom.style = "-fx-background-color: $value;"; custom.graphic = null }
            hexField.text = value // a swatch clicked while the field has focus wins over what was typed there
            syncing = true
            try { picker.value = Color.web(value) } finally { syncing = false }
        }
    }

    companion object {
        private val HEX = Regex("^#[0-9A-F]{6}$")

        /** Launcher colours: the design's graphite first, then seven that read well under white text. */
        private val ACCENTS = listOf(
            Preset("Graphite", Theme.DEFAULT_ACCENT), Preset("Slate", "#3B4A5C"), Preset("Ocean", "#1F66B3"), Preset("Teal", "#127A72"),
            Preset("Forest", "#237A47"), Preset("Plum", "#6B45B5"), Preset("Wine", "#B23A69"), Preset("Rust", "#B8532E"),
        )

        /** Backgrounds: the design's paper first, five light tints, and two dark canvases (the cards stay white on them). */
        private val BACKGROUNDS = listOf(
            Preset("Paper", Theme.DEFAULT_BACKGROUND), Preset("Mist", "#EEF1F5"), Preset("Linen", "#F5F0E8"), Preset("Sand", "#F1EADF"),
            Preset("Sage", "#E8EFE9"), Preset("Lavender", "#EEEAF5"), Preset("Dusk", "#2A2D33"), Preset("Night", "#1C1D20"),
        )
    }
}
