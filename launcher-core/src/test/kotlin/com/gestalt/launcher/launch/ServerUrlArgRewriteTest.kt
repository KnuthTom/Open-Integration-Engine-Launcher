/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.launch

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The launched Mirth client connects back to the URL in its first application argument. A port-forward
 * server reports its OWN configured URL in the JNLP (e.g. https://localhost:8443) rather than the address
 * the user reaches it at (https://localhost:31065), so the launcher rewrites that argument to the
 * connection's own URL — otherwise the launched Administrator dials a port nothing is forwarded to.
 */
class ServerUrlArgRewriteTest {

    @Test
    @DisplayName("a port-forward connection rewrites the server-URL argument to the connection's port")
    fun forwardPortRewritten() {
        val out = LauncherCore.rewriteServerUrlArg(
            "https://localhost:31065",
            listOf("https://localhost:8443", "4.4.2"),
        )
        assertEquals(listOf("https://localhost:31065", "4.4.2"), out)
    }

    @Test
    @DisplayName("a direct connection whose JNLP URL already matches is unchanged")
    fun directConnectionUnchanged() {
        val out = LauncherCore.rewriteServerUrlArg(
            "https://mirth.example.internal:8443",
            listOf("https://mirth.example.internal:8443", "4.0.0"),
        )
        assertEquals(listOf("https://mirth.example.internal:8443", "4.0.0"), out)
    }

    @Test
    @DisplayName("a different host is also redirected to the connection's address, preserving a path")
    fun hostAndPathHandled() {
        val out = LauncherCore.rewriteServerUrlArg(
            "https://localhost:31065",
            listOf("https://internal-mirth:8443/mirth", "4.4.2"),
        )
        assertEquals(listOf("https://localhost:31065/mirth", "4.4.2"), out)
    }

    @Test
    @DisplayName("only the first URL argument is rewritten; non-URL arguments are untouched")
    fun onlyFirstUrlArgRewritten() {
        val out = LauncherCore.rewriteServerUrlArg(
            "https://localhost:31065",
            listOf("4.4.2", "https://localhost:8443", "https://other:9000"),
        )
        assertEquals(listOf("4.4.2", "https://localhost:31065", "https://other:9000"), out)
    }

    @Test
    @DisplayName("a port-less connection URL keeps the argument's own port (never drops it to the default)")
    fun portlessConnectionKeepsArgPort() {
        val out = LauncherCore.rewriteServerUrlArg("https://localhost", listOf("https://localhost:8443", "4.4.2"))
        assertEquals(listOf("https://localhost:8443", "4.4.2"), out)
    }

    @Test
    @DisplayName("no URL arguments, or an unparseable connection URL, passes args through unchanged")
    fun passthroughCases() {
        assertEquals(listOf("4.4.2"), LauncherCore.rewriteServerUrlArg("https://localhost:31065", listOf("4.4.2")))
        assertEquals(listOf("https://localhost:8443"), LauncherCore.rewriteServerUrlArg("", listOf("https://localhost:8443")))
    }
}
