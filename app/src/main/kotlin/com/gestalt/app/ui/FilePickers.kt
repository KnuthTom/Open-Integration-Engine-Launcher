/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.app.ui

import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/**
 * Native (AWT) modal file dialogs for import/export. Modal-blocking by design — invoked from a button
 * handler; returns the chosen [File] or null if cancelled.
 */
object FilePickers {

    fun openFile(title: String = "Import connections"): File? {
        val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
        dialog.setFilenameFilter { _, name -> name.endsWith(".json", ignoreCase = true) }
        dialog.isVisible = true
        val name = dialog.file ?: return null
        return File(dialog.directory, name)
    }

    fun saveFile(title: String = "Export connections", defaultName: String = "connections.json"): File? {
        val dialog = FileDialog(null as Frame?, title, FileDialog.SAVE)
        dialog.file = defaultName
        dialog.isVisible = true
        val name = dialog.file ?: return null
        return File(dialog.directory, name)
    }

    /** Choose an image file (.svg/.png/.jpg) to add as a custom connection icon. */
    fun openImageFile(title: String = "Add custom icon"): File? {
        val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
        dialog.setFilenameFilter { _, name -> IMAGE_EXTENSIONS.any { name.endsWith(".$it", ignoreCase = true) } }
        dialog.isVisible = true
        val name = dialog.file ?: return null
        return File(dialog.directory, name)
    }

    private val IMAGE_EXTENSIONS = listOf("svg", "png", "jpg", "jpeg")
}
