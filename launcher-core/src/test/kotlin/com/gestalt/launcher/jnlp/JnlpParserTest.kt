/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.jnlp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class JnlpParserTest {

    private val benign = """
        <?xml version="1.0" encoding="UTF-8"?>
        <jnlp spec="1.0+" codebase="https://host/app" version="1.2.3">
          <resources>
            <j2se version="17" java-vm-args="-Xmx512m"/>
            <jar href="lib/mirth-client-core.jar" main="true"/>
            <jar href="ext/foo.jar"/>
          </resources>
          <application-desc main-class="com.mirth.connect.client.ui.Mirth">
            <argument>-server</argument>
          </application-desc>
        </jnlp>
    """.trimIndent()

    private val weaponized = """
        <?xml version="1.0" encoding="UTF-8"?>
        <jnlp spec="1.0+" codebase="https://host/app" version="1.2.3">
          <security><allow-self-signed/></security>
          <resources>
            <property name="allow-incorrect-digest" value="true"/>
            <property name="disable-verification" value="true"/>
            <jar href="lib/mirth-client-core.jar" main="true"/>
            <jar href="ext/foo.jar"/>
          </resources>
          <application-desc main-class="com.mirth.connect.client.ui.Mirth">
            <argument>-server</argument>
          </application-desc>
        </jnlp>
    """.trimIndent()

    @Test
    @DisplayName("parses only jars / main-class / arguments / version")
    fun parsesSafeProjection() {
        val d = JnlpParser.parse(benign)
        assertEquals(listOf("lib/mirth-client-core.jar", "ext/foo.jar"), d.jars.map { it.href })
        assertEquals(true, d.mainJar?.main)
        assertEquals("com.mirth.connect.client.ui.Mirth", d.mainClass)
        assertEquals(listOf("-server"), d.arguments)
        assertEquals("1.2.3", d.version)
        assertTrue(d.ignoredDirectives.isEmpty())
        // server JVM args are captured for transparency but never applied to the child JVM
        assertTrue(d.untrustedJvmArgs.contains("-Xmx512m"))
    }

    @Test
    @DisplayName("server 'allow-self-signed' / 'allow-incorrect-digest' directives are detected but inert")
    fun weakeningDirectivesAreDetectedButInert() {
        val good = JnlpParser.parse(benign)
        val bad = JnlpParser.parse(weaponized)

        // Same jars / main-class extracted regardless of the directives — they change nothing.
        assertEquals(good.jars, bad.jars)
        assertEquals(good.mainClass, bad.mainClass)

        // They surface only as ignored, and the descriptor has no field for them to flow into.
        assertTrue(bad.ignoredDirectives.isNotEmpty())
        assertTrue(
            bad.ignoredDirectives.any {
                it.contains("allow-self-signed") || it.contains("allow-incorrect-digest") || it.contains("disable-verification")
            },
        ) { "directives: ${bad.ignoredDirectives}" }
    }

    @Test
    @DisplayName("parses the per-jar inline sha256 digest (the real OIE/Mirth integrity source)")
    fun parsesInlineSha256() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <jnlp spec="1.0+" codebase="http://localhost:8080" version="4.5.2">
              <resources>
                <jar href="webstart/client-lib/mirth-client.jar" main="true" sha256="Bpv4LHN1+aRsffHWwl6ZiJ4H2YlWfeFm4ewBDK82MfA="/>
                <jar href="webstart/client-lib/other.jar"/>
              </resources>
              <application-desc main-class="com.mirth.connect.client.ui.Mirth"/>
            </jnlp>
        """.trimIndent()
        val d = JnlpParser.parse(xml)
        assertEquals("Bpv4LHN1+aRsffHWwl6ZiJ4H2YlWfeFm4ewBDK82MfA=", d.mainJar?.sha256)
        assertEquals(null, d.jars[1].sha256) { "a jar without the attribute carries no digest" }
    }

    @Test
    @DisplayName("parses <extension href> component descriptors (one per installed Mirth extension)")
    fun parsesExtensionDescriptors() {
        // The shape a stock Mirth Connect server emits: core jars inline, then one <extension> per plugin.
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <jnlp spec="1.0+" codebase="https://localhost:8443" href="webstart.jnlp">
              <resources>
                <j2se version="1.9+" java-vm-args="--add-modules=java.sql.rowset"/>
                <jar href="webstart/client-lib/mirth-client.jar" main="true" download="eager" sha256="Bpv4LHN1+aRsffHWwl6ZiJ4H2YlWfeFm4ewBDK82MfA="/>
                <extension href="webstart/extensions/datatypes.jnlp"/>
                <extension href="webstart/extensions/mllpconnector.jnlp"/>
                <extension href=""/>
              </resources>
              <application-desc main-class="com.mirth.connect.client.ui.Mirth">
                <argument>https://localhost:8443</argument>
                <argument>4.4.2</argument>
              </application-desc>
            </jnlp>
        """.trimIndent()
        val d = JnlpParser.parse(xml)
        assertEquals(listOf("webstart/extensions/datatypes.jnlp", "webstart/extensions/mllpconnector.jnlp"), d.extensions) {
            "every non-blank extension href, in document order"
        }
        assertEquals(1, d.jars.size) { "the extension elements themselves are not jars" }
        assertEquals(listOf("https://localhost:8443", "4.4.2"), d.arguments)
    }

    @Test
    @DisplayName("a component descriptor (extension JNLP) parses with jars but no main-class or arguments")
    fun parsesComponentDescriptor() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <jnlp spec="1.0+" codebase="https://localhost:8443" href="webstart/extensions/datatypes.jnlp">
              <resources>
                <jar href="webstart/extensions/libs/datatypes/datatypes-client.jar" sha256="Bpv4LHN1+aRsffHWwl6ZiJ4H2YlWfeFm4ewBDK82MfA="/>
              </resources>
              <component-desc/>
            </jnlp>
        """.trimIndent()
        val d = JnlpParser.parse(xml)
        assertEquals(listOf("webstart/extensions/libs/datatypes/datatypes-client.jar"), d.jars.map { it.href })
        assertEquals(null, d.mainClass)
        assertTrue(d.arguments.isEmpty())
        assertTrue(d.extensions.isEmpty())
    }

    @Test
    @DisplayName("XXE-hardened: a JNLP with a DOCTYPE/entity is rejected (no external entity expansion)")
    fun rejectsDoctypeXxe() {
        val xxe = """
            <?xml version="1.0"?>
            <!DOCTYPE jnlp [ <!ENTITY x SYSTEM "file:///etc/passwd"> ]>
            <jnlp><resources><jar href="a.jar"/></resources></jnlp>
        """.trimIndent()
        assertThrows(Exception::class.java) { JnlpParser.parse(xxe) }
    }
}
