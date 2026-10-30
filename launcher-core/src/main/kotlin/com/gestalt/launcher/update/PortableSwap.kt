/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.update

import java.nio.file.Files
import java.nio.file.Path

/**
 * Helper for the **portable** in-place update path: replacing a running portable app's files cannot be
 * done by the app itself (its own executable is locked), so it stages a verified new copy and hands the
 * swap to a small external helper that runs after the app exits.
 *
 * Everything here is pure (a writability probe, an image-root finder, and script text), so it is
 * unit-testable; the app spawns the script and exits. Portable updates are attempted only when
 * [isAppDirWritable]; otherwise the app falls back to notify-only (show the release + a download link).
 */
object PortableSwap {

    fun isAppDirWritable(appDir: Path): Boolean =
        runCatching { Files.isDirectory(appDir) && Files.isWritable(appDir) }.getOrDefault(false)

    /**
     * The directory inside an extracted update archive that holds the app image — the one containing
     * [exeName] — searched at the root and one level down (the portable zip wraps the image in a folder
     * named after the package). Null when the archive holds no such executable; the caller must then
     * refuse to swap rather than mirror the wrong directory over the app.
     */
    fun findImageRoot(extractedDir: Path, exeName: String): Path? {
        if (Files.isRegularFile(extractedDir.resolve(exeName))) return extractedDir
        return runCatching {
            Files.list(extractedDir).use { stream ->
                stream.filter { Files.isDirectory(it) && Files.isRegularFile(it.resolve(exeName)) }
                    .findFirst().orElse(null)
            }
        }.getOrNull()
    }

    /**
     * A Windows PowerShell helper that, after the launching app (PID [pid]) exits, replaces the portable
     * image in [appDir] with the verified [stagedImageDir] and relaunches [relaunchExe], then deletes
     * itself. [stagedImageDir] and the script must live OUTSIDE [appDir] (e.g. in temp).
     *
     * Safety properties, in order:
     *  1. Never touches a file while the app is still running — waits up to two minutes, then aborts.
     *  2. Refuses to proceed unless the staged image actually contains the executable (guards against
     *     mirroring an empty or wrongly nested directory over the app).
     *  3. Backs up the current image beside the app folder before changing anything; a failed backup
     *     aborts with the app untouched.
     *  4. Mirrors with bounded retries (`/R:5 /W:2`, not robocopy's default million retries); on failure
     *     or a missing executable afterwards, restores the backup.
     *  5. [preserveSubdir] (the portable `data` folder) is excluded from every copy, so an update never
     *     deletes the user's portable state.
     * Paths are emitted as single-quoted PowerShell literals (embedded quotes doubled), so `$`, backticks
     * and spaces in a folder name cannot break or inject into the script.
     */
    fun windowsSwapScript(
        appDir: Path,
        stagedImageDir: Path,
        pid: Long,
        relaunchExe: Path,
        preserveSubdir: String = "data",
    ): String {
        fun literal(s: String) = "'" + s.replace("'", "''") + "'"
        val app = literal(appDir.toAbsolutePath().toString())
        val staged = literal(stagedImageDir.toAbsolutePath().toString())
        val exe = literal(relaunchExe.toAbsolutePath().toString())
        val exeName = literal(relaunchExe.fileName.toString())
        val preserve = literal(preserveSubdir)
        // `§` stands in for `$` so the PowerShell text can be written without Kotlin template escapes.
        return """
            §ErrorActionPreference = 'Continue'
            §app = $app
            §staged = $staged
            §exe = $exe
            §exeName = $exeName
            §preserve = Join-Path §app $preserve
            §backup = Join-Path (Split-Path §app -Parent) ((Split-Path §app -Leaf) + '.previous')
            §rc = @('/R:5', '/W:2', '/NFL', '/NDL', '/NJH', '/NJS', '/NP')
            function Abort(§msg) {
                Write-Host ('update aborted: ' + §msg)
                Remove-Item -LiteralPath §staged -Recurse -Force -ErrorAction SilentlyContinue
                exit 1
            }
            # 1. The app's own files are locked until it exits; never touch them while it is alive.
            §p = Get-Process -Id $pid -ErrorAction SilentlyContinue
            if (§p) {
                §p.WaitForExit(120000) | Out-Null
                if (-not §p.HasExited) { Abort 'the launcher is still running' }
            }
            # 2. The staged image must contain the executable we are about to replace.
            if (-not (Test-Path -LiteralPath (Join-Path §staged §exeName))) { Abort ('staged image does not contain ' + §exeName) }
            # 3. Back up the current image (everything but the preserved data folder) before changing anything.
            Remove-Item -LiteralPath §backup -Recurse -Force -ErrorAction SilentlyContinue
            robocopy §app §backup /E /XD §preserve @rc | Out-Null
            if (§LASTEXITCODE -ge 8) { Abort 'could not back up the current version' }
            # 4. Mirror the verified image over the app folder, preserving the data folder; roll back on failure.
            robocopy §staged §app /MIR /XD §preserve @rc | Out-Null
            §copyRc = §LASTEXITCODE
            if (§copyRc -ge 8 -or -not (Test-Path -LiteralPath §exe)) {
                robocopy §backup §app /MIR /XD §preserve @rc | Out-Null
                Abort ('copy failed (robocopy ' + §copyRc + '); the previous version was restored')
            }
            # 5. Relaunch and clean up.
            Start-Process -FilePath §exe -WorkingDirectory §app
            Remove-Item -LiteralPath §staged -Recurse -Force -ErrorAction SilentlyContinue
            Remove-Item -LiteralPath §backup -Recurse -Force -ErrorAction SilentlyContinue
            Remove-Item -LiteralPath §MyInvocation.MyCommand.Path -Force -ErrorAction SilentlyContinue
        """.trimIndent().replace('§', '$') + "\n"
    }
}
