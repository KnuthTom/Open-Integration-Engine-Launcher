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
 * Lives in the root StackPane so it covers the sidebar and the detail pane, bottom bar included.
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

/**
 * The window's one-line notice — "Saved", "Launching …", "Couldn't import" — as a strip in the detail
 * pane's bottom bar, left of the unsaved marker and Delete, where it never covers the form. An icon (a
 * spinner while something runs), the title, a muted detail that ellipsizes when the bar is narrow, a
 * thin progress bar for a launch, and an X: it clears the notice, and when [show] is given [onClose]
 * it runs that first — a launch notice's X cancels the launch (tooltip [closeTip]).
 */
class NoticeStrip : HBox() {

    enum class Kind { Info, Success, Error, Progress }

    private val iconSlot = StackPane().apply { minWidth = 16.0; prefWidth = 16.0; maxWidth = 16.0 }
    private val titleLabel = Label().apply { styleClass += "notice-title"; minWidth = 0.0 }
    private val detailLabel = Label().apply { styleClass += "notice-detail"; minWidth = 0.0; maxWidth = 440.0 }
    private val bar = ProgressBar(ProgressBar.INDETERMINATE_PROGRESS).apply { styleClass += "notice-bar"; minWidth = 96.0; prefWidth = 96.0; maxWidth = 96.0 }
    private val close = Controls.iconButton("x", "Close", 13.0) { val run = onClose; dismiss(); run?.invoke() }.apply { styleClass += "notice-close" }
    private var onClose: (() -> Unit)? = null
    private var hide: PauseTransition? = null
    private var spin: RotateTransition? = null
    private var serial = 0

    /** True while a notice is up (the bar shows itself for one even when no connection is selected). */
    val isShowing: Boolean get() = isVisible

    init {
        styleClass += "notice"
        alignment = Pos.CENTER_LEFT
        minWidth = 0.0
        children.addAll(iconSlot, titleLabel, detailLabel, bar, close)
        isVisible = false; isManaged = false
    }

    /**
     * Show a notice; a non-progress one clears itself after [millis]. A new notice replaces the one up,
     * and the replaced one's [onClose] does not run — only the X runs it.
     */
    fun show(title: String, detail: String?, kind: Kind, millis: Long = 3400, closeTip: String = "Close", onClose: (() -> Unit)? = null) {
        dismiss()
        val mine = ++serial
        val icon: Node = when (kind) {
            Kind.Progress -> Icons.glyph("loader-circle", 15.0, "-text-muted").also { spin(it) }
            Kind.Error -> Icons.glyph("circle-alert", 15.0, "-danger")
            Kind.Success -> Icons.glyph("circle-check", 15.0, "-ok")
            Kind.Info -> Icons.glyph("circle-check", 15.0, "-text-muted")
        }
        iconSlot.children.setAll(icon)
        titleLabel.text = title
        setDetail(detail)
        bar.progress = ProgressBar.INDETERMINATE_PROGRESS
        bar.isVisible = kind == Kind.Progress; bar.isManaged = bar.isVisible
        close.tooltip.text = closeTip
        this.onClose = onClose
        styleClass.removeAll("info", "success", "error", "progress")
        styleClass += kind.name.lowercase()
        isVisible = true; isManaged = true
        if (kind != Kind.Progress) {
            hide = PauseTransition(Duration.millis(millis.toDouble())).apply { setOnFinished { if (serial == mine) dismiss() }; play() }
        }
    }

    /** Move the progress bar to [done]/[total] and say so beside the title; no-op unless a progress notice is up. */
    fun progress(done: Int, total: Int, detail: String) {
        if (!isVisible || !bar.isVisible) return
        bar.progress = if (total > 0) (done.toDouble() / total).coerceIn(0.0, 1.0) else ProgressBar.INDETERMINATE_PROGRESS
        setDetail(detail)
    }

    fun dismiss() {
        hide?.stop(); hide = null
        spin?.stop(); spin = null
        onClose = null
        iconSlot.children.clear()
        isVisible = false; isManaged = false
    }

    private fun setDetail(detail: String?) {
        detailLabel.text = detail.orEmpty()
        detailLabel.isVisible = !detail.isNullOrBlank(); detailLabel.isManaged = detailLabel.isVisible
    }

    private fun spin(node: Node) {
        spin = RotateTransition(Duration.millis(900.0), node).apply {
            byAngle = 360.0
            cycleCount = Animation.INDEFINITE
            interpolator = javafx.animation.Interpolator.LINEAR
            play()
        }
    }
}
