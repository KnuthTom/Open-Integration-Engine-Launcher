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
 * Everything here is pure (a writability probe + script text), so it is unit-testable; the app spawns
 * the script and exits. Portable updates are attempted only when [isAppDirWritable]; otherwise the app
 * falls back to notify-only (show the release + a download link).
 */
object PortableSwap {

    fun isAppDirWritable(appDir: Path): Boolean =
        runCatching { Files.isDirectory(appDir) && Files.isWritable(appDir) }.getOrDefault(false)

    /**
     * A Windows PowerShell helper that, after the launching app (PID [pid]) exits, mirrors the verified
     * [stagedImageDir] over [appDir] and relaunches [relaunchExe], then deletes itself. [stagedImageDir]
     * and the script itself must live OUTSIDE [appDir] (e.g. in temp) so the mirror does not clobber them.
     *
     * [preserveSubdir] (the portable `data` folder by default) is EXCLUDED from the mirror so an
     * in-place update never deletes the user's portable state.
     */
    fun windowsSwapScript(
        appDir: Path,
        stagedImageDir: Path,
        pid: Long,
        relaunchExe: Path,
        preserveSubdir: String = "data",
    ): String {
        val app = appDir.toAbsolutePath().toString()
        val staged = stagedImageDir.toAbsolutePath().toString()
        val exe = relaunchExe.toAbsolutePath().toString()
        return """
            ${'$'}ErrorActionPreference = 'Stop'
            # Wait for the launching app to exit so its files are unlocked.
            try { Wait-Process -Id $pid -Timeout 120 } catch {}
            # Mirror the verified new image over the portable app folder, PRESERVING ./$preserveSubdir.
            robocopy "$staged" "$app" /MIR /XD "$app\$preserveSubdir" /NFL /NDL /NJH /NJS /NP | Out-Null
            # Relaunch and clean up.
            Start-Process -FilePath "$exe"
            Remove-Item -LiteralPath "$staged" -Recurse -Force -ErrorAction SilentlyContinue
            Remove-Item -LiteralPath ${'$'}MyInvocation.MyCommand.Path -Force -ErrorAction SilentlyContinue
        """.trimIndent() + "\n"
    }
}
