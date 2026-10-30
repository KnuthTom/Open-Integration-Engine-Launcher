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
    }
}
