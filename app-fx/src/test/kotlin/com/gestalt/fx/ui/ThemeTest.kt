/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.fx.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** The palette maths: a background and an accent become the whole colour register (no toolkit needed — colours only). */
class ThemeTest {

    private fun tokens(accent: String, background: String): Map<String, String> =
        Theme.paletteCss(accent, background).lines()
            .filter { it.trim().startsWith("-") }
            .associate { line -> line.trim().removeSuffix(";").split(":", limit = 2).let { it[0].trim() to it[1].trim() } }

    @Test
    @DisplayName("the defaults reproduce the design: the canvas is the light surface, graphite accent with white text, light.css's selection tint")
    fun defaultsAreTheDesign() {
        val t = tokens(Theme.DEFAULT_ACCENT, Theme.DEFAULT_BACKGROUND)
        assertEquals("#F7F7F8", t["-canvas"])
        assertEquals("#1C1D20", t["-canvas-text"])
        assertEquals("#2E3035", t["-accent"])
        assertEquals("#FFFFFF", t["-accent-contrast"]) { "white on graphite" }
        assertEquals("#D7D7D9", t["-selected"]) { "accent 16% over the surface, as light.css has it" }
        assertEquals("#2E3035", t["-focus-ring"])
        assertTrue(t.keys.none { it == "-surface" || it == "-text" || it == "-border" }) { "the chrome, cards, fields and text keep the design's register: ${t.keys}" }
    }

    @Test
    @DisplayName("the background shades the canvas only — a dark pick gets white text for what sits on it directly, and a light accent gets ink on it")
    fun backgroundIsTheCanvasOnly() {
        val t = tokens("#FFD60A", "#101418")
        assertEquals("#101418", t["-canvas"])
        assertEquals("#FFFFFF", t["-canvas-text"])
        assertEquals("#1C1D20", t["-accent-contrast"]) { "ink on a bright yellow" }
        assertEquals("#FFD60A", t["-focus-ring"])
        assertTrue(t.keys.none { it.startsWith("-surface") || it == "-text" || it == "-ok" }) { "nothing but the canvas and the accent changes: ${t.keys}" }
    }

    @Test
    @DisplayName("colours are normalised: junk and blanks fall back to the defaults, case is folded")
    fun normalisation() {
        assertEquals(Theme.DEFAULT_ACCENT, Theme.normalizeAccent(null))
        assertEquals(Theme.DEFAULT_ACCENT, Theme.normalizeAccent("red"))
        assertEquals(Theme.DEFAULT_BACKGROUND, Theme.normalizeBackground(""))
        assertEquals("#1F66B3", Theme.normalizeAccent(" #1f66b3 "))
        assertEquals("#1F66B3", Theme.hex(javafx.scene.paint.Color.web("#1f66b3")))
    }
}
