#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

if [[ -n "$(git -C "$ROOT" status --short)" ]]; then
  echo "Android QA handoff requires a clean committed worktree" >&2
  exit 2
fi

PHONE_APK="phone/build/outputs/apk/debug/phone-debug.apk"
PHONE_AAB="phone/build/outputs/bundle/debug/phone-debug.aab"
TV_APK="tvplay/build/outputs/apk/debug/tvplay-debug.apk"
TV_AAB="tvplay/build/outputs/bundle/debug/tvplay-debug.aab"
for artifact in "$PHONE_APK" "$PHONE_AAB" "$TV_APK" "$TV_AAB"; do
  [[ -s "$artifact" ]] || { echo "missing QA artifact: $artifact" >&2; exit 1; }
done

python3 scripts/check-play-store-package.py

commit="$(git -C "$ROOT" rev-parse --short=8 HEAD)"
stamp="$(date -u +%Y%m%d-%H%M%SZ)"
handoff_root="$HOME/Library/Mobile Documents/com~apple~CloudDocs/4789 Android QA"
destination="$handoff_root/$stamp-$commit"
mkdir -p "$destination"

cp "$PHONE_APK" "$destination/4789-phone-qa-$commit.apk"
cp "$PHONE_AAB" "$destination/4789-phone-qa-$commit.aab"
cp "$TV_APK" "$destination/4789-tv-play-qa-$commit.apk"
cp "$TV_AAB" "$destination/4789-tv-play-qa-$commit.aab"
cp -R play-store "$destination/play-store"
cp "$ROOT/../docs/IOS_ANDROID_QA_HANDOVER.md" "$destination/HANDOVER.md"

(
  cd "$destination"
  shasum -a 256 \
    "4789-phone-qa-$commit.apk" \
    "4789-phone-qa-$commit.aab" \
    "4789-tv-play-qa-$commit.apk" \
    "4789-tv-play-qa-$commit.aab" > SHA256SUMS.txt
)

printf '%s\n' \
  "4789 Android QA handoff" \
  "Commit: $commit" \
  "Created UTC: $stamp" \
  "" \
  "The APK files are installable QA builds signed with the Android debug certificate." \
  "The AAB files are QA inspection artifacts and must not be uploaded to a production Play track." \
  "Production upload requires the owner's upload key and Play Console workflow." \
  "Read HANDOVER.md for emulator setup, test commands, completed work, and next steps." \
  > "$destination/README.txt"

for artifact in "$destination"/*.apk "$destination"/*.aab; do
  [[ -s "$artifact" ]] || { echo "iCloud handoff copy is empty: $artifact" >&2; exit 1; }
done

echo "$destination"
shasum -a 256 "$destination"/*.apk "$destination"/*.aab
