/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

// The product UI, rebuilt on JavaFX (the design's build notes target "Java 17+ / JavaFX 17+": stock
// controls and JavaFX CSS, no WebView, no icon fonts). JavaFX 21 is the newest line that runs on the
// JDK 17 runtime the launcher already ships with. This module sits beside :app (Compose) until it
// reaches parity, then :app and :design-system retire.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.javafx)
    application
}

kotlin {
    jvmToolchain(17)
}

javafx {
    version = libs.versions.javafx.get()
    modules = listOf("javafx.controls", "javafx.graphics", "javafx.base")
}

application {
    // A plain `main` (not an Application subclass) so the app also starts from a classpath launch —
    // the shape jpackage produces — without the "JavaFX runtime components are missing" check.
    mainClass.set("com.gestalt.fx.MainKt")
    applicationDefaultJvmArgs = listOf("-Dfile.encoding=UTF-8")
}

val appVersion = (findProperty("gestaltVersion") as String?) ?: "1.0.0"

dependencies {
    // The fail-closed launch path, connection model/store, trust stores, credentials, audit.
    implementation(project(":launcher-core"))
    implementation(project(":core-trust"))

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    // log4j-core on the test classpath so the AUDIT logger has a backend during tests.
    testRuntimeOnly(libs.log4j.core)
}

// Version shown in the title bar; single-sourced from gradle.properties like the SBOM and installers.
tasks.processResources {
    inputs.property("version", appVersion)
    filesMatching("com/gestalt/fx/app-fx.properties") {
        expand("version" to appVersion)
    }
}

tasks.test {
    useJUnitPlatform()
    // Keep any per-user state under build/ during tests so the suite never touches the real profile.
    systemProperty("gestalt.home", layout.buildDirectory.dir("test-gestalt-home").get().asFile.absolutePath)
    // Model tests run headless: no toolkit, no window.
    systemProperty("java.awt.headless", "true")
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
