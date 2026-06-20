/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.matrix

import com.gestalt.launcher.net.ArtifactFetcher
import com.gestalt.launcher.testutil.TestArtifacts
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64

/**
 * Shared infrastructure for the Phase-5 validation suite (matrix harness, cert-rotation drill, soak
 * regression). Each harness writes a markdown results file under the **repo-root** `build/` directory so
 * the acceptance artifacts land at the documented paths regardless of the test working directory.
 */
object Phase5Report {
    /** Ascend from the test working directory to the directory that holds `settings.gradle.kts`. */
    fun repoRoot(): Path {
        var dir: Path? = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        while (dir != null) {
            if (Files.exists(dir.resolve("settings.gradle.kts"))) return dir
            dir = dir.parent
        }
        return Path.of(System.getProperty("user.dir")).toAbsolutePath()
    }

    fun write(fileName: String, content: String): Path {
        val out = repoRoot().resolve("build").resolve(fileName)
        Files.createDirectories(out.parent)
        Files.writeString(out, content)
        return out
    }
}

/** Offline fetcher: serves canned bytes keyed by absolute-URI string; throws (like a 404) otherwise. */
class FakeFetcher(private val responses: Map<String, ByteArray>) : ArtifactFetcher {
    override fun fetchBytes(uri: URI): ByteArray =
        responses[uri.toString()] ?: throw java.io.IOException("no canned response for $uri")
}

/** Builds the same JNLP / signed-jar / canned-response fixtures all three harnesses drive the pipeline with. */
object Phase5Fixtures {
    fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)
    fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)

    /** A signed `core.jar` admitted by the BrightCode (OIE) publisher pin, returned as bytes. */
    fun signedCore(dir: Path, leaf: TestArtifacts.Leaf, fileName: String = "build-core.jar"): ByteArray {
        val jar = dir.resolve(fileName)
        TestArtifacts.writeSignedJar(
            jar,
            mapOf("mirth/Main.class" to "main".toByteArray()),
            leaf.keyPair.private,
            leaf.chain,
        )
        return Files.readAllBytes(jar)
    }

    /**
     * A JNLP shaped like a real OIE/Mirth descriptor: an inline per-jar SHA-256, the curated Java-9+
     * module flags the client needs (plus nothing hostile), the server's self-reported URL as the first
     * application argument, and the version as the second.
     */
    fun jnlp(serverArg: String, sha256B64: String): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <jnlp spec="1.0+" version="1.0.0">
          <resources>
            <j2se version="1.9+" java-vm-args="--add-opens=java.base/java.lang=ALL-UNNAMED --add-modules=java.sql.rowset"/>
            <jar href="core.jar" main="true" sha256="$sha256B64"/>
          </resources>
          <application-desc main-class="com.mirth.connect.client.ui.Mirth">
            <argument>$serverArg</argument>
            <argument>1.0.0</argument>
          </application-desc>
        </jnlp>
    """.trimIndent()

    /** Canned `/webstart` + `core.jar` responses for [base], with the inline digest of [jarBytes]. */
    fun canned(base: String, jarBytes: ByteArray, serverArg: String): Map<String, ByteArray> = mapOf(
        "${base}webstart" to jnlp(serverArg, b64(sha256(jarBytes))).toByteArray(),
        "${base}core.jar" to jarBytes,
    )
}
