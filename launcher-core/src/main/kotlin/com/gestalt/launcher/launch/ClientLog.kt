/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.launch

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Where the launched Administrator's own console output goes. The client logs its failures — "error
 * connecting to the server", a TLS handshake it didn't like, a class it couldn't load — to its stdout /
 * stderr, and a windowless launcher (`javaw`, the installed app) has no console for that to land in, so
 * the only evidence of why a login failed would be lost. One file per launch under `logs/client/`,
 * named after the connection and the start time; the newest [KEEP] are kept.
 *
 * The header never includes the command line: with auto-login the credentials ride on it.
 */
internal object ClientLog {
    internal const val KEEP = 20
    private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    private val UNSAFE = Regex("[^A-Za-z0-9._-]")

    /**
     * Create the log file for [plan] with its header written, pruning older files past [KEEP]. Null when
     * the directory can't be written — the caller then lets the child inherit the launcher's console, as
     * before, rather than fail a launch over a log file.
     */
    fun open(dir: Path, plan: LaunchPlan, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): Path? =
        runCatching {
            Files.createDirectories(dir)
            val file = freshFile(dir, "${stem(plan)}-${STAMP.withZone(zone).format(now)}")
            Files.write(
                file,
                listOf(
                    "# Administrator client output — ${plan.connection.name}",
                    "# started: $now",
                    "# server: ${plan.connection.serverUrl}   version: ${plan.version}",
                    "# java: ${plan.command.firstOrNull() ?: "-"}   main: ${plan.mainClass}   jars: ${plan.admitted.size}",
                    "",
                ),
                Charsets.UTF_8,
                StandardOpenOption.WRITE, StandardOpenOption.APPEND,
            )
            prune(dir, KEEP, keepAlso = file)
            file
        }.getOrNull()

    /** The connection's name as a file-name stem (its id when the name has nothing usable). */
    internal fun stem(plan: LaunchPlan): String =
        plan.connection.name.replace(UNSAFE, "_").trim('_').take(40).ifBlank { plan.connection.id.replace(UNSAFE, "_").take(12) }

    /** `<base>.log`, or `<base>-2.log`, `-3`… when a launch in the same second already took it. */
    private fun freshFile(dir: Path, base: String): Path {
        var n = 1
        while (true) {
            val candidate = dir.resolve(if (n == 1) "$base.log" else "$base-$n.log")
            try {
                Files.createFile(candidate)
                return candidate
            } catch (taken: java.nio.file.FileAlreadyExistsException) {
                n++
            }
        }
    }

    /** Delete the oldest `*.log` files in [dir] beyond the newest [keep]; [keepAlso] is never deleted. */
    internal fun prune(dir: Path, keep: Int = KEEP, keepAlso: Path? = null) {
        val logs = runCatching {
            Files.list(dir).use { stream ->
                stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".log") }
                    .toList()
            }
        }.getOrDefault(emptyList())
        if (logs.size <= keep) return
        val byAge = logs.sortedWith(
            compareByDescending<Path> { runCatching { Files.getLastModifiedTime(it).toMillis() }.getOrDefault(0L) }
                .thenByDescending { it.fileName.toString() },
        )
        for (old in byAge.drop(keep)) {
            if (keepAlso != null && old == keepAlso) continue
            runCatching { Files.deleteIfExists(old) }
        }
    }
}
