/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.model

import com.gestalt.launcher.importer.ConnectionImport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ConnectionStoreModelTest {

    private fun fullConnection() = Connection(
        id = "c1",
        name = "Prod OIE",
        serverUrl = "https://prod.lab:8443",
        username = "alice",
        pinnedCertSha256 = "ab".repeat(32),
        legacyTlsCompatibility = true,
        group = "Production",
        sortOrder = 3,
        connectionMode = ConnectionMode.Standard,
        icon = "dna",
        javaHome = JavaHomeConfig(JavaHomeMode.Custom, "17", "C:\\Java\\jdk-17"),
        javaFxHome = "C:\\Java\\fx",
        heapSize = "2048m",
        showJavaConsole = true,
        sslProtocols = SslOption(custom = true, value = "TLSv1.3"),
        sslCipherSuites = SslOption(custom = true, value = "TLS_AES_256_GCM_SHA384"),
    )

    @Test
    @DisplayName("the full extended model round-trips through a schema-versioned connections.json")
    fun fullModelRoundTrips(@TempDir dir: Path) {
        val file = dir.resolve("connections.json")
        ConnectionStore(file).add(fullConnection())

        // Re-load from disk in a fresh store: the entire model must survive byte-for-byte.
        val reloaded = ConnectionStore(file).get("c1")
        assertEquals(fullConnection(), reloaded)

        val text = Files.readString(file)
        assertTrue(text.contains("\"version\"")) { "connections.json must be schema-versioned" }
    }

    @Test
    @DisplayName("export writes a schema-versioned Gestalt file with no secrets")
    fun exportHasNoSecrets(@TempDir dir: Path) {
        val store = ConnectionStore(dir.resolve("connections.json"))
        store.add(fullConnection())
        val target = dir.resolve("export/out.json")
        store.exportTo(target)

        val text = Files.readString(target)
        assertEquals(ConnectionImport.SourceFormat.Gestalt, ConnectionImport.detectFormat(text))
        // Non-secret metadata is present; no credential ever is (the pin/username are non-secret).
        assertFalse(text.contains("password", ignoreCase = true))
        assertTrue(text.contains("Prod OIE"))
    }

    @Test
    @DisplayName("reorder persists sortOrder")
    fun reorderPersists(@TempDir dir: Path) {
        val file = dir.resolve("connections.json")
        val store = ConnectionStore(file)
        store.add(Connection(id = "a", name = "A", serverUrl = "https://a:8443", sortOrder = 0))
        store.add(Connection(id = "b", name = "B", serverUrl = "https://b:8443", sortOrder = 1))
        store.add(Connection(id = "c", name = "C", serverUrl = "https://c:8443", sortOrder = 2))

        store.reorder(listOf("c", "a", "b"))

        val reloaded = ConnectionStore(file)
        assertEquals(0, reloaded.get("c")!!.sortOrder)
        assertEquals(1, reloaded.get("a")!!.sortOrder)
        assertEquals(2, reloaded.get("b")!!.sortOrder)
    }
}
