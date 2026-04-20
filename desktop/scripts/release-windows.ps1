$ErrorActionPreference = "Stop"

$desktopRoot = Split-Path -Parent $PSScriptRoot
$repoRoot = Split-Path -Parent $desktopRoot
$tauriConf = Join-Path $desktopRoot "src-tauri\tauri.conf.json"
$webview2Path = Join-Path $desktopRoot "src-tauri\MicrosoftEdgeWebview2Setup.exe"
$webview2Url = "https://go.microsoft.com/fwlink/p/?LinkId=2124703"
$appExe = Join-Path $desktopRoot "src-tauri\target\release\simpssh-desktop.exe"

$version = (Get-Content -LiteralPath $tauriConf -Raw | ConvertFrom-Json).version
$setup = Join-Path $repoRoot "release\simpssh_${version}_x64-setup.exe"
$portableExe = Join-Path $repoRoot "release\simpssh.exe"

# 允许用户通过 CARGO_HOME / RUSTUP_HOME 覆盖 Rust 工具链位置;没设则走默认
# %USERPROFILE%\.cargo 和 %USERPROFILE%\.rustup,和 rustup 官方安装一致。
if (-not $env:CARGO_HOME) { $env:CARGO_HOME = Join-Path $env:USERPROFILE ".cargo" }
if (-not $env:RUSTUP_HOME) { $env:RUSTUP_HOME = Join-Path $env:USERPROFILE ".rustup" }
$cargoBin = Join-Path $env:CARGO_HOME "bin"
if ($env:Path -notlike "*$cargoBin*") { $env:Path = "$cargoBin;" + $env:Path }

if (-not (Test-Path $webview2Path)) {
  Write-Host "Downloading WebView2 bootstrapper from Microsoft..."
  Invoke-WebRequest -Uri $webview2Url -OutFile $webview2Path -UseBasicParsing
}

Get-Process -ErrorAction SilentlyContinue | Where-Object {
  $_.ProcessName -in @('simpssh', 'simpssh-desktop')
} | Stop-Process -Force -ErrorAction SilentlyContinue

Push-Location $desktopRoot
try {
  npm run icons:generate
  if ($LASTEXITCODE -ne 0) { throw "icons:generate failed with exit code $LASTEXITCODE" }

  Get-ChildItem -Path (Join-Path $desktopRoot "src-tauri\target\release\build") -Directory -Filter "simpssh-desktop-*" -ErrorAction SilentlyContinue |
    Remove-Item -Recurse -Force -ErrorAction SilentlyContinue

  npm run tauri build -- --no-bundle
  if ($LASTEXITCODE -ne 0) { throw "tauri build failed with exit code $LASTEXITCODE" }

  npm run dist:setup
  if ($LASTEXITCODE -ne 0) { throw "dist:setup failed with exit code $LASTEXITCODE" }

  Copy-Item -LiteralPath $appExe -Destination $portableExe -Force

  Write-Host "Release package: $setup"
  Write-Host "Portable exe: $portableExe"
} finally {
  Pop-Location
}
