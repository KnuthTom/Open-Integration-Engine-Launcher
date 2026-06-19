/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.app.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.gestalt.app.LaunchDefaults
import com.gestalt.launcher.importer.ConnectionImport
import com.gestalt.launcher.model.CloseAfterLaunch
import com.gestalt.launcher.model.Connection
import com.gestalt.launcher.model.ConnectionMode
import com.gestalt.launcher.model.ConnectionUrl
import com.gestalt.launcher.model.JavaHomeConfig
import com.gestalt.launcher.model.JavaHomeMode
import com.gestalt.launcher.model.SslOption

/**
 * Mutable state for the connection form, redesigned around a single SMART address field (§6.3.1): the
 * mode/scheme/security are auto-detected from what's typed or pasted, only Name + Address are required,
 * and the advanced runtime/security fields live behind collapsed sections. Kept UI-free (no composables)
 * so the detection, paste-fill, and validation are unit-tested directly.
 */
internal class ConnectionFormState(initial: Connection?, defaults: LaunchDefaults? = null) {
    val isNew = initial == null
    val id = initial?.id

    var name by mutableStateOf(initial?.name ?: "")
    var group by mutableStateOf(initial?.group ?: "")
    /** The one smart field: a host:port or full URL. Detection derives the rest. */
    var address by mutableStateOf(initial?.serverUrl ?: "")
    /** An explicit mode override; null means "use the auto-detected mode" (never a prerequisite). */
    var modeOverride by mutableStateOf<ConnectionMode?>(null)

    var icon by mutableStateOf(initial?.icon ?: Connection.DEFAULT_ICON_KEY)
    var javaHomeMode by mutableStateOf(initial?.javaHome?.mode ?: defaults?.javaHomeMode ?: JavaHomeMode.Default)
    var bundledVersion by mutableStateOf(initial?.javaHome?.bundledVersion ?: defaults?.bundledVersion ?: "17")
    var customJavaHome by mutableStateOf(initial?.javaHome?.customPath ?: defaults?.customJavaHome ?: "")
    var javaFxHome by mutableStateOf(initial?.javaFxHome ?: "")
    var heap by mutableStateOf(initial?.heapSize ?: defaults?.heap ?: "")
    var showConsole by mutableStateOf(initial?.showJavaConsole ?: false)
    var closeAfterLaunch by mutableStateOf(initial?.closeAfterLaunch ?: CloseAfterLaunch.Inherit)
    var legacyDh by mutableStateOf(initial?.legacyTlsCompatibility ?: false)
    var sslProtocolsCustom by mutableStateOf(initial?.sslProtocols?.custom ?: false)
    var sslProtocolsValue by mutableStateOf(initial?.sslProtocols?.value ?: "")
    var sslCiphersCustom by mutableStateOf(initial?.sslCipherSuites?.custom ?: false)
    var sslCiphersValue by mutableStateOf(initial?.sslCipherSuites?.value ?: "")
    var pin by mutableStateOf(initial?.pinnedCertSha256 ?: "")
    var username by mutableStateOf(initial?.username ?: "")
    var password by mutableStateOf("")

    /** Live detection from the current address — drives the inline hint, the insecure flag, and the mode. */
    val detected: ConnectionUrl.Detected get() = ConnectionUrl.detect(address)

    /** The mode that will be stored: the explicit override, else the auto-detected one. */
    val effectiveMode: ConnectionMode get() = modeOverride ?: detected.mode

    /** True when the connection as currently edited would use plain, unencrypted http. */
    val isPlainHttp: Boolean get() = detected.insecure

    /**
     * Handle text typed or pasted into the address field. A pasted connection entry (a legacy
     * `connections.json` object or a Gestalt one) prefills the whole form; anything else is just the
     * address. This is what makes paste-to-fill work without a separate import step.
     */
    fun onAddressInput(value: String) {
        val t = value.trimStart()
        val entry = if (t.startsWith("{") || t.startsWith("[")) ConnectionImport.parseSingleEntry(value) else null
        if (entry != null) applyEntry(entry) else address = value
    }

    /** Prefill every field from a pasted connection entry. Credentials are never adopted. */
    fun applyEntry(c: Connection) {
        if (c.name.isNotBlank()) name = c.name
        address = c.serverUrl
        modeOverride = null
        c.group?.let { group = it }
        icon = c.icon
        javaHomeMode = c.javaHome.mode
        bundledVersion = c.javaHome.bundledVersion
        customJavaHome = c.javaHome.customPath ?: ""
        javaFxHome = c.javaFxHome ?: ""
        heap = c.heapSize ?: ""
        showConsole = c.showJavaConsole
        closeAfterLaunch = c.closeAfterLaunch
        legacyDh = c.legacyTlsCompatibility
        sslProtocolsCustom = c.sslProtocols.custom
        sslProtocolsValue = c.sslProtocols.value
        sslCiphersCustom = c.sslCipherSuites.custom
        sslCiphersValue = c.sslCipherSuites.value
        pin = c.pinnedCertSha256 ?: ""
    }

    /** Minimal required input: only Name + a usable Address. Everything else has a sane default. */
    fun validate(): String? {
        if (name.isBlank()) return "Name is required."
        if (address.isBlank()) return "Address is required."
        if (!detected.valid) {
            return "Address must be a host:port or full URL — e.g. oie.lab:8443 or https://host:8443."
        }
        if (heap.isNotBlank() && !heap.trim().matches(HEAP_PATTERN)) {
            return "Heap size must look like 512m, 1024m, or 2g."
        }
        if (password.isNotBlank() && username.isBlank()) {
            return "A username is required to store a password."
        }
        return null
    }

    fun toConnection(id: String, sortOrder: Int): Connection = Connection(
        id = id,
        name = name.trim(),
        serverUrl = detected.normalized.ifBlank { address.trim() },
        username = username.trim().ifBlank { null },
        pinnedCertSha256 = pin.trim().ifBlank { null },
        legacyTlsCompatibility = legacyDh,
        group = group.trim().ifBlank { null },
        sortOrder = sortOrder,
        connectionMode = effectiveMode,
        icon = icon,
        javaHome = JavaHomeConfig(javaHomeMode, bundledVersion, customJavaHome.trim().ifBlank { null }),
        javaFxHome = javaFxHome.trim().ifBlank { null },
        heapSize = heap.trim().ifBlank { null },
        showJavaConsole = showConsole,
        sslProtocols = SslOption(sslProtocolsCustom, sslProtocolsValue.trim()),
        sslCipherSuites = SslOption(sslCiphersCustom, sslCiphersValue.trim()),
        closeAfterLaunch = closeAfterLaunch,
    )

    fun passwordChars(): CharArray? = password.takeIf { it.isNotBlank() }?.toCharArray()

    /** Summary chip for the collapsed "Runtime (JVM)" section, or null when everything is at its default. */
    val runtimeSummary: String?
        get() = buildList {
            when (javaHomeMode) {
                JavaHomeMode.Bundled -> add("Java $bundledVersion")
                JavaHomeMode.Custom -> add("Custom JDK")
                JavaHomeMode.Default -> {}
            }
            if (heap.isNotBlank()) add(heap.trim())
            if (javaFxHome.isNotBlank()) add("JavaFX")
            if (showConsole) add("console")
            when (closeAfterLaunch) {
                CloseAfterLaunch.On -> add("close on")
                CloseAfterLaunch.Off -> add("close off")
                CloseAfterLaunch.Inherit -> {}
            }
        }.takeIf { it.isNotEmpty() }?.joinToString(" · ")

    /** Summary chip for the collapsed "Security (TLS)" section, or null when everything is at its default. */
    val securitySummary: String?
        get() = buildList {
            if (sslProtocolsCustom && sslProtocolsValue.isNotBlank()) add("TLS protocols")
            if (sslCiphersCustom && sslCiphersValue.isNotBlank()) add("ciphers")
            if (legacyDh) add("legacy DH")
            if (pin.isNotBlank()) add("pinned")
        }.takeIf { it.isNotEmpty() }?.joinToString(" · ")

    companion object {
        private val HEAP_PATTERN = Regex("^\\d+[kKmMgG]?$")
    }
}
