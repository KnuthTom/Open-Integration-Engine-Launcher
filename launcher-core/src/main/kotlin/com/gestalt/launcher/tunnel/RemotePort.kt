/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.tunnel

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * The destination ("remote") port a port-forward command names — read, never changed.
 *
 * An SSM port-forward names it as `portNumber=<n>` (`aws ssm start-session … --parameters
 * "localPortNumber=…,portNumber=<n>"`), either in the command line itself or inside the batch file the
 * command runs. The launcher reads it to record it with the forward (`tunnel.open … remotePort=`) and to
 * name it when the server side refuses it ([TunnelManager.destinationRefused]); the command itself runs
 * exactly as the user wrote it. A command that names no `portNumber=` simply has no known remote port.
 */
object RemotePort {

    /**
     * The remote port in either form the AWS CLI takes `--parameters` in: shorthand `portNumber=<n>` as its
     * own word (`localPortNumber=` must not match — it ends in the same letters), or JSON
     * `"portNumber":["<n>"]` — quotes possibly backslash-escaped inside a batch file, spaces allowed
     * (`"localPortNumber"` cannot match: a quote must sit right before `portNumber`).
     */
    private val PARAM = Regex("(?i)(\\bportNumber=|\\\\?\"portNumber\\\\?\"\\s*:\\s*\\[\\s*\\\\?\")(\\d{1,5})")
    private val SCRIPT_EXTENSIONS = setOf("bat", "cmd", "sh", "ps1")

    /** The script file a command names, when the command is exactly a path to one (quotes allowed), else null. */
    fun scriptOf(command: String): Path? {
        val bare = command.trim().trim('"')
        if (bare.isEmpty()) return null
        val path = runCatching { Path.of(bare) }.getOrNull() ?: return null
        if (!path.isAbsolute) return null
        val name = path.fileName?.toString() ?: return null
        if (name.substringAfterLast('.', "").lowercase() !in SCRIPT_EXTENSIONS) return null
        return path.takeIf { runCatching { Files.isRegularFile(it) }.getOrDefault(false) }
    }

    /** The remote port [command] (or the script it runs) names, or null when none is recognised. */
    fun of(command: String): Int? {
        val text = textOf(command) ?: return null
        return PARAM.find(text)?.groupValues?.get(2)?.toIntOrNull()?.takeIf { it in 1..65535 }
    }

    private fun textOf(command: String): String? {
        val script = scriptOf(command) ?: return command
        return runCatching { String(Files.readAllBytes(script), SCRIPT_CHARSET) }.getOrNull()
    }

    /** One byte per char, whatever code page cmd.exe reads the file in. */
    private val SCRIPT_CHARSET: Charset = StandardCharsets.ISO_8859_1
}
