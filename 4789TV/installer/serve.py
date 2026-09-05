#!/usr/bin/env python3
"""Local installer for the 4789 TV receiver.

A small window: type a box's IP, press Check, press Install, watch the log. Nothing here
ships with the app — it is a Mac-side tool for putting the receiver onto a TV.

Binds to 127.0.0.1 by default. It runs adb commands, so it must never be reachable from an
untrusted network.

    ./start.sh              # or: python3 serve.py
    ./start.sh --lan        # also answer on the LAN, for the phone page at /m

`--lan` exists so a phone can drive the installer from a home-screen bookmark. It is opt-in, and
it never relaxes the rule above quietly:

  * every request must carry the secret in `?k=` (the bookmark holds it), and
  * the caller's address must be a private one — loopback, RFC1918, or link-local.

The secret persists in ~/.4789-installer-token so a saved bookmark survives a restart. Anyone who
holds it can install software on any TV you can reach, so treat it like a password: `--lan` is for
a home network, never a café or an office guest VLAN.
"""
from __future__ import annotations

import binascii
import http.server
import ipaddress
import json
import os
import re
import secrets
import shutil
import socket
import struct
import subprocess
import sys
import tempfile
import threading
import urllib.parse
import urllib.request
import zlib

# TBMM already owns 4789 on this Mac. Keep the installer name stable while using a dedicated
# origin port so the public tunnel can never accidentally serve TBMM's web app.
PORT = 14789
LAN_MODE = False          # set by --lan; gates every LAN-facing behaviour below
DESKTOP_WINDOW = False    # only true when we opened the Mac Chrome window ourselves
TOKEN_FILE = os.path.expanduser("~/.4789-installer-token")
TOKEN = ""
HERE = os.path.dirname(os.path.abspath(__file__))
TV_ROOT = os.path.dirname(HERE)
APK = os.path.join(TV_ROOT, "app/build/outputs/apk/debug/app-debug.apk")
PKG = "com.fourseveneightnine.tv"
ADB_PORT = 5555
RECEIVER_PORT = 8791
MIN_SDK = 28


# Backup players worth putting on a TV, checked 2026-07. Every source is the developer's own
# release channel or F-Droid — never an APK aggregator, because a video player installed from a
# random mirror is a rootkit with a play button. Ordered by how well each one serves as a handoff
# target for this receiver: it must accept the position/subtitle/header extras we send.
PLAYERS: list[dict] = [
    {
        "id": "justplayer",
        "name": "Just Player",
        "package": "com.brouken.player",
        "why": "ExoPlayer + ffmpeg — DTS-HD/TrueHD, and it honours the position and subtitle "
               "extras our handoff sends. The best target for “open this on the TV instead”.",
        "source": "github:moneytoo/Player",
        "asset": lambda name, abi: name.startswith("Just.Player") and name.endswith(".apk")
        and "legacy" not in name,
    },
    {
        "id": "vlc",
        "name": "VLC",
        "package": "org.videolan.vlc",
        "why": "Plays anything, from anywhere (SMB/NFS/FTP), no ads. The safe fallback when a "
               "file defeats everything else.",
        "source": "videolan",
    },
    {
        "id": "kodi",
        "name": "Kodi",
        "package": "org.xbmc.kodi",
        "why": "A full media centre, and the reference for hardware video + software audio. "
               "Heavier than the rest; worth it if you want a library.",
        "source": "kodi",
    },
    {
        "id": "mpv",
        "name": "mpv",
        "package": "is.xyz.mpv",
        "why": "The same engine the receiver plays with, as a standalone app. Handoff sends it the "
               "URL only — no resume position — but it plays what the others refuse.",
        "source": "github:mpv-android/mpv-android",
        # Assets are app-default-<abi>-release.apk; never the debug or api29 variants.
        "asset": lambda name, abi: name == f"app-default-{abi}-release.apk",
    },
    {
        "id": "nextplayer",
        "name": "Next Player",
        "package": "dev.anilbeesetti.nextplayer",
        "why": "Modern ExoPlayer player from the author of the ffmpeg decoders this receiver "
               "now uses. Small, quick, per-ABI build.",
        "source": "github:anilbeesetti/nextplayer",
        "asset": lambda name, abi: name.endswith(f"-{abi}.apk"),
    },
]


def http_json(url: str, timeout: int = 25):
    request = urllib.request.Request(url, headers={"User-Agent": "4789-installer"})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.loads(response.read())


def http_text(url: str, timeout: int = 25) -> str:
    request = urllib.request.Request(url, headers={"User-Agent": "4789-installer"})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return response.read().decode("utf-8", "replace")


def resolve_player(player: dict, abi: str) -> tuple[str, str] | None:
    """(download url, version) from the developer's own channel, or None if it cannot be found."""
    source = player["source"]
    try:
        if source.startswith("github:"):
            repo = source.split(":", 1)[1]
            release = http_json(f"https://api.github.com/repos/{repo}/releases/latest")
            version = release.get("tag_name", "")
            picker = player["asset"]
            for asset in release.get("assets", []):
                if picker(asset["name"], abi):
                    return asset["browser_download_url"], version
            # A per-ABI build may not cover this device; fall back to a universal asset.
            for asset in release.get("assets", []):
                if "universal" in asset["name"] and asset["name"].endswith(".apk"):
                    return asset["browser_download_url"], version
            return None

        if source == "videolan":
            # F-Droid knows the current version; VideoLAN serves the per-ABI build.
            index = http_json("https://f-droid.org/api/v1/packages/org.videolan.vlc")
            version = ""
            for package in index.get("packages", []):
                version = package.get("versionName", "")
                if version:
                    break
            if not version:
                return None
            return (
                f"https://get.videolan.org/vlc-android/{version}/VLC-Android-{version}-{abi}.apk",
                version,
            )

        if source == "kodi":
            # Kodi names its 32-bit ARM *directory* "arm" while the files inside it still carry
            # the "-armeabi-v7a" suffix, so the folder and the filename do not match.
            is64 = abi == "arm64-v8a"
            folder = "arm64-v8a" if is64 else "arm"
            suffix = "arm64-v8a" if is64 else "armeabi-v7a"
            listing = http_text(f"https://mirrors.kodi.tv/releases/android/{folder}/")
            def version_key(name: str) -> tuple[int, ...]:
                match = re.search(r"kodi-(\d+)\.(\d+)", name)
                return (int(match.group(1)), int(match.group(2))) if match else (0, 0)

            names = sorted(
                {
                    n
                    for n in re.findall(rf'kodi-[0-9][^"<]*?-{re.escape(suffix)}\.apk', listing)
                    # Stable only: no alpha/beta/rc on someone's television.
                    if not re.search(r"(alpha|beta|_rc)", n, re.IGNORECASE)
                },
                key=version_key,
            )
            if not names:
                return None
            newest = names[-1]
            label = re.sub(rf"^kodi-|-{re.escape(suffix)}\.apk$", "", newest).replace("_", " ")
            return f"https://mirrors.kodi.tv/releases/android/{folder}/{newest}", label
    except Exception:
        return None
    return None


RECOMMENDED_APPS: dict[str, dict] = {
    "250931": {
        "name": "Stremio for Android TV",
        "url": "https://dl.strem.io/android/v1.6.12-android_tv/com.stremio.one-1.6.12-7053528-android_tv-arm64-v8a.apk",
        "code": "250931",
    },
    "stremio": {
        "name": "Stremio for Android TV",
        "url": "https://dl.strem.io/android/v1.6.12-android_tv/com.stremio.one-1.6.12-7053528-android_tv-arm64-v8a.apk",
        "code": "250931",
    },
    "nuvio": {
        "name": "Nuvio TV",
        "source": "github:NuvioMedia/NuvioTV",
        "code": "nuvio",
    },
    "smarttube": {
        "name": "SmartTube (Ad-free YouTube for TV)",
        "source": "github:yuliskov/SmartTube",
        "code": "28544",
    },
    "28544": {
        "name": "SmartTube (Ad-free YouTube for TV)",
        "source": "github:yuliskov/SmartTube",
        "code": "28544",
    },
    "tivimate": {
        "name": "TiviMate IPTV Player",
        "url": "https://tivimate.com/apk",
        "code": "272483",
    },
    "272483": {
        "name": "TiviMate IPTV Player",
        "url": "https://tivimate.com/apk",
        "code": "272483",
    },
    "downloader": {
        "name": "Downloader App (AFTVnews)",
        "url": "https://browser.aftvnews.com/downloader.apk",
        "code": "798542",
    },
    "798542": {
        "name": "Downloader App (AFTVnews)",
        "url": "https://browser.aftvnews.com/downloader.apk",
        "code": "798542",
    },
    "syncler": {
        "name": "Syncler",
        "url": "https://syncler.net/download/syncler.apk",
        "code": "66085",
    },
    "66085": {
        "name": "Syncler",
        "url": "https://syncler.net/download/syncler.apk",
        "code": "66085",
    },
    "projectivy": {
        "name": "Projectivy Launcher",
        "source": "github:spocky/projectivy-launcher",
        "code": "447477",
    },
    "447477": {
        "name": "Projectivy Launcher",
        "source": "github:spocky/projectivy-launcher",
        "code": "447477",
    },
    "555555": {
        "name": "Unlinked App Store",
        "url": "https://unlinked.link/unlinked.apk",
        "code": "555555",
    },
    "unlinked": {
        "name": "Unlinked App Store",
        "url": "https://unlinked.link/unlinked.apk",
        "code": "555555",
    },
    "741490": {
        "name": "IPTV Smarters Pro",
        "url": "https://www.iptvsmarters.com/smarters.apk",
        "code": "741490",
    },
    "smarters": {
        "name": "IPTV Smarters Pro",
        "url": "https://www.iptvsmarters.com/smarters.apk",
        "code": "741490",
    },
}


def resolve_code_or_url(input_str: str, abi: str = "armeabi-v7a") -> tuple[str, str] | None:
    """Resolves a 5/6-digit Downloader shortcode, app name, or direct APK URL into (download_url, label)."""
    target = input_str.strip()
    if not target:
        return None

    # Check if string contains a 5/6 digit code (e.g. "Stremio TV (250931)")
    code_match = re.search(r"\b(\d{5,6})\b", target)
    if code_match:
        target = code_match.group(1)

    lower_target = target.lower()
    if lower_target in RECOMMENDED_APPS:
        entry = RECOMMENDED_APPS[lower_target]
        if "source" in entry and entry["source"].startswith("github:"):
            try:
                repo = entry["source"].split(":", 1)[1]
                release = http_json(f"https://api.github.com/repos/{repo}/releases/latest")
                version = release.get("tag_name", "")
                apks = [
                    a for a in release.get("assets", [])
                    if a["name"].endswith(".apk") and "debug" not in a["name"].lower()
                ]
                # This device's ABI first, then a universal build, then whatever is left. Picking
                # the first .apk in the list installs an armeabi-v7a build on an arm64 TV as often
                # as not, which fails with INSTALL_FAILED_NO_MATCHING_ABIS.
                ordered = (
                    [a for a in apks if abi in a["name"]]
                    + [a for a in apks if "universal" in a["name"].lower()]
                    + apks
                )
                for asset in ordered:
                    return asset["browser_download_url"], f"{entry['name']} {version}"
            except Exception:
                pass
        if "url" in entry:
            return entry["url"], entry["name"]

    # Numeric 5 or 6 digit Downloader shortcode (e.g. 250931, 798542)
    if target.isdigit() and len(target) in (5, 6):
        short_url = f"https://downloader.aftvnews.com/short_url/{target}"
        try:
            req = urllib.request.Request(short_url, headers={"User-Agent": "Mozilla/5.0 (Android TV)"})
            with urllib.request.urlopen(req, timeout=15) as resp:
                final_url = resp.geturl()
                return final_url, f"App (Code {target})"
        except Exception:
            alt_url = f"http://aftv.news/{target}"
            try:
                req = urllib.request.Request(alt_url, headers={"User-Agent": "Mozilla/5.0 (Android TV)"})
                with urllib.request.urlopen(req, timeout=15) as resp:
                    return resp.geturl(), f"App (Code {target})"
            except Exception:
                return None

    # Direct HTTP/HTTPS URL
    if target.startswith("http://") or target.startswith("https://"):
        try:
            req = urllib.request.Request(target, headers={"User-Agent": "Mozilla/5.0 (Android TV)"})
            with urllib.request.urlopen(req, timeout=15) as resp:
                final_url = resp.geturl()
                filename = final_url.split("/")[-1].split("?")[0]
                label = filename if filename.endswith(".apk") else "Custom APK"
                return final_url, label
        except Exception:
            return target, "Custom APK"

    return None


def run(args: list[str], timeout: int = 60) -> tuple[int, str]:
    try:
        p = subprocess.run(args, capture_output=True, text=True, timeout=timeout)
        return p.returncode, (p.stdout + p.stderr).strip()
    except subprocess.TimeoutExpired:
        return 124, f"timed out after {timeout}s"
    except FileNotFoundError:
        return 127, f"{args[0]} not found"


def adb(target: str, *args: str, timeout: int = 60) -> tuple[int, str]:
    return run(["adb", "-s", target, *args], timeout=timeout)


def local_ipv4s() -> list[str]:
    """Every IPv4 this Mac holds, so we can tell the user which network they are on."""
    found = []
    code, out = run(["ifconfig"], timeout=10)
    if code == 0:
        for line in out.splitlines():
            line = line.strip()
            if line.startswith("inet ") and "127.0.0.1" not in line:
                found.append(line.split()[1])
    return found


def same_network(target: str, mine: list[str]) -> bool:
    """A /24 match is the honest heuristic: the netmask is usually 255.255.255.0 at home."""
    try:
        t = ipaddress.ip_address(target)
    except ValueError:
        return False
    for ip in mine:
        try:
            if t in ipaddress.ip_network(f"{ip}/24", strict=False):
                return True
        except ValueError:
            continue
    return False


def port_open(host: str, port: int, timeout: float = 2.5) -> bool:
    try:
        with socket.create_connection((host, port), timeout=timeout):
            return True
    except OSError:
        return False


class Log:
    """Streams lines straight to the browser as the work happens, and keeps the window the size
    of what has actually been printed — the page cannot do this itself, because Chrome ignores
    resizeTo on an --app window and throttles timers while the window sits behind the terminal."""

    LINE_PX = 17
    CHROME_PX = 230  # address row + player row + sideload row + help row + padding + title bar

    def __init__(self, wfile):
        self.wfile = wfile
        self.failed = False
        self.lines = 0

    def __call__(self, text: str = "", mark: str = "") -> None:
        payload = json.dumps({"line": text, "mark": mark}) + "\n"
        try:
            self.wfile.write(payload.encode())
            self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError):
            pass
        # Long lines wrap, so count the rows they will really occupy.
        self.lines += max(1, (len(text) // 68) + 1)
        if self.lines % 4 == 0:
            self.fit()

    def fit(self) -> None:
        global LAST_LINES
        LAST_LINES = self.lines
        fit_window(WINDOW_WIDTH, self.CHROME_PX + self.lines * self.LINE_PX)

    def ok(self, text: str) -> None:
        self(text, "ok")

    def bad(self, text: str) -> None:
        self.failed = True
        self(text, "bad")

    def warn(self, text: str) -> None:
        self(text, "warn")

    def step(self, text: str) -> None:
        self(text, "step")


def preflight(host: str, log: Log) -> dict | None:
    """Every cheap check, in the order that fails fastest. No work until they all pass."""
    target = f"{host}:{ADB_PORT}"

    log.step("Checking the address")
    try:
        ipaddress.ip_address(host)
    except ValueError:
        log.bad(f"“{host}” is not an IP address.")
        return None
    log.ok(f"{host} looks like an address")

    if not shutil.which("adb"):
        log.bad("adb is not installed on this Mac. Install it with: brew install android-platform-tools")
        return None

    log.step("Checking you are on the same network as the TV")
    mine = local_ipv4s()
    if not mine:
        log.warn("Could not read this Mac's network address; continuing anyway.")
    elif same_network(host, mine):
        log.ok(f"this Mac is on the same network ({', '.join(mine)})")
    else:
        log.bad(
            f"this Mac is on {', '.join(mine)} — a different network from {host}. "
            "They must be on the same Wi-Fi/router. VPN on this Mac will also do this."
        )
        return None

    log.step("Checking the TV is awake and reachable")
    if not port_open(host, ADB_PORT):
        log.bad(
            f"nothing is listening on {host}:{ADB_PORT}. Either the TV is asleep, or ADB "
            "debugging is still switched off — see the instructions on the left."
        )
        return None
    log.ok(f"{host}:{ADB_PORT} is open")

    log.step("Connecting")
    run(["adb", "connect", target], timeout=20)
    code, state = run(["adb", "-s", target, "get-state"], timeout=15)
    state = state.strip()
    if state == "unauthorized" or "unauthorized" in state:
        log.bad(
            "the TV has not trusted this Mac yet. Look at the TV — there is an "
            "“Allow USB debugging?” prompt. Accept it with the remote, then press Check again."
        )
        return None
    if code != 0 or state != "device":
        log.bad(f"ADB could not attach ({state or 'no answer'}). Try switching ADB debugging off and on.")
        return None
    log.ok("ADB attached and trusted")

    log.step("Reading what this box is")
    props = {}
    for key, prop in (
        ("model", "ro.product.model"),
        ("maker", "ro.product.manufacturer"),
        ("release", "ro.build.version.release"),
        ("sdk", "ro.build.version.sdk"),
    ):
        _, value = adb(target, "shell", "getprop", prop, timeout=15)
        props[key] = value.strip()
    sdk = int(props["sdk"]) if props["sdk"].isdigit() else 0
    log.ok(f"{props['maker']} {props['model']} · Android {props['release']} (API {sdk})")
    if sdk and sdk < MIN_SDK:
        log.bad(f"this box is API {sdk}; the receiver needs API {MIN_SDK} or newer.")
        return None

    log.step("Checking the receiver build on this Mac")
    if not os.path.exists(APK):
        log.bad("no APK built yet. Press Build first, or run ./gradlew assembleDebug in 4789TV.")
        return None
    size_mb = os.path.getsize(APK) / (1024 * 1024)
    log.ok(f"app-debug.apk ready ({size_mb:.0f} MB)")

    log("")
    log.ok("All checks passed — safe to install.")
    return props


WINDOW_TITLE = "4789 · install on a TV"
WINDOW_WIDTH = 520
WINDOW_MIN_HEIGHT = 280
WINDOW_MAX_HEIGHT = 860
HELP_PANEL_PX = 190      # the expanded "First time on this TV?" list
HELP_OPEN = False        # reported by the page; the server cannot see it
LAST_LINES = 0           # rows printed by the run in progress


def fit_window(width: int, height: int) -> None:
    """Resize our own Chrome window to the height actually needed, keeping its top-left."""
    # A phone driving the installer over the LAN has no Mac window to resize, and resizing one it
    # cannot see would be baffling.
    if not DESKTOP_WINDOW:
        return
    height = int(height) + (HELP_PANEL_PX if HELP_OPEN else 0)
    height = max(WINDOW_MIN_HEIGHT, min(height, WINDOW_MAX_HEIGHT + HELP_PANEL_PX))
    script = f'''
    tell application "Google Chrome"
      repeat with w in windows
        try
          if (title of active tab of w) contains "install on a TV" then
            set b to bounds of w
            set bounds of w to {{item 1 of b, item 2 of b, (item 1 of b) + {width}, (item 2 of b) + {height}}}
          end if
        end try
      end repeat
    end tell
    '''
    subprocess.run(["osascript", "-e", script], capture_output=True, timeout=10)


def preflight_light(host: str, log: Log) -> bool:
    """The subset that matters for pushing a third-party APK: reachable, trusted, new enough."""
    target = f"{host}:{ADB_PORT}"
    try:
        ipaddress.ip_address(host)
    except ValueError:
        log.bad(f"“{host}” is not an IP address.")
        return False
    if not port_open(host, ADB_PORT):
        log.bad(f"{host}:{ADB_PORT} is not answering — is the TV awake with ADB on?")
        return False
    run(["adb", "connect", target], timeout=20)
    _, state = run(["adb", "-s", target, "get-state"], timeout=15)
    if state.strip() != "device":
        log.bad(f"ADB is not attached ({state.strip() or 'no answer'}). Accept the prompt on the TV.")
        return False
    log.ok("TV reachable and trusted")
    return True


def load_token() -> str:
    """One long-lived secret, so a saved home-screen bookmark keeps working across restarts."""
    try:
        existing = open(TOKEN_FILE).read().strip()
        if len(existing) >= 24:
            return existing
    except OSError:
        pass
    fresh = secrets.token_urlsafe(24)
    with open(os.open(TOKEN_FILE, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600), "w") as handle:
        handle.write(fresh)
    return fresh


def is_private_client(address: str) -> bool:
    """Loopback, RFC1918, and link-local only. A public source address is never served."""
    try:
        ip = ipaddress.ip_address(address)
    except ValueError:
        return False
    return ip.is_loopback or ip.is_private or ip.is_link_local


def home_screen_icon() -> bytes:
    """A 180×180 PNG for the iOS home screen, drawn here so the installer stays one file.

    Amber rounded screen on the near-black the pages already use — recognisable at icon size
    without shipping a binary asset next to the script."""
    size, bg, ink = 180, (20, 22, 26), (224, 138, 76)
    rows = bytearray()
    outer, radius = 26, 18

    def inside(x: int, y: int, pad: int, r: int) -> bool:
        left, top, right, bottom = pad, pad, size - pad, size - pad
        if not (left <= x < right and top <= y < bottom):
            return False
        cx = min(max(x, left + r), right - r)
        cy = min(max(y, top + r), bottom - r)
        return (x - cx) ** 2 + (y - cy) ** 2 <= r * r

    for y in range(size):
        rows.append(0)  # PNG filter byte: none
        for x in range(size):
            screen = inside(x, y, outer, radius) and not inside(x, y, outer + 9, radius - 6)
            # A play triangle centred in the screen.
            play = 74 <= x <= 106 and abs(y - 90) <= (106 - x) * 0.62
            rows.extend(ink if (screen or play) else bg)

    def chunk(kind: bytes, payload: bytes) -> bytes:
        body = kind + payload
        return struct.pack(">I", len(payload)) + body + struct.pack(">I", binascii.crc32(body))

    header = struct.pack(">IIBBBBB", size, size, 8, 2, 0, 0, 0)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header)
            + chunk(b"IDAT", zlib.compress(bytes(rows), 9)) + chunk(b"IEND", b""))


def mobile_manifest() -> str:
    """Return a token-bound manifest so iOS launches the installer as a standalone app."""
    key = urllib.parse.quote(TOKEN, safe="")
    return json.dumps({
        "name": "4789 TV Installer",
        "short_name": "4789 TV",
        "start_url": f"/m?k={key}",
        "scope": "/",
        "display": "standalone",
        "background_color": "#14161a",
        "theme_color": "#14161a",
        "icons": [{
            "src": f"/icon.png?k={key}",
            "sizes": "180x180",
            "type": "image/png",
            "purpose": "any maskable",
        }],
    }, separators=(",", ":"))


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, format: str, *args) -> None:  # noqa: A002 — keep the terminal quiet
        pass

    def _stream_headers(self) -> None:
        self.send_response(200)
        self.send_header("Content-Type", "application/x-ndjson")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Transfer-Encoding", "chunked")
        self.end_headers()

    def _write_chunked(self, data: bytes) -> None:
        self.wfile.write(f"{len(data):X}\r\n".encode() + data + b"\r\n")
        self.wfile.flush()

    def authorized(self) -> bool:
        """Loopback needs nothing. Anything else must be a private address holding the secret."""
        client = self.client_address[0]
        # A reverse proxy (Tailscale serve, cloudflared) connects from loopback on our behalf, so
        # a bare loopback address is NOT proof the caller is sitting at this Mac. Any request
        # carrying proxy or Tailscale identity headers is treated as remote and must hold the key.
        proxied = bool(
            self.headers.get("X-Forwarded-For")
            or self.headers.get("Tailscale-User-Login")
            or self.headers.get("Tailscale-Headers-Info")
        )
        if client in ("127.0.0.1", "::1") and not proxied:
            return True  # the Mac's own window, which carries no key
        if not LAN_MODE and not proxied:
            return False
        if not proxied and not is_private_client(client):
            return False
        query = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
        cookie_values = {}
        for part in self.headers.get("Cookie", "").split(";"):
            if "=" in part:
                name, value = part.strip().split("=", 1)
                cookie_values[name] = value
        supplied = (
            (query.get("k", [""])[0])
            or self.headers.get("X-Installer-Key", "")
            or urllib.parse.unquote(cookie_values.get("4789-installer-key", ""))
        )
        return secrets.compare_digest(supplied, TOKEN)

    def deny(self) -> None:
        self.send_response(403)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def _send(self, body: bytes, content_type: str, set_cookie: bool = False) -> None:
        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        if content_type.startswith(("text/html", "application/manifest+json")):
            # The page and its start URL contain the private installer key. Never let Safari
            # keep a stale copy after the server has restarted or the page has been updated.
            self.send_header("Cache-Control", "no-store")
        if set_cookie:
            key = urllib.parse.quote(TOKEN, safe="")
            self.send_header(
                "Set-Cookie",
                f"4789-installer-key={key}; Path=/; Max-Age=31536000; Secure; SameSite=Strict",
            )
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):  # noqa: N802
        route = urllib.parse.urlparse(self.path).path
        if not self.authorized():
            return self.deny()
        if route in ("/", "/index.html"):
            self._send(PAGE.encode(), "text/html; charset=utf-8")
        elif route in ("/m", "/m/"):
            self._send(
                MOBILE_PAGE.replace("__KEY__", TOKEN).encode(),
                "text/html; charset=utf-8",
                set_cookie=True,
            )
        elif route == "/manifest.json":
            self._send(mobile_manifest().encode(), "application/manifest+json")
        elif route == "/icon.png":
            self._send(home_screen_icon(), "image/png")
        elif route.startswith("/logo/"):
            self.send_logo(route[len("/logo/"):])
        else:
            self.send_error(404)

    def send_logo(self, name: str) -> None:
        """Each app's own logo, downloaded by refresh-logos.sh. The name is matched against the
        directory listing rather than joined onto a path, so a crafted name cannot escape it."""
        folder = os.path.join(HERE, "logos")
        try:
            available = set(os.listdir(folder))
        except OSError:
            return self.send_error(404)
        if name not in available or name.startswith("."):
            return self.send_error(404)
        kind = "image/svg+xml" if name.endswith(".svg") else "image/png"
        try:
            with open(os.path.join(folder, name), "rb") as handle:
                body = handle.read()
        except OSError:
            return self.send_error(404)
        self.send_response(200)
        self.send_header("Content-Type", kind)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "public, max-age=86400")
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):  # noqa: N802
        if not self.authorized():
            return self.deny()
        length = int(self.headers.get("Content-Length", 0))
        try:
            payload = json.loads(self.rfile.read(length) or b"{}")
        except json.JSONDecodeError:
            payload = {}
        host = str(payload.get("ip", "")).strip()
        route = urllib.parse.urlparse(self.path).path

        # Chrome refuses window.resizeTo on an --app window, so the page tells us the height it
        # wants and we move the real OS window with AppleScript instead.
        if route == "/help":
            global HELP_OPEN
            HELP_OPEN = bool(payload.get("open"))
            fit_window(WINDOW_WIDTH, WINDOW_MIN_HEIGHT + LAST_LINES * Log.LINE_PX)
            self.send_response(204)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return

        self._stream_headers()
        chunked = self

        class ChunkWriter:
            def write(self, data: bytes) -> None:
                chunked._write_chunked(data)

            def flush(self) -> None:
                pass

        log = Log(ChunkWriter())
        try:
            if route == "/check":
                preflight(host, log)
            elif route == "/install":
                self.do_install(host, log)
            elif route == "/build":
                self.do_build(log)
            elif route == "/players":
                self.do_players(host, log)
            elif route == "/installplayer":
                self.do_install_player(host, str(payload.get("player", "")), log)
            elif route == "/sideload":
                self.do_sideload_code(host, str(payload.get("code", "")), log)
            else:
                log.bad("unknown action")
        except Exception as error:  # never leave the browser hanging
            log.bad(f"unexpected failure: {error}")
        finally:
            log.fit()
            self._write_chunked(b"")
            try:
                self.wfile.write(b"0\r\n\r\n")
                self.wfile.flush()
            except (BrokenPipeError, ConnectionResetError):
                pass

    def device_abi(self, target: str) -> str:
        """Prefer 64-bit when the device supports it — some TVs report a 32-bit primary ABI
        while still running arm64 code, and some (the Hisense here) are genuinely 32-bit only.
        Installing the wrong one fails with INSTALL_FAILED_NO_MATCHING_ABIS."""
        _, abilist = adb(target, "shell", "getprop", "ro.product.cpu.abilist", timeout=15)
        supported = [a.strip() for a in abilist.strip().split(",") if a.strip()]
        if not supported:
            _, primary = adb(target, "shell", "getprop", "ro.product.cpu.abi", timeout=15)
            supported = [primary.strip()]
        if "arm64-v8a" in supported:
            return "arm64-v8a"
        if "armeabi-v7a" in supported:
            return "armeabi-v7a"
        return supported[0] if supported else "arm64-v8a"

    def do_players(self, host: str, log: Log) -> None:
        """What backup players exist, which are already on this TV, and what the latest is."""
        target = f"{host}:{ADB_PORT}"
        run(["adb", "connect", target], timeout=20)
        _, state = run(["adb", "-s", target, "get-state"], timeout=15)
        attached = state.strip() == "device"
        installed: set[str] = set()
        abi = "arm64-v8a"
        if attached:
            abi = self.device_abi(target)
            _, listing = adb(target, "shell", "pm", "list", "packages", timeout=30)
            installed = {line.strip().replace("package:", "") for line in listing.splitlines()}
            log.ok(f"talking to the TV · {abi}")
        else:
            log.warn("not attached to a TV — showing the catalogue only.")

        for player in PLAYERS:
            resolved = resolve_player(player, abi)
            here = player["package"] in installed
            mark = "ok" if here else "step"
            version = resolved[1] if resolved else "could not reach the release channel"
            log(f"{player['name']} — {'installed' if here else 'not installed'} · {version}", mark)
            log(f"    {player['why']}")
        log("")
        log.ok("Press a player's Install button to put it on the TV.")

    def do_install_player(self, host: str, player_id: str, log: Log) -> None:
        player = next((p for p in PLAYERS if p["id"] == player_id), None)
        if player is None:
            log.bad(f"unknown player “{player_id}”")
            return

        target = f"{host}:{ADB_PORT}"
        log.step(f"Checking the TV before installing {player['name']}")
        if preflight_light(host, log) is False:
            return
        abi = self.device_abi(target)

        log.step(f"Finding the current {player['name']} release ({abi})")
        resolved = resolve_player(player, abi)
        if resolved is None:
            log.bad(
                f"could not reach {player['name']}'s release channel. It publishes at "
                f"{player['source']} — try again, or install it on the TV by hand."
            )
            return
        url, version = resolved
        log.ok(f"{player['name']} {version}")
        log(f"    from {url}")

        with tempfile.TemporaryDirectory() as workdir:
            path = os.path.join(workdir, f"{player['id']}.apk")
            log.step("Downloading")
            try:
                request = urllib.request.Request(url, headers={"User-Agent": "4789-installer"})
                with urllib.request.urlopen(request, timeout=60) as response, open(path, "wb") as out:
                    total = int(response.headers.get("Content-Length") or 0)
                    got = 0
                    next_mark = 10
                    while chunk := response.read(256 * 1024):
                        out.write(chunk)
                        got += len(chunk)
                        if total:
                            percent = got * 100 // total
                            if percent >= next_mark:
                                log(f"    {percent}% ({got / 1e6:.0f} of {total / 1e6:.0f} MB)")
                                next_mark = percent - percent % 10 + 10
            except Exception as error:
                log.bad(f"download failed: {error}")
                return
            size_mb = os.path.getsize(path) / 1e6
            if size_mb < 1:
                log.bad("the download was too small to be an APK — the mirror may be broken.")
                return
            log.ok(f"downloaded {size_mb:.0f} MB")

            log.step(f"Installing on the TV")
            code, out = adb(target, "install", "-r", path, timeout=900)
            if code != 0 or "Success" not in out:
                line = out.splitlines()[-1] if out else "no output"
                if "INSTALL_FAILED_VERSION_DOWNGRADE" in out:
                    log.bad("a newer build is already on the TV — nothing to do.")
                elif "INSTALL_FAILED_UPDATE_INCOMPATIBLE" in out:
                    log.bad(
                        "a differently-signed copy is already installed. Uninstall it on the TV "
                        "first, then install again."
                    )
                else:
                    log.bad(f"install failed: {line}")
                return

        log.ok(f"{player['name']} {version} installed")
        log("")
        log.ok("The receiver will offer it as a backup player on its next start.")

    def do_sideload_code(self, host: str, code_or_url: str, log: Log) -> None:
        target = f"{host}:{ADB_PORT}"
        log.step(f"Checking the TV before sideloading “{code_or_url}”")
        if preflight_light(host, log) is False:
            return
        abi = self.device_abi(target)

        log.step(f"Resolving Downloader code / APK URL “{code_or_url}”")
        resolved = resolve_code_or_url(code_or_url, abi)
        if resolved is None:
            log.bad(
                f"Could not resolve Downloader code or APK URL “{code_or_url}”. "
                "Check the 5/6-digit code or direct APK link."
            )
            return

        url, name = resolved
        log.ok(f"Target: {name}")
        log(f"    from {url}")

        with tempfile.TemporaryDirectory() as workdir:
            path = os.path.join(workdir, "sideload.apk")
            log.step(f"Downloading {name}")
            try:
                request = urllib.request.Request(
                    url, headers={"User-Agent": "Mozilla/5.0 (Android TV; Downloader)"}
                )
                with urllib.request.urlopen(request, timeout=90) as response, open(path, "wb") as out:
                    total = int(response.headers.get("Content-Length") or 0)
                    got = 0
                    next_mark = 10
                    while chunk := response.read(256 * 1024):
                        out.write(chunk)
                        got += len(chunk)
                        if total:
                            percent = got * 100 // total
                            if percent >= next_mark:
                                log(f"    {percent}% ({got / 1e6:.1f} of {total / 1e6:.1f} MB)")
                                next_mark = percent - percent % 10 + 10
            except Exception as error:
                log.bad(f"Download failed: {error}")
                return

            size_mb = os.path.getsize(path) / 1e6
            if size_mb < 0.5:
                log.bad("The download was too small to be a valid APK — shortcode or link may be broken.")
                return
            log.ok(f"Downloaded {size_mb:.1f} MB APK")

            log.step("Installing on TV via ADB")
            code_ret, out = adb(target, "install", "-r", path, timeout=900)
            if code_ret != 0 or "Success" not in out:
                line = out.splitlines()[-1] if out else "no output"
                log.bad(f"Install failed: {line}")
                return

        log.ok(f"✓ {name} successfully installed on TV!")

    def do_build(self, log: Log) -> None:
        log.step("Building the receiver (a minute or so the first time)")
        env_java = os.environ.get("JAVA_HOME", "/opt/homebrew/opt/openjdk@17")
        code, out = run(
            ["env", f"JAVA_HOME={env_java}", "./gradlew", "assembleDebug", "-q"], timeout=1800
        ) if os.path.exists(os.path.join(TV_ROOT, "gradlew")) else (127, "gradlew missing")
        os.chdir(TV_ROOT)
        if code != 0:
            log.bad("build failed:")
            for line in out.splitlines()[-12:]:
                log(line)
            return
        log.ok("built app-debug.apk")

    def do_install(self, host: str, log: Log) -> None:
        props = preflight(host, log)
        if props is None:
            log.bad("Stopping — nothing was installed.")
            return

        target = f"{host}:{ADB_PORT}"
        log("")
        log.step("Installing the receiver")
        code, out = adb(target, "install", "-r", APK, timeout=600)
        if code != 0 or "Success" not in out:
            log.bad(f"install failed: {out.splitlines()[-1] if out else 'no output'}")
            return
        log.ok("installed")

        log.step("Starting it on the TV")
        adb(target, "shell", "am", "start", "-n", f"{PKG}/.ui.MainActivity", timeout=30)

        log.step("Asking the receiver what this TV can play")
        info = None
        for _ in range(10):
            threading.Event().wait(2)
            try:
                request = urllib.request.Request(
                    f"http://{host}:{RECEIVER_PORT}/jsonrpc",
                    data=json.dumps(
                        {"jsonrpc": "2.0", "id": 1, "method": "X4789.GetReceiverInfo", "params": {}}
                    ).encode(),
                    headers={"Content-Type": "application/json"},
                )
                with urllib.request.urlopen(request, timeout=5) as response:
                    info = json.loads(response.read()).get("result")
                if info:
                    break
            except Exception:
                continue

        if not info:
            log.warn(
                "installed, but the receiver did not answer yet. It only serves while its screen "
                "is in front — check the TV is showing 4789, then press Check."
            )
            return

        video = info.get("hardwareVideoCodecs") or []
        audio = info.get("audioCodecs") or {}
        players = [p.get("label") for p in info.get("externalPlayers") or []]
        log("")
        log.ok(f"{info.get('name')} is live on {props['maker']} {props['model']}")
        log(f"   video decoders : {', '.join(video) or 'unknown'}")
        log(f"   audio decode   : {', '.join(audio.get('decode') or []) or 'unknown'}")
        log(f"   passthrough    : {', '.join(audio.get('passthrough') or []) or 'unknown'}")
        log(f"   backup players : {', '.join(players) if players else 'none installed'}")
        if "dolbyvision" in video:
            log.ok("this TV decodes Dolby Vision natively")
        if {"dts", "dtshd", "truehd"} & set(audio.get("passthrough") or []):
            log.ok("this TV bitstreams DTS/TrueHD to a receiver")
        log("")
        log.ok("Done. Your phone will find this TV by itself.")


MOBILE_PAGE = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<meta name="apple-mobile-web-app-capable" content="yes">
<meta name="mobile-web-app-capable" content="yes">
<meta name="apple-mobile-web-app-status-bar-style" content="black-translucent">
<meta name="apple-mobile-web-app-title" content="4789 TV">
<meta name="theme-color" content="#14161a">
<link rel="manifest" href="/manifest.json?k=__KEY__">
<link rel="apple-touch-icon" sizes="180x180" href="/icon.png?k=__KEY__">
<title>4789 TV installer</title>
<style>
  :root { color-scheme: dark; --bg:#14161a; --panel:#1c1f24; --line:#2b3038;
          --text:#eceae6; --dim:#848b94; --accent:#e08a4c;
          --ok:#7fc79c; --bad:#e08a76; --warn:#d8b05c; }
  * { box-sizing: border-box; -webkit-tap-highlight-color: transparent; }
  html, body { margin:0; background:var(--bg); color:var(--text);
               font:15px/1.5 -apple-system,"SF Pro Text",system-ui,sans-serif; }
  /* The page is a fixed panel, not a document: nothing scrolls except the log, and nothing
     ever moves sideways. overscroll-behavior kills the rubber-band drag on iOS. */
  html, body { height:100%; overflow:hidden; overscroll-behavior:none; }
  body { display:flex; flex-direction:column; height:100dvh; max-width:100vw;
         padding:env(safe-area-inset-top) env(safe-area-inset-right)
                 env(safe-area-inset-bottom) env(safe-area-inset-left); }
  header { padding:14px 16px 10px; display:flex; align-items:baseline; gap:8px; }
  header h1 { margin:0; font-size:17px; font-weight:700; letter-spacing:-.01em; }
  header span { font:11px/1 ui-monospace,Menlo,monospace; letter-spacing:.1em;
                text-transform:uppercase; color:var(--dim); }
  .field { display:flex; gap:8px; padding:0 16px 10px; }
  input { flex:1; min-width:0; background:var(--panel); border:1px solid var(--line);
          color:var(--text); border-radius:10px; padding:12px 13px; font:inherit;
          font-variant-numeric:tabular-nums; }
  input:focus { outline:none; border-color:var(--accent); }
  .go { background:var(--panel); color:var(--text); border:1px solid var(--line);
        border-radius:10px; padding:0 16px; font:inherit; font-weight:600; }
  .go:active:not(:disabled) { background:#262b33; }
  .go:disabled, .tile:disabled { opacity:.4; }
  .grid { display:grid; grid-template-columns:repeat(auto-fill, minmax(64px, 1fr));
          gap:14px 10px; padding:4px 16px 16px; }
  .tile { background:none; border:none; padding:0; display:flex; flex-direction:column;
          align-items:center; gap:6px; color:var(--dim); font:inherit; font-size:10px;
          text-align:center; line-height:1.25; overflow-wrap:anywhere; }
  .tile svg, .tile img { width:100%; height:auto; aspect-ratio:1; border-radius:22%;
              display:block; box-shadow:0 1px 3px rgba(0,0,0,.5); object-fit:cover;
              background:#0f1216; transition:transform .12s ease; }
  .tile:active:not(:disabled) svg, .tile:active:not(:disabled) img { transform:scale(.93); }
  @media (prefers-reduced-motion:reduce) { .tile svg, .tile img { transition:none; } }
  /* A tile reports its own outcome, so you never have to read the log to know what happened:
     amber while working, green when the TV took it, red when it did not. */
  .tile.busy svg, .tile.busy img { outline:2px solid var(--accent); outline-offset:2px; }
  .tile.busy { color:var(--accent); }
  .tile.busy svg, .tile.busy img { animation:pulse 1.1s ease-in-out infinite; }
  .tile.done svg, .tile.done img { outline:2px solid var(--ok); outline-offset:2px; }
  .tile.done { color:var(--ok); }
  .tile.failed svg, .tile.failed img { outline:2px solid var(--bad); outline-offset:2px; }
  .tile.failed { color:var(--bad); }
  /* A working tile stays fully lit while its siblings dim, so the screen shows what is running. */
  .tile:disabled.busy, .tile:disabled.done, .tile:disabled.failed { opacity:1; }
  @keyframes pulse { 50% { opacity:.55; } }
  .go.done { background:var(--ok); border-color:var(--ok); color:#06231a; opacity:1; }
  .go.failed { background:var(--bad); border-color:var(--bad); color:#2b0d07; opacity:1; }
  .go.busy { background:var(--accent); border-color:var(--accent); color:#221004; opacity:1; }
  @media (prefers-reduced-motion:reduce) {
    .tile.busy svg, .tile.busy img { animation:none; }
  }
  /* min-height:0 is what actually lets a flex child scroll instead of pushing the page taller. */
  #log { flex:1; min-height:0; margin:0; padding:12px 16px 20px;
         border-top:1px solid var(--line);
         background:#101216; overflow-y:auto; overflow-x:hidden; overscroll-behavior:contain;
         white-space:pre-wrap; overflow-wrap:anywhere; word-break:break-word;
         font:12px/1.6 ui-monospace,"SF Mono",Menlo,monospace; color:var(--text);
         -webkit-overflow-scrolling:touch; }
  .ok::before   { content:"\\2713 "; color:var(--ok); }
  .bad::before  { content:"\\2717 "; color:var(--bad); }
  .warn::before { content:"! ";      color:var(--warn); }
  .step::before { content:"\\25B8 "; color:var(--accent); }
  .ok { color:var(--ok); } .bad { color:var(--bad); } .warn { color:var(--warn); }
  .step { color:var(--text); margin-top:8px; display:block; }
</style>

</head>
<body>
<header><h1>4789 TV</h1><span id="target">no TV yet</span></header>

<div class="field">
  <input id="ip" placeholder="TV address, e.g. 192.168.0.106"
         inputmode="decimal" autocapitalize="off" autocorrect="off" spellcheck="false">
  <button class="go" id="check">Check</button>
</div>

<div class="grid">
  <button class="tile" data-act="install" title="The 4789 receiver — built on this Mac">
    <svg viewBox="0 0 64 64" aria-hidden="true">
      <rect width="64" height="64" fill="#14161a"/>
      <rect x="11" y="15" width="42" height="28" rx="4" fill="none" stroke="#e08a4c" stroke-width="3.4"/>
      <path d="M28 24l11 5-11 5z" fill="#e08a4c"/>
      <path d="M24 51h16" stroke="#e08a4c" stroke-width="3.4" stroke-linecap="round"/>
    </svg>4789
  </button>

  <button class="tile" data-act="installplayer" data-player="justplayer"
          title="Just Player — best handoff target">
    <img src="/logo/justplayer.png?k=__KEY__" alt="" loading="lazy">Just Player
  </button>

  <button class="tile" data-act="installplayer" data-player="nextplayer"
          title="Next Player — ffmpeg decoders, small and quick">
    <img src="/logo/nextplayer.png?k=__KEY__" alt="" loading="lazy">Next Player
  </button>

  <button class="tile" data-act="installplayer" data-player="kodi" title="Kodi media centre">
    <img src="/logo/kodi.svg?k=__KEY__" alt="" loading="lazy">Kodi
  </button>

  <button class="tile" data-act="installplayer" data-player="vlc" title="VLC — plays anything">
    <img src="/logo/vlc.png?k=__KEY__" alt="" loading="lazy">VLC
  </button>

  <button class="tile" data-act="installplayer" data-player="mpv"
          title="mpv — the receiver's own engine, standalone">
    <img src="/logo/mpv.svg?k=__KEY__" alt="" loading="lazy">mpv
  </button>

  <button class="tile" data-act="sideload" data-code="tivimate" title="TiviMate IPTV player">
    <img src="/logo/tivimate.png?k=__KEY__" alt="" loading="lazy">TiviMate
  </button>

  <button class="tile" data-act="sideload" data-code="stremio" title="Stremio for Android TV">
    <img src="/logo/stremio.png?k=__KEY__" alt="" loading="lazy">Stremio
  </button>

  <button class="tile" data-act="sideload" data-code="nuvio" title="Nuvio TV">
    <img src="/logo/nuvio.png?k=__KEY__" alt="" loading="lazy">Nuvio
  </button>
</div>

<div class="field">
  <input id="code" placeholder="Any app or 6-digit code — Stremio, TiviMate, 250931"
         autocapitalize="off" autocorrect="off" spellcheck="false">
  <button class="go" id="sideload">Get</button>
</div>

<pre id="log"><span class="step">Type the TV's address, then press Check.</span>
</pre>

<script>
  const KEY = "__KEY__";
  const $ = (id) => document.getElementById(id);
  const log = $("log");
  const controls = [...document.querySelectorAll("button")];
  const STORE = "4789.tv.ip";
  const IS_IOS = /iPhone|iPad|iPod/.test(navigator.userAgent);
  $("ip").value = localStorage.getItem(STORE) || "";
  $("target").textContent = $("ip").value || "no TV yet";

  function line(text, mark) {
    const el = document.createElement("span");
    if (mark) el.className = mark;
    el.textContent = text + "\\n";
    log.appendChild(el);
    log.scrollTop = log.scrollHeight;
  }

  function mark(el, state) {
    if (!el) return;
    el.classList.remove("busy", "done", "failed");
    if (state) el.classList.add(state);
    if (state === "done" || state === "failed") {
      clearTimeout(el._reset);
      el._reset = setTimeout(() => el.classList.remove("done", "failed"), 6000);
    }
  }

  function openPhoneInstaller(source) {
    const ip = $("ip").value.trim();
    if (!ip) { line("Enter the TV's address first.", "bad"); mark(source, "failed"); return; }
    localStorage.setItem(STORE, ip);
    $("target").textContent = ip;
    log.textContent = "";
    line("Opening 4789 on this phone…", "step");
    line("The phone will connect directly to " + ip + ":5555; the Mac can be on another network.", "ok");
    window.location.href = "fourseveneightnine://install-tv?host=" + encodeURIComponent(ip) + "&port=5555";
  }

  async function call(path, extra = {}, source) {
    const ip = $("ip").value.trim();
    if (!ip) { line("Enter the TV's address first.", "bad"); mark(source, "failed"); return; }
    localStorage.setItem(STORE, ip);
    $("target").textContent = ip;
    log.textContent = "";
    controls.forEach((b) => (b.disabled = true));
    mark(source, "busy");
    // The server never says "finished, and it worked" — it just streams marked lines. A red line
    // anywhere means the job failed; otherwise a green one means it got somewhere.
    let sawBad = false, sawOk = false;
    try {
      const response = await fetch(path + "?k=" + encodeURIComponent(KEY), {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ ip, ...extra }),
      });
      if (!response.ok) {
        line("installer refused the request (" + response.status + ")", "bad");
        sawBad = true;
        return;
      }
      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      let buffer = "";
      for (;;) {
        const { value, done } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        const parts = buffer.split("\\n");
        buffer = parts.pop();
        for (const part of parts) {
          if (!part.trim()) continue;
          try {
            const m = JSON.parse(part);
            if (m.mark === "bad") sawBad = true;
            if (m.mark === "ok") sawOk = true;
            line(m.line, m.mark);
          } catch (_) {}
        }
      }
    } catch (error) {
      sawBad = true;
      line("lost the connection to the Mac: " + error, "bad");
      line("Is the installer still running, and are you on the same Wi-Fi?", "warn");
    } finally {
      controls.forEach((b) => (b.disabled = false));
      mark(source, sawBad ? "failed" : (sawOk ? "done" : null));
    }
  }

  $("check").onclick = () => IS_IOS ? openPhoneInstaller($("check")) : call("/check", {}, $("check"));
  $("sideload").onclick = () => {
    const code = $("code").value.trim();
    if (!code) {
      line("Type an app name, a 6-digit code, or an APK link.", "bad");
      mark($("sideload"), "failed");
      return;
    }
    call("/sideload", { code }, $("sideload"));
  };
  $("code").addEventListener("keydown", (e) => { if (e.key === "Enter") $("sideload").click(); });
  $("ip").addEventListener("keydown", (e) => { if (e.key === "Enter") $("check").click(); });

  document.querySelectorAll(".tile").forEach((tile) => {
    tile.onclick = () => {
      const act = tile.dataset.act;
      if (act === "install" && IS_IOS) openPhoneInstaller(tile);
      else if (act === "install") call("/install", {}, tile);
      else if (act === "installplayer") call("/installplayer", { player: tile.dataset.player }, tile);
      else call("/sideload", { code: tile.dataset.code }, tile);
    };
  });
</script>
</body>
</html>
"""

PAGE = """<!doctype html>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>4789 · install on a TV</title>
<style>
  :root { color-scheme: dark; --bg:#111316; --panel:#181b20; --line:#262a31;
          --text:#e8eaed; --dim:#9aa0a6; --ok:#5ad19a; --bad:#ff7b72; --warn:#e3b341; --key:#7cc5ff; }
  * { box-sizing: border-box; }
  html, body { background:var(--bg); }
  body { margin:0; font:12px/1.5 ui-sans-serif,-apple-system,"SF Pro Text",system-ui;
         color:var(--text); display:flex; flex-direction:column; }
  .row { display:flex; gap:6px; align-items:center; padding:9px 10px; border-bottom:1px solid var(--line); }
  .row.wrap { flex-wrap:wrap; gap:5px; padding-top:7px; padding-bottom:7px; }
  input { flex:1; min-width:0; background:#0d0f12; border:1px solid var(--line); color:var(--text);
          border-radius:6px; padding:7px 9px; font:inherit; font-variant-numeric:tabular-nums; }
  input:focus { outline:none; border-color:var(--key); }
  button { background:#22262d; color:var(--text); border:1px solid var(--line); border-radius:6px;
           padding:7px 10px; font:inherit; font-weight:600; cursor:pointer; white-space:nowrap; }
  button:hover:not(:disabled) { background:#2c313a; }
  button.primary { background:var(--key); border-color:var(--key); color:#06263d; }
  button.small { padding:5px 8px; font-size:11px; font-weight:500; }
  button:disabled { opacity:.45; cursor:default; }
  .tag { font-size:10px; text-transform:uppercase; letter-spacing:.08em; color:var(--dim); font-weight:600; }
  details { border-bottom:1px solid var(--line); }
  summary { padding:7px 10px; cursor:pointer; color:var(--dim); font-size:11px; list-style:none; }
  summary::-webkit-details-marker { display:none; }
  summary::before { content:"▸ "; }
  details[open] summary::before { content:"▾ "; }
  details ol { margin:0 0 9px; padding:0 12px 0 26px; color:var(--dim); font-size:11px; }
  details li { margin-bottom:5px; }
  b { color:var(--text); }
  #log { margin:0; padding:9px 10px 11px; overflow-y:auto; overflow-x:hidden;
         white-space:pre-wrap; word-break:break-word; max-height:62vh;
         font:11px/1.55 ui-monospace,"SF Mono",Menlo,monospace; }
  .ok::before   { content:"✓ "; color:var(--ok); }
  .bad::before  { content:"✗ "; color:var(--bad); }
  .warn::before { content:"! "; color:var(--warn); }
  .step::before { content:"▸ "; color:var(--key); }
  .ok { color:var(--ok); } .bad { color:var(--bad); } .warn { color:var(--warn); }
  .step { color:var(--text); margin-top:6px; }
</style>
<div class="row">
  <input id="ip" placeholder="TV address, e.g. 192.168.0.106" autofocus spellcheck="false">
  <button id="check">Check</button>
  <button id="install" class="primary">Install</button>
</div>
<div class="row wrap">
  <span class="tag">Players</span>
  <button class="small" data-player="justplayer">Just Player</button>
  <button class="small" data-player="vlc">VLC</button>
  <button class="small" data-player="kodi">Kodi</button>
  <button class="small" data-player="nextplayer">Next</button>
  <button class="small" id="players">Installed?</button>
  <button class="small" id="build">Build 4789</button>
</div>
<div class="row">
  <input id="code" list="suggestions" placeholder="Search app or 6-digit code (e.g. Stremio, TiviMate, 250931)" spellcheck="false">
  <button id="sideload" class="primary">Sideload</button>
</div>
<datalist id="suggestions">
  <option value="250931">Stremio for Android TV (Code 250931)</option>
  <option value="28544">SmartTube — Ad-free YouTube (Code 28544)</option>
  <option value="272483">TiviMate IPTV Player (Code 272483)</option>
  <option value="798542">Downloader App by AFTVnews (Code 798542)</option>
  <option value="66085">Syncler (Code 66085)</option>
  <option value="447477">Projectivy Launcher (Code 447477)</option>
  <option value="555555">Unlinked App Store (Code 555555)</option>
  <option value="741490">IPTV Smarters Pro (Code 741490)</option>
  <option value="stremio">Stremio (Keyword)</option>
  <option value="smarttube">SmartTube (Keyword)</option>
  <option value="tivimate">TiviMate (Keyword)</option>
  <option value="downloader">Downloader (Keyword)</option>
  <option value="syncler">Syncler (Keyword)</option>
  <option value="projectivy">Projectivy Launcher (Keyword)</option>
  <option value="smarters">IPTV Smarters (Keyword)</option>
</datalist>
<div class="row wrap">
  <span class="tag">Sideload</span>
  <button class="small" data-code="250931">Stremio TV (250931)</button>
  <button class="small" data-code="smarttube">SmartTube (28544)</button>
  <button class="small" data-code="tivimate">TiviMate (272483)</button>
  <button class="small" data-code="downloader">Downloader (798542)</button>
  <button class="small" data-code="syncler">Syncler (66085)</button>
</div>
<details>
  <summary>First time on this TV?</summary>
  <ol>
    <li>Fire TV: <b>Settings → My Fire TV → Developer Options</b>.<br>
        Google/Android TV: <b>Settings → System → About</b>, press <b>Build</b> 7×, back to
        <b>Developer options</b>.</li>
    <li>Turn on <b>ADB debugging</b>, and <b>Install unknown apps</b> if offered.</li>
    <li>IP is in <b>Settings → Network</b>.</li>
    <li>Accept <b>“Allow USB debugging?”</b> on the TV the first time.</li>
    <li>Same Wi-Fi, VPN off, TV awake.</li>
  </ol>
</details>
<pre id="log"><span class="step">Enter the TV's address and press Check.</span>
</pre>
<script>
  const $ = (id) => document.getElementById(id);
  const log = $("log");
  const buttons = [...document.querySelectorAll("button")];
  const KEY = "4789.tv.ip";
  $("ip").value = localStorage.getItem(KEY) || "";

  // The page does not size the window: measuring it here was unreliable (it reported 806px for
  // a 210px page) and Chrome ignores resizeTo on --app windows anyway. The server knows exactly
  // how many lines it printed, so it owns the height. All the page reports is whether the help
  // panel is open, which the server cannot see.
  function reportHelp() {
    fetch("/help", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ open: document.querySelector("details").open }),
      keepalive: true,
    }).catch(() => {});
  }

  function line(text, mark) {
    const el = document.createElement("span");
    if (mark) el.className = mark;
    el.textContent = text + "\\n";
    log.appendChild(el);
    log.scrollTop = log.scrollHeight;
  }

  async function call(path, extra = {}) {
    const ip = $("ip").value.trim();
    localStorage.setItem(KEY, ip);
    log.textContent = "";
    buttons.forEach((b) => (b.disabled = true));
    try {
      const response = await fetch(path, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ ip, ...extra }),
      });
      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      let buffer = "";
      for (;;) {
        const { value, done } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        const parts = buffer.split("\\n");
        buffer = parts.pop();
        for (const part of parts) {
          if (!part.trim()) continue;
          try { const m = JSON.parse(part); line(m.line, m.mark); } catch (_) {}
        }
      }
    } catch (error) {
      line("lost the connection to the installer: " + error, "bad");
    } finally {
      buttons.forEach((b) => (b.disabled = false));
    }
  }

  $("check").onclick = () => call("/check");
  $("build").onclick = () => call("/build");
  $("install").onclick = () => call("/install");
  $("players").onclick = () => call("/players");
  document.querySelectorAll("[data-player]").forEach((b) => {
    b.onclick = () => call("/installplayer", { player: b.dataset.player });
  });
  $("sideload").onclick = () => {
    const code = $("code").value.trim();
    if (!code) return alert("Enter a 5/6-digit Downloader code or APK URL first");
    call("/sideload", { code });
  };
  document.querySelectorAll("[data-code]").forEach((b) => {
    b.onclick = () => {
      $("code").value = b.dataset.code;
      call("/sideload", { code: b.dataset.code });
    };
  });
  $("code").addEventListener("keydown", (e) => {
    if (e.key === "Enter") {
      const code = $("code").value.trim();
      if (code) call("/sideload", { code });
    }
  });
  $("ip").addEventListener("keydown", (e) => { if (e.key === "Enter") call("/check"); });
  document.querySelector("details").addEventListener("toggle", reportHelp);
  reportHelp();
</script>
"""


def main() -> None:
    global LAN_MODE, TOKEN, DESKTOP_WINDOW
    os.chdir(TV_ROOT)
    LAN_MODE = "--lan" in sys.argv
    TOKEN = load_token()
    bind = "0.0.0.0" if LAN_MODE else "127.0.0.1"
    server = http.server.ThreadingHTTPServer((bind, PORT), Handler)
    url = f"http://127.0.0.1:{PORT}"
    print(f"4789 TV installer → {url}   (ctrl-C to stop)")
    if LAN_MODE:
        for address in local_ipv4s():
            print(f"  phone  → http://{address}:{PORT}/m?k={TOKEN}")
        print("  Open that on the iPhone, then Share → Add to Home Screen.")
        print("  It only answers private addresses, and only with the key above.")
    if "--no-open" not in sys.argv:
        DESKTOP_WINDOW = True
        opened = subprocess.run(
            ["open", "-na", "Google Chrome", "--args", f"--app={url}",
             "--window-size=520,210"],
            capture_output=True,
        )
        if opened.returncode != 0:
            subprocess.run(["open", url], capture_output=True)
        # Chrome reuses a remembered size when it is already running, so force the resting
        # size once the window exists.
        threading.Timer(2.5, lambda: fit_window(WINDOW_WIDTH, WINDOW_MIN_HEIGHT)).start()
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nstopped")


if __name__ == "__main__":
    main()
