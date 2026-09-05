#!/usr/bin/env bash
# Re-download each app's own logo into ./logos, which the phone page serves at /logo/<file>.
#
# Every source below is the project's own repository or website — the same rule the APK sources
# follow. These are trademarks used to identify each app in a launcher, nothing more; do not
# restyle them or use them to imply the projects endorse this installer.
set -uo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")" || exit 1
mkdir -p logos

RAW=https://raw.githubusercontent.com

get() {
  local out="logos/$1" url="$2" want="$3" tmp
  tmp="$(mktemp)"
  if ! curl -sL --max-time 30 -o "$tmp" "$url"; then
    echo "  ✗ $1 — download failed, keeping the old file"; rm -f "$tmp"; return
  fi
  local kind; kind="$(file -b --mime-type "$tmp")"
  if [[ "$kind" != "$want" ]]; then
    echo "  ✗ $1 — got $kind, expected $want (the URL probably moved)"; rm -f "$tmp"; return
  fi
  mv "$tmp" "$out"
  printf "  ✓ %-16s %7s bytes\n" "$1" "$(wc -c <"$out" | tr -d ' ')"
}

echo "▸ fetching each project's own logo"
get justplayer.png "$RAW/moneytoo/Player/master/fastlane/metadata/android/en-US/images/icon.png" image/png
get nextplayer.png "$RAW/anilbeesetti/nextplayer/main/app/src/main/ic_launcher-playstore.png"    image/png
get kodi.svg       "$RAW/xbmc/xbmc/master/tools/Linux/packaging/media/iconScalable.svg"          image/svg+xml
get mpv.svg        "$RAW/mpv-player/mpv/master/etc/mpv-gradient.svg"                             image/svg+xml
get stremio.png    "$RAW/Stremio/stremio-web/development/assets/images/icon_196x196.png"         image/png
get nuvio.png      "$RAW/NuvioMedia/NuvioTV/main/assets/brand/app_logo_mark.png"                 image/png
get vlc.png        "https://images.videolan.org/images/VLC-IconSmall.png"                        image/png
get tivimate.png   "https://tivimate.com/icon.png"                                               image/png

echo "▸ done — reload the phone page to see them"
