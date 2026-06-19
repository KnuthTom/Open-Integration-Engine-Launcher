/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.icon

/**
 * Raised when an uploaded icon cannot be made safe (an SVG that can't be parsed/sanitized, a file over
 * the size cap, an over-large or unreadable raster, or an unsupported type). The upload is refused and
 * nothing is written — the caller surfaces [message] to the user. Fail-closed by construction.
 */
class IconRejectedException(message: String) : Exception(message)
