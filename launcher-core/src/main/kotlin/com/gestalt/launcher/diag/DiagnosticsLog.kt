/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.diag

import com.gestalt.launcher.AppPaths
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Appends full Test-connection detail to `logs/diagnostics.txt` so a failing run can be sent back for
 * support. Contains only host/port/cert/HTTP-status detail — never credentials (passwords live solely
 * in the OS keychain and are never read here). The file is bounded so it cannot grow without limit.
 */
object DiagnosticsLog {

    const val FILE_NAME = "diagnostics.txt"
    private const val MAX_BYTES = 256L * 1024

    /** Append [report]'s full text to `logs/diagnostics.txt` and return the file path. */
    fun append(report: ConnectionTestReport, dir: Path = AppPaths.logDir): Path {
        Files.createDirectories(dir)
        val file = dir.resolve(FILE_NAME)
        val block = buildString {
            appendLine("================================================================")
            append(report.toText())
            appendLine()
        }
        Files.write(
            file,
            block.toByteArray(Charsets.UTF_8),
            StandardOpenOption.CREATE, StandardOpenOption.APPEND,
        )
        trimIfTooLarge(file)
        return file
    }

    /** Keep the file from growing unbounded: when it exceeds the cap, retain only the most recent tail. */
    private fun trimIfTooLarge(file: Path) {
        runCatching {
            if (Files.size(file) <= MAX_BYTES) return
            val bytes = Files.readAllBytes(file)
            val cut = (bytes.size - MAX_BYTES.toInt()).coerceAtLeast(0)
            val keep = bytes.copyOfRange(cut, bytes.size)
            // Drop the (possibly partial / UTF-8-split) leading line so the retained tail starts clean.
            val nl = keep.indexOf('\n'.code.toByte())
            val clean = if (nl in 0 until keep.size - 1) keep.copyOfRange(nl + 1, keep.size) else keep
            Files.write(file, clean)
        }
    }
}
