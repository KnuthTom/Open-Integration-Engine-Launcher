# Contributing

Thanks for your interest! This is a community-maintained, open-source launcher for the Open
Integration Engine (OIE) / Mirth Connect Administrator. Issues, discussion, and pull requests are all
welcome — and **co-maintainers especially so** (the originator is stepping back).

By contributing you agree that your contributions are licensed under the project's
[Mozilla Public License 2.0](LICENSE).

## Ground rules (please read)

1. **Keep it clean-room.** This project contains **no** Mirth Connect / NextGen source and bundles
   **no** proprietary client binaries. Never add decompiled, copied, or derived Mirth/NextGen code, or
   a vendored Administrator jar. The launcher verifies and runs the client *your server* serves.
2. **No real connection data.** Code, tests, fixtures, docs, and screenshots must use **synthetic**
   values only — `localhost`, `127.0.0.1`, RFC-2606 domains (`example.com`, `example.org`),
   `*.example.internal`, and fake org names like "Example Lab". No real hostnames, IPs, customer/org
   names, or credentials.
3. **No secrets.** Never commit keystores, private keys, `.pfx`/`.p12`, tokens, or `.env` files (the
   `.gitignore` and a CI gitleaks scan guard this). Synthetic test values live in `.gitleaks.toml`.

## Getting started

Requires **JDK 17** (the Gradle toolchain pins it).

```bash
./gradlew build            # compile + run the full test suite
./gradlew :app:run         # launch the product UI
./gradlew :app:run --args="--gallery"   # the design-system gallery
```

## Project layout

| module           | what it is                                                                       |
|------------------|----------------------------------------------------------------------------------|
| `:core-trust`    | Pure jar-signature verification engine (CMS / PKIX / EKU / RFC-3161). No UI.      |
| `:launcher-core` | The fail-closed launch path: the single verification gate, TLS pinning, import, credentials, audit, updates. |
| `:design-system` | Token-driven Compose theming engine, themed components, icon + branding shells.  |
| `:app`           | Compose Desktop product UI (connection list / form / import-export / launch).    |

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the trust pipeline and where things live.

## Pull requests

- Fork, branch off `main`, and open a PR against `main`.
- Keep the existing tests green and **add tests** for new behavior — especially anything touching the
  verification gate, TLS, import, or update verification.
- Match the style of the surrounding code (Kotlin, expressive names, comments that explain *why*).
- Each source file carries the standard MPL-2.0 header; new files should too.
- A [DCO](https://developercertificate.org/) sign-off (`git commit -s`) is appreciated.
- Describe the change and how you verified it; the PR template will prompt you.

## Reporting bugs & security issues

- **Bugs / features:** open an issue (templates provided).
- **Security vulnerabilities:** please report **privately** — see [SECURITY.md](SECURITY.md). Do not
  open a public issue for a vulnerability.
