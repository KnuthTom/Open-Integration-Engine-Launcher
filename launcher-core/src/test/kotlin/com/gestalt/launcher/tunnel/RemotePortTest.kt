/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.tunnel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
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
    @DisplayName("the JSON form of --parameters is read too — quotes escaped as a batch file has them, or bare, spaces allowed; localPortNumber never matches")
    fun readsTheJsonForm() {
        val escaped = """aws ssm start-session --target mi-0123456789abcdef0 --document-name AWS-StartPortForwardingSession --parameters "{\"portNumber\":[\"8000\"],\"localPortNumber\":[\"31074\"]}""""
        assertEquals(8000, RemotePort.of(escaped))
        val bare = """aws ssm start-session --parameters '{"localPortNumber":["31074"],"portNumber":["8443"]}'"""
        assertEquals(8443, RemotePort.of(bare))
        assertEquals(8000, RemotePort.of("""--parameters '{ "localPortNumber" : [ "31074" ], "portNumber" : [ "8000" ] }'"""))
        assertNull(RemotePort.of("""--parameters '{"localPortNumber":["31074"]}'""")) { "no remote port named" }
    }

    @Test
    @DisplayName("a batch file's port is read from the file (quotes around the path allowed); the file is only ever read")
    fun readsFromAScript(@TempDir dir: Path) {
        val scripts = Files.createDirectories(dir.resolve("scripts"))
        val bat = scripts.resolve("forward.bat")
        val content = "@echo off\r\ncd /d %~dp0\r\n$inline\r\n"
        Files.writeString(bat, content)
        assertEquals(8000, RemotePort.of(bat.toString()))
        assertEquals(8000, RemotePort.of("\"$bat\"")) { "quotes allowed" }
        assertEquals(content, Files.readString(bat)) { "the user's file is never modified" }
        assertNotNull(RemotePort.scriptOf(bat.toString()))
        assertNull(RemotePort.scriptOf(scripts.resolve("missing.bat").toString()))
        assertNull(RemotePort.scriptOf("forward.bat")) { "relative: not a script the launcher would read" }
        assertNull(RemotePort.of(scripts.resolve("missing.bat").toString())) { "a path to nothing names no port" }
    }
}
