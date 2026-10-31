/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.tunnel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class RemotePortTest {

    private val inline = "aws ssm start-session --target mi-0123456789abcdef0 --document-name AWS-StartPortForwardingSession --parameters \"localPortNumber=31065,portNumber=8000\""

    @Test
    @DisplayName("the remote port is read from an SSM command line (case-insensitively); a command that names none gives null")
    fun readsThePort() {
        assertEquals(8000, RemotePort.of(inline))
        assertEquals(8443, RemotePort.of(inline.replace("portNumber=8000", "PORTNUMBER=8443")))
        assertNull(RemotePort.of("ssh -N -L 31065:engine.example.internal:8443 bastion.example.internal"))
        assertNull(RemotePort.of("forward.bat"))
        assertNull(RemotePort.of(""))
    }

    @Test
    @DisplayName("an inline command is re-pointed in place; the same port is no change")
    fun repointsAnInlineCommand(@TempDir dir: Path) {
        val r = RemotePort.repointed(inline, 8443, "c1", dir.resolve("cache"))!!
        assertEquals(inline.replace("portNumber=8000", "portNumber=8443"), r.command)
        assertEquals(8000, r.original)
        assertNull(r.workingDir)
        assertNull(RemotePort.repointed(inline, 8000, "c1", dir.resolve("cache"))) { "already that port" }
        assertNull(RemotePort.repointed("forward.bat", 8443, "c1", dir.resolve("cache"))) { "nothing to change" }
    }

    @Test
    @DisplayName("a batch file is re-pointed as a copy in the cache dir, run in the ORIGINAL file's folder; the user's file is untouched")
    fun repointsAScriptAsACopy(@TempDir dir: Path) {
        val scripts = Files.createDirectories(dir.resolve("scripts"))
        val bat = scripts.resolve("forward.bat")
        val content = "@echo off\r\ncd /d %~dp0\r\n$inline\r\n"
        Files.writeString(bat, content)
        assertEquals(8000, RemotePort.of(bat.toString()))
        assertEquals(8000, RemotePort.of("\"$bat\"")) { "quotes allowed" }

        val cache = dir.resolve("cache").resolve("tunnel")
        val r = RemotePort.repointed(bat.toString(), 8443, "c1", cache)!!
        val copy = Path.of(r.command)
        assertEquals(cache, copy.parent)
        assertEquals("c1-8443.bat", copy.fileName.toString())
        val folder = scripts.toString() + java.io.File.separator
        assertEquals(content.replace("portNumber=8000", "portNumber=8443").replace("%~dp0", folder), Files.readString(copy)) { "the number changed, and %~dp0 spelled out as the original's folder" }
        val stamp = Files.getLastModifiedTime(copy)
        Thread.sleep(20)
        RemotePort.repointed(bat.toString(), 8443, "c1", cache)
        assertEquals(stamp, Files.getLastModifiedTime(copy)) { "an identical copy is left alone (the forward may be running it)" }
        RemotePort.forget("c1", cache)
        assertTrue(Files.notExists(copy)) { "forgotten with the connection" }
        assertEquals(scripts, r.workingDir) { "runs where the original lives, so its relative paths work" }
        assertEquals(8000, r.original)
        assertEquals(content, Files.readString(bat)) { "the user's file is never modified" }
        assertNotNull(RemotePort.scriptOf(bat.toString()))
        assertNull(RemotePort.scriptOf(scripts.resolve("missing.bat").toString()))
        assertNull(RemotePort.scriptOf("forward.bat")) { "relative: not a script the launcher would copy" }
        assertTrue(RemotePort.FALLBACKS == listOf(8443, 8000))
    }
}
