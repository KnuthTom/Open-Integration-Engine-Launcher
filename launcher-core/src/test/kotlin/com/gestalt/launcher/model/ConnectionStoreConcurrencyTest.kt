/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The store is hit from the UI thread and IO coroutines at once (edits vs. the debounced save, import,
 * export). It must never throw, tear the file, or drop an edit under that contention — and the key
 * allow-list that guards every save must stay in step with the serializer, so adding a field to
 * [Connection] can't turn into "every save fails at runtime".
 */
class ConnectionStoreConcurrencyTest {

    private fun conn(id: String, name: String = id) = Connection(id = id, name = name, serverUrl = "https://$id.example.com:8443")

    private fun idsOnDisk(file: Path): List<String> =
        Json.parseToJsonElement(Files.readString(file)).jsonObject["connections"]!!.jsonArray
            .map { it.jsonObject["id"]!!.jsonPrimitive.content }

    @Test
    @DisplayName("concurrent edits + saves from many threads never throw and the final file holds every edit")
    fun concurrentEditsAndSaves(@TempDir dir: Path) {
        val file = dir.resolve("connections.json")
        val store = ConnectionStore(file)
        val threads = 8
        val perThread = 40
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        repeat(threads) { t ->
            pool.submit {
                try {
                    start.await()
                    repeat(perThread) { i ->
                        val id = "t$t-$i"
                        store.add(conn(id), persist = false)
                        store.update(conn(id, name = "$id edited"), persist = false)
                        // Every few edits, race a full save against the other threads' edits and saves.
                        if (i % 5 == 0) store.save()
                        if (i % 7 == 0) store.exportTo(dir.resolve("export-$t.json"))
                    }
                } catch (e: Throwable) {
                    failure.compareAndSet(null, e)
                }
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS)) { "workers must finish" }
        failure.get()?.let { throw AssertionError("a worker threw: $it", it) }

        store.save()
        val expected = (0 until threads).flatMap { t -> (0 until perThread).map { i -> "t$t-$i" } }.sorted()
        assertEquals(expected, idsOnDisk(file).sorted()) { "every add must be on disk" }
        assertEquals(expected, ConnectionStore(file).list().map { it.id }.sorted()) { "and must reload" }
        assertTrue(ConnectionStore(file).list().all { it.name.endsWith(" edited") }) { "every update must be on disk" }
        val leftovers = Files.list(dir).use { s -> s.filter { it.fileName.toString().endsWith(".tmp") }.toList() }
        assertTrue(leftovers.isEmpty()) { "no temp file may be left behind: $leftovers" }
    }

    @Test
    @DisplayName("a stale temp file from a crash is swept on load and never read")
    fun staleTempSwept(@TempDir dir: Path) {
        val file = dir.resolve("connections.json")
        ConnectionStore(file).add(conn("real"))
        val stale = dir.resolve("connections.json.deadbeef.tmp")
        Files.writeString(stale, "{ this is not json")
        val store = ConnectionStore(file)
        assertEquals(listOf("real"), store.list().map { it.id })
        assertFalse(Files.exists(stale)) { "the stale temp must be deleted on load" }
    }

    @Test
    @DisplayName("a file written by a NEWER schema is preserved as .v<N> before this build first overwrites it")
    fun newerSchemaPreserved(@TempDir dir: Path) {
        val file = dir.resolve("connections.json")
        val newer = """
            {"version": ${ConnectionStore.SCHEMA_VERSION + 1},
             "connections": [{"id":"a","name":"A","serverUrl":"https://a.example.com:8443","futureField":"keep me"}]}
        """.trimIndent()
        Files.writeString(file, newer)
        val store = ConnectionStore(file)
        assertEquals(listOf("a"), store.list().map { it.id }) { "unknown fields are ignored on load" }

        store.add(conn("b"))
        store.add(conn("c"))
        val preserved = dir.resolve("connections.json.v${ConnectionStore.SCHEMA_VERSION + 1}")
        assertTrue(Files.isRegularFile(preserved)) { "the newer-schema file must be kept" }
        assertTrue(Files.readString(preserved).contains("futureField")) { "with its unknown fields intact" }
        assertEquals(ConnectionStore.SCHEMA_VERSION, ConnectionStore.declaredVersion(Files.readString(file))) { "the rewritten file carries this schema" }
    }

    @Test
    @DisplayName("the save-time key allow-list matches the serializers exactly (no stale list, no runtime surprise)")
    fun allowListPinnedToSerializers() {
        fun names(descriptor: kotlinx.serialization.descriptors.SerialDescriptor): Set<String> =
            (0 until descriptor.elementsCount).map { descriptor.getElementName(it) }.toSet()

        assertEquals(names(Connection.serializer().descriptor), ConnectionStore.ALLOWED_CONNECTION_KEYS) {
            "ALLOWED_CONNECTION_KEYS must list exactly the fields of Connection — update both together"
        }
        assertEquals(names(JavaHomeConfig.serializer().descriptor), ConnectionStore.ALLOWED_NESTED_KEYS.getValue("javaHome"))
        assertEquals(names(SslOption.serializer().descriptor), ConnectionStore.ALLOWED_NESTED_KEYS.getValue("sslProtocols"))
        assertEquals(names(SslOption.serializer().descriptor), ConnectionStore.ALLOWED_NESTED_KEYS.getValue("sslCipherSuites"))
    }
}
