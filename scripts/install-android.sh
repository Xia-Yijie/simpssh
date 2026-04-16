#!/usr/bin/env bash
# Install Android SDK command-line tools + SDK packages + NDK, headless.
# Driven by `pixi run install-android`.
#
# Target layout:
#   macOS: ~/Library/Android/sdk
#   Linux: ~/Android/Sdk
# (matches Android Studio's default so later GUI install picks it up.)

set -euo pipefail

OS_NAME="$(uname -s)"
case "$OS_NAME" in
  Darwin)
    CMDLINE_OS="mac"
    SDK_ROOT="$HOME/Library/Android/sdk"
    ;;
  Linux)
    CMDLINE_OS="linux"
    SDK_ROOT="$HOME/Android/Sdk"
    ;;
  *)
    echo "Unsupported OS: $OS_NAME" >&2
    exit 1
    ;;
esac

# Pinned. Bump by checking https://developer.android.com/studio#command-line-tools-only
CMDLINE_BUILD="11076708"
NDK_VERSION="26.3.11579264"
PLATFORM="android-34"
BUILD_TOOLS="34.0.0"

CMDLINE_URL="https://dl.google.com/android/repository/commandlinetools-${CMDLINE_OS}-${CMDLINE_BUILD}_latest.zip"
CMDLINE_DIR="$SDK_ROOT/cmdline-tools/latest"

echo ">>> ANDROID_HOME = $SDK_ROOT"
mkdir -p "$SDK_ROOT/cmdline-tools"

if [ ! -x "$CMDLINE_DIR/bin/sdkmanager" ]; then
  echo ">>> Downloading Android cmdline-tools (${CMDLINE_BUILD})..."
  tmp=$(mktemp -d)
  trap 'rm -rf "$tmp"' EXIT
  curl -fL --progress-bar "$CMDLINE_URL" -o "$tmp/cmdline-tools.zip"
  unzip -q "$tmp/cmdline-tools.zip" -d "$tmp"
  rm -rf "$CMDLINE_DIR"
  mv "$tmp/cmdline-tools" "$CMDLINE_DIR"
else
  echo ">>> cmdline-tools already present, skipping download."
fi

SDKMANAGER="$CMDLINE_DIR/bin/sdkmanager"

echo ">>> Accepting SDK licenses..."
yes | "$SDKMANAGER" --sdk_root="$SDK_ROOT" --licenses >/dev/null 2>&1 || true

echo ">>> Installing SDK packages (this is the big download, ~1.2 GB)..."
"$SDKMANAGER" --sdk_root="$SDK_ROOT" \
  "platform-tools" \
  "platforms;${PLATFORM}" \
  "build-tools;${BUILD_TOOLS}" \
  "ndk;${NDK_VERSION}"

echo
echo ">>> Done. Installed under: $SDK_ROOT"
echo ">>>   platform-tools, platforms;${PLATFORM}, build-tools;${BUILD_TOOLS}, ndk;${NDK_VERSION}"
