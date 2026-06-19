/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.credential

/**
 * Linux credential store backed by the Secret Service (GNOME Keyring / KWallet) via libsecret's
 * `secret-tool` CLI.
 *
 * Unlike the macOS `security` tool, `secret-tool store` reads the secret from **stdin**, so the
 * password is never placed on the command line. Items are keyed by the attributes
 * `service=GestaltCloudConnect account=<accountKey>`.
 */
class LinuxSecretCredentialStore : CredentialStore {
    override val name: String = "linux-libsecret"

    private val service = "GestaltCloudConnect"
    private val secretTool = "secret-tool"

    override fun store(connectionId: String, username: String, password: CharArray) {
        val account = CredentialStores.accountKey(connectionId, username)
        val r = Shell.run(
            listOf(secretTool, "store", "--label=GestaltCloudConnect", "service", service, "account", account),
            stdin = password,
        )
        check(r.exitCode == 0) { "secret-tool store failed (${r.exitCode}): ${r.stderr.trim()}" }
    }

    override fun retrieve(connectionId: String, username: String): CharArray? {
        val account = CredentialStores.accountKey(connectionId, username)
        val r = Shell.run(listOf(secretTool, "lookup", "service", service, "account", account))
        if (r.exitCode != 0) return null
        // Decode the captured bytes straight into a zeroable CharArray — no String holds the secret.
        return Shell.decodeSecret(r.stdout)
    }

    override fun delete(connectionId: String, username: String) {
        val account = CredentialStores.accountKey(connectionId, username)
        Shell.run(listOf(secretTool, "clear", "service", service, "account", account))
    }
}
