#!/usr/bin/env bash
# Build, platform-sign and publish an MG4 Streams release.
#   scripts/release.sh 0.5.0            bump to 0.5.0, build, sign, verify, then ask before publishing
#   scripts/release.sh 0.5.0 --no-publish   stop after the signed APK is in dist/
# Release notes: dist/notes-<version>.md if it exists, otherwise you're asked for one line.
set -euo pipefail
cd "$(dirname "$0")/.."

VERSION="${1:?usage: scripts/release.sh <version> [--no-publish]}"
PUBLISH=1; [[ "${2:-}" == "--no-publish" ]] && PUBLISH=0
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "Version must look like 0.5.0"; exit 1; }

REPO="andrew-snape/MG4-Streams"
KEYS="$HOME/Documents/GitHub/DiPlay-MG4/tools"
EXPECTED_SHA="c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8"
GRADLE_FILE="app/build.gradle.kts"
OUT="dist/MG4Streams-$VERSION-platform.apk"

[[ -f "$KEYS/platform.pk8" && -f "$KEYS/platform.x509.pem" ]] || { echo "Platform key not found in $KEYS"; exit 1; }
APKSIGNER=$(ls ~/Library/Android/sdk/build-tools/*/apksigner | sort -V | tail -1)

# 1. Bump versionName and versionCode (+1).
OLD_CODE=$(sed -nE 's/^ *versionCode = ([0-9]+).*/\1/p' "$GRADLE_FILE")
OLD_NAME=$(sed -nE 's/^ *versionName = "([^"]+)".*/\1/p' "$GRADLE_FILE")
[[ -n "$OLD_CODE" && -n "$OLD_NAME" ]] || { echo "Can't find versionCode/versionName in $GRADLE_FILE"; exit 1; }
if [[ "$OLD_NAME" == "$VERSION" ]]; then
  NEW_CODE=$OLD_CODE
  echo "Already at $VERSION (versionCode $OLD_CODE); rebuilding."
else
  NEW_CODE=$((OLD_CODE + 1))
  sed -i '' -E "s/^( *versionCode = )[0-9]+/\1$NEW_CODE/; s/^( *versionName = )\"[^\"]+\"/\1\"$VERSION\"/" "$GRADLE_FILE"
  echo "Version $OLD_NAME ($OLD_CODE) -> $VERSION ($NEW_CODE)"
fi

# 2. Build (clean: iCloud leaves " 2" duplicates in build/) and test.
rm -rf app/build
./gradlew --no-daemon -q testDebugUnitTest assembleRelease
UNSIGNED=app/build/outputs/apk/release/app-release-unsigned.apk

# 3. Sign with the platform key and check the certificate.
mkdir -p dist
"$APKSIGNER" sign --key "$KEYS/platform.pk8" --cert "$KEYS/platform.x509.pem" --out "$OUT" "$UNSIGNED"
rm -f dist/*.idsig
SHA=$("$APKSIGNER" verify --print-certs "$OUT" | sed -nE 's/.*certificate SHA-256 digest: ([0-9a-f]+).*/\1/p' | head -1)
if [[ "$SHA" != "$EXPECTED_SHA" ]]; then
  echo "Signing certificate is $SHA, expected $EXPECTED_SHA. The head unit would reject this. Stopping."
  exit 1
fi
echo "Signed: $OUT ($(du -h "$OUT" | cut -f1), cert OK)"

[[ $PUBLISH == 1 ]] || exit 0

# 4. Publish on GitHub after confirmation.
NOTES_FILE="dist/notes-$VERSION.md"
if [[ ! -f "$NOTES_FILE" ]]; then
  read -r -p "One-line release notes: " LINE
  printf '%s\n' "$LINE" > "$NOTES_FILE"
fi
echo "About to publish v$VERSION to $REPO with $OUT."
read -r -p "Publish? [y/N] " OK
[[ "$OK" == "y" || "$OK" == "Y" ]] || { echo "Not published. APK is in dist/."; exit 0; }
gh release create "v$VERSION" "$OUT" --repo "$REPO" --title "MG4 Streams $VERSION" --notes-file "$NOTES_FILE"
echo "Published. The app's Check for update will find v$VERSION."
