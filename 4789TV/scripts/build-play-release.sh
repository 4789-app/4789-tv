#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

required=(
  FOURSEVENEIGHTNINE_PLAY_STORE_FILE
  FOURSEVENEIGHTNINE_PLAY_KEY_ALIAS
  FOURSEVENEIGHTNINE_PLAY_STORE_PASSWORD
  FOURSEVENEIGHTNINE_PLAY_KEY_PASSWORD
)
for name in "${required[@]}"; do
  if [[ -z "${!name:-}" ]]; then
    echo "missing required Play upload-key variable: $name" >&2
    exit 2
  fi
done

if [[ ! -f "$FOURSEVENEIGHTNINE_PLAY_STORE_FILE" ]]; then
  echo "Play upload keystore does not exist: $FOURSEVENEIGHTNINE_PLAY_STORE_FILE" >&2
  exit 2
fi

python3 scripts/check-play-store-package.py

./gradlew --no-daemon \
  :contract:test \
  :phone:testReleaseUnitTest :phone:lintRelease :phone:bundleRelease \
  :tvplay:testReleaseUnitTest :tvplay:lintRelease :tvplay:bundleRelease

PHONE_AAB="phone/build/outputs/bundle/release/phone-release.aab"
TV_AAB="tvplay/build/outputs/bundle/release/tvplay-release.aab"
for bundle in "$PHONE_AAB" "$TV_AAB"; do
  [[ -s "$bundle" ]] || { echo "missing release bundle: $bundle" >&2; exit 1; }
  verification="$(jarsigner -verify -certs "$bundle")"
  grep -Fq 'jar verified.' <<<"$verification" || {
    echo "release bundle signature verification failed: $bundle" >&2
    exit 1
  }
done

TV_ENTRIES="$(unzip -Z1 "$TV_AAB")"
for forbidden in libmpv.so libplayer.so libavdevice.so libavfilter.so libavformat.so; do
  if grep -Fq "/$forbidden" <<<"$TV_ENTRIES"; then
    echo "release Play TV bundle contains forbidden native library: $forbidden" >&2
    exit 1
  fi
done

echo "Signed Play release bundles verified"
shasum -a 256 "$PHONE_AAB" "$TV_AAB"
