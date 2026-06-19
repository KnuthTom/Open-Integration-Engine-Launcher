/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.update

/**
 * Minimal, fail-safe version ordering for update gating. Compares the dotted numeric core of two
 * versions (a pre-release/`+build` suffix is ignored, missing components are treated as 0). The only
 * case in which an update applies is when the offered version is **strictly newer** — so an equal or
 * lower version (a downgrade or a re-offer) is never applied.
 */
object SemVer {
    private fun core(v: String): List<Int> =
        v.trim().substringBefore('-').substringBefore('+')
            .split('.').map { it.trim().toIntOrNull() ?: 0 }

    fun compare(a: String, b: String): Int {
        val x = core(a)
        val y = core(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    /** True iff [candidate] is strictly newer than [current]. */
    fun isNewer(candidate: String, current: String): Boolean = compare(candidate, current) > 0
}
