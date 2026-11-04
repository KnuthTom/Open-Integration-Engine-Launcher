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
import org.junit.jupiter.api.Assertions.assertNotNull
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
        commandBuilder: ChildJvmCommandBuilder = ChildJvmCommandBuilder(),
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
            commandBuilder = commandBuilder,
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

    @Test
    @DisplayName("Automatic runtime: the bundled Java 8 for a 4.4 server, the bundled Java 17 for 4.7, an explicit Bundled choice as chosen, the launcher's JVM when nothing is staged")
    fun automaticRuntimeFollowsServerVersion(@TempDir dir: Path) {
        val ca = TestArtifacts.selfSignedCa("CN=Pretend Root,O=Pretend CA,C=GB")
        val jarBytes = signedCoreJar(dir, ca, "CN=OIE,O=BrightCodeCompany OÜ,C=EE")
        // Two staged runtimes, the way a packaged install carries them.
        val resources = dir.resolve("resources")
        val jre8 = resources.resolve("runtimes").resolve("jre8")
        val jre17 = resources.resolve("runtimes").resolve("jre17")
        for ((home, v) in listOf(jre8 to "1.8.0_504", jre17 to "17.0.20.1")) {
            Files.createDirectories(home.resolve("bin"))
            Files.writeString(home.resolve("release"), "JAVA_VERSION=\"$v\"\n")
        }
        val launcherJdk = dir.resolve("launcher-jdk").resolve("bin").resolve("java")
        fun launcherFor(version: String, resourcesDir: Path?, windows: Boolean = false): LauncherCore {
            val responses = mapOf(
                "${base}webstart" to jnlpWithArgs(version, sha256Base64(jarBytes)).toByteArray(),
                "${base}core.jar" to jarBytes,
            )
            // The builder reads the packaged-resources property; point it at this test's staging (or nowhere).
            if (resourcesDir != null) System.setProperty("compose.application.resources.dir", resourcesDir.toString())
            else System.clearProperty("compose.application.resources.dir")
            return LauncherCore(
                trustStore = TrustStore.of(ca.cert),
                signerPins = GestaltTrust.OFFICIAL_SIGNER_PINS,
                gateMode = TrustMode.PIN_KNOWN_SIGNERS,
                cacheDir = dir.resolve("cache-$version-${resourcesDir != null}-$windows"),
                commandBuilder = ChildJvmCommandBuilder(javaExe = launcherJdk.toString(), windowsDesktop = windows),
                trustedSigners = com.gestalt.launcher.gate.TrustedSignerStore(dir.resolve("trusted-signers.json")),
                fetcherFactory = { FakeFetcher(responses) },
            )
        }
        fun javaDirOf(command: List<String>) = Path.of(command[0]).parent.parent
        val previous = System.getProperty("compose.application.resources.dir")
        try {
            assertEquals(jre8, javaDirOf(launcherFor("4.4.2", resources).prepareLaunch(connection()).command)) { "4.4.2 → Java 8" }
            assertEquals(jre8, javaDirOf(launcherFor("4.6.1", resources).prepareLaunch(connection()).command)) { "4.6 → still Java 8" }
            assertEquals(jre17, javaDirOf(launcherFor("4.7.0", resources).prepareLaunch(connection()).command)) { "4.7 → Java 17" }
            val explicit17 = connection().copy(javaHome = com.gestalt.launcher.model.JavaHomeConfig(com.gestalt.launcher.model.JavaHomeMode.Bundled, "17"))
            assertEquals(jre17, javaDirOf(launcherFor("4.4.2", resources).prepareLaunch(explicit17).command)) { "an explicit choice is honoured" }
            assertEquals(launcherJdk.parent.parent, javaDirOf(launcherFor("4.4.2", null).prepareLaunch(connection()).command)) { "nothing staged → the launcher's own JVM" }

            // On Windows the client runs through its own process host when the package built one into the runtime.
            val onWindows = launcherFor("4.7.0", resources, windows = true)
            assertEquals("javaw.exe", Path.of(onWindows.prepareLaunch(connection()).command[0]).fileName.toString()) { "no host in the runtime: the JVM's own windowless launcher" }
            Files.writeString(jre17.resolve("bin").resolve("jli.dll"), "")
            Files.writeString(jre17.resolve("bin").resolve("${ChildJvmCommandBuilder.ADMINISTRATOR_HOST}.exe"), "")
            Files.writeString(jre17.resolve("bin").resolve("${ChildJvmCommandBuilder.ADMINISTRATOR_HOST}-console.exe"), "")
            assertEquals("${ChildJvmCommandBuilder.ADMINISTRATOR_HOST}.exe", Path.of(onWindows.prepareLaunch(connection()).command[0]).fileName.toString()) { "the host: Task Manager names the process by it" }
            assertEquals("${ChildJvmCommandBuilder.ADMINISTRATOR_HOST}-console.exe", Path.of(onWindows.prepareLaunch(connection().copy(showJavaConsole = true)).command[0]).fileName.toString()) { "the console variant when a console is asked for" }
            assertEquals(jre17, javaDirOf(onWindows.prepareLaunch(connection()).command)) { "still that runtime's bin" }
        } finally {
            if (previous == null) System.clearProperty("compose.application.resources.dir") else System.setProperty("compose.application.resources.dir", previous)
        }
    }

    /** A JNLP with the stock two arguments — the server's URL and its version — and an inline digest. */
    private fun jnlpWithArgs(version: String, sha256Base64: String): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <jnlp spec="1.0+">
          <resources>
            <jar href="core.jar" main="true" sha256="$sha256Base64"/>
          </resources>
          <application-desc main-class="com.mirth.connect.client.ui.Mirth">
            <argument>https://launcher.example.com:8443</argument>
            <argument>$version</argument>
          </application-desc>
        </jnlp>
    """.trimIndent()

    @Test
    @DisplayName("admission cache: identical bytes under the same policy reuse the recorded verdict; a tampered file is still caught by the digest; another policy re-verifies")
    fun admissionCacheReusesVerdicts(@TempDir dir: Path) {
        val ca = TestArtifacts.selfSignedCa("CN=Pretend Root,O=Pretend CA,C=GB")
        val jarBytes = signedCoreJar(dir, ca, "CN=OIE,O=BrightCodeCompany OÜ,C=EE")
        val cacheDir = dir.resolve("cache")
        val signers = com.gestalt.launcher.gate.TrustedSignerStore(dir.resolve("trusted-signers.json"))
        val admissions = com.gestalt.launcher.gate.AdmissionCache(cacheDir.resolve("admissions.json"))
        val responses = mapOf(
            "${base}webstart" to jnlpWithInlineDigest(sha256Base64(jarBytes)).toByteArray(),
            "${base}core.jar" to jarBytes,
        )
        fun core(mode: TrustMode) = LauncherCore(
            trustStore = TrustStore.of(ca.cert), signerPins = GestaltTrust.OFFICIAL_SIGNER_PINS, gateMode = mode,
            cacheDir = cacheDir, trustedSigners = signers, fetcherFactory = { FakeFetcher(responses) }, admissions = admissions,
        )

        // First launch: full verification, verdict recorded.
        val first = core(TrustMode.PIN_KNOWN_SIGNERS).prepareLaunch(connection())
        val sha = com.gestalt.launcher.digest.DigestVerifier.sha256Hex(jarBytes)
        val policy = com.gestalt.launcher.gate.AdmissionCache.policyFingerprint(TrustMode.PIN_KNOWN_SIGNERS, GestaltTrust.OFFICIAL_SIGNER_PINS, TrustStore.of(ca.cert), signers)
        val recorded = admissions.lookup(sha, policy)
        assertNotNull(recorded)
        assertEquals(first.admitted.single().signerDN, recorded!!.signerDN)

        // Second launch: the recorded verdict is what the gate uses — provable by planting a marker in it.
        admissions.record(recorded.copy(signerDN = "CN=From The Cache,O=Example Lab"))
        val second = core(TrustMode.PIN_KNOWN_SIGNERS).prepareLaunch(connection())
        assertEquals("CN=From The Cache,O=Example Lab", second.admitted.single().signerDN) { "the cached admission was used" }

        // Another policy: the marker is not honoured; full verification gives the real signer.
        val caRoot = core(TrustMode.CA_ROOT).prepareLaunch(connection())
        assertEquals(first.admitted.single().signerDN, caRoot.admitted.single().signerDN)

        // Altered bytes on disk, with the server now serving altered bytes too but still publishing the
        // ORIGINAL digest. The slot's bytes fail the digest check — and the shared pool holds the bytes
        // that digest names (verified on the first launch), so the slot is healed from it and the launch
        // goes ahead on exactly the bytes the server publishes; nothing altered is ever admitted.
        val slot = first.admitted.single().file
        Files.write(slot, jarBytes + byteArrayOf(0x00))
        val tampered = mapOf("${base}webstart" to responses.getValue("${base}webstart"), "${base}core.jar" to jarBytes + byteArrayOf(0x00))
        fun poisoned(pool: JarPool?) = LauncherCore(
            trustStore = TrustStore.of(ca.cert), signerPins = GestaltTrust.OFFICIAL_SIGNER_PINS, gateMode = TrustMode.PIN_KNOWN_SIGNERS,
            cacheDir = cacheDir, trustedSigners = signers, fetcherFactory = { FakeFetcher(tampered) }, admissions = admissions, jarPool = pool,
        )
        val healed = poisoned(JarPool(cacheDir.resolve(JarPool.DIR_NAME))).prepareLaunch(connection())
        assertEquals(sha, healed.admitted.single().sha256) { "the pool restored the published bytes" }
        assertEquals(jarBytes.toList(), Files.readAllBytes(slot).toList())

        // Without the pool (or with its entry gone too), the digest check catches the altered bytes and the launch aborts.
        Files.write(slot, jarBytes + byteArrayOf(0x00))
        val ex = assertThrows(LaunchAbortedException::class.java) { poisoned(null).prepareLaunch(connection()) }
        assertTrue(ex.message!!.contains("digest mismatch")) { ex.message }
    }

    @Test
    @DisplayName("display scale is not a setting: on a Windows desktop the JVM draws at 100% and the desktop scales the window; elsewhere Java follows the monitor; a stored uiScale is ignored")
    fun displayScaleIsHardWired(@TempDir dir: Path) {
        val ca = TestArtifacts.selfSignedCa("CN=Pretend Root,O=Pretend CA,C=GB")
        val jarBytes = signedCoreJar(dir, ca, "CN=OIE,O=BrightCodeCompany OÜ,C=EE")
        val onWindows = launcher(dir.resolve("cache"), ca, jnlp(), manifestForCore(jarBytes), jarBytes, commandBuilder = ChildJvmCommandBuilder(windowsDesktop = true))
        val elsewhere = launcher(dir.resolve("cache2"), ca, jnlp(), manifestForCore(jarBytes), jarBytes, commandBuilder = ChildJvmCommandBuilder(windowsDesktop = false))

        for (stored in listOf(null, "1.25", "0.75", "monitor", "windows", "9; -javaagent:x")) {
            val win = onWindows.prepareLaunch(connection().copy(uiScale = stored)).command
            assertTrue(win.contains("-Dsun.java2d.uiScale=${ChildJvmCommandBuilder.WINDOWS_UI_SCALE}")) { "uiScale=$stored: on Windows the JVM draws at 100%, the desktop scales the window: $win" }
            assertEquals(1, win.count { it.startsWith("-Dsun.java2d.uiScale=") }) { "one factor, the hard-wired one: $win" }
            assertTrue(win.none { it.contains("javaagent") }) { "a stored value never reaches the command line" }
            if (Runtime.version().feature() >= 9) {
                assertTrue(win.contains("-Dglass.win.uiScale=100%") && win.contains("-Dglass.gtk.uiScale=100%")) { "the embedded JavaFX is pinned to Swing's 100%: $win" }
                assertTrue(win.contains("-Dswing.aatext=true") && win.contains("-Dawt.useSystemAAFontSettings=lcd") && win.contains("-Dsun.java2d.d3d=false")) { "text rendering pinned: $win" }
            }
            val other = elsewhere.prepareLaunch(connection().copy(uiScale = stored)).command
            assertTrue(other.none { it.contains("uiScale") }) { "uiScale=$stored: off Windows no factor is set, Java follows each monitor's DPI: $other" }
        }
    }
}
