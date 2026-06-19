/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.design.components

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import com.gestalt.design.theme.GestaltTheme

/** Type roles, mapped to the active [com.gestalt.design.theme.TypographyTokens] scale. */
enum class TextRole { Display, Title, Heading, Body, Label, Mono }

/** Themed text. Style comes from the type scale; color defaults to the primary text token. */
@Composable
fun GestaltText(
    text: String,
    modifier: Modifier = Modifier,
    role: TextRole = TextRole.Body,
    color: Color = GestaltTheme.colors.textPrimary,
    maxLines: Int = Int.MAX_VALUE,
) {
    val type = GestaltTheme.typography
    val base: TextStyle = when (role) {
        TextRole.Display -> type.display
        TextRole.Title -> type.title
        TextRole.Heading -> type.heading
        TextRole.Body -> type.body
        TextRole.Label -> type.label
        TextRole.Mono -> type.mono
    }
    BasicText(
        text = text,
        modifier = modifier,
        style = base.copy(color = color),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/** A small secondary label (the [TextRole.Label] role, secondary text color). */
@Composable
fun GestaltLabel(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = GestaltTheme.colors.textSecondary,
) = GestaltText(text, modifier, TextRole.Label, color)
