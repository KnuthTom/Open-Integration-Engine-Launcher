# tools — signing-identity bootstrap

These scripts create the **Gestalt code-signing identity** and document how to sign and verify
jars with it. Everything they produce lands in `tools/out/`, which is **gitignored** — the
private key (PKCS12 keystore) must never be committed.

## 1. Generate the signing identity

```bash
# macOS / Linux / Git Bash
./tools/gen-signing-cert.sh
```

```powershell
# Windows PowerShell
.\tools\gen-signing-cert.ps1
```

This produces, under `tools/out/`:

| file                    | what it is                                            |
|-------------------------|-------------------------------------------------------|
| `gestalt-signing.p12`   | PKCS12 keystore holding the private key (**secret**)  |
| `gestalt-signing.pem`   | the public code-signing certificate (a trust anchor)  |

Certificate properties: alias `gestalt-signing`, RSA-4096, `SHA384withRSA`,
`KeyUsage=digitalSignature` (critical), `ExtendedKeyUsage=codeSigning`, 10-year validity.

The keystore password defaults to `changeit`; override it by exporting
`GESTALT_SIGNING_STOREPASS` before running.

## 2. Sign a jar (with RFC-3161 timestamping)

Timestamping is **required**: it lets a signature remain valid after the signing certificate
expires, because the verifier checks the cert's validity at the timestamp instant rather than
"now". Use DigiCert's public TSA:

```bash
jarsigner \
  -keystore   tools/out/gestalt-signing.p12 \
  -storetype  PKCS12 \
  -storepass  changeit \
  -digestalg  SHA-256 \
  -sigalg     SHA384withRSA \
  -tsa        http://timestamp.digicert.com \
  -signedjar  gestalt-signed.jar \
  input.jar \
  gestalt-signing
```

- `-tsa http://timestamp.digicert.com` embeds the RFC-3161 timestamp token.
- `-signedjar gestalt-signed.jar` writes the signed copy (leave it off to sign in place).
- `gestalt-signing` is the key alias (last positional argument).

## 3. Verify a jar

Quick check with the JDK tool:

```bash
jarsigner -verify -verbose -certs gestalt-signed.jar
# look for "jar verified." and a "signed by" / "timestamped by" line
```

Authoritative check with the Gestalt engine (`:core-trust`) — this is what the launcher uses,
and unlike `jarsigner -verify` it pins the publisher and enforces the codeSigning EKU:

```kotlin
val trust = TrustStore.fromPem(File("tools/out/gestalt-signing.pem"))
val result = JarSignatureVerifier(trust, VerifierConfig.caRoot()).verify(File("gestalt-signed.jar"))
println(result)   // valid=true, signerDN=CN=Gestalt Cloud Connect Code Signing,..., usedTimestamp=true
```

## Notes

- `tools/out/` is gitignored. If you need the public cert as a test anchor, copy
  `tools/out/gestalt-signing.pem` to
  `core-trust/src/test/resources/fixtures/anchors/gestalt-signing.pem`.
- This is a **self-signed** identity for development and for Gestalt's own artifacts. Production
  signing with a publicly-trusted CA cert is a separate, later concern.
