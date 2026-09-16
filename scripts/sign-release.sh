#!/usr/bin/env bash
# Align and sign assembleRelease output the same way F-Droid expects:
# 16 KiB page alignment with apksigner-style padding, then v1/v2/v3 signing.
#
# Build the unsigned APK first with CARGO_HOME=/home/vagrant/.cargo so
# libhostlookup.so panic paths match F-Droid's builder.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
props="${HOSTLOOKUP_KEYSTORE_PROPERTIES:-$HOME/.config/hostlookup/keystore.properties}"
unsigned="${1:-$root/app/build/outputs/apk/release/app-release-unsigned.apk}"
tools="${REPRODUCIBLE_APK_TOOLS:-}"

if [[ ! -f "$unsigned" ]]; then
  echo "unsigned APK not found: $unsigned" >&2
  exit 1
fi
if [[ ! -f "$props" ]]; then
  echo "keystore properties not found: $props" >&2
  exit 1
fi

store_file=""
store_password=""
key_alias=""
key_password=""
while IFS='=' read -r key value; do
  [[ -z "${key:-}" || "$key" == \#* ]] && continue
  case "$key" in
    storeFile) store_file="$value" ;;
    storePassword) store_password="$value" ;;
    keyAlias) key_alias="$value" ;;
    keyPassword) key_password="$value" ;;
  esac
done < "$props"

if [[ ! -f "$store_file" ]]; then
  candidate="$(dirname "$props")/$(basename "$store_file")"
  if [[ -f "$candidate" ]]; then
    store_file="$candidate"
  else
    echo "keystore not found: $store_file" >&2
    exit 1
  fi
fi

version="$(sed -n 's/.*versionName = "\(.*\)"/\1/p' "$root/app/build.gradle.kts" | head -n1)"
out="${2:-$root/app/build/outputs/apk/release/HostLookup-${version}.apk}"
work="$(mktemp -d)"
cleanup() { rm -rf "$work"; }
trap cleanup EXIT
aligned="$work/aligned.apk"

if [[ -z "$tools" ]]; then
  git clone --quiet --depth 1 --branch v0.3.0 \
    https://gitlab.com/fdroid/reproducible-apk-tools.git "$work/reproducible-apk-tools"
  tools="$work/reproducible-apk-tools"
fi

python3 "$tools/zipalign.py" --page-size 16 --pad-like-apksigner \
  --replace "$unsigned" "$aligned"

mkdir -p "$(dirname "$out")"
apksigner sign \
  --ks "$store_file" \
  --ks-key-alias "$key_alias" \
  --ks-pass "pass:$store_password" \
  --key-pass "pass:$key_password" \
  --v1-signing-enabled true \
  --v2-signing-enabled true \
  --v3-signing-enabled true \
  --out "$out" \
  "$aligned"

apksigner verify --verbose --print-certs "$out"
echo "signed $out"
