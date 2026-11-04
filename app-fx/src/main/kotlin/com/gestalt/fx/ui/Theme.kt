/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.fx.ui

import com.gestalt.launcher.AppPaths
import javafx.scene.Scene
import javafx.scene.paint.Color
import javafx.scene.text.Font
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.roundToInt

/**
 * Stylesheets built on looked-up colors. `base.css` is structure; `light.css` / `dark.css` are the
 * colors (the product uses the light register; the dark one stays for previews). The **accent** — the
 * launcher's colour: the Launch button, selection, switches, focus rings — is the user's pick, set as
 * looked-up colours inline on the scene root so every node inherits it ([applyAccent]). Font families
 * come from the platform: JavaFX CSS has no font variables, so a tiny `fonts.css` naming the chosen
 * families is written beside the data files at start-up.
 */
object Theme {

    private fun res(name: String): String = requireNotNull(Theme::class.java.getResource("/com/gestalt/fx/$name")) { "missing stylesheet $name" }.toExternalForm()

    val base: String get() = res("base.css")
    val light: String get() = res("light.css")
    val dark: String get() = res("dark.css")

    /** The first family of each list the platform has; the last entry is the JavaFX logical fallback. */
    val sansFamily: String by lazy { pick("Segoe UI Variable Text", "Segoe UI", "SF Pro Text", ".AppleSystemUIFont", "Inter", "Cantarell", "Ubuntu", "Noto Sans", "DejaVu Sans", "System") }
    val monoFamily: String by lazy { pick("Cascadia Mono", "Consolas", "JetBrains Mono", "SF Mono", "Menlo", "DejaVu Sans Mono", "Noto Sans Mono", "Liberation Mono", "Monospaced") }
    val displayFamily: String by lazy { pick("Newsreader", "Georgia", "Times New Roman", "Serif") }

    private fun pick(vararg candidates: String): String {
        val families = runCatching { Font.getFamilies().toSet() }.getOrDefault(emptySet())
        return candidates.firstOrNull { it in families } ?: candidates.last()
    }

    private val fontsCss: String by lazy {
        val css = """
            .root { -fx-font-family: "$sansFamily"; }
            .mono, .mono-field, .sidebar-count, .row-count, .badge-warn, .menu-hint, .seg-button.mono, .cert-host, .mono-text { -fx-font-family: "$monoFamily"; }
            .name-field, .display { -fx-font-family: "$displayFamily"; }
        """.trimIndent()
        val file: Path = runCatching {
            val dir = AppPaths.dataDir
            Files.createDirectories(dir)
            dir.resolve("ui-fonts.css").also { Files.writeString(it, css) }
        }.getOrElse {
            Files.createTempFile("oie-launcher-fonts", ".css").also { Files.writeString(it, css) }
        }
        file.toUri().toString()
    }

    /** Install the stylesheets on [scene] for [dark] or light; call again to switch. */
    fun apply(scene: Scene, dark: Boolean) {
        val sheets = scene.stylesheets
        sheets.setAll(base, fontsCss, if (dark) this.dark else light)
        isDark = dark
    }

    /** The register the last [apply] installed. */
    @Volatile
    var isDark: Boolean = false
        private set

    /** The design's graphite: the accent when the user has picked none. */
    const val DEFAULT_ACCENT = "#2E3035"
    private val HEX = Regex("^#[0-9A-Fa-f]{6}$")

    /** `#RRGGBB`, upper-case, or the default for anything else (a stray pref, an empty string). */
    fun normalizeAccent(value: String?): String = value?.trim()?.uppercase()?.takeIf { HEX.matches(it) } ?: DEFAULT_ACCENT

    /** A JavaFX colour as `#RRGGBB`. */
    fun hex(c: Color): String = String.format("#%02X%02X%02X", (c.red * 255).roundToInt(), (c.green * 255).roundToInt(), (c.blue * 255).roundToInt())

    /** The accent the last [applyAccent] installed. */
    @Volatile
    var accent: String = DEFAULT_ACCENT
        private set

    /**
     * Install [accent] as the launcher's colour: the accent tokens are set inline on the scene root
     * (inline beats the stylesheet, and looked-up colours resolve up the tree, so every node follows);
     * the derived tones — hover, active, the text on it, the soft fill, selection, focus — are computed
     * from it. [literal] follows for popups.
     */
    fun applyAccent(scene: Scene, accent: String) {
        val a = normalizeAccent(accent)
        scene.root.style = accentStyle(a, isDark)
        this.accent = a
    }

    /** The inline style that sets the accent tokens for [hex] over the light (or dark) surface. */
    internal fun accentStyle(hex: String, dark: Boolean): String {
        val base = Color.web(hex)
        val surface = Color.web(if (dark) "#0A0A0B" else "#F7F7F8")
        val hover = base.deriveColor(0.0, 1.0, 0.86, 1.0)
        val active = base.deriveColor(0.0, 1.0, 0.72, 1.0)
        // Text on the accent: dark ink on a light colour, white on a dark or saturated one (relative luminance).
        fun lin(c: Double) = if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        val luminance = 0.2126 * lin(base.red) + 0.7152 * lin(base.green) + 0.0722 * lin(base.blue)
        val contrast = if (luminance > 0.40) "#1C1D20" else "#FFFFFF"
        val soft = surface.interpolate(base, 0.10)
        val selected = surface.interpolate(base, 0.16)
        return listOf(
            "-accent: ${hex(base)}", "-accent-hover: ${hex(hover)}", "-accent-active: ${hex(active)}",
            "-accent-contrast: $contrast", "-accent-soft: ${hex(soft)}", "-selected: ${hex(selected)}", "-focus-ring: ${hex(base)}",
        ).joinToString("; ", postfix = ";")
    }

    /**
     * A colour token as a literal for the current register, for nodes that live in a popup (menus,
     * tooltips): an inline `-fx-stroke: -text` on a node inside a popup window cannot resolve the
     * looked-up colours the main scene's `.root` defines, and JavaFX warns "Could not resolve '-text'"
     * and draws nothing. Popups are rebuilt each time they open, so a literal follows the theme.
     * Unknown tokens are returned as they are. Keep in step with light.css / dark.css.
     */
    fun literal(token: String): String = if (token == "-accent") accent else (if (isDark) DARK else LIGHT)[token] ?: token

    private val LIGHT = mapOf("-text" to "#1C1D20", "-text-muted" to "#5F6168", "-accent" to "#2E3035", "-ok" to "#237A47", "-danger" to "#B23B2E")
    private val DARK = mapOf("-text" to "#F2F3F5", "-text-muted" to "#A6A7AD", "-accent" to "#E9EAEC", "-ok" to "#6EE7A0", "-danger" to "#EF6B6B")
}
