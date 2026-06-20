/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.matrix

import com.gestalt.launcher.diag.ConnectionDiagnostic
import com.gestalt.launcher.gate.GestaltTrust
import com.gestalt.launcher.launch.LauncherCore
import com.gestalt.launcher.model.Connection
import com.gestalt.launcher.model.ConnectionStore
import com.gestalt.launcher.testutil.TestArtifacts
import com.gestalt.trust.TrustMode
import com.gestalt.trust.TrustStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Phase-5 **soak regression** (the hardening-pass guard, headless + automated). Mirrors the live soak in
 * `FINDINGS.md` — import 34 connections, probe them, "launch" a few, repeat — but in-process so it can
 * **fail on regression**: it asserts the live heap does not grow across iterations and that no non-daemon
 * thread leaks. It exercises exactly the paths the hardening pass touched: the atomic/crash-safe
 * `connections.json` write+reload (#4), the bounded jar cache (reuse + stale-version prune, #12), and the
 * closeable hygiene in the verify pipeline.
 *
 * The live-GUI facets of the soak (10-minute idle flatness, clean JVM exit on window close) were measured
 * in `FINDINGS.md` and are unchanged by the fast-forward merge (identical commits); this test is the
 * repeatable CI guard for the in-JVM paths. Iteration count is overridable with
 * `-Dphase5.soak.iterations=N`. Results are written to `build/soak-results.md`.
 */
class SoakRegressionTest {

    private val oneMb = 1024L * 1024L

    @Test
    @DisplayName("import 34 → probe → launch a few → repeat: heap flat, no non-daemon thread leak")
    fun soakHasNoHeapOrThreadRegression(@TempDir dir: Path) {
        // --- import 34 connections via the atomic, crash-safe store, then reload (hardening fix #4) ---
        val store = ConnectionStore(dir.resolve("connections.json"))
        store.replaceAll((1..34).map { synthConnection(it) })
        store.load()
        assertEquals(34, store.list().size) { "all 34 imported connections must survive the atomic write + reload" }
        val connections = store.list()

        // --- a launcher for the headless "launch a few" load (exercises cache reuse + prune, #12) ---
        val ca = TestArtifacts.selfSignedCa("CN=Pretend Sectigo,O=Pretend CA,C=GB")
        val leaf = TestArtifacts.codeSigningLeaf("CN=OIE,O=BrightCodeCompany OÜ,C=EE", ca)
        val jarBytes = Phase5Fixtures.signedCore(dir, leaf)
        val launchBase = "https://localhost:8443/"
        val launcher = LauncherCore(
            trustStore = TrustStore.of(ca.cert),
            signerPins = GestaltTrust.OFFICIAL_SIGNER_PINS,
            gateMode = TrustMode.PIN_KNOWN_SIGNERS,
            cacheDir = dir.resolve("cache"),
            fetcherFactory = { FakeFetcher(Phase5Fixtures.canned(launchBase, jarBytes, "https://localhost:8443")) },
        )
        val launchConn = Connection(id = "soak-launch", name = "soak-launch", serverUrl = launchBase)

        val iterations = (System.getProperty("phase5.soak.iterations")?.toIntOrNull() ?: 10).coerceIn(3, 200)

        // Baseline after a warmup iteration (lets class-loading / JIT settle before we measure).
        val threadBaseline = nonDaemonThreads()
        soakIteration(connections, launcher, launchConn)
        val baselineHeap = liveHeapAfterGc()
        val series = mutableListOf<Long>()
        repeat(iterations) {
            soakIteration(connections, launcher, launchConn)
            series += liveHeapAfterGc()
        }
        System.gc(); Thread.sleep(150); System.gc()
        val threadEnd = nonDaemonThreads()

        val endHeap = series.last()
        val growthMb = (endHeap - baselineHeap).toDouble() / oneMb
        // A leak across these paths would compound every iteration; allow generous noise but catch growth.
        // Judge "rose every iteration" in whole-MB terms so byte-level GC jitter doesn't read as a climb.
        val mbSeries = series.map { it / oneMb }
        val strictlyMonotonic = mbSeries.size >= 2 && mbSeries.zipWithNext().all { (a, b) -> b > a }

        writeReport(iterations, threadBaseline, threadEnd, baselineHeap, series, growthMb, strictlyMonotonic)

        assertTrue(threadEnd <= threadBaseline) {
            "non-daemon threads leaked across the soak: baseline=$threadBaseline end=$threadEnd"
        }
        assertTrue(growthMb < 24.0) {
            "live heap grew ${"%.1f".format(growthMb)} MB across $iterations iterations (possible leak); series=$series"
        }
        assertFalse(strictlyMonotonic && (endHeap - baselineHeap) > 8 * oneMb) {
            "live heap rose every single iteration — monotonic growth signal; series=$series"
        }
    }

    /** One soak iteration: probe all connections (real fast-failing TCP), then "launch" a few headlessly. */
    private fun soakIteration(connections: List<Connection>, launcher: LauncherCore, launchConn: Connection) {
        for (c in connections) runCatching { ConnectionDiagnostic.run(c, timeoutMs = 250) }
        repeat(3) { runCatching { launcher.prepareLaunch(launchConn) } }
    }

    /** A loopback connection to a closed high port — DNS-free and refused fast, so the probe stays snappy. */
    private fun synthConnection(i: Int): Connection {
        val scheme = if (i % 3 == 0) "http" else "https"
        return Connection(id = "soak-$i", name = "Soak $i", serverUrl = "$scheme://127.0.0.1:${59000 + i}/")
    }

    private fun nonDaemonThreads(): Int = Thread.getAllStackTraces().keys.count { !it.isDaemon && it.isAlive }

    private fun liveHeapAfterGc(): Long {
        System.gc(); Thread.sleep(50); System.gc()
        val rt = Runtime.getRuntime()
        return rt.totalMemory() - rt.freeMemory()
    }

    private fun writeReport(
        iterations: Int,
        threadBaseline: Int,
        threadEnd: Int,
        baselineHeap: Long,
        series: List<Long>,
        growthMb: Double,
        strictlyMonotonic: Boolean,
    ) {
        val sb = StringBuilder()
        sb.appendLine("# Gestalt Cloud Connect 1.0 — Phase 5 Soak Regression")
        sb.appendLine()
        sb.appendLine("Generated ${java.time.LocalDate.now()} by `SoakRegressionTest` ($iterations measured iterations).")
        sb.appendLine()
        sb.appendLine("Headless re-run of the hardening soak: **import 34 → probe → launch a few → repeat**, asserting")
        sb.appendLine("no heap growth and no non-daemon thread leak. Exercises the atomic `connections.json` write +")
        sb.appendLine("reload (#4), the bounded jar cache (#12), and verify-pipeline closeable hygiene.")
        sb.appendLine()
        sb.appendLine("| Metric | Value |")
        sb.appendLine("|---|---|")
        sb.appendLine("| Imported connections (after atomic write + reload) | 34 |")
        sb.appendLine("| Non-daemon threads (baseline → end) | $threadBaseline → $threadEnd |")
        sb.appendLine("| Live heap baseline (post-GC) | ${"%.1f".format(baselineHeap.toDouble() / oneMb)} MB |")
        sb.appendLine("| Live heap end (post-GC) | ${"%.1f".format(series.last().toDouble() / oneMb)} MB |")
        sb.appendLine("| Heap growth baseline → end | ${"%.1f".format(growthMb)} MB |")
        sb.appendLine("| Heap rose every iteration? | $strictlyMonotonic |")
        sb.appendLine("| Per-iteration heap series (MB) | ${series.joinToString(", ") { "%.1f".format(it.toDouble() / oneMb) }} |")
        sb.appendLine()
        sb.appendLine("**Verdict:** ${if (growthMb < 24.0 && threadEnd <= threadBaseline) "PASS — no regression" else "FAIL — see series"}.")
        sb.appendLine()
        sb.appendLine("Live-GUI facets (10-min idle flatness, clean JVM exit on window close) are recorded in")
        sb.appendLine("`FINDINGS.md` and unchanged by the fast-forward merge.")
        Phase5Report.write("soak-results.md", sb.toString())
    }
}
