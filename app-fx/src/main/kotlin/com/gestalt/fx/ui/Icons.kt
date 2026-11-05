/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.fx.ui

import javafx.scene.Group
import javafx.scene.Node
import javafx.scene.layout.StackPane
import javafx.scene.paint.Color
import javafx.scene.shape.Rectangle
import javafx.scene.shape.SVGPath
import javafx.scene.shape.StrokeLineCap
import javafx.scene.shape.StrokeLineJoin
import javafx.scene.transform.Scale

/**
 * Draws [Lucide] glyphs as stroked [SVGPath]s scaled from the 24-grid, tinted through a looked-up
 * color so they follow the theme without inverting (the design's "Icons" note). Every glyph sits in a
 * fixed [size]×[size] box so rows and buttons lay out identically whatever the shape.
 */
object Icons {

    /**
     * @param color a looked-up color name (`-text-muted`, `-lc-blue`, …) or a CSS color literal.
     */
    fun glyph(name: String, size: Double = 16.0, color: String = "-text-muted", strokeWidth: Double = 2.0): Node {
        val paths = Lucide.paths[name] ?: Lucide.paths.getValue("square")
        val group = Group()
        group.children += Rectangle(0.0, 0.0, 24.0, 24.0).apply { fill = Color.TRANSPARENT; stroke = null; isMouseTransparent = true }
        for (d in paths) {
            group.children += SVGPath().apply {
                content = d
                fill = Color.TRANSPARENT
                this.strokeWidth = strokeWidth
                strokeLineCap = StrokeLineCap.ROUND
                strokeLineJoin = StrokeLineJoin.ROUND
                styleClass += "glyph"
                style = "-fx-stroke: $color;"
            }
        }
        return boxed(group, size)
    }

    /**
     * Scale the 24-grid [group] to [size] and pin it in a fixed box so layout never depends on the shape.
     *
     * The scaled group goes into the box inside a second, plain [Group]: layout places a node by its
     * layoutBounds, which leave out the node's own transforms, so the box would centre the glyph as if it
     * were still 24 units wide and draw it (24 − size) / 2 px up and to the left of where it belongs —
     * every glyph smaller than 24 px sat off-centre, the change-icon pencil most visibly. A Group's
     * layoutBounds do include its children's transforms, so the outer one is exactly [size] square.
     */
    private fun boxed(group: Group, size: Double): Node {
        val s = size / 24.0
        group.transforms += Scale(s, s)
        return StackPane(Group(group)).apply {
            minWidth = size; prefWidth = size; maxWidth = size
            minHeight = size; prefHeight = size; maxHeight = size
            isPickOnBounds = false
        }
    }
}

/** One entry of the built-in icon library: a Lucide glyph, its tint token and a label. */
data class LibraryIcon(val name: String, val tint: String, val label: String) {
    val colorVar: String get() = IconLibrary.colorVar(tint)
}

/**
 * The design's built-in library — Healthcare, Lab, Cloud, SaaS — plus the mapping from the icon keys
 * the Compose app persisted (its pathology SVG set) to the nearest glyph, so existing connections keep
 * a sensible icon. A connection stores the glyph name as its `icon`; `custom:<id>` keys are user images.
 */
object IconLibrary {
    val health = listOf(
        LibraryIcon("hospital", "blue", "Hospital"), LibraryIcon("building-2", "blue", "Clinic"), LibraryIcon("stethoscope", "teal", "Provider"),
        LibraryIcon("heart-pulse", "rose", "Cardiology"), LibraryIcon("activity", "rose", "Monitoring"), LibraryIcon("file-heart", "rose", "EHR"),
        LibraryIcon("clipboard-list", "blue", "Orders"), LibraryIcon("arrow-left-right", "teal", "Interface"), LibraryIcon("network", "violet", "HIE"),
        LibraryIcon("printer", "ink", "Fax"), LibraryIcon("pill", "violet", "Pharmacy"), LibraryIcon("scan-line", "teal", "Imaging"),
        LibraryIcon("ambulance", "rose", "Emergency"), LibraryIcon("shield-plus", "green", "Payer"),
    )
    val lab = listOf(
        LibraryIcon("flask-conical", "blue", "Chemistry"), LibraryIcon("flask-round", "violet", "Lab"), LibraryIcon("test-tube", "rose", "Specimen"),
        LibraryIcon("test-tubes", "teal", "Samples"), LibraryIcon("microscope", "blue", "Pathology"), LibraryIcon("dna", "violet", "Genomics"),
        LibraryIcon("atom", "teal", "Research"), LibraryIcon("brain", "rose", "Neurology"), LibraryIcon("droplet", "rose", "Hematology"),
        LibraryIcon("syringe", "teal", "Draw station"), LibraryIcon("biohazard", "green", "Biosafety"), LibraryIcon("scan-search", "blue", "Slide scan"),
    )
    val cloud = listOf(
        LibraryIcon("cloud", "blue", "Cloud"), LibraryIcon("cloud-cog", "teal", "Cloud service"), LibraryIcon("cloud-upload", "blue", "Backup"),
        LibraryIcon("server", "ink", "Server"), LibraryIcon("server-cog", "blue", "Engine"), LibraryIcon("database", "violet", "Database"),
        LibraryIcon("hard-drive", "ink", "Storage"), LibraryIcon("container", "teal", "Container"), LibraryIcon("cpu", "ink", "Compute"),
        LibraryIcon("router", "blue", "Network"), LibraryIcon("globe", "green", "Region"), LibraryIcon("shield-check", "green", "Secure"),
        LibraryIcon("tree-pine", "green", "Site"), LibraryIcon("tree-palm", "teal", "Site"),
    )
    val saas = listOf(
        LibraryIcon("layers", "blue", "Platform"), LibraryIcon("boxes", "violet", "Tenants"), LibraryIcon("box", "teal", "Package"),
        LibraryIcon("app-window", "blue", "Web app"), LibraryIcon("blocks", "teal", "Modules"), LibraryIcon("workflow", "violet", "Workflow"),
        LibraryIcon("webhook", "rose", "Webhook"), LibraryIcon("plug", "ink", "Integration"), LibraryIcon("gauge", "green", "Performance"),
        LibraryIcon("terminal", "ink", "Console"), LibraryIcon("code", "blue", "Dev"), LibraryIcon("bug", "rose", "Test"),
        LibraryIcon("rocket", "green", "Prod"), LibraryIcon("git-branch", "violet", "Branch"),
    )

    val categories: List<Pair<String, List<LibraryIcon>>> = listOf("Healthcare" to health, "Lab" to lab, "Cloud" to cloud, "SaaS" to saas)

    private val byName: Map<String, LibraryIcon> = (health + lab + cloud + saas).associateBy { it.name }

    /** Keys the Compose app's icon set used, mapped to the nearest glyph. */
    private val legacy: Map<String, String> = mapOf(
        "microscope" to "microscope", "microscope-specimen" to "microscope", "specimen" to "test-tube",
        "test-tubes" to "test-tubes", "urine-sample" to "flask-round", "blood-bag" to "droplet", "blood-drop" to "droplet",
        "blood-cells" to "droplet", "cell-nuclei" to "atom", "cancer-cells" to "biohazard", "dna" to "dna",
        "bacteria" to "biohazard", "tissue" to "scan-line", "biopsy" to "syringe", "hematology-lab" to "droplet",
        "biochemistry-lab" to "flask-conical", "lab-search" to "scan-search", "database" to "database", "virus" to "biohazard",
        "default" to "server",
    )

    const val DEFAULT = "server"

    fun colorVar(tint: String): String = when (tint) {
        "blue", "teal", "violet", "rose", "green" -> "-lc-$tint"
        else -> "-text-muted"
    }

    /** True for a user-imported image key. */
    fun isCustom(key: String): Boolean = key.startsWith("custom:")

    /** The glyph for a stored icon key (library name, legacy Compose key, or anything else → default). */
    fun glyphFor(key: String?): LibraryIcon {
        val k = key?.trim().orEmpty()
        byName[k]?.let { return it }
        legacy[k]?.let { mapped -> byName[mapped]?.let { return it } }
        if (k in Lucide) return LibraryIcon(k, "ink", k)
        return byName.getValue(DEFAULT)
    }
}
