/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.fx.ui

import com.gestalt.launcher.AppPaths
import javafx.scene.Scene
import javafx.scene.text.Font
import java.nio.file.Files
import java.nio.file.Path

/**
 * Two stylesheets built on the same looked-up colors, swapped on the Scene at runtime (the design's
 * "Light and dark"). `base.css` is structure; `light.css` / `dark.css` are the colors (and the dark
 * register's squarer radii). Font families come from the platform: JavaFX CSS has no font variables,
 * so a tiny `fonts.css` naming the chosen families is written beside the data files at start-up.
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

    /**
     * A colour token as a literal for the current register, for nodes that live in a popup (menus,
     * tooltips): an inline `-fx-stroke: -text` on a node inside a popup window cannot resolve the
     * looked-up colours the main scene's `.root` defines, and JavaFX warns "Could not resolve '-text'"
     * and draws nothing. Popups are rebuilt each time they open, so a literal follows the theme.
     * Unknown tokens are returned as they are. Keep in step with light.css / dark.css.
     */
    fun literal(token: String): String = (if (isDark) DARK else LIGHT)[token] ?: token

    private val LIGHT = mapOf("-text" to "#1C1D20", "-text-muted" to "#5F6168", "-accent" to "#2E3035", "-ok" to "#237A47", "-danger" to "#B23B2E")
    private val DARK = mapOf("-text" to "#F2F3F5", "-text-muted" to "#A6A7AD", "-accent" to "#E9EAEC", "-ok" to "#6EE7A0", "-danger" to "#EF6B6B")
}
