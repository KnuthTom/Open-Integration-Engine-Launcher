/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.icon

import com.gestalt.launcher.AppPaths
import com.gestalt.launcher.audit.AuditLog
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

@Serializable
private data class IconIndex(val version: Int = 1, val icons: List<CustomIcon> = emptyList())

/**
 * The per-user library of uploaded custom icons under [dir] (default `<data>/icons/`, never the install
 * dir). Each icon is the **sanitized** bytes at `<id>.<ext>` plus a row in `index.json`. All mutations
 * go through [IconSanitizer] — there is no path that writes un-sanitized bytes — and are audit-logged.
 *
 * The id is content-addressed (SHA-256 of the sanitized bytes), so it is filesystem-safe by construction
 * (hex only) and re-uploading the same image is idempotent rather than duplicating it.
 */
class CustomIconStore(private val dir: Path = AppPaths.iconsDir) {

    private val indexFile: Path = dir.resolve(INDEX_FILE)
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    private val icons = LinkedHashMap<String, CustomIcon>()

    init {
        load()
    }

    fun list(): List<CustomIcon> = icons.values.toList()

    fun get(id: String): CustomIcon? = icons[id]

    /** Absolute path of the image file for [id], or null if the row/file is missing or would escape [dir]. */
    fun path(id: String): Path? {
        val entry = icons[id] ?: return null
        val file = dir.resolve("${entry.id}.${entry.ext}").normalize()
        if (!file.startsWith(dir.normalize())) return null // never resolve outside the icons dir
        return file.takeIf { Files.isRegularFile(it) }
    }

    /** The image bytes for [id], or null if absent. */
    fun bytes(id: String): ByteArray? = path(id)?.let { Files.readAllBytes(it) }

    /**
     * Sanitize [input] (named [sourceName]), persist the safe copy, and register it. Re-uploading the same
     * image updates the existing row in place (keeping its label). Throws [IconRejectedException] — and
     * writes nothing — if the upload can't be made safe.
     */
    fun add(sourceName: String, input: ByteArray): CustomIcon {
        val sanitized = try {
            IconSanitizer.sanitize(sourceName, input)
        } catch (e: IconRejectedException) {
            AuditLog.iconRejected(sourceName, e.message ?: "rejected")
            throw e
        }
        val sha = sha256Hex(sanitized.bytes)
        val id = sha.take(ID_LENGTH)

        Files.createDirectories(dir)
        // Drop any stale file for this id under a different extension before writing the new one.
        icons[id]?.takeIf { it.ext != sanitized.ext }?.let { runCatching { Files.deleteIfExists(dir.resolve("${id}.${it.ext}")) } }
        Files.write(dir.resolve("$id.${sanitized.ext}"), sanitized.bytes)

        val entry = icons[id]?.copy(ext = sanitized.ext, tintable = sanitized.tintable, sha256 = sha)
            ?: CustomIcon(
                id = id,
                label = labelFrom(sourceName),
                ext = sanitized.ext,
                tintable = sanitized.tintable,
                createdAtEpochMs = System.currentTimeMillis(),
                sha256 = sha,
                sourceName = baseName(sourceName),
            )
        icons[id] = entry
        save()
        AuditLog.iconAdded(id, sanitized.ext, sanitized.bytes.size.toLong())
        return entry
    }

    /** Rename an icon (no-op for a blank label or unknown id). */
    fun rename(id: String, label: String) {
        val existing = icons[id] ?: return
        val trimmed = label.trim().ifBlank { return }
        icons[id] = existing.copy(label = trimmed)
        save()
        AuditLog.iconRenamed(id)
    }

    /** Remove an icon's row and its file. */
    fun delete(id: String) {
        val removed = icons.remove(id) ?: return
        runCatching { Files.deleteIfExists(dir.resolve("${removed.id}.${removed.ext}")) }
        save()
        AuditLog.iconDeleted(id)
    }

    private fun load() {
        icons.clear()
        if (!Files.isRegularFile(indexFile)) return
        val parsed = runCatching { json.decodeFromString<IconIndex>(Files.readString(indexFile)) }.getOrNull()
        if (parsed == null) {
            // A non-empty index that won't parse is damaged — preserve the bytes (rather than letting the
            // next save() silently overwrite the user's whole library with an empty one) and start clean.
            runCatching { Files.move(indexFile, dir.resolve("$INDEX_FILE.corrupt"), StandardCopyOption.REPLACE_EXISTING) }
            AuditLog.event("icon.index.corrupt", "result" to "PRESERVED")
            return
        }
        // Only accept well-formed rows (guards a hand-edited / future-schema index from driving file paths).
        parsed.icons.filter { it.id.matches(ID_PATTERN) && it.ext.lowercase() in ALLOWED_EXTS }.forEach { icons[it.id] = it }
        reconcileOrphans()
    }

    /** Best-effort: delete image files with no matching row (crash orphans / rows lost to a corrupt index). */
    private fun reconcileOrphans() {
        runCatching {
            Files.newDirectoryStream(dir).use { stream ->
                for (path in stream) {
                    val fileName = path.fileName.toString()
                    if (fileName.substringAfterLast('.', "").lowercase() !in ALLOWED_EXTS) continue // skip index.json, .corrupt, .tmp
                    if (fileName.substringBeforeLast('.') !in icons) runCatching { Files.deleteIfExists(path) }
                }
            }
        }
    }

    private fun save() {
        Files.createDirectories(dir)
        // Write to a sibling temp file then move into place, so a crash mid-write can't truncate the index.
        val tmp = dir.resolve("$INDEX_FILE.tmp")
        Files.writeString(tmp, json.encodeToString(IconIndex(icons = icons.values.toList())))
        Files.move(tmp, indexFile, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun labelFrom(sourceName: String): String =
        baseName(sourceName).substringBeforeLast('.').trim().ifBlank { "Custom icon" }

    private fun baseName(name: String): String =
        name.substringAfterLast('/').substringAfterLast('\\').ifBlank { name }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        /** The connection-icon key namespace for user-uploaded icons: `custom:<id>`. */
        const val KEY_PREFIX = "custom:"

        const val INDEX_FILE = "index.json"

        /** Hex chars of the content hash used as the stable id (64-bit handle — ample for a personal set). */
        const val ID_LENGTH = 16

        /** A valid stored id is lowercase hex (content-addressed) — filesystem-safe by construction. */
        private val ID_PATTERN = Regex("[0-9a-f]{1,64}")

        /** Canonical stored extensions (what [IconSanitizer] emits). */
        private val ALLOWED_EXTS = setOf("svg", "png", "jpg")

        /** The id encoded in a `custom:<id>` key, or null if [key] isn't a custom-icon key. */
        fun idFromKey(key: String): String? = key.removePrefix(KEY_PREFIX).takeIf { key.startsWith(KEY_PREFIX) && it.isNotBlank() }
    }
}
