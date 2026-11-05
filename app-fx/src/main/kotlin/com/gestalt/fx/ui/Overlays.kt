/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.fx.ui

import javafx.animation.PauseTransition
import javafx.animation.RotateTransition
import javafx.animation.Animation
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Label
import javafx.scene.control.ProgressBar
import javafx.scene.control.TextField
import javafx.scene.input.KeyCode
import javafx.scene.layout.HBox
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.util.Duration

/** One button of a modal. */
data class ModalAction(val label: String, val kind: Controls.ButtonKind = Controls.ButtonKind.Secondary, val isDefault: Boolean = false, val onClick: () -> Unit)

/**
 * The window's modal layer: a scrim over the whole window with one card in the middle, like the
 * design's dialogs. Only one modal shows at a time; Esc closes it, Enter runs the default action.
 * Lives in the root StackPane so it covers the sidebar, the detail pane and the toast.
 */
class ModalHost(private val root: StackPane) {

    private var current: StackPane? = null
    private var onEscape: (() -> Unit)? = null
    private var onEnter: (() -> Unit)? = null

    val isShowing: Boolean get() = current != null

    /** Show [card] (already styled) at [width]; [escape] runs on Esc, [enter] on Enter. */
    fun show(card: Node, width: Double, escape: (() -> Unit)? = { close() }, enter: (() -> Unit)? = null) {
        close()
        val holder = VBox(card).apply {
            maxWidth = width
            minWidth = 0.0
            isFillWidth = true
            alignment = Pos.CENTER
        }
        val scrim = StackPane(holder).apply {
            styleClass += "modal-scrim"
            alignment = Pos.CENTER
            padding = Insets(16.0)
            isFocusTraversable = true
            // Swallow clicks on the scrim so nothing underneath reacts; the card's own handlers run first.
            setOnMouseClicked { it.consume() }
            setOnKeyPressed { e ->
                when (e.code) {
                    KeyCode.ESCAPE -> { onEscape?.invoke(); e.consume() }
                    KeyCode.ENTER -> { onEnter?.let { it(); e.consume() } }
                    else -> Unit
                }
            }
        }
        StackPane.setAlignment(holder, Pos.CENTER)
        onEscape = escape
        onEnter = enter
        current = scrim
        root.children += scrim
        scrim.requestFocus()
    }

    fun close() {
        current?.let { root.children.remove(it) }
        current = null
        onEscape = null
        onEnter = null
    }

    /** A title, a body paragraph and a row of actions (the design's modal). */
    fun confirm(title: String, body: String, actions: List<ModalAction>) {
        val card = VBox().apply {
            styleClass += "modal-card"
            children += Label(title).apply { styleClass += "card-title" }
            children += Label(body).apply { styleClass += "modal-body"; isWrapText = true; VBox.setMargin(this, Insets(8.0, 0.0, 0.0, 0.0)) }
            children += HBox().apply {
                styleClass += "modal-actions"
                for (a in actions) children += Controls.button(a.label, a.kind) { close(); a.onClick() }
            }
        }
        val default = actions.firstOrNull { it.isDefault }
        show(card, 400.0, escape = { close() }, enter = default?.let { d -> { close(); d.onClick() } })
    }

    /** A single text prompt (rename). */
    fun prompt(title: String, initial: String, okLabel: String = "OK", onOk: (String) -> Unit) {
        val field = TextField(initial)
        val card = VBox().apply {
            styleClass += "modal-card"
            children += Label(title).apply { styleClass += "card-title" }
            children += field.apply { VBox.setMargin(this, Insets(14.0, 0.0, 0.0, 0.0)) }
            children += HBox().apply {
                styleClass += "modal-actions"
                children += Controls.button("Cancel", Controls.ButtonKind.Secondary) { close() }
                children += Controls.button(okLabel, Controls.ButtonKind.Primary) { val v = field.text; close(); onOk(v) }
            }
        }
        // The field consumes Enter itself (its action event), so the OK path is wired to it directly too.
        field.setOnAction { val v = field.text; close(); onOk(v) }
        show(card, 400.0, escape = { close() }, enter = { val v = field.text; close(); onOk(v) })
        field.requestFocus()
        field.selectAll()
    }
}

/** A transient notice in the window's bottom-right corner. */
class ToastHost(private val root: StackPane) {

    enum class Kind { Info, Success, Error, Progress }

    private var current: HBox? = null
    private var hide: PauseTransition? = null
    private var bar: ProgressBar? = null
    private var sub: Label? = null

    /**
     * Show a toast. Every toast carries an X: it closes the toast, and when [onClose] is given it runs
     * that first — a launch toast's X cancels the launch (tooltip [closeTip]). Clicking the body only hides.
     */
    fun show(title: String, detail: String?, kind: Kind, millis: Long = 3400, closeTip: String = "Close", onClose: (() -> Unit)? = null) {
        dismiss()
        val icon: Node = when (kind) {
            Kind.Progress -> Icons.glyph("loader-circle", 16.0, "-text-muted").also { spin(it) }
            Kind.Error -> Icons.glyph("circle-alert", 16.0, "-danger")
            Kind.Success -> Icons.glyph("circle-check", 16.0, "-ok")
            Kind.Info -> Icons.glyph("circle-check", 16.0, "-text-muted")
        }
        val subLabel = Label(detail.orEmpty()).apply { styleClass += "toast-sub"; isVisible = !detail.isNullOrBlank(); isManaged = isVisible }
        val progressBar = if (kind == Kind.Progress) ProgressBar(ProgressBar.INDETERMINATE_PROGRESS).apply { styleClass += "toast-bar"; maxWidth = Double.MAX_VALUE } else null
        val text = VBox(3.0).apply {
            children += Label(title).apply { styleClass += "toast-title" }
            children += subLabel
            progressBar?.let { children += it }
            minWidth = 0.0
            prefWidth = if (progressBar != null) 260.0 else javafx.scene.layout.Region.USE_COMPUTED_SIZE
        }
        bar = progressBar
        sub = subLabel
        HBox.setHgrow(text, javafx.scene.layout.Priority.ALWAYS)
        val close = Controls.iconButton("x", closeTip, 13.0) { onClose?.invoke(); dismiss() }.apply {
            styleClass += "toast-close"
            setOnMouseClicked { it.consume() } // the body's click-to-hide must not double up on the X
        }
        val toast = HBox(icon, text, close).apply {
            styleClass += "toast"
            isPickOnBounds = false
            maxWidth = 380.0
            maxHeight = HBox.USE_PREF_SIZE
            setOnMouseClicked { dismiss() }
        }
        StackPane.setAlignment(toast, Pos.BOTTOM_RIGHT)
        StackPane.setMargin(toast, Insets(0.0, 24.0, 64.0, 0.0))
        current = toast
        root.children += toast
        if (kind != Kind.Progress) {
            hide = PauseTransition(Duration.millis(millis.toDouble())).apply { setOnFinished { if (current === toast) dismiss() }; play() }
        }
    }

    /** Move the progress toast's bar to [done]/[total] and say so beneath the title; no-op unless a progress toast is up. */
    fun progress(done: Int, total: Int, detail: String) {
        val b = bar ?: return
        b.progress = if (total > 0) (done.toDouble() / total).coerceIn(0.0, 1.0) else ProgressBar.INDETERMINATE_PROGRESS
        sub?.let { it.text = detail; it.isVisible = detail.isNotBlank(); it.isManaged = it.isVisible }
    }

    fun dismiss() {
        hide?.stop(); hide = null
        current?.let { root.children.remove(it) }
        current = null
        bar = null
        sub = null
    }

    private fun spin(node: Node) {
        RotateTransition(Duration.millis(900.0), node).apply {
            byAngle = 360.0
            cycleCount = Animation.INDEFINITE
            interpolator = javafx.animation.Interpolator.LINEAR
            play()
        }
    }
}
