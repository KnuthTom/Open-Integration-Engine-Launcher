/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.icon

import kotlinx.serialization.Serializable

/**
 * Metadata for one user-uploaded custom icon, persisted in `<data>/icons/index.json`. The image bytes
 * live beside it as `<data>/icons/<id>.<ext>`. A connection references it by the key `custom:<id>`.
 *
 * @property id content-addressed handle (the leading hex of the sanitized bytes' SHA-256) — re-uploading
 *   the same image yields the same id, so duplicates collapse.
 * @property label the human-readable name shown in the picker / manage view (editable).
 * @property ext canonical stored extension: `svg`, `png`, or `jpg`.
 * @property tintable true when the renderer may recolor it from the theme (a `currentColor` SVG).
 * @property createdAtEpochMs upload time.
 * @property sha256 full lowercase-hex SHA-256 of the sanitized bytes.
 * @property sourceName the original filename at upload (for provenance only).
 */
@Serializable
data class CustomIcon(
    val id: String,
    val label: String,
    val ext: String,
    val tintable: Boolean,
    val createdAtEpochMs: Long,
    val sha256: String,
    val sourceName: String,
) {
    /** The stable key a connection stores in its `icon` field. */
    val key: String get() = "${CustomIconStore.KEY_PREFIX}$id"
}
