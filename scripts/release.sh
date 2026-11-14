#!/usr/bin/env bash
# Release MG4 Streams.
#
#   scripts/release.sh 0.5.2 "What changed"     bump, test, commit, tag v0.5.2 and push. GitHub
#                                               Actions (Release workflow) builds, platform-signs
#                                               and publishes the release.
#   scripts/release.sh 0.5.2 --local            build, sign and publish from this Mac instead
#                                               (needs the platform key in DiPlay-MG4/tools).
#   scripts/release.sh 0.5.2 --local --no-publish   stop after the signed APK is in dist/.
#
# Notes: the second argument, else dist/notes-<version>.md, else you're asked for one line.
set -euo pipefail
cd "$(dirname "$0")/.."

VERSION="${1:?usage: scripts/release.sh <version> [\"notes\"] [--local] [--no-publish]}"
shift
VERSION="${VERSION#v}"; VERSION="${VERSION%.}"   # accept v0.5.0 and a stray trailing dot
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "Version must look like 0.5.0"; exit 1; }
LOCAL=0; PUBLISH=1; NOTES=""
for arg in "$@"; do
  case "$arg" in
    --local) LOCAL=1 ;;
    --no-publish) PUBLISH=0 ;;
    *) NOTES="$arg" ;;
  esac
done

REPO="andrew-snape/MG4-Streams"
TAG="v$VERSION"
EXPECTED_SHA="c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8"
GRADLE_FILE="app/build.gradle.kts"

if [[ $PUBLISH == 1 ]]; then
  [[ "$(git rev-parse --abbrev-ref HEAD)" == "main" ]] || { echo "Release from main."; exit 1; }
  git diff --quiet && git diff --cached --quiet || { echo "Commit or stash your changes first."; git status --short; exit 1; }
  git fetch -q origin main --tags
  [[ "$(git rev-parse HEAD)" == "$(git rev-parse origin/main)" ]] || { echo "main isn't in step with origin/main. Pull or push first."; exit 1; }
  if git rev-parse -q --verify "refs/tags/$TAG" >/dev/null; then echo "Tag $TAG already exists."; exit 1; fi
fi

# 1. Bump versionName and versionCode (+1).
OLD_CODE=$(sed -nE 's/^ *versionCode = ([0-9]+).*/\1/p' "$GRADLE_FILE")
OLD_NAME=$(sed -nE 's/^ *versionName = "([^"]+)".*/\1/p' "$GRADLE_FILE")
[[ -n "$OLD_CODE" && -n "$OLD_NAME" ]] || { echo "Can't find versionCode/versionName in $GRADLE_FILE"; exit 1; }
if [[ "$OLD_NAME" == "$VERSION" ]]; then
  echo "Already at $VERSION (versionCode $OLD_CODE)."
else
  NEW_CODE=$((OLD_CODE + 1))
  sed -i '' -E "s/^( *versionCode = )[0-9]+/\1$NEW_CODE/; s/^( *versionName = )\"[^\"]+\"/\1\"$VERSION\"/" "$GRADLE_FILE"
  echo "Version $OLD_NAME ($OLD_CODE) -> $VERSION ($NEW_CODE)"
fi

# 2. Notes.
if [[ -z "$NOTES" && -f "dist/notes-$VERSION.md" ]]; then NOTES=$(cat "dist/notes-$VERSION.md"); fi
if [[ -z "$NOTES" && $PUBLISH == 1 ]]; then read -r -p "Release notes (one line): " NOTES; fi
[[ -n "$NOTES" || $PUBLISH == 0 ]] || { echo "Release notes are needed."; exit 1; }

# 3. Test (and for --local, build and sign). Clean first: iCloud leaves " 2" copies in build/.
rm -rf app/build
python3 scripts/check_public_tree.py
if [[ $LOCAL == 0 ]]; then
  ./gradlew --no-daemon -q testDebugUnitTest lintDebug
else
  KEYS="$HOME/Documents/GitHub/DiPlay-MG4/tools"
  [[ -f "$KEYS/platform.pk8" && -f "$KEYS/platform.x509.pem" ]] || { echo "Platform key not found in $KEYS"; exit 1; }
  APKSIGNER=$(ls ~/Library/Android/sdk/build-tools/*/apksigner | sort -V | tail -1)
  OUT="dist/MG4Streams-$VERSION-platform.apk"
  ./gradlew --no-daemon -q testDebugUnitTest assembleRelease
  mkdir -p dist
  "$APKSIGNER" sign --key "$KEYS/platform.pk8" --cert "$KEYS/platform.x509.pem" \
    --out "$OUT" app/build/outputs/apk/release/app-release-unsigned.apk
  rm -f dist/*.idsig
  SHA=$("$APKSIGNER" verify --print-certs "$OUT" | sed -nE 's/.*certificate SHA-256 digest: ([0-9a-f]+).*/\1/p' | head -1)
  [[ "$SHA" == "$EXPECTED_SHA" ]] || { echo "Signing certificate is $SHA, expected $EXPECTED_SHA. Stopping."; exit 1; }
  echo "Signed: $OUT ($(du -h "$OUT" | cut -f1), cert OK)"
fi
[[ $PUBLISH == 1 ]] || exit 0

# 4. Commit the bump, tag it (the tag message becomes the release notes), push.
read -r -p "Release $TAG to $REPO? [y/N] " OK
[[ "$OK" == "y" || "$OK" == "Y" ]] || { echo "Not released. The version bump is left uncommitted."; exit 0; }
git diff --quiet -- "$GRADLE_FILE" || git commit -q -m "Release $VERSION" -- "$GRADLE_FILE"
git tag -a "$TAG" -m "$NOTES"
git push -q origin main
if [[ $LOCAL == 0 ]]; then
  git push -q origin "$TAG"
  echo "Pushed $TAG. GitHub Actions is building and publishing it:"
  echo "  gh run watch --repo $REPO \$(gh run list --repo $REPO --workflow release.yml --limit 1 --json databaseId --jq '.[0].databaseId')"
else
  # Publish first, then push the tag: the Release workflow sees the release exists and skips.
  gh release create "$TAG" "$OUT" --repo "$REPO" --target "$(git rev-parse HEAD)" \
    --title "MG4 Streams $VERSION" --notes "$NOTES"
  git push -q origin "$TAG" 2>/dev/null || true   # gh already created the tag on GitHub
  echo "Published $TAG."
fi
