/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.app

import com.gestalt.launcher.model.Connection
import com.gestalt.launcher.model.ConnectionUrl
import java.net.InetSocketAddress
import java.net.Socket

/** Result of a per-connection reachability probe. */
enum class Reachability { Unknown, Checking, Ok, Unreachable }

/**
 * A cheap, bounded TCP-connect reachability check — does NOT do TLS or verification (that is the
 * launch pipeline's job), just "is something listening on host:port". Blocking; callers run it off the
 * UI thread and may cancel by abandoning the coroutine. Probing is on-demand, never automatic on load.
 */
object ReachabilityProbe {
    fun isReachable(connection: Connection, timeoutMs: Int = 1500): Boolean {
        val host = ConnectionUrl.host(connection.serverUrl) ?: return false
        val port = ConnectionUrl.port(connection.serverUrl) ?: 443
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        }.getOrDefault(false)
    }
}
