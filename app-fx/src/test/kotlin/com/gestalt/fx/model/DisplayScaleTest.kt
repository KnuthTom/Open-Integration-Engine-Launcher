/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.fx.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class DisplayScaleTest {

    @Test
    @DisplayName("Auto: the largest offered factor that keeps the Administrator's layout on the screen, by physical pixels")
    fun autoByScreen() {
        assertEquals("0.75", DisplayScale.auto(1920.0, 1080.0)) { "a 1080p-class display, whatever Windows scales it to" }
        assertEquals("0.75", DisplayScale.auto(1920.0, 1200.0))
        assertEquals("1.0", DisplayScale.auto(2560.0, 1440.0))
        assertEquals("1.0", DisplayScale.auto(2560.0, 1600.0))
        assertEquals("1.25", DisplayScale.auto(3840.0, 2160.0)) { "4K: the largest offered" }
        assertEquals("0.5", DisplayScale.auto(1366.0, 768.0)) { "a small laptop panel" }
        assertEquals("0.5", DisplayScale.auto(800.0, 600.0)) { "never below the smallest offered" }
        assertEquals("0.75", DisplayScale.auto(0.0, 0.0)) { "no screen: the engine's default" }
    }

    @Test
    @DisplayName("a connection's own well-formed scale wins over Auto")
    fun ownScaleWins() {
        assertEquals("1.0", DisplayScale.effective("1.0"))
        assertEquals("0.5", DisplayScale.effective(" 0.5 "))
    }
}
