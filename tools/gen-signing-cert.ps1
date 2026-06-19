<#
.SYNOPSIS
  Bootstrap the Gestalt code-signing identity (Windows / PowerShell).

.DESCRIPTION
  Creates a self-signed RSA-4096 code-signing certificate via keytool and stores it in a
  PKCS12 keystore under tools\out\ (which is gitignored — the private key must never be
  committed). Also exports the public certificate as PEM so it can be used as a trust anchor.

    alias       : gestalt-signing
    key         : RSA 4096
    sig alg     : SHA384withRSA
    key usage   : digitalSignature (critical)
    ext key use : codeSigning
    validity    : 10 years (3650 days)
    keystore    : tools\out\gestalt-signing.p12  (PKCS12)
    public cert : tools\out\gestalt-signing.pem

  Override the keystore password with the GESTALT_SIGNING_STOREPASS environment variable
  (default: changeit).
#>
$ErrorActionPreference = 'Stop'

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$OutDir    = Join-Path $ScriptDir 'out'

$Alias     = 'gestalt-signing'
$DName     = 'CN=Gestalt Cloud Connect Code Signing, O=Gestalt Diagnostics, C=US'
$Keystore  = Join-Path $OutDir 'gestalt-signing.p12'
$CertPem   = Join-Path $OutDir 'gestalt-signing.pem'
$StorePass = if ($env:GESTALT_SIGNING_STOREPASS) { $env:GESTALT_SIGNING_STOREPASS } else { 'changeit' }

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

if (Test-Path $Keystore) {
    Write-Error "$Keystore already exists. Delete it first to regenerate."
}

Write-Host ">> Generating self-signed code-signing key pair (alias: $Alias)"
& keytool -genkeypair `
    -alias $Alias `
    -keyalg RSA -keysize 4096 `
    -sigalg SHA384withRSA `
    -validity 3650 `
    -dname $DName `
    -ext 'KeyUsage:critical=digitalSignature' `
    -ext 'ExtendedKeyUsage=codeSigning' `
    -storetype PKCS12 `
    -keystore $Keystore `
    -storepass $StorePass `
    -keypass $StorePass
if ($LASTEXITCODE -ne 0) { throw "keytool -genkeypair failed ($LASTEXITCODE)" }

Write-Host ">> Exporting public certificate to PEM"
& keytool -exportcert -rfc `
    -alias $Alias `
    -keystore $Keystore `
    -storetype PKCS12 `
    -storepass $StorePass `
    -file $CertPem
if ($LASTEXITCODE -ne 0) { throw "keytool -exportcert failed ($LASTEXITCODE)" }

Write-Host ""
Write-Host ">> Done."
Write-Host "   keystore : $Keystore"
Write-Host "   cert     : $CertPem"
Write-Host ""
Write-Host ">> Certificate summary:"
& keytool -printcert -file $CertPem | Select-Object -First 12
