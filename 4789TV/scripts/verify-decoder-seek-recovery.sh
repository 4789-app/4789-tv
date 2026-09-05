#!/usr/bin/env bash
# Prove that rapid forward/backward seeks do not masquerade as a frozen decoder.
#
# The receiver once kept the pre-seek playhead as its progress baseline. On a backward seek that
# made healthy playback look frozen for 12 seconds and surfaced a false decoder-stall failure.
# This probe serves a fully buffered local clip, repeats that sequence, then requires continued
# progress with no decoder recovery or terminal decoder failure. It is not an allocator-failure
# reproduction tool.

set -euo pipefail

# No default receiver address. This probe installs to and drives a real box, so it must
# never guess which one; pass the address explicitly or set RECEIVER_HOST.
HOST="${1:-${RECEIVER_HOST:-}}"
if [[ -z "$HOST" ]]; then
  echo "usage: $0 <receiver-ip>   (or set RECEIVER_HOST)" >&2
  exit 2
fi
ENDPOINT="http://${HOST}:8791/jsonrpc"
PACKAGE="com.fourseveneightnine.tv"
SERIAL="${HOST}:5555"

if ! adb -s "$SERIAL" shell true >/dev/null 2>&1; then
  SERIAL="$(adb devices | awk -v host="$HOST" '$1 ~ "^" host ":" && $2 == "device" { print $1; exit }')"
fi
if [[ -z "$SERIAL" ]]; then
  echo "FAIL no ADB connection for $HOST" >&2
  exit 1
fi

MAC_IP="$(ipconfig getifaddr en0 2>/dev/null || true)"
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

echo "building 120-second h264/aac seek fixture"
ffmpeg -y -loglevel error \
  -f lavfi -i "testsrc2=size=1280x720:rate=24:duration=120" \
  -f lavfi -i "sine=frequency=440:duration=120:sample_rate=48000" \
  -c:v libx264 -pix_fmt yuv420p -preset ultrafast -g 48 \
  -c:a aac -b:a 128k -movflags +faststart "$CLIP"

SERVE_PORT="$(python3 -c 'import socket; s=socket.socket(); s.bind(("", 0)); print(s.getsockname()[1]); s.close()')"
(
  cd "$TEST_DIR"
  exec python3 -m http.server "$SERVE_PORT" --bind 0.0.0.0 >"$SERVER_LOG" 2>&1
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
