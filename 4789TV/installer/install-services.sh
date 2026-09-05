#!/usr/bin/env bash
# Install and start the two processes that make the saved phone shortcut work:
# the local ADB installer and the named Cloudflare Tunnel. Both are kept alive by launchd.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
USER_ID="$(id -u)"
DOMAIN="install.4789library.com"
AGENT_DIR="${HOME:?}/Library/LaunchAgents"
LOG_DIR="${HOME:?}/Library/Logs"
DOMAIN_TARGET="gui/$USER_ID"

command -v launchctl >/dev/null || { echo "✗ launchctl is required on macOS" >&2; exit 1; }
command -v cloudflared >/dev/null || { echo "✗ cloudflared is not installed — brew install cloudflared" >&2; exit 1; }
[[ -f "${HOME:?}/.cloudflared/config.yml" ]] || {
  echo "✗ no named tunnel config at ~/.cloudflared/config.yml" >&2
  echo "  Create the 4789-installer tunnel and route $DOMAIN once, then retry." >&2
  exit 1
}

mkdir -p "$AGENT_DIR" "$LOG_DIR"
TV_WORKSPACE="$(cd "$HERE/.." && pwd)"
for name in server tunnel; do
  sed -e "s|__TV_WORKSPACE__|$TV_WORKSPACE|g" -e "s|__OWNER_HOME__|${HOME:?}|g" \
    "$HERE/launchd/com.4789.installer.$name.plist" > "$AGENT_DIR/com.4789.installer.$name.plist"
done

for label in com.4789.installer.server com.4789.installer.tunnel; do
  launchctl bootout "$DOMAIN_TARGET/$label" >/dev/null 2>&1 || true
  for _ in $(seq 1 20); do
    launchctl print "$DOMAIN_TARGET/$label" >/dev/null 2>&1 || break
    sleep 0.25
  done
done

# A previous `public-link.sh` could have left detached processes behind. Stop only a Python
# process that is actually listening on the installer port and has the installer arguments;
# never stop an unrelated web server.
for pid in $(lsof -ti tcp:14789 -sTCP:LISTEN 2>/dev/null || true); do
  command_line="$(ps -p "$pid" -o command= 2>/dev/null || true)"
  if [[ "$command_line" == *"serve.py --lan"* ]]; then
    kill "$pid" 2>/dev/null || true
  fi
done
for _ in $(seq 1 20); do
  lsof -ti tcp:14789 -sTCP:LISTEN >/dev/null 2>&1 || break
  sleep 0.25
done
for pid in $(pgrep -f 'cloudflared tunnel run 4789-installer' 2>/dev/null || true); do
  kill "$pid" 2>/dev/null || true
done

launchctl bootstrap "$DOMAIN_TARGET" "$AGENT_DIR/com.4789.installer.server.plist"
launchctl bootstrap "$DOMAIN_TARGET" "$AGENT_DIR/com.4789.installer.tunnel.plist"
launchctl kickstart -k "$DOMAIN_TARGET/com.4789.installer.server"
launchctl kickstart -k "$DOMAIN_TARGET/com.4789.installer.tunnel"

echo "✓ 4789 installer and Cloudflare Tunnel are now supervised by launchd"
echo "  public: https://$DOMAIN"
echo "  logs:   $LOG_DIR/4789-installer-server.log"
echo "          $LOG_DIR/4789-installer-tunnel.log"
