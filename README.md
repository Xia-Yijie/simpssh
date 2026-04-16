# simpssh

> A small, modern SSH client for Android. Native UI, Rust core.

`simpssh` is a mobile SSH client that connects to your servers over SSH and SFTP
without going through any third-party relay. The transport layer (SSH protocol,
terminal state machine, SFTP) lives in a shared Rust library that compiles to a
native `.so`; the UI is Kotlin + Jetpack Compose.

It is built so the same Rust core can later back an iOS app with minimal extra
work — only the UI layer is platform-specific.

## Features (current)

- **Stored servers** with name, IP/domain, port, username, password
- **Per-server init scripts** — multiple named scripts per host (e.g. "进入项目目录",
  "启动开发服务器"), pick one when you connect; sent to the shell line by line right
  after the PTY comes up
- **Multiple concurrent sessions** with a scrollable tab bar; switching to the
  server list does not kill connections
- **Live terminal** powered by `alacritty_terminal` running headlessly inside the
  Rust core; UI just renders the row snapshot
- **SFTP file browser** (per session): breadcrumb navigation, text preview,
  download / upload via Storage Access Framework, mkdir, rename, delete
- **Material 3 UI** with dynamic colour, dark mode, adaptive launcher icon

## Architecture

```
┌─────────────────────────────────────────┐
│ Android app (Kotlin + Jetpack Compose)  │
│   server list ◂─▸ session tabs ◂─▸ UI   │
└──────────────────────┬──────────────────┘
                       │  uniffi bindings
┌──────────────────────┴──────────────────┐
│ simpssh-core (Rust, .so)                │
│ ┌─────────────┐  ┌────────────────────┐ │
│ │ TerminalView│  │ SshSession (russh) │ │
│ │ alacritty   │  ├────────────────────┤ │
│ │ _terminal   │  │ SftpSession        │ │
│ │             │  │ (russh-sftp)       │ │
│ └─────────────┘  └────────────────────┘ │
└─────────────────────────────────────────┘
```

The same Rust crate also produces a `staticlib` for iOS (`xcframework`); the
iOS app shell is on the to-do list.

## Status

Early. Working but rough:

- ✅ Password auth, PTY, interactive shell
- ✅ SFTP browse / preview / download / upload / mkdir / rename / delete
- ✅ Multi-tab sessions persisted across navigation
- ❌ Per-keystroke key handling (currently line-buffered with Enter to send)
- ❌ Coloured terminal rendering (currently plain text)
- ❌ Public-key auth and `known_hosts` host-key verification (TOFU accept-any)
- ❌ iOS app shell

## Building

Headless macOS / Linux. See [`README setup`](#dev-environment-setup) below for
the full sequence; briefly:

```sh
make install-rust-targets    # rustup add android targets
make install-cargo-ndk       # cargo install cargo-ndk
make install-android         # ~1.2 GB: SDK + NDK
make test                    # cargo test on the core crate
cd android && ./gradlew assembleDebug
```

The APK lands at `android/app/build/outputs/apk/debug/app-debug.apk`.

## Dev environment setup

### 1. Rust

Install via [rustup](https://rustup.rs):

```sh
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
make install-rust-targets
make install-cargo-ndk
```

### 2. JDK 17

Gradle (for Android builds) needs JDK 17. Headless install via pixi:

```sh
pixi global install openjdk=17
pixi global expose add -e openjdk java javac jar keytool javadoc
```

Or `brew install openjdk@17` (macOS) / `apt install openjdk-17-jdk` (Linux).

### 3. Android SDK + NDK

```sh
make install-android
```

Downloads ~1.2 GB to `~/Library/Android/sdk` (macOS) or `~/Android/Sdk` (Linux):
command-line tools, platform-tools, `platforms;android-34`, `build-tools;34.0.0`,
`ndk;26.3.11579264`. Matches Android Studio's default SDK path.

Set `ANDROID_HOME` in your shell profile if not already set.

## Common tasks

```sh
make check           # cargo check on the Rust core
make test            # cargo test
make build           # cargo build (host)
make clippy
make fmt
```

To rebuild the Android `.so` and regen Kotlin bindings after changing Rust:

```sh
cd core && cargo ndk -t arm64-v8a -o ../android/app/src/main/jniLibs build --release
cd core && cargo run --bin uniffi-bindgen -- \
    generate --library ./target/aarch64-linux-android/release/libsimpssh_core.so \
    --language kotlin --out-dir ../android/app/src/main/java
```

(Wiring this into Gradle's `preBuild` is on the to-do list.)

## License

[Apache 2.0](LICENSE) © 2026 Yijie Xia (夏义杰)

## Author

Yijie Xia · [github.com/Xia-Yijie](https://github.com/Xia-Yijie)
