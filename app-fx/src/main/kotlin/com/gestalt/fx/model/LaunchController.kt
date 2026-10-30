/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.fx.model

import com.gestalt.launcher.diag.Diagnosis
import com.gestalt.launcher.diag.FailureDiagnosis
import com.gestalt.launcher.launch.AutoLogin
import com.gestalt.launcher.launch.LaunchPhase
import com.gestalt.launcher.launch.LauncherCore
import com.gestalt.launcher.launch.PublisherReviewRequiredException
import com.gestalt.launcher.launch.ServerCertificateRejectedException
import com.gestalt.launcher.launch.ServerCertificateReviewRequiredException
import com.gestalt.launcher.model.Connection
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.FutureTask

/** What the UI hears about a launch. Every call arrives on the UI thread. */
interface LaunchEvents {
    fun onPhase(id: String, phase: LaunchPhase)

    /** The child is running; [instances] Administrators this launcher started for [id] are alive. */
    fun onRunning(id: String, instances: Int)

    /** A child exited; [instances] remain. */
    fun onExited(id: String, instances: Int)

    fun onCancelled(id: String)

    /** The server's certificate is unknown and the connection doesn't auto-approve: show it, offer Trust / Reject. */
    fun onNeedsServerTrust(id: String, leaf: X509Certificate, hostPort: String?)

    /** The server's certificate was rejected earlier: the launch stays closed until that decision changes. */
    fun onServerRejected(id: String, leaf: X509Certificate)

    /** A jar's publisher is unknown: show it, offer Trust / Reject. */
    fun onNeedsPublisherTrust(id: String, signer: X509Certificate, artifactName: String, reason: String)

    fun onFailed(id: String, error: Throwable, diagnosis: Diagnosis)
}

/**
 * Runs launches off the UI thread and holds the Administrators they start. One launch may be in
 * flight per connection (a second request while one is preparing is ignored — a held Enter can't
 * spawn two downloads); a connection that is already running may be launched again. A launch still
 * preparing can be cancelled: the worker thread is interrupted and [LauncherCore] stops at its next
 * fetch boundary, spawning nothing.
 *
 * [ui] marshals every event onto the UI thread (`Platform.runLater` in the app; a direct call in
 * tests, where no toolkit runs).
 */
class LaunchController(
    private val launcherFactory: () -> LauncherCore,
    private val events: LaunchEvents,
    private val ui: (Runnable) -> Unit,
    private val executor: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "launch").apply { isDaemon = true }
    },
) {
    private val inFlight = ConcurrentHashMap<String, Future<*>>()
    private val running = ConcurrentHashMap<String, CopyOnWriteArrayList<Process>>()

    fun isInFlight(id: String): Boolean = inFlight.containsKey(id)

    fun runningInstances(id: String): Int = running[id]?.size ?: 0

    /** Start [connection]. [autoLogin] (if any) is handed to the launcher, which zeroes it. */
    fun launch(connection: Connection, autoLogin: AutoLogin? = null) {
        val id = connection.id
        if (inFlight.containsKey(id)) return
        val launcher = launcherFactory()
        val task = FutureTask<Unit> {
            try {
                val process = launcher.launch(connection, { phase -> if (phase != LaunchPhase.Running) post { events.onPhase(id, phase) } }, autoLogin)
                track(id, process)
            } catch (e: InterruptedException) {
                post { events.onCancelled(id) }
            } catch (t: Throwable) {
                if (Thread.currentThread().isInterrupted) post { events.onCancelled(id) }
                else post { deliverFailure(id, connection, t) }
            }
        }
        // Registered before it runs, removed only if still ours: a launch that fails at once can't leave
        // a stale in-flight entry behind that would block every later launch of this connection.
        inFlight[id] = task
        executor.execute {
            try { task.run() } finally { inFlight.remove(id, task) }
        }
    }

    /** Cancel a launch still being prepared. No-op once the child is running. */
    fun cancel(id: String) {
        inFlight[id]?.cancel(true)
    }

    private fun track(id: String, process: Process) {
        val list = running.getOrPut(id) { CopyOnWriteArrayList() }
        list.add(process)
        post { events.onRunning(id, list.size) }
        process.onExit().thenRun {
            list.remove(process)
            if (list.isEmpty()) running.remove(id, list)
            post { events.onExited(id, list.size) }
        }
    }

    private fun deliverFailure(id: String, connection: Connection, t: Throwable) {
        when (t) {
            is ServerCertificateReviewRequiredException -> events.onNeedsServerTrust(id, t.leaf, t.hostPort)
            is ServerCertificateRejectedException -> events.onServerRejected(id, t.leaf)
            is PublisherReviewRequiredException -> events.onNeedsPublisherTrust(id, t.signer, t.artifactName, t.reason)
            else -> events.onFailed(id, t, FailureDiagnosis.classify(t, connection))
        }
    }

    private fun post(block: () -> Unit) = ui(Runnable { block() })

    fun shutdown() {
        inFlight.values.forEach { it.cancel(true) }
        executor.shutdownNow()
    }
}
