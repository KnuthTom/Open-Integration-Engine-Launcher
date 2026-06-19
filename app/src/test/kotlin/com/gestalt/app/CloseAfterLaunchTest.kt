/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.app

import com.gestalt.launcher.credential.InMemoryCredentialStore
import com.gestalt.launcher.launch.LaunchPhase
import com.gestalt.launcher.launch.LauncherCore
import com.gestalt.launcher.model.CloseAfterLaunch
import com.gestalt.launcher.model.Connection
import com.gestalt.launcher.model.ConnectionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Path

/**
 * Close-after-launch resolution (§6.1): the per-connection override wins; Inherit follows the global
 * toggle. The close itself fires only on the success path in [AppState.launch] (a failure goes to the
 * catch and never closes) — that is guaranteed structurally; here we pin down the override resolution.
 */
class CloseAfterLaunchTest {

    private fun newState(dir: Path): AppState = AppState(
        store = ConnectionStore(dir.resolve("connections.json")),
        prefs = AppPreferences(dir.resolve("prefs.properties")),
        credentials = InMemoryCredentialStore(),
        scope = CoroutineScope(Dispatchers.Unconfined),
    )

    private fun conn(mode: CloseAfterLaunch) =
        Connection(id = "c", name = "C", serverUrl = "https://h:8443", closeAfterLaunch = mode)

    @Test
    @DisplayName("Inherit follows the global toggle (both off and on)")
    fun inheritFollowsGlobal(@TempDir dir: Path) {
        val state = newState(dir)
        state.updateCloseAfterLaunch(false)
        assertFalse(state.resolveCloseAfterLaunch(conn(CloseAfterLaunch.Inherit)))
        state.updateCloseAfterLaunch(true)
        assertTrue(state.resolveCloseAfterLaunch(conn(CloseAfterLaunch.Inherit)))
    }

    @Test
    @DisplayName("a per-connection override wins over the global setting both ways")
    fun overrideWins(@TempDir dir: Path) {
        val state = newState(dir)
        state.updateCloseAfterLaunch(false) // global OFF
        assertTrue(state.resolveCloseAfterLaunch(conn(CloseAfterLaunch.On))) { "force-on overrides global off" }

        state.updateCloseAfterLaunch(true) // global ON
        assertFalse(state.resolveCloseAfterLaunch(conn(CloseAfterLaunch.Off))) { "force-off overrides global on" }
    }

    @Test
    @DisplayName("a FAILED launch never closes the window, even with close-after-launch forced on")
    fun failedLaunchNeverCloses(@TempDir dir: Path) {
        var closed = false
        val state = AppState(
            store = ConnectionStore(dir.resolve("connections.json")),
            prefs = AppPreferences(dir.resolve("prefs.properties")),
            credentials = InMemoryCredentialStore(),
            scope = CoroutineScope(Dispatchers.Unconfined),
            onRequestClose = { closed = true },
            // Inject a launcher whose fetch fails immediately, so the launch lands in the catch branch.
            launcherFactory = { cfg ->
                LauncherCore(
                    trustStore = cfg.trustStore,
                    signerPins = cfg.signerPins,
                    gateMode = cfg.mode,
                    fetcherFactory = { throw IOException("boom — the launch must fail") },
                )
            },
        )
        state.updateCloseAfterLaunch(true) // global ON
        state.upsert(Connection(id = "c", name = "C", serverUrl = "https://h:8443", closeAfterLaunch = CloseAfterLaunch.On)) // override ON too

        state.launch("c")

        // launch() runs its work on Dispatchers.IO; wait briefly for it to reach the Failed phase.
        var waited = 0
        while (state.launches["c"]?.phase != LaunchPhase.Failed && waited < 3000) { Thread.sleep(25); waited += 25 }
        assertEquals(LaunchPhase.Failed, state.launches["c"]?.phase) { "the injected launcher must fail the launch" }
        assertFalse(closed) { "a FAILED launch must NEVER close the window, even with close-after-launch forced on" }
    }
}
