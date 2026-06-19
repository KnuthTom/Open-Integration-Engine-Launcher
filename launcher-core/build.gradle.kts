/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `java-library`
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // The trust engine is the signature half of the single verification gate.
    api(project(":core-trust"))

    // All https — including any OCSP/CRL fetch — uses HttpClient 5. There is no HttpClient 4 here.
    implementation(libs.httpclient5)

    // Audit logging.
    implementation(libs.log4j.api)
    runtimeOnly(libs.log4j.core)

    // Windows DPAPI credential storage (Crypt32). macOS/Linux shell out to the OS keychain CLIs.
    implementation(libs.jna)
    implementation(libs.jna.platform)

    // connections.json — non-secret connection metadata only.
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    // log4j-core on the test classpath so the AUDIT logger has a backend during tests.
    testRuntimeOnly(libs.log4j.core)
}

tasks.test {
    useJUnitPlatform()
    // Keep all per-user state (connections.json, credential blobs, logs/audit.log) under build/
    // during tests so the suite never touches the real user profile.
    systemProperty("gestalt.home", layout.buildDirectory.dir("test-gestalt-home").get().asFile.absolutePath)
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = true
    }
}
