/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.design.theme

import androidx.compose.runtime.Immutable

/** Default fill treatment for [com.gestalt.design.components.GestaltButton]. */
enum class ButtonStyle { Filled, Outlined, Ghost }

/** Layout treatment for [com.gestalt.design.components.GestaltListRow]. */
enum class ListRowStyle { Card, Flat }

/** Navigation chrome for [com.gestalt.design.components.GestaltScaffold]. */
enum class NavStyle { Sidebar, TopTabs }

/** Decoration for [com.gestalt.design.components.GestaltTextField]. */
enum class FieldStyle { Outlined, Underline }

/**
 * The structural choices a [Skin] makes — these change component *shape and layout*, not just color,
 * which is what makes a preset flip re-skin the whole UI rather than recolor it. Components read these
 * as their default style; a caller may still override per-instance.
 */
@Immutable
data class ComponentVariants(
    val button: ButtonStyle,
    val listRow: ListRowStyle,
    val nav: NavStyle,
    val field: FieldStyle,
)
