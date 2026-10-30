/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.update

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** The portable swap helper must never be able to delete the app: it is pure text here, so pin its safety properties. */
class PortableSwapTest {

    private fun script(appDir: Path, staged: Path): String =
        PortableSwap.windowsSwapScript(appDir, staged, pid = 4242, relaunchExe = appDir.resolve("OpenIntegrationEngineLauncher.exe"))

    @Test
    @DisplayName("paths are single-quoted literals: spaces, \$, backticks and quotes in a folder name cannot break or inject")
    fun pathsAreQuotedLiterals(@TempDir dir: Path) {
        val app = dir.resolve("Tom's Apps\$weird`dir")
        val staged = dir.resolve("staging")
        val s = script(app, staged)
        val expectedApp = "'" + app.toAbsolutePath().toString().replace("'", "''") + "'"
        assertTrue(s.contains("\$app = $expectedApp")) { s }
        assertFalse(s.contains("\"$app")) { "no double-quoted (interpolating) path literals" }
        assertTrue(s.contains("\$exeName = 'OpenIntegrationEngineLauncher.exe'"))
        assertTrue(s.contains("Get-Process -Id 4242"))
    }

    @Test
    @DisplayName("the script waits for the app, verifies the staged image, backs up, bounds retries, and rolls back")
    fun safetyPropertiesPresent(@TempDir dir: Path) {
        val s = script(dir.resolve("app"), dir.resolve("staged"))
        // 1. never touch a running app
        assertTrue(s.contains("WaitForExit(120000)"))
        assertTrue(s.contains("if (-not \$p.HasExited) { Abort"))
        // 2. the staged image must contain the exe before anything is mirrored
        assertTrue(s.contains("Test-Path -LiteralPath (Join-Path \$staged \$exeName)"))
        // 3. backup before change, 4. bounded retries + rollback
        assertTrue(s.indexOf("robocopy \$app \$backup /E") < s.indexOf("robocopy \$staged \$app /MIR")) { "backup precedes the mirror" }
        assertTrue(s.contains("'/R:5', '/W:2'")) { "robocopy must not use its default million retries" }
        assertTrue(s.contains("robocopy \$backup \$app /MIR")) { "a failed copy restores the backup" }
        assertTrue(s.contains("LASTEXITCODE -ge 8")) { "robocopy exit codes >= 8 are failures" }
        // 5. the data folder is excluded from every copy
        assertEquals(3, Regex("robocopy .*?/XD \\\$preserve").findAll(s).count()) { "backup, mirror and rollback all preserve data" }
        assertFalse(s.contains("GestaltCloudConnect")) { "no legacy product name" }
    }

    @Test
    @DisplayName("findImageRoot locates the directory holding the executable at the root or one level down, else null")
    fun findImageRoot(@TempDir dir: Path) {
        val exe = "OpenIntegrationEngineLauncher.exe"
        // Archive layout A: image at the root.
        val flat = Files.createDirectories(dir.resolve("flat"))
        Files.writeString(flat.resolve(exe), "x")
        assertEquals(flat, PortableSwap.findImageRoot(flat, exe))

        // Layout B: image wrapped in a folder named after the package (the portable zip's shape).
        val wrapped = Files.createDirectories(dir.resolve("wrapped"))
        val inner = Files.createDirectories(wrapped.resolve("OpenIntegrationEngineLauncher"))
        Files.writeString(inner.resolve(exe), "x")
        assertEquals(inner, PortableSwap.findImageRoot(wrapped, exe))

        // Layout C: nothing usable — the caller must refuse to swap.
        val empty = Files.createDirectories(dir.resolve("empty"))
        Files.createDirectories(empty.resolve("something-else"))
        assertNull(PortableSwap.findImageRoot(empty, exe))
    }
}
