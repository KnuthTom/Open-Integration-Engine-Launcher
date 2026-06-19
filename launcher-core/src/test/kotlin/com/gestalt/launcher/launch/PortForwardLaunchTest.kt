/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.launch

import com.gestalt.launcher.digest.DigestVerifier
import com.gestalt.launcher.gate.GestaltTrust
import com.gestalt.launcher.model.Connection
import com.gestalt.launcher.model.ConnectionMode
import com.gestalt.launcher.net.ArtifactFetcher
import com.gestalt.launcher.testutil.TestArtifacts
import com.gestalt.launcher.tls.CertFingerprint
import com.gestalt.trust.TrustMode
import com.gestalt.trust.TrustStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.net.ConnectException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.cert.X509Certificate

/**
 * Port-forward (loopback) launch behavior. An SSM tunnel often refuses the launcher's rapid burst of
 * connections though a single one succeeds, and reports its own (un-forwarded) URL in the JNLP. For a
 * LOOPBACK connection — keyed on the host, NOT the stored connection mode — the launcher (a) retries
 * transient connect failures and (b) rewrites the client's server-URL argument to the forwarded address.
 * A remote connection gets neither. (Gating on the host, not the mode, is deliberate: a real-world
 * loopback appliance connection may be stored as Standard mode yet still needs the forward handling.)
 */
class PortForwardLaunchTest {

    /** Fetcher that throws [error] for the first [failFirst] calls TO EACH uri, then serves canned bytes. */
    private class FlakyFetcher(
        private val responses: Map<String, ByteArray>,
        private val failFirst: Int,
        private val error: () -> Exception = { ConnectException("Connection refused: no further information") },
    ) : ArtifactFetcher {
        val calls = HashMap<String, Int>()
        override fun fetchBytes(uri: URI): ByteArray {
            val k = uri.toString()
            val n = (calls[k] ?: 0) + 1
            calls[k] = n
            if (n <= failFirst) throw error()
            return responses[k] ?: throw IOException("no canned response for $uri")
        }
    }

    private fun jnlp(): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <jnlp spec="1.0+" version="1.0.0">
          <resources><jar href="core.jar" main="true"/></resources>
          <application-desc main-class="com.mirth.connect.client.ui.Mirth">
            <argument>https://localhost:8443</argument>
            <argument>1.0.0</argument>
          </application-desc>
        </jnlp>
    """.trimIndent()

    private fun signedCoreJar(dir: Path, ca: TestArtifacts.Ca): ByteArray {
        val leaf = TestArtifacts.codeSigningLeaf("CN=OIE,O=BrightCodeCompany OÜ,C=EE", ca)
        val jar = dir.resolve("build-core.jar")
        TestArtifacts.writeSignedJar(jar, mapOf("mirth/Main.class" to "main".toByteArray()), leaf.keyPair.private, leaf.chain)
        return Files.readAllBytes(jar)
    }

    private fun responses(base: String, jarBytes: ByteArray): Map<String, ByteArray> = mapOf(
        "${base}webstart" to jnlp().toByteArray(),
        "${base}digest/1.0.0" to "${DigestVerifier.sha256Hex(jarBytes)}  core.jar".toByteArray(),
        "${base}core.jar" to jarBytes,
    )

    private fun launcher(dir: Path, ca: TestArtifacts.Ca, fetcher: ArtifactFetcher, leaf: X509Certificate?): LauncherCore =
        LauncherCore(
            trustStore = TrustStore.of(ca.cert),
            signerPins = GestaltTrust.OFFICIAL_SIGNER_PINS,
            gateMode = TrustMode.PIN_KNOWN_SIGNERS,
            cacheDir = dir.resolve("cache"),
            fetcherFactory = { fetcher },
            serverLeafProbe = { leaf ?: error("probe should not be called for this connection") },
            sleeper = {}, // no real waiting between retries in tests
        )

    @Test
    @DisplayName("loopback connection (even in Standard mode) retries transient refusals AND rewrites the client URL")
    fun loopbackRetriesAndRewrites(@TempDir dir: Path) {
        val base = "https://localhost:31065/"
        val ca = TestArtifacts.selfSignedCa("CN=Pretend Root,O=Pretend CA,C=GB")
        val jarBytes = signedCoreJar(dir, ca)
        val (_, serverLeaf) = TestArtifacts.serverCert("mirth-connect", "mirth-connect")
        val pin = CertFingerprint.sha256Hex(serverLeaf)
        val fetcher = FlakyFetcher(responses(base, jarBytes), failFirst = 2)
        // Stored as Standard mode on purpose — the host, not the mode, drives the forward handling.
        val conn = Connection(
            id = "pf", name = "local-appliance", serverUrl = base,
            pinnedCertSha256 = pin, connectionMode = ConnectionMode.Standard,
        )

        val plan = launcher(dir, ca, fetcher, serverLeaf).prepareLaunch(conn)

        assertEquals(listOf("https://localhost:31065", "1.0.0"), plan.appArgs) {
            "the client must be pointed at the forwarded URL, not the JNLP's https://localhost:8443"
        }
        assertTrue((fetcher.calls["${base}webstart"] ?: 0) >= 3) { "the JNLP fetch must have been retried past the refusals" }
        assertTrue((fetcher.calls["${base}core.jar"] ?: 0) >= 3) { "the per-jar fetch (the dominant cost) must also be retried" }
    }

    @Test
    @DisplayName("remote connection: the client URL is left as the JNLP declares it (no rewrite)")
    fun remoteNoRewrite(@TempDir dir: Path) {
        val base = "https://mirth.example:8443/"
        val ca = TestArtifacts.selfSignedCa("CN=Pretend Root,O=Pretend CA,C=GB")
        val jarBytes = signedCoreJar(dir, ca)
        val fetcher = FlakyFetcher(responses(base, jarBytes), failFirst = 0)
        val conn = Connection(id = "r", name = "Remote", serverUrl = base, connectionMode = ConnectionMode.Standard)

        val plan = launcher(dir, ca, fetcher, leaf = null).prepareLaunch(conn)

        assertEquals(listOf("https://localhost:8443", "1.0.0"), plan.appArgs) { "a remote connection is untouched" }
    }

    @Test
    @DisplayName("remote connection: a transient connect failure is NOT retried (fails fast)")
    fun remoteNoRetry(@TempDir dir: Path) {
        val base = "https://mirth.example:8443/"
        val ca = TestArtifacts.selfSignedCa("CN=Pretend Root,O=Pretend CA,C=GB")
        val jarBytes = signedCoreJar(dir, ca)
        val fetcher = FlakyFetcher(responses(base, jarBytes), failFirst = 1)
        val conn = Connection(id = "r", name = "Remote", serverUrl = base, connectionMode = ConnectionMode.Standard)

        assertThrows(ConnectException::class.java) { launcher(dir, ca, fetcher, leaf = null).prepareLaunch(conn) }
    }

    @Test
    @DisplayName("loopback connection: a NON-connect error (e.g. plain IOException) is NOT retried")
    fun loopbackDoesNotRetryNonConnectErrors(@TempDir dir: Path) {
        val base = "https://localhost:31065/"
        val ca = TestArtifacts.selfSignedCa("CN=Pretend Root,O=Pretend CA,C=GB")
        val jarBytes = signedCoreJar(dir, ca)
        val fetcher = FlakyFetcher(responses(base, jarBytes), failFirst = 1, error = { IOException("malformed response") })
        val conn = Connection(id = "pf", name = "local-appliance", serverUrl = base, connectionMode = ConnectionMode.Standard)

        assertThrows(IOException::class.java) { launcher(dir, ca, fetcher, leaf = null).prepareLaunch(conn) }
    }
}
