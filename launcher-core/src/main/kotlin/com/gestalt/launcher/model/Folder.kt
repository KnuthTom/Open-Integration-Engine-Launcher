/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.model

import kotlinx.serialization.Serializable

/**
 * A folder in the connection tree. Folders nest ([parentId]; null = top level), carry a manual
 * [sortOrder] among their siblings, and remember whether they are [expanded] in the sidebar. Non-secret
 * metadata only, persisted beside the connections in `connections.json`.
 */
@Serializable
data class Folder(
    val id: String,
    val name: String,
    val parentId: String? = null,
    val sortOrder: Int = 0,
    val expanded: Boolean = true,
)
