# simpssh

A mobile SSH / SFTP client for Android. Native UI, Rust core.

The transport layer — SSH protocol, xterm state machine (alacritty_terminal),
and SFTP — lives in a shared Rust crate that compiles to a native `.so` and is
exposed to Kotlin via [uniffi](https://github.com/mozilla/uniffi-rs). The UI is
Kotlin + Jetpack Compose. Because the core crate is platform-agnostic, the same
Rust code also builds as an `xcframework` for iOS.

## Environment setup

One-time. macOS or Linux, headless or with Android Studio.

### 1. Rust + Android targets

```sh
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
make install-rust-targets    # rustup add aarch64-linux-android et al.
make install-cargo-ndk       # cargo install cargo-ndk
```

### 2. JDK 17

Gradle needs JDK 17.

```sh
brew install openjdk@17                     # macOS
# or
sudo apt install openjdk-17-jdk             # Linux
# or
pixi global install openjdk=17 && \
  pixi global expose add -e openjdk java javac jar keytool javadoc
```

### 3. Android SDK + NDK

```sh
make install-android
```

Downloads ~1.2 GB to `~/Library/Android/sdk` (macOS) or `~/Android/Sdk` (Linux):
command-line tools, platform-tools, `platforms;android-34`, `build-tools;34.0.0`,
`ndk;26.3.11579264`.

### 4. Environment variables

Add to your shell profile (`~/.zshrc` / `~/.bashrc`):

```sh
export ANDROID_HOME="$HOME/Library/Android/sdk"   # or $HOME/Android/Sdk on Linux
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
export JAVA_HOME="$(/usr/libexec/java_home -v 17)" # macOS
# export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64  # Linux
```

## Building the APK

Two steps: build the Rust core for arm64 + regen the Kotlin bindings, then run
Gradle.

```sh
# 1. Build the native library and regenerate uniffi bindings
cd core && cargo ndk -t arm64-v8a -o ../android/app/src/main/jniLibs build --release
cargo run --bin uniffi-bindgen -- \
    generate --library ./target/aarch64-linux-android/release/libsimpssh_core.so \
    --language kotlin --out-dir ../android/app/src/main/java

# 2. Build the APK
cd ../android && ./gradlew assembleDebug
```

The APK lands at `android/app/build/outputs/apk/debug/simpssh-<version>-debug.apk`.

For a release build use `./gradlew assembleRelease` (unsigned; sign it
yourself).

## Development commands

```sh
make check     # cargo check on the Rust core
make test      # cargo test
make clippy    # cargo clippy -D warnings
make fmt       # cargo fmt
```

## License

[Apache 2.0](LICENSE) © 2026 Yijie Xia (夏义杰)
