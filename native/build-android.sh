#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
SDK_ROOT="${ANDROID_SDK_ROOT:-/home/user/Android/Sdk}"
NDK_ROOT="${ANDROID_NDK_HOME:-$SDK_ROOT/ndk/28.2.13676358}"
TOOLCHAIN="$NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin"
RUST_CARGO="${HOSTLOOKUP_CARGO:-$HOME/.cargo/bin/cargo}"
RUST_COMPILER="${HOSTLOOKUP_RUSTC:-$HOME/.cargo/bin/rustc}"

if [[ ! -x "$RUST_CARGO" ]]; then
  RUST_CARGO="$(command -v cargo)"
fi
if [[ ! -x "$RUST_COMPILER" ]]; then
  RUST_COMPILER="$(command -v rustc)"
fi
export RUSTC="$RUST_COMPILER"

if [[ ! -x "$TOOLCHAIN/aarch64-linux-android26-clang" ]]; then
  echo "Android NDK 28.2.13676358 was not found at $NDK_ROOT" >&2
  echo "Install it with: sdkmanager 'ndk;28.2.13676358'" >&2
  exit 1
fi

mkdir -p "$PROJECT_DIR/app/src/main/jniLibs/arm64-v8a"
mkdir -p "$PROJECT_DIR/app/src/main/jniLibs/x86_64"

export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$TOOLCHAIN/aarch64-linux-android26-clang"
export CC_aarch64_linux_android="$TOOLCHAIN/aarch64-linux-android26-clang"
export AR_aarch64_linux_android="$TOOLCHAIN/llvm-ar"
"$RUST_CARGO" build --manifest-path "$PROJECT_DIR/native/Cargo.toml" --target aarch64-linux-android --release
cp "$PROJECT_DIR/native/target/aarch64-linux-android/release/libhostlookup.so" \
  "$PROJECT_DIR/app/src/main/jniLibs/arm64-v8a/libhostlookup.so"

export CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER="$TOOLCHAIN/x86_64-linux-android26-clang"
export CC_x86_64_linux_android="$TOOLCHAIN/x86_64-linux-android26-clang"
export AR_x86_64_linux_android="$TOOLCHAIN/llvm-ar"
"$RUST_CARGO" build --manifest-path "$PROJECT_DIR/native/Cargo.toml" --target x86_64-linux-android --release
cp "$PROJECT_DIR/native/target/x86_64-linux-android/release/libhostlookup.so" \
  "$PROJECT_DIR/app/src/main/jniLibs/x86_64/libhostlookup.so"
