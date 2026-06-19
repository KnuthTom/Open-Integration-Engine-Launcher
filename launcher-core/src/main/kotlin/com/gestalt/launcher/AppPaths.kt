/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher

import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/**
 * Filesystem locations for Open Integration Engine Launcher, resolved per operating system.
 *
 * Everything Gestalt persists (the connection list, the verified-artifact cache, DPAPI credential
 * blobs, `ui-prefs.properties`, and `logs/audit.log`) lives under [dataDir]. There are three layouts,
 * resolved in this order:
 *
 *  1. **Override** — `-Dgestalt.home=<path>` forces the root (used by tests so they never touch the
 *     real user profile).
 *  2. **Portable** — if a [PORTABLE_SENTINEL] file sits next to the executable, ALL state is redirected
 *     to a `./data` folder beside the app (§5.3/§7). This is what the portable app-image ships with, so
 *     it is portable out of the box; deleting the sentinel reverts that copy to the per-user layout.
 *  3. **Per-user** (default) — the conventional per-user app-data location for the platform.
 */
object AppPaths {
    const val APP_DIR_NAME = "GestaltCloudConnect"

    /** Pre-rename data-dir name; best-effort migrated to [APP_DIR_NAME] on first resolve. */
    private const val LEGACY_APP_DIR_NAME = "GestaltCommand"

    /** Marker file (next to the executable) that switches a copy into portable mode. */
    const val PORTABLE_SENTINEL = "gestalt-portable.txt"

    /** Folder (beside the executable) that holds all state in portable mode. */
    const val PORTABLE_DATA_DIR_NAME = "data"

    private val os = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
    val isWindows: Boolean = os.contains("win")
    val isMac: Boolean = os.contains("mac") || os.contains("darwin")
    val isLinux: Boolean = !isWindows && !isMac

    /** Root data directory, resolved once per process. */
    val dataDir: Path by lazy {
        resolveDataDir(
            override = System.getProperty("gestalt.home"),
            appExeDir = appExecutableDir(),
            isWindows = isWindows,
            isMac = isMac,
            localAppData = env("LOCALAPPDATA"),
            xdgDataHome = env("XDG_DATA_HOME"),
            userHome = home(),
        ).also { runCatching { Files.createDirectories(it) } }
    }

    /** True when the running app is in portable mode (sentinel found beside the executable). */
    val isPortable: Boolean by lazy {
        System.getProperty("gestalt.home") == null && portableRoot(appExecutableDir()) != null
    }

    /**
     * Pure resolution of the data-dir root (no directory creation). Extracted so the portable / per-user
     * / override branches are unit-testable without the process-wide caching of [dataDir]'s `lazy`.
     */
    internal fun resolveDataDir(
        override: String?,
        appExeDir: Path?,
        isWindows: Boolean,
        isMac: Boolean,
        localAppData: String?,
        xdgDataHome: String?,
        userHome: String,
    ): Path {
        if (override != null) return Path.of(override)

        portableRoot(appExeDir)?.let { return it }

        val base = when {
            isWindows -> Path.of(localAppData ?: "$userHome/AppData/Local", APP_DIR_NAME)
            isMac -> Path.of(userHome, "Library", "Application Support", APP_DIR_NAME)
            else -> Path.of(xdgDataHome ?: "$userHome/.local/share", APP_DIR_NAME)
        }
        migrateLegacyDir(base)
        return base
    }

    /** The portable `./data` root iff the sentinel sits next to the executable, else null. */
    internal fun portableRoot(appExeDir: Path?): Path? {
        val dir = appExeDir ?: return null
        val sentinel = dir.resolve(PORTABLE_SENTINEL)
        val present = runCatching { Files.isRegularFile(sentinel) }.getOrDefault(false)
        return if (present) dir.resolve(PORTABLE_DATA_DIR_NAME) else null
    }

    /**
     * The directory holding the installed/portable executable, from jpackage's `jpackage.app-path`
     * (the full path to the native launcher). Null when not running from a packaged image (e.g. a dev
     * `:app:run`), in which case portable mode is simply off. On macOS the launcher lives at
     * `Foo.app/Contents/MacOS/Foo`, so "beside the app" is the directory containing the `.app` bundle.
     */
    private fun appExecutableDir(): Path? {
        val exe = System.getProperty("jpackage.app-path")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
            ?: return null
        val normalized = exe.toString().replace('\\', '/')
        return if (normalized.contains("/Contents/MacOS/")) {
            // Foo -> MacOS -> Contents -> Foo.app -> directory containing Foo.app
            exe.parent?.parent?.parent?.parent
        } else {
            exe.parent
        }
    }

    /**
     * Best-effort one-time migration of a pre-rename `GestaltCommand` data dir to the new
     * `GestaltCloudConnect` location. Runs only for the per-user layout, when the new dir does not yet
     * exist and a legacy dir sits beside it; any failure is swallowed and the app falls back to a fresh
     * data dir. (Portable copies never migrate per-user state.)
     */
    private fun migrateLegacyDir(newBase: Path) {
        runCatching {
            if (Files.exists(newBase)) return
            val legacy = newBase.resolveSibling(LEGACY_APP_DIR_NAME)
            if (Files.isDirectory(legacy)) {
                runCatching { Files.move(legacy, newBase) }
            }
        }
    }

    val logDir: Path by lazy { sub("logs") }
    val cacheDir: Path by lazy { sub("cache") }
    val credentialsDir: Path by lazy { sub("credentials") }
    /** Per-user store for uploaded custom connection icons (`<id>.<ext>` + `index.json`). */
    val iconsDir: Path by lazy { sub("icons") }
    /**
     * Per-connection PKCS12 server truststores (`<connId>.p12`), each holding only the connection's
     * pinned server leaf so it can be handed to the launched Administrator's child JVM. These are PUBLIC
     * certificates (no private keys, no secrets); they are entirely separate from the code-signing
     * anchors in [com.gestalt.launcher.gate.GestaltTrust].
     */
    val truststoresDir: Path by lazy { sub("truststores") }
    val connectionsFile: Path by lazy { dataDir.resolve("connections.json") }

    private fun sub(name: String): Path =
        dataDir.resolve(name).also { runCatching { Files.createDirectories(it) } }

    private fun home(): String = System.getProperty("user.home") ?: "."
    private fun env(key: String): String? = System.getenv(key)?.takeIf { it.isNotBlank() }
}
