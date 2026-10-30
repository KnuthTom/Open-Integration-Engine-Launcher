/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.fx

import com.gestalt.fx.model.LauncherModel
import com.gestalt.fx.ui.Shell
import com.gestalt.launcher.icon.CustomIconStore
import javafx.application.Application
import javafx.application.Platform
import javafx.stage.Screen
import javafx.stage.Stage
import java.util.Properties

/**
 * The launcher window: a resizable stage, 1120 × 760 by default and never smaller than 720 × 560,
 * remembering its size, position and maximized state between runs.
 */
class LauncherApp : Application() {

    private var shell: Shell? = null

    override fun start(stage: Stage) {
        val model = LauncherModel()
        val icons = runCatching { CustomIconStore() }.getOrNull()
        val shell = Shell(model, stage, icons).also { this.shell = it }

        stage.title = "$APP_NAME ${version()}".trim()
        stage.icons.setAll(appIcons())
        stage.scene = shell.scene
        stage.minWidth = 720.0
        stage.minHeight = 560.0
        restoreGeometry(stage, model)
        // The X goes through the shell: a plain close, or — while an Administrator still runs through a
        // port-forward the launcher opened — hide and stay alive until it exits (see Shell.requestClose).
        stage.setOnCloseRequest { e -> e.consume(); shell.requestClose() }
        stage.show()
        // A launcher started while this one runs (hidden or not) brings this window back instead.
        SingleInstance.onShow = { Platform.runLater { shell.show() } }
    }

    override fun stop() {
        SingleInstance.release()
        shell?.shutdown()
    }

    private fun restoreGeometry(stage: Stage, model: LauncherModel) {
        val p = model.prefs
        val w = p.windowWidth ?: 1120.0
        val h = p.windowHeight ?: 760.0
        stage.width = w.coerceAtLeast(720.0)
        stage.height = h.coerceAtLeast(560.0)
        val x = p.windowX
        val y = p.windowY
        if (x != null && y != null && Screen.getScreensForRectangle(x, y, 80.0, 40.0).isNotEmpty()) {
            stage.x = x
            stage.y = y
        }
        if (p.windowMaximized) stage.isMaximized = true
    }

    companion object {
        const val APP_NAME = "Open Integration Engine Launcher"

        /** The window / taskbar icon at every size the platform may ask for. */
        fun appIcons(): List<javafx.scene.image.Image> =
            listOf(16, 24, 32, 48, 64, 128, 256).mapNotNull { size ->
                LauncherApp::class.java.getResourceAsStream("/com/gestalt/fx/icon/app-$size.png")?.use { javafx.scene.image.Image(it) }
            }

        /** The build's version, from the resource Gradle expands; empty when running unpackaged. */
        fun version(): String = runCatching {
            LauncherApp::class.java.getResourceAsStream("/com/gestalt/fx/app-fx.properties")?.use { input ->
                Properties().apply { load(input) }.getProperty("version", "")
            }
        }.getOrNull()?.takeIf { !it.contains("\${") }.orEmpty()

        fun exit() = Platform.exit()
    }
}
