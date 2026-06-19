/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.net

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.net.Inet4Address
import java.net.InetAddress

/** IPv4-first loopback resolution: the fix for the IPv4-only SSM port-forward (`localhost`) case. */
class LocalhostResolverTest {

    @Test
    @DisplayName("isLoopbackHost recognizes localhost and loopback literals, not real hosts")
    fun loopbackDetection() {
        listOf("localhost", "LOCALHOST", "127.0.0.1", "127.0.0.53", "::1", "[::1]", "0:0:0:0:0:0:0:1")
            .forEach { assertTrue(LocalhostResolver.isLoopbackHost(it)) { "expected loopback: $it" } }
        // A real DNS hostname whose first label is "127" must NOT be treated as loopback (only a true
        // 127.0.0.0/8 dotted-quad literal is) — otherwise preferIPv4Stack could misfire on a remote host.
        listOf("host.example", "192.168.1.10", "10.0.0.1", "example.com", "127.example.com", "127.internal.corp", "127", null, "")
            .forEach { assertFalse(LocalhostResolver.isLoopbackHost(it)) { "expected NOT loopback: $it" } }
    }

    @Test
    @DisplayName("orderIpv4First moves IPv4 ahead of IPv6 while keeping relative order")
    fun ipv4First() {
        val v6 = InetAddress.getByName("::1")          // literal — no DNS
        val v4 = InetAddress.getByName("127.0.0.1")    // literal — no DNS
        val ordered = LocalhostResolver.orderIpv4First(arrayOf(v6, v4))
        assertEquals(v4, ordered[0]) { "IPv4 loopback must be offered first" }
        assertEquals(v6, ordered[1])
        // A single address (or none) is returned unchanged.
        assertEquals(1, LocalhostResolver.orderIpv4First(arrayOf(v6)).size)
    }

    @Test
    @DisplayName("resolveOrdered offers an IPv4 loopback address first for localhost")
    fun resolveLocalhostIpv4First() {
        val resolved = LocalhostResolver.resolveOrdered("localhost")
        assertTrue(resolved.isNotEmpty())
        if (resolved.any { it is Inet4Address }) {
            assertTrue(resolved.first() is Inet4Address) { "localhost must resolve IPv4-first, got ${resolved.toList()}" }
        }
    }

    @Test
    @DisplayName("the Apache DnsResolver prefers IPv4 for loopback and answers canonical lookups")
    fun dnsResolverLoopback() {
        val addrs = LocalhostResolver.dnsResolver.resolve("localhost")
        assertTrue(addrs.isNotEmpty())
        if (addrs.any { it is Inet4Address }) assertTrue(addrs.first() is Inet4Address)
        // Canonical hostname resolution delegates to the system resolver (must not throw for a literal).
        LocalhostResolver.dnsResolver.resolveCanonicalHostname("127.0.0.1")
    }
}
