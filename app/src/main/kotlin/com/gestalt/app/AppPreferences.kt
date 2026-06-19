/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.app

import com.gestalt.design.theme.Density
import com.gestalt.design.theme.Mode
import com.gestalt.design.theme.Skin
import com.gestalt.launcher.AppPaths
import com.gestalt.launcher.model.JavaHomeMode
import com.gestalt.trust.TrustMode
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/**
 * App-level preference store (a properties file under the per-user data dir). Holds only UI and
 * launch-default preferences — **never** connection config or secrets.
 *
 * The Build & Signing section (Phase 3c) is the one place that touches code-signing config, and even
 * there it persists only the keystore *path*, *alias*, and *TSA URL*. The PKCS12 private key and its
 * passphrase are never written here — they come from the OS keychain or a build-time prompt.
 */
class AppPreferences(private val file: Path = AppPaths.dataDir.resolve("ui-prefs.properties")) {

    private val props = Properties().also { p ->
        runCatching { if (Files.isRegularFile(file)) Files.newInputStream(file).use { p.load(it) } }
    }

    // --- appearance -------------------------------------------------------------------------------

    var skin: Skin
        get() = runCatching { Skin.valueOf(props.getProperty("skin", Skin.Dashboard.name)) }.getOrDefault(Skin.Dashboard)
        set(value) { props.setProperty("skin", value.name); persist() }

    var mode: Mode
        get() = runCatching { Mode.valueOf(props.getProperty("mode", Mode.Light.name)) }.getOrDefault(Mode.Light)
        set(value) { props.setProperty("mode", value.name); persist() }

    /** Comfortable/Compact density toggle (overrides the skin's natural density). */
    var density: Density
        get() = runCatching { Density.valueOf(props.getProperty("density", Density.Comfortable.name)) }.getOrDefault(Density.Comfortable)
        set(value) { props.setProperty("density", value.name); persist() }

    var closeAfterLaunch: Boolean
        get() = props.getProperty("closeAfterLaunch", "false").toBoolean()
        set(value) { props.setProperty("closeAfterLaunch", value.toString()); persist() }

    // --- launch defaults (applied to new connections) --------------------------------------------

    var defaultJavaHomeMode: JavaHomeMode
        get() = runCatching { JavaHomeMode.valueOf(props.getProperty("defaultJavaHomeMode", JavaHomeMode.Default.name)) }
            .getOrDefault(JavaHomeMode.Default)
        set(value) { props.setProperty("defaultJavaHomeMode", value.name); persist() }

    var defaultBundledVersion: String
        get() = props.getProperty("defaultBundledVersion", "17")
        set(value) { props.setProperty("defaultBundledVersion", value); persist() }

    var defaultCustomJavaHome: String
        get() = props.getProperty("defaultCustomJavaHome", "")
        set(value) { props.setProperty("defaultCustomJavaHome", value); persist() }

    var defaultHeap: String
        get() = props.getProperty("defaultHeap", "")
        set(value) { props.setProperty("defaultHeap", value); persist() }

    // --- trust ------------------------------------------------------------------------------------

    var trustPosture: TrustMode
        get() = runCatching { TrustMode.valueOf(props.getProperty("trustPosture", TrustMode.PIN_KNOWN_SIGNERS.name)) }
            .getOrDefault(TrustMode.PIN_KNOWN_SIGNERS)
        set(value) { props.setProperty("trustPosture", value.name); persist() }

    /** Advanced: extra publisher DNs the operator added to the pin allow-list (one DN per line). */
    var customSignerDns: List<String>
        get() = readList("customSignerDns")
        set(value) { writeList("customSignerDns", value); persist() }

    /** Advanced: extra CA-anchor PEM file paths the operator added (one path per line). */
    var customAnchorPaths: List<String>
        get() = readList("customAnchorPaths")
        set(value) { writeList("customAnchorPaths", value); persist() }

    // --- logging / data / updates -----------------------------------------------------------------

    var logLevel: String
        get() = props.getProperty("logLevel", "INFO")
        set(value) { props.setProperty("logLevel", value); persist() }

    var updateChannelUrl: String
        get() = props.getProperty("updateChannelUrl", "")
        set(value) { props.setProperty("updateChannelUrl", value); persist() }

    // --- Build & Signing (developer / reseller) ---------------------------------------------------
    // GUARDRAIL: only path + alias + TSA are stored. The private key and passphrase are NEVER here.

    var signingKeystorePath: String
        get() = props.getProperty("signingKeystorePath", "")
        set(value) { props.setProperty("signingKeystorePath", value); persist() }

    var signingKeyAlias: String
        get() = props.getProperty("signingKeyAlias", "")
        set(value) { props.setProperty("signingKeyAlias", value); persist() }

    var signingTsaUrl: String
        get() = props.getProperty("signingTsaUrl", "")
        set(value) { props.setProperty("signingTsaUrl", value); persist() }

    // --- internals --------------------------------------------------------------------------------

    private fun readList(key: String): List<String> =
        props.getProperty(key, "").split('\n').map { it.trim() }.filter { it.isNotEmpty() }

    private fun writeList(key: String, value: List<String>) {
        val cleaned = value.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleaned.isEmpty()) props.remove(key) else props.setProperty(key, cleaned.joinToString("\n"))
    }

    private fun persist() {
        runCatching {
            file.parent?.let { Files.createDirectories(it) }
            Files.newOutputStream(file).use { props.store(it, "Open Integration Engine Launcher preferences") }
        }
    }
}
