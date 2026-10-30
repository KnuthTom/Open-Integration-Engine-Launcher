/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.launch

import com.gestalt.launcher.gate.GestaltTrust
import com.gestalt.launcher.model.Connection
import com.gestalt.launcher.testutil.TestArtifacts
import com.gestalt.trust.TrustStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Path

/**
 * Auto-login rides as the Administrator's 3rd and 4th program arguments — the mechanism the client
 * itself defines (and MCAL used). The plan stays secret-free; the credentials join the command only at
 * the spawn, only in the stock argument shape, and the password array is zeroed whatever happens.
 */
class AutoLoginArgsTest {

    private val conn = Connection(id = "c", name = "C", serverUrl = "https://mirth.example.internal:8443")

    private fun plan(args: List<String>) = LaunchPlan(
        connection = conn,
        version = "4.4.2",
        mainClass = "com.mirth.connect.client.ui.Mirth",
        admitted = emptyList(),
        appArgs = args,
        command = listOf("java", "-cp", "client.jar", "com.mirth.connect.client.ui.Mirth") + args,
    )

    private fun core(dir: Path) = LauncherCore(
        trustStore = TrustStore.of(TestArtifacts.selfSignedCa("CN=Pretend Root,O=Pretend CA,C=GB").cert),
        signerPins = GestaltTrust.OFFICIAL_SIGNER_PINS,
        cacheDir = dir.resolve("cache"),
        fetcherFactory = { throw IOException("offline") },
        sleeper = {},
    )

    @Test
    @DisplayName("stock descriptor (serverUrl, version): username and password are appended as arguments 3 and 4; the plan keeps no secret")
    fun appendedInTheStockShape(@TempDir dir: Path) {
        val p = plan(listOf("https://mirth.example.internal:8443", "4.4.2"))
        val password = "synthetic-pw".toCharArray()

        val cmd = core(dir).commandWithLogin(p, AutoLogin("admin", password))

        assertEquals(p.command + listOf("admin", "synthetic-pw"), cmd)
        assertEquals("https://mirth.example.internal:8443", cmd[cmd.size - 4])
        assertEquals("4.4.2", cmd[cmd.size - 3])
        assertFalse(p.command.any { it.contains("synthetic-pw") }) { "the plan itself never carries the password" }
        assertFalse(p.appArgs.any { it.contains("synthetic-pw") })
    }

    @Test
    @DisplayName("no credentials, a blank username, or an empty password: the command is the plan's, untouched")
    fun absentOrEmptyCredentialsChangeNothing(@TempDir dir: Path) {
        val p = plan(listOf("https://mirth.example.internal:8443", "4.4.2"))
        val c = core(dir)
        assertSame(p.command, c.commandWithLogin(p, null))
        assertSame(p.command, c.commandWithLogin(p, AutoLogin("  ", "pw".toCharArray())))
        assertSame(p.command, c.commandWithLogin(p, AutoLogin("admin", CharArray(0))))
    }

    @Test
    @DisplayName("a descriptor with any other argument shape gets no credentials — positions 3 and 4 would mean something else")
    fun unexpectedArgumentShapeSkipsAutoLogin(@TempDir dir: Path) {
        val c = core(dir)
        val one = plan(listOf("https://mirth.example.internal:8443"))
        val three = plan(listOf("https://mirth.example.internal:8443", "4.4.2", "extra"))
        assertSame(one.command, c.commandWithLogin(one, AutoLogin("admin", "pw".toCharArray())))
        assertSame(three.command, c.commandWithLogin(three, AutoLogin("admin", "pw".toCharArray())))
    }

    @Test
    @DisplayName("launch() zeroes the password array even when the launch fails before a spawn")
    fun passwordIsZeroedOnFailure(@TempDir dir: Path) {
        val password = "synthetic-pw".toCharArray()

        assertThrows(Exception::class.java) { core(dir).launch(conn, autoLogin = AutoLogin("admin", password)) }

        assertTrue(password.all { it == '\u0000' }) { "password must be zeroed: ${String(password)}" }
    }

    @Test
    @DisplayName("AutoLogin never prints its password")
    fun toStringRedacts() {
        val a = AutoLogin("admin", "synthetic-pw".toCharArray())
        assertFalse(a.toString().contains("synthetic-pw"))
        assertTrue(a.toString().contains("admin"))
    }
}
