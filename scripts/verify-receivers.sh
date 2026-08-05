#!/usr/bin/env bash
#
# verify-receivers.sh — install the current APK on every reachable 4789 TV box and prove that a
# title actually plays, from a cold open, with audio that survives.
#
# WHY THIS EXISTS
# ---------------
# `./gradlew testDebugUnitTest lintDebug assembleDebug` cannot see a Fire OS runtime fault. On
# 2026-08-03 a change shipped that compiled, linted and unit-tested clean, then killed audio 68ms
# after `startOutput()` on every Fire OS 7 box. Two things hid it:
#
#   1. A title that was ALREADY PLAYING kept playing — it had started under the previous build.
#      Only a cold open shows the fault, so this script always force-stops first.
#   2. `firstFrameRendered` was true. Video was fine; audio died a moment later. So this script
#      asserts audio output STAYS UP, not merely that it started.
#
# It is also the answer to "it works on one TV and not the other": it runs every box you own in
# one command and prints one table, so version skew and per-engine faults are visible at a glance.
#
# USAGE
#   scripts/verify-receivers.sh                 # install + verify every adb-reachable box
#   scripts/verify-receivers.sh --no-install    # verify what is already installed
#   scripts/verify-receivers.sh --probe-only    # just report what is on the network
#   scripts/verify-receivers.sh --host 192.168.0.106   # also probe a box adb cannot reach
#   scripts/verify-receivers.sh --only 192.168.0.106   # touch ONLY this box
#
# NOTE: every verified box is FORCE-STOPPED to guarantee a cold open. That interrupts whatever is
# playing on it. Use --only when someone is watching another TV.
#
# EXIT CODE is non-zero if any box fails, so it can gate a release.

set -uo pipefail

cd "$(dirname "$0")/.."
REPO_TV="$PWD"
APK="$REPO_TV/app/build/outputs/apk/debug/app-debug.apk"

DO_INSTALL=1
PROBE_ONLY=0
EXTRA_HOSTS=()
ONLY_HOST=""
PLAY_SECONDS=25
# Audio must stay up longer than the failure it exists to catch (that one died in 68ms), but short
# enough that a full sweep is quick. 8s of continuous output is decisive.
MIN_AUDIO_SECONDS=8

while [[ $# -gt 0 ]]; do
  case "$1" in
    --no-install) DO_INSTALL=0; shift ;;
    --probe-only) PROBE_ONLY=1; DO_INSTALL=0; shift ;;
    --host) EXTRA_HOSTS+=("$2"); shift 2 ;;
    --only) ONLY_HOST="$2"; shift 2 ;;
    --play-seconds) PLAY_SECONDS="$2"; shift 2 ;;
    -h|--help) sed -n '2,30p' "$0"; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done

RED=$'\033[31m'; GRN=$'\033[32m'; YEL=$'\033[33m'; DIM=$'\033[2m'; OFF=$'\033[0m'
pass(){ echo "  ${GRN}PASS${OFF} $*"; }
fail(){ echo "  ${RED}FAIL${OFF} $*"; }
warn(){ echo "  ${YEL}WARN${OFF} $*"; }
info(){ echo "  ${DIM}$*${OFF}"; }

# The ports a 4789 receiver has ever used. 8080 and 8090 are retired but old boxes still answer
# there, and an old box is exactly what this script needs to be able to see.
RECEIVER_PORTS=(8791 8090 8080)

rpc() { # rpc <host:port> <json>  -> response body, empty on failure
  curl -s -m 4 "http://$1/jsonrpc" -X POST -H 'Content-Type: application/json' -d "$2" 2>/dev/null
}

receiver_info() { # receiver_info <host> -> "port|name|version-ish|engine|codecs" or empty
  local host="$1" port body
  for port in "${RECEIVER_PORTS[@]}"; do
    body="$(rpc "$host:$port" '{"jsonrpc":"2.0","id":1,"method":"X4789.GetReceiverInfo"}')"
    [[ -z "$body" ]] && continue
    echo "$body" | jq -e '.result.x4789 == true' >/dev/null 2>&1 || continue
    echo "$port|$(echo "$body" | jq -r '.result.name // "?"')|$(echo "$body" | jq -r '.result.httpPort // "?"')|$(echo "$body" | jq -r '.result.engine // "unknown"')|$(echo "$body" | jq -r '(.result.hardwareVideoCodecs // []) | join(",") | if . == "" then "not-reported" else . end')"
    return 0
  done
  return 1
}

# ---------------------------------------------------------------- test clip
# Generated locally rather than downloaded: the point is to exercise the box's decode and its HDMI
# audio path, so the clip must have a real video track and a real AC3 track. A remote URL would also
# drag network flakiness into a test about playback.
CLIP_DIR="$(mktemp -d)"
SERVER_PID=""
cleanup() {
  [[ -n "$SERVER_PID" ]] && kill "$SERVER_PID" 2>/dev/null
  rm -rf "$CLIP_DIR"
}
trap cleanup EXIT

# Two clips, deliberately. A single AC3 5.1 clip conflates two different questions: "is this box
# working at all" and "can this box do surround". On 2026-08-03 an old receiver stalled at 0s on AC3
# and read as a dead box, when in fact it played h264+AAC perfectly. Baseline decides pass/fail;
# surround is reported as a capability.
CLIP_BASE="$CLIP_DIR/baseline.mp4"    # h264 + AAC stereo — every box must play this
CLIP_SURROUND="$CLIP_DIR/surround.mp4" # h264 + AC3 5.1 — exercises the HDMI passthrough path

make_clip() {
  info "building test clips (baseline h264+aac, surround h264+ac3 5.1)"
  ffmpeg -y -loglevel error \
    -f lavfi -i "testsrc2=size=1280x720:rate=24:duration=30" \
    -f lavfi -i "sine=frequency=440:duration=30:sample_rate=48000" \
    -c:v libx264 -pix_fmt yuv420p -preset ultrafast -g 48 \
    -c:a aac -b:a 128k -movflags +faststart "$CLIP_BASE" 2>&1 | head -3
  ffmpeg -y -loglevel error \
    -f lavfi -i "testsrc2=size=1280x720:rate=24:duration=30" \
    -f lavfi -i "sine=frequency=440:duration=30:sample_rate=48000" \
    -af "pan=5.1|c0=c0|c1=c0|c2=c0|c3=c0|c4=c0|c5=c0" \
    -c:v libx264 -pix_fmt yuv420p -preset ultrafast -g 48 \
    -c:a ac3 -b:a 448k -movflags +faststart "$CLIP_SURROUND" 2>&1 | head -3
  [[ -s "$CLIP_BASE" && -s "$CLIP_SURROUND" ]] || { echo "${RED}could not build the test clips${OFF}"; exit 1; }
}

# play_and_measure <host:port> <url> <seconds> -> echoes the position reached, in seconds
play_and_measure() {
  local endpoint="$1" url="$2" secs="$3" body
  body="$(rpc "$endpoint" "$(jq -nc --arg f "$url" \
    '{jsonrpc:"2.0",id:1,method:"Player.Open",params:{item:{file:$f}}}')")"
  if ! echo "$body" | jq -e '.result' >/dev/null 2>&1; then echo "REJECTED"; return; fi
  sleep "$secs"
  local pos
  pos="$(rpc "$endpoint" '{"jsonrpc":"2.0","id":1,"method":"Player.GetProperties","params":{"playerid":1,"properties":["time","speed"]}}')"
  echo "$pos" | jq -r '((.result.time.minutes // 0) * 60) + (.result.time.seconds // 0)' 2>/dev/null || echo 0
}

MAC_IP="$(ipconfig getifaddr en0 2>/dev/null || true)"
serve_clip() {
  [[ -n "$MAC_IP" ]] || { echo "${RED}no LAN address on en0 — cannot serve the clip${OFF}"; exit 1; }
  # Claim a free port up front rather than parsing python's banner: that banner is buffered when
  # redirected, so reading it back is a race the script loses.
  SERVE_PORT="$(python3 -c 'import socket;s=socket.socket();s.bind(("",0));print(s.getsockname()[1]);s.close()')"
  ( cd "$CLIP_DIR" && exec python3 -m http.server "$SERVE_PORT" --bind 0.0.0.0 >"$CLIP_DIR/server.log" 2>&1 ) &
  SERVER_PID=$!
  disown "$SERVER_PID" 2>/dev/null   # otherwise the shell prints a "Terminated" line at exit
  local ready=0 _
  for _ in $(seq 1 40); do
    if curl -s -m 2 -o /dev/null "http://127.0.0.1:$SERVE_PORT/baseline.mp4"; then ready=1; break; fi
    sleep 0.25
  done
  [[ $ready -eq 1 ]] || { echo "${RED}test HTTP server did not start${OFF}"; cat "$CLIP_DIR/server.log"; exit 1; }
  BASE_URL="http://$MAC_IP:$SERVE_PORT/baseline.mp4"
  SURROUND_URL="http://$MAC_IP:$SERVE_PORT/surround.mp4"
  info "serving clips from http://$MAC_IP:$SERVE_PORT/"
}

# ---------------------------------------------------------------- per-box verification
declare -a RESULTS=()

verify_box() { # verify_box <serial-or-"-"> <host>
  local serial="$1" host="$2" adb_ok=0 name port engine codecs info_line
  [[ "$serial" != "-" ]] && adb_ok=1

  echo
  echo "${DIM}────────────────────────────────────────────────────────${OFF}"
  echo "BOX $host  ${DIM}(adb: ${serial})${OFF}"

  # -- install + cold start ------------------------------------------------
  if [[ $adb_ok -eq 1 && $DO_INSTALL -eq 1 ]]; then
    [[ -f "$APK" ]] || { fail "no APK at $APK — run ./gradlew assembleDebug"; RESULTS+=("$host|NO-APK"); return 1; }
    if adb -s "$serial" install -r "$APK" 2>&1 | grep -q Success; then
      pass "installed $(basename "$APK")"
    else
      fail "install rejected (signature mismatch? uninstall first)"
      RESULTS+=("$host|INSTALL-FAILED"); return 1
    fi
  fi

  if [[ $adb_ok -eq 1 ]]; then
    # THE COLD OPEN. Without this an already-running title masks the fault entirely.
    adb -s "$serial" shell am force-stop com.fourseveneightnine.tv >/dev/null 2>&1
    sleep 1
    adb -s "$serial" shell monkey -p com.fourseveneightnine.tv \
      -c android.intent.category.LEANBACK_LAUNCHER 1 >/dev/null 2>&1
    adb -s "$serial" logcat -c >/dev/null 2>&1
    info "cold start requested"
  fi

  # -- wait for the receiver to answer -------------------------------------
  local found="" i
  for i in $(seq 1 30); do
    found="$(receiver_info "$host" || true)"
    [[ -n "$found" ]] && break
    sleep 1
  done
  if [[ -z "$found" ]]; then
    fail "no receiver answered on ${RECEIVER_PORTS[*]}"
    RESULTS+=("$host|NO-RECEIVER"); return 1
  fi

  port="${found%%|*}"; local rest="${found#*|}"
  name="${rest%%|*}"; rest="${rest#*|}"
  local reported_port="${rest%%|*}"; rest="${rest#*|}"
  engine="${rest%%|*}"; codecs="${rest#*|}"

  pass "receiver up on :$port  name=\"$name\"  engine=$engine"
  info "codecs: $codecs"

  # Version skew is the thing that made two TVs behave differently for weeks. Say it plainly.
  if [[ "$codecs" == "not-reported" ]]; then
    warn "STALE BUILD — this box predates hardwareVideoCodecs. The phone will mis-feed it."
  fi
  if [[ "$port" != "8791" ]]; then
    warn "answering on retired port $port (current is 8791) — the phone treats this as stock Kodi"
  fi

  if [[ $PROBE_ONLY -eq 1 ]]; then
    RESULTS+=("$host|PROBED :$port $engine"); return 0
  fi

  # -- baseline playback, from a cold open ---------------------------------
  local ok=1 secs
  secs="$(play_and_measure "$host:$port" "$BASE_URL" "$PLAY_SECONDS")"
  if [[ "$secs" == "REJECTED" ]]; then
    fail "Player.Open rejected"
    RESULTS+=("$host|OPEN-REJECTED"); return 1
  elif [[ "${secs:-0}" -gt 3 ]]; then
    pass "baseline (h264/aac) advanced to ${secs}s"
  else
    fail "baseline (h264/aac) did not advance (${secs:-0}s) — connected but not playing"
    ok=0
  fi

  # -- the two runtime checks the release gate cannot make -------------------
  # Engine-aware: the two engines emit completely different evidence, and asserting ExoPlayer's tags
  # against a libmpv box failed a receiver that was demonstrably playing (2026-08-04, Hisense .106).
  if [[ $adb_ok -eq 1 ]]; then
    local log frames astart astop
    log="$(adb -s "$serial" logcat -d -t 4000 2>/dev/null)"

    if [[ "$engine" == "mpv" ]]; then
      # libmpv announces its chosen video output; a failed EGL surface (no visible foreground
      # window) prints "Could not create EGL surface" and no VO line at all.
      if grep -q "VO: \[" <<<"$log"; then
        pass "video output up ($(grep -o 'VO: \[[^]]*\][^|]*' <<<"$log" | tail -1 | cut -c1-40))"
      else
        fail "no VO line — libmpv never initialised video output"
        grep -q "Could not create EGL surface" <<<"$log" &&
          info "cause: EGL surface refused — the app must be FOREGROUND and visible to render"
        ok=0
      fi
    else
      frames="$(adb -s "$serial" shell "run-as com.fourseveneightnine.tv cat files/receiver-diagnostics.log 2>/dev/null | tail -40" 2>/dev/null | grep -c 'firstFrameRendered | true')"
      if [[ "${frames:-0}" -gt 0 ]]; then pass "video rendered a frame"; else fail "no frame rendered"; ok=0; fi
    fi

    if [[ "$engine" == "mpv" ]]; then
      # libmpv drives AudioTrack itself and prints the negotiated output, e.g.
      # "AO: [audiotrack] 48000Hz 5.1 6ch float" — which also proves the channel layout.
      local ao
      ao="$(grep -o 'AO: \[[^]]*\][^\\]*' <<<"$log" | tail -1 | cut -c1-48)"
      if [[ -n "$ao" ]]; then pass "audio output up ($ao)"; else fail "libmpv never opened an audio output"; ok=0; fi
      if grep -q "FATAL EXCEPTION" <<<"$log"; then fail "crash in logcat"; ok=0; fi
      rpc "$host:$port" '{"jsonrpc":"2.0","id":1,"method":"Player.Stop","params":{"playerid":1}}' >/dev/null
      local sur_m
      sur_m="$(play_and_measure "$host:$port" "$SURROUND_URL" 12)"
      if [[ "$sur_m" != "REJECTED" && "${sur_m:-0}" -gt 3 ]]; then
        pass "surround (h264/ac3 5.1) advanced to ${sur_m}s"
      else
        warn "surround (h264/ac3 5.1) stalled at ${sur_m:-0}s — this box cannot play AC3 5.1"
      fi
      rpc "$host:$port" '{"jsonrpc":"2.0","id":1,"method":"Player.Stop","params":{"playerid":1}}' >/dev/null
      if [[ $ok -eq 1 ]]; then RESULTS+=("$host|OK :$port $engine"); return 0
      else RESULTS+=("$host|FAILED :$port $engine"); return 1; fi
    fi

    # The app's own diagnostic is the portable signal: `exo.audio.trackInit` proves the sink was
    # configured, and carries the encoding + channel mask. The platform's APM start/stopOutput lines
    # are vendor-dependent — Fire OS prints them, Hisense does not — so they refine the verdict but
    # must never be the only evidence (a healthy Hisense read as "audio never started", 2026-08-04).
    local track
    track="$(adb -s "$serial" shell "run-as com.fourseveneightnine.tv cat files/receiver-diagnostics.log 2>/dev/null | tail -40" 2>/dev/null | grep 'exo.audio.trackInit' | tail -1)"
    if [[ -z "$track" ]]; then
      fail "audio sink never initialised (no exo.audio.trackInit)"
      ok=0
    else
      pass "audio sink up ($(sed 's/.*trackInit | //' <<<"$track" | cut -c1-46))"
    fi

    astart="$(grep 'startOutput()' <<<"$log" | tail -1 | awk '{print $2}')"
    astop="$(echo "$log" | grep 'stopOutput()'  | tail -1 | awk '{print $2}')"
    if [[ -z "$astart" ]]; then
      info "platform APM logs unavailable on this device — sink evidence above stands"
    elif [[ -n "$astop" ]] && [[ "$astop" > "$astart" ]]; then
      local held
      held="$(python3 - "$astart" "$astop" <<'PY'
import sys, datetime
def t(s):
    h, m, rest = s.split(":")
    return datetime.timedelta(hours=int(h), minutes=int(m), seconds=float(rest)).total_seconds()
print(round(t(sys.argv[2]) - t(sys.argv[1]), 2))
PY
)"
      if python3 -c "import sys; sys.exit(0 if float('$held') >= $MIN_AUDIO_SECONDS else 1)"; then
        pass "audio output held ${held}s"
      else
        fail "audio output collapsed after ${held}s (needs >= ${MIN_AUDIO_SECONDS}s) — the 0.1.22 audio-route regression signature"
        ok=0
      fi
    else
      pass "audio output still running (no stopOutput seen)"
    fi

    if grep -q "FATAL EXCEPTION" <<<"$log"; then fail "crash in logcat"; ok=0; fi
  else
    warn "no adb on this box — cannot check frames or audio output. Enable ADB debugging for full coverage."
  fi

  rpc "$host:$port" '{"jsonrpc":"2.0","id":1,"method":"Player.Stop","params":{"playerid":1}}' >/dev/null

  # -- surround: a CAPABILITY, not a verdict --------------------------------
  # A box that cannot do AC3 5.1 is limited, not broken. Reporting this separately is what stops
  # "no surround support" from looking identical to "dead receiver".
  local sur
  sur="$(play_and_measure "$host:$port" "$SURROUND_URL" 12)"
  if [[ "$sur" != "REJECTED" && "${sur:-0}" -gt 3 ]]; then
    pass "surround (h264/ac3 5.1) advanced to ${sur}s"
  else
    warn "surround (h264/ac3 5.1) stalled at ${sur:-0}s — this box cannot play AC3 5.1"
  fi
  rpc "$host:$port" '{"jsonrpc":"2.0","id":1,"method":"Player.Stop","params":{"playerid":1}}' >/dev/null

  if [[ $ok -eq 1 ]]; then RESULTS+=("$host|OK :$port $engine"); return 0
  else RESULTS+=("$host|FAILED :$port $engine"); return 1; fi
}

# ---------------------------------------------------------------- discovery
echo "4789 TV receiver verification"
[[ $DO_INSTALL -eq 1 ]] && echo "APK: $APK"

declare -a BOXES=()
while read -r serial _; do
  [[ -z "$serial" || "$serial" == "List" ]] && continue
  host="${serial%%:*}"
  BOXES+=("$serial|$host")
done < <(adb devices 2>/dev/null | tail -n +2 | grep -w device)

for h in "${EXTRA_HOSTS[@]:-}"; do
  [[ -z "$h" ]] && continue
  dup=0
  for b in "${BOXES[@]:-}"; do [[ "${b#*|}" == "$h" ]] && dup=1; done
  [[ $dup -eq 0 ]] && BOXES+=("-|$h")
done

if [[ ${#BOXES[@]} -eq 0 ]]; then
  echo "${RED}no boxes found.${OFF} Connect one with: adb connect <ip>:5555   (or pass --host <ip>)"
  exit 1
fi
if [[ -n "$ONLY_HOST" ]]; then
  declare -a KEEP=()
  for b in "${BOXES[@]}"; do [[ "${b#*|}" == "$ONLY_HOST" ]] && KEEP+=("$b"); done
  BOXES=("${KEEP[@]:-}")
  [[ -n "${BOXES[0]:-}" ]] || { echo "${RED}--only $ONLY_HOST matched no box${OFF}"; exit 1; }
fi
echo "boxes: ${#BOXES[@]}"
[[ $PROBE_ONLY -eq 0 ]] && echo "${YEL}note: each box is force-stopped for a cold open — playback on it WILL be interrupted${OFF}"

if [[ $PROBE_ONLY -eq 0 ]]; then
  make_clip
  serve_clip
fi

FAILED=0
for b in "${BOXES[@]}"; do
  verify_box "${b%%|*}" "${b#*|}" || FAILED=1
done

echo
echo "${DIM}════════════════════════ SUMMARY ═══════════════════════${OFF}"
for r in "${RESULTS[@]}"; do
  host="${r%%|*}"; state="${r#*|}"
  case "$state" in
    OK*)     printf "  ${GRN}%-14s %s${OFF}\n" "$host" "$state" ;;
    PROBED*) printf "  %-14s %s\n" "$host" "$state" ;;
    *)       printf "  ${RED}%-14s %s${OFF}\n" "$host" "$state" ;;
  esac
done
echo
[[ $FAILED -eq 0 ]] && echo "${GRN}all boxes passed${OFF}" || echo "${RED}at least one box failed${OFF}"
exit $FAILED
