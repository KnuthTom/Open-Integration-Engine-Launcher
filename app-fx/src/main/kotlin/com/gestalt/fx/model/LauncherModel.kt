/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.fx.model

import com.gestalt.launcher.audit.AuditLog
import com.gestalt.launcher.credential.CredentialStore
import com.gestalt.launcher.credential.CredentialStores
import com.gestalt.launcher.diag.Diagnosis
import com.gestalt.launcher.gate.GestaltTrust
import com.gestalt.launcher.gate.SignerPin
import com.gestalt.launcher.gate.TrustedSignerStore
import com.gestalt.launcher.launch.AutoLogin
import com.gestalt.launcher.launch.LaunchPhase
import com.gestalt.launcher.launch.LauncherCore
import com.gestalt.launcher.model.Connection
import com.gestalt.launcher.model.ConnectionStore
import com.gestalt.launcher.model.Folder
import com.gestalt.launcher.tls.TrustMigration
import com.gestalt.launcher.tls.TrustedCertStore
import javafx.beans.property.BooleanProperty
import javafx.beans.property.ObjectProperty
import javafx.beans.property.ReadOnlyBooleanProperty
import javafx.beans.property.ReadOnlyBooleanWrapper
import javafx.beans.property.SimpleBooleanProperty
import javafx.beans.property.SimpleObjectProperty
import javafx.beans.property.SimpleStringProperty
import javafx.beans.property.StringProperty
import javafx.collections.FXCollections
import javafx.collections.ObservableList
import javafx.collections.ObservableMap
import java.io.File
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** A connection's launch, as the sidebar and the Launch button show it. */
data class LaunchState(val phase: LaunchPhase, val instances: Int = 0, val message: String? = null) {
    /** Preparing: downloading / verifying / spawning. The Launch button waits and the row offers cancel. */
    val inFlight: Boolean get() = phase == LaunchPhase.Downloading || phase == LaunchPhase.Verifying || phase == LaunchPhase.Launching
}

/**
 * The launcher window's state: the connections and folders (a mirror of [ConnectionStore], refreshed
 * after every mutation), the sort / filter / selection the sidebar shows, the draft being edited in the
 * detail pane, and the launches in flight. Everything here is read and written on the UI thread; the
 * store is thread-safe, the disk write is debounced off-thread and flushed on close, and launch work
 * runs in [LaunchController].
 *
 * JavaFX properties are used for what the view binds to. They need no toolkit, so the model is
 * unit-tested headless with a direct `ui` executor.
 */
class LauncherModel(
    val store: ConnectionStore = ConnectionStore(),
    val prefs: LauncherPrefs = LauncherPrefs(),
    private val credentials: CredentialStore = CredentialStores.forCurrentOs(),
    val trustedCerts: TrustedCertStore = TrustedCertStore.shared,
    val trustedSigners: TrustedSignerStore = TrustedSignerStore.shared,
    private val ui: (Runnable) -> Unit = { javafx.application.Platform.runLater(it) },
    launcherFactory: ((LauncherModel) -> LauncherCore)? = null,
    private val now: () -> Instant = { Instant.now() },
) {
    // --- observable state ------------------------------------------------------------------------

    val connections: ObservableList<Connection> = FXCollections.observableArrayList()
    val folders: ObservableList<Folder> = FXCollections.observableArrayList()

    /** The sidebar rows for the current sort / filter / expansion state. */
    val rows: ObservableList<TreeRow> = FXCollections.observableArrayList()

    val sort: ObjectProperty<SortMode> = SimpleObjectProperty(prefs.sort)
    val foldersFirst: BooleanProperty = SimpleBooleanProperty(prefs.foldersFirst)
    val filter: StringProperty = SimpleStringProperty("")

    /** The connection shown in the detail pane; null when none is selected. */
    val activeId: StringProperty = SimpleStringProperty(null)

    /** The editable copy of the active connection; null when nothing is selected. */
    val draft: ObjectProperty<Connection?> = SimpleObjectProperty(null)

    private val dirtyWrapper = ReadOnlyBooleanWrapper(false)
    val dirty: ReadOnlyBooleanProperty get() = dirtyWrapper.readOnlyProperty

    val closeAfterLaunch: BooleanProperty = SimpleBooleanProperty(prefs.closeAfterLaunch)
    val dark: BooleanProperty = SimpleBooleanProperty(prefs.dark)

    val launches: ObservableMap<String, LaunchState> = FXCollections.observableHashMap()

    /** Set by the window: how to close it after a launch when the setting asks for that. */
    var onRequestClose: () -> Unit = {}

    /** Set by the window: a one-line notice (the toast). */
    var onNotice: (title: String, detail: String?, kind: NoticeKind) -> Unit = { _, _, _ -> }

    enum class NoticeKind { Info, Success, Error, Progress }

    // --- launches ---------------------------------------------------------------------------------

    /** What the launch controller reports; the window layers its prompts on these. */
    var launchListener: LaunchEvents? = null

    // Explicit types on these two: the events object refers back to the controller, and inference
    // otherwise chases its own tail ("type checking has run into a recursive problem").
    private val launchEvents: LaunchEvents = object : LaunchEvents {
        override fun onPhase(id: String, phase: LaunchPhase) {
            launches[id] = LaunchState(phase, instances = launchController.runningInstances(id))
            launchListener?.onPhase(id, phase)
        }

        override fun onRunning(id: String, instances: Int) {
            launches[id] = LaunchState(LaunchPhase.Running, instances)
            store.markLaunched(id, now(), persist = false)
            schedulePersist()
            refresh()
            launchListener?.onRunning(id, instances)
            if (closeAfterLaunch.get()) onRequestClose()
        }

        override fun onExited(id: String, instances: Int) {
            val current = launches[id]
            if (instances == 0) {
                if (current?.phase == LaunchPhase.Running) launches.remove(id)
            } else if (current?.phase == LaunchPhase.Running) {
                launches[id] = LaunchState(LaunchPhase.Running, instances)
            }
            launchListener?.onExited(id, instances)
        }

        override fun onCancelled(id: String) {
            if (launches[id]?.inFlight == true) launches.remove(id)
            launchListener?.onCancelled(id)
        }

        override fun onNeedsServerTrust(id: String, leaf: X509Certificate, hostPort: String?) {
            launches.remove(id)
            launchListener?.onNeedsServerTrust(id, leaf, hostPort)
        }

        override fun onServerRejected(id: String, leaf: X509Certificate) {
            launches.remove(id)
            launchListener?.onServerRejected(id, leaf)
        }

        override fun onNeedsPublisherTrust(id: String, signer: X509Certificate, artifactName: String, reason: String) {
            launches.remove(id)
            launchListener?.onNeedsPublisherTrust(id, signer, artifactName, reason)
        }

        override fun onFailed(id: String, error: Throwable, diagnosis: Diagnosis) {
            launches[id] = LaunchState(LaunchPhase.Failed, message = error.message ?: error.javaClass.simpleName)
            launchListener?.onFailed(id, error, diagnosis)
        }
    }

    private val makeLauncher: (LauncherModel) -> LauncherCore = launcherFactory ?: { m -> m.defaultLauncher() }
    val launchController: LaunchController = LaunchController({ makeLauncher(this) }, launchEvents, ui)

    /** The launcher for the current trust posture and the operator's extra pins / anchors. */
    fun defaultLauncher(): LauncherCore = LauncherCore(
        trustStore = GestaltTrust.productionTrustStore(customAnchorCerts()),
        signerPins = GestaltTrust.OFFICIAL_SIGNER_PINS + customSignerPins(),
        gateMode = prefs.trustPosture,
        trustedCerts = trustedCerts,
        trustedSigners = trustedSigners,
    )

    fun customSignerPins(): List<SignerPin> = prefs.customSignerDns.mapNotNull { parseSignerDn(it) }

    fun customAnchorCerts(): List<X509Certificate> = prefs.customAnchorPaths.flatMap { path ->
        runCatching {
            File(path).inputStream().use { input ->
                CertificateFactory.getInstance("X.509").generateCertificates(input).filterIsInstance<X509Certificate>()
            }
        }.getOrDefault(emptyList())
    }

    fun launchState(id: String): LaunchState? = launches[id]

    /** Launch [id] (the saved connection — unsaved draft edits don't take part; the UI saves first). */
    fun launch(id: String) {
        val conn = store.get(id) ?: return
        if (launches[id]?.inFlight == true) return
        launches[id] = LaunchState(LaunchPhase.Downloading)
        launchController.launch(conn, autoLoginFor(conn))
    }

    fun cancelLaunch(id: String) = launchController.cancel(id)

    /** The saved credential for [conn] as the Administrator's auto-login, read on the launch thread's behalf here (cheap: one keychain read). */
    internal fun autoLoginFor(conn: Connection): AutoLogin? {
        val user = conn.username?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val password = runCatching { credentials.retrieve(conn.id, user) }
            .onFailure { AuditLog.event("credential.error", "connection" to conn.id, "action" to "retrieve", "error" to (it.message ?: it.javaClass.simpleName)) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() } ?: return null
        return AutoLogin(user, password)
    }

    // --- trust decisions from the launch prompts ---------------------------------------------------

    fun trustServerCertificate(id: String, leaf: X509Certificate, hostPort: String?) {
        trustedCerts.trust(leaf, hostPort, id)
    }

    fun rejectServerCertificate(id: String, leaf: X509Certificate, hostPort: String?) {
        trustedCerts.reject(leaf, hostPort, id)
    }

    fun trustPublisher(id: String, signer: X509Certificate, artifactName: String) {
        trustedSigners.trust(signer, artifactName, id)
    }

    fun rejectPublisher(id: String, signer: X509Certificate, artifactName: String) {
        trustedSigners.reject(signer, artifactName, id)
    }

    // --- selection and the draft ------------------------------------------------------------------

    init {
        TrustMigration.migrateLegacyPins(store, trustedCerts)
        refresh()
        sort.addListener { _, _, v -> prefs.sort = v; rebuildRows() }
        foldersFirst.addListener { _, _, v -> prefs.foldersFirst = v; rebuildRows() }
        filter.addListener { _, _, _ -> rebuildRows() }
        closeAfterLaunch.addListener { _, _, v -> prefs.closeAfterLaunch = v }
        dark.addListener { _, _, v -> prefs.dark = v }
        draft.addListener { _, _, _ -> recomputeDirty() }
    }

    val active: Connection? get() = activeId.get()?.let { store.get(it) }

    /** Show [id] in the detail pane with a fresh draft (the caller has settled any unsaved edits). */
    fun select(id: String?) {
        val conn = id?.let { store.get(it) }
        activeId.set(conn?.id)
        draft.set(conn)
    }

    /** Edit the draft. */
    fun updateDraft(transform: (Connection) -> Connection) {
        val d = draft.get() ?: return
        draft.set(transform(d))
    }

    fun revert() {
        draft.set(active)
    }

    /** Persist the draft over the active connection. Returns false when there is nothing to save. */
    fun save(): Boolean {
        val d = draft.get() ?: return false
        if (store.get(d.id) == null) return false
        store.update(d, persist = false)
        schedulePersist()
        refresh()
        draft.set(store.get(d.id))
        return true
    }

    /** Save the draft as a new connection beside the active one and select it. Returns the new id. */
    fun saveAs(): String? {
        val d = draft.get() ?: return null
        val copy = d.copy(
            id = freshId(),
            name = d.name + " copy",
            sortOrder = d.sortOrder + 1,
            lastLaunchedAt = null,
            addedAt = null,
            username = null,
        )
        store.add(copy, persist = false)
        schedulePersist()
        refresh()
        select(copy.id)
        return copy.id
    }

    /** A new connection in [folderId] (null = top level), added at once and selected. */
    fun newConnection(folderId: String? = null): Connection {
        val siblings = store.list().filter { it.folderId == folderId }
        val conn = Connection(
            id = freshId(),
            name = "New connection",
            serverUrl = "https://",
            folderId = folderId,
            sortOrder = (siblings.maxOfOrNull { it.sortOrder } ?: -1) + 1,
            icon = DEFAULT_ICON,
        )
        store.add(conn, persist = false)
        folderId?.let { expandAncestors(it) }
        schedulePersist()
        refresh()
        select(conn.id)
        return store.get(conn.id)!!
    }

    fun duplicate(id: String): Connection? {
        val src = store.get(id) ?: return null
        val copy = src.copy(id = freshId(), name = src.name + " copy", sortOrder = src.sortOrder + 1, lastLaunchedAt = null, addedAt = null, username = null)
        store.add(copy, persist = false)
        schedulePersist()
        refresh()
        return store.get(copy.id)
    }

    fun deleteConnection(id: String) {
        val conn = store.get(id) ?: return
        store.delete(id, persist = false)
        conn.username?.takeIf { it.isNotBlank() }?.let { user -> runCatching { credentials.delete(id, user) } }
        trustedCerts.forgetConnection(id)
        launches.remove(id)
        if (activeId.get() == id) select(null)
        schedulePersist()
        refresh()
    }

    /**
     * Change a saved connection at once — the trust and identity switches (auto-approve, the icon) apply
     * without a Save. The draft gets the same change so unsaved edits elsewhere in the form survive.
     */
    fun applyToSaved(id: String, transform: (Connection) -> Connection) {
        val conn = store.get(id) ?: return
        store.update(transform(conn), persist = false)
        if (activeId.get() == id) draft.get()?.let { draft.set(transform(it)) }
        schedulePersist()
        refresh()
    }

    fun setAutoApprove(id: String, on: Boolean) = applyToSaved(id) { it.copy(autoApproveCertificates = on) }

    fun setIcon(id: String, key: String) = applyToSaved(id) { it.copy(icon = key) }

    fun rename(id: String, name: String) {
        val conn = store.get(id) ?: return
        val trimmed = name.trim().ifEmpty { return }
        store.update(conn.copy(name = trimmed), persist = false)
        if (activeId.get() == id) draft.set(draft.get()?.copy(name = trimmed))
        schedulePersist()
        refresh()
    }

    fun moveConnection(id: String, folderId: String?) {
        val conn = store.get(id) ?: return
        if (folderId != null && store.getFolder(folderId) == null) return
        store.update(conn.copy(folderId = folderId), persist = false)
        if (activeId.get() == id) draft.set(draft.get()?.copy(folderId = folderId))
        schedulePersist()
        refresh()
    }

    // --- drag-and-drop: move into a folder, or reorder before a sibling -----------------------------

    /** `f:<id>` → the folder's parent; `c:<id>` → the connection's folder. */
    private fun parentOf(key: String): String? = when {
        key.startsWith("c:") -> store.get(key.substring(2))?.folderId
        key.startsWith("f:") -> store.getFolder(key.substring(2))?.parentId
        else -> null
    }

    private fun exists(key: String): Boolean = when {
        key.startsWith("c:") -> store.get(key.substring(2)) != null
        key.startsWith("f:") -> store.getFolder(key.substring(2)) != null
        else -> false
    }

    /** True when [folderId] is [key]'s own folder or lies below it (a folder can't move into itself). */
    private fun wouldCycle(key: String, folderId: String?): Boolean {
        if (!key.startsWith("f:") || folderId == null) return false
        val id = key.substring(2)
        return folderId == id || folderId in store.descendantFolderIds(id)
    }

    /** Move the row [key] into [folderId] (null = top level), last among its new siblings. */
    fun moveInto(key: String, folderId: String?): Boolean {
        if (!exists(key) || wouldCycle(key, folderId)) return false
        if (folderId != null && store.getFolder(folderId) == null) return false
        val siblings = TreeRows.childKeys(folderId, connections, folders, sort.get(), foldersFirst.get()).filter { it != key }
        return reorder(siblings + key, folderId)
    }

    /**
     * Put the row [key] immediately before [beforeKey], among that row's siblings. A reorder is a
     * manual-order gesture, so the sort switches to Manual when it isn't already.
     */
    fun moveBefore(key: String, beforeKey: String): Boolean {
        if (key == beforeKey || !exists(key) || !exists(beforeKey)) return false
        val parent = parentOf(beforeKey)
        if (wouldCycle(key, parent)) return false
        val siblings = TreeRows.childKeys(parent, connections, folders, sort.get(), foldersFirst.get()).filter { it != key }.toMutableList()
        val idx = siblings.indexOf(beforeKey).takeIf { it >= 0 } ?: siblings.size
        siblings.add(idx, key)
        if (sort.get() != SortMode.Manual) sort.set(SortMode.Manual)
        return reorder(siblings, parent)
    }

    /** Put the row [key] immediately after [afterKey], among that row's siblings (manual order). */
    fun moveAfter(key: String, afterKey: String): Boolean {
        if (key == afterKey || !exists(key) || !exists(afterKey)) return false
        val parent = parentOf(afterKey)
        if (wouldCycle(key, parent)) return false
        val siblings = TreeRows.childKeys(parent, connections, folders, sort.get(), foldersFirst.get()).filter { it != key }.toMutableList()
        val idx = siblings.indexOf(afterKey).let { if (it >= 0) it + 1 else siblings.size }
        siblings.add(idx, key)
        if (sort.get() != SortMode.Manual) sort.set(SortMode.Manual)
        return reorder(siblings, parent)
    }

    /** The row's siblings in display order (folders and connections of its parent), as keys. */
    fun siblingKeys(key: String): List<String> =
        TreeRows.childKeys(parentOf(key), connections, folders, sort.get(), foldersFirst.get())

    /** Position of [key] among its siblings in display order, or -1. */
    fun siblingIndex(key: String): Int = siblingKeys(key).indexOf(key)

    /** Swap the row with the sibling above it (a manual-order gesture: the sort switches to Manual). */
    fun moveUp(key: String): Boolean {
        val siblings = siblingKeys(key)
        val i = siblings.indexOf(key)
        if (i <= 0) return false
        return moveBefore(key, siblings[i - 1])
    }

    /** Swap the row with the sibling below it. */
    fun moveDown(key: String): Boolean {
        val siblings = siblingKeys(key)
        val i = siblings.indexOf(key)
        if (i < 0 || i >= siblings.size - 1) return false
        return moveAfter(key, siblings[i + 1])
    }

    /** Assign [keys] their positions under [parent], moving each in as needed. All or nothing. */
    private fun reorder(keys: List<String>, parent: String?): Boolean {
        val before = store.list().associateBy { it.id } to store.folders().associateBy { it.id }
        try {
            keys.forEachIndexed { i, k ->
                if (k.startsWith("c:")) {
                    val c = store.get(k.substring(2)) ?: return@forEachIndexed
                    if (c.folderId != parent || c.sortOrder != i) store.update(c.copy(folderId = parent, sortOrder = i), persist = false)
                } else if (k.startsWith("f:")) {
                    val f = store.getFolder(k.substring(2)) ?: return@forEachIndexed
                    if (f.parentId != parent || f.sortOrder != i) store.updateFolder(f.copy(parentId = parent, sortOrder = i), persist = false)
                }
            }
        } catch (e: IllegalArgumentException) {
            // A refused move (cycle): put back what was changed so far.
            before.first.values.forEach { store.update(it, persist = false) }
            before.second.values.forEach { runCatching { store.updateFolder(it, persist = false) } }
            return false
        }
        activeId.get()?.let { id -> store.get(id)?.let { c -> draft.set(draft.get()?.copy(folderId = c.folderId, sortOrder = c.sortOrder)) } }
        if (parent != null) expandAncestors(parent)
        schedulePersist()
        refresh()
        return true
    }

    // --- folders ----------------------------------------------------------------------------------

    fun newFolder(parentId: String? = null, name: String = "New folder"): Folder {
        val siblings = store.folders().filter { it.parentId == parentId }
        val folder = Folder(id = freshId(), name = name, parentId = parentId, sortOrder = (siblings.maxOfOrNull { it.sortOrder } ?: -1) + 1, expanded = true)
        store.addFolder(folder, persist = false)
        parentId?.let { expandAncestors(it) }
        schedulePersist()
        refresh()
        return folder
    }

    fun renameFolder(id: String, name: String) {
        val folder = store.getFolder(id) ?: return
        val trimmed = name.trim().ifEmpty { return }
        store.updateFolder(folder.copy(name = trimmed), persist = false)
        schedulePersist()
        refresh()
    }

    fun deleteFolder(id: String) {
        store.deleteFolder(id, persist = false)
        schedulePersist()
        refresh()
    }

    fun setFolderExpanded(id: String, expanded: Boolean) {
        val folder = store.getFolder(id) ?: return
        if (folder.expanded == expanded) return
        store.updateFolder(folder.copy(expanded = expanded), persist = false)
        schedulePersist()
        refresh()
    }

    fun toggleFolder(id: String) {
        val folder = store.getFolder(id) ?: return
        setFolderExpanded(id, !folder.expanded)
    }

    fun setAllExpanded(expanded: Boolean) {
        store.folders().filter { it.expanded != expanded }.forEach { store.updateFolder(it.copy(expanded = expanded), persist = false) }
        schedulePersist()
        refresh()
    }

    /** True when any folder is open (the toolbar's expand-all / collapse-all toggle). */
    val anyFolderExpanded: Boolean get() = folders.any { it.expanded }

    private fun expandAncestors(folderId: String) {
        var cur = store.getFolder(folderId)
        var guard = 0
        while (cur != null && guard++ < 64) {
            if (!cur.expanded) store.updateFolder(cur.copy(expanded = true), persist = false)
            cur = cur.parentId?.let { store.getFolder(it) }
        }
    }

    /** Folder path for the header / folder menu. */
    fun pathOf(folderId: String?): String = TreeRows.pathOf(folderId, folders)

    /** Connections anywhere below [folderId]. */
    fun connectionsUnder(folderId: String): List<Connection> = TreeRows.descendantConnections(folderId, connections, folders)

    // --- mirror + persistence ---------------------------------------------------------------------

    /** Re-read the store into the observable lists and rebuild the rows. */
    fun refresh() {
        connections.setAll(store.list())
        folders.setAll(store.folders())
        rebuildRows()
        recomputeDirty()
    }

    private fun rebuildRows() {
        rows.setAll(TreeRows.build(connections, folders, sort.get(), foldersFirst.get(), filter.get().orEmpty()))
    }

    private fun recomputeDirty() {
        val d = draft.get()
        dirtyWrapper.set(d != null && d != store.get(d.id))
    }

    private val persistExecutor = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "persist").apply { isDaemon = true } }
    private var pendingSave: ScheduledFuture<*>? = null
    private val persistLock = Any()

    /** Debounced save: many quick edits become one write; [flushPendingSave] forces it. */
    private fun schedulePersist() {
        synchronized(persistLock) {
            pendingSave?.cancel(false)
            pendingSave = persistExecutor.schedule({ runCatching { store.save() } }, 300, TimeUnit.MILLISECONDS)
        }
    }

    fun flushPendingSave() {
        val pending = synchronized(persistLock) { pendingSave.also { pendingSave = null } }
        if (pending != null && pending.cancel(false)) runCatching { store.save() }
        else pending?.let { runCatching { it.get(5, TimeUnit.SECONDS) } }
    }

    /** Flush edits and stop background work; the window closes after this. */
    fun shutdown() {
        flushPendingSave()
        launchController.shutdown()
        persistExecutor.shutdownNow()
    }

    private fun freshId(): String {
        var id = UUID.randomUUID().toString()
        while (store.get(id) != null || store.getFolder(id) != null) id = UUID.randomUUID().toString()
        return id
    }

    companion object {
        /** The icon a new connection starts with (a Lucide glyph name — the design's `server`). */
        const val DEFAULT_ICON = "server"

        /** Parse an RFC-2253-ish DN ("CN=…, O=…") into a [SignerPin] of DN-attribute requirements. */
        fun parseSignerDn(dn: String): SignerPin? {
            val rdns = dn.split(',')
                .mapNotNull { part ->
                    val eq = part.indexOf('=')
                    if (eq <= 0) return@mapNotNull null
                    val k = part.substring(0, eq).trim()
                    val v = part.substring(eq + 1).trim()
                    if (k.isEmpty() || v.isEmpty()) null else k.uppercase() to v
                }
                .toMap()
            return if (rdns.isEmpty()) null else SignerPin("custom: $dn", rdns)
        }
    }
}
