/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.fx

import com.gestalt.fx.model.LauncherModel
import com.gestalt.fx.ui.Shell
import com.gestalt.launcher.audit.AuditLog
import com.gestalt.launcher.icon.CustomIconStore
import javafx.application.Application
import javafx.application.Platform
import javafx.scene.control.Alert
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
        // Nothing on the UI thread may die unseen: a failure is recorded, shown, and never a silent hang.
        Thread.currentThread().setUncaughtExceptionHandler { _, t -> onUncaught(t) }
        try {
            build(stage)
        } catch (t: Throwable) {
            fatal(t)
        }
    }

    private fun build(stage: Stage) {
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

    /** An exception that escaped on the FX thread: audited (class, scrubbed message, the top frames) and shown; the window stays up. */
    private fun onUncaught(t: Throwable) {
        val where = t.stackTrace.take(3).joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
        // One record per 2 s per failure site: an exception thrown on every pulse must not roll real records out of audit.log.
        val signature = "${t.javaClass.name}@$where"
        val now = System.currentTimeMillis()
        val last = lastError
        if (last != null && last.first == signature && now - last.second < 2_000) return
        lastError = signature to now
        runCatching { AuditLog.event("launcher.error", "exception" to t.javaClass.name, "message" to brief(t), "at" to where) }
        t.printStackTrace()
        runCatching { shell?.reportError(t) }
    }

    private var lastError: Pair<String, Long>? = null

    /**
     * What of an exception message may reach the audit trail: an address typed with `user:secret@` ends up
     * verbatim in a URISyntaxException, and a tunnel failure quotes the tunnel tool's output — so URL
     * userinfo is masked and the length bounded. (The notice in the bottom bar shows the message as it is; it is not a log.)
     */
    private fun brief(t: Throwable): String =
        (t.message?.takeIf { it.isNotBlank() } ?: "-").replace(USERINFO, "$1***@").take(240)

    /** The window could not be built (an unreadable profile, a locked file, a missing resource): say so in a plain dialog and leave; no window would be worse. */
    private fun fatal(t: Throwable) {
        runCatching { AuditLog.event("launcher.fatal", "exception" to t.javaClass.name, "message" to brief(t)) }
        t.printStackTrace()
        runCatching {
            Alert(Alert.AlertType.ERROR).apply {
                title = APP_NAME
                headerText = "The launcher couldn’t start"
                contentText = "${t.javaClass.simpleName}: ${t.message ?: "no details"}\n\nIts data folder may be unreadable or in use. See logs/audit.log."
            }.showAndWait()
        }
        SingleInstance.release()
        Platform.exit()
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

        /** `scheme://anything-but-slash@` — the userinfo of a URL, masked before a message is logged. */
        private val USERINFO = Regex("([A-Za-z][A-Za-z0-9+.-]*://)[^/\\s@]+@")

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
