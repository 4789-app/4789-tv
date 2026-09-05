#!/usr/bin/env bash
# Put the installer on the public internet and print the one link the phone needs.
#
#   ./public-link.sh                 # stable address, supervised by macOS
#   ./public-link.sh tv.example.com  # your own hostname, the same every run
#
# The second form needs a one-time setup you have to do yourself, because it opens a browser:
#
#   cloudflared tunnel login
#   cloudflared tunnel create 4789-installer
#   cloudflared tunnel route dns 4789-installer tv.example.com
#
# SECURITY. This publishes a server that installs software on televisions. It is reachable by
# anyone who knows the address, so the key in the link is the only thing standing between the
# internet and your TVs. Do not post the link anywhere. The stable mode is kept alive by launchd;
# use the local link only on a trusted home network.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HOSTNAME_ARG="${1:-}"
PORT=14789

command -v cloudflared >/dev/null || { echo "✗ cloudflared not installed — brew install cloudflared"; exit 1; }

cleanup() { [[ -n "${TUNNEL_PID:-}" ]] && kill "$TUNNEL_PID" 2>/dev/null; [[ -n "${SERVE_PID:-}" ]] && kill "$SERVE_PID" 2>/dev/null; }
trap cleanup EXIT INT TERM

# The installer itself. --lan is what turns on the key check that the tunnel then depends on.
if lsof -ti "tcp:$PORT" >/dev/null 2>&1; then
  echo "▸ installer already listening on $PORT"
else
  echo "▸ starting the installer"
  python3 "$HERE/serve.py" --lan --no-open >/tmp/4789-installer.log 2>&1 &
  SERVE_PID=$!
  sleep 2
fi

KEY="$(cat "$HOME/.4789-installer-token" 2>/dev/null)"
[[ -n "$KEY" ]] || { echo "✗ no key yet — the installer did not start; see /tmp/4789-installer.log"; exit 1; }

LOG=/tmp/4789-tunnel.log
: >"$LOG"

# The named tunnel is the good case: one address that never changes. It exists once
# `cloudflared tunnel create` has run and a hostname has been routed to it.
NAMED_TUNNEL="4789-installer"
DEFAULT_HOST="install.4789library.com"
if [[ -z "$HOSTNAME_ARG" ]] && cloudflared tunnel info "$NAMED_TUNNEL" >/dev/null 2>&1; then
  echo "▸ enabling launchd supervision for the stable installer link"
  "$HERE/install-services.sh"

  for _ in $(seq 1 30); do
    KEY="$(cat "$HOME/.4789-installer-token" 2>/dev/null || true)"
    [[ -n "$KEY" ]] && break
    sleep 1
  done
  [[ -n "$KEY" ]] || { echo "✗ the installer did not create its key"; exit 1; }

  LINK="https://$DEFAULT_HOST/m?k=$KEY"
  CODE="000"
  for _ in $(seq 1 30); do
    CODE="$(curl -sS -o /dev/null -w '%{http_code}' --connect-timeout 4 --max-time 10 "$LINK" || true)"
    [[ "$CODE" == "200" ]] && break
    sleep 1
  done

  echo
  echo "  $LINK"
  echo
  if [[ "$CODE" == "200" ]]; then
    echo "  ✓ answering ($CODE)"
    echo "  In Safari: Share → Add to Home Screen → Add. Delete the old shortcut first if it opens Safari."
    echo "  This link survives Mac restarts and Wi-Fi changes while this Mac is awake and online."
  else
    echo "  ! the public link answered $CODE — see ~/Library/Logs/4789-installer-tunnel.log"
  fi
  echo
  exit 0
fi

if [[ -n "$HOSTNAME_ARG" ]]; then
  echo "▸ opening the tunnel on $HOSTNAME_ARG"
  if cloudflared tunnel info "$NAMED_TUNNEL" >/dev/null 2>&1; then
    cloudflared tunnel --no-autoupdate --protocol http2 run "$NAMED_TUNNEL" >"$LOG" 2>&1 &
  else
    cloudflared tunnel --no-autoupdate --hostname "$HOSTNAME_ARG" --url "http://127.0.0.1:$PORT" >"$LOG" 2>&1 &
  fi
  TUNNEL_PID=$!
  PUBLIC="https://$HOSTNAME_ARG"
else
  echo "▸ opening a throwaway tunnel"
  cloudflared tunnel --no-autoupdate --protocol http2 --url "http://127.0.0.1:$PORT" >"$LOG" 2>&1 &
  TUNNEL_PID=$!
  PUBLIC=""
  for _ in $(seq 1 30); do
    PUBLIC="$(grep -oE 'https://[a-z0-9-]+\.trycloudflare\.com' "$LOG" | head -1)"
    [[ -n "$PUBLIC" ]] && break
    sleep 1
  done
  [[ -n "$PUBLIC" ]] || { echo "✗ the tunnel never reported an address; see $LOG"; exit 1; }
fi

LINK="$PUBLIC/m?k=$KEY"
sleep 3
CODE="$(curl -s -o /dev/null -w '%{http_code}' --max-time 20 "$LINK" || echo "000")"

echo
echo "  $LINK"
echo
if [[ "$CODE" == "200" ]]; then
  echo "  ✓ answering ($CODE). Open it on the iPhone, then Share → Add to Home Screen."
else
  echo "  ! the address answered $CODE — give the tunnel a few seconds and reload."
fi
echo "  Leave this window open. Ctrl-C takes the link down."
echo

wait
