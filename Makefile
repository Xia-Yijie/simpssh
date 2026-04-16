.PHONY: help check build test fmt clippy install-android install-cargo-ndk install-rust-targets

help:
	@echo "simpssh — task targets"
	@echo ""
	@echo "  make check    — cargo check on the core crate"
	@echo "  make build    — cargo build on the core crate"
	@echo "  make test     — cargo test on the core crate"
	@echo "  make fmt      — cargo fmt"
	@echo "  make clippy   — cargo clippy -D warnings"
	@echo ""
	@echo "First-time setup (see README):"
	@echo "  make install-rust-targets  — rustup add android targets"
	@echo "  make install-cargo-ndk     — cargo install cargo-ndk"
	@echo "  make install-android       — download Android SDK + NDK (~1.2 GB)"

check:
	cd core && cargo check

build:
	cd core && cargo build

test:
	cd core && cargo test

fmt:
	cd core && cargo fmt

clippy:
	cd core && cargo clippy -- -D warnings

install-rust-targets:
	rustup target add \
	  aarch64-linux-android \
	  armv7-linux-androideabi \
	  x86_64-linux-android \
	  i686-linux-android

install-cargo-ndk:
	cargo install cargo-ndk --locked

install-android:
	bash scripts/install-android.sh
