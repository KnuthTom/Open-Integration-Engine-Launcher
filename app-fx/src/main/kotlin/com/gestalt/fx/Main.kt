/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.fx

import javafx.application.Application

/**
 * Entry point. Deliberately not an [Application] subclass: when JavaFX is on the classpath rather than
 * the module path (a jpackage'd image, a plain `java -cp` run) the JavaFX launcher refuses a main class
 * that extends Application; a plain main that calls [Application.launch] starts either way.
 */
fun main(args: Array<String>) {
    Application.launch(LauncherApp::class.java, *args)
}
