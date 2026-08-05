#!/usr/bin/env bash
# Put the installer on the public internet and print the one link the phone needs.
#
#   ./public-link.sh                 # throwaway address, new every run
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
# internet and your TVs. Do not post the link anywhere. Ctrl-C stops both halves.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HOSTNAME_ARG="${1:-}"
PORT=4789

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
# Set INSTALLER_HOST to your own hostname, or pass one as the first argument. There is
# deliberately no default: a shared default would point every user at one person's machine.
DEFAULT_HOST="${INSTALLER_HOST:-}"
if [[ -z "$HOSTNAME_ARG" ]] && cloudflared tunnel info "$NAMED_TUNNEL" >/dev/null 2>&1; then
  HOSTNAME_ARG="$DEFAULT_HOST"
fi

if [[ -n "$HOSTNAME_ARG" ]]; then
  echo "▸ opening the tunnel on $HOSTNAME_ARG"
  if cloudflared tunnel info "$NAMED_TUNNEL" >/dev/null 2>&1; then
    cloudflared tunnel --no-autoupdate run "$NAMED_TUNNEL" >"$LOG" 2>&1 &
  else
    cloudflared tunnel --no-autoupdate --hostname "$HOSTNAME_ARG" --url "http://127.0.0.1:$PORT" >"$LOG" 2>&1 &
  fi
  TUNNEL_PID=$!
  PUBLIC="https://$HOSTNAME_ARG"
else
  echo "▸ opening a throwaway tunnel"
  cloudflared tunnel --no-autoupdate --url "http://127.0.0.1:$PORT" >"$LOG" 2>&1 &
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
