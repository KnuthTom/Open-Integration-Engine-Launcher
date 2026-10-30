/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** Smart single-field address detection for the connection form (§6.3.1 auto-detect rules). */
class ConnectionUrlDetectTest {

    @Test
    @DisplayName("https host:port -> Standard, secure")
    fun standard() {
        val d = ConnectionUrl.detect("https://oie.example.internal:8443")
        assertEquals(ConnectionMode.Standard, d.mode)
        assertEquals("https", d.scheme)
        assertEquals("oie.example.internal", d.host)
        assertEquals(8443, d.port)
        assertFalse(d.insecure)
        assertTrue(d.valid)
    }

    @Test
    @DisplayName("a bare host:port (no scheme) is assumed https + Standard")
    fun bareHostPortAssumesHttps() {
        val d = ConnectionUrl.detect("oie.example.internal:8443")
        assertEquals(ConnectionMode.Standard, d.mode)
        assertEquals("https", d.scheme)
        assertEquals("https://oie.example.internal:8443", d.normalized)
        assertTrue(d.valid)
    }

    @Test
    @DisplayName("https localhost / 127.0.0.1 with a port -> LocalhostPortForward")
    fun localhost() {
        assertEquals(ConnectionMode.LocalhostPortForward, ConnectionUrl.detect("https://localhost:31065").mode)
        assertEquals(ConnectionMode.LocalhostPortForward, ConnectionUrl.detect("https://127.0.0.1:8443").mode)
    }

    @Test
    @DisplayName("plain http is first-class: classified like https, flagged insecure (matches the importer)")
    fun httpIsFirstClassAndInsecure() {
        val local = ConnectionUrl.detect("http://localhost:8080")
        assertEquals(ConnectionMode.LocalhostPortForward, local.mode) { "same answer ConnectionUrl.deriveMode gives the importer" }
        assertTrue(local.insecure)
        assertTrue(local.valid)

        val remote = ConnectionUrl.detect("http://oie.example.internal:8080")
        assertEquals(ConnectionMode.Standard, remote.mode)
        assertTrue(remote.insecure)
        assertTrue(remote.valid)

        assertEquals(ConnectionMode.LocalhostPortForward, ConnectionUrl.deriveMode("http://localhost:8080"))
        assertEquals(ConnectionMode.Standard, ConnectionUrl.deriveMode("http://oie.example.internal:8080"))
    }

    @Test
    @DisplayName("userinfo in the address is refused — credentials never ride in a persisted, logged URL")
    fun userInfoIsRefused() {
        val d = ConnectionUrl.detect("https://admin:hunter2@mirth.example.com:8443")
        assertFalse(d.valid)
        assertTrue(d.problem.orEmpty().contains("username or password")) { d.problem.orEmpty() }
        assertEquals("https://mirth.example.com:8443", ConnectionUrl.stripUserInfo("https://admin:hunter2@mirth.example.com:8443"))
        assertEquals("https://mirth.example.com:8443/mirth", ConnectionUrl.stripUserInfo("https://admin@mirth.example.com:8443/mirth"))
        assertEquals("https://mirth.example.com:8443", ConnectionUrl.stripUserInfo("https://mirth.example.com:8443")) { "no userinfo → unchanged" }
    }

    @Test
    @DisplayName("an IPv6 loopback literal with a port is a LocalhostPortForward")
    fun ipv6LoopbackIsPortForward() {
        val d = ConnectionUrl.detect("https://[::1]:8443")
        assertTrue(d.valid) { d.problem.orEmpty() }
        assertEquals(ConnectionMode.LocalhostPortForward, d.mode)
        assertEquals(8443, d.port)
        assertEquals(ConnectionMode.LocalhostPortForward, ConnectionUrl.deriveMode("https://127.0.0.1:31065"))
        assertEquals(ConnectionMode.LocalhostPortForward, ConnectionUrl.deriveMode("https://[::1]:31065"))
    }

    @Test
    @DisplayName("a port outside 1..65535 is not valid")
    fun portRange() {
        assertFalse(ConnectionUrl.detect("https://host:0").valid)
        assertFalse(ConnectionUrl.detect("https://host:70000").valid)
        assertTrue(ConnectionUrl.detect("https://host:65535").valid)
        assertEquals(ConnectionMode.Custom, ConnectionUrl.deriveMode("https://host:70000"))
    }

    @Test
    @DisplayName("a path-bearing URL or a missing port -> Custom")
    fun pathOrNoPortIsCustom() {
        assertEquals(ConnectionMode.Custom, ConnectionUrl.detect("https://host:8443/apps/x").mode)
        assertEquals(ConnectionMode.Custom, ConnectionUrl.detect("https://host").mode)
        assertEquals(ConnectionMode.Custom, ConnectionUrl.detect("ftp://host:21").mode)
    }

    @Test
    @DisplayName("a trailing /webstart is stripped from the normalized base")
    fun stripsWebstart() {
        val d = ConnectionUrl.detect("https://oie:8443/webstart")
        assertEquals("https://oie:8443", d.normalized)
        assertEquals(ConnectionMode.Standard, d.mode)
    }

    @Test
    @DisplayName("blank / unparseable input is not valid (never throws)")
    fun blankOrGarbage() {
        assertFalse(ConnectionUrl.detect("").valid)
        assertFalse(ConnectionUrl.detect("   ").valid)
        assertFalse(ConnectionUrl.detect("http://").valid)
    }
}
