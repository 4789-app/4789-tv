#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

GRADLE_TASKS=(
  :contract:test
  :phone:assembleDebug
  :phone:assembleDebugAndroidTest
  :phone:bundleDebug
  :phone:testDebugUnitTest
  :phone:lintDebug
  :app:testDebugUnitTest
  :app:lintDebug
  :app:assembleDebug
  :tvplay:testDebugUnitTest
  :tvplay:lintDebug
  :tvplay:assembleDebug
  :tvplay:bundleDebug
)

if [[ "${1:-}" == "--clean" ]]; then
  GRADLE_TASKS=(clean "${GRADLE_TASKS[@]}")
fi

./gradlew --no-daemon "${GRADLE_TASKS[@]}"

PHONE_MANIFEST="phone/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml"
LEGACY_MANIFEST="app/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml"
PLAY_TV_MANIFEST="tvplay/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml"
PHONE_APK="phone/build/outputs/apk/debug/phone-debug.apk"
PHONE_AAB="phone/build/outputs/bundle/debug/phone-debug.aab"
LEGACY_APK="app/build/outputs/apk/debug/app-debug.apk"
PLAY_TV_AAB="tvplay/build/outputs/bundle/debug/tvplay-debug.aab"
PLAY_TV_APK="tvplay/build/outputs/apk/debug/tvplay-debug.apk"
PLAY_TV_ARM64_LIBS="tvplay/build/intermediates/merged_native_libs/debug/mergeDebugNativeLibs/out/lib/arm64-v8a"

require_file() {
  [[ -s "$1" ]] || { echo "missing or empty artifact: $1" >&2; exit 1; }
}

require_text() {
  grep -Fq "$2" "$1" || { echo "missing invariant '$2' in $1" >&2; exit 1; }
}

reject_text() {
  if grep -Fq "$2" "$1"; then
    echo "forbidden invariant '$2' found in $1" >&2
    exit 1
  fi
}

require_file "$PHONE_APK"
require_file "$PHONE_AAB"
require_file "$LEGACY_APK"
require_file "$PLAY_TV_AAB"
require_file "$PLAY_TV_APK"

require_text "$PHONE_MANIFEST" 'package="com.fourseveneightnine.phone"'
require_text "$PHONE_MANIFEST" 'android:minSdkVersion="26"'
require_text "$PHONE_MANIFEST" 'android:targetSdkVersion="36"'
require_text "$PHONE_MANIFEST" 'android.intent.category.LAUNCHER'
reject_text "$PHONE_MANIFEST" 'android.intent.category.LEANBACK_LAUNCHER'
reject_text "$PHONE_MANIFEST" 'android.permission.REQUEST_INSTALL_PACKAGES'
reject_text "$PHONE_MANIFEST" 'android.permission.RECEIVE_BOOT_COMPLETED'

require_text "$PLAY_TV_MANIFEST" 'package="com.fourseveneightnine.tv.play"'
require_text "$PLAY_TV_MANIFEST" 'android:minSdkVersion="28"'
require_text "$PLAY_TV_MANIFEST" 'android:targetSdkVersion="34"'
require_text "$PLAY_TV_MANIFEST" 'android.intent.category.LEANBACK_LAUNCHER'
require_text "$PLAY_TV_MANIFEST" 'android:banner="@drawable/tv_banner"'
require_text "$PLAY_TV_MANIFEST" 'android:screenOrientation="landscape"'
reject_text "$PLAY_TV_MANIFEST" 'android.intent.category.LAUNCHER'
reject_text "$PLAY_TV_MANIFEST" 'android.permission.REQUEST_INSTALL_PACKAGES'

# The absent permission is NOT proof the installer is gone. The Play product and the sideload
# receiver share app/src/main/java, and the installer used to sit inside MainActivity, so its
# download hosts and its install intent were compiled straight into the Play APK. A reviewer
# running `strings` on the dex would have found them. Google Play forbids an app that downloads
# and installs other apps, so check the shipped bytecode, not the manifest alone.
#
# The working installer now lives in app/src/installer/ (compiled by :app only) and the Play
# product takes the stub from app/src/installer-stub/. These greps prove that split holds.
reject_dex_text() {
  local apk="$1" needle="$2" dex
  for dex in $(unzip -Z1 "$apk" '*.dex'); do
    if unzip -p "$apk" "$dex" | strings | grep -Fq -- "$needle"; then
      echo "forbidden installer string '$needle' found in $apk ($dex)" >&2
      exit 1
    fi
  done
}

# The install MIME type needs its own rule. A dependency ships a MIME database listing
# "application/vnd.android.package-archive,apk" among hundreds of types; that is reference data,
# not an install call. Our own intent stores the bare type as its own dex string. Whole-line
# matching cannot separate them, because dex strings carry a length prefix that `strings` renders
# as a stray leading character, so `grep -x` matches nothing at all. Excluding the table's ",apk"
# form is what actually distinguishes the two.
reject_install_mime() {
  local apk="$1" dex hits
  for dex in $(unzip -Z1 "$apk" '*.dex'); do
    hits="$(unzip -p "$apk" "$dex" | strings |
      grep -F 'application/vnd.android.package-archive' | grep -vF ',apk' || true)"
    if [[ -n "$hits" ]]; then
      echo "forbidden install intent MIME type found in $apk ($dex)" >&2
      exit 1
    fi
  done
}

reject_install_mime "$PLAY_TV_APK"
for needle in \
  'get.videolan.org' \
  'mirrors.kodi.tv' \
  'nextplayer/releases' \
  'just-player/releases' \
  'Install Player'
do
  reject_dex_text "$PLAY_TV_APK" "$needle"
done
echo "PASS Play TV build carries no APK installer"

require_text "$LEGACY_MANIFEST" 'package="com.fourseveneightnine.tv"'
require_text "$LEGACY_MANIFEST" 'android:targetSdkVersion="28"'
require_text "$LEGACY_MANIFEST" 'android.permission.REQUEST_INSTALL_PACKAGES'

if [[ "$(grep -c 'android.intent.category.LAUNCHER' "$PHONE_MANIFEST")" -ne 1 ]]; then
  echo "phone must expose exactly one launcher activity" >&2
  exit 1
fi

if [[ "$(grep -c 'android.intent.category.LEANBACK_LAUNCHER' "$PLAY_TV_MANIFEST")" -ne 1 ]]; then
  echo "Play TV must expose exactly one Leanback launcher activity" >&2
  exit 1
fi

python3 - <<'PY'
from pathlib import Path
import struct
import xml.etree.ElementTree as ET

android = "{http://schemas.android.com/apk/res/android}"
manifest = ET.parse(
    "tvplay/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml"
).getroot()
features = {
    feature.attrib.get(android + "name"): feature.attrib.get(android + "required")
    for feature in manifest.findall("uses-feature")
}
if features.get("android.hardware.touchscreen") != "false":
    raise SystemExit("Play TV must declare touchscreen not required")
if features.get("android.software.leanback") != "true":
    raise SystemExit("Play TV must require Android TV Leanback")
print("PASS Android TV manifest feature requirements")

for path, expected in (
    (Path("app/src/main/res/drawable-mdpi/tv_banner.png"), (320, 180)),
    (Path("app/src/main/res/drawable-xhdpi/tv_banner.png"), (640, 360)),
):
    data = path.read_bytes()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise SystemExit(f"TV launcher banner is not PNG: {path}")
    size = struct.unpack(">II", data[16:24])
    if size != expected:
        raise SystemExit(f"TV launcher banner has {size}, expected {expected}: {path}")
print("PASS Android TV launcher banner dimensions")
PY

AAB_ENTRIES="$(unzip -Z1 "$PLAY_TV_AAB")"

if ! grep -Fq 'base/lib/arm64-v8a/' <<<"$AAB_ENTRIES"; then
  echo "Play TV bundle is missing arm64-v8a native libraries" >&2
  exit 1
fi

if ! grep -Fq 'base/lib/armeabi-v7a/' <<<"$AAB_ENTRIES"; then
  echo "Play TV bundle is missing armeabi-v7a native libraries" >&2
  exit 1
fi

for forbidden_native in libmpv.so libplayer.so libavdevice.so libavfilter.so libavformat.so; do
  if grep -Fq "/$forbidden_native" <<<"$AAB_ENTRIES"; then
    echo "Play TV bundle contains disabled/colliding native engine library $forbidden_native" >&2
    exit 1
  fi
done

for required_native in libmedia3ext.so libavcodec.so libavutil.so libswresample.so libswscale.so; do
  if ! grep -Fq "base/lib/arm64-v8a/$required_native" <<<"$AAB_ENTRIES"; then
    echo "Play TV bundle is missing working Media3 native library $required_native" >&2
    exit 1
  fi
done

SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$SDK_ROOT" && -f local.properties ]]; then
  SDK_ROOT="$(sed -n 's/^sdk\.dir=//p' local.properties | tail -1)"
fi
ZIPALIGN="$(find "$SDK_ROOT/build-tools" -type f -name zipalign 2>/dev/null | sort -V | tail -1)"
if [[ -z "$ZIPALIGN" ]]; then
  echo "Android zipalign was not found under $SDK_ROOT/build-tools" >&2
  exit 1
fi
"$ZIPALIGN" -c -P 16 4 "$PLAY_TV_APK"

ARM64_LIBRARIES=("$PLAY_TV_ARM64_LIBS"/*.so)
if [[ ! -e "${ARM64_LIBRARIES[0]}" ]]; then
  echo "Play TV merged ARM64 libraries were not found" >&2
  exit 1
fi
python3 scripts/check-elf-page-alignment.py "${ARM64_LIBRARIES[@]}"
python3 scripts/check-play-store-package.py

echo "Android foundation gate passed"
shasum -a 256 "$PHONE_APK" "$PHONE_AAB" "$LEGACY_APK" "$PLAY_TV_APK" "$PLAY_TV_AAB"
