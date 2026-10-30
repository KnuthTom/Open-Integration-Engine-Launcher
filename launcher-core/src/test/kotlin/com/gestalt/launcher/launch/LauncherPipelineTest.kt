/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.launch

import com.gestalt.launcher.gate.GestaltTrust
import com.gestalt.launcher.net.ArtifactFetcher
import com.gestalt.launcher.model.Connection
import com.gestalt.launcher.testutil.TestArtifacts
import com.gestalt.trust.TrustMode
import com.gestalt.trust.TrustStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

/**
 * End-to-end launch pipeline (with an offline fake fetcher): proves the single gate is the only path
 * onto the classpath, that any verification miss aborts the launch closed, that a JNLP can't weaken
 * verification, and that the legacy-TLS downgrade is strictly opt-in.
 */
class LauncherPipelineTest {

    private val base = "https://launcher.example.com/"

    private class FakeFetcher(private val responses: Map<String, ByteArray>) : ArtifactFetcher {
        override fun fetchBytes(uri: URI): ByteArray =
            responses[uri.toString()] ?: throw java.io.IOException("no canned response for $uri")
    }

    private fun jnlp(resourcesExtra: String = ""): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <jnlp spec="1.0+" version="1.0.0">
          <resources>
            $resourcesExtra
            <jar href="core.jar" main="true"/>
          </resources>
          <application-desc main-class="com.mirth.connect.client.ui.Mirth"/>
        </jnlp>
    """.trimIndent()

    /** Build a launcher whose fetcher serves the given JNLP/manifest/jar; trust anchored at [ca]. */
    private fun launcher(
        cacheDir: Path,
        ca: TestArtifacts.Ca,
        jnlpXml: String,
        manifestText: String,
        jarBytes: ByteArray,
        mode: TrustMode = TrustMode.PIN_KNOWN_SIGNERS,
    ): LauncherCore {
        val responses = mapOf(
            "${base}webstart" to jnlpXml.toByteArray(),
            "${base}digest/1.0.0" to manifestText.toByteArray(),
            "${base}core.jar" to jarBytes,
        )
        return LauncherCore(
            trustStore = TrustStore.of(ca.cert),
            signerPins = GestaltTrust.OFFICIAL_SIGNER_PINS,
            gateMode = mode,
            cacheDir = cacheDir,
            // A private publisher store so an "unknown signer" case here never pollutes the shared one.
            trustedSigners = com.gestalt.launcher.gate.TrustedSignerStore(cacheDir.resolveSibling("trusted-signers.json")),
            fetcherFactory = { FakeFetcher(responses) },
        )
    }

    private fun signedCoreJar(dir: Path, ca: TestArtifacts.Ca, subjectDn: String): ByteArray {
        val leaf = TestArtifacts.codeSigningLeaf(subjectDn, ca)
        val jar = dir.resolve("build-core.jar")
        TestArtifacts.writeSignedJar(jar, mapOf("mirth/Main.class" to "main".toByteArray()), leaf.keyPair.private, leaf.chain)
        return Files.readAllBytes(jar)
    }

    private fun manifestForCore(jarBytes: ByteArray): String =
        "${com.gestalt.launcher.digest.DigestVerifier.sha256Hex(jarBytes)}  core.jar"

    /** A JNLP that carries the integrity digest inline (base64 SHA-256), the way a real OIE/Mirth server does. */
    private fun jnlpWithInlineDigest(sha256Base64: String): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <jnlp spec="1.0+" version="1.0.0">
          <resources>
            <jar href="core.jar" main="true" sha256="$sha256Base64"/>
          </resources>
          <application-desc main-class="com.mirth.connect.client.ui.Mirth"/>
        </jnlp>
    """.trimIndent()

    private fun sha256Base64(bytes: ByteArray): String =
        java.util.Base64.getEncoder().encodeToString(
            java.security.MessageDigest.getInstance("SHA-256").digest(bytes),
        )

    /** A JNLP whose Java-9+ profile carries the module flags the client needs, plus hostile extras. */
    private fun jnlpWithVmArgs(): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <jnlp spec="1.0+" version="1.0.0">
          <resources>
            <j2se version="1.9+" java-vm-args="--add-modules=java.sql.rowset --add-opens=java.base/java.lang=ALL-UNNAMED -javaagent:/tmp/evil.jar -Djavax.net.ssl.trustStore=/tmp/evil -Xmx999g"/>
            <jar href="core.jar" main="true"/>
          </resources>
          <application-desc main-class="com.mirth.connect.client.ui.Mirth"/>
        </jnlp>
    """.trimIndent()

    private fun connection(legacyTls: Boolean = false) =
        Connection(id = "prod", name = "Prod", serverUrl = base, username = "alice", legacyTlsCompatibility = legacyTls)

    @Test
    @DisplayName("happy path: only the verified jar is admitted; child JVM is default-secure")
    fun admitsOnlyVerifiedJarsDefaultSecure(@TempDir dir: Path) {
        val ca = TestArtifacts.selfSignedCa("CN=Pretend Root,O=Pretend CA,C=GB")
        val jarBytes = signedCoreJar(dir, ca, "CN=OIE,O=BrightCodeCompany OÜ,C=EE")
        val launcher = launcher(dir.resolve("cache"), ca, jnlp(), manifestForCore(jarBytes), jarBytes)

        val plan = launcher.prepareLaunch(connection())

        assertEquals(1, plan.admitted.size)
        assertEquals("com.mirth.connect.client.ui.Mirth", plan.mainClass)
        assertTrue(plan.command.contains("-cp"))
        assertTrue(plan.command.any { it.contains("core.jar") })
        assertTrue(plan.command.contains("-Dhttps.protocols=TLSv1.2,TLSv1.3"))
        assertFalse(plan.command.any { it.contains("ephemeralDHKeySize") }) { "legacy DH must be OFF by default" }
    }

    @Test
    @DisplayName("digest mismatch aborts the launch closed (nothing admitted)")
    fun digestMismatchAbortsClosed(@TempDir dir: Path) {
        val ca = TestArtifacts.selfSignedCa("CN=Pretend Root,O=Pretend CA,C=GB")
        val jarBytes = signedCoreJar(dir, ca, "CN=OIE,O=BrightCodeCompany OÜ,C=EE")
        val wrongManifest = "${"0".repeat(64)}  core.jar"
        val launcher = launcher(dir.resolve("cache"), ca, jnlp(), wrongManifest, jarBytes)
        assertThrows(LaunchAbortedException::class.java) { launcher.prepareLaunch(connection()) }
    }

    @Test
    @DisplayName("unknown signer aborts the launch closed in PIN mode (as a publisher review, never an admission)")
    fun unknownSignerAbortsClosed(@TempDir dir: Path) {
        val ca = TestArtifacts.selfSignedCa("CN=Pretend Root,O=Pretend CA,C=GB")
        val jarBytes = signedCoreJar(dir, ca, "CN=Rogue,O=Unknown Vendor,C=US")
        val launcher = launcher(dir.resolve("cache"), ca, jnlp(), manifestForCore(jarBytes), jarBytes)
        val ex = assertThrows(LaunchAbortedException::class.java) { launcher.prepareLaunch(connection()) }
        assertTrue(ex is PublisherReviewRequiredException) { "an intact jar from an unknown publisher is reviewable: $ex" }
    }

    @Test
    @DisplayName("a JNLP 'allow-incorrect-digest' directive cannot prevent the abort")
    fun jnlpDirectiveCannotWeakenPipeline(@TempDir dir: Path) {
        val ca = TestArtifacts.selfSignedCa("CN=Pretend Root,O=Pretend CA,C=GB")
        val jarBytes = signedCoreJar(dir, ca, "CN=OIE,O=BrightCodeCompany OÜ,C=EE")
        val weakeningJnlp = jnlp("""<property name="allow-incorrect-digest" value="true"/>""")
        val wrongManifest = "${"0".repeat(64)}  core.jar"
        val launcher = launcher(dir.resolve("cache"), ca, weakeningJnlp, wrongManifest, jarBytes)
        assertThrows(LaunchAbortedException::class.java) { launcher.prepareLaunch(connection()) }
    }

    @Test
    @DisplayName("http connection: fetches JNLP/digest/jars over HTTP (no TLS); signatures still enforced")
    fun httpConnectionUsesPlainHttp(@TempDir dir: Path) {
        val httpBase = "http://localhost:8080/"
        val ca = TestArtifacts.selfSignedCa("CN=Pretend Root,O=Pretend CA,C=GB")
        val jarBytes = signedCoreJar(dir, ca, "CN=OIE,O=BrightCodeCompany OÜ,C=EE")
        val responses = mapOf(
            "${httpBase}webstart" to jnlp().toByteArray(),
            "${httpBase}digest/1.0.0" to manifestForCore(jarBytes).toByteArray(),
            "${httpBase}core.jar" to jarBytes,
        )
        val launcher = LauncherCore(
            trustStore = TrustStore.of(ca.cert),
            signerPins = GestaltTrust.OFFICIAL_SIGNER_PINS,
            gateMode = TrustMode.PIN_KNOWN_SIGNERS,
            cacheDir = dir.resolve("cache"),
            fetcherFactory = { FakeFetcher(responses) },
        )
        val plan = launcher.prepareLaunch(Connection(id = "http", name = "HTTP", serverUrl = httpBase))
        assertEquals(1, plan.admitted.size) { "the signed jar is admitted over plain http" }
        assertTrue(plan.command.any { it.contains("core.jar") })
    }

    @Test
    @DisplayName("https connection refuses a JNLP jar href that downgrades to http")
    fun httpsRefusesDowngradeJar(@TempDir dir: Path) {
        val ca = TestArtifacts.selfSignedCa("CN=Pretend Root,O=Pretend CA,C=GB")
        val jarBytes = signedCoreJar(dir, ca, "CN=OIE,O=BrightCodeCompany OÜ,C=EE")
        // Same host (passes the SSRF host-check) but an http jar URL while the connection is https.
        val downgradeJnlp = """
            <?xml version="1.0" encoding="UTF-8"?>
            <jnlp spec="1.0+" version="1.0.0">
              <resources>
                <jar href="http://launcher.example.com/core.jar" main="true"/>
              </resources>
              <application-desc main-class="com.mirth.connect.client.ui.Mirth"/>
            </jnlp>
        """.trimIndent()
        val launcher = launcher(dir.resolve("cache"), ca, downgradeJnlp, manifestForCore(jarBytes), jarBytes)
        assertThrows(LaunchAbortedException::class.java) { launcher.prepareLaunch(connection()) }
    }

    @Test
    @DisplayName("inline JNLP sha256 is used and NO external /digest is fetched (real OIE/Mirth shape)")
    fun inlineDigestsLaunchWithoutExternalManifest(@TempDir dir: Path) {
        val ca = TestArtifacts.selfSignedCa("CN=Pretend Root,O=Pretend CA,C=GB")
        val jarBytes = signedCoreJar(dir, ca, "CN=OIE,O=BrightCodeCompany OÜ,C=EE")
        // Serve the JNLP (with inline digest) and the jar, but deliberately NOT digest/1.0.0 — the
        // FakeFetcher throws if the pipeline reaches for it, which is exactly the 404 a stock server gives.
        val responses = mapOf(
            "${base}webstart" to jnlpWithInlineDigest(sha256Base64(jarBytes)).toByteArray(),
            "${base}core.jar" to jarBytes,
        )
        val launcher = LauncherCore(
            trustStore = TrustStore.of(ca.cert),
            signerPins = GestaltTrust.OFFICIAL_SIGNER_PINS,
            gateMode = TrustMode.PIN_KNOWN_SIGNERS,
            cacheDir = dir.resolve("cache"),
            fetcherFactory = { FakeFetcher(responses) },
        )

        val plan = launcher.prepareLaunch(connection())
        assertEquals(1, plan.admitted.size) { "the signed jar is admitted using the inline digest" }
        assertTrue(plan.command.any { it.contains("core.jar") })
    }

    @Test
    @DisplayName("host-only base URL resolves a relative jar href WITH the separating slash (real Mirth shape)")
    fun hostOnlyBaseResolvesRelativeHrefs(@TempDir dir: Path) {
        val httpBase = "http://localhost:8080" // no trailing slash, no path — exactly the real connection shape
        val ca = TestArtifacts.selfSignedCa("CN=Pretend Root,O=Pretend CA,C=GB")
        val jarBytes = signedCoreJar(dir, ca, "CN=OIE,O=BrightCodeCompany OÜ,C=EE")
        val jnlp = """
            <?xml version="1.0" encoding="UTF-8"?>
            <jnlp spec="1.0+" version="4.5.2">
              <resources>
                <jar href="webstart/client-lib/core.jar" main="true" sha256="${sha256Base64(jarBytes)}"/>
              </resources>
              <application-desc main-class="com.mirth.connect.client.ui.Mirth"/>
            </jnlp>
        """.trimIndent()
        val responses = mapOf(
            "$httpBase/webstart" to jnlp.toByteArray(),
            // The ONLY canned jar URL has the slash; the buggy resolution would request "8080webstart/..." and miss.
            "$httpBase/webstart/client-lib/core.jar" to jarBytes,
        )
        val launcher = LauncherCore(
            trustStore = TrustStore.of(ca.cert),
            signerPins = GestaltTrust.OFFICIAL_SIGNER_PINS,
            gateMode = TrustMode.PIN_KNOWN_SIGNERS,
            cacheDir = dir.resolve("cache"),
            fetcherFactory = { FakeFetcher(responses) },
        )

        val plan = launcher.prepareLaunch(Connection(id = "live", name = "Live", serverUrl = httpBase))
        assertEquals(1, plan.admitted.size) { "the relative href must resolve to $httpBase/webstart/client-lib/core.jar" }
    }

    @Test
    @DisplayName("a tampered inline JNLP digest aborts closed (no external fallback rescues it)")
    fun inlineDigestMismatchAbortsClosed(@TempDir dir: Path) {
        val ca = TestArtifacts.selfSignedCa("CN=Pretend Root,O=Pretend CA,C=GB")
        val jarBytes = signedCoreJar(dir, ca, "CN=OIE,O=BrightCodeCompany OÜ,C=EE")
        val wrongDigest = java.util.Base64.getEncoder().encodeToString(ByteArray(32)) // SHA-256 of nothing real
        val responses = mapOf(
            "${base}webstart" to jnlpWithInlineDigest(wrongDigest).toByteArray(),
            "${base}core.jar" to jarBytes,
        )
        val launcher = LauncherCore(
            trustStore = TrustStore.of(ca.cert),
            signerPins = GestaltTrust.OFFICIAL_SIGNER_PINS,
            gateMode = TrustMode.PIN_KNOWN_SIGNERS,
            cacheDir = dir.resolve("cache"),
            fetcherFactory = { FakeFetcher(responses) },
        )
        assertThrows(LaunchAbortedException::class.java) { launcher.prepareLaunch(connection()) }
    }

    @Test
    @DisplayName("client module flags pass through (Java 9+) but server -javaagent/-D/-X are dropped")
    fun curatedModuleArgsPassThrough(@TempDir dir: Path) {
        val ca = TestArtifacts.selfSignedCa("CN=Pretend Root,O=Pretend CA,C=GB")
        val jarBytes = signedCoreJar(dir, ca, "CN=OIE,O=BrightCodeCompany OÜ,C=EE")
        val launcher = launcher(dir.resolve("cache"), ca, jnlpWithVmArgs(), manifestForCore(jarBytes), jarBytes)

        // Default runtime = the launcher's own (this test) JVM, which is Java 9+, so the flags apply.
        val plan = launcher.prepareLaunch(connection())

        assertTrue(plan.command.contains("--add-opens=java.base/java.lang=ALL-UNNAMED")) { plan.command.toString() }
        assertTrue(plan.command.contains("--add-modules=java.sql.rowset"))
        assertFalse(plan.command.any { it.startsWith("-javaagent") }) { "server agent must never be passed" }
        assertFalse(plan.command.any { it.contains("javax.net.ssl.trustStore") }) { "server -D must never be passed" }
        assertFalse(plan.command.any { it == "-Xmx999g" }) { "server -Xmx must never be passed" }
        // The module flags must precede the classpath/main-class (they are JVM options, not app args).
        assertTrue(plan.command.indexOf("--add-opens=java.base/java.lang=ALL-UNNAMED") < plan.command.indexOf("-cp"))
    }

    @Test
    @DisplayName("legacy TLS compatibility is strictly opt-in per connection")
    fun legacyTlsIsOptIn(@TempDir dir: Path) {
        val ca = TestArtifacts.selfSignedCa("CN=Pretend Root,O=Pretend CA,C=GB")
        val jarBytes = signedCoreJar(dir, ca, "CN=OIE,O=BrightCodeCompany OÜ,C=EE")
        val launcher = launcher(dir.resolve("cache"), ca, jnlp(), manifestForCore(jarBytes), jarBytes)

        val plan = launcher.prepareLaunch(connection(legacyTls = true))
        assertTrue(plan.command.contains("-Djdk.tls.ephemeralDHKeySize=legacy")) { "opt-in flag should appear" }
    }
}
