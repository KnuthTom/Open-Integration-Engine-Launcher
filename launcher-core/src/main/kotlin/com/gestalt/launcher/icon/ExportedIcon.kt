/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.icon

import kotlinx.serialization.Serializable

/**
 * A custom icon as it travels inside an exported connections file: the sanitized image bytes, Base64,
 * with the id and label they were filed under. On import the bytes go through [IconSanitizer] again
 * (via [CustomIconStore.add]) and are filed under the id *they* hash to — the exporting machine's id
 * is only a handle for re-pointing the connections that used it.
 */
@Serializable
data class ExportedIcon(
    val id: String,
    val label: String,
    val ext: String,
    /** The image bytes, Base64 (standard alphabet, no line breaks). */
    val data: String,
)
