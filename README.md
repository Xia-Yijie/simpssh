# simpssh

A mobile SSH + terminal + browser client. Rust core, native UIs on Android and
iOS.

- `core/` — shared Rust library: SSH client (russh), terminal state machine
  (alacritty_terminal), and a `uniffi` FFI layer.
- `android/` — Kotlin + Jetpack Compose UI (not yet scaffolded).
- `ios/` — Swift + SwiftUI UI (future).

## Dev environment setup (macOS / Linux)

Host dev + Android cross-compile stack:

### 1. Rust

Install via [rustup](https://rustup.rs):

```sh
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
```

Add Android targets:

```sh
make install-rust-targets
```

Install cargo-ndk (cross-compile driver):

```sh
make install-cargo-ndk
```

### 2. JDK 17

Gradle (for Android builds) needs JDK 17. Any install works. The simplest
headless path uses pixi's global environment:

```sh
pixi global install openjdk=17
pixi global expose add -e openjdk java javac jar keytool javadoc
```

Alternatives: `brew install openjdk@17` (macOS) or
`apt install openjdk-17-jdk` (Debian/Ubuntu).

### 3. Android SDK + NDK

```sh
make install-android
```

Downloads ~1.2 GB to `~/Library/Android/sdk` (macOS) or `~/Android/Sdk` (Linux):
command-line tools, platform-tools, `platforms;android-34`, `build-tools;34.0.0`,
`ndk;26.3.11579264`. Matches Android Studio's default SDK path, so installing
the GUI later just picks this up.

Set `ANDROID_HOME` in your shell profile:

```sh
# macOS
export ANDROID_HOME="$HOME/Library/Android/sdk"
# Linux
export ANDROID_HOME="$HOME/Android/Sdk"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
```

## Common tasks

```sh
make check    # cargo check
make test     # cargo test
make build    # cargo build
make clippy
make fmt
```

See `make help` for the full list.
