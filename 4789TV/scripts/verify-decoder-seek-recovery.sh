#!/usr/bin/env bash
# Prove that rapid forward/backward seeks do not masquerade as a frozen decoder.
#
# The receiver once kept the pre-seek playhead as its progress baseline. On a backward seek that
# made healthy playback look frozen for 12 seconds and surfaced a false decoder-stall failure.
# This probe serves a fully buffered local clip, repeats that sequence, then requires continued
# progress with no decoder recovery or terminal decoder failure. It is not an allocator-failure
# reproduction tool. It also verifies the real D-pad path after playback with hidden controls.
# Set REMOTE_IDLE_SECONDS=600 for the ten-minute idle regression.

set -euo pipefail

# No default receiver address. This probe installs to and drives a real box, so it must
# never guess which one; pass the address explicitly or set RECEIVER_HOST.
HOST="${1:-${RECEIVER_HOST:-}}"
if [[ -z "$HOST" ]]; then
  echo "usage: $0 <receiver-ip>   (or set RECEIVER_HOST)" >&2
  exit 2
fi
ENDPOINT="${RECEIVER_ENDPOINT:-http://${HOST}:8791/jsonrpc}"
PACKAGE="com.fourseveneightnine.tv"
SERIAL="${RECEIVER_SERIAL:-${HOST}:5555}"
REMOTE_IDLE_SECONDS="${REMOTE_IDLE_SECONDS:-15}"
[[ "$REMOTE_IDLE_SECONDS" =~ ^[0-9]+$ && "$REMOTE_IDLE_SECONDS" -le 900 ]] || {
  echo "FAIL REMOTE_IDLE_SECONDS must be an integer from 0 to 900" >&2
  exit 2
}
CLIP_SECONDS=$((REMOTE_IDLE_SECONDS + 120))

if ! adb -s "$SERIAL" shell true >/dev/null 2>&1; then
  SERIAL="$(adb devices | awk -v host="$HOST" '$1 ~ "^" host ":" && $2 == "device" { print $1; exit }')"
fi
if [[ -z "$SERIAL" ]]; then
  echo "FAIL no ADB connection for $HOST" >&2
  exit 1
fi

MAC_IP="${FIXTURE_HOST:-$(ipconfig getifaddr en0 2>/dev/null || true)}"
if [[ -z "$MAC_IP" ]]; then
  echo "FAIL no LAN address on en0" >&2
  exit 1
fi

TEST_DIR="$(mktemp -d)"
CLIP="$TEST_DIR/seek-regression.mp4"
SERVER_LOG="$TEST_DIR/server.log"
BURST_RESPONSE_65="$TEST_DIR/burst-65.json"
BURST_RESPONSE_70="$TEST_DIR/burst-70.json"
BURST_RESPONSE_75="$TEST_DIR/burst-75.json"
SERVER_PID=""
OPENED=0

cleanup() {
  if [[ "$OPENED" == 1 ]]; then
    curl -s -m 5 -X POST "$ENDPOINT" -H 'Content-Type: application/json' \
      -d '{"jsonrpc":"2.0","id":99,"method":"Player.Stop","params":{"playerid":1}}' >/dev/null || true
  fi
  if [[ -n "$SERVER_PID" ]]; then
    kill "$SERVER_PID" 2>/dev/null || true
    wait "$SERVER_PID" 2>/dev/null || true
  fi
  rm -f -- "$CLIP" "$SERVER_LOG" "$BURST_RESPONSE_65" "$BURST_RESPONSE_70" "$BURST_RESPONSE_75"
  rmdir "$TEST_DIR" 2>/dev/null || true
}
trap cleanup EXIT

echo "building ${CLIP_SECONDS}-second h264/aac seek fixture"
ffmpeg -y -loglevel error \
  -f lavfi -i "testsrc2=size=1280x720:rate=24:duration=${CLIP_SECONDS}" \
  -f lavfi -i "sine=frequency=440:duration=${CLIP_SECONDS}:sample_rate=48000" \
  -c:v libx264 -pix_fmt yuv420p -preset ultrafast -g 48 \
  -c:a aac -b:a 128k -movflags +faststart "$CLIP"

SERVE_PORT="$(python3 -c 'import socket; s=socket.socket(); s.bind(("", 0)); print(s.getsockname()[1]); s.close()')"
(
  cd "$TEST_DIR"
  # Media3 must be able to fetch a seek's byte range. SimpleHTTPRequestHandler ignores Range
  # and makes a long fixture re-download its entire prefix, testing the server instead of seeks.
  exec python3 - "$SERVE_PORT" >"$SERVER_LOG" 2>&1 <<'PY'
import http.server
import pathlib
import re
import sys

clip = pathlib.Path("seek-regression.mp4")

class FixtureHandler(http.server.BaseHTTPRequestHandler):
    def do_HEAD(self):
        self.serve_clip(body=False)

    def do_GET(self):
        self.serve_clip(body=True)

    def serve_clip(self, body):
        if self.path.split("?", 1)[0] != "/seek-regression.mp4":
            self.send_error(404)
            return
        size = clip.stat().st_size
        start, end = 0, size - 1
        byte_range = self.headers.get("Range")
        if byte_range:
            match = re.fullmatch(r"bytes=(\d*)-(\d*)", byte_range)
            if not match or not any(match.groups()):
                self.send_error(416)
                return
            first, last = match.groups()
            if first:
                start = int(first)
                end = min(int(last), end) if last else end
            else:
                start = max(0, size - int(last))
            if start > end:
                self.send_error(416)
                return
        self.send_response(206 if byte_range else 200)
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Type", "video/mp4")
        self.send_header("Content-Length", str(end - start + 1))
        if byte_range:
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        self.end_headers()
        if body:
            with clip.open("rb") as source:
                source.seek(start)
                remaining = end - start + 1
                try:
                    while remaining:
                        data = source.read(min(64 * 1024, remaining))
                        if not data:
                            break
                        self.wfile.write(data)
                        remaining -= len(data)
                except (BrokenPipeError, ConnectionResetError):
                    pass

http.server.ThreadingHTTPServer(("0.0.0.0", int(sys.argv[1])), FixtureHandler).serve_forever()
PY
) &
SERVER_PID=$!

for _ in $(seq 1 40); do
  if curl -s -m 2 -o /dev/null "http://127.0.0.1:${SERVE_PORT}/seek-regression.mp4"; then
    break
  fi
  sleep 0.25
done

rpc() {
  curl -s -m 5 -X POST "$ENDPOINT" -H 'Content-Type: application/json' -d "$1"
}

rpc_checked() {
  local label="$1"
  local payload="$2"
  local response
  response="$(rpc "$payload")"
  if ! jq -e '.error == null and .result != null' >/dev/null 2>&1 <<<"$response"; then
    echo "FAIL ${label} rejected: ${response}" >&2
    exit 1
  fi
}

rpc_response_checked() {
  local label="$1"
  local response_file="$2"
  if ! jq -e '.error == null and .result != null' >/dev/null 2>&1 <"$response_file"; then
    echo "FAIL ${label} rejected: $(<"$response_file")" >&2
    exit 1
  fi
}

position_seconds() {
  local response
  response="$(rpc '{"jsonrpc":"2.0","id":9,"method":"Player.GetProperties","params":{"playerid":1,"properties":["time","speed"]}}')"
  if ! jq -e '.error == null and .result != null' >/dev/null 2>&1 <<<"$response"; then
    echo "FAIL Player.GetProperties rejected: ${response}" >&2
    exit 1
  fi
  jq -r '((.result.time.hours // 0) * 3600) + ((.result.time.minutes // 0) * 60) + (.result.time.seconds // 0) + ((.result.time.milliseconds // 0) / 1000)' <<<"$response"
}

# ReceiverDiagnostics trims its bounded log; a pre-run line count becomes invalid after trim.
# Compare the timestamp of the last existing record instead of using a mutable line offset.
START_TIME="$(adb -s "$SERIAL" shell "run-as $PACKAGE tail -1 files/receiver-diagnostics.log" | awk '{print $1}')"
[[ "$START_TIME" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T ]] || { echo "FAIL no diagnostic time boundary" >&2; exit 1; }
CLIP_URL="http://${MAC_IP}:${SERVE_PORT}/seek-regression.mp4"
OPENED=1
rpc_checked "Player.Open" "$(jq -nc --arg file "$CLIP_URL" '{jsonrpc:"2.0",id:1,method:"Player.Open",params:{item:{file:$file}}}')"

PRE_SEEK=0
for _ in $(seq 1 30); do
  PRE_SEEK="$(position_seconds)"
  if awk -v position="$PRE_SEEK" 'BEGIN { exit !(position >= 15) }'; then
    break
  fi
  sleep 1
done
if ! awk -v position="$PRE_SEEK" 'BEGIN { exit !(position >= 15) }'; then
  echo "FAIL fixture never reached 15 seconds (last=$PRE_SEEK)" >&2
  exit 1
fi
echo "pre-seek position: ${PRE_SEEK}s"
STARTUP_LOG="$(adb -s "$SERIAL" shell "run-as $PACKAGE cat files/receiver-diagnostics.log" | awk -v start="$START_TIME" '$1 > start')"
if ! grep -Fq ' | exo.firstFrameRendered |' <<<"$STARTUP_LOG"; then
  echo "FAIL no rendered video frame; select a TV profile and foreground the player before testing" >&2
  exit 1
fi

# Wire property reads do not warm the TV's UI snapshot. Leave the controls hidden, then send
# actual remote keys: a controller-only seek test cannot catch the stale absolute UI target.
echo "leaving playback untouched for ${REMOTE_IDLE_SECONDS}s with controls hidden"
IDLE_REMAINING="$REMOTE_IDLE_SECONDS"
IDLE_POSITION="$PRE_SEEK"
while ((IDLE_REMAINING > 0)); do
  IDLE_STEP=$((IDLE_REMAINING < 30 ? IDLE_REMAINING : 30))
  sleep "$IDLE_STEP"
  CURRENT_IDLE_POSITION="$(position_seconds)"
  # Check forward progress, not emulator throughput relative to the host's wall clock.
  if ! awk -v before="$IDLE_POSITION" -v after="$CURRENT_IDLE_POSITION" \
      'BEGIN { exit !(after - before > 0.05) }'; then
    echo "FAIL idle playback stopped advancing: ${IDLE_POSITION}s -> ${CURRENT_IDLE_POSITION}s" >&2
    exit 1
  fi
  IDLE_REMAINING=$((IDLE_REMAINING - IDLE_STEP))
  IDLE_POSITION="$CURRENT_IDLE_POSITION"
  echo "idle $((REMOTE_IDLE_SECONDS - IDLE_REMAINING))/${REMOTE_IDLE_SECONDS}s: position=${IDLE_POSITION}s"
done
BEFORE_REMOTE="$(position_seconds)"
adb -s "$SERIAL" shell input keyevent KEYCODE_DPAD_RIGHT
sleep 1
AFTER_RIGHT="$(position_seconds)"
adb -s "$SERIAL" shell input keyevent KEYCODE_DPAD_LEFT
sleep 1
AFTER_LEFT="$(position_seconds)"
echo "hidden remote: ${BEFORE_REMOTE}s -> Right ${AFTER_RIGHT}s -> Left ${AFTER_LEFT}s"
if ! awk -v before="$BEFORE_REMOTE" -v right="$AFTER_RIGHT" -v left="$AFTER_LEFT" \
    'BEGIN { exit !(right - before >= 9 && right - before <= 14 && left - right >= -11 && left - right <= -7) }'; then
  echo "HIDDEN_REMOTE_SEEK_FAIL"
  exit 1
fi
echo "HIDDEN_REMOTE_SEEK_PASS"

rpc_checked "pause for rapid remote taps" '{"jsonrpc":"2.0","id":20,"method":"Player.PlayPause","params":{"playerid":1,"play":false}}'
sleep 2
BEFORE_TAPS="$(position_seconds)"
for _ in 1 2 3; do
  adb -s "$SERIAL" shell input keyevent KEYCODE_DPAD_RIGHT
done
sleep 1
AFTER_TAPS="$(position_seconds)"
echo "three paused remote taps: ${BEFORE_TAPS}s -> ${AFTER_TAPS}s"
if ! awk -v before="$BEFORE_TAPS" -v after="$AFTER_TAPS" \
    'BEGIN { delta = after - before; exit !(delta >= 29 && delta <= 31) }'; then
  echo "RAPID_REMOTE_SEEK_FAIL"
  exit 1
fi
echo "RAPID_REMOTE_SEEK_PASS"
rpc_checked "resume after rapid remote taps" '{"jsonrpc":"2.0","id":21,"method":"Player.PlayPause","params":{"playerid":1,"play":true}}'

# Launch three forward commands concurrently inside the 250ms coalescing window, then issue a
# separate sharp backward seek. A final forward seek gives three settled boundaries to audit.
BURST_STARTED_NS="$(python3 -c 'import time; print(time.time_ns())')"
rpc '{"jsonrpc":"2.0","id":2,"method":"Player.Seek","params":{"playerid":1,"value":{"percentage":65}}}' >"$BURST_RESPONSE_65" &
BURST_PID_65=$!
rpc '{"jsonrpc":"2.0","id":3,"method":"Player.Seek","params":{"playerid":1,"value":{"percentage":70}}}' >"$BURST_RESPONSE_70" &
BURST_PID_70=$!
rpc '{"jsonrpc":"2.0","id":4,"method":"Player.Seek","params":{"playerid":1,"value":{"percentage":75}}}' >"$BURST_RESPONSE_75" &
BURST_PID_75=$!
BURST_ENDED_NS="$(python3 -c 'import time; print(time.time_ns())')"
BURST_MS=$(( (BURST_ENDED_NS - BURST_STARTED_NS) / 1000000 ))
echo "burst launch: ${BURST_MS}ms"
if [[ "$BURST_MS" -ge 250 ]]; then
  echo "FAIL seek burst exceeded coalescing window: ${BURST_MS}ms" >&2
  exit 1
fi
for burst_pid in "$BURST_PID_65" "$BURST_PID_70" "$BURST_PID_75"; do
  if ! wait "$burst_pid"; then
    echo "FAIL burst RPC transport failed" >&2
    exit 1
  fi
done
rpc_response_checked "burst seek 65%" "$BURST_RESPONSE_65"
rpc_response_checked "burst seek 70%" "$BURST_RESPONSE_70"
rpc_response_checked "burst seek 75%" "$BURST_RESPONSE_75"
sleep 2
rpc_checked "sharp backward seek 5%" '{"jsonrpc":"2.0","id":5,"method":"Player.Seek","params":{"playerid":1,"value":{"percentage":5}}}'
sleep 2
rpc_checked "post-backward seek 35%" '{"jsonrpc":"2.0","id":6,"method":"Player.Seek","params":{"playerid":1,"value":{"percentage":35}}}'

# The snapshot advertises an asynchronous pending target before a frame is rendered. Start the
# decoder-progress interval only after the final target lands, not while seeks are still queued.
FINAL_TARGET_MS=$((CLIP_SECONDS * 350))
FINAL_FRAME_LANDED=0
for _ in $(seq 1 48); do
  SETTLE_LOG="$(adb -s "$SERIAL" shell "run-as $PACKAGE cat files/receiver-diagnostics.log" | awk -v start="$START_TIME" '$1 > start')"
  if grep -Eq "exo.seek.frame.accepted.*targetMs=${FINAL_TARGET_MS}([[:space:]]|$)" <<<"$SETTLE_LOG"; then
    FINAL_FRAME_LANDED=1
    break
  fi
  sleep 0.25
done
if [[ "$FINAL_FRAME_LANDED" -ne 1 ]]; then
  echo "FAIL final seek did not render its target" >&2
  exit 1
fi
AFTER_SEEK="$(position_seconds)"
# Deliberately cross the 12-second starvation threshold that produced the false error.
sleep 14
AFTER_THRESHOLD="$(position_seconds)"
echo "post-seek position: ${AFTER_SEEK}s -> ${AFTER_THRESHOLD}s"

NEW_LOG="$(adb -s "$SERIAL" shell "run-as $PACKAGE cat files/receiver-diagnostics.log" | awk -v start="$START_TIME" '$1 > start')"
SEEK_COUNT="$(grep -c ' | exo.seek.discontinuity |' <<<"$NEW_LOG" || true)"
RECOVERY_COUNT="$(grep -c ' | exo.decoder.recover |' <<<"$NEW_LOG" || true)"
TERMINAL_COUNT="$(grep -c ' | exo.decoder.terminal |' <<<"$NEW_LOG" || true)"
FRAME_COUNT="$(grep -c ' | exo.seek.frame.accepted |' <<<"$NEW_LOG" || true)"
DEFER_COUNT="$(grep -c 'exo.seek.submit.*decision=Defer' <<<"$NEW_LOG" || true)"
OPEN_COUNT="$(grep -c ' | exo.open.begin |' <<<"$NEW_LOG" || true)"
echo "diagnostics: opens=$OPEN_COUNT defers=$DEFER_COUNT seeks=$SEEK_COUNT frames=$FRAME_COUNT decoderRecoveries=$RECOVERY_COUNT terminalFailures=$TERMINAL_COUNT"

grep -E 'exo.seek.(submit|issue|discontinuity|frame.accepted)|exo.decoder.(recover|terminal)' <<<"$NEW_LOG" |
  tail -30 || true

ADVANCED="$(awk -v start="$AFTER_SEEK" -v end="$AFTER_THRESHOLD" 'BEGIN { print (end - start >= 10) ? 1 : 0 }')"
rpc_checked "Player.Stop" '{"jsonrpc":"2.0","id":7,"method":"Player.Stop","params":{"playerid":1}}'

if [[ "$OPEN_COUNT" -ne 1 || "$DEFER_COUNT" -lt 1 || "$SEEK_COUNT" -lt 3 || "$FRAME_COUNT" -lt 3 || "$RECOVERY_COUNT" -ne 0 || "$TERMINAL_COUNT" -ne 0 || "$ADVANCED" -ne 1 ]]; then
  echo "SEEK_REGRESSION_FAIL"
  exit 1
fi

echo "SEEK_REGRESSION_PASS"
