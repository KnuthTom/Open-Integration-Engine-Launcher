/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.importer

import com.gestalt.launcher.model.ConnectionMode
import com.gestalt.launcher.model.JavaHomeMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** Paste-to-fill: parsing a single pasted connection entry (legacy or Gestalt) into a [Connection]. */
class ConnectionImportSingleEntryTest {

    @Test
    @DisplayName("a single legacy MCAL entry parses (address stripped, mode derived, runtime mapped, no creds)")
    fun parsesSingleLegacyEntry() {
        val entry = """
            {"id":"legacy-7","name":"Prod OIE","address":"https://oie.prod:8443/webstart",
             "javaHome":"BUNDLED","javaHomeBundledValue":"Java 8","heapSize":"1000m",
             "useLegacyDHSettings":true,"username":"bob","password":"hunter2"}
        """.trimIndent()

        val c = ConnectionImport.parseSingleEntry(entry) { "fixed-id" }!!
        assertEquals("Prod OIE", c.name)
        assertEquals("https://oie.prod:8443", c.serverUrl)
        assertEquals(ConnectionMode.Standard, c.connectionMode)
        assertEquals(JavaHomeMode.Bundled, c.javaHome.mode)
        assertEquals("8", c.javaHome.bundledVersion)
        assertEquals("1000m", c.heapSize)
        assertTrue(c.legacyTlsCompatibility)
        assertNull(c.username) { "credentials are never imported" }
    }

    @Test
    @DisplayName("a single Gestalt connection object parses and drops any pasted credential")
    fun parsesSingleGestaltEntry() {
        val entry = """
            {"id":"abc","name":"Lab","serverUrl":"https://localhost:31065","username":"alice"}
        """.trimIndent()

        val c = ConnectionImport.parseSingleEntry(entry)!!
        assertEquals("Lab", c.name)
        assertEquals("https://localhost:31065", c.serverUrl)
        assertNull(c.username) { "a pasted credential association is dropped" }
    }

    @Test
    @DisplayName("non-entry text (a bare URL or junk) returns null so the caller can treat it as an address")
    fun returnsNullForNonEntry() {
        assertNull(ConnectionImport.parseSingleEntry("https://oie:8443"))
        assertNull(ConnectionImport.parseSingleEntry("not json"))
        assertNull(ConnectionImport.parseSingleEntry(""))
        assertNull(ConnectionImport.parseSingleEntry("[1,2,3]")) // an array of non-objects
    }
}
