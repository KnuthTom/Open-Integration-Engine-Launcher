/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.launch

import com.gestalt.launcher.model.Connection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.nio.file.Files

/**
 * Opt-in live check against a REAL OIE/Mirth server. Skipped unless `GESTALT_LIVE_SERVER` is set to the
 * server base URL (e.g. `http://localhost:8080`). It runs the production defaults end to end — bundled
 * anchors + official signer pins + inline-JNLP digests — and asserts every jar the server serves is
 * admitted by the fail-closed gate, and that the child command carries the curated module flags.
 *
 * Run it with: `GESTALT_LIVE_SERVER=http://localhost:8080 ./gradlew :launcher-core:test --tests '*LiveServerLaunchIT*' --no-daemon`
 */
@EnabledIfEnvironmentVariable(named = "GESTALT_LIVE_SERVER", matches = ".+")
class LiveServerLaunchIT {

    @Test
    @DisplayName("prepareLaunch verifies and admits all jars from a live OIE/Mirth server")
    fun verifiesRealServer() {
        val url = System.getenv("GESTALT_LIVE_SERVER")
        val cache = Files.createTempDirectory("gestalt-live")
        val plan = LauncherCore(cacheDir = cache).prepareLaunch(
            Connection(id = "live", name = "Live", serverUrl = url),
        ) { phase -> println("phase=$phase") }

        println("admitted=${plan.admitted.size} version=${plan.version} main=${plan.mainClass}")
        plan.admitted.take(3).forEach { println("  ${it.artifactName} <- ${it.signerDN}") }
        println("module flags: " + plan.command.filter { it.startsWith("--add-") })

        assertEquals("com.mirth.connect.client.ui.Mirth", plan.mainClass)
        assertTrue(plan.admitted.size >= 90) { "expected ~93 admitted jars, got ${plan.admitted.size}" }
        assertTrue(plan.command.any { it.startsWith("--add-opens") }) {
            "the modern client's module flags should be present on a Java 9+ launcher JVM"
        }
    }
}
