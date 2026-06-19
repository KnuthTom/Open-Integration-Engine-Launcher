/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.app.screens

import com.gestalt.launcher.model.CloseAfterLaunch
import com.gestalt.launcher.model.Connection
import com.gestalt.launcher.model.ConnectionMode
import com.gestalt.launcher.model.JavaHomeMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** The smart connection form's logic: minimal-required validation, auto-detect, and paste-to-fill. */
class ConnectionFormStateTest {

    @Test
    @DisplayName("only Name + Address are required to save; advanced fields keep their defaults")
    fun minimalRequired() {
        val f = ConnectionFormState(null)
        f.name = "Prod OIE"
        f.onAddressInput("oie.lab:8443")
        assertNull(f.validate())

        val c = f.toConnection("id1", 0)
        assertEquals("Prod OIE", c.name)
        assertEquals("https://oie.lab:8443", c.serverUrl)
        assertEquals(ConnectionMode.Standard, c.connectionMode)
        assertEquals(JavaHomeMode.Default, c.javaHome.mode)
        assertNull(c.heapSize)
        assertNull(c.pinnedCertSha256)
        assertNull(c.username)
    }

    @Test
    @DisplayName("validation requires a name, a non-blank address, and a parseable address")
    fun validation() {
        assertTrue(ConnectionFormState(null).apply { address = "oie:8443" }.validate()!!.contains("Name"))
        assertTrue(ConnectionFormState(null).apply { name = "x" }.validate()!!.contains("Address"))
        assertNotNull(ConnectionFormState(null).apply { name = "x"; address = "bad host with spaces" }.validate())
        assertNull(ConnectionFormState(null).apply { name = "x"; onAddressInput("https://host:8443") }.validate())
    }

    @Test
    @DisplayName("mode is auto-detected from the address; an override wins and never touches the address")
    fun modeAutoDetectAndOverride() {
        val f = ConnectionFormState(null).apply { address = "https://localhost:31065" }
        assertEquals(ConnectionMode.LocalhostPortForward, f.effectiveMode)

        f.modeOverride = ConnectionMode.Standard
        assertEquals(ConnectionMode.Standard, f.effectiveMode)
        assertEquals("https://localhost:31065", f.address) // override did not wipe input
        assertEquals(ConnectionMode.Standard, f.toConnection("id", 0).connectionMode)
    }

    @Test
    @DisplayName("pasting a legacy connection entry prefills the whole form")
    fun pasteLegacyEntryPrefills() {
        val f = ConnectionFormState(null)
        f.onAddressInput(
            """{"name":"Imported","address":"https://oie:8443/webstart","javaHome":"BUNDLED",
               "javaHomeBundledValue":"Java 8","heapSize":"1000m","username":"bob","password":"x"}""",
        )
        assertEquals("Imported", f.name)
        assertEquals("https://oie:8443", f.address)
        assertEquals(JavaHomeMode.Bundled, f.javaHomeMode)
        assertEquals("8", f.bundledVersion)
        assertEquals("1000m", f.heap)
        assertEquals("", f.username) { "credentials are never adopted from a pasted entry" }
        assertEquals("Java 8 · 1000m", f.runtimeSummary)
    }

    @Test
    @DisplayName("pasting/typing a plain URL just sets the address (no prefill, keeps a typed name)")
    fun pasteUrlSetsAddressOnly() {
        val f = ConnectionFormState(null).apply { name = "keep" }
        f.onAddressInput("https://host:8443")
        assertEquals("https://host:8443", f.address)
        assertEquals("keep", f.name)
    }

    @Test
    @DisplayName("collapsed sections show a summary chip only when they hold a non-default value")
    fun sectionSummaries() {
        val f = ConnectionFormState(null)
        assertNull(f.runtimeSummary)
        assertNull(f.securitySummary)

        f.javaHomeMode = JavaHomeMode.Bundled; f.bundledVersion = "8"; f.heap = "1000m"
        assertEquals("Java 8 · 1000m", f.runtimeSummary)

        f.legacyDh = true; f.pin = "ab12"
        assertEquals("legacy DH · pinned", f.securitySummary)
    }

    @Test
    @DisplayName("the close-after-launch override defaults to Inherit and round-trips through the form")
    fun closeAfterLaunchOverride() {
        // Default for a new connection is Inherit (no chip).
        val fresh = ConnectionFormState(null)
        assertEquals(CloseAfterLaunch.Inherit, fresh.closeAfterLaunch)
        assertEquals(CloseAfterLaunch.Inherit, fresh.toConnection("id", 0).closeAfterLaunch)

        // Editing a connection loads its override and surfaces it in the Runtime summary chip.
        val existing = Connection(id = "e", name = "E", serverUrl = "https://h:8443", closeAfterLaunch = CloseAfterLaunch.On)
        val f = ConnectionFormState(existing)
        assertEquals(CloseAfterLaunch.On, f.closeAfterLaunch)
        assertEquals("close on", f.runtimeSummary)
        f.closeAfterLaunch = CloseAfterLaunch.Off
        assertEquals(CloseAfterLaunch.Off, f.toConnection("e", 0).closeAfterLaunch)
    }
}
