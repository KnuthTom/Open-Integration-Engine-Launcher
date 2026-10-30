/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import com.gestalt.app.diagnose.Diagnosis
import com.gestalt.app.diagnose.FailureDiagnosis
import com.gestalt.app.trust.CertDetails
import com.gestalt.design.theme.Density
import com.gestalt.design.theme.Mode
import com.gestalt.design.theme.Skin
import com.gestalt.launcher.AppPaths
import com.gestalt.launcher.audit.AuditLog
import com.gestalt.launcher.credential.CredentialStore
import com.gestalt.launcher.diag.ConnectionDiagnostic
import com.gestalt.launcher.diag.ConnectionTestReport
import com.gestalt.launcher.diag.DiagnosticsLog
import com.gestalt.launcher.gate.GestaltTrust
import com.gestalt.launcher.gate.SignerPin
import com.gestalt.launcher.icon.CustomIcon
import com.gestalt.launcher.icon.CustomIconStore
import com.gestalt.launcher.importer.ConnectionImport
import com.gestalt.launcher.launch.LaunchPhase
import com.gestalt.launcher.launch.LauncherCore
import com.gestalt.launcher.model.CloseAfterLaunch
import com.gestalt.launcher.model.Connection
import com.gestalt.launcher.model.ConnectionStore
import com.gestalt.launcher.model.JavaHomeMode
import com.gestalt.launcher.tls.ServerCertPinning
import com.gestalt.launcher.tls.ServerCertProbe
import com.gestalt.launcher.tls.ServerTrustStore
import com.gestalt.launcher.update.PortableSwap
import com.gestalt.launcher.update.UpdateOutcome
import com.gestalt.launcher.update.UpdateService
import com.gestalt.trust.TrustMode
import com.gestalt.trust.TrustStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.awt.Desktop
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.zip.ZipInputStream

/** Which screen is showing. [Edit] with a null id is "new connection". */
sealed interface Route {
    data object List : Route
    data class Edit(val connectionId: String?) : Route
    data object Trust : Route
    data object Settings : Route
}

/** List sort order. Manual is the drag-reorder order ([Connection.sortOrder]). */
enum class SortMode { Manual, Name, Group, Status }

/**
 * Coarse launch state for one connection, surfaced in its row. Carries a [Diagnosis] once it fails.
 * While [LaunchPhase.Running], [instances] is the number of Administrator processes this launcher
 * started for the connection that are still alive (a connection may be opened more than once).
 */
data class LaunchView(
    val phase: LaunchPhase,
    val error: String? = null,
    val diagnosis: Diagnosis? = null,
    val instances: Int = 0,
) {
    /** True while a launch is being prepared (download / verify / spawn) and can still be cancelled. */
    val inFlight: Boolean get() = phase == LaunchPhase.Downloading || phase == LaunchPhase.Verifying || phase == LaunchPhase.Launching
}

/** Best-effort environment hint inferred from a connection's name/group. */
enum class EnvCue { Prod, Staging, Test, Dev }

/** UI state for the self-update flow (Settings → Updates). */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Unavailable(val reason: String) : UpdateState
    data class Failed(val reason: String) : UpdateState
    data class Available(val outcome: UpdateOutcome.Available) : UpdateState
    data object Applying : UpdateState
}

/** Defaults applied to a *new* connection's Java runtime + heap (from global settings). */
data class LaunchDefaults(
    val javaHomeMode: JavaHomeMode,
    val bundledVersion: String,
    val customJavaHome: String,
    val heap: String,
)

/** The trust material a launch runs under, derived from the current posture + custom pins/anchors. */
data class LauncherConfig(
    val mode: TrustMode,
    val signerPins: List<SignerPin>,
    val trustStore: TrustStore,
)

/** A modal shown over the shell. All are transient UI state; none persists. */
sealed interface AppDialog {
    /** Human-readable explanation of a launch failure + safe fixes. */
    data class Diagnose(val connectionId: String, val diagnosis: Diagnosis) : AppDialog

    /** Trust-on-first-connect: review a freshly captured server cert and explicitly pin it. */
    data class TrustPin(
        val connectionId: String,
        val connectionName: String,
        val cert: X509Certificate,
        val details: CertDetails,
        /** The fingerprint currently pinned (if any) that this would replace. */
        val replacesPin: String?,
    ) : AppDialog

    /** Read-only view of a connection's live server cert (with pin-match / expiry flags). */
    data class CertView(
        val connectionId: String,
        val connectionName: String,
        val details: CertDetails,
        /** null = not pinned; true/false = live cert matches the stored pin. */
        val pinMatches: Boolean?,
    ) : AppDialog

    /**
     * The staged Test-connection report (DNS/TCP → TLS → JNLP → digest). [connectionId] is the saved
     * connection so an untrusted result can route to the managed trust-pin flow; null for an unsaved
     * draft tested from the form (no pin target yet).
     */
    data class TestResult(
        val connectionId: String?,
        val report: ConnectionTestReport,
    ) : AppDialog
}

/**
 * The product app's single state holder. Binds the [ConnectionStore], drives list sorting/search,
 * per-connection reachability and launch state, owns the trust/settings layer, and persists UI
 * preferences. All store mutations go through here so the in-memory list and the on-disk file stay in
 * sync.
 *
 * Trust is a thin layer **over** `:core-trust` / `:launcher-core` — pinning goes through
 * [ServerCertPinning], probing through [ServerCertProbe], and the code-signing posture flows into the
 * launch by building the [LauncherCore] via [launcherFactory] with the current [LauncherConfig]. None
 * of the core verification logic is changed or bypassed here.
 */
class AppState(
    val store: ConnectionStore,
    val prefs: AppPreferences,
    private val credentials: CredentialStore,
    private val scope: CoroutineScope,
    private val onRequestClose: () -> Unit = {},
    private val launcherFactory: (LauncherConfig) -> LauncherCore = { c ->
        LauncherCore(trustStore = c.trustStore, signerPins = c.signerPins, gateMode = c.mode)
    },
    private val updateServiceFactory: () -> UpdateService = {
        UpdateService(BuildInfo.version, Platform.osArch(), AppPaths.isPortable)
    },
    private val iconStore: CustomIconStore = CustomIconStore(),
) {
    var skin by mutableStateOf(prefs.skin)
        private set
    var mode by mutableStateOf(prefs.mode)
        private set
    var density by mutableStateOf(prefs.density)
        private set
    var closeAfterLaunch by mutableStateOf(prefs.closeAfterLaunch)
        private set
    var trustPosture by mutableStateOf(prefs.trustPosture)
        private set

    /** Dedupe policy applied to the next imported file (surfaced as a sidebar tool). */
    var importPolicy by mutableStateOf(ConnectionImport.DedupePolicy.Merge)
        private set
    fun updateImportPolicy(value: ConnectionImport.DedupePolicy) { importPolicy = value }

    private var routeState by mutableStateOf<Route>(Route.List)

    /**
     * The active screen. Leaving the list clears the keyboard row cursor ([focusedId]) so a cursor left
     * over from earlier navigation can't make the global Enter/Space launch keys shadow button activation
     * (those keys are gated on a live cursor).
     */
    var route: Route
        get() = routeState
        set(value) {
            if (value !is Route.List) focusedId = null
            routeState = value
        }

    var search by mutableStateOf("")
    var sort by mutableStateOf(SortMode.Manual)
    var banner by mutableStateOf<String?>(null)

    /** The currently open modal, if any (diagnosis / trust-pin / cert-view). */
    var dialog by mutableStateOf<AppDialog?>(null)

    // --- keyboard-first interaction ---------------------------------------------------------------

    /** Whether the keyboard-shortcuts overlay ("?") is showing. */
    var showShortcuts by mutableStateOf(false)
        private set

    /** Bumped to ask the list's search field to take focus (Ctrl/Cmd-K or -F). */
    var searchFocusSignal by mutableStateOf(0)
        private set

    fun focusSearch() { searchFocusSignal++ }
    fun toggleShortcuts() { showShortcuts = !showShortcuts }

    /**
     * Esc handling, in priority order: close a modal, else the shortcuts overlay, else clear a
     * multi-select, else leave the form. Returns true when it consumed the key so the global handler
     * knows to stop.
     */
    fun onEscape(): Boolean = when {
        dialog != null -> { dialog = null; true }
        showShortcuts -> { showShortcuts = false; true }
        selection.isNotEmpty() -> { clearSelection(); true }
        route is Route.Edit -> { route = Route.List; true }
        else -> false
    }

    // --- multi-select + keyboard row navigation ---------------------------------------------------

    /** Ids checked for a bulk action (delete / group / export). A list used as a set (no duplicates). */
    val selection: SnapshotStateList<String> = mutableStateListOf()

    /** The keyboard "cursor" row: Up/Down move it, Enter launches it, Space toggles its selection. */
    var focusedId by mutableStateOf<String?>(null)
        private set

    /** True while the list's search field holds focus, so row-nav keys don't fire while typing. */
    var searchFocused by mutableStateOf(false)

    val selectionCount: Int get() = selection.size
    fun isSelected(id: String): Boolean = id in selection
    fun toggleSelected(id: String) { if (!selection.remove(id)) selection.add(id) }
    fun clearSelection() { selection.clear() }
    fun setFocused(id: String?) { focusedId = id }

    /** Are all currently-visible (filtered) rows selected? Drives the select-all checkbox. */
    fun allVisibleSelected(): Boolean {
        val vis = visibleConnections()
        return vis.isNotEmpty() && vis.all { it.id in selection }
    }

    /** Select-all / clear toggle, scoped to the visible (filtered) rows. */
    fun toggleSelectAllVisible() {
        val vis = visibleConnections()
        if (allVisibleSelected()) vis.forEach { selection.remove(it.id) }
        else vis.forEach { if (it.id !in selection) selection.add(it.id) }
    }

    /** Move the cursor through the visible rows (clamped); from no cursor, jump to the first/last. */
    fun moveFocus(delta: Int) {
        val vis = visibleConnections()
        if (vis.isEmpty()) { focusedId = null; return }
        val idx = vis.indexOfFirst { it.id == focusedId }
        focusedId = when {
            idx < 0 -> vis[if (delta > 0) 0 else vis.lastIndex].id
            else -> vis[(idx + delta).coerceIn(0, vis.lastIndex)].id
        }
    }

    /** Launch the cursor row (Enter). No-op when no row is focused. */
    fun launchFocused() { focusedId?.let { launch(it) } }

    /** Toggle the cursor row's selection (Space). */
    fun toggleFocusedSelection() { focusedId?.let { toggleSelected(it) } }

    /** Existing non-blank group names, for the "Group…" picker. */
    fun existingGroups(): List<String> =
        connections.mapNotNull { it.group?.takeIf { g -> g.isNotBlank() } }.distinct().sorted()

    /** Delete every selected connection (and any keychain association), then clear the selection. */
    fun deleteSelected() {
        val ids = selection.toList()
        if (ids.isEmpty()) return
        ids.forEach { id ->
            val conn = store.get(id)
            store.delete(id, persist = false)
            if (conn?.username != null) runCatching { credentials.delete(id, conn.username!!) }
            purgeConnectionState(id)
        }
        if (focusedId in ids) focusedId = null
        selection.clear()
        reload()
        schedulePersist()
        banner = "Deleted ${ids.size} connection(s)."
    }

    /** Set (or clear, when [group] is null/blank) the group on every selected connection. */
    fun groupSelected(group: String?) {
        val ids = selection.toList()
        if (ids.isEmpty()) return
        val g = group?.trim()?.takeIf { it.isNotBlank() }
        ids.forEach { id -> store.get(id)?.let { store.update(it.copy(group = g), persist = false) } }
        reload()
        schedulePersist()
        banner = if (g == null) "Cleared the group on ${ids.size} connection(s)." else "Moved ${ids.size} connection(s) to “$g”."
    }

    /** Synchronous subset export (the test seam); the UI uses [exportSelectedAsync]. */
    fun exportSelected(path: Path) {
        val ids = selection.toSet()
        if (ids.isEmpty()) return
        store.exportTo(path, ids)
        banner = "Exported ${ids.size} selected connection(s) to ${path.fileName}."
    }

    /** Export the selected connections off the UI thread. */
    fun exportSelectedAsync(path: Path) {
        if (selection.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            runCatching { exportSelected(path) }.onFailure { banner = "Export failed: ${it.message}" }
        }
    }

    /** Bumped whenever the custom-trust lists change, to recompose the trust screen. */
    var trustNonce by mutableStateOf(0)
        private set

    /** Bumped to force the connection form to reset even when the route doesn't structurally change. */
    var formNonce by mutableStateOf(0)
        private set

    /** Open a fresh "new connection" form, resetting any in-progress new-form fields. */
    fun startNewConnection() {
        formNonce++
        route = Route.Edit(null)
    }

    val connections: SnapshotStateList<Connection> = store.list().sortedBy { it.sortOrder }.toMutableStateList()
    val reachability = mutableStateMapOf<String, Reachability>()
    val launches = mutableStateMapOf<String, LaunchView>()

    /** User-uploaded custom icons, mirrored from [iconStore] for the picker / manage view. */
    val customIcons: SnapshotStateList<CustomIcon> = iconStore.list().toMutableStateList()

    /** Bumped on any custom-icon change so icon renders and the picker recompose. */
    var customIconNonce by mutableStateOf(0)
        private set

    // Concurrent: read during composition (UI thread), evicted from add/delete (may be the IO thread).
    private val iconBytesCache = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

    init {
        maybeFirstRunImport()
    }

    // --- theme + prefs ----------------------------------------------------------------------------

    fun updateSkin(value: Skin) { skin = value; prefs.skin = value }
    fun updateMode(value: Mode) { mode = value; prefs.mode = value }
    fun updateDensity(value: Density) { density = value; prefs.density = value }
    fun toggleMode() = updateMode(if (mode == Mode.Light) Mode.Dark else Mode.Light)
    fun updateCloseAfterLaunch(value: Boolean) { closeAfterLaunch = value; prefs.closeAfterLaunch = value }

    /**
     * Whether the launcher should close once THIS connection launches successfully: the per-connection
     * override ([CloseAfterLaunch.On]/[Off]) wins; [CloseAfterLaunch.Inherit] follows the global toggle.
     */
    internal fun resolveCloseAfterLaunch(conn: Connection): Boolean = when (conn.closeAfterLaunch) {
        CloseAfterLaunch.On -> true
        CloseAfterLaunch.Off -> false
        CloseAfterLaunch.Inherit -> closeAfterLaunch
    }

    fun launchDefaults(): LaunchDefaults = LaunchDefaults(
        prefs.defaultJavaHomeMode, prefs.defaultBundledVersion, prefs.defaultCustomJavaHome, prefs.defaultHeap,
    )

    // --- list / store -----------------------------------------------------------------------------

    private fun reload() {
        connections.clear()
        connections.addAll(store.list().sortedBy { it.sortOrder })
    }

    fun get(id: String): Connection? = store.get(id)

    /** The connections to render: filtered by [search] and ordered by [sort]. */
    fun visibleConnections(): List<Connection> {
        val q = search.trim().lowercase()
        val filtered = if (q.isEmpty()) connections.toList() else connections.filter {
            it.name.lowercase().contains(q) ||
                it.serverUrl.lowercase().contains(q) ||
                (it.group?.lowercase()?.contains(q) ?: false)
        }
        return when (sort) {
            SortMode.Manual -> filtered.sortedBy { it.sortOrder }
            SortMode.Name -> filtered.sortedBy { it.name.lowercase() }
            // Order matches GroupedLazyList exactly — by the same group label (including the literal
            // "Ungrouped"), case-sensitive like its toSortedMap, then by name — so the keyboard cursor
            // walks rows in the on-screen order rather than a divergent flat order.
            SortMode.Group -> filtered.sortedWith(
                compareBy({ it.group?.takeIf { g -> g.isNotBlank() } ?: "Ungrouped" }, { it.name.lowercase() }),
            )
            SortMode.Status -> filtered.sortedWith(compareBy({ statusRank(it.id) }, { it.name.lowercase() }))
        }
    }

    private fun statusRank(id: String): Int = when (reachability[id]) {
        Reachability.Ok -> 0
        Reachability.Checking -> 1
        Reachability.Unreachable -> 2
        else -> 3
    }

    /**
     * Persist a new manual order from a drag-reorder. [visibleOrderedIds] may be a filtered subset; the
     * reordered ids are spliced back into the exact slots they occupied in the full manual order, so any
     * filtered-out connections keep their relative positions and no sortOrder is lost or duplicated.
     */
    fun commitOrder(visibleOrderedIds: List<String>) {
        val full = connections.sortedBy { it.sortOrder }.map { it.id }.toMutableList()
        val visibleSet = visibleOrderedIds.toSet()
        val slots = full.indices.filter { full[it] in visibleSet }
        slots.forEachIndexed { i, slot -> if (i < visibleOrderedIds.size) full[slot] = visibleOrderedIds[i] }
        store.reorder(full, persist = false)
        reload()
        schedulePersist()
    }

    // The in-memory model updates synchronously (fast); the connections.json write is moved off the UI
    // thread and debounced so rapid edits coalesce into one write and the UI never blocks on disk.
    //
    // The write itself is NOT cancellable: `scope` is the window's composition scope and is cancelled
    // when the window closes, which used to cancel a pending save with it — edit, close within 150 ms,
    // and the edit never reached disk (delete was worse: the keychain entry and truststore were already
    // purged synchronously, so the connection came back with a dead credential link). Now a `dirty`
    // flag records that disk is behind the model, the save runs under NonCancellable once the debounce
    // elapses, and [flushPendingSave] writes synchronously before any close.
    private var persistJob: Job? = null
    private val persistLock = Any()
    @Volatile private var dirty = false

    private fun schedulePersist() {
        dirty = true
        persistJob?.cancel()
        persistJob = scope.launch(Dispatchers.IO) {
            delay(150) // coalesce a burst of edits into a single write
            withContext(NonCancellable) { flushNow() }
        }
    }

    /**
     * Write any change that hasn't reached `connections.json` yet, synchronously, on the calling thread.
     * No-op when disk is already current. Called before the window closes (both the close button and
     * close-after-launch) so no edit is lost to a cancelled debounce; safe to call from any thread.
     */
    fun flushPendingSave() {
        persistJob?.cancel()
        flushNow()
    }

    private fun flushNow() {
        synchronized(persistLock) {
            if (!dirty) return
            dirty = false
            runCatching { store.save() }.onFailure {
                dirty = true
                banner = "Couldn't save connections: ${it.message}"
            }
        }
    }

    /** Flush pending edits, then ask the host to close the window. */
    fun requestClose() {
        runCatching { flushPendingSave() }
        onRequestClose()
    }

    /** Insert or update a connection; if [password] is non-empty it is stored in the keychain only. */
    fun upsert(connection: Connection, password: CharArray? = null): String {
        val isNew = store.get(connection.id) == null
        val saved = if (isNew) store.add(connection, persist = false) else store.update(connection, persist = false)
        if (password != null && password.isNotEmpty() && !connection.username.isNullOrBlank()) {
            runCatching { credentials.store(saved.id, connection.username!!, password) }
            password.fill(' ')
        }
        reload()
        schedulePersist()
        return saved.id
    }

    fun delete(id: String) {
        val conn = store.get(id)
        store.delete(id, persist = false)
        if (conn?.username != null) runCatching { credentials.delete(id, conn.username!!) }
        selection.remove(id)
        if (focusedId == id) focusedId = null
        purgeConnectionState(id)
        reload()
        schedulePersist()
    }

    /**
     * Drop every per-connection artifact when a connection is removed, so nothing accumulates:
     *  - in-memory status maps (reachability / launch view / in-flight test / probe-freshness) — synchronous;
     *  - the per-connection PKCS12 truststore and the downloaded-jar cache subtree — off the UI thread.
     * (The icon-bytes cache is keyed by icon id, not connection id, and may be shared, so it is NOT touched
     * here — it is pruned when the icon itself is deleted.)
     */
    private fun purgeConnectionState(id: String) {
        reachability.remove(id)
        launches.remove(id)
        testing.remove(id)
        lastProbed.remove(id)
        scope.launch(Dispatchers.IO) {
            runCatching { ServerTrustStore.delete(id) }
            runCatching { LauncherCore.purgeConnectionCache(id) }
        }
    }

    fun duplicate(connection: Connection) {
        val maxOrder = connections.maxOfOrNull { it.sortOrder } ?: -1
        store.add(
            connection.copy(
                id = UUID.randomUUID().toString(),
                name = "${connection.name} (copy)",
                username = null, // never copy a credential association
                sortOrder = maxOrder + 1,
            ),
            persist = false,
        )
        reload()
        schedulePersist()
    }

    fun newId(): String = UUID.randomUUID().toString()

    fun nextSortOrder(): Int = (connections.maxOfOrNull { it.sortOrder } ?: -1) + 1

    // --- import / export --------------------------------------------------------------------------

    fun import(path: Path, policy: ConnectionImport.DedupePolicy): ConnectionImport.ImportSummary {
        val summary = ConnectionImport.importFile(store, path, policy)
        reload()
        banner = "Imported ${summary.added} new, merged ${summary.merged}, " +
            "replaced ${summary.replaced}, skipped ${summary.skipped} (${summary.format})."
        return summary
    }

    fun export(path: Path) {
        store.exportTo(path)
        banner = "Exported ${connections.size} connection(s) to ${path.fileName}."
    }

    /** Import off the UI thread (the parse + secret-scanned write can be non-trivial on a slow disk). */
    fun importAsync(path: Path, policy: ConnectionImport.DedupePolicy) {
        scope.launch(Dispatchers.IO) {
            runCatching { import(path, policy) }.onFailure { banner = "Import failed: ${it.message}" }
        }
    }

    /** Export off the UI thread. */
    fun exportAsync(path: Path) {
        scope.launch(Dispatchers.IO) {
            runCatching { export(path) }.onFailure { banner = "Export failed: ${it.message}" }
        }
    }

    private fun maybeFirstRunImport() {
        if (connections.isNotEmpty()) return
        val pointer = System.getProperty("gestalt.legacy.connections")
            ?: System.getenv("GESTALT_LEGACY_CONNECTIONS")
            ?: return
        val path = runCatching { Path.of(pointer) }.getOrNull() ?: return
        if (!Files.isRegularFile(path)) return
        runCatching { import(path, ConnectionImport.DedupePolicy.Skip) }
            .onFailure { banner = "First-run import failed: ${it.message}" }
    }

    // --- custom connection icons ------------------------------------------------------------------

    fun customIcon(id: String): CustomIcon? = customIcons.firstOrNull { it.id == id }

    /** Bytes for a custom icon, cached so the painter isn't re-read from disk on every recomposition. */
    fun customIconBytes(id: String): ByteArray? {
        iconBytesCache[id]?.let { return it }
        val bytes = runCatching { iconStore.bytes(id) }.getOrNull() ?: return null
        iconBytesCache[id] = bytes
        return bytes
    }

    /** Sanitize + store a chosen image off the UI thread, then refresh the picker (fail-closed). */
    fun addCustomIcon(file: File) {
        scope.launch(Dispatchers.IO) {
            runCatching { addCustomIconNow(file.name, Files.readAllBytes(file.toPath())) }
                .onSuccess { banner = "Added custom icon “${it.label}”." }
                .onFailure { banner = "Couldn't add that icon: ${it.message}" }
        }
    }

    /** Synchronous core of [addCustomIcon] (also the test seam). */
    internal fun addCustomIconNow(sourceName: String, bytes: ByteArray): CustomIcon {
        val icon = iconStore.add(sourceName, bytes)
        iconBytesCache.remove(icon.id)
        refreshCustomIcons()
        return icon
    }

    fun renameCustomIcon(id: String, label: String) {
        iconStore.rename(id, label)
        refreshCustomIcons()
    }

    fun deleteCustomIcon(id: String) {
        iconStore.delete(id)
        iconBytesCache.remove(id)
        refreshCustomIcons()
    }

    private fun refreshCustomIcons() {
        customIcons.clear()
        customIcons.addAll(iconStore.list())
        customIconNonce++
    }

    fun openIconsFolder() = openFolder(AppPaths.iconsDir)

    // --- reachability -----------------------------------------------------------------------------

    // Reachability is cached with a TTL so dots are never re-probed per recomposition/scroll, and an
    // auto-refresh skips hosts probed within the window. Per-host timeout lives in ReachabilityProbe.
    private val lastProbed = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val reachabilityTtlMs = 30_000L

    private fun isFresh(id: String): Boolean =
        (System.currentTimeMillis() - (lastProbed[id] ?: 0L)) < reachabilityTtlMs

    /** Probe one host. An explicit user click [force]s a fresh probe; an auto-refresh skips fresh hosts. */
    fun probe(id: String, force: Boolean = true) {
        val conn = store.get(id) ?: return
        if (!force && isFresh(id)) return
        reachability[id] = Reachability.Checking
        scope.launch(Dispatchers.IO) {
            val ok = ReachabilityProbe.isReachable(conn)
            lastProbed[id] = System.currentTimeMillis()
            reachability[id] = if (ok) Reachability.Ok else Reachability.Unreachable
        }
    }

    /** Probe the whole fleet with capped concurrency. [force]=false skips hosts probed within the TTL. */
    fun probeAll(force: Boolean = true) {
        val ids = connections.map { it.id }.filter { force || !isFresh(it) }
        if (ids.isEmpty()) return
        ids.forEach { reachability[it] = Reachability.Checking }
        scope.launch(Dispatchers.IO) {
            val gate = Semaphore(8) // cap concurrency over the full fleet
            ids.forEach { id ->
                val conn = store.get(id) ?: return@forEach
                launch {
                    gate.withPermit {
                        val ok = ReachabilityProbe.isReachable(conn)
                        lastProbed[id] = System.currentTimeMillis()
                        reachability[id] = if (ok) Reachability.Ok else Reachability.Unreachable
                    }
                }
            }
        }
    }

    /** Auto-refresh only stale/unknown hosts — called (debounced) when the connection list appears. */
    fun refreshStaleReachability() = probeAll(force = false)

    // --- launch -----------------------------------------------------------------------------------

    /**
     * The trust material the next launch will use. Built from the current posture plus any operator
     * custom signer DNs / CA anchors. Exposed (internal) so a test can assert the posture toggle flows
     * into the config the launcher is built from.
     */
    internal fun currentLauncherConfig(): LauncherConfig {
        val pins = GestaltTrust.OFFICIAL_SIGNER_PINS + customSignerPins()
        val trustStore = GestaltTrust.productionTrustStore(customAnchorCerts())
        return LauncherConfig(trustPosture, pins, trustStore)
    }

    // The in-flight launch job per connection (cancellable) and the Administrator processes this launcher
    // started per connection that are still alive. Both are touched from IO threads and process-exit
    // callbacks, hence concurrent collections; the Compose-visible state is `launches`.
    private val launchJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private val runningProcesses = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.CopyOnWriteArrayList<Process>>()

    /** Live Administrator processes started for [id] (for tests and a future "running instances" view). */
    fun runningProcesses(id: String): List<Process> = runningProcesses[id]?.toList() ?: emptyList()

    fun launch(id: String) {
        val conn = store.get(id) ?: return
        // Idempotency: ignore a launch while one for this connection is still being PREPARED, so held /
        // auto-repeated Enter, a fast double-click, or repeated Play clicks can't spawn duplicate downloads.
        // A connection that is already RUNNING may be launched again — opening the same server twice is a
        // normal thing to do — and a failed/cleared launch can be retried normally.
        if (launches[id]?.inFlight == true) return
        launches[id] = LaunchView(LaunchPhase.Downloading)
        val launcher = launcherFactory(currentLauncherConfig())
        val job = scope.launch(Dispatchers.IO) {
            try {
                // runInterruptible: a cancel() interrupts this thread, and LauncherCore checks the flag
                // between fetches, so the download stops at the next boundary and nothing is spawned.
                val process = runInterruptible {
                    launcher.launch(conn) { phase -> if (phase != LaunchPhase.Running) launches[id] = LaunchView(phase) }
                }
                trackRunning(id, process)
                // Close on the UI thread via the composition scope. (Dispatchers.Main isn't installed on
                // this desktop target — kotlinx-coroutines-swing isn't a dependency — so using it here threw
                // and was swallowed by the catch below as a "launch failure", which is why close-after-launch
                // silently did nothing.) The per-connection override resolves the global setting; either way
                // this runs ONLY on the success path — a failed launch falls into the catch and never closes.
                if (resolveCloseAfterLaunch(conn)) scope.launch { requestClose() }
            } catch (e: CancellationException) {
                // User cancelled: back to idle, no diagnosis, no dialog. (Nothing was spawned.)
                if (launches[id]?.inFlight == true) launches.remove(id)
                throw e
            } catch (t: Throwable) {
                val diagnosis = FailureDiagnosis.classify(t, conn)
                launches[id] = LaunchView(LaunchPhase.Failed, t.message ?: t.javaClass.simpleName, diagnosis)
                dialog = AppDialog.Diagnose(id, diagnosis)
            } finally {
                launchJobs.remove(id)
            }
        }
        launchJobs[id] = job
    }

    /** Cancel a launch that is still being prepared (download / verify). No-op once the child is running. */
    fun cancelLaunch(id: String) {
        launchJobs[id]?.cancel()
    }

    /**
     * Record a freshly spawned Administrator and watch it: when it exits the row goes back to idle (or
     * shows the remaining instance count), so "Running" is a fact, not a sticky label. The watcher is a
     * process-exit callback, independent of the (cancellable) launch job and of the window's scope.
     */
    private fun trackRunning(id: String, process: Process) {
        val list = runningProcesses.getOrPut(id) { java.util.concurrent.CopyOnWriteArrayList() }
        list.add(process)
        launches[id] = LaunchView(LaunchPhase.Running, instances = list.size)
        process.onExit().thenRun { onChildExited(id, process) }
    }

    private fun onChildExited(id: String, process: Process) {
        val list = runningProcesses[id] ?: return
        list.remove(process)
        if (list.isEmpty()) {
            runningProcesses.remove(id, list)
            // Clear only a Running view: a newer launch already being prepared for this connection, or a
            // failure recorded meanwhile, must not be clobbered by an old child's exit.
            if (launches[id]?.phase == LaunchPhase.Running) launches.remove(id)
        } else if (launches[id]?.phase == LaunchPhase.Running) {
            launches[id] = LaunchView(LaunchPhase.Running, instances = list.size)
        }
    }

    /** Drop a Failed (or stale) row state so the row returns to idle. */
    fun clearLaunch(id: String) { launches.remove(id) }

    /** Re-open the diagnosis for a previously failed launch (e.g. from its row). */
    fun showDiagnosis(id: String) {
        launches[id]?.diagnosis?.let { dialog = AppDialog.Diagnose(id, it) }
    }

    fun dismissDialog() { dialog = null }

    // --- test connection (diagnostic) -------------------------------------------------------------

    /** Connection ids whose Test-connection run is in flight (the row/form shows a busy state). */
    val testing = mutableStateMapOf<String, Boolean>()

    /** Run the staged diagnostic for a saved connection and open the result dialog. */
    fun testConnection(id: String) {
        val conn = store.get(id) ?: return
        runDiagnostic(id, conn)
    }

    /**
     * Run the diagnostic against an unsaved/edited draft from the form (test before save). The result's
     * trust action only applies when the draft tests the SAME target as an already-saved connection — if
     * the form's URL was edited (or it's a new draft), there is no valid pin target (you'd pin a different
     * host's cert than the one shown), so the trust button is withheld.
     */
    fun testConnectionDraft(connection: Connection) {
        val saved = store.get(connection.id)
        val pinTarget = saved?.id?.takeIf { saved.serverUrl == connection.serverUrl }
        runDiagnostic(pinTarget, connection)
    }

    private fun runDiagnostic(connectionId: String?, conn: Connection) {
        if (connectionId != null && testing[connectionId] == true) return // a run for this id is already in flight
        connectionId?.let { testing[it] = true }
        banner = "Testing ${conn.name} (${conn.serverUrl})…"
        scope.launch(Dispatchers.IO) {
            val report = runCatching { ConnectionDiagnostic.run(conn) }.getOrNull()
            connectionId?.let { testing.remove(it) }
            if (report == null) {
                banner = "Couldn't run the connection test for ${conn.name}."
                return@launch
            }
            runCatching { DiagnosticsLog.append(report) }
            banner = null
            dialog = AppDialog.TestResult(connectionId, report)
        }
    }

    // --- in-form test (inline result, no dialog) --------------------------------------------------

    /** The latest in-form Test-connection report, rendered inline under the form's address field. */
    var formTestReport by mutableStateOf<ConnectionTestReport?>(null)
        private set

    /** True while an in-form test is running, so the form can show a busy state. */
    var formTesting by mutableStateOf(false)
        private set

    /** Run the diagnostic for the form's current draft and surface the result INLINE (not as a modal). */
    fun testConnectionForForm(draft: Connection) {
        if (formTesting) return
        formTesting = true
        formTestReport = null
        banner = "Testing ${draft.serverUrl}…"
        scope.launch(Dispatchers.IO) {
            val report = runCatching { ConnectionDiagnostic.run(draft) }.getOrNull()
            formTesting = false
            banner = null
            if (report == null) { banner = "Couldn't run the connection test."; return@launch }
            runCatching { DiagnosticsLog.append(report) }
            formTestReport = report
        }
    }

    /** Clear the inline test result (on form open / address change). */
    fun clearFormTest() { formTestReport = null }

    // --- server-certificate trust (managed trust-on-first-connect) --------------------------------

    val pinnedConnections: List<Connection> get() = connections.filter { it.isPinned }

    /** Probe the live server cert and open the trust-pin dialog so a human can review + pin it. */
    fun beginTrustPrompt(id: String) {
        val conn = store.get(id) ?: return
        val (host, port) = hostPort(conn) ?: run { banner = "This connection has no host to probe."; return }
        dialog = null // close any current dialog while we probe; the trust dialog opens on success
        banner = "Fetching certificate from $host:$port…"
        scope.launch(Dispatchers.IO) {
            runCatching { ServerCertProbe.fetchLeaf(host, port) }
                .onSuccess { cert ->
                    banner = null
                    dialog = AppDialog.TrustPin(id, conn.name, cert, CertDetails.from(cert), conn.normalizedPin)
                }
                .onFailure { banner = "Couldn't fetch certificate from $host:$port: ${it.message ?: it.javaClass.simpleName}" }
        }
    }

    /** Apply the explicit, audited pin from the trust dialog. */
    fun confirmTrustPin(id: String, cert: X509Certificate) {
        val conn = store.get(id) ?: return
        ServerCertPinning.trust(conn, cert) // audit: human reviewed + trusted
        ServerCertPinning.pin(store, conn, cert, persist = false) // audit: pinned; saved via the single debounced path
        reload()
        schedulePersist()
        dialog = null
        banner = "Pinned the server certificate for ${conn.name}."
    }

    /** Remove a connection's server-cert pin (audited). */
    fun unpinServerCert(id: String) {
        val conn = store.get(id) ?: return
        ServerCertPinning.revoke(store, conn, persist = false)
        reload()
        schedulePersist()
        banner = "Removed the pinned certificate for ${conn.name}."
    }

    /** Probe + show the live cert details (read-only), flagging pin-mismatch and expiry. */
    fun viewServerCert(id: String) {
        val conn = store.get(id) ?: return
        val (host, port) = hostPort(conn) ?: run { banner = "This connection has no host to probe."; return }
        dialog = null // close any current dialog while we probe; the details dialog opens on success
        banner = "Fetching certificate from $host:$port…"
        scope.launch(Dispatchers.IO) {
            runCatching { ServerCertProbe.fetchLeaf(host, port) }
                .onSuccess { cert ->
                    banner = null
                    val details = CertDetails.from(cert)
                    val pinMatches = conn.normalizedPin?.let { it == details.fingerprintPlain }
                    dialog = AppDialog.CertView(id, conn.name, details, pinMatches)
                }
                .onFailure { banner = "Couldn't fetch certificate from $host:$port: ${it.message ?: it.javaClass.simpleName}" }
        }
    }

    private fun hostPort(conn: Connection): Pair<String, Int>? {
        val uri = runCatching { conn.baseUri() }.getOrNull() ?: return null
        val host = uri.host ?: return null
        return host to (if (uri.port > 0) uri.port else 443)
    }

    // --- code-signing trust (posture + custom pins / anchors) -------------------------------------

    fun updateTrustPosture(value: TrustMode) {
        trustPosture = value
        prefs.trustPosture = value
        AuditLog.event("trust.posture", "mode" to value.name)
    }

    /** Official (built-in, read-only) publisher pins. */
    val officialSignerPins: List<SignerPin> get() = GestaltTrust.OFFICIAL_SIGNER_PINS

    /** Operator-added publisher DNs (strings, as entered). */
    fun customSignerDnStrings(): List<String> = prefs.customSignerDns

    /** Operator-added CA-anchor PEM paths. */
    fun customAnchorPaths(): List<String> = prefs.customAnchorPaths

    /** The bundled CA anchors (public roots + the Gestalt self-signed cert if present). */
    fun bundledAnchors(): List<X509Certificate> =
        runCatching { GestaltTrust.publicRoots().anchors.toList() }.getOrDefault(emptyList()) +
            (GestaltTrust.gestaltCert()?.let { listOf(it) } ?: emptyList())

    fun addCustomSignerDn(dn: String) {
        val trimmed = dn.trim()
        if (trimmed.isBlank() || parseSignerDn(trimmed) == null) {
            banner = "That doesn't look like a DN (expected e.g. CN=Acme Code Signing, O=Acme Inc)."
            return
        }
        prefs.customSignerDns = (prefs.customSignerDns + trimmed).distinct()
        AuditLog.event("trust.signer.add", "dn" to trimmed)
        trustNonce++
        banner = "Added custom signer pin."
    }

    fun removeCustomSignerDn(dn: String) {
        prefs.customSignerDns = prefs.customSignerDns.filterNot { it == dn }
        AuditLog.event("trust.signer.remove", "dn" to dn)
        trustNonce++
    }

    fun addCustomAnchor(path: String) {
        val trimmed = path.trim()
        if (trimmed.isBlank()) return
        val loaded = runCatching { loadAnchorFile(trimmed) }.getOrNull()
        if (loaded.isNullOrEmpty()) {
            banner = "Couldn't read a certificate from $trimmed."
            return
        }
        prefs.customAnchorPaths = (prefs.customAnchorPaths + trimmed).distinct()
        AuditLog.event("trust.anchor.add", "path" to trimmed)
        trustNonce++
        banner = "Added custom CA anchor."
    }

    fun removeCustomAnchor(path: String) {
        prefs.customAnchorPaths = prefs.customAnchorPaths.filterNot { it == path }
        AuditLog.event("trust.anchor.remove", "path" to path)
        trustNonce++
    }

    /** Resolve operator custom DNs into [SignerPin]s for the gate (skips unparseable ones). */
    fun customSignerPins(): List<SignerPin> = prefs.customSignerDns.mapNotNull { parseSignerDn(it) }

    /** Resolve operator custom anchor files into certs (skips unreadable ones). */
    fun customAnchorCerts(): List<X509Certificate> = prefs.customAnchorPaths.flatMap {
        runCatching { loadAnchorFile(it) }.getOrDefault(emptyList())
    }

    private fun loadAnchorFile(path: String): List<X509Certificate> =
        File(path).inputStream().use { input ->
            java.security.cert.CertificateFactory.getInstance("X.509")
                .generateCertificates(input)
                .filterIsInstance<X509Certificate>()
        }

    /** Parse an RFC-2253-ish DN ("CN=…, O=…") into a [SignerPin] of DN-attribute requirements. */
    private fun parseSignerDn(dn: String): SignerPin? {
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

    // --- diagnosis dialog actions -----------------------------------------------------------------

    fun openLogsFolder() = openFolder(AppPaths.logDir)
    fun openDataFolder() = openFolder(AppPaths.dataDir)
    fun openCacheFolder() = openFolder(AppPaths.cacheDir)

    /** Open a URL in the default browser (used for icon-pack attribution links). */
    fun openUrl(url: String) {
        if (url.isBlank()) return
        runCatching {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(java.net.URI.create(url))
            } else {
                banner = "Open this URL: $url"
            }
        }.onFailure { banner = "Open this URL: $url" }
    }

    private fun openFolder(path: Path) {
        runCatching {
            Files.createDirectories(path)
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(path.toFile())
            } else {
                banner = "Folder: $path"
            }
        }.onFailure { banner = "Couldn't open $path: ${it.message}" }
    }

    fun clearCache() {
        scope.launch(Dispatchers.IO) {
            banner = runCatching { clearCacheNow() }.fold({ "Cache cleared." }, { "Couldn't clear cache: ${it.message}" })
        }
    }

    /** Delete the contents of the cache dir (keeping the dir). Synchronous; the IO wrapper is [clearCache]. */
    internal fun clearCacheNow() {
        val dir = AppPaths.cacheDir.toFile()
        if (dir.isDirectory) dir.listFiles()?.forEach { it.deleteRecursively() }
    }

    // --- self-update (Phase 4b) -------------------------------------------------------------------

    var updateState by mutableStateOf<UpdateState>(UpdateState.Idle)
        private set

    /** Best-effort check on app launch — only when a channel is configured. Silent (no banner). */
    fun checkForUpdatesOnLaunch() {
        if (prefs.updateChannelUrl.isBlank()) return
        checkForUpdates()
    }

    /** Fetch + verify the signed manifest at the configured channel and surface the result. */
    fun checkForUpdates() {
        val channel = prefs.updateChannelUrl.trim()
        if (channel.isEmpty()) {
            updateState = UpdateState.Unavailable("Set an update channel URL first (above).")
            return
        }
        updateState = UpdateState.Checking
        scope.launch(Dispatchers.IO) {
            val outcome = runCatching { updateServiceFactory().check(channel) }
                .getOrElse { UpdateOutcome.Failed(it.message ?: "update check failed") }
            updateState = when (outcome) {
                is UpdateOutcome.Available -> UpdateState.Available(outcome)
                UpdateOutcome.UpToDate -> UpdateState.UpToDate
                is UpdateOutcome.Unavailable -> UpdateState.Unavailable(outcome.reason)
                is UpdateOutcome.Failed -> UpdateState.Failed(outcome.reason)
            }
        }
    }

    /**
     * Apply the currently-available update, fail-closed (download verifies the signed SHA-256 first):
     *  - installed → launch the verified installer to upgrade in place, then exit;
     *  - portable + writable (Windows) → stage the verified archive and hand off to a swap helper, then exit;
     *  - portable + read-only / other OS → notify only (the UI offers a download link).
     */
    fun applyAvailableUpdate() {
        val available = (updateState as? UpdateState.Available)?.outcome ?: return
        updateState = UpdateState.Applying
        scope.launch(Dispatchers.IO) {
            runCatching {
                val service = updateServiceFactory()
                val file = service.download(available.artifact, AppPaths.cacheDir.resolve("updates"))
                if (available.portable) applyPortable(service, available, file) else applyInstalled(service, available, file)
            }.onFailure { updateState = UpdateState.Failed(it.message ?: "Update failed") }
        }
    }

    private fun applyInstalled(service: UpdateService, available: UpdateOutcome.Available, installer: Path) {
        // Detached updater: discard its stdio so a chatty installer can't stall on a full, undrained pipe
        // (the app exits right after) and so the child doesn't depend on the parent's stream handles.
        ProcessBuilder(service.installerCommand(installer))
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        AuditLog.updateApplied(available.manifest.version, "installed")
        runCatching { requestClose() }
    }

    private fun applyPortable(service: UpdateService, available: UpdateOutcome.Available, archive: Path) {
        val appDir = AppPaths.dataDir.parent
        if (appDir == null || !AppPaths.isWindows || !PortableSwap.isAppDirWritable(appDir)) {
            // Read-only / non-Windows portable copy: notify only.
            banner = "Update ${available.manifest.version} downloaded and verified. This copy can't self-update " +
                "here — use Download to update manually."
            updateState = UpdateState.Available(available)
            return
        }
        val staging = Files.createTempDirectory("gcc-update-")
        unzip(archive, staging)
        val imageRoot = staging.resolve("GestaltCloudConnect").takeIf { Files.isDirectory(it) } ?: staging
        val exe = appDir.resolve("GestaltCloudConnect.exe")
        val script = staging.resolve("gestalt-swap.ps1")
        Files.writeString(
            script,
            PortableSwap.windowsSwapScript(appDir, imageRoot, ProcessHandle.current().pid(), exe, AppPaths.PORTABLE_DATA_DIR_NAME),
        )
        ProcessBuilder("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", script.toString())
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        AuditLog.updateApplied(available.manifest.version, "portable")
        runCatching { requestClose() }
    }

    /** Open the update artifact's download URL in the browser (portable notify path). */
    fun openUpdateDownload() {
        val url = (updateState as? UpdateState.Available)?.outcome?.artifact?.url ?: return
        runCatching {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(java.net.URI.create(url))
            } else {
                banner = "Download: $url"
            }
        }.onFailure { banner = "Open this URL to download: $url" }
    }

    private fun unzip(zip: Path, destDir: Path) {
        Files.createDirectories(destDir)
        ZipInputStream(Files.newInputStream(zip)).use { zin ->
            var entry = zin.nextEntry
            while (entry != null) {
                val target = destDir.resolve(entry.name).normalize()
                require(target.startsWith(destDir)) { "zip entry escapes target dir: ${entry.name}" }
                if (entry.isDirectory) {
                    Files.createDirectories(target)
                } else {
                    Files.createDirectories(target.parent)
                    Files.newOutputStream(target).use { zin.copyTo(it) }
                }
                entry = zin.nextEntry
            }
        }
    }

    companion object {
        fun envCue(connection: Connection): EnvCue? {
            val hay = (connection.name + " " + (connection.group ?: "")).lowercase()
            return when {
                Regex("\\bprod(uction)?\\b").containsMatchIn(hay) -> EnvCue.Prod
                Regex("\\bstag(e|ing)?\\b").containsMatchIn(hay) -> EnvCue.Staging
                Regex("\\b(test|qa|uat)\\b").containsMatchIn(hay) -> EnvCue.Test
                Regex("\\bdev(elopment)?\\b").containsMatchIn(hay) -> EnvCue.Dev
                else -> null
            }
        }
    }
}
