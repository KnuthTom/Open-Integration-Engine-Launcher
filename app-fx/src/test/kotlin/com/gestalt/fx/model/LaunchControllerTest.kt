/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.fx.model

import com.gestalt.launcher.diag.Diagnosis
import com.gestalt.launcher.diag.FailureKind
import com.gestalt.launcher.launch.AutoLogin
import com.gestalt.launcher.launch.LaunchPhase
import com.gestalt.launcher.launch.LauncherCore
import com.gestalt.launcher.launch.PublisherReviewRequiredException
import com.gestalt.launcher.launch.ServerCertificateReviewRequiredException
import com.gestalt.launcher.model.Connection
import com.gestalt.launcher.tls.CertDetails
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.IOException
import java.security.cert.X509Certificate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The launch worker: one in flight per connection, cancel by interrupt, and each failure kind routed to its own prompt. */
class LaunchControllerTest {

    private class Recorder : LaunchEvents {
        val log = CopyOnWriteArrayList<String>()
        val done = CountDownLatch(1)
        override fun onPhase(id: String, phase: LaunchPhase) { log += "phase:$phase" }
        override fun onRunning(id: String, instances: Int) { log += "running:$instances"; done.countDown() }
        override fun onExited(id: String, instances: Int) { log += "exited:$instances" }
        override fun onCancelled(id: String) { log += "cancelled"; done.countDown() }
        override fun onNeedsServerTrust(id: String, leaf: X509Certificate, hostPort: String?) { log += "server-trust:$hostPort"; done.countDown() }
        override fun onServerRejected(id: String, leaf: X509Certificate) { log += "server-rejected"; done.countDown() }
        override fun onNeedsPublisherTrust(id: String, signer: X509Certificate, artifactName: String, reason: String) { log += "publisher-trust:$artifactName"; done.countDown() }
        override fun onFailed(id: String, error: Throwable, diagnosis: Diagnosis) { log += "failed:${diagnosis.kind}"; done.countDown() }
    }

    private class ScriptedLauncher(private val script: (onPhase: (LaunchPhase) -> Unit) -> Process) : LauncherCore() {
        override fun launch(connection: Connection, onPhase: (LaunchPhase) -> Unit, autoLogin: AutoLogin?): Process = script(onPhase)
    }

    private val conn = Connection(id = "c", name = "C", serverUrl = "https://lab.example.internal:8443")

    /** Any real certificate will do for routing; the bundled public root is on the classpath. */
    private fun someCert(): X509Certificate =
        requireNotNull(javaClass.getResourceAsStream("/anchors/usertrust-rsa-root.pem")).use { CertDetails.readCert(it) }

    private fun controller(rec: Recorder, launcher: LauncherCore) = LaunchController({ launcher }, rec, { it.run() })

    @Test
    @DisplayName("an unknown server certificate is a review event, not a failure")
    fun serverTrustIsRouted() {
        val rec = Recorder()
        val leaf = someCert()
        val ctl = controller(rec, ScriptedLauncher { throw ServerCertificateReviewRequiredException(leaf, "lab.example.internal:8443", null) })
        ctl.launch(conn)
        assertTrue(rec.done.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("server-trust:lab.example.internal:8443"), rec.log)
        assertFalse(ctl.isInFlight("c")) { "the in-flight slot is released after a failure" }
    }

    @Test
    @DisplayName("an unknown publisher is a review event carrying the jar name")
    fun publisherTrustIsRouted() {
        val rec = Recorder()
        val signer = someCert()
        val ctl = controller(rec, ScriptedLauncher { throw PublisherReviewRequiredException(signer, "webstart/extensions/libs/custom/custom-client.jar", "not on the pinned allow-list") })
        ctl.launch(conn)
        assertTrue(rec.done.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("publisher-trust:webstart/extensions/libs/custom/custom-client.jar"), rec.log)
    }

    @Test
    @DisplayName("any other error is classified into a diagnosis")
    fun failureIsDiagnosed() {
        val rec = Recorder()
        val ctl = controller(rec, ScriptedLauncher { throw IOException("Connection refused: connect") })
        ctl.launch(conn)
        assertTrue(rec.done.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("failed:${FailureKind.HostUnreachable}"), rec.log)
    }

    @Test
    @DisplayName("a launch still preparing can be cancelled: the worker is interrupted, nothing is spawned")
    fun cancelInterrupts() {
        val rec = Recorder()
        val started = CountDownLatch(1)
        val ctl = controller(rec, ScriptedLauncher { onPhase ->
            onPhase(LaunchPhase.Downloading)
            started.countDown()
            Thread.sleep(10_000) // interrupted by cancel
            throw IllegalStateException("unreachable")
        })
        ctl.launch(conn)
        assertTrue(started.await(5, TimeUnit.SECONDS))
        assertTrue(ctl.isInFlight("c"))
        ctl.cancel("c")
        assertTrue(rec.done.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("phase:Downloading", "cancelled"), rec.log)
        assertFalse(ctl.isInFlight("c"))
    }

    @Test
    @DisplayName("a second launch while one is preparing is ignored (one in flight per connection)")
    fun oneInFlight() {
        val rec = Recorder()
        val gate = CountDownLatch(1)
        var calls = 0
        val ctl = controller(rec, ScriptedLauncher { calls++; gate.await(); throw IOException("stop") })
        ctl.launch(conn)
        ctl.launch(conn)
        gate.countDown()
        assertTrue(rec.done.await(5, TimeUnit.SECONDS))
        assertEquals(1, calls)
    }
}
