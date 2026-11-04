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
    @DisplayName("the defaults reproduce the design's light register: ink on near-white, graphite accent with white text")
    fun defaultsAreTheDesign() {
        val t = tokens(Theme.DEFAULT_ACCENT, Theme.DEFAULT_BACKGROUND)
        assertEquals("#F7F7F8", t["-surface"])
        assertEquals("#1C1D20", t["-text"])
        assertEquals("#2E3035", t["-accent"])
        assertEquals("#FFFFFF", t["-accent-contrast"]) { "white on graphite" }
        assertEquals("#EAEAEB", t["-hover"]) { "text 6% over the surface, as light.css has it" }
        assertEquals("#D7D7D9", t["-selected"]) { "accent 16% over the surface, as light.css has it" }
        assertEquals("#237A47", t["-ok"]) { "state colours of the light register" }
        assertTrue(t.containsKey("-surface-raised") && t.containsKey("-border-strong") && t.containsKey("-focus-ring"))
    }

    @Test
    @DisplayName("a dark background flips the register: light text, surfaces that step toward white, dark-register state colours; a light accent gets ink on it")
    fun darkBackgroundFlipsTheRegister() {
        val t = tokens("#FFD60A", "#101418")
        assertEquals("#F2F3F5", t["-text"])
        assertEquals("#1C1D20", t["-accent-contrast"]) { "ink on a bright yellow" }
        assertEquals("#6EE7A0", t["-ok"])
        assertEquals("#101418", t["-surface"])
        val raised = Integer.parseInt(t["-surface-raised"]!!.drop(1), 16)
        assertTrue(raised > 0x101418) { "raised steps toward white on a dark background: ${t["-surface-raised"]}" }
        assertEquals("#FFD60A", t["-focus-ring"])
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
