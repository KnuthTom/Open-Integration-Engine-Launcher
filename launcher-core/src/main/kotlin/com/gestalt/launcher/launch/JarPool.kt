/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.launch

import com.gestalt.launcher.digest.DigestVerifier
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Jars shared across connections, filed by their SHA-256.
 *
 * Two servers on the same engine version publish the same jars byte for byte, but the jar cache is
 * per connection, so the first launch of each new connection used to pull all ~170 jars through its
 * tunnel again. This pool is a second place to look before the network: when a connection's slot
 * lacks the bytes its server publishes the digest of, the pool is asked for exactly those bytes by
 * that digest. Bytes enter the pool only once the gate has admitted them for some connection, and a
 * pooled jar is used for another connection only through the same path a download takes — hashed and
 * compared to what *that* server publishes, then the gate (or a cached admission under the policy in
 * force). The pool is a byte source, never a verdict; a corrupt entry hashes wrong, is dropped, and
 * the jar is fetched.
 *
 * Layout: `<pool>/<first two hex>/<sha256>.jar`. A hit touches the entry; [prune] drops entries not
 * used for [MAX_AGE_DAYS]. Losing the pool costs downloads, nothing else.
 */
class JarPool(private val dir: Path) {

    /** The pooled file holding [sha256], or null. The entry is touched so [prune] keeps what is in use. */
    fun find(sha256: String): Path? {
        val file = fileFor(sha256) ?: return null
        if (!Files.isRegularFile(file)) return null
        runCatching { Files.setLastModifiedTime(file, FileTime.from(Instant.now())) }
        return file
    }

    /**
     * Fill [target] from the pool with the bytes whose digest is [sha256], verifying the copy. True when
     * [target] now holds them; false when the pool has no such entry or its entry was corrupt (then
     * dropped). Never throws.
     */
    fun restore(sha256: String, target: Path): Boolean {
        val pooled = find(sha256) ?: return false
        return runCatching {
            Files.createDirectories(target.parent)
            val tmp = target.resolveSibling(target.fileName.toString() + ".pool.tmp")
            Files.copy(pooled, tmp, StandardCopyOption.REPLACE_EXISTING)
            val actual = DigestVerifier.sha256Hex(tmp)
            if (!DigestVerifier.constantTimeEquals(actual, sha256.lowercase())) {
                Files.deleteIfExists(tmp)
                Files.deleteIfExists(pooled) // corrupt: not the bytes it was filed under
                return false
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            true
        }.getOrDefault(false)
    }

    /** File [source]'s bytes under [sha256] (their digest, already computed by the caller) unless already pooled. Never throws. */
    fun offer(sha256: String, source: Path) {
        val file = fileFor(sha256) ?: return
        if (Files.isRegularFile(file)) return
        runCatching {
            Files.createDirectories(file.parent)
            val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
            Files.copy(source, tmp, StandardCopyOption.REPLACE_EXISTING)
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }

    /** Drop entries not used for [maxAgeDays] (hits and offers both count as use). Never throws; returns the number removed. */
    fun prune(maxAgeDays: Long = MAX_AGE_DAYS, now: Instant = Instant.now()): Int {
        if (!Files.isDirectory(dir)) return 0
        val cutoff = now.minus(maxAgeDays, ChronoUnit.DAYS)
        var removed = 0
        runCatching {
            Files.walk(dir).use { paths ->
                paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jar") }.forEach { f ->
                    val stamp = runCatching { Files.getLastModifiedTime(f).toInstant() }.getOrNull() ?: return@forEach
                    if (stamp.isBefore(cutoff) && runCatching { Files.deleteIfExists(f) }.getOrDefault(false)) removed++
                }
            }
        }
        return removed
    }

    /** How many jars the pool holds. */
    fun size(): Int = if (!Files.isDirectory(dir)) 0 else runCatching {
        Files.walk(dir).use { it.filter { p -> Files.isRegularFile(p) && p.fileName.toString().endsWith(".jar") }.count().toInt() }
    }.getOrDefault(0)

    private fun fileFor(sha256: String): Path? {
        val hex = sha256.lowercase()
        if (!HEX64.matches(hex)) return null
        return dir.resolve(hex.substring(0, 2)).resolve("$hex.jar")
    }

    companion object {
        const val DIR_NAME = "pool"
        const val MAX_AGE_DAYS = 90L
        private val HEX64 = Regex("^[0-9a-f]{64}$")
    }
}
