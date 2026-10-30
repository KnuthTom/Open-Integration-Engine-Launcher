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
 * LOOPBACK connection — keyed on the host, NOT the stored connection mode — the launcher retries
 * transient connect failures; a remote connection fails fast. (Gating on the host, not the mode, is
 * deliberate: a real-world loopback appliance connection may be stored as Standard mode yet still needs
 * the retry handling.)
 *
 * The client's server-URL argument is rewritten to the connection's own address for EVERY connection:
 * whatever the user typed (scheme, host and port) is what the Administrator connects to and shows on its
 * login screen, never the server's self-reported URL.
 */
class PortForwardLaunchTest {

    /** Fetcher that throws [error] for the first [failFirst] calls TO EACH uri, then serves canned bytes. */
    private class FlakyFetcher(
        private val responses: Map<String, ByteArray>,
        private val failFirst: Int,
        private val error: () -> Exception = { ConnectException("Connection refused: no further information") },
        /** What the transport reports it reached the server on (null: a transport that can't tell). */
        override val lastRemoteAddress: java.net.InetAddress? = null,
    ) : ArtifactFetcher {
        val calls = HashMap<String, Int>()
        override fun fetchBytes(uri: URI): ByteArray {
            val k = uri.toString()
            // Jars are fetched on a pool now; the count must be atomic per uri.
            val n = synchronized(calls) { val next = (calls[k] ?: 0) + 1; calls[k] = next; next }
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
    @DisplayName("loopback reached over ::1: the client is told to prefer IPv6 — not pinned to an IPv4 stack it can't use")
    fun loopbackReachedOverIpv6PrefersIpv6(@TempDir dir: Path) {
        val base = "https://localhost:31054/"
        val ca = TestArtifacts.selfSignedCa("CN=Pretend Root,O=Pretend CA,C=GB")
        val jarBytes = signedCoreJar(dir, ca)
        val (_, serverLeaf) = TestArtifacts.serverCert("mirth-connect", "mirth-connect")
        val v6 = java.net.InetAddress.getByName("::1")
        val fetcher = FlakyFetcher(responses(base, jarBytes), failFirst = 0, lastRemoteAddress = v6)
        val conn = Connection(id = "pf6", name = "ssm-forward", serverUrl = base, pinnedCertSha256 = CertFingerprint.sha256Hex(serverLeaf))

        val plan = launcher(dir, ca, fetcher, serverLeaf).prepareLaunch(conn)

        assertTrue("-Djava.net.preferIPv6Addresses=true" in plan.command) { "the forward answered on ::1, so the client must look there first: ${plan.command}" }
        assertTrue("-Djava.net.preferIPv4Stack=true" !in plan.command) { "an IPv4-only stack would strand the client on 127.0.0.1: ${plan.command}" }
        assertEquals(listOf("https://localhost:31054", "1.0.0"), plan.appArgs) { "the login screen still shows the typed address" }
    }

    @Test
    @DisplayName("loopback reached over 127.0.0.1 (or a transport that can't say): the IPv4-only stack as before")
    fun loopbackReachedOverIpv4KeepsIpv4Stack(@TempDir dir: Path) {
        val base = "https://localhost:31065/"
        val ca = TestArtifacts.selfSignedCa("CN=Pretend Root,O=Pretend CA,C=GB")
        val jarBytes = signedCoreJar(dir, ca)
        val (_, serverLeaf) = TestArtifacts.serverCert("mirth-connect", "mirth-connect")
        val pin = CertFingerprint.sha256Hex(serverLeaf)
        for (reached in listOf(java.net.InetAddress.getByName("127.0.0.1"), null)) {
            val fetcher = FlakyFetcher(responses(base, jarBytes), failFirst = 0, lastRemoteAddress = reached)
            val conn = Connection(id = "pf4-${reached?.hostAddress ?: "unknown"}", name = "forward", serverUrl = base, pinnedCertSha256 = pin)

            val plan = launcher(dir, ca, fetcher, serverLeaf).prepareLaunch(conn)

            assertTrue("-Djava.net.preferIPv4Stack=true" in plan.command) { "reached=$reached: ${plan.command}" }
            assertTrue("-Djava.net.preferIPv6Addresses=true" !in plan.command) { "reached=$reached: ${plan.command}" }
        }
    }

    @Test
    @DisplayName("remote connection: no loopback stack flag at all, whichever family the fetch used")
    fun remoteGetsNoStackFlag(@TempDir dir: Path) {
        val base = "https://mirth.example:8443/"
        val ca = TestArtifacts.selfSignedCa("CN=Pretend Root,O=Pretend CA,C=GB")
        val jarBytes = signedCoreJar(dir, ca)
        val fetcher = FlakyFetcher(responses(base, jarBytes), failFirst = 0, lastRemoteAddress = java.net.InetAddress.getByName("2001:db8::10"))
        val conn = Connection(id = "r6", name = "Remote", serverUrl = base, connectionMode = ConnectionMode.Standard)

        val plan = launcher(dir, ca, fetcher, leaf = null).prepareLaunch(conn)

        assertTrue(plan.command.none { it.startsWith("-Djava.net.preferIPv") }) { "a remote host keeps the default dual stack: ${plan.command}" }
    }

    @Test
    @DisplayName("remote connection: the client URL is ALSO rewritten to the address the user typed")
    fun remoteRewrittenToConnectionAddress(@TempDir dir: Path) {
        val base = "https://mirth.example:8443/"
        val ca = TestArtifacts.selfSignedCa("CN=Pretend Root,O=Pretend CA,C=GB")
        val jarBytes = signedCoreJar(dir, ca)
        val fetcher = FlakyFetcher(responses(base, jarBytes), failFirst = 0)
        val conn = Connection(id = "r", name = "Remote", serverUrl = base, connectionMode = ConnectionMode.Standard)

        val plan = launcher(dir, ca, fetcher, leaf = null).prepareLaunch(conn)

        assertEquals(listOf("https://mirth.example:8443", "1.0.0"), plan.appArgs) {
            "the JNLP's self-reported https://localhost:8443 must be replaced by the connection's own address"
        }
    }

    @Test
    @DisplayName("plain-http launcher port: the client is sent to the server's https port on the typed host (the http port has no API)")
    fun plainHttpGoesToTheServersHttpsPort(@TempDir dir: Path) {
        val base = "http://localhost:9912/"
        val ca = TestArtifacts.selfSignedCa("CN=Pretend Root,O=Pretend CA,C=GB")
        val jarBytes = signedCoreJar(dir, ca)
        val fetcher = FlakyFetcher(responses(base, jarBytes), failFirst = 0)
        val conn = Connection(id = "http-fwd", name = "Appliance", serverUrl = base, connectionMode = ConnectionMode.LocalhostPortForward)

        val plan = launcher(dir, ca, fetcher, leaf = null).prepareLaunch(conn)

        assertEquals(listOf("https://localhost:8443", "1.0.0"), plan.appArgs) {
            "the launcher files came over http:9912, but Mirth/OIE answer the API only on https — the JNLP's https://localhost:8443"
        }
    }

    @Test
    @DisplayName("port-less connection URL: the argument keeps its own port rather than dropping to 443")
    fun portlessConnectionKeepsArgumentPort(@TempDir dir: Path) {
        val base = "https://mirth.example/"
        val ca = TestArtifacts.selfSignedCa("CN=Pretend Root,O=Pretend CA,C=GB")
        val jarBytes = signedCoreJar(dir, ca)
        val fetcher = FlakyFetcher(responses(base, jarBytes), failFirst = 0)
        val conn = Connection(id = "np", name = "No port", serverUrl = base, connectionMode = ConnectionMode.Custom)

        val plan = launcher(dir, ca, fetcher, leaf = null).prepareLaunch(conn)

        assertEquals(listOf("https://mirth.example:8443", "1.0.0"), plan.appArgs) {
            "host from the connection, port from the JNLP argument when the connection names none"
        }
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
