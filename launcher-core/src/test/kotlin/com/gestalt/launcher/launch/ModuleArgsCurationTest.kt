/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.launch

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The security-critical filter that decides which (untrusted) server JVM args may reach the child JVM.
 * Only JPMS module-encapsulation flags survive; anything that could set properties, load an agent, or
 * weaken TLS must be dropped.
 */
class ModuleArgsCurationTest {

    @Test
    @DisplayName("keeps module flags (=value form) and drops everything dangerous")
    fun keepsModuleFlagsDropsTheRest() {
        // The exact shape Mirth's JNLP uses, salted with hostile extras a malicious server might add.
        val tokens = listOf(
            "--add-modules=java.sql.rowset",
            "--add-exports=java.base/sun.security.provider=ALL-UNNAMED",
            "--add-opens=java.base/java.lang=ALL-UNNAMED",
            "-javaagent:/tmp/evil.jar",
            "-Djavax.net.ssl.trustStore=/tmp/evil",
            "-Djdk.tls.client.protocols=SSLv3",
            "-Xmx999g",
            "-cp", "/tmp/evil.jar",
        )
        val curated = ChildJvmCommandBuilder.curateModuleArgs(tokens)

        assertEquals(
            listOf(
                "--add-modules=java.sql.rowset",
                "--add-exports=java.base/sun.security.provider=ALL-UNNAMED",
                "--add-opens=java.base/java.lang=ALL-UNNAMED",
            ),
            curated,
        )
        assertTrue(curated.none { it.startsWith("-javaagent") }) { "agent must be dropped" }
        assertTrue(curated.none { it.startsWith("-D") }) { "system properties must be dropped" }
        assertTrue(curated.none { it.startsWith("-X") }) { "memory/other -X flags must be dropped" }
        assertTrue(curated.none { it == "-cp" || it.endsWith(".jar") }) { "classpath injection must be dropped" }
    }

    @Test
    @DisplayName("supports the two-token '--add-opens VALUE' form and drops a flag with no value")
    fun twoTokenForm() {
        assertEquals(
            listOf("--add-opens", "java.base/java.util=ALL-UNNAMED"),
            ChildJvmCommandBuilder.curateModuleArgs(listOf("--add-opens", "java.base/java.util=ALL-UNNAMED")),
        )
        // A trailing flag with no following value (or a flag-looking next token) yields nothing.
        assertEquals(emptyList<String>(), ChildJvmCommandBuilder.curateModuleArgs(listOf("--add-opens")))
        assertEquals(emptyList<String>(), ChildJvmCommandBuilder.curateModuleArgs(listOf("--add-opens", "-javaagent:x")))
    }

    @Test
    @DisplayName("empty in -> empty out")
    fun emptyIsEmpty() {
        assertEquals(emptyList<String>(), ChildJvmCommandBuilder.curateModuleArgs(emptyList()))
    }

    @Test
    @DisplayName("parses the major Java version across legacy and modern schemes")
    fun parsesMajor() {
        assertEquals(8, ChildJvmCommandBuilder.parseMajor("1.8.0_412"))
        assertEquals(11, ChildJvmCommandBuilder.parseMajor("11.0.2"))
        assertEquals(17, ChildJvmCommandBuilder.parseMajor("\"17.0.11\""))
        assertEquals(17, ChildJvmCommandBuilder.parseMajor("17"))
        assertEquals(21, ChildJvmCommandBuilder.parseMajor("21.0.1+12"))
        assertNull(ChildJvmCommandBuilder.parseMajor(null))
        assertNull(ChildJvmCommandBuilder.parseMajor("  "))
    }
}
