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
import org.junit.jupiter.api.Assertions.assertFalse
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
    @DisplayName("fetches run in parallel everywhere — a loopback address, a tunnel command, a direct host — unless the forward is known to be a basic-mode SSM session")
    fun parallelUnlessBasicForward(@TempDir dir: Path) {
        val ca = TestArtifacts.selfSignedCa("CN=Pretend Root,O=Pretend CA,C=GB")
        val core = LauncherCore(
            trustStore = TrustStore.of(ca.cert), cacheDir = dir.resolve("cache"), fetcherFactory = { FlakyFetcher(emptyMap(), 0) },
            sleeper = {}, parallelism = 4,
        )
        assertEquals(4, core.parallelismFor(Connection(id = "a", name = "fwd", serverUrl = "https://localhost:31065")))
        assertEquals(4, core.parallelismFor(Connection(id = "c", name = "tunnelled", serverUrl = "https://localhost:31065", tunnelCommand = "forward.bat")))
        assertEquals(4, core.parallelismFor(Connection(id = "d", name = "direct", serverUrl = "https://mirth.example.internal:8443")))
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

    /** A tunnel process that lives until destroyed. */
    private class FakeTunnelProcess : Process() {
        private val exited = java.util.concurrent.CountDownLatch(1)
        override fun getOutputStream(): java.io.OutputStream = java.io.OutputStream.nullOutputStream()
        override fun getInputStream(): java.io.InputStream = java.io.InputStream.nullInputStream()
        override fun getErrorStream(): java.io.InputStream = java.io.InputStream.nullInputStream()
        override fun waitFor(): Int { exited.await(); return 0 }
        override fun waitFor(timeout: Long, unit: java.util.concurrent.TimeUnit): Boolean = exited.await(timeout, unit)
        override fun exitValue(): Int { if (exited.count > 0) throw IllegalThreadStateException("running"); return 0 }
        override fun destroy() = exited.countDown()
        override fun isAlive(): Boolean = exited.count > 0
    }

    /** A launcher whose forward is a fake process that writes [sessionOutput] into its tunnel log, and whose fetches all fail with [failure]. */
    private fun launcherThroughFakeForward(dir: Path, sessionOutput: String, failure: () -> Exception): Pair<LauncherCore, com.gestalt.launcher.tunnel.TunnelManager> {
        val starter = com.gestalt.launcher.tunnel.TunnelManager.ProcessStarter { _, _, log ->
            log?.let { Files.writeString(it, Files.readString(it) + sessionOutput) }
            FakeTunnelProcess()
        }
        var probes = 0
        val tunnels = com.gestalt.launcher.tunnel.TunnelManager(
            logDir = dir.resolve("logs"), starter = starter, probe = { _, _ -> ++probes > 1 }, sleeper = {}, timeoutMs = 5_000, clock = { 0L },
        )
        val ca = TestArtifacts.selfSignedCa("CN=Pretend Root,O=Pretend CA,C=GB")
        val core = LauncherCore(
            trustStore = TrustStore.of(ca.cert), cacheDir = dir.resolve("cache"), tunnels = tunnels,
            fetcherFactory = { FlakyFetcher(emptyMap(), Int.MAX_VALUE, error = failure) }, sleeper = {},
        )
        return core to tunnels
    }

    @Test
    @DisplayName("a fetch that dies through a launcher-opened forward whose session reports the server side refused the port fails as THAT, not as a reset connection")
    fun destinationRefusedThroughTheForward(@TempDir dir: Path) {
        val conn = Connection(id = "t", name = "Forwarded", serverUrl = "https://localhost:31065", tunnelCommand = "forward.bat")
        val session = "Port 31065 opened for sessionId user-abc123.\nWaiting for connections...\n\nConnection accepted for session [user-abc123]\n\nConnection to destination port failed, check SSM Agent logs.\n"
        val (core, tunnels) = launcherThroughFakeForward(dir, session) { IOException("Connection reset") }

        val ex = assertThrows(com.gestalt.launcher.tunnel.TunnelFailedException::class.java) { core.launch(conn) }

        assertTrue(ex.message!!.contains("Connection to destination port failed")) { ex.message }
        assertTrue(ex.cause is IOException) { "the transport failure rides along as the cause" }
        assertFalse(tunnels.hasLiveTunnels()) { "the failed launch released its forward" }
    }

    @Test
    @DisplayName("the same failure through a forward whose session reported nothing of the kind stays what it was")
    fun plainFailureThroughTheForward(@TempDir dir: Path) {
        val conn = Connection(id = "t", name = "Forwarded", serverUrl = "https://localhost:31065", tunnelCommand = "forward.bat")
        val session = "Port 31065 opened for sessionId user-abc123.\nWaiting for connections...\n\nConnection accepted for session [user-abc123]\n"
        val (core, tunnels) = launcherThroughFakeForward(dir, session) { IOException("Connection reset") }

        val ex = assertThrows(IOException::class.java) { core.launch(conn) }

        assertEquals("Connection reset", ex.message)
        assertFalse(tunnels.hasLiveTunnels())
    }

    /**
     * A forward whose session accepts on some remote ports and refuses on others, as the SSM plugin reports
     * it, with fetches that only succeed while the forward points at a port the engine answers on.
     */
    private class PortAwareForward(private val answering: Set<Int>) {
        val started = ArrayList<String>()
        val processes = ArrayList<FakeTunnelProcess>()
        @Volatile var current: Int? = null
        val starter = com.gestalt.launcher.tunnel.TunnelManager.ProcessStarter { command, _, log ->
            val line = command.last()
            started += line
            val port = Regex("portNumber=(\\d+)").find(line)!!.groupValues[1].toInt()
            current = port
            val session = "Port 31065 opened for sessionId user-abc123.\nWaiting for connections...\n\nConnection accepted for session [user-abc123]\n" +
                if (port in answering) "" else "\nConnection to destination port failed, check SSM Agent logs.\n"
            log?.let { Files.writeString(it, Files.readString(it) + session) }
            FakeTunnelProcess().also { processes += it }
        }
        /** Nothing listens before the first start; afterwards the port answers while the latest forward lives. */
        val probe: (String, Int) -> Boolean = { _, _ -> processes.lastOrNull()?.isAlive == true }
        fun fetcher(canned: Map<String, ByteArray>): ArtifactFetcher = object : ArtifactFetcher {
            override fun fetchBytes(uri: URI): ByteArray {
                if (current !in answering) throw IOException("Connection reset")
                return canned[uri.toString()] ?: throw IOException("no canned response for $uri")
            }
        }
    }

    @Test
    @DisplayName("the server side refuses the command's port: the engine's usual ports are tried, the one that answers is remembered and used first next time")
    fun fallsBackToTheEnginesUsualPorts(@TempDir dir: Path) {
        val base = "https://localhost:31065/"
        val ca = TestArtifacts.selfSignedCa("CN=Pretend Root,O=Pretend CA,C=GB")
        val jarBytes = signedCoreJar(dir, ca)
        val forward = PortAwareForward(answering = setOf(8443))
        val tunnels = com.gestalt.launcher.tunnel.TunnelManager(
            logDir = dir.resolve("logs"), starter = forward.starter, probe = forward.probe, sleeper = {}, timeoutMs = 5_000, clock = { 0L },
            cacheDir = dir.resolve("tunnel-cache"),
        )
        val core = LauncherCore(
            trustStore = TrustStore.of(ca.cert), signerPins = GestaltTrust.OFFICIAL_SIGNER_PINS, gateMode = TrustMode.PIN_KNOWN_SIGNERS,
            cacheDir = dir.resolve("cache"), tunnels = tunnels, fetcherFactory = { forward.fetcher(responses(base, jarBytes)) },
            serverLeafProbe = { error("not an https trust failure") }, sleeper = {},
        )
        val command = "aws ssm start-session --target mi-0123456789abcdef0 --document-name AWS-StartPortForwardingSession --parameters \"localPortNumber=31065,portNumber=8000\""
        val conn = Connection(id = "t", name = "Forwarded", serverUrl = base, tunnelCommand = command)

        val process = core.launch(conn)
        try {
            assertEquals(2, forward.started.size) { forward.started.toString() }
            assertTrue(forward.started[0].contains("portNumber=8000")) { "the command's own port first" }
            assertTrue(forward.started[1].contains("portNumber=8443")) { "then the engine's usual https port" }
            assertFalse(forward.processes[0].isAlive) { "the refused forward was torn down before the next was opened" }
            // (processes[1] is held for the Administrator — and released the moment the child JVM exits, which the
            // stand-in client does within milliseconds, so its liveness is not asserted here.)
            assertEquals(8443, tunnels.rememberedRemotePort("t"))
            assertTrue(Files.readString(dir.resolve("tunnel-cache").resolve(com.gestalt.launcher.tunnel.TunnelManager.REMOTE_PORTS_FILE)).contains("8443"))
        } finally {
            process.destroyForcibly()
            process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)
        }
        await("the forward is released with the Administrator") { !forward.processes[1].isAlive }

        // Next time: straight to 8443, no detour through the refused port.
        val again = core.launch(conn)
        try {
            assertEquals(3, forward.started.size)
            assertTrue(forward.started[2].contains("portNumber=8443"))
        } finally {
            again.destroyForcibly()
            again.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)
        }
    }

    /** A forward whose first [dying] sessions end the moment the launcher connects (the plugin's `Exiting session`), leaving the process alive on `pause`; later ones serve. */
    private class DyingForward(private val dying: Int, outdated: Boolean = true) {
        val processes = ArrayList<FakeTunnelProcess>()
        @Volatile var sessionAlive = false
        private val warning = if (outdated) "WARNING: An outdated SessionManagerPlugin version detected. Please upgrade it to the latest version.\n" else ""
        val starter = com.gestalt.launcher.tunnel.TunnelManager.ProcessStarter { _, _, log ->
            val n = processes.size
            val session = warning + "Starting session with SessionId: user-abc123\nPort 31065 opened for sessionId user-abc123.\nWaiting for connections...\n\nConnection accepted for session [user-abc123]\n" +
                if (n < dying) "\n\nExiting session with sessionId: user-abc123.\n\nPress any key to continue . . .\n" else ""
            log?.let { Files.writeString(it, Files.readString(it) + session) }
            sessionAlive = n >= dying
            FakeTunnelProcess().also { processes += it } // alive either way: a batch file parked on `pause` after the session ended
        }
        /** The port answers once per forward while it comes up (the launcher's own probe — the connection the session dies on), then only while the session is alive. */
        private val probedOnce = HashSet<Int>()
        val probe: (String, Int) -> Boolean = { _, _ -> processes.lastOrNull()?.isAlive == true && (sessionAlive || probedOnce.add(processes.lastIndex)) }
        fun fetcher(canned: Map<String, ByteArray>): ArtifactFetcher = object : ArtifactFetcher {
            override fun fetchBytes(uri: URI): ByteArray {
                if (!sessionAlive) throw java.net.ConnectException("Connection refused: connect")
                return canned[uri.toString()] ?: throw IOException("no canned response for $uri")
            }
        }
    }

    @Test
    @DisplayName("a forward whose session ends the moment the launcher connects — its process parked on `pause` — is torn down and reopened once; the launch goes through the fresh session")
    fun sessionEndsUnderTheLauncher(@TempDir dir: Path) {
        val base = "https://localhost:31065/"
        val ca = TestArtifacts.selfSignedCa("CN=Pretend Root,O=Pretend CA,C=GB")
        val jarBytes = signedCoreJar(dir, ca)
        val forward = DyingForward(dying = 1)
        val tunnels = com.gestalt.launcher.tunnel.TunnelManager(
            logDir = dir.resolve("logs"), starter = forward.starter, probe = forward.probe, sleeper = {}, timeoutMs = 5_000, clock = { 0L },
            cacheDir = dir.resolve("tunnel-cache"),
        )
        val core = LauncherCore(
            trustStore = TrustStore.of(ca.cert), signerPins = GestaltTrust.OFFICIAL_SIGNER_PINS, gateMode = TrustMode.PIN_KNOWN_SIGNERS,
            cacheDir = dir.resolve("cache"), tunnels = tunnels, fetcherFactory = { forward.fetcher(responses(base, jarBytes)) },
            serverLeafProbe = { error("not an https trust failure") }, sleeper = {},
        )
        val conn = Connection(id = "t", name = "Forwarded", serverUrl = base, tunnelCommand = "forward.bat")

        val process = core.launch(conn)
        try {
            assertEquals(2, forward.processes.size) { "the dead session's process was replaced by one fresh forward" }
            assertFalse(forward.processes[0].isAlive) { "the process parked on `pause` was taken down with its session" }
        } finally {
            process.destroyForcibly()
            process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)
        }
    }

    @Test
    @DisplayName("a session that ends again on the reopened forward fails as that, naming the plugin's own warning")
    fun sessionKeepsEnding(@TempDir dir: Path) {
        val forward = DyingForward(dying = Int.MAX_VALUE)
        val tunnels = com.gestalt.launcher.tunnel.TunnelManager(
            logDir = dir.resolve("logs"), starter = forward.starter, probe = forward.probe, sleeper = {}, timeoutMs = 5_000, clock = { 0L },
            cacheDir = dir.resolve("tunnel-cache"),
        )
        val ca = TestArtifacts.selfSignedCa("CN=Pretend Root,O=Pretend CA,C=GB")
        val core = LauncherCore(trustStore = TrustStore.of(ca.cert), cacheDir = dir.resolve("cache"), tunnels = tunnels, fetcherFactory = { forward.fetcher(emptyMap()) }, sleeper = {})
        val conn = Connection(id = "t", name = "Forwarded", serverUrl = "https://localhost:31065", tunnelCommand = "forward.bat")

        val ex = assertThrows(com.gestalt.launcher.tunnel.TunnelFailedException::class.java) { core.launch(conn) }

        assertEquals(LauncherCore.SESSION_REOPENS + 1, forward.processes.size) { "opened, reopened once, then given up" }
        assertTrue(ex.message!!.contains("session ended as soon as the launcher connected")) { ex.message }
        assertTrue(ex.message!!.contains("outdated")) { "the plugin's own warning is passed on: ${ex.message}" }
        assertTrue(forward.processes.none { it.isAlive })
        assertFalse(tunnels.hasLiveTunnels())
    }

    @Test
    @DisplayName("every usual port refused: the failure names the ports tried")
    fun allPortsRefused(@TempDir dir: Path) {
        val forward = PortAwareForward(answering = emptySet())
        val tunnels = com.gestalt.launcher.tunnel.TunnelManager(
            logDir = dir.resolve("logs"), starter = forward.starter, probe = forward.probe, sleeper = {}, timeoutMs = 5_000, clock = { 0L },
            cacheDir = dir.resolve("tunnel-cache"),
        )
        val ca = TestArtifacts.selfSignedCa("CN=Pretend Root,O=Pretend CA,C=GB")
        val core = LauncherCore(trustStore = TrustStore.of(ca.cert), cacheDir = dir.resolve("cache"), tunnels = tunnels, fetcherFactory = { forward.fetcher(emptyMap()) }, sleeper = {})
        val command = "aws ssm start-session --target mi-0123456789abcdef0 --document-name AWS-StartPortForwardingSession --parameters \"localPortNumber=31065,portNumber=8000\""
        val conn = Connection(id = "t", name = "Forwarded", serverUrl = "https://localhost:31065", tunnelCommand = command)

        val ex = assertThrows(com.gestalt.launcher.tunnel.TunnelFailedException::class.java) { core.launch(conn) }

        assertEquals(listOf(8000, 8443), forward.started.map { Regex("portNumber=(\\d+)").find(it)!!.groupValues[1].toInt() }) { "its own port, then the fallbacks not already tried" }
        assertTrue(ex.message!!.contains("8000, 8443")) { ex.message }
        assertTrue(forward.processes.none { it.isAlive })
        assertEquals(null, tunnels.rememberedRemotePort("t"))
    }

    private fun await(what: String, timeoutMs: Long = 10_000, cond: () -> Boolean) {
        var waited = 0L
        while (!cond() && waited < timeoutMs) { Thread.sleep(20); waited += 20 }
        assertTrue(cond()) { "timed out waiting for: $what" }
    }
}
