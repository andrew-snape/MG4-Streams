#!/usr/bin/env bash
# Builds app/libs/mg4-tailnet.aar from tailnet/ (Go, tsnet) with gomobile.
# Needs Go and the Android NDK 28.2.13676358. Run again after changing tailnet/.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
module="$root/tailnet"
output="$root/app/libs/mg4-tailnet.aar"

sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$sdk" && -f "$root/local.properties" ]]; then
    sdk="$(sed -n 's/^sdk\.dir=//p' "$root/local.properties")"
fi
export ANDROID_HOME="$sdk" ANDROID_SDK_ROOT="$sdk"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$sdk/ndk/28.2.13676358}"
[[ -d "$ANDROID_NDK_HOME" ]] || { echo "NDK not found at $ANDROID_NDK_HOME" >&2; exit 1; }
export GOFLAGS="-buildvcs=false ${GOFLAGS:-}"

cd "$module"
tools="$module/.tools/bin"
mkdir -p "$tools" "$(dirname "$output")"
go build -o "$tools/gomobile" golang.org/x/mobile/cmd/gomobile
go build -o "$tools/gobind" golang.org/x/mobile/cmd/gobind
export PATH="$tools:$PATH"

# arm64 and 32-bit arm: the app ships both (abiFilters), and the head unit may run it as either.
# Package mg4tailnet becomes com.mg4.streams.tailnet.Mg4tailnet / .Node in Java.
gomobile bind \
    -target=android/arm64,android/arm \
    -androidapi=28 \
    -javapkg=com.mg4.streams.tailnet \
    -trimpath \
    -ldflags='-s -w' \
    -o "$output" \
    .
rm -f "${output%.aar}-sources.jar"
ls -l "$output"
