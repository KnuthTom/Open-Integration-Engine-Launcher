/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.launch

import com.gestalt.launcher.digest.DigestVerifier
import com.gestalt.launcher.gate.GestaltTrust
import com.gestalt.launcher.model.Connection
import com.gestalt.launcher.net.ArtifactFetcher
import com.gestalt.launcher.testutil.TestArtifacts
import com.gestalt.launcher.tls.CertFingerprint
import com.gestalt.launcher.tls.ServerTrustStore
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
import java.security.KeyStore
import java.security.cert.X509Certificate

/**
 * The launched Administrator opens its OWN TLS connection to the server, so for a pinned https connection
 * the launcher must hand it the pinned leaf as a per-connection PKCS12 truststore (-Djavax.net.ssl.trustStore).
 * Proves that store is written + passed for a pinned connection, NOT written for an unpinned one, and that a
 * probed leaf which doesn't match the pin fails the launch closed. Offline via the injected leaf probe.
 */
class ChildTrustStorePropagationTest {

    private val base = "https://pinned.example.com/"

    private class FakeFetcher(private val responses: Map<String, ByteArray>) : ArtifactFetcher {
        override fun fetchBytes(uri: URI): ByteArray =
            responses[uri.toString()] ?: throw java.io.IOException("no canned response for $uri")
    }

    private fun jnlp(): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <jnlp spec="1.0+" version="1.0.0">
          <resources><jar href="core.jar" main="true"/></resources>
          <application-desc main-class="com.mirth.connect.client.ui.Mirth"/>
        </jnlp>
    """.trimIndent()

    private fun signedCoreJar(dir: Path, ca: TestArtifacts.Ca): ByteArray {
        val leaf = TestArtifacts.codeSigningLeaf("CN=OIE,O=BrightCodeCompany OÜ,C=EE", ca)
        val jar = dir.resolve("build-core.jar")
        TestArtifacts.writeSignedJar(jar, mapOf("mirth/Main.class" to "main".toByteArray()), leaf.keyPair.private, leaf.chain)
        return Files.readAllBytes(jar)
    }

    private fun launcher(dir: Path, ca: TestArtifacts.Ca, jarBytes: ByteArray, probedLeaf: X509Certificate?, base: String = this.base): LauncherCore {
        val responses = mapOf(
            "${base}webstart" to jnlp().toByteArray(),
            "${base}digest/1.0.0" to "${DigestVerifier.sha256Hex(jarBytes)}  core.jar".toByteArray(),
            "${base}core.jar" to jarBytes,
        )
        return LauncherCore(
            trustStore = TrustStore.of(ca.cert),
            signerPins = GestaltTrust.OFFICIAL_SIGNER_PINS,
            gateMode = TrustMode.PIN_KNOWN_SIGNERS,
            cacheDir = dir.resolve("cache"),
            fetcherFactory = { FakeFetcher(responses) },
            serverLeafProbe = { probedLeaf ?: error("probe should not be called for this connection") },
        )
    }

    @Test
    @DisplayName("a pinned https connection writes + passes the server leaf as a child-JVM PKCS12 truststore")
    fun pinnedConnectionPropagatesTrustStore(@TempDir dir: Path) {
        val ca = TestArtifacts.selfSignedCa("CN=Pretend Root,O=Pretend CA,C=GB")
        val jarBytes = signedCoreJar(dir, ca)
        val (_, serverLeaf) = TestArtifacts.serverCert("mirth-connect", "mirth-connect")
        val pin = CertFingerprint.sha256Hex(serverLeaf)
        val conn = Connection(id = "pinned", name = "P", serverUrl = base, pinnedCertSha256 = pin)

        val plan = launcher(dir, ca, jarBytes, serverLeaf).prepareLaunch(conn)

        val tsArg = plan.command.firstOrNull { it.startsWith("-Djavax.net.ssl.trustStore=") }
        assertNotNull(tsArg) { "the child JVM must receive the per-connection truststore: ${plan.command}" }
        val tsPath = Path.of(tsArg!!.substringAfter("="))
        assertTrue(Files.exists(tsPath)) { "the PKCS12 truststore file must exist at $tsPath" }
        assertTrue(plan.command.contains("-Djavax.net.ssl.trustStorePassword=${ServerTrustStore.PASSWORD}"))
        assertTrue(plan.command.contains("-Djavax.net.ssl.trustStoreType=PKCS12"))

        val ks = KeyStore.getInstance("PKCS12").apply { Files.newInputStream(tsPath).use { load(it, ServerTrustStore.PASSWORD.toCharArray()) } }
        assertEquals(pin, CertFingerprint.sha256Hex(ks.getCertificate("server") as X509Certificate)) {
            "the truststore must hold exactly the pinned server leaf"
        }
        // A non-loopback host is left on its default dual stack (may legitimately be IPv6-only).
        assertFalse(plan.command.contains("-Djava.net.preferIPv4Stack=true")) {
            "a remote host must not be forced onto IPv4: ${plan.command}"
        }
    }

    @Test
    @DisplayName("a localhost (SSM-forward) connection forces the child JVM onto the IPv4 stack")
    fun localhostConnectionPrefersIpv4(@TempDir dir: Path) {
        val ca = TestArtifacts.selfSignedCa("CN=Pretend Root,O=Pretend CA,C=GB")
        val jarBytes = signedCoreJar(dir, ca)
        val (_, serverLeaf) = TestArtifacts.serverCert("mirth-connect", "mirth-connect")
        val pin = CertFingerprint.sha256Hex(serverLeaf)
        val localBase = "https://localhost:31065/"
        val conn = Connection(id = "local", name = "L", serverUrl = localBase, pinnedCertSha256 = pin)

        val plan = launcher(dir, ca, jarBytes, serverLeaf, base = localBase).prepareLaunch(conn)

        assertTrue(plan.command.contains("-Djava.net.preferIPv4Stack=true")) {
            "a localhost connection must prefer IPv4 for the child JVM: ${plan.command}"
        }
    }

    @Test
    @DisplayName("a non-pinned https connection writes no truststore and passes no trustStore -D")
    fun unpinnedConnectionPropagatesNothing(@TempDir dir: Path) {
        val ca = TestArtifacts.selfSignedCa("CN=Pretend Root,O=Pretend CA,C=GB")
        val jarBytes = signedCoreJar(dir, ca)
        val conn = Connection(id = "np", name = "NP", serverUrl = base) // https, no pin

        val plan = launcher(dir, ca, jarBytes, probedLeaf = null).prepareLaunch(conn)

        assertFalse(plan.command.any { it.contains("javax.net.ssl.trustStore") }) { "no pin -> no launcher truststore" }
    }

    @Test
    @DisplayName("a probed leaf that does not match the pin fails the launch closed")
    fun pinMismatchFailsClosed(@TempDir dir: Path) {
        val ca = TestArtifacts.selfSignedCa("CN=Pretend Root,O=Pretend CA,C=GB")
        val jarBytes = signedCoreJar(dir, ca)
        val (_, pinnedLeaf) = TestArtifacts.serverCert("mirth-connect", "mirth-connect")
        val (_, differentLeaf) = TestArtifacts.serverCert("imposter", "imposter")
        val pin = CertFingerprint.sha256Hex(pinnedLeaf)
        val conn = Connection(id = "pinned", name = "P", serverUrl = base, pinnedCertSha256 = pin)

        // The server presents a DIFFERENT leaf than the one pinned -> hard stop before any download.
        assertThrows(LaunchAbortedException::class.java) {
            launcher(dir, ca, jarBytes, differentLeaf).prepareLaunch(conn)
        }
    }
}
