/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.fx.model

import com.gestalt.launcher.credential.InMemoryCredentialStore
import com.gestalt.launcher.gate.TrustedSignerStore
import com.gestalt.launcher.launch.AutoLogin
import com.gestalt.launcher.launch.LaunchPhase
import com.gestalt.launcher.launch.LauncherCore
import com.gestalt.launcher.model.Connection
import com.gestalt.launcher.model.ConnectionStore
import com.gestalt.launcher.tls.TrustedCertStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The window's state holder, headless: selection and the draft, dirty tracking, save / save as /
 * new / delete / rename, folders, the debounced persist and its flush, and the launch path through a
 * fake launcher.
 */
class LauncherModelTest {

    private class FakeProcess : Process() {
        private val exited = CountDownLatch(1)
        fun exit() = exited.countDown()
        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int { exited.await(); return 0 }
        override fun exitValue(): Int { if (exited.count > 0) throw IllegalThreadStateException("running"); return 0 }
        override fun destroy() { exited.countDown() }
        override fun pid(): Long = 4242
        override fun isAlive(): Boolean = exited.count > 0
    }

    private class FakeLauncher : LauncherCore() {
        val spawned = ArrayList<FakeProcess>()
        var lastLogin: AutoLogin? = null
        @Volatile var lastConnection: Connection? = null
        val called = CountDownLatch(1)
        override fun launch(connection: Connection, onPhase: (LaunchPhase) -> Unit, autoLogin: AutoLogin?): Process {
            lastConnection = connection
            lastLogin = autoLogin?.let { AutoLogin(it.username, it.password.copyOf()) }
            onPhase(LaunchPhase.Verifying)
            progressListener?.invoke(0, 3)
            progressListener?.invoke(2, 3)
            onPhase(LaunchPhase.Launching)
            called.countDown()
            return FakeProcess().also { spawned += it }
        }
    }

    private fun model(dir: Path, launcher: LauncherCore = FakeLauncher(), creds: InMemoryCredentialStore = InMemoryCredentialStore()) = LauncherModel(
        store = ConnectionStore(dir.resolve("connections.json")),
        prefs = LauncherPrefs(dir.resolve("ui-prefs.properties")),
        credentials = creds,
        trustedCerts = TrustedCertStore(dir.resolve("trusted-certs.json")),
        trustedSigners = TrustedSignerStore(dir.resolve("trusted-signers.json")),
        ui = { it.run() },
        launcherFactory = { launcher },
    )

    private fun await(what: String, timeoutMs: Long = 5_000, cond: () -> Boolean) {
        var waited = 0L
        while (!cond() && waited < timeoutMs) { Thread.sleep(10); waited += 10 }
        assertTrue(cond()) { "timed out waiting for: $what" }
    }

    @Test
    @DisplayName("a new connection is added at once and selected; edits make the draft dirty; save persists, revert drops them")
    fun draftLifecycle(@TempDir dir: Path) {
        val m = model(dir)
        val c = m.newConnection(null)
        assertEquals(c.id, m.activeId.get())
        assertEquals("New connection", m.draft.get()!!.name)
        assertFalse(m.dirty.get())
        assertNotNull(m.store.get(c.id)!!.addedAt)

        m.updateDraft { it.copy(name = "Example Lab", serverUrl = "https://lab.example.internal:8443") }
        assertTrue(m.dirty.get())
        assertEquals("New connection", m.store.get(c.id)!!.name) { "the store is untouched until Save" }

        assertTrue(m.save())
        assertFalse(m.dirty.get())
        assertEquals("Example Lab", m.store.get(c.id)!!.name)
        assertEquals("Example Lab", m.rows.filterIsInstance<TreeRow.ConnectionRow>().single().name) { "the sidebar rows follow the store" }

        m.updateDraft { it.copy(heapSize = "2g") }
        assertTrue(m.dirty.get())
        m.revert()
        assertFalse(m.dirty.get())
        assertNull(m.draft.get()!!.heapSize)

        m.flushPendingSave()
        assertEquals("Example Lab", ConnectionStore(dir.resolve("connections.json")).get(c.id)!!.name) { "flushed to disk" }
    }

    @Test
    @DisplayName("save as copies the draft (including unsaved edits) into a new connection beside the original, without the credential link, and selects it")
    fun saveAs(@TempDir dir: Path) {
        val m = model(dir)
        val c = m.newConnection(null)
        m.updateDraft { it.copy(name = "Prod", username = "admin") }
        m.save()
        m.updateDraft { it.copy(heapSize = "4g") }

        val copyId = m.saveAs()!!

        assertEquals(copyId, m.activeId.get())
        val copy = m.store.get(copyId)!!
        assertEquals("Prod copy", copy.name)
        assertEquals("4g", copy.heapSize) { "the unsaved edit went into the copy" }
        assertNull(copy.username) { "a copy starts without a keychain link" }
        assertEquals(c.sortOrder + 1, copy.sortOrder)
        assertNull(m.store.get(c.id)!!.heapSize) { "the original kept its saved state" }
        assertFalse(m.dirty.get())
    }

    @Test
    @DisplayName("delete clears the selection, the credential and the per-connection trust bookkeeping")
    fun delete(@TempDir dir: Path) {
        val creds = InMemoryCredentialStore()
        val m = model(dir, creds = creds)
        val c = m.newConnection(null)
        m.updateDraft { it.copy(username = "admin") }
        m.save()
        creds.store(c.id, "admin", "synthetic-pw".toCharArray())

        m.deleteConnection(c.id)

        assertNull(m.activeId.get())
        assertNull(m.draft.get())
        assertNull(m.store.get(c.id))
        assertNull(creds.retrieve(c.id, "admin"))
        assertTrue(m.rows.isEmpty())
    }

    @Test
    @DisplayName("folders: new, rename, move a connection in, collapse, delete lifts contents; a new connection in a collapsed folder opens it")
    fun folders(@TempDir dir: Path) {
        val m = model(dir)
        val f = m.newFolder(null, "Clients")
        val sub = m.newFolder(f.id, "SLU")
        m.renameFolder(sub.id, "St Louis University")
        assertEquals("St Louis University", m.store.getFolder(sub.id)!!.name)

        val c = m.newConnection(null)
        m.moveConnection(c.id, sub.id)
        assertEquals(sub.id, m.store.get(c.id)!!.folderId)
        assertEquals(sub.id, m.draft.get()!!.folderId) { "the draft follows a move of the active connection" }
        assertEquals("Clients / St Louis University", m.pathOf(sub.id))

        m.setFolderExpanded(f.id, false)
        assertEquals(listOf("f:${f.id}"), m.rows.map { it.key }) { "collapsed: the subtree is hidden" }

        val d = m.newConnection(sub.id)
        assertTrue(m.store.getFolder(f.id)!!.expanded) { "creating inside a collapsed tree opens the ancestors" }
        assertTrue(m.rows.any { it.key == "c:${d.id}" })

        m.deleteFolder(sub.id)
        assertEquals(f.id, m.store.get(c.id)!!.folderId) { "contents lifted to the parent" }
        assertEquals(f.id, m.store.get(d.id)!!.folderId)
        assertNull(m.store.getFolder(sub.id))
    }

    @Test
    @DisplayName("drag-and-drop: into a folder appends there; before/after reorders among siblings and switches to manual order; a folder can't be dropped into itself")
    fun dragAndDrop(@TempDir dir: Path) {
        val m = model(dir)
        val clients = m.newFolder(null, "Clients")
        val slu = m.newFolder(clients.id, "SLU")
        val a = m.newConnection(null); m.rename(a.id, "A")
        val b = m.newConnection(null); m.rename(b.id, "B")
        val c = m.newConnection(null); m.rename(c.id, "C")
        fun keys() = m.rows.map { it.key }

        assertTrue(m.moveInto("c:${a.id}", clients.id))
        assertEquals(clients.id, m.store.get(a.id)!!.folderId)
        assertEquals(listOf("f:${clients.id}", "f:${slu.id}", "c:${a.id}", "c:${b.id}", "c:${c.id}"), keys()) { "A lands last among Clients' children" }

        m.sort.set(SortMode.NameDesc)
        assertTrue(m.moveBefore("c:${c.id}", "c:${b.id}"))
        assertEquals(SortMode.Manual, m.sort.get()) { "a reorder is a manual-order gesture" }
        assertEquals(listOf("f:${clients.id}", "f:${slu.id}", "c:${a.id}", "c:${c.id}", "c:${b.id}"), keys())

        assertTrue(m.moveAfter("f:${slu.id}", "c:${b.id}"))
        assertNull(m.store.getFolder(slu.id)!!.parentId) { "SLU moved to the top level, after B" }
        assertEquals(listOf("f:${clients.id}", "c:${a.id}", "c:${c.id}", "c:${b.id}", "f:${slu.id}"), keys())

        assertTrue(m.moveInto("f:${slu.id}", clients.id))
        assertFalse(m.moveInto("f:${clients.id}", slu.id)) { "a folder never moves into its own subtree" }
        assertFalse(m.moveInto("f:${clients.id}", clients.id))
        assertEquals(clients.id, m.store.getFolder(slu.id)!!.parentId)
        assertNull(m.store.getFolder(clients.id)!!.parentId)

        m.select(a.id)
        assertTrue(m.moveInto("c:${a.id}", null))
        assertNull(m.draft.get()!!.folderId) { "the active connection's draft follows its move" }
        assertFalse(m.dirty.get())
    }

    @Test
    @DisplayName("move up / move down step a row among its siblings, stop at the ends, and switch to manual order")
    fun moveUpDown(@TempDir dir: Path) {
        val m = model(dir)
        val f = m.newFolder(null, "Clients")
        val a = m.newConnection(f.id); m.rename(a.id, "A")
        val b = m.newConnection(f.id); m.rename(b.id, "B")
        val c = m.newConnection(f.id); m.rename(c.id, "C")
        fun order() = m.siblingKeys("c:${a.id}").map { it.substring(2) }

        assertEquals(listOf(a.id, b.id, c.id), order())
        assertFalse(m.moveUp("c:${a.id}")) { "the first row can't move up" }
        assertFalse(m.moveDown("c:${c.id}")) { "the last row can't move down" }

        m.sort.set(SortMode.NameDesc)
        assertEquals(listOf(c.id, b.id, a.id), order()) { "siblings follow the current sort" }
        assertTrue(m.moveDown("c:${c.id}"))
        assertEquals(SortMode.Manual, m.sort.get())
        assertEquals(listOf(b.id, c.id, a.id), order())
        assertTrue(m.moveUp("c:${a.id}"))
        assertEquals(listOf(b.id, a.id, c.id), order())
        assertEquals(1, m.siblingIndex("c:${a.id}"))
    }

    @Test
    @DisplayName("auto-approve and the icon apply to the saved connection at once, and the draft keeps its unsaved edits")
    fun immediateSwitches(@TempDir dir: Path) {
        val m = model(dir)
        val c = m.newConnection(null)
        m.updateDraft { it.copy(heapSize = "2g") } // an unsaved edit
        assertTrue(m.dirty.get())

        m.setAutoApprove(c.id, true)
        m.setIcon(c.id, "flask-conical")

        assertTrue(m.store.get(c.id)!!.autoApproveCertificates) { "saved without a Save" }
        assertEquals("flask-conical", m.store.get(c.id)!!.icon)
        assertTrue(m.draft.get()!!.autoApproveCertificates) { "the draft got the same change" }
        assertEquals("flask-conical", m.draft.get()!!.icon)
        assertEquals("2g", m.draft.get()!!.heapSize) { "the unsaved edit survived" }
        assertTrue(m.dirty.get()) { "still dirty for the unsaved edit only" }
        m.save()
        assertEquals("2g", m.store.get(c.id)!!.heapSize)
    }

    @Test
    @DisplayName("sort, folders-first, the launcher colour and close-after-launch are remembered in the preferences")
    fun prefsFollowProperties(@TempDir dir: Path) {
        val m = model(dir)
        assertEquals(LauncherPrefs.DEFAULT_ACCENT, m.accent.get()) { "graphite until the user picks" }
        m.sort.set(SortMode.Recent)
        m.foldersFirst.set(true)
        m.accent.set("#1F66B3")
        m.background.set("#101418")
        m.closeAfterLaunch.set(true)
        val again = LauncherPrefs(dir.resolve("ui-prefs.properties"))
        assertEquals(SortMode.Recent, again.sort)
        assertTrue(again.foldersFirst)
        assertEquals("#1F66B3", again.accent)
        assertEquals("#101418", again.background)
        assertTrue(again.closeAfterLaunch)
        again.accent = "not a colour"
        again.background = ""
        val third = LauncherPrefs(dir.resolve("ui-prefs.properties"))
        assertEquals(LauncherPrefs.DEFAULT_ACCENT, third.accent) { "a stray value reads as the default" }
        assertEquals(LauncherPrefs.DEFAULT_BACKGROUND, third.background)
    }

    @Test
    @DisplayName("launch: phases flow into the launch state, the child is tracked as Running, lastLaunchedAt is stamped, exit clears the row; the saved credential rides along")
    fun launchFlow(@TempDir dir: Path) {
        val launcher = FakeLauncher()
        val creds = InMemoryCredentialStore()
        val m = model(dir, launcher, creds)
        val c = m.newConnection(null)
        m.updateDraft { it.copy(name = "Lab", serverUrl = "https://lab.example.internal:8443", username = "admin") }
        m.save()
        creds.store(c.id, "admin", "synthetic-pw".toCharArray())

        var closed = false
        m.onRequestClose = { closed = true }
        m.launch(c.id)
        assertTrue(m.launchState(c.id)!!.inFlight)
        assertTrue(launcher.called.await(5, TimeUnit.SECONDS))
        await("running") { m.launchState(c.id)?.phase == LaunchPhase.Running }

        assertEquals(1, m.launchState(c.id)!!.instances)
        assertNotNull(m.store.get(c.id)!!.lastLaunchedAt)
        assertEquals("admin", launcher.lastLogin!!.username)
        assertEquals("synthetic-pw", String(launcher.lastLogin!!.password))
        assertFalse(closed) { "close-after-launch is off by default" }

        m.launch(c.id) // a second instance is allowed
        await("two running") { m.launchState(c.id)?.instances == 2 }

        launcher.spawned.forEach { it.exit() }
        await("cleared") { m.launchState(c.id) == null }
    }

    @Test
    @DisplayName("the jar step's progress reaches the launch state while the launch is in flight, and leaves with it")
    fun progressInTheLaunchState(@TempDir dir: Path) {
        val launcher = FakeLauncher()
        val m = model(dir, launcher)
        val c = m.newConnection(null)
        var seen: Pair<Int, Int>? = null
        m.launchListener = object : LaunchEvents {
            override fun onPhase(id: String, phase: LaunchPhase) = Unit
            override fun onProgress(id: String, done: Int, total: Int) { seen = done to total }
            override fun onRunning(id: String, instances: Int) = Unit
            override fun onExited(id: String, instances: Int) = Unit
            override fun onCancelled(id: String) = Unit
            override fun onNeedsServerTrust(id: String, leaf: java.security.cert.X509Certificate, hostPort: String?) = Unit
            override fun onServerRejected(id: String, leaf: java.security.cert.X509Certificate) = Unit
            override fun onNeedsPublisherTrust(id: String, signer: java.security.cert.X509Certificate, artifactName: String, reason: String) = Unit
            override fun onFailed(id: String, error: Throwable, diagnosis: com.gestalt.launcher.diag.Diagnosis) = Unit
        }

        m.launch(c.id)
        assertTrue(launcher.called.await(5, TimeUnit.SECONDS))
        await("progress seen") { seen == (2 to 3) }
        await("running") { m.launchState(c.id)?.phase == LaunchPhase.Running }
        assertEquals(0, m.launchState(c.id)!!.total) { "Running carries no jar progress" }
        launcher.spawned.forEach { it.exit() }
        await("cleared") { m.launchState(c.id) == null }
    }

    @Test
    @DisplayName("a launch stamps 'last launched' on the saved connection without making the draft dirty, and a later Save keeps the stamp")
    fun launchDoesNotDirtyTheDraft(@TempDir dir: Path) {
        val launcher = FakeLauncher()
        val m = model(dir, launcher)
        val c = m.newConnection(null)
        assertFalse(m.dirty.get())

        m.launch(c.id)
        assertTrue(launcher.called.await(5, TimeUnit.SECONDS))
        await("running") { m.launchState(c.id)?.phase == LaunchPhase.Running }
        val stamp = m.store.get(c.id)!!.lastLaunchedAt
        assertNotNull(stamp) { "the store was stamped" }
        assertFalse(m.dirty.get()) { "a launch is bookkeeping, not an edit — no 'Save changes?' for it" }
        assertEquals(stamp, m.draft.get()!!.lastLaunchedAt) { "the header's 'Last launched' follows the stamp" }

        // A real edit made before the launch still saves — and does not roll the stamp back.
        m.updateDraft { it.copy(heapSize = "2g", lastLaunchedAt = null) }
        assertTrue(m.dirty.get())
        assertTrue(m.save())
        assertEquals("2g", m.store.get(c.id)!!.heapSize)
        assertEquals(stamp, m.store.get(c.id)!!.lastLaunchedAt) { "Save never overwrites the bookkeeping with the draft's stale copy" }
        assertFalse(m.dirty.get())
        launcher.spawned.forEach { it.exit() }
        await("cleared") { m.launchState(c.id) == null }
    }

    @Test
    @DisplayName("close-after-launch asks the window to close once the child is running")
    fun closeAfterLaunch(@TempDir dir: Path) {
        val launcher = FakeLauncher()
        val m = model(dir, launcher)
        val c = m.newConnection(null)
        m.closeAfterLaunch.set(true)
        var closed = false
        m.onRequestClose = { closed = true }

        m.launch(c.id)

        await("closed") { closed }
    }

    @Test
    @DisplayName("sign-in: the password goes to the credential store under the connection and username, the username is saved at once, and the launch carries both")
    fun savePassword(@TempDir dir: Path) {
        val launcher = FakeLauncher()
        val creds = InMemoryCredentialStore()
        val m = model(dir, launcher, creds)
        val c = m.newConnection(null)
        m.updateDraft { it.copy(serverUrl = "https://lab.example.internal:8443") }
        m.save()
        assertFalse(m.hasSavedPassword(c.id))

        assertEquals("Enter the username first", m.savePassword(c.id, "  ", "pw".toCharArray()))
        assertEquals("Enter the password to save", m.savePassword(c.id, "admin", CharArray(0)))

        val typed = "synthetic-pw".toCharArray()
        assertNull(m.savePassword(c.id, " admin ", typed))
        assertTrue(typed.all { it == '\u0000' }) { "the array is zeroed once stored" }
        assertEquals("admin", m.store.get(c.id)!!.username) { "the username is saved with the password, no Save needed" }
        assertEquals("admin", m.draft.get()!!.username)
        assertFalse(m.dirty.get())
        assertTrue(m.hasSavedPassword(c.id))
        assertEquals("synthetic-pw", String(creds.retrieve(c.id, "admin")!!))
        m.flushPendingSave()
        val onDisk = java.nio.file.Files.readString(dir.resolve("connections.json"))
        assertTrue(onDisk.contains("admin") && !onDisk.contains("synthetic-pw")) { "the username is in connections.json, the password never is" }

        m.launch(c.id)
        assertTrue(launcher.called.await(5, TimeUnit.SECONDS))
        await("running") { m.launchState(c.id)?.phase == LaunchPhase.Running } // the launch thread's refresh() must finish before we mutate again
        assertEquals("admin", launcher.lastLogin?.username)
        assertEquals("synthetic-pw", launcher.lastLogin?.password?.let { String(it) })

        // A new password under another username replaces the old entry.
        assertNull(m.savePassword(c.id, "operator", "other-pw".toCharArray()))
        assertNull(creds.retrieve(c.id, "admin"))
        assertEquals("other-pw", String(creds.retrieve(c.id, "operator")!!))
        assertEquals("operator", m.store.get(c.id)!!.username)

        m.clearPassword(c.id)
        assertFalse(m.hasSavedPassword(c.id))
        assertNull(creds.retrieve(c.id, "operator"))
        assertEquals("operator", m.store.get(c.id)!!.username) { "the username stays" }
    }

    @Test
    @DisplayName("sign-in: renaming the username on Save moves the saved password with it; clearing the username drops it")
    fun passwordFollowsUsername(@TempDir dir: Path) {
        val creds = InMemoryCredentialStore()
        val m = model(dir, creds = creds)
        val c = m.newConnection(null)
        assertNull(m.savePassword(c.id, "admin", "synthetic-pw".toCharArray()))

        m.updateDraft { it.copy(username = "admin2") }
        assertTrue(m.dirty.get())
        assertTrue(m.save())
        assertNull(creds.retrieve(c.id, "admin"))
        assertEquals("synthetic-pw", String(creds.retrieve(c.id, "admin2")!!))
        assertTrue(m.hasSavedPassword(c.id))

        m.updateDraft { it.copy(username = null) }
        assertTrue(m.save())
        assertNull(creds.retrieve(c.id, "admin2"))
        assertFalse(m.hasSavedPassword(c.id))
    }
}
