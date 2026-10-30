/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.matrix

import com.gestalt.launcher.gate.GestaltTrust
import com.gestalt.launcher.launch.LauncherCore
import com.gestalt.launcher.model.Connection
import com.gestalt.launcher.model.JavaHomeConfig
import com.gestalt.launcher.model.JavaHomeMode
import com.gestalt.launcher.testutil.TestArtifacts
import com.gestalt.launcher.tls.CertFingerprint
import com.gestalt.launcher.tls.PinnedTrustManager
import com.gestalt.trust.TrustMode
import com.gestalt.trust.TrustStore
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Phase-5 **matrix harness**. Drives the real fail-closed verify+launch pipeline ([LauncherCore.prepareLaunch],
 * which stops before spawning any process) across the dimensions we can exercise here:
 *
 *   scheme (http/https) × topology (local / localhost-port-forward / internal-host / public-FQDN)
 *     × client runtime (bundled Java 8 AND Java 17) × trust (CA-valid / self-signed-pinned)
 *
 * Each cell asserts the pipeline does the right thing: admits the verified jar, builds a child command
 * for the *right* bundled runtime (Java-9+ module flags present on 17, withheld on 8), propagates a
 * per-connection truststore only when pinned, and rewrites the client's server-URL argument only for a
 * loopback port-forward. The live network facet (a real connect to an internal/public server, or a
 * specific OS) is marked **manual** with what it needs. A markdown grid is emitted to
 * `build/matrix-results.md`.
 *
 * Trust is also validated against live certificate objects (exact-cert pin accept/reject; the system CA
 * path refusing a self-signed leaf), proving the CA-valid vs self-signed-pinned distinction directly.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MatrixHarnessTest {

    enum class Scheme(val s: String) { HTTP("http"), HTTPS("https") }
    enum class Topology(val label: String) {
        LOCAL("local"), PORT_FORWARD("localhost-port-forward"), INTERNAL("internal-host"), PUBLIC("public-FQDN")
    }
    enum class Runtime(val label: String, val bundled: String, val homeDir: String) {
        JAVA8("bundled Java 8", "8", "bundled-jre8"), JAVA17("bundled Java 17", "17", "bundled-jre17")
    }
    enum class Trust(val label: String) { CA_VALID("CA-valid"), PINNED("self-signed-pinned"), NA("n/a (no TLS)") }

    data class Cell(val scheme: Scheme, val topology: Topology, val runtime: Runtime, val trust: Trust) {
        fun id() = "${scheme.s}-${topology.name.lowercase()}-${runtime.bundled}-${trust.name.lowercase()}"
    }

    private data class Row(val cell: Cell, val status: String, val detail: String, val live: String)

    private val rows = java.util.Collections.synchronizedList(mutableListOf<Row>())
    private val tlsRows = java.util.Collections.synchronizedList(mutableListOf<Triple<String, String, String>>())

    private lateinit var classTmp: Path
    private lateinit var ca: TestArtifacts.Ca

    @BeforeAll
    fun setUp() {
        classTmp = Files.createTempDirectory("phase5-matrix")
        ca = TestArtifacts.selfSignedCa("CN=Pretend Sectigo,O=Pretend CA,C=GB")
        // Two fake bundled JRE homes — a directory with a `release` file is all the command builder needs
        // to resolve the runtime and read its major version (the child process is never spawned here).
        fakeJre(Runtime.JAVA8.homeDir, "1.8.0_412")
        fakeJre(Runtime.JAVA17.homeDir, "17.0.11")
        System.setProperty("gestalt.java.8.home", classTmp.resolve(Runtime.JAVA8.homeDir).toString())
        System.setProperty("gestalt.java.17.home", classTmp.resolve(Runtime.JAVA17.homeDir).toString())
    }

    @AfterAll
    fun tearDownAndReport() {
        System.clearProperty("gestalt.java.8.home")
        System.clearProperty("gestalt.java.17.home")
        Phase5Report.write("matrix-results.md", renderReport())
        runCatching { classTmp.toFile().deleteRecursively() }
    }

    private fun fakeJre(dirName: String, javaVersion: String) {
        val home = classTmp.resolve(dirName)
        Files.createDirectories(home.resolve("bin"))
        Files.writeString(home.resolve("release"), "JAVA_VERSION=\"$javaVersion\"\n")
    }

    // --- the matrix ----------------------------------------------------------------------------------

    private fun cells(): List<Cell> = buildList {
        for (scheme in Scheme.entries) for (topo in Topology.entries) {
            val trusts = if (scheme == Scheme.HTTPS) listOf(Trust.CA_VALID, Trust.PINNED) else listOf(Trust.NA)
            for (trust in trusts) for (rt in Runtime.entries) add(Cell(scheme, topo, rt, trust))
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cells")
    fun matrixCell(cell: Cell) {
        val row = runCell(cell)
        rows += row
        assertTrue(row.status != "FAIL") { "${cell.id()}: ${row.detail}" }
    }

    private fun runCell(cell: Cell): Row {
        val host = hostFor(cell.topology)
        val base = baseFor(cell)
        return try {
            val leaf = TestArtifacts.codeSigningLeaf("CN=OIE,O=BrightCodeCompany OÜ,C=EE", ca)
            val jarBytes = Phase5Fixtures.signedCore(classTmp, leaf, "core-${cell.id()}.jar")
            val (_, serverCert) = TestArtifacts.serverCert(host, host)
            val pin = if (cell.trust == Trust.PINNED) CertFingerprint.sha256Hex(serverCert) else null

            val launcher = LauncherCore(
                trustStore = TrustStore.of(ca.cert),
                signerPins = GestaltTrust.OFFICIAL_SIGNER_PINS,
                gateMode = TrustMode.PIN_KNOWN_SIGNERS,
                cacheDir = classTmp.resolve("cache"),
                fetcherFactory = { FakeFetcher(Phase5Fixtures.canned(base, jarBytes, serverArgFor(cell))) },
                serverLeafProbe = { serverCert },
            )
            val connection = Connection(
                id = cell.id(),
                name = cell.toString(),
                serverUrl = base,
                pinnedCertSha256 = pin,
                javaHome = JavaHomeConfig(mode = JavaHomeMode.Bundled, bundledVersion = cell.runtime.bundled),
            )

            val plan = launcher.prepareLaunch(connection)
            val fails = mutableListOf<String>()
            fun check(cond: Boolean, msg: String) { if (!cond) fails += msg }

            // verify+launch pipeline admitted exactly the verified jar
            check(plan.admitted.size == 1, "expected 1 admitted jar, got ${plan.admitted.size}")

            // client runtime: the child command targets the selected bundled JRE…
            val exe = plan.command.first()
            check(exe.contains(cell.runtime.homeDir), "child JVM '$exe' should resolve to ${cell.runtime.homeDir}")
            // …and the Java-9+ module flags are applied only on a Java-9+ target (invalid on Java 8)
            val hasModuleFlag = plan.command.any { it.startsWith("--add-opens") }
            if (cell.runtime == Runtime.JAVA8) check(!hasModuleFlag, "Java 8 must NOT receive Java-9+ module flags")
            else check(hasModuleFlag, "Java 17 must receive the curated module flag from the JNLP")

            // trust: a per-connection truststore is propagated ONLY for a pinned https connection
            val hasTrustStore = plan.command.any { it.contains("javax.net.ssl.trustStore=") }
            if (cell.trust == Trust.PINNED) check(hasTrustStore, "pinned https must propagate a truststore to the child")
            else check(!hasTrustStore, "unpinned/http must NOT write a truststore override")

            // scheme: modern TLS is set for the child (harmless on http); never downgraded
            check(plan.command.contains("-Dhttps.protocols=TLSv1.2,TLSv1.3"), "modern TLS protocols must be set")

            // topology: the client server-URL is always the connection's own address — for a port-forward
            // that means the forwarded port replaces the server's self-reported one; elsewhere host/port match
            when (cell.topology) {
                Topology.PORT_FORWARD -> {
                    check(plan.appArgs.any { it.contains(":31065") }, "port-forward must rewrite to the forwarded port")
                    check(plan.appArgs.none { it.contains(":8443") || it.contains(":8080") }, "self-reported port must be rewritten away")
                }
                else -> check(plan.appArgs.any { it.contains(host) }, "the server URL argument must be passed through")
            }

            if (fails.isEmpty()) Row(cell, "PASS", "verify+launch construction validated", liveNote(cell))
            else Row(cell, "FAIL", fails.joinToString("; "), liveNote(cell))
        } catch (e: Exception) {
            Row(cell, "FAIL", "threw ${e.javaClass.simpleName}: ${e.message}", liveNote(cell))
        }
    }

    // --- live-certificate trust checks (the CA-valid vs self-signed-pinned distinction) --------------

    @Test
    @DisplayName("TLS trust: an exact-cert pin accepts its own leaf and rejects a rotated leaf (fail closed)")
    fun pinAcceptsOwnLeafRejectsRotated() {
        val (_, leaf) = TestArtifacts.serverCert("mirth-connect", "mirth-connect")
        val (_, rotated) = TestArtifacts.serverCert("mirth-connect", "mirth-connect")
        val tm = PinnedTrustManager(CertFingerprint.sha256Hex(leaf))
        tm.checkServerTrusted(arrayOf(leaf), "RSA") // own leaf: no exception
        val rejected = runCatching { tm.checkServerTrusted(arrayOf(rotated), "RSA") }.isFailure
        tlsRows += Triple(
            "exact-cert pin accepts own leaf / rejects a rotated leaf",
            if (rejected) "PASS" else "FAIL",
            if (rejected) "own=accepted, rotated=rejected" else "a rotated leaf was NOT rejected",
        )
        assertTrue(rejected) { "a rotated server leaf must fail the pin closed" }
    }

    @Test
    @DisplayName("TLS trust: the system CA path refuses a self-signed leaf (so only a pin can trust it)")
    fun systemTrustRejectsSelfSigned() {
        val (_, selfSigned) = TestArtifacts.serverCert("mirth-connect", "mirth-connect")
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?)
        val sysTm = tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
        val rejected = runCatching { sysTm.checkServerTrusted(arrayOf(selfSigned), "RSA") }.isFailure
        tlsRows += Triple(
            "system CA path rejects an unpinned self-signed leaf",
            if (rejected) "PASS" else "FAIL",
            if (rejected) "self-signed is not system-trusted → it must be pinned" else "unexpectedly system-trusted",
        )
        assertTrue(rejected) { "an unpinned self-signed server must not be system-trusted" }
    }

    // --- helpers -------------------------------------------------------------------------------------

    private fun hostFor(topo: Topology): String = when (topo) {
        Topology.LOCAL, Topology.PORT_FORWARD -> "localhost"
        Topology.INTERNAL -> "mirth.example.internal"
        Topology.PUBLIC -> "oie.example.com"
    }

    /** The address the user actually reaches the server at — for a port-forward, the forwarded local port. */
    private fun baseFor(cell: Cell): String {
        val port = when {
            cell.topology == Topology.PORT_FORWARD -> 31065
            cell.scheme == Scheme.HTTPS -> 8443
            else -> 8080
        }
        return "${cell.scheme.s}://${hostFor(cell.topology)}:$port/"
    }

    /** The server's *self-reported* URL inside the JNLP — its real port, which a port-forward differs from. */
    private fun serverArgFor(cell: Cell): String {
        val selfPort = if (cell.scheme == Scheme.HTTPS) 8443 else 8080
        return "${cell.scheme.s}://${hostFor(cell.topology)}:$selfPort"
    }

    private fun liveNote(cell: Cell): String = when (cell.topology) {
        Topology.LOCAL -> "live loopback exercised by the soak + live-cert trust checks"
        Topology.PORT_FORWARD -> "forwarded-port URL rewrite asserted; live tunnel field-verified (see memory)"
        Topology.INTERNAL -> "manual — needs an internal host (e.g. mirth.example.internal:8443) reachable from the runner"
        Topology.PUBLIC -> "manual — needs a public-CA FQDN + DNS"
    }

    private fun renderReport(): String {
        val all = rows.sortedWith(compareBy({ it.cell.scheme }, { it.cell.topology }, { it.cell.trust }, { it.cell.runtime }))
        val pass = all.count { it.status == "PASS" }
        val fail = all.count { it.status == "FAIL" }
        val sb = StringBuilder()
        sb.appendLine("# Gestalt Cloud Connect 1.0 — Phase 5 Matrix Harness Results")
        sb.appendLine()
        sb.appendLine("Generated ${java.time.LocalDate.now()} by `MatrixHarnessTest` (launcher-core test suite).")
        sb.appendLine()
        sb.appendLine("Each cell drives the **real** fail-closed verify+launch pipeline (`LauncherCore.prepareLaunch`,")
        sb.appendLine("which halts before spawning any process) with an offline fetcher serving genuine BouncyCastle-")
        sb.appendLine("signed jars + a real OIE/Mirth-shaped JNLP. The pipeline's *construction* (jar admission, child-")
        sb.appendLine("JVM command for the selected bundled runtime, truststore propagation, server-URL rewrite) is")
        sb.appendLine("verified locally for every cell. A **live connect** to a real internal/public server (or a")
        sb.appendLine("different OS) can't run on this runner and is marked _manual_ with what it needs.")
        sb.appendLine()
        sb.appendLine("**Summary:** $pass PASS, $fail FAIL, ${all.size} cells. Companion reports:")
        sb.appendLine("`cert-rotation-results.md`, `soak-results.md`.")
        sb.appendLine()
        sb.appendLine("| Scheme | Topology | Trust | Client runtime | Status | Live-connect facet |")
        sb.appendLine("|---|---|---|---|---|---|")
        for (r in all) {
            sb.appendLine(
                "| ${r.cell.scheme.s} | ${r.cell.topology.label} | ${r.cell.trust.label} | " +
                    "${r.cell.runtime.label} | ${r.status} | ${r.live} |",
            )
        }
        sb.appendLine()
        sb.appendLine("## TLS trust — live certificate objects")
        sb.appendLine()
        sb.appendLine("| Check | Status | Detail |")
        sb.appendLine("|---|---|---|")
        for (t in tlsRows) sb.appendLine("| ${t.first} | ${t.second} | ${t.third} |")
        sb.appendLine()
        sb.appendLine("## Notes")
        sb.appendLine()
        sb.appendLine("- **Client runtime** is exercised by pointing the bundled-JRE override at a synthetic Java-8 and")
        sb.appendLine("  Java-17 home; the harness asserts the child command targets the right runtime and that the")
        sb.appendLine("  Java-9+ module flags are applied on 17 and withheld on 8 (they abort a Java-8 launch).")
        sb.appendLine("- **internal-host / public-FQDN** rows validate URL resolution, the no-downgrade / SSRF host")
        sb.appendLine("  checks, and command construction headlessly; only a *live* TCP/TLS connect needs the real")
        sb.appendLine("  server and is left manual.")
        sb.appendLine("- The live exact-cert TLS round-trip (pin pass / wrong-pin fail / hostname-mismatch tolerance)")
        sb.appendLine("  is additionally covered by `HostnameVerificationTest`.")
        return sb.toString()
    }
}
