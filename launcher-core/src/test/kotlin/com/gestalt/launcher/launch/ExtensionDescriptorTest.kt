/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.launch

import com.gestalt.launcher.gate.GestaltTrust
import com.gestalt.launcher.jnlp.JnlpParser
import com.gestalt.launcher.model.Connection
import com.gestalt.launcher.net.ArtifactFetcher
import com.gestalt.launcher.testutil.TestArtifacts
import com.gestalt.trust.TrustMode
import com.gestalt.trust.TrustStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

/**
 * A stock Mirth Connect `webstart.jnlp` lists the core `client-lib` jars inline and then one
 * `<extension href="webstart/extensions/<name>.jnlp"/>` per installed extension; each of those is a
 * component descriptor whose `<jar>`s are that extension's client classes (under
 * `webstart/extensions/libs/…`). The launcher must follow them — same host, same hardened parser,
 * bounded — or the Administrator starts with no connector / data-type / plugin code at all.
 */
class ExtensionDescriptorTest {

    private val base = "https://mirth.example.com:8443"

    private class FakeFetcher(private val responses: Map<String, ByteArray>) : ArtifactFetcher {
        val fetched: MutableList<String> = java.util.Collections.synchronizedList(ArrayList())
        override fun fetchBytes(uri: URI): ByteArray {
            fetched += uri.toString()
            return responses[uri.toString()] ?: throw java.io.IOException("no canned response for $uri")
        }
    }

    private fun signedJar(dir: Path, ca: TestArtifacts.Ca, fileName: String, entry: String): ByteArray {
        val leaf = TestArtifacts.codeSigningLeaf("CN=OIE,O=BrightCodeCompany OÜ,C=EE", ca)
        val jar = dir.resolve(fileName)
        TestArtifacts.writeSignedJar(jar, mapOf(entry to entry.toByteArray()), leaf.keyPair.private, leaf.chain)
        return Files.readAllBytes(jar)
    }

    private fun b64Sha256(bytes: ByteArray): String =
        java.util.Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun rootJnlp(coreSha: String, extensions: List<String>, version: String = "4.4.2"): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <jnlp spec="1.0+" codebase="$base" href="webstart.jnlp">
          <resources>
            <j2se version="1.9+" java-vm-args="--add-modules=java.sql.rowset"/>
            <jar href="webstart/client-lib/mirth-client.jar" main="true" download="eager" sha256="$coreSha"/>
            ${extensions.joinToString("\n            ") { """<extension href="$it"/>""" }}
          </resources>
          <application-desc main-class="com.mirth.connect.client.ui.Mirth">
            <argument>https://localhost:8443</argument>
            <argument>$version</argument>
          </application-desc>
        </jnlp>
    """.trimIndent()

    private fun componentJnlp(jarHref: String, sha: String, nested: List<String> = emptyList()): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <jnlp spec="1.0+" codebase="$base">
          <resources>
            <jar href="$jarHref" download="eager" sha256="$sha"/>
            ${nested.joinToString("\n            ") { """<extension href="$it"/>""" }}
          </resources>
          <component-desc/>
        </jnlp>
    """.trimIndent()

    private fun launcher(cacheDir: Path, ca: TestArtifacts.Ca, fetcher: ArtifactFetcher): LauncherCore =
        LauncherCore(
            trustStore = TrustStore.of(ca.cert),
            signerPins = GestaltTrust.OFFICIAL_SIGNER_PINS,
            gateMode = TrustMode.PIN_KNOWN_SIGNERS,
            cacheDir = cacheDir,
            fetcherFactory = { fetcher },
            sleeper = {},
        )

    private fun connection() = Connection(id = "mirth", name = "Mirth 4.4.2", serverUrl = base)

    @Test
    @DisplayName("real Mirth shape: extension jars are fetched, gated and on the classpath; same-basename jars don't collide")
    fun followsExtensionsAndKeepsSameBasenameJarsApart(@TempDir dir: Path) {
        val ca = TestArtifacts.selfSignedCa("CN=Pretend Root,O=Pretend CA,C=GB")
        val core = signedJar(dir, ca, "core.jar", "mirth/Main.class")
        val commonA = signedJar(dir, ca, "a-common.jar", "a/A.class")
        val commonB = signedJar(dir, ca, "b-common.jar", "b/B.class")
        // Two extensions each ship a jar called common.jar — different bytes, different digests.
        val responses = mapOf(
            "$base/webstart" to rootJnlp(b64Sha256(core), listOf("webstart/extensions/exta.jnlp", "webstart/extensions/extb.jnlp")).toByteArray(),
            "$base/webstart/extensions/exta.jnlp" to componentJnlp("webstart/extensions/libs/exta/common.jar", b64Sha256(commonA)).toByteArray(),
            "$base/webstart/extensions/extb.jnlp" to componentJnlp("webstart/extensions/libs/extb/common.jar", b64Sha256(commonB)).toByteArray(),
            "$base/webstart/client-lib/mirth-client.jar" to core,
            "$base/webstart/extensions/libs/exta/common.jar" to commonA,
            "$base/webstart/extensions/libs/extb/common.jar" to commonB,
        )
        val fetcher = FakeFetcher(responses)
        val cacheDir = dir.resolve("cache")

        val plan = launcher(cacheDir, ca, fetcher).prepareLaunch(connection())

        assertEquals(3, plan.admitted.size) { "core + both extension jars must be admitted: ${plan.admitted.map { it.artifactName }}" }
        assertEquals(
            listOf("webstart/client-lib/mirth-client.jar", "webstart/extensions/libs/exta/common.jar", "webstart/extensions/libs/extb/common.jar"),
            plan.admitted.map { it.artifactName },
        ) { "artifacts are named by server-relative path, root jars first" }

        val cp = plan.command[plan.command.indexOf("-cp") + 1].split(File.pathSeparator)
        assertEquals(3, cp.toSet().size) { "three DISTINCT classpath entries, no path listed twice: $cp" }
        assertTrue(cp.any { it.replace('\\', '/').endsWith("/exta/common.jar") }) { cp.toString() }
        assertTrue(cp.any { it.replace('\\', '/').endsWith("/extb/common.jar") }) { cp.toString() }

        // The cache mirrors the server-relative path under <cache>/<connection>/<version>/ — version taken
        // from the Administrator's second argument, not a <jnlp version> attribute (none here).
        assertEquals("4.4.2", plan.version)
        assertTrue(Files.isRegularFile(cacheDir.resolve("mirth/4.4.2/webstart/extensions/libs/exta/common.jar")))
        assertTrue(Files.isRegularFile(cacheDir.resolve("mirth/4.4.2/webstart/extensions/libs/extb/common.jar")))
        assertTrue(Files.isRegularFile(cacheDir.resolve("mirth/4.4.2/webstart/client-lib/mirth-client.jar")))

        // Every descriptor was fetched through the same (policy-bearing) fetcher.
        assertTrue(fetcher.fetched.contains("$base/webstart/extensions/exta.jnlp"))
        assertTrue(fetcher.fetched.contains("$base/webstart/extensions/extb.jnlp"))

        // And the client is pointed at the connection's address, not the JNLP's https://localhost:8443.
        assertEquals(listOf("https://mirth.example.com:8443", "4.4.2"), plan.appArgs)
    }

    @Test
    @DisplayName("an extension descriptor on another host is refused (same-host rule applies to descriptors too)")
    fun crossHostExtensionAborts(@TempDir dir: Path) {
        val ca = TestArtifacts.selfSignedCa("CN=Pretend Root,O=Pretend CA,C=GB")
        val core = signedJar(dir, ca, "core.jar", "mirth/Main.class")
        val responses = mapOf(
            "$base/webstart" to rootJnlp(b64Sha256(core), listOf("https://evil.example.org/webstart/extensions/x.jnlp")).toByteArray(),
            "$base/webstart/client-lib/mirth-client.jar" to core,
        )
        val ex = assertThrows(LaunchAbortedException::class.java) {
            launcher(dir.resolve("cache"), ca, FakeFetcher(responses)).prepareLaunch(connection())
        }
        assertTrue(ex.message.orEmpty().contains("evil.example.org")) { ex.message.orEmpty() }
    }

    @Test
    @DisplayName("descriptors that reference each other (a cycle) are each fetched once; the launch still succeeds")
    fun cyclicExtensionsTerminate(@TempDir dir: Path) {
        val ca = TestArtifacts.selfSignedCa("CN=Pretend Root,O=Pretend CA,C=GB")
        val core = signedJar(dir, ca, "core.jar", "mirth/Main.class")
        val a = signedJar(dir, ca, "a.jar", "a/A.class")
        val b = signedJar(dir, ca, "b.jar", "b/B.class")
        val responses = mapOf(
            "$base/webstart" to rootJnlp(b64Sha256(core), listOf("webstart/extensions/a.jnlp")).toByteArray(),
            // a -> b -> a, and a also references itself.
            "$base/webstart/extensions/a.jnlp" to componentJnlp("webstart/extensions/libs/a/a.jar", b64Sha256(a), nested = listOf("webstart/extensions/b.jnlp", "webstart/extensions/a.jnlp")).toByteArray(),
            "$base/webstart/extensions/b.jnlp" to componentJnlp("webstart/extensions/libs/b/b.jar", b64Sha256(b), nested = listOf("webstart/extensions/a.jnlp")).toByteArray(),
            "$base/webstart/client-lib/mirth-client.jar" to core,
            "$base/webstart/extensions/libs/a/a.jar" to a,
            "$base/webstart/extensions/libs/b/b.jar" to b,
        )
        val fetcher = FakeFetcher(responses)

        val plan = launcher(dir.resolve("cache"), ca, fetcher).prepareLaunch(connection())

        assertEquals(3, plan.admitted.size)
        assertEquals(1, fetcher.fetched.count { it.endsWith("/a.jnlp") }) { "a.jnlp fetched exactly once: ${fetcher.fetched}" }
        assertEquals(1, fetcher.fetched.count { it.endsWith("/b.jnlp") }) { "b.jnlp fetched exactly once: ${fetcher.fetched}" }
    }

    @Test
    @DisplayName("descriptors nested deeper than the bound abort the launch (no unbounded walk)")
    fun tooDeepNestingAborts(@TempDir dir: Path) {
        val ca = TestArtifacts.selfSignedCa("CN=Pretend Root,O=Pretend CA,C=GB")
        val core = signedJar(dir, ca, "core.jar", "mirth/Main.class")
        val leafJar = signedJar(dir, ca, "leaf.jar", "l/L.class")
        // root -> d1 -> d2 -> d3 -> d4: depth 4 exceeds the bound of 3.
        val responses = mutableMapOf(
            "$base/webstart" to rootJnlp(b64Sha256(core), listOf("webstart/extensions/d1.jnlp")).toByteArray(),
            "$base/webstart/client-lib/mirth-client.jar" to core,
        )
        for (i in 1..4) {
            val nested = if (i < 4) listOf("webstart/extensions/d${i + 1}.jnlp") else emptyList()
            responses["$base/webstart/extensions/d$i.jnlp"] =
                componentJnlp("webstart/extensions/libs/d$i/leaf.jar", b64Sha256(leafJar), nested).toByteArray()
            responses["$base/webstart/extensions/libs/d$i/leaf.jar"] = leafJar
        }
        assertThrows(LaunchAbortedException::class.java) {
            launcher(dir.resolve("cache"), ca, FakeFetcher(responses)).prepareLaunch(connection())
        }
    }

    @Test
    @DisplayName("the same artifact listed twice with different digests aborts (server contradicts itself)")
    fun contradictoryDigestsAbort(@TempDir dir: Path) {
        val ca = TestArtifacts.selfSignedCa("CN=Pretend Root,O=Pretend CA,C=GB")
        val core = signedJar(dir, ca, "core.jar", "mirth/Main.class")
        val shared = signedJar(dir, ca, "shared.jar", "s/S.class")
        val responses = mapOf(
            "$base/webstart" to rootJnlp(b64Sha256(core), listOf("webstart/extensions/a.jnlp", "webstart/extensions/b.jnlp")).toByteArray(),
            "$base/webstart/extensions/a.jnlp" to componentJnlp("webstart/extensions/libs/shared.jar", b64Sha256(shared)).toByteArray(),
            "$base/webstart/extensions/b.jnlp" to componentJnlp("webstart/extensions/libs/shared.jar", b64Sha256(core)).toByteArray(),
            "$base/webstart/client-lib/mirth-client.jar" to core,
            "$base/webstart/extensions/libs/shared.jar" to shared,
        )
        assertThrows(LaunchAbortedException::class.java) {
            launcher(dir.resolve("cache"), ca, FakeFetcher(responses)).prepareLaunch(connection())
        }
    }

    @Test
    @DisplayName("a path-bearing connection base keeps its path when resolving descriptor and jar hrefs")
    fun pathBearingBaseResolvesUnderThePath(@TempDir dir: Path) {
        val proxied = "https://proxy.example.com/mirth"
        val ca = TestArtifacts.selfSignedCa("CN=Pretend Root,O=Pretend CA,C=GB")
        val core = signedJar(dir, ca, "core.jar", "mirth/Main.class")
        val ext = signedJar(dir, ca, "ext.jar", "e/E.class")
        val root = """
            <?xml version="1.0" encoding="UTF-8"?>
            <jnlp spec="1.0+">
              <resources>
                <jar href="webstart/client-lib/mirth-client.jar" main="true" sha256="${b64Sha256(core)}"/>
                <extension href="webstart/extensions/e.jnlp"/>
              </resources>
              <application-desc main-class="com.mirth.connect.client.ui.Mirth">
                <argument>https://localhost:8443</argument><argument>4.4.2</argument>
              </application-desc>
            </jnlp>
        """.trimIndent()
        val responses = mapOf(
            // Served at both the server root (where Connection.webstartUri() still looks today) and under
            // /mirth/ (where it will look once the path-preserving fix lands) so this test covers both.
            "https://proxy.example.com/webstart" to root.toByteArray(),
            "$proxied/webstart" to root.toByteArray(),
            "$proxied/webstart/extensions/e.jnlp" to componentJnlp("webstart/extensions/libs/e/ext.jar", b64Sha256(ext)).toByteArray(),
            "$proxied/webstart/client-lib/mirth-client.jar" to core,
            "$proxied/webstart/extensions/libs/e/ext.jar" to ext,
        )
        val fetcher = FakeFetcher(responses)

        val plan = launcher(dir.resolve("cache"), ca, fetcher).prepareLaunch(Connection(id = "px", name = "Proxied", serverUrl = proxied))

        assertEquals(2, plan.admitted.size) { fetcher.fetched.toString() }
        assertEquals(listOf("webstart/client-lib/mirth-client.jar", "webstart/extensions/libs/e/ext.jar"), plan.admitted.map { it.artifactName }) {
            "artifact names are relative to the connection base path, not the server root"
        }
        assertTrue(fetcher.fetched.contains("$proxied/webstart/extensions/libs/e/ext.jar")) { fetcher.fetched.toString() }
    }

    @Test
    @DisplayName("real extension shape: codebase …/webstart/extensions with jars listed as libs/<name>/<jar> — resolved against the descriptor's codebase path, on the TYPED origin")
    fun extensionCodebasePathIsHonouredOnTheTypedOrigin(@TempDir dir: Path) {
        // A stock Mirth extension descriptor. Note the codebase names the server's OWN address
        // (localhost:8443 — what the server thinks it is), while the user reaches it through a forward on
        // another port: only the codebase PATH is used, the origin is the connection's.
        val typed = "https://localhost:31065"
        val ca = TestArtifacts.selfSignedCa("CN=Pretend Root,O=Pretend CA,C=GB")
        val core = signedJar(dir, ca, "core.jar", "mirth/Main.class")
        val shared = signedJar(dir, ca, "scriptfilestep-shared.jar", "sfs/Shared.class")
        val client = signedJar(dir, ca, "scriptfilestep-client.jar", "sfs/Client.class")
        val root = """
            <?xml version="1.0" encoding="UTF-8"?>
            <jnlp spec="1.0+" codebase="https://localhost:8443" href="webstart.jnlp">
              <resources>
                <jar href="webstart/client-lib/mirth-client.jar" main="true" sha256="${b64Sha256(core)}"/>
                <extension href="webstart/extensions/scriptfilestep.jnlp"/>
              </resources>
              <application-desc main-class="com.mirth.connect.client.ui.Mirth">
                <argument>https://localhost:8443</argument><argument>4.4.2</argument>
              </application-desc>
            </jnlp>
        """.trimIndent()
        val extension = """
            <?xml version="1.0" encoding="UTF-8"?>
            <jnlp spec="1.0+" codebase="https://localhost:8443/webstart/extensions" href="scriptfilestep.jnlp">
              <resources>
                <jar href="libs/scriptfilestep/scriptfilestep-shared.jar" sha256="${b64Sha256(shared)}"/>
                <jar href="libs/scriptfilestep/scriptfilestep-client.jar" sha256="${b64Sha256(client)}"/>
              </resources>
              <component-desc/>
            </jnlp>
        """.trimIndent()
        val responses = mapOf(
            "$typed/webstart" to root.toByteArray(),
            "$typed/webstart/extensions/scriptfilestep.jnlp" to extension.toByteArray(),
            "$typed/webstart/client-lib/mirth-client.jar" to core,
            "$typed/webstart/extensions/libs/scriptfilestep/scriptfilestep-shared.jar" to shared,
            "$typed/webstart/extensions/libs/scriptfilestep/scriptfilestep-client.jar" to client,
        )
        val fetcher = FakeFetcher(responses)

        val plan = launcher(dir.resolve("cache"), ca, fetcher).prepareLaunch(Connection(id = "fwd", name = "Forwarded", serverUrl = typed))

        assertEquals(
            listOf("webstart/client-lib/mirth-client.jar", "webstart/extensions/libs/scriptfilestep/scriptfilestep-shared.jar", "webstart/extensions/libs/scriptfilestep/scriptfilestep-client.jar"),
            plan.admitted.map { it.artifactName },
        ) { fetcher.fetched.toString() }
        assertTrue(fetcher.fetched.none { it.contains("localhost:8443") }) { "nothing is fetched from the server's self-reported address: ${fetcher.fetched}" }
        assertTrue(fetcher.fetched.none { it == "$typed/libs/scriptfilestep/scriptfilestep-shared.jar" }) { "the extension jar is not resolved against the root" }
    }

    @Test
    @DisplayName("a path-bearing connection keeps its prefix under an extension codebase the server reports without it")
    fun extensionCodebaseUnderAProxyPrefix(@TempDir dir: Path) {
        val proxied = "https://proxy.example.com/mirth"
        val ca = TestArtifacts.selfSignedCa("CN=Pretend Root,O=Pretend CA,C=GB")
        val core = signedJar(dir, ca, "core.jar", "mirth/Main.class")
        val ext = signedJar(dir, ca, "ext.jar", "e/E.class")
        val root = rootJnlp(b64Sha256(core), listOf("webstart/extensions/e.jnlp"))
        val extension = """
            <?xml version="1.0" encoding="UTF-8"?>
            <jnlp spec="1.0+" codebase="https://mirth.example.com:8443/webstart/extensions">
              <resources><jar href="libs/e/ext.jar" sha256="${b64Sha256(ext)}"/></resources>
              <component-desc/>
            </jnlp>
        """.trimIndent()
        val responses = mapOf(
            "$proxied/webstart" to root.toByteArray(),
            "$proxied/webstart/extensions/e.jnlp" to extension.toByteArray(),
            "$proxied/webstart/client-lib/mirth-client.jar" to core,
            "$proxied/webstart/extensions/libs/e/ext.jar" to ext,
        )
        val fetcher = FakeFetcher(responses)

        val plan = launcher(dir.resolve("cache"), ca, fetcher).prepareLaunch(Connection(id = "px", name = "Proxied", serverUrl = proxied))

        assertEquals(listOf("webstart/client-lib/mirth-client.jar", "webstart/extensions/libs/e/ext.jar"), plan.admitted.map { it.artifactName }) { fetcher.fetched.toString() }
        assertTrue(fetcher.fetched.contains("$proxied/webstart/extensions/libs/e/ext.jar")) { fetcher.fetched.toString() }
    }

    @Test
    @DisplayName("serverVersionOf: second argument wins, then the jnlp version attribute, else 'current'")
    fun serverVersionRule() {
        fun parse(args: List<String>, attr: String?): String = LauncherCore.serverVersionOf(
            JnlpParser.parse(
                """
                <jnlp spec="1.0+" ${if (attr != null) "version=\"$attr\"" else ""}>
                  <resources><jar href="core.jar" main="true"/></resources>
                  <application-desc main-class="x.Main">${args.joinToString("") { "<argument>$it</argument>" }}</application-desc>
                </jnlp>
                """.trimIndent(),
            ),
        )
        assertEquals("4.4.2", parse(listOf("https://localhost:8443", "4.4.2"), null))
        assertEquals("4.5.2", parse(listOf("https://localhost:8443", "4.5.2"), "1.0.0")) { "the argument wins over the attribute" }
        assertEquals("3.12.0-SNAPSHOT", parse(listOf("https://localhost:8443", "3.12.0-SNAPSHOT"), null))
        assertEquals("1.0.0", parse(emptyList(), "1.0.0"))
        assertEquals("1.0.0", parse(listOf("https://localhost:8443", "not-a-version"), "1.0.0"))
        assertEquals("current", parse(emptyList(), null))
    }
}
