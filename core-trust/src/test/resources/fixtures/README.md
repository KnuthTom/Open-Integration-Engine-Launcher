# Test fixtures

The verifier tests run against real signed jars. Those jars are **not committed** (they are large
and/or proprietary), so they are gitignored. Each test that needs a fixture **skips with a clear
message** when the file is absent — so a clean checkout still builds green.

Drop the following into this directory to light up the full suite:

| file                         | how to produce it                                                            |
|------------------------------|------------------------------------------------------------------------------|
| `oie-mirth-client-core.jar`  | copy the genuine OIE jar (see below)                                         |
| `gestalt-signed.jar`         | sign a small jar with the Gestalt identity (see below)                      |
| `anchors/gestalt-signing.pem`| copy `tools/out/gestalt-signing.pem` here                                   |
| `tampered.jar`               | the OIE jar with one class byte flipped (see below)                         |
| `unsigned.jar`               | any unsigned jar (see below)                                                |

The committed public roots used as trust anchors live one level up, in
`core-trust/src/test/resources/anchors/` (Sectigo Public Code Signing Root R46 + USERTrust RSA).

---

### `oie-mirth-client-core.jar` — the genuine OIE jar

```bash
cp "/c/Program Files/OpenIntegrationEngine/client-lib/mirth-client-core.jar" \
   core-trust/src/test/resources/fixtures/oie-mirth-client-core.jar
```

Expectation: validates in `PIN_KNOWN_SIGNERS` mode, `signerDN` contains `BrightCodeCompany`,
`usedTimestamp == true`.

### `gestalt-signed.jar` + `anchors/gestalt-signing.pem`

```bash
./tools/gen-signing-cert.sh                       # creates tools/out/gestalt-signing.{p12,pem}

printf 'hello' > /tmp/hello.txt
jar cf /tmp/small.jar -C /tmp hello.txt

jarsigner -keystore tools/out/gestalt-signing.p12 -storetype PKCS12 -storepass changeit \
  -digestalg SHA-256 -sigalg SHA384withRSA -tsa http://timestamp.digicert.com \
  -signedjar core-trust/src/test/resources/fixtures/gestalt-signed.jar /tmp/small.jar gestalt-signing

cp tools/out/gestalt-signing.pem core-trust/src/test/resources/fixtures/anchors/gestalt-signing.pem
```

### `tampered.jar` — one flipped byte

Rebuild the OIE jar with a single byte changed inside one class. The MANIFEST is left untouched,
so the per-entry digest no longer matches → the verifier must reject it.

```python
# python3 make-tampered.py
import zipfile
src = "core-trust/src/test/resources/fixtures/oie-mirth-client-core.jar"
dst = "core-trust/src/test/resources/fixtures/tampered.jar"
with zipfile.ZipFile(src) as zin:
    names = zin.namelist()
    target = next(n for n in names if n.endswith(".class"))
    with zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as zout:
        for n in names:
            data = zin.read(n)
            if n == target:
                b = bytearray(data); b[len(b)//2] ^= 0xFF; data = bytes(b)
            zout.writestr(zin.getinfo(n), data)
print("flipped a byte in", target)
```

### `unsigned.jar`

```bash
printf 'noop' > /tmp/noop.txt
jar cf core-trust/src/test/resources/fixtures/unsigned.jar -C /tmp noop.txt
```
