<#
.SYNOPSIS
  Generate a SELF-SIGNED Windows code-signing certificate for DEV signing of Gestalt Cloud Connect
  installers/executables, and export it to a password-protected .pfx.

.DESCRIPTION
  Dev/internal only. A self-signed code-signing cert is NOT chained to a public root, so SmartScreen /
  "Unknown publisher" warnings are expected on other machines — that is fine for internal testing. For
  production, obtain an OV/EV code-signing certificate from a CA and supply IT as the .pfx via secrets;
  no code changes are needed (see docs/SIGNING.md). The exported .pfx and its password are secrets:
  they land in tools/out/ (gitignored) and must never be committed.

.EXAMPLE
  ./tools/gen-win-codesign-cert.ps1 -Password 'dev-pfx-pw'
#>
param(
    [string]$Subject  = "CN=Gestalt Cloud Connect (Dev), O=Gestalt Diagnostics, C=US",
    [string]$Password = "gestalt-dev",
    [string]$OutDir   = "$PSScriptRoot/out"
)
$ErrorActionPreference = 'Stop'
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

$cert = New-SelfSignedCertificate `
    -Type CodeSigningCert `
    -Subject $Subject `
    -CertStoreLocation 'Cert:\CurrentUser\My' `
    -KeyExportPolicy Exportable `
    -KeyUsage DigitalSignature `
    -KeySpec Signature `
    -HashAlgorithm SHA256 `
    -NotAfter (Get-Date).AddYears(3)

$pfxPath = Join-Path $OutDir 'gestalt-dev-codesign.pfx'
$secure  = ConvertTo-SecureString -String $Password -Force -AsPlainText
Export-PfxCertificate -Cert $cert -FilePath $pfxPath -Password $secure | Out-Null

Write-Host "Code-signing certificate created (DEV / self-signed):"
Write-Host "  Subject    : $Subject"
Write-Host "  Thumbprint : $($cert.Thumbprint)"
Write-Host "  PFX        : $pfxPath   (SECRET - gitignored)"
Write-Host ""
Write-Host "Use it with the signing step, e.g.:"
Write-Host "  gradle :app:signWindows -Pgestalt.win.pfx=`"$pfxPath`" -Pgestalt.win.pfxPassword=`"$Password`""
