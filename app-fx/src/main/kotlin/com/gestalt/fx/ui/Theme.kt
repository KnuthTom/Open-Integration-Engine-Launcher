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
 * Stylesheets built on looked-up colors. `base.css` is structure; `light.css` is the design's neutral
 * register — the window's chrome, cards, fields and text. On top of it the user's **palette** is
 * written as a stylesheet of its own ([applyPalette]): the **background** shades only the canvas the
 * cards sit on (`-canvas`), and the **accent** gives the Launch button, selection, switches and focus
 * rings their colour, with its hover/active tones, the text on it, the soft fill and the selection
 * tint derived from it. A stylesheet, not an inline style, so popups — menus, tooltips, the toast —
 * follow too. Font families come from the platform: JavaFX CSS has no font variables, so a tiny
 * `fonts.css` naming the chosen families is written beside the data files.
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
        writeSheet("ui-fonts.css", css)
    }

    /** Write a generated stylesheet beside the data files (a temp file when that fails) and return its URL. */
    private fun writeSheet(name: String, css: String): String {
        val file: Path = runCatching {
            val dir = AppPaths.dataDir
            Files.createDirectories(dir)
            dir.resolve(name).also { Files.writeString(it, css) }
        }.getOrElse {
            Files.createTempFile("oie-launcher-", ".css").also { Files.writeString(it, css) }
        }
        return file.toUri().toString()
    }

    /** Install the stylesheets on [scene] for [dark] or light with the default palette; [applyPalette] is the product's way. */
    fun apply(scene: Scene, dark: Boolean) {
        scene.stylesheets.setAll(base, fontsCss, if (dark) this.dark else light)
        isDark = dark
        literals = if (dark) DARK else LIGHT
    }

    /** The register in force (the product uses the light one; [apply] can install the dark sheet for previews). */
    @Volatile
    var isDark: Boolean = false
        private set

    /** The design's graphite: the accent when the user has picked none. */
    const val DEFAULT_ACCENT = "#2E3035"
    /** The design's light surface: the canvas behind the cards when the user has picked none. */
    const val DEFAULT_BACKGROUND = "#F7F7F8"
    private val HEX = Regex("^#[0-9A-Fa-f]{6}$")

    /** `#RRGGBB`, upper-case, or [fallback] for anything else (a stray pref, an empty string). */
    fun normalizeColor(value: String?, fallback: String): String = value?.trim()?.uppercase()?.takeIf { HEX.matches(it) } ?: fallback
    fun normalizeAccent(value: String?): String = normalizeColor(value, DEFAULT_ACCENT)
    fun normalizeBackground(value: String?): String = normalizeColor(value, DEFAULT_BACKGROUND)

    /** A JavaFX colour as `#RRGGBB`. */
    fun hex(c: Color): String = String.format("#%02X%02X%02X", (c.red * 255).roundToInt(), (c.green * 255).roundToInt(), (c.blue * 255).roundToInt())

    /** The accent and background the last [applyPalette] installed. */
    @Volatile var accent: String = DEFAULT_ACCENT; private set
    @Volatile var background: String = DEFAULT_BACKGROUND; private set

    /**
     * Install the user's palette: the stylesheets become base + fonts + light + a generated sheet whose
     * `.root` sets the canvas from [background] and the accent tokens from [accent] (see [paletteCss]).
     * The sheet is written under a name that carries the colours, since JavaFX caches stylesheets by
     * URL; earlier ones are removed. [literal] follows.
     */
    fun applyPalette(scene: Scene, accent: String, background: String) {
        val a = normalizeAccent(accent)
        val b = normalizeBackground(background)
        val name = "ui-theme-${b.drop(1)}-${a.drop(1)}.css".lowercase()
        pruneSheets(keep = name)
        val url = writeSheet(name, paletteCss(a, b))
        scene.stylesheets.setAll(base, fontsCss, light, url)
        this.accent = a
        this.background = b
        isDark = false
        literals = LIGHT + ("-accent" to a)
    }

    private fun pruneSheets(keep: String) {
        runCatching {
            Files.list(AppPaths.dataDir).use { files ->
                files.filter { f -> f.fileName.toString().let { it.startsWith("ui-theme-") && it.endsWith(".css") && it != keep } }
                    .forEach { runCatching { Files.deleteIfExists(it) } }
            }
        }
    }

    /** Relative luminance (sRGB), 0 black … 1 white. */
    private fun luminance(c: Color): Double {
        fun lin(v: Double) = if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        return 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)
    }

    /**
     * The `.root` tokens for a palette. The background is the canvas behind the cards and nothing else
     * — the chrome, the cards, fields and text keep the design's register — with the text that may sit
     * on the canvas directly (the empty state) ink or white by its luminance. The accent tones follow
     * the accent: hover and active darker, the text on it ink or white by luminance, the soft fill and
     * the selection tint the accent laid over the design's surface.
     */
    internal fun paletteCss(accent: String, background: String): String {
        val canvas = Color.web(background)
        val a = Color.web(accent)
        val surface = Color.web(DEFAULT_BACKGROUND)
        val canvasText = if (luminance(canvas) > 0.40) "#1C1D20" else "#FFFFFF"
        val accentHover = a.deriveColor(0.0, 1.0, 0.86, 1.0)
        val accentActive = a.deriveColor(0.0, 1.0, 0.72, 1.0)
        val accentContrast = if (luminance(a) > 0.40) "#1C1D20" else "#FFFFFF"
        val accentSoft = surface.interpolate(a, 0.06)
        val selected = surface.interpolate(a, 0.16)
        val tokens = linkedMapOf(
            "-canvas" to hex(canvas), "-canvas-text" to canvasText,
            "-accent" to hex(a), "-accent-hover" to hex(accentHover), "-accent-active" to hex(accentActive), "-accent-contrast" to accentContrast,
            "-accent-soft" to hex(accentSoft), "-selected" to hex(selected), "-focus-ring" to hex(a),
        )
        return ".root {\n" + tokens.entries.joinToString("\n") { (k, v) -> "    $k: $v;" } + "\n}\n"
    }

    /**
     * A colour token as a literal for the palette in force, for nodes drawn with inline styles inside a
     * popup (menu glyphs): an inline `-fx-stroke: -text` there cannot resolve the looked-up colours, and
     * JavaFX warns "Could not resolve '-text'" and draws nothing. Unknown tokens are returned as they are.
     */
    fun literal(token: String): String = literals[token] ?: token

    // Declared before `literals`: an object's properties initialise top to bottom.
    private val LIGHT = mapOf("-text" to "#1C1D20", "-text-muted" to "#5F6168", "-accent" to "#2E3035", "-ok" to "#237A47", "-danger" to "#B23B2E")
    private val DARK = mapOf("-text" to "#F2F3F5", "-text-muted" to "#A6A7AD", "-accent" to "#E9EAEC", "-ok" to "#6EE7A0", "-danger" to "#EF6B6B")
    @Volatile private var literals: Map<String, String> = LIGHT
}
