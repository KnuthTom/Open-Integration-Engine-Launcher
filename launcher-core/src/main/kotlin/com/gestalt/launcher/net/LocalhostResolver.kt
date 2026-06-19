/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.net

import org.apache.hc.client5.http.DnsResolver
import org.apache.hc.client5.http.SystemDefaultDnsResolver
import java.net.Inet4Address
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * IPv4-first address resolution for loopback hosts.
 *
 * The localhost / SSM-port-forward case (`https://localhost:31065`) only listens on IPv4 — the forward is
 * IPv4-only — yet a dual-stack JVM frequently resolves `localhost` to the IPv6 loopback `::1` first and
 * never falls back, so the connection is refused even though `127.0.0.1` is alive. This helper returns
 * loopback addresses IPv4-first and ensures every resolved address is offered (so an `::1` refusal can
 * fall back to `127.0.0.1`).
 *
 * Behaviour for **non-loopback** hosts is intentionally identical to the platform default — only the
 * loopback ordering is changed — so nothing about real remote connections is altered.
 */
object LocalhostResolver {

    // A 127.0.0.0/8 IPv4 loopback LITERAL — a strict dotted-quad, so a real DNS hostname whose first label
    // happens to be "127" (e.g. "127.example.com") is NOT misclassified as loopback.
    private val IPV4_LOOPBACK = Regex("""^127\.\d{1,3}\.\d{1,3}\.\d{1,3}$""")

    /** True when [host] names the loopback interface (`localhost` or a 127.0.0.0/8 / ::1 literal). */
    fun isLoopbackHost(host: String?): Boolean {
        val h = host?.trim()?.trim('[', ']')?.lowercase() ?: return false
        return h == "localhost" ||
            h == "::1" ||
            h == "0:0:0:0:0:0:0:1" ||
            IPV4_LOOPBACK.matches(h)
    }

    /**
     * Stable IPv4-first ordering: every [Inet4Address] (in its original relative order) before every
     * IPv6 address. Pure, so the ordering is unit-tested with literal addresses (no DNS).
     */
    fun orderIpv4First(addresses: Array<InetAddress>): Array<InetAddress> {
        if (addresses.size < 2) return addresses
        val (v4, rest) = addresses.partition { it is Inet4Address }
        return (v4 + rest).toTypedArray()
    }

    /**
     * Resolve [host] to all its addresses, IPv4-first when [host] is loopback, otherwise in the platform's
     * own order. Used by the cert probe and the diagnostic so they reach the same IPv4 endpoint the real
     * launcher fetch does.
     */
    @Throws(UnknownHostException::class)
    fun resolveOrdered(host: String): Array<InetAddress> {
        val all = InetAddress.getAllByName(host)
        return if (isLoopbackHost(host)) orderIpv4First(all) else all
    }

    /**
     * A [DnsResolver] for Apache HttpClient that prefers IPv4 for loopback and delegates everything else to
     * the system resolver. The pooling connection manager already retries each returned address before
     * failing, so handing it `[127.0.0.1, ::1]` gives the wanted fall-back from a refused `::1`.
     */
    val dnsResolver: DnsResolver = object : DnsResolver {
        override fun resolve(host: String): Array<InetAddress> =
            if (isLoopbackHost(host)) orderIpv4First(InetAddress.getAllByName(host))
            else SystemDefaultDnsResolver.INSTANCE.resolve(host)

        override fun resolveCanonicalHostname(host: String): String =
            SystemDefaultDnsResolver.INSTANCE.resolveCanonicalHostname(host)
    }
}
