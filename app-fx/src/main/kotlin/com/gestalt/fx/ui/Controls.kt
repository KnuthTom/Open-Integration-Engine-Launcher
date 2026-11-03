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
import javafx.scene.control.MenuButton
import javafx.scene.control.MenuItem
import javafx.scene.control.SeparatorMenuItem
import javafx.scene.control.Tooltip
import javafx.scene.image.Image
import javafx.scene.image.ImageView
import javafx.scene.layout.ColumnConstraints
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import java.io.ByteArrayInputStream

/** Small building blocks that give stock JavaFX controls the design's shapes. */
object Controls {

    enum class ButtonKind(val styleClass: String) { Primary("btn-primary"), Secondary("btn-secondary"), Outline("btn-outline"), Ghost("btn-ghost"), Danger("btn-danger") }

    fun button(text: String, kind: ButtonKind = ButtonKind.Secondary, icon: Node? = null, small: Boolean = false, onAction: () -> Unit = {}): Button =
        Button(text).apply {
            styleClass.addAll("btn", kind.styleClass)
            if (small) styleClass += "sm"
            graphic = icon
            setOnAction { onAction() }
        }

    /** A 24×26 toolbar button holding a glyph. */
    fun iconButton(glyph: String, tooltip: String, size: Double = 15.0, onAction: () -> Unit = {}): Button =
        Button().apply {
            styleClass += "icon-button"
            graphic = Icons.glyph(glyph, size)
            this.tooltip = Tooltip(tooltip)
            isFocusTraversable = false
            setOnAction { onAction() }
        }

    /** Replace the glyph on an [iconButton]. */
    fun setGlyph(button: Button, glyph: String, size: Double = 15.0) {
        button.graphic = Icons.glyph(glyph, size)
    }

    fun label(text: String, vararg classes: String): Label = Label(text).apply { styleClass.addAll(classes) }

    fun spacer(): Region = Region().apply { HBox.setHgrow(this, Priority.ALWAYS); VBox.setVgrow(this, Priority.ALWAYS) }

    /** A card: title, then [body]. */
    fun card(title: String, body: Node, head: Node? = null): VBox =
        VBox().apply {
            styleClass += "card"
            children += head ?: label(title, "card-title")
            children += body
        }

    /** A two-column label/field grid as the cards use. */
    fun fieldGrid(labelWidth: Double = 104.0): GridPane = GridPane().apply {
        styleClass += "field-grid"
        columnConstraints.setAll(
            ColumnConstraints().apply { minWidth = labelWidth; prefWidth = labelWidth; maxWidth = labelWidth },
            ColumnConstraints().apply { hgrow = Priority.ALWAYS; minWidth = 0.0 },
        )
    }

    /** Horizontal group with the design's 8 px gap. */
    fun fieldRow(vararg nodes: Node): HBox = HBox(8.0, *nodes).apply { styleClass += "field-row"; alignment = Pos.CENTER_LEFT }

    /** An outline-styled MenuButton with a leading icon, caption and a chevron; items supplied by [items]. */
    fun menuButton(caption: String, leading: Node? = null, menuItems: () -> List<MenuItem>): MenuButton {
        val button = MenuButton()
        button.styleClass.addAll("btn", "btn-outline")
        // Never squeezed: a label's minimum width is the width of "…", so a crowded row turned the caption
        // (the Java runtime's "Automatic", a folder's path) into an ellipsis.
        button.minWidth = Region.USE_PREF_SIZE
        val captionLabel = Label(caption).apply { minWidth = Region.USE_PREF_SIZE }
        button.graphic = HBox(8.0).apply {
            alignment = Pos.CENTER_LEFT
            leading?.let { children += it }
            children += captionLabel
            children += Icons.glyph("chevron-down", 14.0)
        }
        button.properties["caption"] = captionLabel
        button.setOnShowing { button.items.setAll(menuItems()) }
        return button
    }

    /** A menu item with a leading glyph (or a check), a label, an optional right-aligned hint. */
    fun menuItem(label: String, glyph: String? = null, checked: Boolean? = null, hint: String = "", danger: Boolean = false, indent: Int = 0, onAction: () -> Unit): MenuItem =
        MenuItem().apply {
            val row = HBox(8.0).apply {
                alignment = Pos.CENTER_LEFT
                padding = Insets(0.0, 0.0, 0.0, indent * 14.0)
                // Literal colours: a menu lives in a popup window, where an inline looked-up colour does not resolve (see Theme.literal).
                val lead: Node = when {
                    checked != null -> Icons.glyph("check", 14.0, Theme.literal("-text")).apply { isVisible = checked }
                    glyph != null -> Icons.glyph(glyph, 14.0, Theme.literal(if (danger) "-danger" else "-text"))
                    else -> Region().apply { minWidth = 14.0; prefWidth = 14.0 }
                }
                children += lead
                children += Label(label).apply { if (danger) styleClass += "danger-text"; minWidth = Region.USE_PREF_SIZE }
                if (hint.isNotEmpty()) {
                    children += Region().apply { minWidth = 16.0; HBox.setHgrow(this, Priority.ALWAYS) }
                    children += Label(hint).apply { styleClass += "menu-hint" }
                }
            }
            graphic = row
            if (danger) styleClass += "danger"
            setOnAction { onAction() }
        }

    fun menuHeader(text: String): MenuItem = MenuItem().apply {
        graphic = Label(text).apply { styleClass += "menu-header" }
        isDisable = true
        styleClass += "menu-header-item"
    }

    fun menuSeparator(): MenuItem = SeparatorMenuItem()

    /** The segmented control: one button per option; [onSelect] fires with the chosen value. */
    class Segmented<T>(private val options: List<Pair<T, String>>, mono: Boolean = false, private val onSelect: (T) -> Unit) : HBox() {
        private val buttons = ArrayList<Pair<T, Button>>()
        var value: T? = null
            set(v) {
                field = v
                // Exactly one lit segment: set idempotently (a style class list keeps duplicates, and one
                // `remove` takes only one of them — which is how a segment once stayed lit beside the new one).
                buttons.forEach { (k, b) -> if (k == v) { if ("on" !in b.styleClass) b.styleClass.add("on") } else b.styleClass.removeAll("on") }
            }

        init {
            styleClass += "segmented"
            alignment = Pos.CENTER_LEFT
            for ((key, text) in options) {
                val b = Button(text).apply {
                    styleClass += "seg-button"
                    if (mono) styleClass += "mono"
                    isFocusTraversable = false
                    minWidth = Region.USE_PREF_SIZE // a squeezed segment must not shrink its label to "…"
                    setOnAction { onSelect(key) }
                }
                buttons += key to b
                children += b
            }
        }
    }

    /** A 34×20 switch. */
    class Switch(private val onToggle: (Boolean) -> Unit) : StackPane() {
        private val knob = Region().apply { styleClass += "knob" }
        var on: Boolean = false
            set(v) {
                field = v
                if (v) { if ("on" !in styleClass) styleClass.add("on") } else styleClass.removeAll("on")
                StackPane.setAlignment(knob, if (v) Pos.CENTER_RIGHT else Pos.CENTER_LEFT)
            }

        init {
            styleClass += "switch"
            children += knob
            StackPane.setMargin(knob, Insets(2.0))
            StackPane.setAlignment(knob, Pos.CENTER_LEFT)
            setOnMouseClicked { onToggle(!on) }
        }
    }

    /** A 16 px box with a check, followed by a label; clicking anywhere toggles. */
    class CheckRow(text: String, private val onToggle: (Boolean) -> Unit) : HBox() {
        private val mark = StackPane(Icons.glyph("check", 12.0, "-accent-contrast", 2.5)).apply { styleClass += "check-box-mark" }
        val label = Label(text)
        var on: Boolean = false
            set(v) {
                field = v
                if (v) { if ("on" !in styleClass) styleClass.add("on") } else styleClass.removeAll("on")
                mark.children.first().isVisible = v
            }

        init {
            styleClass += "check-row"
            alignment = Pos.CENTER_LEFT
            children.addAll(mark, label)
            mark.children.first().isVisible = false
            setOnMouseClicked { onToggle(!on) }
        }
    }

    /**
     * The icon of a connection at [size]: a library glyph in its tint, or the user's image (a `custom:`
     * key) decoded from the icon store. A custom icon that isn't a bitmap (the Compose app allowed SVG)
     * falls back to the default glyph for now.
     */
    fun connectionIcon(key: String?, size: Double, icons: CustomIconStore?): Node {
        if (key != null && IconLibrary.isCustom(key) && icons != null) {
            val id = CustomIconStore.idFromKey(key)
            val icon = id?.let { icons.get(it) }
            if (icon != null && icon.ext.lowercase() != "svg") {
                val bytes = runCatching { icons.bytes(icon.id) }.getOrNull()
                if (bytes != null) {
                    val image = runCatching { Image(ByteArrayInputStream(bytes), size * 2, size * 2, true, true) }.getOrNull()
                    if (image != null && !image.isError) {
                        return ImageView(image).apply {
                            fitWidth = size; fitHeight = size; isPreserveRatio = true; isSmooth = true
                        }
                    }
                }
            }
        }
        val glyph = IconLibrary.glyphFor(key)
        return Icons.glyph(glyph.name, size, glyph.colorVar)
    }
}

/** Add a label/field pair at [index] of a [Controls.fieldGrid]. */
fun GridPane.row(index: Int, label: String, field: Node) {
    add(Label(label).apply { styleClass += "field-label" }, 0, index)
    add(field, 1, index)
    GridPane.setHgrow(field, Priority.ALWAYS)
}

/** Change the caption of a [Controls.menuButton]. */
fun MenuButton.setCaption(text: String) {
    (properties["caption"] as? Label)?.text = text
}
