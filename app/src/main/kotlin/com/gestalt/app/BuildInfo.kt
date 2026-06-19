/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.app

import java.util.Properties

/**
 * Version + build metadata, read from the `build-info.properties` resource that the `:app` build
 * generates (see `generateBuildInfo` in `app/build.gradle.kts`). Falls back to sensible dev defaults
 * when the resource is absent (e.g. running from the IDE without the generation task). Surfaced in the
 * Settings → About section.
 */
object BuildInfo {
    private val props: Properties = Properties().apply {
        // build-info.properties (always) + icon-pack.properties (only when a build bundled a licensed pack).
        runCatching { BuildInfo::class.java.getResourceAsStream("/build-info.properties")?.use { load(it) } }
        runCatching { BuildInfo::class.java.getResourceAsStream("/icon-pack.properties")?.use { load(it) } }
    }

    val appName: String get() = props.getProperty("name", "Open Integration Engine Launcher")
    val version: String get() = props.getProperty("version", "1.0.0")
    val vendor: String get() = props.getProperty("vendor", "Open Integration Engine Launcher")
    val buildTime: String get() = props.getProperty("buildTime", "dev build")
    val gitCommit: String get() = props.getProperty("gitCommit", "dev")
    val gitBranch: String get() = props.getProperty("gitBranch", "")
    val bundledRuntimes: String get() = props.getProperty("bundledRuntimes", "8, 17")

    // --- optional licensed icon pack (Part B; empty unless a build bundled one) --------------------
    val iconPackName: String get() = props.getProperty("iconPackName", "")
    val iconPackLicense: String get() = props.getProperty("iconPackLicense", "")
    val iconPackAttribution: String get() = props.getProperty("iconPackAttribution", "")
    val iconPackHomepage: String get() = props.getProperty("iconPackHomepage", "")
    val hasIconPack: Boolean get() = iconPackName.isNotBlank()

    /** "1.0.0 (abc1234)" when a commit is known, else just the version. */
    val displayVersion: String
        get() = if (gitCommit.isNotBlank() && gitCommit != "dev") "$version ($gitCommit)" else version
}
