/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Unit-tests the Phase-4a data-dir resolution (override / portable / per-user). The logic is
 * exercised through [AppPaths.resolveDataDir], the pure resolver, because the public [AppPaths.dataDir]
 * caches its result for the whole process and so can't observe different inputs within one JVM.
 */
class AppPathsResolutionTest {

    @Test
    @DisplayName("explicit gestalt.home override wins over everything — even a portable sentinel")
    fun overrideWins(@TempDir tmp: Path) {
        Files.createFile(tmp.resolve(AppPaths.PORTABLE_SENTINEL)) // would otherwise trigger portable mode
        val resolved = AppPaths.resolveDataDir(
            override = tmp.resolve("forced").toString(),
            appExeDir = tmp,
            isWindows = true, isMac = false,
            localAppData = tmp.resolve("LocalAppData").toString(),
            xdgDataHome = null,
            userHome = tmp.toString(),
        )
        assertEquals(tmp.resolve("forced"), resolved)
    }

    @Test
    @DisplayName("portable: sentinel beside the executable redirects state to ./data beside the app")
    fun portableWhenSentinelPresent(@TempDir tmp: Path) {
        Files.createFile(tmp.resolve(AppPaths.PORTABLE_SENTINEL))
        val resolved = AppPaths.resolveDataDir(
            override = null, appExeDir = tmp,
            isWindows = true, isMac = false,
            localAppData = tmp.resolve("LocalAppData").toString(),
            xdgDataHome = null, userHome = tmp.toString(),
        )
        assertEquals(tmp.resolve(AppPaths.PORTABLE_DATA_DIR_NAME), resolved)
    }

    @Test
    @DisplayName("per-user (Windows): no sentinel -> %LOCALAPPDATA%/GestaltCloudConnect")
    fun perUserWindows(@TempDir tmp: Path) {
        val localAppData = tmp.resolve("LocalAppData")
        val resolved = AppPaths.resolveDataDir(
            override = null, appExeDir = tmp, // exe dir present, but NO sentinel
            isWindows = true, isMac = false,
            localAppData = localAppData.toString(),
            xdgDataHome = null, userHome = tmp.toString(),
        )
        assertEquals(localAppData.resolve(AppPaths.APP_DIR_NAME), resolved)
    }

    @Test
    @DisplayName("not packaged (no executable dir) -> portable off, falls through to per-user")
    fun perUserWhenNoExeDir(@TempDir tmp: Path) {
        val localAppData = tmp.resolve("LocalAppData")
        val resolved = AppPaths.resolveDataDir(
            override = null, appExeDir = null,
            isWindows = true, isMac = false,
            localAppData = localAppData.toString(),
            xdgDataHome = null, userHome = tmp.toString(),
        )
        assertEquals(localAppData.resolve(AppPaths.APP_DIR_NAME), resolved)
    }

    @Test
    @DisplayName("per-user (Linux): \$XDG_DATA_HOME/GestaltCloudConnect")
    fun perUserLinux(@TempDir tmp: Path) {
        val xdg = tmp.resolve("xdg")
        val resolved = AppPaths.resolveDataDir(
            override = null, appExeDir = tmp,
            isWindows = false, isMac = false,
            localAppData = null, xdgDataHome = xdg.toString(),
            userHome = tmp.toString(),
        )
        assertEquals(xdg.resolve(AppPaths.APP_DIR_NAME), resolved)
    }

    @Test
    @DisplayName("portableRoot is null without a sentinel and null with no executable dir")
    fun portableRootGuards(@TempDir tmp: Path) {
        assertNull(AppPaths.portableRoot(tmp))   // no sentinel
        assertNull(AppPaths.portableRoot(null))  // not packaged
    }
}
