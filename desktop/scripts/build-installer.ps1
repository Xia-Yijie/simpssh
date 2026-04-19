$ErrorActionPreference = "Stop"

$desktopRoot = Split-Path -Parent $PSScriptRoot
$repoRoot = Split-Path -Parent $desktopRoot
$releaseDir = Join-Path $repoRoot "release"
$appExe = Join-Path $desktopRoot "src-tauri\target\release\simpssh-desktop.exe"
$nsisScript = Join-Path $desktopRoot "packaging\simpssh-installer.nsi"
$tauriConf = Join-Path $desktopRoot "src-tauri\tauri.conf.json"

$version = (Get-Content -LiteralPath $tauriConf -Raw | ConvertFrom-Json).version

$cmd = Get-Command makensis.exe -ErrorAction SilentlyContinue
if ($cmd) {
  $makensis = $cmd.Source
} else {
  $makensis = Join-Path ${env:ProgramFiles(x86)} "NSIS\makensis.exe"
}
if (-not (Test-Path $makensis)) {
  throw "makensis.exe not found on PATH or at $makensis. Install NSIS: https://nsis.sourceforge.io/Download"
}

if (-not (Test-Path $appExe)) {
  throw "App executable not found: $appExe. Run 'npm run tauri build -- --no-bundle' first."
}

New-Item -ItemType Directory -Path $releaseDir -Force | Out-Null

& $makensis "/V3" "/DAPP_VERSION=$version" $nsisScript
if ($LASTEXITCODE -ne 0) {
  throw "makensis failed with exit code $LASTEXITCODE"
}
