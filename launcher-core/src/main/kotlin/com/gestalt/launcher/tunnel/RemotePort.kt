/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.tunnel

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * The destination ("remote") port a port-forward command names, and the command re-pointed at another.
 *
 * An SSM port-forward names it as `portNumber=<n>` (`aws ssm start-session … --parameters
 * "localPortNumber=…,portNumber=<n>"`), either in the command line itself or inside the batch file the
 * command runs. When the server side refuses that port ([TunnelManager.destinationRefused]) the launcher
 * tries the engine's usual ports instead — [FALLBACKS], in order — and remembers the one that answers.
 *
 * A re-pointed script is a copy under the launcher's cache dir with only that number changed, run in the
 * ORIGINAL file's folder so its relative paths still work; the user's file is never touched. A command
 * that names no `portNumber=` is left alone: there is nothing the launcher knows how to change.
 */
object RemotePort {

    /** The engine's usual https ports, tried in this order after the command's own port is refused. */
    val FALLBACKS: List<Int> = listOf(8443, 8000)

    /**
     * The remote port in either form the AWS CLI takes `--parameters` in: shorthand `portNumber=<n>` as its
     * own word (`localPortNumber=` must not match — it ends in the same letters), or JSON
     * `"portNumber":["<n>"]` — quotes possibly backslash-escaped inside a batch file, spaces allowed
     * (`"localPortNumber"` cannot match: a quote must sit right before `portNumber`).
     */
    private val PARAM = Regex("(?i)(\\bportNumber=|\\\\?\"portNumber\\\\?\"\\s*:\\s*\\[\\s*\\\\?\")(\\d{1,5})")
    /** A batch file's own path and folder, which a copy would resolve to the cache dir: written out as the original's. */
    private val SELF_DIR = Regex("(?i)%~dp0")
    private val SELF_PATH = Regex("(?i)%~f0")
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
        return portIn(text)
    }

    /** What the command runs re-pointed at [port]: the edited command, or a copy of its script, and where to run it. */
    class Repointed(val command: String, val workingDir: Path?, val original: Int)

    /**
     * [command] re-pointed at [port], or null when the command names no port (or already names [port]).
     * A script copy is written to `<cacheDir>/<connectionId>-<port>.<ext>` — byte for byte the original
     * except the port number, `%~dp0` / `%~f0` (which would name the cache dir) spelled out as the
     * original's folder and path, and the original's permissions — atomically, and left alone when the
     * copy already has those bytes (the forward may be running it).
     */
    fun repointed(command: String, port: Int, connectionId: String, cacheDir: Path): Repointed? {
        val script = scriptOf(command)
        val text = textOf(command) ?: return null
        val original = portIn(text) ?: return null
        if (original == port) return null
        var edited = PARAM.replace(text) { m -> m.groupValues[1] + port }
        if (script == null) return Repointed(edited, TunnelManager.workingDirFor(command), original)
        val folder = script.parent.toString().let { if (it.endsWith(FS)) it else it + FS }
        edited = SELF_DIR.replace(edited) { folder }.let { t -> SELF_PATH.replace(t) { script.toString() } }
        val ext = script.fileName.toString().substringAfterLast('.')
        val copy = cacheDir.resolve("${safe(connectionId)}-$port.$ext")
        val bytes = edited.toByteArray(SCRIPT_CHARSET)
        val same = runCatching { Files.isRegularFile(copy) && Files.readAllBytes(copy).contentEquals(bytes) }.getOrDefault(false)
        if (!same) {
            Files.createDirectories(cacheDir)
            val tmp = cacheDir.resolve(copy.fileName.toString() + ".tmp")
            Files.write(tmp, bytes)
            if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                runCatching { Files.setPosixFilePermissions(tmp, Files.getPosixFilePermissions(script)) } // a .sh stays executable
            }
            Files.move(tmp, copy, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
        return Repointed(copy.toString(), script.parent, original)
    }

    /** Remove what [repointed] wrote for [connectionId] — on connection delete. */
    fun forget(connectionId: String, cacheDir: Path) {
        val prefix = safe(connectionId) + "-"
        runCatching {
            if (!Files.isDirectory(cacheDir)) return
            Files.list(cacheDir).use { files ->
                files.filter { it.fileName.toString().startsWith(prefix) }.forEach { runCatching { Files.deleteIfExists(it) } }
            }
        }
    }

    private fun textOf(command: String): String? {
        val script = scriptOf(command) ?: return command
        return runCatching { String(Files.readAllBytes(script), SCRIPT_CHARSET) }.getOrNull()
    }

    private fun portIn(text: String): Int? = PARAM.find(text)?.groupValues?.get(2)?.toIntOrNull()?.takeIf { it in 1..65535 }

    /** One byte per char both ways, so the copy is the original's bytes with only the edits above — whatever code page cmd.exe reads it in. */
    private val SCRIPT_CHARSET: Charset = StandardCharsets.ISO_8859_1
    private val FS: String = java.io.File.separator

    private fun safe(id: String): String = id.replace(Regex("[^A-Za-z0-9._-]"), "_").take(64).ifEmpty { "tunnel" }
}
