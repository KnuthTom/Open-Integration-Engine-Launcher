/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.importer

import com.gestalt.launcher.importer.ConnectionImport.DedupePolicy
import com.gestalt.launcher.importer.ConnectionImport.SourceFormat
import com.gestalt.launcher.icon.CustomIconStore
import com.gestalt.launcher.model.CloseAfterLaunch
import com.gestalt.launcher.model.Connection
import com.gestalt.launcher.model.ConnectionMode
import com.gestalt.launcher.model.ConnectionStore
import com.gestalt.launcher.model.Folder
import com.gestalt.launcher.model.JavaHomeConfig
import com.gestalt.launcher.model.JavaHomeMode
import com.gestalt.launcher.model.SslOption
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

        val own = """{"version":1,"connections":[{"id":"x","name":"Own","serverUrl":"https://admin:hunter2@mirth.example.com:8443","username":" admin "}]}"""
        val fromOwn = ConnectionImport.parse(own).single()
        assertEquals("https://mirth.example.com:8443", fromOwn.serverUrl)
        assertEquals("admin", fromOwn.username) { "the username is a setting (it pre-fills the login) and comes along, trimmed" }
        assertFalse(Json.encodeToString(listOf(fromLegacy, fromOwn)).contains("hunter2"))
    }

    @Test
    @DisplayName("port-forward commands come in under the caller's policy: kept as reviewed, kept switched off (default), or dropped")
    fun tunnelCommandsUnderPolicy(@TempDir dir: Path) {
        val own = """{"version":1,"connections":[{"id":"x","name":"Forwarded","serverUrl":"https://localhost:31065","tunnelCommand":"C:\\\\tools\\\\forward.bat","tunnelEnabled":true}]}"""
        val parsed = ConnectionImport.parseAll(own)
        assertEquals(1, parsed.forwards.size) { "a UI lists what the file carries before choosing" }
        assertEquals("C:\\\\tools\\\\forward.bat", parsed.connections.single().tunnelCommand)

        fun imported(policy: ConnectionImport.TunnelPolicy): Connection {
            val store = ConnectionStore(dir.resolve("c-${policy.name}.json"))
            val summary = ConnectionImport.import(store, own, DedupePolicy.Merge, policy)
            assertEquals(1, summary.forwards)
            return store.list().single()
        }
        val disarmed = imported(ConnectionImport.TunnelPolicy.Disarm)
        assertEquals("C:\\\\tools\\\\forward.bat", disarmed.tunnelCommand) { "the command is kept, visible, for the user to arm" }
        assertEquals(false, disarmed.tunnelEnabled)
        assertFalse(disarmed.hasTunnel) { "nothing runs until the switch is turned on here" }
        val kept = imported(ConnectionImport.TunnelPolicy.Keep)
        assertEquals(true, kept.tunnelEnabled)
        assertTrue(kept.hasTunnel)
        val dropped = imported(ConnectionImport.TunnelPolicy.Drop)
        assertNull(dropped.tunnelCommand)
        assertNull(dropped.tunnelEnabled)

        val bare = """{"id":"x","name":"Forwarded","serverUrl":"https://localhost:31065","tunnelCommand":"forward.bat","tunnelEnabled":true}"""
        val pasted = ConnectionImport.parseSingleEntry(bare)!!
        assertEquals("forward.bat", pasted.tunnelCommand) { "a pasted entry keeps its command in the form" }
        assertEquals(false, pasted.tunnelEnabled) { "with the switch off" }
    }

    @Test
    @DisplayName("merging an export over the same connection keeps this machine's port-forward switch when the command is unchanged")
    fun mergeKeepsTheLocalSwitch(@TempDir dir: Path) {
        val store = ConnectionStore(dir.resolve("connections.json"))
        store.add(Connection(id = "local", name = "Fwd", serverUrl = "https://localhost:31065", tunnelCommand = "forward.bat", tunnelEnabled = true))
        val sameCommand = """{"version":1,"connections":[{"id":"x","name":"Fwd","serverUrl":"https://localhost:31065","tunnelCommand":"forward.bat","tunnelEnabled":true}]}"""
        ConnectionImport.import(store, sameCommand, DedupePolicy.Merge, ConnectionImport.TunnelPolicy.Disarm)
        assertEquals(true, store.get("local")!!.tunnelEnabled) { "re-importing one's own export never disarms a forward already set up here" }
        val newCommand = sameCommand.replace("forward.bat", "other.bat")
        ConnectionImport.import(store, newCommand, DedupePolicy.Merge, ConnectionImport.TunnelPolicy.Disarm)
        val after = store.get("local")!!
        assertEquals("other.bat", after.tunnelCommand) { "a changed command comes in" }
        assertEquals(false, after.tunnelEnabled) { "switched off, as a new command the user has not armed" }
    }

    @Test
    @DisplayName("never imports a secret — the legacy usernames come along, the plaintext passwords never do")
    fun neverImportsSecrets() {
        val all = parsed()
        assertEquals("labadmin", all.first { it.name == "Local Secrets Entry" }.username) { "a legacy username pre-fills the login as it did there" }
        val serialized = Json.encodeToString(all)
        assertFalse(serialized.contains("S3cr3t-do-not-import!"))
        assertFalse(serialized.contains("another-plaintext-secret"))
        assertFalse(serialized.contains("password", ignoreCase = true))
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
    @DisplayName("merge carries every setting the file has, and keeps what is this machine's: id, placement, pin, timestamps")
    fun mergeCarriesEverySetting(@TempDir dir: Path) {
        val store = ConnectionStore(dir.resolve("connections.json"))
        store.add(Connection(id = "keep-me", name = "Prod", serverUrl = "https://h:8443", pinnedCertSha256 = "ab".repeat(32), username = "old", sortOrder = 0))
        val incoming = Connection(
            id = "ignored", name = "Prod", serverUrl = "https://h:8443/", username = "new", pinnedCertSha256 = "cd".repeat(32),
            webstartPath = "/mirth/webstart", digestPath = "/mirth/digest", closeAfterLaunch = CloseAfterLaunch.On,
            autoApproveCertificates = true, uiScale = "monitor", showJavaConsole = true, legacyTlsCompatibility = true,
            javaHome = JavaHomeConfig(JavaHomeMode.Bundled, "8"), heapSize = "1g", icon = "dna", sortOrder = 7,
        )
        ConnectionImport.import(store, """{"version":2,"connections":[${Json.encodeToString(incoming)}]}""", DedupePolicy.Merge)
        val merged = store.get("keep-me")!!
        assertEquals("new", merged.username)
        assertEquals("/mirth/webstart", merged.webstartPath)
        assertEquals("/mirth/digest", merged.digestPath)
        assertEquals(CloseAfterLaunch.On, merged.closeAfterLaunch)
        assertFalse(merged.autoApproveCertificates) { "auto-approve is this machine's trust decision, never a file's" }
        assertEquals("monitor", merged.uiScale)
        assertTrue(merged.showJavaConsole && merged.legacyTlsCompatibility)
        assertEquals(JavaHomeMode.Bundled, merged.javaHome.mode)
        assertEquals("1g", merged.heapSize)
        assertEquals("dna", merged.icon)
        assertEquals("ab".repeat(32), merged.pinnedCertSha256) { "the certificate pin is this machine's trust state" }
        assertEquals(0, merged.sortOrder) { "placement is this machine's" }
    }

    @Test
    @DisplayName("export → import round-trips every setting, the folder tree and the custom icons (passwords stay in the keychain)")
    fun exportImportRoundTrip(@TempDir dir: Path) {
        val icons = CustomIconStore(dir.resolve("icons-a"))
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><path fill="currentColor" d="M2 2h20v20H2z"/></svg>"""
        val icon = icons.add("lab.svg", svg.toByteArray())

        val source = ConnectionStore(dir.resolve("a/connections.json"))
        val folder = source.addFolder(Folder(id = "f-region", name = "Region"))
        val sub = source.addFolder(Folder(id = "f-site", name = "Site", parentId = folder.id))
        val full = Connection(
            id = "src", name = "Everything", serverUrl = "https://engine.example.internal:8443", username = "svc-launcher",
            legacyTlsCompatibility = true, webstartPath = "/mirth/webstart", digestPath = "/mirth/digest",
            connectionMode = ConnectionMode.LocalhostPortForward, icon = icon.key,
            javaHome = JavaHomeConfig(JavaHomeMode.Custom, "17", "C:\\Java\\jdk-17"), javaFxHome = "C:\\JavaFX",
            heapSize = "2g", showJavaConsole = true, sslProtocols = SslOption(custom = true, value = "TLSv1.2"),
            sslCipherSuites = SslOption(custom = true, value = "TLS_AES_256_GCM_SHA384"), closeAfterLaunch = CloseAfterLaunch.Off,
            autoApproveCertificates = true, folderId = sub.id, uiScale = "1.25",
            tunnelCommand = "C:\\tools\\forward.bat", tunnelEnabled = true,
        )
        source.add(full)
        source.add(Connection(id = "plain", name = "Plain", serverUrl = "http://localhost:8080"))
        val export = dir.resolve("export.json")
        source.exportTo(export, icons = icons)
        val text = java.nio.file.Files.readString(export)
        assertTrue(text.contains("\"icons\"") && text.contains("\"data\"")) { "the custom icon travels inside the file" }
        assertTrue(text.contains("svc-launcher") && text.contains("forward.bat"))

        val targetIcons = CustomIconStore(dir.resolve("icons-b"))
        val target = ConnectionStore(dir.resolve("b/connections.json"))
        val summary = ConnectionImport.import(target, text, DedupePolicy.Merge, ConnectionImport.TunnelPolicy.Keep, targetIcons)
        assertEquals(2, summary.added)
        assertEquals(1, summary.icons)
        assertEquals(1, summary.forwards)

        val back = target.list().first { it.name == "Everything" }
        val filed = targetIcons.list().single()
        assertEquals(filed.key, back.icon) { "the connection points at the icon as filed on this machine" }
        assertEquals("lab", filed.label)
        assertTrue(targetIcons.bytes(filed.id)!!.isNotEmpty())
        // Everything but the identity the importing machine assigns — and the trust decisions, which are its own to make.
        val expected = full.copy(
            id = back.id, icon = back.icon, folderId = back.folderId, sortOrder = back.sortOrder, addedAt = back.addedAt, lastLaunchedAt = null,
            pinnedCertSha256 = null, autoApproveCertificates = false,
        )
        assertEquals(expected, back)
        val site = target.getFolder(back.folderId!!)!!
        assertEquals("Site", site.name)
        assertEquals("Region", target.getFolder(site.parentId!!)!!.name) { "the folder tree re-imports intact" }

        // Importing the same file again on the same machine is a no-op merge: nothing doubles, the icon is matched by content.
        val again = ConnectionImport.import(target, text, DedupePolicy.Merge, ConnectionImport.TunnelPolicy.Disarm, targetIcons)
        assertEquals(2, again.merged)
        assertEquals(1, targetIcons.list().size)
        assertEquals(2, target.list().size)
        assertEquals(true, target.list().first { it.name == "Everything" }.tunnelEnabled) { "an unchanged command keeps the local switch" }
    }

    @Test
    @DisplayName("trust never comes from a file: a pin and the auto-approve switch are dropped on import, kept on a merge, and a Replace keeps this machine's")
    fun trustDecisionsAreTheMachinesOwn(@TempDir dir: Path) {
        val pin = "ab".repeat(32)
        val text = """{"version":2,"connections":[{"id":"x","name":"Planted","serverUrl":"https://engine.example.internal:8443","pinnedCertSha256":"$pin","autoApproveCertificates":true}]}"""
        val fresh = ConnectionStore(dir.resolve("fresh.json"))
        ConnectionImport.import(fresh, text, DedupePolicy.Merge)
        val added = fresh.list().single()
        assertNull(added.pinnedCertSha256) { "a planted pin would skip the certificate review on the next start" }
        assertFalse(added.autoApproveCertificates) { "a planted auto-approve would accept whatever the server presents" }
        assertFalse(added.isPinned)

        for (policy in listOf(DedupePolicy.Merge, DedupePolicy.Replace)) {
            val store = ConnectionStore(dir.resolve("existing-$policy.json"))
            store.add(Connection(id = "mine", name = "Planted", serverUrl = "https://engine.example.internal:8443", pinnedCertSha256 = "cd".repeat(32), autoApproveCertificates = true))
            ConnectionImport.import(store, text, policy)
            val mine = store.get("mine")!!
            assertEquals("cd".repeat(32), mine.pinnedCertSha256) { "$policy keeps the machine's pin" }
            assertTrue(mine.autoApproveCertificates) { "$policy keeps the machine's auto-approve" }
        }
    }

    @Test
    @DisplayName("icons: only those an incoming connection uses are filed, and no more than MAX_ICONS")
    fun iconsBoundedAndReferenced(@TempDir dir: Path) {
        val icons = CustomIconStore(dir.resolve("icons"))
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><path fill="currentColor" d="M2 2h20v20H2z"/></svg>"""
        val data = java.util.Base64.getEncoder().encodeToString(svg.toByteArray())
        val unreferenced = (1..3).joinToString(",") { """{"id":"${"%016x".format(it.toLong())}","label":"stray $it","ext":"svg","data":"$data"}""" }
        val text = """{"version":2,"connections":[{"id":"x","name":"Uses one","serverUrl":"https://h:8443","icon":"custom:feedfacefeedface"}],""" +
            """"icons":[{"id":"feedfacefeedface","label":"used","ext":"svg","data":"$data"},$unreferenced]}"""
        val store = ConnectionStore(dir.resolve("connections.json"))
        val summary = ConnectionImport.import(store, text, DedupePolicy.Merge, ConnectionImport.TunnelPolicy.Drop, icons)
        assertEquals(1, summary.icons) { "the three stray icons are not decoded or filed" }
        assertEquals(1, icons.list().size)
        assertEquals(icons.list().single().key, store.list().single().icon)
    }

    @Test
    @DisplayName("a custom icon the file does not carry falls back to the default glyph unless this machine already has it")
    fun missingCustomIconFallsBack(@TempDir dir: Path) {
        val icons = CustomIconStore(dir.resolve("icons"))
        val store = ConnectionStore(dir.resolve("connections.json"))
        val text = """{"version":2,"connections":[{"id":"x","name":"Orphan","serverUrl":"https://h:8443","icon":"custom:0123456789abcdef"}]}"""
        ConnectionImport.import(store, text, DedupePolicy.Merge, ConnectionImport.TunnelPolicy.Drop, icons)
        assertEquals(Connection.DEFAULT_ICON_KEY, store.list().single().icon)
        val noIcons = ConnectionStore(dir.resolve("c2.json"))
        ConnectionImport.import(noIcons, text, DedupePolicy.Merge, ConnectionImport.TunnelPolicy.Drop)
        assertEquals("custom:0123456789abcdef", noIcons.list().single().icon) { "a caller that manages no icons gets the key as written" }
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
