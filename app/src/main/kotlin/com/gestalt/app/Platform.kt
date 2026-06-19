/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.app

import java.util.Locale

/** Host platform token, matching the packaging build's `<os>-<arch>` (e.g. "windows-x64"). */
object Platform {
    fun osArch(): String {
        val os = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
        val arch = System.getProperty("os.arch").orEmpty().lowercase(Locale.ROOT)
        val o = when {
            os.contains("win") -> "windows"
            os.contains("mac") || os.contains("darwin") -> "macos"
            else -> "linux"
        }
        val a = if (arch.contains("aarch64") || arch.contains("arm64")) "arm64" else "x64"
        return "$o-$a"
    }
}
