/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** The descriptor / digest URLs a connection launches against must live UNDER its base path. */
class ConnectionUriTest {

    private fun conn(url: String) = Connection(id = "c", name = "c", serverUrl = url)

    @Test
    @DisplayName("host-only base: /webstart and /digest/<v> sit at the server root, with the separating slash")
    fun hostOnlyBase() {
        assertEquals("https://mirth.example.com:8443/webstart", conn("https://mirth.example.com:8443").webstartUri().toString())
        assertEquals("https://mirth.example.com:8443/webstart", conn("https://mirth.example.com:8443/").webstartUri().toString())
        assertEquals("https://mirth.example.com:8443/digest/4.4.2", conn("https://mirth.example.com:8443").digestUri("4.4.2").toString())
        assertEquals("http://localhost:8080/webstart", conn("http://localhost:8080").webstartUri().toString())
    }

    @Test
    @DisplayName("path-bearing base (reverse proxy): the path is KEPT, not replaced by /webstart")
    fun pathBearingBase() {
        assertEquals("https://proxy.example.com/mirth/webstart", conn("https://proxy.example.com/mirth").webstartUri().toString())
        assertEquals("https://proxy.example.com/mirth/webstart", conn("https://proxy.example.com/mirth/").webstartUri().toString())
        assertEquals("https://proxy.example.com/apps/mirth/digest/4.4.2", conn("https://proxy.example.com/apps/mirth").digestUri("4.4.2").toString())
    }

    @Test
    @DisplayName("baseDirUri is the base as a directory, without query or fragment")
    fun baseDir() {
        assertEquals("https://h:8443/", conn("https://h:8443").baseDirUri().toString())
        assertEquals("https://h:8443/", conn("https://h:8443/").baseDirUri().toString())
        assertEquals("https://h:8443/mirth/", conn("https://h:8443/mirth").baseDirUri().toString())
        assertEquals("https://h:8443/mirth/", conn("https://h:8443/mirth?x=1#frag").baseDirUri().toString())
        // Relative resolution under a directory base lands under the path, never beside it.
        assertEquals("https://h:8443/mirth/webstart/client-lib/x.jar", conn("https://h:8443/mirth").baseDirUri().resolve("webstart/client-lib/x.jar").toString())
    }

    @Test
    @DisplayName("a customised webstartPath is honoured under the base whether or not it carries a leading slash")
    fun customWebstartPath() {
        assertEquals("https://h:8443/webstart.jnlp", conn("https://h:8443").copy(webstartPath = "/webstart.jnlp").webstartUri().toString())
        assertEquals("https://h:8443/mirth/webstart.jnlp", conn("https://h:8443/mirth").copy(webstartPath = "webstart.jnlp").webstartUri().toString())
    }
}
