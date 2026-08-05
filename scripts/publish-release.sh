#!/usr/bin/env bash
# Publish the 4789 TV receiver APK as a GitHub Release so any box can sideload it
# from a plain URL. Release-only: this pushes the APK, never the source tree.
#
#   ./scripts/publish-release.sh            # publish the current versionName
#   ./scripts/publish-release.sh --build    # rebuild the APK first
#
# Requires: gh (logged in). The stable link afterwards is
#   https://github.com/<owner>/4789tv-releases/releases/latest/download/4789tv.apk
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APK="$HERE/app/build/outputs/apk/debug/app-debug.apk"
REPO_NAME="4789tv-releases"

command -v gh >/dev/null || { echo "✗ gh not installed"; exit 1; }
gh auth status >/dev/null 2>&1 || { echo "✗ gh not logged in — run: gh auth login"; exit 1; }

if [[ "${1:-}" == "--build" || ! -f "$APK" ]]; then
  echo "▸ building the receiver…"
  ( cd "$HERE" && JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}" ./gradlew assembleDebug -q )
fi
[[ -f "$APK" ]] || { echo "✗ no APK at $APK"; exit 1; }

VERSION="$(grep -m1 'versionName' "$HERE/app/build.gradle.kts" | sed 's/.*"\(.*\)".*/\1/')"
OWNER="$(gh api user --jq .login)"
TAG="v$VERSION"

if ! gh repo view "$OWNER/$REPO_NAME" >/dev/null 2>&1; then
  echo "▸ creating $OWNER/$REPO_NAME (public, empty — releases only)"
  gh repo create "$OWNER/$REPO_NAME" --public \
    --description "4789 TV receiver — Android TV / Google TV / Fire TV sideload builds"
fi

# A release cannot be attached to an empty repo, so make sure a first commit exists.
if ! gh api "repos/$OWNER/$REPO_NAME/contents/README.md" >/dev/null 2>&1; then
  echo "▸ seeding the repo with a README"
  README=$(printf '# 4789 TV\n\nSideload builds of the 4789 TV receiver for Android TV, Google TV and Fire TV.\n\nAlways-newest APK:\n\n    https://github.com/%s/%s/releases/latest/download/4789tv.apk\n\nOn the box: install Downloader (or any browser), open that URL, and allow\ninstalls from unknown sources when prompted.\n' "$OWNER" "$REPO_NAME" | base64)
  gh api -X PUT "repos/$OWNER/$REPO_NAME/contents/README.md" \
    -f message="add the install link" -f content="$README" >/dev/null
fi

STAGE="$(mktemp -d)"
cp "$APK" "$STAGE/4789tv.apk"

if gh release view "$TAG" --repo "$OWNER/$REPO_NAME" >/dev/null 2>&1; then
  echo "▸ replacing the asset on the existing $TAG release"
  gh release upload "$TAG" "$STAGE/4789tv.apk" --repo "$OWNER/$REPO_NAME" --clobber
else
  echo "▸ cutting release $TAG"
  gh release create "$TAG" "$STAGE/4789tv.apk" \
    --repo "$OWNER/$REPO_NAME" \
    --title "4789 TV $VERSION" \
    --notes "Sideload build of the 4789 TV receiver.

On the box: install Downloader (or any browser), then open
https://github.com/$OWNER/$REPO_NAME/releases/latest/download/4789tv.apk

Allow installs from unknown sources when prompted."
fi

rm -rf "$STAGE"

echo
echo "✓ stable link (always the newest build):"
echo "  https://github.com/$OWNER/$REPO_NAME/releases/latest/download/4789tv.apk"
