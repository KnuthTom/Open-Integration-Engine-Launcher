/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.app

import com.gestalt.launcher.credential.CredentialStore
import com.gestalt.launcher.credential.InMemoryCredentialStore
import com.gestalt.launcher.launch.AutoLogin
import com.gestalt.launcher.launch.LaunchPhase
import com.gestalt.launcher.launch.LauncherCore
import com.gestalt.launcher.model.Connection
import com.gestalt.launcher.model.ConnectionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The saved credential reaches the launch as the Administrator's auto-login, and only then: no
 * username or no saved password means the login screen. The keychain is never allowed to break a
 * launch, and when it refuses to store a password the user is told rather than left to wonder.
 */
class AutoLoginWiringTest {

    /** Captures what AppState hands the launcher instead of spawning anything. */
    private class CapturingLauncher : LauncherCore() {
        val captured = AtomicReference<AutoLogin?>()
        val capturedPassword = AtomicReference<String?>()
        val called = CountDownLatch(1)
        override fun launch(connection: Connection, onPhase: (LaunchPhase) -> Unit, autoLogin: AutoLogin?): Process {
            captured.set(autoLogin)
            capturedPassword.set(autoLogin?.password?.let { String(it) }) // copy before anyone zeroes it
            called.countDown()
            onPhase(LaunchPhase.Launching)
            return ProcessBuilder(javaExe(), "-version").start()
        }
        private fun javaExe() = Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").lowercase().contains("win")) "java.exe" else "java").toString()
    }

    /** A keychain that is broken in one direction or both. */
    private class FailingCredentialStore(private val onStore: Boolean, private val onRetrieve: Boolean) : CredentialStore {
        private val inner = InMemoryCredentialStore()
        override val name = "failing"
        override fun store(connectionId: String, username: String, password: CharArray) {
            if (onStore) throw IOException("keychain locked")
            inner.store(connectionId, username, password)
        }
        override fun retrieve(connectionId: String, username: String): CharArray? {
            if (onRetrieve) throw IOException("keychain unavailable")
            return inner.retrieve(connectionId, username)
        }
        override fun delete(connectionId: String, username: String) = inner.delete(connectionId, username)
    }

    private fun state(dir: Path, creds: CredentialStore, launcher: LauncherCore) = AppState(
        store = ConnectionStore(dir.resolve("connections.json")),
        prefs = AppPreferences(dir.resolve("prefs.properties")),
        credentials = creds,
        scope = CoroutineScope(Dispatchers.Unconfined),
        launcherFactory = { launcher },
    )

    private fun conn(username: String? = "admin") = Connection(id = "c", name = "C", serverUrl = "https://mirth.example.internal:8443", username = username)

    @Test
    @DisplayName("a saved password reaches the launcher as the auto-login for that username")
    fun savedPasswordIsHandedToTheLaunch(@TempDir dir: Path) {
        val launcher = CapturingLauncher()
        val s = state(dir, InMemoryCredentialStore(), launcher)
        s.upsert(conn(), "synthetic-pw".toCharArray())

        s.launch("c")

        assertTrue(launcher.called.await(5, TimeUnit.SECONDS)) { "launch never reached the launcher" }
        val login = launcher.captured.get()
        assertNotNull(login)
        assertEquals("admin", login!!.username)
        assertEquals("synthetic-pw", launcher.capturedPassword.get())
    }

    @Test
    @DisplayName("no username, or a username with nothing saved for it: no auto-login, the login screen asks")
    fun nothingSavedMeansNoAutoLogin(@TempDir dir: Path) {
        val s = state(dir, InMemoryCredentialStore(), CapturingLauncher())
        s.upsert(conn(username = null))
        assertNull(s.autoLoginFor(s.connections.single()))

        s.upsert(conn(username = "admin")) // username, no password
        assertNull(s.autoLoginFor(s.connections.single()))
    }

    @Test
    @DisplayName("a keychain that cannot be read never stops a launch: the launch proceeds without auto-login")
    fun unreadableKeychainStillLaunches(@TempDir dir: Path) {
        val launcher = CapturingLauncher()
        val s = state(dir, FailingCredentialStore(onStore = false, onRetrieve = true), launcher)
        s.upsert(conn(), "synthetic-pw".toCharArray())

        s.launch("c")

        assertTrue(launcher.called.await(5, TimeUnit.SECONDS)) { "launch never reached the launcher" }
        assertNull(launcher.captured.get()) { "a keychain error degrades to the login screen, it does not invent credentials" }
        assertTrue(s.launches["c"]?.phase != LaunchPhase.Failed) { "a keychain error is not a launch failure" }
    }

    @Test
    @DisplayName("a keychain that refuses to store the password keeps the connection and tells the user")
    fun refusedStoreIsSurfaced(@TempDir dir: Path) {
        val s = state(dir, FailingCredentialStore(onStore = true, onRetrieve = false), CapturingLauncher())
        val pw = "synthetic-pw".toCharArray()

        s.upsert(conn(), pw)

        assertEquals("admin", s.connections.single().username) { "the connection itself is saved" }
        val notice = s.dialog as? AppDialog.Notice
        assertNotNull(notice) { "the user must be told the password was not saved" }
        assertTrue(notice!!.message.contains("keychain")) { notice.message }
        assertTrue(pw.all { it == '\u0000' }) { "the password array is zeroed either way" }
    }

    @Test
    @DisplayName("changing the username drops the credential saved under the old one (no orphan in the keychain)")
    fun renamedUserLeavesNoOrphan(@TempDir dir: Path) {
        val creds = InMemoryCredentialStore()
        val s = state(dir, creds, CapturingLauncher())
        s.upsert(conn(username = "admin"), "synthetic-pw".toCharArray())
        assertNotNull(creds.retrieve("c", "admin"))

        s.upsert(conn(username = "operator"))

        assertNull(creds.retrieve("c", "admin")) { "the old user's entry is gone" }
        assertNull(s.autoLoginFor(s.connections.single())) { "and nothing is saved for the new one yet" }
    }
}
