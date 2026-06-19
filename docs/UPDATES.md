# Signed self-update (Phase 4b)

Open Integration Engine Launcher checks a **signed update manifest** and, only if everything verifies, offers an
update. It is **fail-closed**: any signature, integrity, or version problem aborts and changes nothing.

- **Installed** builds update **in place**: download the signed installer, verify it, launch it to
  upgrade, and exit.
- **Portable** builds **check-and-notify**: show the new version + notes + a download link; and if the
  app folder is writable, apply in place via a small swap helper.

Installed vs portable is detected from the Phase-4a layout (the `gestalt-portable.txt` sentinel →
`AppPaths.isPortable`).

---

## The manifest (`<channel>/latest.json`)

```json
{
  "version": "1.1.0",
  "notes": "Fixes the digest manifest signing; adds RPM packaging.",
  "perOS": {
    "windows-x64":          { "url": "https://updates.example.com/gestalt/stable/GestaltCloudConnect-1.1.0.msi",            "sha256": "…" },
    "windows-x64-portable": { "url": "https://updates.example.com/gestalt/stable/GestaltCloudConnect-1.1.0-portable-windows-x64.zip", "sha256": "…" },
    "macos-arm64":          { "url": "https://updates.example.com/gestalt/stable/GestaltCloudConnect-1.1.0.dmg",            "sha256": "…" }
  },
  "signature": "<base64 Ed25519 signature>"
}
```

- **Keys** are `"<os>-<arch>"` for installers and `"<os>-<arch>-portable"` for portable archives — the
  same tokens the packaging build uses. Installed builds look up the former, portable builds the latter.
- **`signature`** is base64 Ed25519 over a canonical, length-prefixed encoding of every other field
  (a domain tag, `version`, `notes`, and each sorted `(os-arch, url, sha256)` triple — see
  `UpdateCrypto.signableBytes`). It is computed over the parsed object, so key order / whitespace /
  unknown extra keys don't affect verification. The `signature` field itself is excluded.
- The app pins the Gestalt **update public key**(s) in `UpdateKeys`; a manifest is trusted only if its
  signature verifies against one of them. This is independent of the Layer-2 jar-signing trust.

---

## What the app does (fail-closed)

On launch (if a channel is set) and via **Settings → Updates → Check for updates**:

1. **Fetch** `<channel>/latest.json` over the launcher's hardened HTTPS (modern TLS, hostname-verified).
2. **Verify** the Ed25519 signature against a pinned public key. *Fail → reject, nothing applied.*
3. **Compare versions** — apply only if strictly newer (no downgrade, no re-apply). *Equal/older → up to date.*
4. **Pick the artifact** for this platform (`…` or `…-portable`). *Missing → "no build for this platform" (benign).*
5. **Apply** (only from step-4 success):
   - **Installed:** download the installer, **verify its signed SHA-256** (download is rejected on any
     mismatch), then launch it (`msiexec /i …` for an MSI) to upgrade in place, and exit.
   - **Portable + writable:** download + verify the archive, stage it, and hand off to a helper that
     waits for the app to exit, mirrors the new files over the app folder (**preserving `./data`**), and
     relaunches.
   - **Portable + read-only:** notify only, with a download link.

Every check, rejection, download, and apply is written to `logs/audit.log` (`event=update.check` /
`update.reject` / `update.download` / `update.apply`). An update that does not fully verify is **never**
applied.

Where checkable, the artifact's own OS signature is an *additional* line of defense (Authenticode on
Windows), but the manifest signature + signed SHA-256 are the authority enforced here.

---

## Publishing a release (the hosting is yours to stand up)

1. **Build + sign** the artifacts — [PACKAGING.md](PACKAGING.md) + [SIGNING.md](SIGNING.md).
2. **Generate the update keypair once** (dev shown; production key is a release secret):
   ```
   gradle :app:generateUpdateKey          # -> tools/out/update-{private,public}.b64
   ```
   Pin the **public** key in `launcher-core/.../update/UpdateKeys.kt`. Keep the **private** key secret.
3. **Sign the manifest** for the release (reuses the app's exact crypto):
   ```
   gradle :app:signUpdateManifest --args="\
     --key tools/out/update-private.b64 \
     --version 1.1.0 --notes 'What changed' \
     --base-url https://updates.example.com/gestalt/stable \
     --artifact windows-x64=build/compose/binaries/main/msi/GestaltCloudConnect-1.1.0.msi \
     --artifact windows-x64-portable=build/distributions/GestaltCloudConnect-1.1.0-portable-windows-x64.zip \
     --out build/update/latest.json"
   ```
4. **Upload** `latest.json` and the artifacts to the channel URL. Point users at it in
   **Settings → Updates → Update channel URL** (e.g. `https://updates.example.com/gestalt/stable`).

---

## Key rotation

The pinned key list in `UpdateKeys` accepts **multiple** public keys. To rotate without locking out
existing clients:

1. Generate the new keypair.
2. Ship an **app update** (signed with the *current* key) that **adds the new public key** to `UpdateKeys`.
3. Once that update has propagated, switch the release signer to the **new private key**. Clients on the
   newer build already trust it; the old key can be dropped in a later release.

Never switch signing keys before the new public key has shipped — manifests signed by an unrecognized
key are (correctly) rejected.

---

## Fail-closed guarantees (unit-tested)

`UpdateServiceTest` / `UpdateCryptoTest` assert:
- a newer, correctly-signed manifest → **Available** with the right artifact;
- a manifest signed by an **untrusted key** → rejected;
- a **tampered** manifest field (signature no longer matches) → rejected;
- a downloaded artifact whose **SHA-256 mismatches** the signed value → integrity error, nothing kept;
- a **downgrade / equal** version → never offered;
- update checks write an **audit** entry.
