#Requires -Version 5.1
<#
.SYNOPSIS
    Build all Amaya release artifacts and collect them into <root>/release/.

.DESCRIPTION
    Builds:
      1. Android APK  (release, signed via .env.local keystore)
      2. Windows Bridge Electron NSIS installer (.exe)
      3. VS Code extension (.vsix)

    All output files are copied to <root>/release/ with clear names.

.USAGE
    From the repo root:
        powershell -ExecutionPolicy Bypass -File scripts\build-release.ps1

    Optional flags:
        -SkipApk        Skip Android build
        -SkipBridge     Skip Windows Bridge build
        -SkipExtension  Skip VS Code extension build
        -Clean          Delete <root>/release/ before building
#>

param(
    [switch]$SkipApk,
    [switch]$SkipBridge,
    [switch]$SkipExtension,
    [switch]$Clean
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$root       = Split-Path -Parent $PSScriptRoot
$releaseDir = Join-Path $root 'release'

function Step([string]$msg) {
    Write-Host ""
    Write-Host "------------------------------------------------------------" -ForegroundColor Cyan
    Write-Host "  $msg" -ForegroundColor Cyan
    Write-Host "------------------------------------------------------------" -ForegroundColor Cyan
}

function Ok([string]$msg)   { Write-Host "  [OK]   $msg" -ForegroundColor Green }
function Warn([string]$msg) { Write-Host "  [WARN] $msg" -ForegroundColor Yellow }
function Fail([string]$msg) { Write-Host "  [FAIL] $msg" -ForegroundColor Red; exit 1 }

function CopyArtifact([string]$src, [string]$destName) {
    if (-not (Test-Path $src)) {
        Fail "Expected artifact not found: $src"
    }
    $dest    = Join-Path $releaseDir $destName
    Copy-Item -Path $src -Destination $dest -Force
    $sizeMB  = [math]::Round((Get-Item $dest).Length / 1MB, 1)
    Ok "Collected $destName ($sizeMB MB)"
}

# ---- setup ------------------------------------------------------------------

if ($Clean -and (Test-Path $releaseDir)) {
    Step "Cleaning release/"
    Remove-Item -Recurse -Force $releaseDir
    Ok "Cleaned"
}

New-Item -ItemType Directory -Path $releaseDir -Force | Out-Null

$summary = @()

# ---- 1. Android APK ---------------------------------------------------------

if (-not $SkipApk) {
    Step "Building Android APK (release)"

    $envFile = Join-Path $root '.env.local'
    $keystoreFile = Join-Path $root 'release.keystore'
    if (-not (Test-Path $envFile)) { Fail "Android distribution requires .env.local" }
    if (-not (Test-Path $keystoreFile)) { Fail "Android distribution requires release.keystore" }
    $releaseSecrets = Get-Content $envFile -Raw
    if ($releaseSecrets -notmatch '(?m)^AMAYA_KEYSTORE_PASSWORD=.+$' -or
        $releaseSecrets -notmatch '(?m)^AMAYA_KEY_ALIAS=.+$') {
        Fail "Android distribution requires AMAYA_KEYSTORE_PASSWORD and AMAYA_KEY_ALIAS"
    }

    Push-Location $root
    try {
        & .\gradlew.bat assembleRelease --quiet --console=plain
        if ($LASTEXITCODE -ne 0) { Fail "Gradle assembleRelease failed (exit $LASTEXITCODE)" }
    } finally {
        Pop-Location
    }

    $apkDir  = Join-Path $root 'app\build\outputs\apk\release'
    $apkFile = Get-ChildItem -Path $apkDir -Filter '*.apk' -ErrorAction SilentlyContinue |
               Sort-Object LastWriteTime -Descending | Select-Object -First 1

    if (-not $apkFile) { Fail "No APK found in $apkDir" }

    CopyArtifact $apkFile.FullName 'amaya-android.apk'
    $summary += "APK:       release/amaya-android.apk"
}

# ---- 2. Windows Bridge (NSIS installer) -------------------------------------

if (-not $SkipBridge) {
    Step "Building Windows Bridge (Electron NSIS installer)"

    $bridgeDir = Join-Path $root 'windows-bridge'
    Push-Location $bridgeDir
    try {
        & npm run package
        if ($LASTEXITCODE -ne 0) { Fail "npm run package failed (exit $LASTEXITCODE)" }
    } finally {
        Pop-Location
    }

    $exeFile = Get-ChildItem -Path (Join-Path $bridgeDir 'release') -Filter '*.exe' -ErrorAction SilentlyContinue |
               Where-Object { $_.Name -notlike '*unpacked*' } |
               Sort-Object LastWriteTime -Descending | Select-Object -First 1

    if (-not $exeFile) { Fail "No NSIS installer found in windows-bridge/release/" }

    CopyArtifact $exeFile.FullName 'amaya-windows-bridge-setup.exe'
    $summary += "Bridge:    release/amaya-windows-bridge-setup.exe"
}

# ---- 3. VS Code extension (.vsix) -------------------------------------------

if (-not $SkipExtension) {
    Step "Building VS Code extension (.vsix)"

    $extDir = Join-Path $root 'amaya-remote-extension'
    Push-Location $extDir
    try {
        & npm run compile
        if ($LASTEXITCODE -ne 0) { Fail "npm run compile failed (exit $LASTEXITCODE)" }

        & npx @vscode/vsce package --no-dependencies --out amaya-remote.vsix
        if ($LASTEXITCODE -ne 0) { Fail "vsce package failed (exit $LASTEXITCODE)" }
    } finally {
        Pop-Location
    }

    $vsixPath = Join-Path $extDir 'amaya-remote.vsix'
    if (-not (Test-Path $vsixPath)) {
        $vsixPath = Get-ChildItem -Path $extDir -Filter '*.vsix' |
                    Sort-Object LastWriteTime -Descending |
                    Select-Object -First 1 -ExpandProperty FullName
    }
    if (-not $vsixPath) { Fail "No .vsix found in $extDir" }

    CopyArtifact $vsixPath 'amaya-remote-extension.vsix'
    $summary += "Extension: release/amaya-remote-extension.vsix"
}

# ---- summary ----------------------------------------------------------------

Step "Build complete"
foreach ($line in $summary) {
    Ok $line
}
Write-Host ""
Write-Host "  All artifacts collected in: $releaseDir" -ForegroundColor White
Write-Host ""
