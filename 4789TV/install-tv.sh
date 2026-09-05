#!/usr/bin/env bash
# Install the 4789 TV receiver onto any Android TV / Google TV / Fire TV box by IP, then prove
# it actually works. One argument: the box's LAN address.
#
#   ./install-tv.sh 192.168.0.106
#   ./install-tv.sh 192.168.0.106 --build     # rebuild the APK first
#
# The box must have ADB debugging enabled once, from its own settings, and the first connection
# raises an "Allow debugging?" prompt that someone has to accept with the remote. Everything
# after that is remote.
set -uo pipefail

IP="${1:-}"
if [[ -z "$IP" ]]; then
  echo "usage: $0 <box-ip> [--build]" >&2
  exit 2
fi
[[ "$IP" == *:* ]] || IP="$IP:5555"

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APK="$HERE/app/build/outputs/apk/debug/app-debug.apk"
PKG="com.fourseveneightnine.tv"

if [[ "${2:-}" == "--build" || ! -f "$APK" ]]; then
  echo "▸ building the receiver…"
  ( cd "$HERE" && JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}" ./gradlew assembleDebug -q ) \
    || { echo "✗ build failed"; exit 1; }
fi

echo "▸ connecting to $IP"
adb connect "$IP" >/dev/null 2>&1
if ! adb -s "$IP" shell true >/dev/null 2>&1; then
  cat >&2 <<EOF
✗ cannot reach $IP over ADB.
  On the box: Settings → System → About → tap the build number 7×, then
  Developer options → enable ADB / network debugging. Accept the prompt that
  appears on the TV the first time. Newer Google TV boxes may instead show a
  pairing code — run: adb pair <ip>:<port>
EOF
  exit 1
fi

MODEL=$(adb -s "$IP" shell getprop ro.product.model 2>/dev/null | tr -d '\r')
MAKER=$(adb -s "$IP" shell getprop ro.product.manufacturer 2>/dev/null | tr -d '\r')
REL=$(adb -s "$IP" shell getprop ro.build.version.release 2>/dev/null | tr -d '\r')
SDK=$(adb -s "$IP" shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')
echo "  $MAKER $MODEL · Android $REL (API $SDK)"
if [[ -n "$SDK" && "$SDK" -lt 28 ]]; then
  echo "✗ this box is API $SDK; the receiver needs API 28 or newer." >&2
  exit 1
fi

echo "▸ installing"
adb -s "$IP" install -r "$APK" 2>&1 | tail -1

echo "▸ launching"
adb -s "$IP" shell am force-stop "$PKG" >/dev/null 2>&1
adb -s "$IP" shell am start -n "$PKG/.ui.MainActivity" >/dev/null 2>&1

# POLL, DON'T SLEEP. A flat wait has to be either too short or wasteful, and too short is the
# expensive kind: the script printed "the receiver did not answer" over a receiver that was fine
# and about to bind, sending you to the television to check something that was not wrong. The
# AFTDCT31 in particular needs well past 8s to get through cold start, surface creation and port
# bind. Polling every second reports success the moment it is true and only gives up once the box
# has really had its chance.
echo "▸ asking the receiver what this box can play"
HOST="${IP%%:*}"
DEADLINE=$((SECONDS + 45))
INFO=""
while (( SECONDS < DEADLINE )); do
  INFO=$(curl -s -m 4 -X POST "http://$HOST:8791/jsonrpc" \
    -H 'Content-Type: application/json' \
    -d '{"jsonrpc":"2.0","id":1,"method":"X4789.GetReceiverInfo","params":{}}')
  [[ -n "$INFO" ]] && break
  # Only complain once the wait stops looking like a normal cold start, so a healthy install still
  # reads as one clean line.
  (( SECONDS % 10 == 0 )) && echo "  still waiting for :8791 …"
  sleep 1
done

if [[ -z "$INFO" ]]; then
  # The app may be installed and running yet not FOREGROUND — the receiver only serves while its
  # screen is up. Say which of the two it is instead of making the user guess.
  RESUMED=$(adb -s "$IP" shell dumpsys activity activities 2>/dev/null | grep -c "$PKG/.ui.MainActivity")
  echo "✗ the receiver did not answer on $HOST:8791 after 45s."
  if [[ "${RESUMED:-0}" == "0" ]]; then
    echo "  Its activity is NOT running — something stopped it. Check: adb -s $IP logcat -d -b crash | tail -40"
  else
    echo "  Its activity IS running, so this is a network path problem, not a launch one:"
    echo "  check this Mac and the box are on the same subnet and nothing is filtering :8791."
  fi
  echo "  Diagnostics: adb -s $IP shell run-as $PKG cat files/receiver-diagnostics.log"
  exit 1
fi

python3 - "$INFO" <<'PY'
import json, sys
try:
    r = json.loads(sys.argv[1])["result"]
except Exception:
    print("✗ unexpected reply:", sys.argv[1][:200]); raise SystemExit(1)
video = r.get("hardwareVideoCodecs", [])
audio = r.get("audioCodecs", {})
print(f"\n✓ {r.get('name')} is live  ·  engine={r.get('engine')}  ·  uuid={r.get('uuid','')[:8]}")
print(f"  video decoders : {', '.join(video) or 'unknown'}")
print(f"  audio decode   : {', '.join(audio.get('decode') or []) or 'unknown'}")
print(f"  passthrough    : {', '.join(audio.get('passthrough') or []) or 'unknown (probe unavailable)'}")
players = [p.get("label") for p in r.get("externalPlayers", [])]
print(f"  backup players : {', '.join(players) if players else 'none installed'}")
print()
if "dolbyvision" in video:
    print("  ★ decodes Dolby Vision natively")
if {"dts", "dtshd", "truehd"} & set(audio.get("passthrough") or []):
    print("  ★ bitstreams DTS/TrueHD to a receiver")
elif {"dts", "dtshd", "truehd"} & set(audio.get("decode") or []):
    print("  ★ decodes DTS/TrueHD (hardware or bundled ffmpeg)")
PY

echo "▸ phone should now find this box by itself (Bonjour _xbmc-jsonrpc-h._tcp)"
echo "  logs any time:  adb -s $IP shell run-as $PKG cat files/receiver-diagnostics.log"
