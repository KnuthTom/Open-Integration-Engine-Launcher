/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.fx.model

import com.gestalt.launcher.launch.ChildJvmCommandBuilder

/**
 * The Administrator's display scale (`sun.java2d.uiScale`, an absolute factor: 1.0 is the unscaled
 * 96-dpi look whatever Windows is set to), and **Auto** — the launcher's own choice for the screen.
 *
 * Windows cannot size the Administrator to the screen: its scaling is what makes the client overflow a
 * high-DPI desktop in the first place, and the client's windows are laid out for roughly 2560 × 1440
 * physical pixels at 1.0. So Auto takes the primary screen's physical size and picks the largest of
 * the offered factors that keeps that layout on the screen: a 1920 × 1080 display gets 0.75, a
 * 2560 × 1440 one 1.0, a 4K one 1.25, a 1366 × 768 laptop 0.5. A connection with no scale of its own
 * is Auto; the factor is resolved at launch time, on the machine that launches.
 */
object DisplayScale {

    /** The factors offered, largest first. */
    val CHOICES: List<String> = listOf("1.25", "1.0", "0.75", "0.5")

    /** What the client's layout wants at 1.0, in physical pixels (an approximation that errs on the roomy side). */
    private const val LAYOUT_WIDTH = 2560.0
    private const val LAYOUT_HEIGHT = 1440.0

    /** The largest offered factor whose scaled layout fits [screenWidthPx] × [screenHeightPx] (physical pixels); the smallest when none does. */
    fun auto(screenWidthPx: Double, screenHeightPx: Double): String {
        if (!(screenWidthPx > 0 && screenHeightPx > 0)) return ChildJvmCommandBuilder.DEFAULT_UI_SCALE
        val fit = minOf(screenWidthPx / LAYOUT_WIDTH, screenHeightPx / LAYOUT_HEIGHT)
        return CHOICES.firstOrNull { it.toDouble() <= fit + 1e-9 } ?: CHOICES.last()
    }

    /** [auto] for the primary screen (its JavaFX bounds times its output scale are its physical pixels); the engine's default when there is no screen. */
    fun autoForPrimaryScreen(): String = runCatching {
        val screen = javafx.stage.Screen.getPrimary()
        auto(screen.bounds.width * screen.outputScaleX, screen.bounds.height * screen.outputScaleY)
    }.getOrDefault(ChildJvmCommandBuilder.DEFAULT_UI_SCALE)

    /** The factor a connection launches with: its own when well-formed, else Auto for this screen. */
    fun effective(uiScale: String?): String = uiScale?.trim()?.takeIf { it.isNotEmpty() } ?: autoForPrimaryScreen()
}
