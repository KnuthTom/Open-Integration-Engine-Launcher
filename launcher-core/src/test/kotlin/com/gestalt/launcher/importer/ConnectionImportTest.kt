/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.importer

import com.gestalt.launcher.importer.ConnectionImport.DedupePolicy
import com.gestalt.launcher.importer.ConnectionImport.SourceFormat
import com.gestalt.launcher.model.Connection
import com.gestalt.launcher.model.ConnectionMode
import com.gestalt.launcher.model.ConnectionStore
import com.gestalt.launcher.model.JavaHomeMode
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Importer tests against the REAL legacy export shape: a top-level JSON ARRAY of 34 objects with the
 * consistent 14-field MCAL schema. Must fail loudly if the fixture is missing.
 */
class ConnectionImportTest {

    private fun fixture(): String =
        requireNotNull(javaClass.getResourceAsStream("/fixtures/legacy-connections.json")) {
            "missing test fixture /fixtures/legacy-connections.json — the real legacy export is required"
        }.bufferedReader().use { it.readText() }

    private fun parsed(): List<Connection> = ConnectionImport.parse(fixture())

    private fun byName(name: String): Connection = parsed().first { it.name == name }

    @Test
    @DisplayName("detects format by ROOT node type: array -> legacy, object+connections -> Gestalt")
    fun detectsFormat() {
        assertEquals(SourceFormat.LegacyMcal, ConnectionImport.detectFormat(fixture())) { "a top-level array is legacy MCAL" }
        val gestalt = """{"version":1,"connections":[{"id":"a","name":"n","serverUrl":"https://h:8443"}]}"""
        assertEquals(SourceFormat.Gestalt, ConnectionImport.detectFormat(gestalt))
        assertEquals(SourceFormat.Unknown, ConnectionImport.detectFormat("""{"nope":true}"""))
        assertEquals(SourceFormat.Unknown, ConnectionImport.detectFormat("not json"))
    }

    @Test
    @DisplayName("imports all 34 entries, regenerating ids so the duplicate-id pair both survive")
    fun importsAll34WithUniqueIds() {
        val all = parsed()
        assertEquals(34, all.size)
        assertEquals(34, all.map { it.id }.distinct().size) { "every imported id must be unique" }
        // The two fixture entries that share legacy id 'dup-7' both import with distinct ids.
        val a = byName("Local Secrets Entry")
        val b = byName("Custom With Secrets")
        assertFalse(a.id == b.id) { "the duplicate legacy id must be regenerated to two distinct ids" }
    }

    @Test
    @DisplayName("preserves file order as sortOrder")
    fun preservesOrder() {
        val all = parsed()
        assertEquals((0 until all.size).toList(), all.map { it.sortOrder })
    }

    @Test
    @DisplayName("preserves the URL scheme: 7 http + 27 https")
    fun preservesScheme() {
        val all = parsed()
        assertEquals(7, all.count { it.serverUrl.startsWith("http://") }) { "7 plain-http connections" }
        assertEquals(27, all.count { it.serverUrl.startsWith("https://") }) { "27 https connections" }
        assertTrue(byName("OIE Local HTTP").isPlainHttp)
        assertFalse(byName("OIE Appliance (tunnel)").isPlainHttp)
    }

    @Test
    @DisplayName("strips a trailing /webstart (and /webstart/) from the 4 that have it; never mid-path")
    fun stripsWebstart() {
        assertEquals("http://localhost:8081", byName("OIE Local HTTP Webstart").serverUrl)
        assertEquals("https://localhost:9912", byName("OIE Appliance (tunnel)").serverUrl)
        assertEquals("https://localhost:9916", byName("Local Trailing Slash").serverUrl)
        assertEquals("https://localhost:9913", byName("Local Webstart Four").serverUrl)
        assertTrue(parsed().none { it.serverUrl.endsWith("/webstart") }) { "no serverUrl keeps a trailing /webstart" }
        // a mid-path /webstart/ is NOT stripped
        assertEquals("https://oie.example.org:8443/apps/webstart/extra", byName("Custom Path Webstart Mid").serverUrl)
    }

    @Test
    @DisplayName("derives mode: 19 localhost (incl. http://localhost), 10 standard, 5 custom")
    fun derivesMode() {
        val byMode = parsed().groupingBy { it.connectionMode }.eachCount()
        assertEquals(19, byMode[ConnectionMode.LocalhostPortForward]) { "all localhost/127 entries, http or https" }
        assertEquals(10, byMode[ConnectionMode.Standard])
        assertEquals(5, byMode[ConnectionMode.Custom])
        // http://localhost:8080 is now a port-forward (http is first-class), not Custom.
        assertEquals(ConnectionMode.LocalhostPortForward, byName("OIE Local HTTP").connectionMode)
        assertEquals(ConnectionMode.LocalhostPortForward, byName("Local 127 Loopback").connectionMode)
        // a non-localhost http host:port is Standard
        assertEquals(ConnectionMode.Standard, byName("Legacy HTTP Host").connectionMode)
    }

    @Test
    @DisplayName("maps javaHome: DEFAULT, BUNDLED 'Java 17'->17, BUNDLED 'Java 8'->8")
    fun mapsJavaHome() {
        val bundled17 = byName("OIE Appliance (tunnel)").javaHome
        assertEquals(JavaHomeMode.Bundled, bundled17.mode)
        assertEquals("17", bundled17.bundledVersion)

        assertEquals(JavaHomeMode.Default, byName("DEV - Local Tunnel A").javaHome.mode)

        val bundled8 = byName("TEST - Local Tunnel").javaHome
        assertEquals(JavaHomeMode.Bundled, bundled8.mode)
        assertEquals("8", bundled8.bundledVersion) { "'Java 8' must map to bundled version 8" }
    }

    @Test
    @DisplayName("carries heap, console, legacy DH, and ssl protocol/cipher overrides")
    fun carriesSettings() {
        val devB = byName("DEV - Local Tunnel B")
        assertEquals("1024m", devB.heapSize)
        assertTrue(devB.showJavaConsole)

        assertTrue(byName("QA Local HTTP").legacyTlsCompatibility)
        assertTrue(byName("TEST - Local Tunnel").legacyTlsCompatibility)

        val staging = byName("Staging Tunnel")
        assertTrue(staging.sslProtocols.custom)
        assertEquals("TLSv1.2,TLSv1.3", staging.sslProtocols.value)

        val histo = byName("Histology Local")
        assertTrue(histo.sslCipherSuites.custom)
        assertEquals("TLS_AES_256_GCM_SHA384", histo.sslCipherSuites.value)
    }

    @Test
    @DisplayName("maps legacy icon filenames to ConnectionIcons keys (infra -> data/server, unknown -> default)")
    fun mapsIcons() {
        assertEquals("microscope", ConnectionImport.iconKeyFor("optical-microscope"))
        assertEquals("test-tubes", ConnectionImport.iconKeyFor("test-tube"))
        assertEquals("database", ConnectionImport.iconKeyFor("cloud-computing"))
        assertEquals("database", ConnectionImport.iconKeyFor("network"))
        assertEquals("cell-nuclei", ConnectionImport.iconKeyFor("eukaryotic"))
        assertEquals(Connection.DEFAULT_ICON_KEY, ConnectionImport.iconKeyFor("rocket-launch"))
        assertEquals(Connection.DEFAULT_ICON_KEY, ConnectionImport.iconKeyFor(null))
        assertEquals("microscope", byName("OIE Appliance (tunnel)").icon)
    }

    @Test
    @DisplayName("lenient: tolerates unknown + missing keys, and maps a CUSTOM java home path")
    fun lenientParsing() {
        val json = """
            [{"id":"x","name":"Lenient","address":"https://h:8443","javaHome":"CUSTOM",
              "javaHomeBundledValue":"Java 8","customJavaHome":"C:\\Java\\jdk-17.0.10",
              "icon":"dna","futureField":123,"nested":{"a":1}}]
        """.trimIndent()
        val c = ConnectionImport.parse(json).single()
        assertEquals(JavaHomeMode.Custom, c.javaHome.mode)
        assertEquals("8", c.javaHome.bundledVersion)
        assertEquals("C:\\Java\\jdk-17.0.10", c.javaHome.customPath)
        assertEquals("https://h:8443", c.serverUrl) { "missing heap/ssl/etc. fields must not break the parse" }
    }

    @Test
    @DisplayName("user:password@ userinfo in an imported address is stripped (legacy and own format)")
    fun stripsUserInfoFromAddresses() {
        val legacy = """[{"name":"Creds in URL","address":"https://admin:hunter2@mirth.example.com:8443/webstart"}]"""
        val fromLegacy = ConnectionImport.parse(legacy).single()
        assertEquals("https://mirth.example.com:8443", fromLegacy.serverUrl)

        val own = """{"version":1,"connections":[{"id":"x","name":"Own","serverUrl":"https://admin:hunter2@mirth.example.com:8443","username":"admin"}]}"""
        val fromOwn = ConnectionImport.parse(own).single()
        assertEquals("https://mirth.example.com:8443", fromOwn.serverUrl)
        assertEquals(null, fromOwn.username) { "ids are regenerated on import, so a username would be a dangling keychain reference" }
        assertFalse(Json.encodeToString(listOf(fromLegacy, fromOwn)).contains("hunter2"))
    }

    @Test
    @DisplayName("never imports credentials — no username and no plaintext secret survives")
    fun neverImportsSecrets() {
        val all = parsed()
        assertTrue(all.all { it.username == null }) { "username (credential-adjacent) must not be imported" }
        val serialized = Json.encodeToString(all)
        assertFalse(serialized.contains("S3cr3t-do-not-import!"))
        assertFalse(serialized.contains("another-plaintext-secret"))
        assertFalse(serialized.contains("labadmin"))
        assertFalse(serialized.contains("svc-mirth"))
    }

    @Test
    @DisplayName("import into a store dedupes by name+baseUrl per policy (skip/replace/merge)")
    fun dedupePolicies(@TempDir dir: Path) {
        fun freshStore(): ConnectionStore {
            val store = ConnectionStore(dir.resolve("connections-${System.nanoTime()}.json"))
            store.add(Connection(id = "keep-me", name = "Prod", serverUrl = "https://h:8443", heapSize = "512m"))
            return store
        }
        val incoming = """{"version":1,"connections":[${Json.encodeToString(
            Connection(id = "ignored", name = "Prod", serverUrl = "https://h:8443", heapSize = "2048m"),
        )}]}"""

        val skip = freshStore()
        assertEquals(1, ConnectionImport.import(skip, incoming, DedupePolicy.Skip).skipped)
        assertEquals("512m", skip.get("keep-me")!!.heapSize) { "skip leaves the existing connection untouched" }

        val replace = freshStore()
        assertEquals(1, ConnectionImport.import(replace, incoming, DedupePolicy.Replace).replaced)
        assertEquals("2048m", replace.get("keep-me")!!.heapSize) { "replace keeps the existing id but takes incoming config" }

        val merge = freshStore()
        assertEquals(1, ConnectionImport.import(merge, incoming, DedupePolicy.Merge).merged)
        assertEquals("2048m", merge.get("keep-me")!!.heapSize) { "merge: incoming config wins, existing id preserved" }
        assertNull(merge.get("ignored")) { "incoming id is discarded on a dedupe match" }
    }

    @Test
    @DisplayName("a full fixture import adds all 34 and persists no secret to connections.json")
    fun fullImportPersistsNoSecret(@TempDir dir: Path) {
        val file = dir.resolve("connections.json")
        val store = ConnectionStore(file)
        val summary = ConnectionImport.import(store, fixture(), DedupePolicy.Merge)
        assertEquals(34, summary.added)
        assertEquals(34, store.list().size)
        val onDisk = java.nio.file.Files.readString(file)
        assertFalse(onDisk.contains("S3cr3t-do-not-import!"))
        assertFalse(onDisk.contains("password", ignoreCase = true))
    }
}
