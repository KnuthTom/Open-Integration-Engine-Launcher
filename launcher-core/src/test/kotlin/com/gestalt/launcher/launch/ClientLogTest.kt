/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.launch

import com.gestalt.launcher.model.Connection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.time.ZoneOffset

/**
 * The per-launch client log: named after the connection, headed with what was launched — never with the
 * command line, which carries the auto-login credentials — and bounded to the newest few files.
 */
class ClientLogTest {

    private val password = "synthetic-pw-s3cret"

    private fun plan(name: String = "Example Lab", id: String = "c1") = LaunchPlan(
        connection = Connection(id = id, name = name, serverUrl = "https://localhost:31065"),
        version = "4.4.2",
        mainClass = "com.mirth.connect.client.ui.Mirth",
        admitted = emptyList(),
        appArgs = listOf("https://localhost:31065", "4.4.2"),
        // As `commandWithLogin` would spawn it: the credentials ride on the command line.
        command = listOf("C:\\java\\bin\\javaw.exe", "-cp", "x.jar", "com.mirth.connect.client.ui.Mirth", "https://localhost:31065", "4.4.2", "tech", password),
    )

    @Test
    @DisplayName("creates <name>-<stamp>.log with a header that names the launch but never the command line")
    fun headerWithoutSecrets(@TempDir dir: Path) {
        val at = Instant.parse("2026-10-30T14:32:05Z")
        val file = ClientLog.open(dir.resolve("client"), plan(), at, ZoneOffset.UTC)

        assertNotNull(file)
        assertEquals("Example_Lab-20261030-143205.log", file!!.fileName.toString())
        val text = Files.readString(file)
        assertTrue(text.contains("Example Lab")) { text }
        assertTrue(text.contains("https://localhost:31065")) { text }
        assertTrue(text.contains("version: 4.4.2")) { text }
        assertTrue(text.contains("com.mirth.connect.client.ui.Mirth")) { text }
        assertTrue(text.contains("javaw.exe")) { text }
        assertFalse(text.contains(password)) { "the auto-login password must never reach a log: $text" }
        assertFalse(text.contains("tech")) { "nor the username: $text" }
    }

    @Test
    @DisplayName("two launches in the same second get distinct files")
    fun sameSecondDoesNotCollide(@TempDir dir: Path) {
        val at = Instant.parse("2026-10-30T14:32:05Z")
        val first = ClientLog.open(dir, plan(), at, ZoneOffset.UTC)!!
        val second = ClientLog.open(dir, plan(), at, ZoneOffset.UTC)!!
        assertEquals("Example_Lab-20261030-143205.log", first.fileName.toString())
        assertEquals("Example_Lab-20261030-143205-2.log", second.fileName.toString())
    }

    @Test
    @DisplayName("a name with nothing usable falls back to the connection id")
    fun unusableNameFallsBackToId() {
        assertEquals("c1", ClientLog.stem(plan(name = "///")))
        assertEquals("Lab_Alpha", ClientLog.stem(plan(name = " Lab/Alpha ")))
    }

    @Test
    @DisplayName("keeps only the newest KEEP logs; the file just opened is always kept")
    fun prunesOldest(@TempDir dir: Path) {
        val base = Instant.parse("2026-10-01T00:00:00Z")
        for (i in 0 until ClientLog.KEEP + 5) {
            val f = Files.writeString(dir.resolve("old-$i.log"), "x")
            Files.setLastModifiedTime(f, FileTime.from(base.plusSeconds(i.toLong())))
        }
        Files.writeString(dir.resolve("notes.txt"), "not a log")

        val fresh = ClientLog.open(dir, plan(), Instant.parse("2026-10-30T14:32:05Z"), ZoneOffset.UTC)!!
        Files.setLastModifiedTime(fresh, FileTime.from(base.minusSeconds(1))) // even if its mtime is odd

        ClientLog.prune(dir, ClientLog.KEEP, keepAlso = fresh)

        val remaining = Files.list(dir).use { s -> s.map { it.fileName.toString() }.sorted().toList() }
        assertTrue(fresh.fileName.toString() in remaining) { "the fresh log must survive: $remaining" }
        assertTrue("notes.txt" in remaining) { "only *.log files are pruned: $remaining" }
        assertEquals(ClientLog.KEEP, remaining.count { it.endsWith(".log") }) { "$remaining" }
        assertFalse("old-0.log" in remaining) { "the oldest must go: $remaining" }
        assertTrue("old-${ClientLog.KEEP + 4}.log" in remaining) { "the newest must stay: $remaining" }
    }

    @Test
    @DisplayName("an unwritable directory yields null rather than a failed launch")
    fun unwritableDirectoryIsNull(@TempDir dir: Path) {
        val blocker = Files.writeString(dir.resolve("client"), "a file where the directory should be")
        assertNull(ClientLog.open(blocker, plan()))
    }
}
