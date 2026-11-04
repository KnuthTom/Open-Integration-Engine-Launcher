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
 * register and the fallback for every token. On top of it the user's **palette** — a background and an
 * accent — is written as a stylesheet of its own ([applyPalette]): the whole neutral register (raised
 * and sunken surfaces, text, muted text, borders, hover and selection tints) is derived from the
 * background, light or dark by its luminance, and the accent tones (hover, active, the text on it, the
 * soft fill, selection, focus) from the accent. A stylesheet, not an inline style, so popups — menus,
 * tooltips, the toast — follow too. Font families come from the platform: JavaFX CSS has no font
 * variables, so a tiny `fonts.css` naming the chosen families is written beside the data files.
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

    /** The register in force: dark when the background is. */
    @Volatile
    var isDark: Boolean = false
        private set

    /** The design's graphite: the accent when the user has picked none. */
    const val DEFAULT_ACCENT = "#2E3035"
    /** The design's light surface: the background when the user has picked none. */
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
     * `.root` redefines every colour token from [background] and [accent] (see [paletteCss]). The sheet is
     * written under a name that carries the colours, since JavaFX caches stylesheets by URL; earlier ones
     * are removed. [literal] follows.
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
        isDark = isDark(Color.web(b))
        literals = literalsFor(a, b)
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

    private fun isDark(background: Color): Boolean = luminance(background) < 0.36

    /**
     * The `.root` tokens for a palette. The neutral register follows the background: on a light one,
     * ink text and surfaces that step toward white (raised) and toward the ink (sunken, borders, hover);
     * on a dark one, light text and surfaces that step toward white by small amounts — the design's
     * light and dark sheets, generalised to any colour. The accent tones follow the accent: hover and
     * active darker, the text on it ink or white by luminance, the soft fill and the selection tint the
     * accent laid over the background.
     */
    internal fun paletteCss(accent: String, background: String): String {
        val bg = Color.web(background)
        val a = Color.web(accent)
        val dark = isDark(bg)
        val text = Color.web(if (dark) "#F2F3F5" else "#1C1D20")
        val raised = if (dark) bg.interpolate(Color.WHITE, 0.045) else bg.interpolate(Color.WHITE, 0.75)
        val sunken = if (dark) bg.interpolate(Color.WHITE, 0.08) else bg.interpolate(text, 0.045)
        val hover = bg.interpolate(text, 0.06)
        val hoverStrong = bg.interpolate(text, 0.08)
        val border = bg.interpolate(text, 0.09)
        val borderStrong = bg.interpolate(text, 0.50)
        val muted = text.interpolate(bg, 0.32)
        val secondary = sunken
        val secondaryHover = bg.interpolate(text, 0.09)
        val accentHover = a.deriveColor(0.0, 1.0, 0.86, 1.0)
        val accentActive = a.deriveColor(0.0, 1.0, 0.72, 1.0)
        val accentContrast = if (luminance(a) > 0.40) "#1C1D20" else "#FFFFFF"
        val accentSoft = bg.interpolate(a, 0.06)
        val selected = bg.interpolate(a, 0.16)
        val state = if (dark) mapOf("-ok" to "#6EE7A0", "-warn" to "#F0B84A", "-danger" to "#EF6B6B", "-danger-contrast" to "#160404",
            "-lc-blue" to "#7AA9D6", "-lc-teal" to "#5CC8BE", "-lc-violet" to "#A98BEB", "-lc-rose" to "#EC8DB2", "-lc-green" to "#6EE7A0",
            "-surface-overlay" to "rgba(0, 0, 0, 0.66)", "-shadow" to "rgba(0, 0, 0, 0.7)")
        else mapOf("-ok" to "#237A47", "-warn" to "#9A5F00", "-danger" to "#B23B2E", "-danger-contrast" to "#FFFFFF",
            "-lc-blue" to "#1F66B3", "-lc-teal" to "#127A72", "-lc-violet" to "#6B45B5", "-lc-rose" to "#B23A69", "-lc-green" to "#237A47",
            "-surface-overlay" to "rgba(18, 19, 22, 0.40)", "-shadow" to "rgba(0, 0, 0, 0.35)")
        val tokens = linkedMapOf(
            "-surface" to hex(bg), "-surface-raised" to hex(raised), "-surface-sunken" to hex(sunken),
            "-text" to hex(text), "-text-muted" to hex(muted), "-border" to hex(border), "-border-strong" to hex(borderStrong),
            "-accent" to hex(a), "-accent-hover" to hex(accentHover), "-accent-active" to hex(accentActive), "-accent-contrast" to accentContrast,
            "-accent-soft" to hex(accentSoft), "-btn-secondary" to hex(secondary), "-btn-secondary-hover" to hex(secondaryHover),
            "-hover" to hex(hover), "-hover-strong" to hex(hoverStrong), "-selected" to hex(selected), "-focus-ring" to hex(a),
            "-lc-ink" to hex(text),
        ) + state
        return ".root {\n" + tokens.entries.joinToString("\n") { (k, v) -> "    $k: $v;" } + "\n}\n"
    }

    private fun literalsFor(accent: String, background: String): Map<String, String> {
        val bg = Color.web(background)
        val dark = isDark(bg)
        val text = Color.web(if (dark) "#F2F3F5" else "#1C1D20")
        return mapOf(
            "-text" to hex(text), "-text-muted" to hex(text.interpolate(bg, 0.32)), "-accent" to accent,
            "-ok" to if (dark) "#6EE7A0" else "#237A47", "-danger" to if (dark) "#EF6B6B" else "#B23B2E",
        )
    }

    /**
     * A colour token as a literal for the palette in force, for nodes drawn with inline styles inside a
     * popup (menu glyphs): an inline `-fx-stroke: -text` there cannot resolve the looked-up colours, and
     * JavaFX warns "Could not resolve '-text'" and draws nothing. Unknown tokens are returned as they are.
     */
    fun literal(token: String): String = literals[token] ?: token

    @Volatile private var literals: Map<String, String> = LIGHT
    private val LIGHT = mapOf("-text" to "#1C1D20", "-text-muted" to "#5F6168", "-accent" to "#2E3035", "-ok" to "#237A47", "-danger" to "#B23B2E")
    private val DARK = mapOf("-text" to "#F2F3F5", "-text-muted" to "#A6A7AD", "-accent" to "#E9EAEC", "-ok" to "#6EE7A0", "-danger" to "#EF6B6B")
}
