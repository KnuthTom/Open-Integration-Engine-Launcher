/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.app

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.gestalt.app.gallery.GalleryApp
import com.gestalt.design.branding.Branding
import com.gestalt.launcher.AppPaths
import com.gestalt.launcher.audit.AuditLog
import com.gestalt.launcher.credential.CredentialStores
import com.gestalt.launcher.diag.ConnectionDiagnostic
import com.gestalt.launcher.diag.DiagnosticsLog
import com.gestalt.launcher.gate.GestaltTrust
import com.gestalt.launcher.model.Connection
import com.gestalt.launcher.model.ConnectionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import java.util.UUID
import kotlin.system.exitProcess

/**
 * Entry point for the Open Integration Engine Launcher desktop app.
 *
 * Default: the **product UI** (connection list / form / launch — Phase 3b). `--gallery` opens the
 * design-system component gallery; `--cli` runs the minimal non-UI path.
 */
fun main(args: Array<String>) {
    when {
        args.any { it == "--test" || it == "--diagnose" } -> runDiagnosticCli(args)
        args.any { it == "--cli" || it == "--headless" } -> runCliPath()
        args.any { it == "--gallery" } -> launchGallery()
        else -> launchProduct()
    }
}

/**
 * Headless Test-connection diagnostic: `--test <connection-name|url>`. Runs the same staged checks the UI
 * button runs (DNS/TCP → TLS handshake + leaf capture/classify → JNLP → digest), prints a PASS/FAIL report,
 * appends full detail to logs/diagnostics.txt, and exits non-zero if any check failed. Never weakens
 * verification — an untrusted cert is reported (with its SHA-256) so it can be reviewed and pinned in the UI.
 */
private fun runDiagnosticCli(args: Array<String>): Nothing {
    val flagIdx = args.indexOfFirst { it == "--test" || it == "--diagnose" }
    val target = args.getOrNull(flagIdx + 1)?.takeIf { it.isNotBlank() && !it.startsWith("--") }
    if (target == null) {
        System.err.println("usage: --test <connection-name|url>   (e.g. --test \"TK Sandbox\"  or  --test https://localhost:31065)")
        exitProcess(2)
    }

    // Optional `--pin <sha256>` to verify the pinned path headlessly (exact-cert trust, hostname bypassed).
    val pin = args.toList().zipWithNext().firstOrNull { it.first == "--pin" }?.second?.takeIf { it.isNotBlank() }

    val store = ConnectionStore()
    val resolved = store.list().firstOrNull { it.name.equals(target, ignoreCase = true) }
        ?: if (target.startsWith("http://") || target.startsWith("https://")) {
            Connection(id = "diag-" + UUID.randomUUID(), name = target, serverUrl = target)
        } else {
            System.err.println("No saved connection named \"$target\". Known connections:")
            store.list().forEach { System.err.println("  - ${it.name}  (${it.serverUrl})") }
            System.err.println("Or pass a full URL, e.g. --test https://localhost:31065")
            exitProcess(2)
        }
    val connection = if (pin != null) resolved.copy(pinnedCertSha256 = pin) else resolved

    println("Testing ${connection.name} (${connection.serverUrl}) …\n")
    val report = runCatching { ConnectionDiagnostic.run(connection) }.getOrElse {
        System.err.println("The connection test could not run: ${it.message ?: it.javaClass.simpleName}")
        exitProcess(1)
    }
    println(report.toText())
    val logPath = runCatching { DiagnosticsLog.append(report) }.getOrNull()
    logPath?.let { println("Full detail appended to: $it") }
    println("\nRESULT: ${report.summaryLine()}")
    exitProcess(if (report.ok) 0 else 1)
}

/** The Phase-3b product window: connection list, form, and the wired Launch action. */
private fun launchProduct() = application {
    val branding = Branding.Default
    val windowState = rememberWindowState(size = DpSize(1180.dp, 840.dp))
    // Safety net: a background coroutine whose body throws without its own catch is otherwise swallowed
    // silently. Log it (audit + stderr) so a probe/test/update failure is never invisible. Cancellation
    // (normal on window close) is ignored.
    val scope = rememberCoroutineScope {
        CoroutineExceptionHandler { _, e ->
            if (e !is CancellationException) {
                runCatching { AuditLog.event("ui.coroutine.uncaught", "error" to (e.message ?: e.javaClass.simpleName)) }
                System.err.println("Uncaught coroutine exception: ${e.javaClass.name}: ${e.message}")
            }
        }
    }
    val appState = remember {
        AppState(
            store = ConnectionStore(),
            prefs = AppPreferences(),
            credentials = CredentialStores.forCurrentOs(),
            scope = scope,
            onRequestClose = ::exitApplication,
        )
    }
    // Best-effort signed update check on launch (only if a channel is configured; never blocks startup).
    androidx.compose.runtime.LaunchedEffect(Unit) { appState.checkForUpdatesOnLaunch() }
    Window(
        onCloseRequest = ::exitApplication,
        state = windowState,
        title = "${branding.appName} 1.0",
        onPreviewKeyEvent = { handleShortcut(it, appState) },
    ) {
        // Keep a sane minimum so the data-table rows can't be squeezed into the broken wrapping layout.
        androidx.compose.runtime.SideEffect { window.minimumSize = java.awt.Dimension(1040, 660) }
        ProductApp(appState, branding)
    }
}

/** App-wide keyboard shortcuts (keyboard-first §6.3.1). Returns true when the key was consumed. */
private fun handleShortcut(ev: KeyEvent, appState: AppState): Boolean {
    if (ev.type != KeyEventType.KeyDown) return false
    val mod = ev.isCtrlPressed || ev.isMetaPressed
    // Global shortcuts — available from anywhere.
    when {
        mod && ev.key == Key.K -> { appState.focusSearch(); return true }
        mod && ev.key == Key.F -> { appState.focusSearch(); return true }
        mod && ev.key == Key.N -> { appState.startNewConnection(); return true }
        ev.key == Key.Escape -> return appState.onEscape()
        ev.isShiftPressed && ev.key == Key.Slash -> { appState.toggleShortcuts(); return true } // "?"
    }
    // Row navigation + multi-select — only when the list is the active surface and we're not typing in
    // search. Enter/Space are claimed only while a row cursor is live; the cursor is dropped whenever the
    // user leaves the list (AppState.route), so a stale cursor can't make these keys shadow buttons.
    val listActive = appState.route is Route.List && appState.dialog == null &&
        !appState.showShortcuts && !appState.searchFocused
    if (listActive) {
        when {
            mod && ev.key == Key.A -> { appState.toggleSelectAllVisible(); return true }
            ev.key == Key.DirectionDown -> { appState.moveFocus(1); return true }
            ev.key == Key.DirectionUp -> { appState.moveFocus(-1); return true }
            ev.key == Key.Enter && appState.focusedId != null -> { appState.launchFocused(); return true }
            ev.key == Key.Spacebar && appState.focusedId != null -> { appState.toggleFocusedSelection(); return true }
        }
    }
    return false
}

/** The design-system component gallery (Phase 3a acceptance artifact). */
private fun launchGallery() = application {
    val branding = Branding.Default
    val windowState = rememberWindowState(size = DpSize(1140.dp, 780.dp))
    Window(onCloseRequest = ::exitApplication, state = windowState, title = "${branding.appName} — Gallery") {
        GalleryApp(branding = branding)
    }
}

/** The minimal, non-UI launch path: report identity and Phase-2 trust posture, then exit. */
private fun runCliPath() {
    val branding = Branding.Default
    println("${branding.appName} 1.0 — CLI launch path")
    runCatching { println("Data dir : ${AppPaths.dataDir}") }
        .onFailure { println("Data dir : <unavailable: ${it.message}>") }
    println("Trust pins: " + GestaltTrust.OFFICIAL_SIGNER_PINS.joinToString { it.label })
    println("Run with no arguments for the product UI, or --gallery for the component gallery.")
}
