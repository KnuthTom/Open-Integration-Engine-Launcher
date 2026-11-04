/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.fx.model

import com.gestalt.launcher.AppPaths
import com.gestalt.trust.TrustMode
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties

/**
 * Launcher preferences — the window, the sidebar, the theme, the trust posture — in one properties
 * file under the data dir. Non-secret by construction (there is nothing here a password could ride
 * in). Shares its file and the keys it has in common with the Compose app's preferences, so a theme
 * or close-after-launch choice carries across while both UIs exist.
 *
 * Writes happen on discrete actions (a toggle, a sort change, closing the window), never per
 * keystroke, and are atomic (temp + rename).
 */
class LauncherPrefs(private val file: Path = AppPaths.dataDir.resolve("ui-prefs.properties")) {

    private val props = Properties()

    init {
        if (Files.isRegularFile(file)) runCatching { Files.newInputStream(file).use { props.load(it) } }
    }

    /** Kept for the Compose app, which shares this file; the JavaFX UI has one register and an [accent] instead. */
    var dark: Boolean
        get() = props.getProperty("mode", "Light").equals("Dark", ignoreCase = true)
        set(value) = put("mode", if (value) "Dark" else "Light")

    /** The launcher's colour — `#RRGGBB`; anything else reads as the default graphite. */
    var accent: String
        get() = props.getProperty("fx.accent")?.trim()?.uppercase()?.takeIf { ACCENT.matches(it) } ?: DEFAULT_ACCENT
        set(value) = put("fx.accent", value.trim().uppercase().takeIf { ACCENT.matches(it) })

    var closeAfterLaunch: Boolean
        get() = props.getProperty("closeAfterLaunch", "false").toBoolean()
        set(value) = put("closeAfterLaunch", value.toString())

    var sort: SortMode
        get() = runCatching { SortMode.valueOf(props.getProperty("fx.sort", SortMode.Manual.name)) }.getOrDefault(SortMode.Manual)
        set(value) = put("fx.sort", value.name)

    var foldersFirst: Boolean
        get() = props.getProperty("fx.foldersFirst", "false").toBoolean()
        set(value) = put("fx.foldersFirst", value.toString())

    var sidebarWidth: Double
        get() = props.getProperty("fx.sidebarWidth", "280").toDoubleOrNull() ?: 280.0
        set(value) = put("fx.sidebarWidth", value.toInt().toString())

    /** Window geometry; null until first saved. */
    var windowWidth: Double?
        get() = props.getProperty("fx.window.w")?.toDoubleOrNull()
        set(value) = put("fx.window.w", value?.toInt()?.toString())
    var windowHeight: Double?
        get() = props.getProperty("fx.window.h")?.toDoubleOrNull()
        set(value) = put("fx.window.h", value?.toInt()?.toString())
    var windowX: Double?
        get() = props.getProperty("fx.window.x")?.toDoubleOrNull()
        set(value) = put("fx.window.x", value?.toInt()?.toString())
    var windowY: Double?
        get() = props.getProperty("fx.window.y")?.toDoubleOrNull()
        set(value) = put("fx.window.y", value?.toInt()?.toString())
    var windowMaximized: Boolean
        get() = props.getProperty("fx.window.maximized", "false").toBoolean()
        set(value) = put("fx.window.maximized", value.toString())

    /** The code-signing posture the gate runs under (shared key with the Compose app). */
    var trustPosture: TrustMode
        get() = runCatching { TrustMode.valueOf(props.getProperty("trustPosture", TrustMode.PIN_KNOWN_SIGNERS.name)) }
            .getOrDefault(TrustMode.PIN_KNOWN_SIGNERS)
        set(value) = put("trustPosture", value.name)

    /** Extra publisher DNs the operator pinned, one per line (shared key with the Compose app). */
    var customSignerDns: List<String>
        get() = props.getProperty("customSignerDns", "").split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        set(value) = put("customSignerDns", value.joinToString("\n"))

    /** Extra CA anchor files, one path per line (shared key with the Compose app). */
    var customAnchorPaths: List<String>
        get() = props.getProperty("customAnchorPaths", "").split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        set(value) = put("customAnchorPaths", value.joinToString("\n"))

    /** Set several values, then write once. */
    fun batch(block: LauncherPrefs.() -> Unit) {
        suppressWrites = true
        try { block() } finally { suppressWrites = false }
        write()
    }

    private var suppressWrites = false

    private fun put(key: String, value: String?) {
        if (value == null) props.remove(key) else props.setProperty(key, value)
        if (!suppressWrites) write()
    }

    private fun write() {
        runCatching {
            Files.createDirectories(file.parent)
            val tmp = Files.createTempFile(file.parent, file.fileName.toString() + ".", ".tmp")
            try {
                Files.newOutputStream(tmp).use { props.store(it, "Launcher preferences (non-secret)") }
                runCatching { Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE) }
                    .recoverCatching { Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING) }
                    .getOrThrow()
            } finally {
                runCatching { Files.deleteIfExists(tmp) }
            }
        }
    }

    companion object {
        const val DEFAULT_ACCENT = "#2E3035"
        private val ACCENT = Regex("^#[0-9A-F]{6}$")
    }
}
