#!/usr/bin/env bash
#
# Bootstrap the Gestalt code-signing identity.
#
# Creates a self-signed RSA-4096 code-signing certificate via keytool and stores it in a
# PKCS12 keystore under tools/out/ (which is gitignored — the private key must never be
# committed). Also exports the public certificate as PEM so it can be used as a trust anchor.
#
#   alias       : gestalt-signing
#   key         : RSA 4096
#   sig alg     : SHA384withRSA
#   key usage   : digitalSignature (critical)
#   ext key use : codeSigning
#   validity    : 10 years (3650 days)
#   keystore    : tools/out/gestalt-signing.p12  (PKCS12)
#   public cert : tools/out/gestalt-signing.pem
#
# Override the keystore password with GESTALT_SIGNING_STOREPASS (default: changeit).
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT_DIR="$SCRIPT_DIR/out"

ALIAS="gestalt-signing"
DNAME="CN=Gestalt Cloud Connect Code Signing, O=Gestalt Diagnostics, C=US"
KEYSTORE="$OUT_DIR/gestalt-signing.p12"
CERT_PEM="$OUT_DIR/gestalt-signing.pem"
STOREPASS="${GESTALT_SIGNING_STOREPASS:-changeit}"

mkdir -p "$OUT_DIR"

if [[ -f "$KEYSTORE" ]]; then
  echo "!! $KEYSTORE already exists. Delete it first to regenerate. Aborting." >&2
  exit 1
fi

echo ">> Generating self-signed code-signing key pair (alias: $ALIAS)"
keytool -genkeypair \
  -alias "$ALIAS" \
  -keyalg RSA -keysize 4096 \
  -sigalg SHA384withRSA \
  -validity 3650 \
  -dname "$DNAME" \
  -ext "KeyUsage:critical=digitalSignature" \
  -ext "ExtendedKeyUsage=codeSigning" \
  -storetype PKCS12 \
  -keystore "$KEYSTORE" \
  -storepass "$STOREPASS" \
  -keypass "$STOREPASS"

echo ">> Exporting public certificate to PEM"
keytool -exportcert -rfc \
  -alias "$ALIAS" \
  -keystore "$KEYSTORE" \
  -storetype PKCS12 \
  -storepass "$STOREPASS" \
  -file "$CERT_PEM"

echo
echo ">> Done."
echo "   keystore : $KEYSTORE"
echo "   cert     : $CERT_PEM"
echo
echo ">> Certificate summary:"
keytool -printcert -file "$CERT_PEM" | sed -n '1,12p'
