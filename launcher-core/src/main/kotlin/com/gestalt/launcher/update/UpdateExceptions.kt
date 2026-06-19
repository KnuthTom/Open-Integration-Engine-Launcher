/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.update

/** Base type for any fail-closed self-update error. The update is aborted and nothing is applied. */
open class UpdateException(message: String) : Exception(message)

/** A downloaded artifact's SHA-256 did not match the signed value, or a signature failed to verify. */
class UpdateIntegrityException(message: String) : UpdateException(message)
