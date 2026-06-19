/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
    `java-library`
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // The design system's public API exposes Compose types (Color, Dp, TextStyle, Painter, …) in the
    // token data classes, so the Compose runtime/foundation/ui are part of the API surface.
    api(compose.runtime)
    api(compose.foundation)
    api(compose.ui)
    // Desktop backing (skiko) — needed for SVG painter loading used by the icon registry.
    implementation(compose.desktop.currentOs)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
