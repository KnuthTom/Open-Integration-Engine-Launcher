/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.fx.ui

import com.gestalt.fx.model.LauncherModel
import javafx.geometry.Insets
import javafx.scene.control.ColorPicker
import javafx.scene.control.Label
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import javafx.scene.paint.Color

/**
 * The colours card behind the header's paintbrush: the background (the canvas behind the cards) and
 * the launcher colour (the accent), each a JavaFX ColorPicker — a swatch grid plus "Custom Color…"
 * with RGB/HSB sliders, the design's default in the custom row. A pick applies at once (the window
 * re-skins live, through the model's properties); Reset puts both defaults back; Done closes.
 */
class PaletteDialog(private val modal: ModalHost, private val model: LauncherModel) {

    private var syncing = false
    // Not `background`: inside a GridPane.apply block that name is the pane's own Region.background.
    private val backgroundPicker = picker(Theme.DEFAULT_BACKGROUND) { model.background.set(it) }
    private val accentPicker = picker(Theme.DEFAULT_ACCENT) { model.accent.set(it) }

    private fun picker(default: String, onPick: (String) -> Unit): ColorPicker = ColorPicker(Color.web(default)).apply {
        styleClass += "palette-picker"
        maxWidth = Double.MAX_VALUE
        customColors += Color.web(default)
        valueProperty().addListener { _, _, c -> if (!syncing && c != null) onPick(Theme.hex(c)) }
    }

    fun show() {
        sync()
        val grid = Controls.fieldGrid(120.0).apply {
            row(0, "Background", backgroundPicker)
            row(1, "Launcher color", accentPicker)
            VBox.setMargin(this, Insets(14.0, 0.0, 0.0, 0.0))
        }
        val card = VBox().apply {
            styleClass += "modal-card"
            children += Label("Colors").apply { styleClass += "card-title" }
            children += Label(
                "The background shades the canvas behind the cards. The launcher color is the Launch button, the selected row, " +
                    "switches and focus rings. Picks apply at once.",
            ).apply { styleClass += "modal-body"; isWrapText = true; VBox.setMargin(this, Insets(8.0, 0.0, 0.0, 0.0)) }
            children += grid
            children += HBox().apply {
                styleClass += "modal-actions"
                children += Controls.button("Reset to defaults", Controls.ButtonKind.Ghost) {
                    model.background.set(Theme.DEFAULT_BACKGROUND)
                    model.accent.set(Theme.DEFAULT_ACCENT)
                    sync()
                }
                children += Controls.button("Done", Controls.ButtonKind.Primary) { modal.close() }
            }
        }
        modal.show(card, 440.0, escape = { modal.close() }, enter = { modal.close() })
    }

    /** Show the model's colours in the pickers without treating that as a pick. */
    private fun sync() {
        syncing = true
        try {
            backgroundPicker.value = Color.web(Theme.normalizeBackground(model.background.get()))
            accentPicker.value = Color.web(Theme.normalizeAccent(model.accent.get()))
        } finally {
            syncing = false
        }
    }
}
