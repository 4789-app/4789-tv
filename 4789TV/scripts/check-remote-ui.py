#!/usr/bin/env python3
"""Repeatable UI and remote checks on an explicitly selected disposable local emulator."""

import argparse
import contextlib
import http.server
import json
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import tempfile
import threading
import time
import urllib.request
import urllib.parse
import zlib

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "com.fourseveneightnine.tv"
SETUP = "com.fourseveneightnine.tv.EmulatorFixtureSetupTest"
CHECKS = ",".join(["com.fourseveneightnine.tv.RemoteResponseUiTest",
                   "com.fourseveneightnine.tv.PlaybackFlowRegressionTest",
                   "com.fourseveneightnine.tv.LegacyRecentsPurgeTest",
                   "com.fourseveneightnine.tv.LibraryUpgradeRegressionTest",
                   "com.fourseveneightnine.tv.ArtworkRecoveryUiTest",
                   "com.fourseveneightnine.tv.DetailArrivalUiTest",
                   "com.fourseveneightnine.tv.StartupProfileIoTest",
                   "com.fourseveneightnine.tv.CatalogCacheReadTest"])


def run(command, timeout=60):
    result = subprocess.run(command, capture_output=True, text=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError(result.stdout + result.stderr)
    return result.stdout


def run_logged(command, destination, stop, timeout=240):
    """Retain partial output and retire owned instrumentation before its fixture can close."""
    with destination.open("w") as log:
        try:
            result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, text=True, timeout=timeout)
        except subprocess.TimeoutExpired:
            log.write(f"\nHOST_TIMEOUT: {timeout} seconds; stopping owned instrumentation\n")
            log.flush()
            stop()
            raise
    return result.returncode, destination.read_text()


def listed_tests(output):
    """Use the installed runner's log-only discovery, including tests outside named files."""
    tests = []
    current = {}
    for line in output.splitlines():
        match = re.fullmatch(r"INSTRUMENTATION_STATUS: (class|test)=(.+)", line)
        if match:
            current[match[1]] = match[2]
        elif line == "INSTRUMENTATION_STATUS_CODE: 1":
            if set(current) != {"class", "test"}:
                raise RuntimeError("Incomplete test discovery status")
            tests.append(current["class"] + "#" + current["test"])
            current = {}
    if not tests or len(tests) != len(set(tests)):
        raise RuntimeError("Empty or duplicate test discovery")
    if not re.search(r"OK \(" + str(len(tests)) + r" tests?\)", output):
        raise RuntimeError("Test discovery did not finish")
    return tests

def png(red, green, blue, width=64, height=64, inset=0):
    def chunk(kind, payload):
        return struct.pack(">I", len(payload)) + kind + payload + struct.pack(">I", zlib.crc32(kind + payload))
    rows = b"".join(b"\0" + b"".join(bytes([red, green, blue, 255])
        if inset <= x < width - inset and inset <= y < height - inset else b"\0\0\0\0"
        for x in range(width)) for y in range(height)) if inset else (b"\0" + bytes([red, green, blue, 255]) * width) * height
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)) + \
        chunk(b"IDAT", zlib.compress(rows)) + chunk(b"IEND", b"")


def episode_track_fixtures(folder):
    # Both episodes contain the same languages with different stream indices.
    sub = folder / "handoff.srt"
    sub.write_text("1\n00:00:01,000 --> 00:00:04,000\nFixture subtitle\n")
    for episode, audio, subtitles in ((1, ("eng", "tam"), ("eng", "spa")), (2, ("tam", "eng"), ("spa", "eng"))):
        run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i", str(folder / "clip.mp4"), "-i", str(sub),
             "-map", "0:v:0", "-map", "0:a:0", "-map", "0:a:0", "-map", "1:s:0", "-map", "1:s:0",
             "-c", "copy", "-c:s", "mov_text", "-metadata:s:a:0", "language=" + audio[0],
             "-metadata:s:a:1", "language=" + audio[1], "-metadata:s:s:0", "language=" + subtitles[0],
             "-metadata:s:s:1", "language=" + subtitles[1], "-movflags", "+faststart",
             str(folder / f"handoff-{episode}.mp4")], timeout=30)


def four_k_handoff_fixture(folder):
    # Resolution affinity must be tested with a real 2160p video format, not a filename label.
    run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i",
         "color=c=red:size=3840x2160:rate=24:duration=60", "-i", str(folder / "handoff-1.mp4"),
         "-map", "0:v:0", "-map", "1:a", "-map", "1:s", "-c:v", "libx264", "-preset", "ultrafast",
         "-threads", "2", "-g", "48", "-pix_fmt", "yuv420p", "-c:a", "copy", "-c:s", "copy",
         "-t", "60", "-movflags", "+faststart", str(folder / "handoff-4k.mp4")], timeout=120)
    probe = json.loads(run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries",
                           "stream=width,height", "-of", "json", str(folder / "handoff-4k.mp4")]))
    if probe["streams"] != [{"width": 3840, "height": 2160}]:
        raise RuntimeError("4K handoff fixture has incorrect dimensions")


def fixture_handler(folder, port_ref):
    ownership_waiting = threading.Event()
    ownership_release = threading.Event()
    ownership_released = threading.Event()
    ownership_release.set()
    ownership_expired = set()
    nextup_requests = 0
    nextup_lock = threading.Lock()
    nextup_player_waiting = threading.Event()
    nextup_player_release = threading.Event()
    nextup_player_released = threading.Event()
    nextup_player_release.set()
    calendar_requests = {}
    calendar_lock = threading.Lock()
    picker_waiting = threading.Event()
    picker_release = threading.Event()
    picker_release.set()
    continue_state = {"expired": False, "hideOriginal": False, "events": [], "failMeta": False,
                      "failAcceptedOpen": False, "delayAcceptedOpen": False}
    continue_lock = threading.Lock()
    live_focus_waiting = threading.Event()
    live_focus_release = threading.Event()
    live_focus_release.set()
    live_focus_details = []
    live_focus_lock = threading.Lock()

    slow_episode_check = threading.Event()
    release_episode_check = threading.Event()

    live_fault_lock = threading.Lock()
    live_mux_lock = threading.Lock()
    live_parts = []
    live_target = 2
    live_fault = {"epoch": "", "started": 0.0, "cut": False, "cuts": 0,
                  "playlist200": 0, "segment200": 0, "segment503": 0, "events": []}

    def prepare_live_hls():
        # Copy the existing bounded H264/AAC fixture; never encode a second video.
        nonlocal live_target
        with live_mux_lock:
            if live_parts:
                return
            directory = folder / "live-fault-hls"
            directory.mkdir(exist_ok=True)
            run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i", str(folder / "clip.mp4"),
                 "-map", "0:v:0", "-map", "0:a:0", "-c", "copy", "-f", "hls", "-hls_time", "2",
                 "-hls_list_size", "0", "-hls_segment_filename", str(directory / "seg-%05d.ts"),
                 str(directory / "all.m3u8")], timeout=30)
            parts = []
            duration = None
            for line in (directory / "all.m3u8").read_text().splitlines():
                if line.startswith("#EXT-X-TARGETDURATION:"):
                    live_target = int(line.split(":", 1)[1])
                elif line.startswith("#EXTINF:"):
                    duration = float(line.split(":", 1)[1].split(",", 1)[0])
                elif line and not line.startswith("#"):
                    media = directory / Path(line).name
                    if duration is None or not media.is_file():
                        raise RuntimeError("Invalid live HLS fixture")
                    parts.append((media, duration))
                    duration = None
            # A final short segment is unused: every three-entry live window must span
            # at least three target durations. The existing fixture uses two-second GOPs.
            if parts and parts[-1][1] < live_target:
                parts.pop()
            if live_target != 2 or len(parts) < 30 or any(not 2 <= duration < 2.5 for _, duration in parts):
                raise RuntimeError("Unexpected live HLS timing")
            live_parts.extend(parts)

    def live_fault_state():
        # Caller holds live_fault_lock. Do not return the host clock origin or file paths.
        return {key: value for key, value in live_fault.items() if key != "started"} | {
            "events": list(live_fault["events"]), "segments": len(live_parts), "targetDuration": live_target}

    class Handler(http.server.BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, *_):
            pass

        def do_CONNECT(self):
            # Test-only holding proxy: never open a tunnel or connect to an external host.
            if self.path == "link.stremio.com:443":
                picker_waiting.set()
                picker_release.wait(timeout=30)
            with contextlib.suppress(BrokenPipeError, ConnectionResetError):
                self.send_response(502)
                self.send_header("Content-Length", "0")
                self.send_header("Connection", "close")
                self.end_headers()
            self.close_connection = True

        def do_HEAD(self):
            self.serve(False)

        def do_GET(self):
            self.serve(True)

        def serve(self, body):
            nonlocal nextup_requests
            path = urllib.parse.unquote(self.path.split("?", 1)[0])
            continuing = path.startswith("/cw/")
            if continuing:
                path = path.removeprefix("/cw")
            dmm = path.startswith("/dmm/")
            if dmm:
                path = path.removeprefix("/dmm")
            base = f"http://10.0.2.2:{port_ref[0]}" + ("/dmm" if dmm else "")
            court = re.fullmatch(r"/live/qa/qa/([1-4])\.ts", path)
            if court and (folder / f"court{court[1]}.mp4").is_file():
                self.send_response(200)
                self.send_header("Content-Type", "video/mp2t")
                self.end_headers()
                if body:
                    with subprocess.Popen(["ffmpeg", "-loglevel", "error", "-re", "-stream_loop", "-1",
                            "-i", str(folder / f"court{court[1]}.mp4"), "-c", "copy", "-f", "mpegts", "pipe:1"],
                            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL) as stream:
                        try:
                            while packet := stream.stdout.read(5264):
                                self.wfile.write(packet)
                        except (BrokenPipeError, ConnectionResetError):
                            pass
                        finally:
                            stream.terminate()
                return
            if continuing:
                base += "/cw"
                with continue_lock:
                    continue_state["events"].append(path)
                    if path == "/control/reset":
                        continue_state.update(expired=False, hideOriginal=False, events=[], failMeta=False,
                                              failAcceptedOpen=False, delayAcceptedOpen=False)
                    elif path == "/control/expire":
                        continue_state["expired"] = True
                    elif path == "/control/hide":
                        continue_state.update(expired=True, hideOriginal=True, events=[])
                    elif path == "/control/fail-meta":
                        continue_state["failMeta"] = True
                    elif path == "/control/fail-accepted-open":
                        continue_state["failAcceptedOpen"] = True
                    elif path == "/control/delay-accepted-open":
                        continue_state["delayAcceptedOpen"] = True
                    state = dict(continue_state, events=list(continue_state["events"]))
                if path.startswith("/control/"):
                    value = json.dumps(state).encode()
                    self.send_response(200)
                    self.send_header("Content-Length", str(len(value)))
                    self.end_headers()
                    if body:
                        self.wfile.write(value)
                    return
                if (path == "/original.mp4" and state["delayAcceptedOpen"] and "epoch=1" in self.path
                        and self.headers.get("Range") in (None, "bytes=0-")):
                    # A valid provider can answer slowly; later nonzero seek ranges stay fast.
                    time.sleep(17)
                if (path == "/original.mp4" and state["failAcceptedOpen"] and "epoch=1" in self.path
                        and self.headers.get("Range") != "bytes=0-65535"):
                    # Prefix validation succeeds. The accepted engine open then stalls while
                    # the same provider's next lookup already offers the renewed exact file.
                    with continue_lock:
                        continue_state["expired"] = True
                    time.sleep(20)
                    with contextlib.suppress(BrokenPipeError, ConnectionResetError):
                        self.send_response(403)
                        self.send_header("Content-Length", "0")
                        self.end_headers()
                    return
                if path == "/original.mp4" and state["expired"] and "epoch=1" in self.path:
                    self.send_response(403)
                    self.send_header("Content-Length", "0")
                    self.end_headers()
                    return
                if path in ("/original.mp4", "/alternative.mp4"):
                    path = "/clip.mp4"
            if path.startswith("/live-fault/"):
                if path in ("/live-fault/reset", "/live-fault/cut", "/live-fault/restore", "/live-fault/state"):
                    if (self.client_address[0] != "127.0.0.1" or
                            self.headers.get("X-4789-Live-Fault") != "disposable" or not body):
                        self.send_response(403)
                        self.send_header("Content-Length", "0")
                        self.end_headers()
                        return
                    action = path.rsplit("/", 1)[-1]
                    if action == "reset":
                        try:
                            prepare_live_hls()
                        except (OSError, RuntimeError, subprocess.TimeoutExpired):
                            self.send_response(500)
                            self.send_header("Content-Length", "0")
                            self.end_headers()
                            return
                    with live_fault_lock:
                        if action == "reset":
                            live_fault.update(epoch=str(time.monotonic_ns()), started=time.monotonic(), cut=False,
                                              cuts=0, playlist200=0, segment200=0, segment503=0, events=[])
                        elif action == "cut":
                            live_fault["cut"] = True
                            live_fault["cuts"] += 1
                        elif action == "restore":
                            live_fault["cut"] = False
                        data = json.dumps(live_fault_state()).encode()
                    self.send_response(200)
                    self.send_header("Content-Type", "application/json")
                    self.send_header("Cache-Control", "no-store")
                    self.send_header("Content-Length", str(len(data)))
                    self.end_headers()
                    self.wfile.write(data)
                    return
                with live_fault_lock:
                    epoch = live_fault["epoch"]
                    first = int((time.monotonic() - live_fault["started"]) // 2) if epoch else 0
                    if not epoch or first + 3 > len(live_parts):
                        # Bounded presentation: no sequence reuse, wrap or ENDLIST. Tests finish
                        # well before the copied ten-minute media inventory is consumed.
                        self.send_response(410)
                        self.send_header("Content-Length", "0")
                        self.end_headers()
                        return
                    if path == "/live-fault/index.m3u8":
                        lines = ["#EXTM3U", "#EXT-X-VERSION:3", f"#EXT-X-TARGETDURATION:{live_target}",
                                 f"#EXT-X-MEDIA-SEQUENCE:{first}"]
                        for sequence in range(first, first + 3):
                            lines.extend([f"#EXTINF:{live_parts[sequence][1]:.6f},",
                                          f"/live-fault/seg-{epoch}-{sequence}.ts"])
                        data = ("\n".join(lines) + "\n").encode()
                        mime = "application/vnd.apple.mpegurl"
                        if body:
                            live_fault["playlist200"] += 1
                    else:
                        segment = re.fullmatch(r"/live-fault/seg-(\d+)-(\d+)\.ts", path)
                        if segment is None or segment.group(1) != epoch or int(segment.group(2)) >= len(live_parts):
                            self.send_response(404)
                            self.send_header("Content-Length", "0")
                            self.end_headers()
                            return
                        sequence = int(segment.group(2))
                        status = 503 if live_fault["cut"] else 200
                        if body:
                            live_fault["segment503" if status == 503 else "segment200"] += 1
                            live_fault["events"].append({"sequence": sequence, "status": status,
                                "cut": live_fault["cuts"], "elapsedMs": round((time.monotonic() - live_fault["started"]) * 1000)})
                            del live_fault["events"][:-64]
                        if status == 503:
                            self.send_response(503)
                            self.send_header("Retry-After", "1")
                            self.send_header("Cache-Control", "no-store")
                            self.send_header("Content-Length", "0")
                            self.send_header("Connection", "close")
                            self.end_headers()
                            self.close_connection = True
                            return
                        data = live_parts[sequence][0].read_bytes()
                        mime = "video/mp2t"
                self.send_response(200)
                self.send_header("Content-Type", mime)
                self.send_header("Cache-Control", "no-store")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                if body:
                    with contextlib.suppress(BrokenPipeError, ConnectionResetError):
                        self.wfile.write(data)
                return
            if path.startswith("/library/v1/api/torrents/"):
                params = urllib.parse.parse_qs(urllib.parse.urlsplit(self.path).query)
                size = (folder / "clip.mp4").stat().st_size
                if path.endswith("/mylist") and "id" not in params:
                    payload = [
                        {"id": 74, "name": "Feature archive", "hash": "b" * 40,
                         "created_at": "2026-01-02T00:00:00Z", "download_present": True,
                         "files": [{"id": 0, "name": "Feature.2026.mp4", "size": size}]},
                        {"id": 73, "name": "Season archive", "hash": "a" * 40,
                         "created_at": "2026-01-01T00:00:00Z", "download_present": True,
                         "files": [{"id": file_id, "name": f"Show.S02E{episode:02}.mp4", "size": size}
                                   for file_id, episode in [(91, 3), (17, 4)]]},
                    ]
                elif path.endswith("/requestdl") and (params.get("torrent_id"), params.get("file_id")) in (
                    (["74"], ["0"]), (["73"], ["91"]), (["73"], ["17"]),
                ):
                    payload = f"{base}/clip.mp4?library-file={params['file_id'][0]}"
                else:
                    self.send_error(404)
                    return
                data = json.dumps({"success": True, "data": payload}).encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                if body:
                    self.wfile.write(data)
                return
            if path.startswith("/pack/v1/api/torrents/"):
                pack_hash = "a" * 40
                files = [{"id": file_id, "name": f"Part-Timers.S01E{episode:02}.1080p.mkv",
                          "size": (folder / "clip.mp4").stat().st_size, "mimetype": "video/mp4"}
                         for file_id, episode in [(91, 6), (17, 5), (33, 4)]]
                torrent = {"id": 73, "hash": pack_hash, "name": "Part-Timers S01", "files": files}
                if path.endswith("/checkcached"):
                    payload = {pack_hash: torrent}
                elif path.endswith("/mylist"):
                    payload = torrent
                elif path.endswith("/requestdl"):
                    params = urllib.parse.parse_qs(urllib.parse.urlsplit(self.path).query)
                    file_id = params.get("file_id", [""])[0]
                    payload = f"{base}/clip.mp4?pack-file={file_id}"
                else:
                    self.send_error(404)
                    return
                data = json.dumps({"success": True, "data": payload}).encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                if body:
                    self.wfile.write(data)
                return
            if path == "/live-focus/reset":
                live_focus_waiting.clear()
                live_focus_release.clear()
                with live_focus_lock:
                    live_focus_details.clear()
                value = {"reset": True}
            elif path == "/live-focus/release":
                live_focus_release.set()
                value = {"release": True}
            elif path == "/live-focus/state":
                with live_focus_lock:
                    value = {"waiting": live_focus_waiting.is_set(), "details": list(live_focus_details)}
            elif (path == "/player_api.php" and (folder / "court1.mp4").is_file() and
                    urllib.parse.parse_qs(urllib.parse.urlsplit(self.path).query).get("username") == ["qa"]):
                action = urllib.parse.parse_qs(urllib.parse.urlsplit(self.path).query).get("action", [""])[0]
                if not action:
                    value = {"user_info": {"auth": 1, "status": "Active", "max_connections": 4}}
                elif action == "get_live_categories":
                    value = [{"category_id": "1", "category_name": "Sports"}]
                elif action == "get_live_streams":
                    value = [{"stream_id": i, "name": f"Court {i}", "category_id": "1"} for i in range(1, 5)]
                else:
                    value = []
            elif path == "/player_api.php":
                query = urllib.parse.parse_qs(urllib.parse.urlsplit(self.path).query)
                if query.get("username") != ["focus-fixture-user"] or query.get("password") != ["focus-fixture-pass"]:
                    self.send_response(403)
                    self.send_header("Content-Length", "0")
                    self.end_headers()
                    return
                action = query.get("action", [""])[0]
                if not action:
                    value = {"user_info": {"auth": 1, "status": "Active", "max_connections": 2}}
                elif action in ("get_live_categories", "get_live_streams", "get_vod_categories", "get_vod_streams"):
                    value = []
                elif action == "get_series_categories":
                    value = [{"category_id": "focus", "category_name": "Focus fixture"}]
                elif action == "get_series":
                    value = [{"series_id": index, "name": f"Focus fixture series {index:02}",
                              "category_id": "focus", "cover": base + "/new.png"} for index in range(1, 21)]
                elif action == "get_series_info":
                    series_id = query.get("series_id", [""])[0]
                    with live_focus_lock:
                        live_focus_details.append(series_id)
                        del live_focus_details[:-32]
                    if series_id == "13":
                        live_focus_waiting.set()
                        live_focus_release.wait(timeout=30)
                    value = {"info": {}, "episodes": {}}
                else:
                    self.send_response(400)
                    self.send_header("Content-Length", "0")
                    self.end_headers()
                    return
            elif path == "/picker-proxy/reset":
                picker_waiting.clear()
                picker_release.clear()
                value = {"reset": True}
            elif path == "/picker-proxy/state":
                value = {"waiting": picker_waiting.is_set()}
            elif path == "/picker-proxy/release":
                picker_release.set()
                value = {"release": True}
            elif path == "/calendar/state":
                identifier = urllib.parse.parse_qs(urllib.parse.urlsplit(self.path).query).get("id", [""])[0]
                with calendar_lock:
                    value = {"requests": list(calendar_requests.get(identifier, []))}
            elif path == "/ownership/nextup-player-reset":
                nextup_player_waiting.clear()
                nextup_player_released.clear()
                nextup_player_release.clear()
                value = {"reset": True}
            elif path == "/ownership/nextup-player-release":
                nextup_player_release.set()
                value = {"release": True}
            elif path == "/ownership/reset":
                ownership_waiting.clear()
                ownership_released.clear()
                ownership_release.clear()
                with nextup_lock:
                    nextup_requests = 0
                    expired = urllib.parse.parse_qs(urllib.parse.urlsplit(self.path).query).get("expire", [""])[0]
                    if expired:
                        ownership_expired.add(expired)
                value = {"reset": True}
            elif path == "/ownership/release":
                ownership_release.set()
                value = {"release": True}
            elif path == "/ownership/state":
                value = {"waiting": ownership_waiting.is_set(), "released": ownership_released.is_set(),
                         "bytes": (folder / "clip.mp4").stat().st_size,
                         "nextupPlayerWaiting": nextup_player_waiting.is_set(),
                         "nextupPlayerReleased": nextup_player_released.is_set()}
                with nextup_lock:
                    value["nextupRequests"] = nextup_requests
            elif path == "/manifest.json":
                value = {"id": "org.4789.localfixture.dmm" if dmm else "org.4789.localfixture",
                         "name": "DMM Cast fixture" if dmm else "Local fixture", "version": "1.0.0",
                         "types": ["movie", "series"], "resources": ["catalog", "meta", "stream"],
                         "catalogs": [{"type": "movie", "id": "fixture", "name": "Fixture movies"}]}
            elif continuing and path.startswith("/meta/movie/"):
                if state["failMeta"]:
                    with continue_lock:
                        continue_state["failMeta"] = False
                    self.send_response(503)
                    self.send_header("Content-Length", "0")
                    self.end_headers()
                    return
                identifier = path.rsplit("/", 1)[-1].removesuffix(".json")
                title = "Continue Fixture " + identifier.removeprefix("fixture:cw-").upper()
                value = {"meta": {"id": identifier, "type": "movie", "name": title, "releaseInfo": "2026",
                                  "runtime": "6 min", "poster": f"{base}/new.png?title={identifier}"}}
            elif continuing and path.startswith("/stream/movie/"):
                identifier = path.rsplit("/", 1)[-1].removesuffix(".json")
                title = "Continue.Fixture." + identifier.removeprefix("fixture:cw-").upper()
                value = {"streams": [{"name": name, "title": f"{title}.2026.{quality}.WEB-DL.x264-{name}.mkv",
                                     "url": f"{base}/{route}.mp4?epoch={2 if state['expired'] else 1}&title={identifier}",
                                     "behaviorHints": {"filename": f"{title}.2026.{quality}.WEB-DL.x264-{name}.mkv",
                                                       "videoSize": size}}
                                    for name, quality, route, size in [("Original", "1080p", "original", (folder / "clip.mp4").stat().st_size),
                                                                      ("Alternative", "2160p", "alternative", (folder / "clip.mp4").stat().st_size)]
                                    if route != "original" or not state["hideOriginal"]]}
            elif path.startswith("/catalog/movie/"):
                value = {"metas": [self.meta(base, index) for index in range(8)]}
            elif path.startswith("/meta/movie/fixture:cinematic-"):
                identifier = path.rsplit("/", 1)[-1].removesuffix(".json")
                description = "In 1950s Madras, tension between a legendary director, an egotistical leading man, and a young starlet lead to murder on the film set."
                if "-long-" in identifier:
                    description = " ".join([description] * 6)
                elif "-missing-" in identifier:
                    description = None
                value = {"meta": {"id": identifier, "type": "movie", "name": "Kaantha", "year": "2025",
                                  "runtime": "150 min", "genres": ["Crime", "Drama", "Horror"],
                                  "description": description, "imdbRating": "7.2",
                                  "background": base + "/detail-backdrop.png", "logo": base + "/detail-logo.png",
                                  "cast": ["Dulquer Salmaan", "Bhagyashri Borse", "Samuthirakani"]}}
                if "-credits-" in identifier:
                    value["meta"]["director"] = ["Selvamani Selvaraj"]
            elif path.startswith("/meta/movie/fixture:filter-refresh-"):
                identifier = path.rsplit("/", 1)[-1].removesuffix(".json")
                index = int(identifier.rsplit("-", 1)[-1])
                value = {"meta": dict(self.meta(base, index), id=identifier,
                                      genres=["Action" if index % 2 == 0 else "Drama"])}
            elif path == "/meta/movie/fixture:source-deck.json":
                broken = {"url": base + "/source-deck-expired.mp4", "name": "[TB+] 4K",
                          "behaviorHints": {"filename": "Source.Deck.2024.2160p.ENG.mp4", "audioLanguages": ["en"]}}
                value = {"meta": dict(self.meta(base, 0), id="fixture:source-deck", name="Source Deck",
                                      original_language="en", streams=[broken])}
            elif path.startswith("/subtitle-search/subtitles/movie/fixture:ownership-subtitle-"):
                if "filename=Subtitle.Search.2024.720p.ENG.mp4" not in path:
                    self.send_error(400)
                    return
                value = {"subtitles": [
                    {"id": "english", "lang": "eng", "url": base + "/handoff-a.srt"},
                    {"id": "tamil", "lang": "tam", "url": base + "/handoff-b.srt"},
                ]}
            elif path == "/stream/movie/fixture:source-deck.json":
                rows = []
                for index, quality in enumerate(("2160p", "2160p", "1080p", "1080p", "1080p", "720p")):
                    rows.append({"name": ("[RD+] " if index == 2 else "") + quality,
                                 "url": base + f"/clip.mp4?source-pick={index}",
                                 "behaviorHints": {"filename": f"Source.Deck.2024.{quality}.ENG-{index}.mp4",
                                                   "audioLanguages": ["en"],
                                                   "videoSize": (folder / "clip.mp4").stat().st_size}})
                value = {"streams": rows}
            elif path == "/source-deck-expired.mp4":
                self.send_error(410)
                return
            elif path.startswith("/meta/series/fixture:calendar-"):
                identifier = path.rsplit("/", 1)[-1].removesuffix(".json")
                match = re.fullmatch(r"fixture:calendar-(\d{4}-\d{2}-\d{2})-\d+", identifier)
                if not match:
                    self.send_error(400)
                    return
                meta = self.meta(base, 0)
                meta.update(id=identifier, type="series", name="Calendar target fixture", released="2020-01-01",
                            cast=["Dulquer Salmaan", "Bhagyashri Borse", "Samuthirakani"], videos=[
                    {"id": identifier + ":1:1", "season": 1, "episode": 1, "title": "Old progress", "released": "2020-01-01"},
                    {"id": identifier + ":3:4", "season": 3, "episode": 4, "title": "Earlier row", "released": match.group(1)},
                    {"id": identifier + ":3:5", "season": 3, "episode": 5, "title": "Selected row", "released": match.group(1)},
                ])
                meta.update(background=base + "/detail-backdrop.png")
                for episode in meta["videos"]:
                    episode["thumbnail"] = base + "/detail-backdrop.png"
                    episode["overview"] = "Synopsis for the selected episode." if episode["episode"] == 5 else "Synopsis for the first episode."
                value = {"meta": meta}
            elif path.startswith(("/meta/movie/", "/meta/series/")):
                if (path.startswith("/meta/series/") and path != "/meta/series/tt7654321.json"
                        and not path.startswith("/meta/series/fixture:nextup-")):
                    self.send_response(404)
                    self.send_header("Content-Length", "0")
                    self.end_headers()
                    return
                meta = self.meta(base, 0)
                if path.startswith("/meta/movie/fixture:ownership-"):
                    meta.update(id=path.rsplit("/", 1)[-1].removesuffix(".json"),
                                name="Ownership fixture", original_language="en")
                if path.startswith("/meta/series/"):
                    meta.update(type="series", logo=base + "/series-logo.png")
                if path.startswith("/meta/series/fixture:nextup-"):
                    series_id = path.rsplit("/", 1)[-1].removesuffix(".json")
                    meta.update(id=series_id, name="Next Up fixture", original_language="en", videos=[
                        {"id": f"{series_id}:1:{episode}", "season": 1, "episode": episode,
                         "title": f"Fixture episode {episode}", "released": "2024-01-01T00:00:00Z"}
                        for episode in (1, 2)])
                if path.startswith("/meta/series/fixture:nextup-drawer-type-"):
                    titles = ["Episode 1", "Episode 2", "SOCIAL STUDIES", "DRAMATIC ARTS", "PHYSICAL EDUCATION",
                              "HOME ECONOMICS", "ROMANTIC LITERATURE", "AP PHYSICS"]
                    meta.update(name="Carrie", logo=None, background=base + "/detail-backdrop.png", videos=[
                        {"id": f"{series_id}:1:{n}", "season": 1, "episode": n, "title": title,
                         "released": "2026-10-07T20:00:00Z", "thumbnail": f"{base}/episode-still-{n}.png",
                         "overview": "Sample synopsis for episode six." if n == 6 else None}
                        for n, title in enumerate(titles, 1)])
                if path.startswith("/meta/series/fixture:nextup-phone-"):
                    meta.update(imdb_id="tt9999997", videos=[
                        {"id": f"{series_id}:1:{e}", "season": 1, "episode": e, "title": f"Episode {e}", "released": "2024-01-01"}
                        for e in (6, 7)])
                value = {"meta": meta}
            elif path.startswith("/stream/series/fixture:nextup-phone-") or path.startswith("/stream/series/tt9999997:"):
                episode = int(path.rsplit(":", 1)[-1].removesuffix(".json"))
                value = {"streams": [{"name": "[RD+] 1080p", "title": "Next Up Fixture S01E06 English",
                                      "url": base + "/clip.mp4?native-episode=6", "behaviorHints": {"audioLanguages": ["en"],
                                      "filename": "Next.Up.Fixture.S01E06.1080p.ENG.mp4",
                                      "videoSize": (folder / "clip.mp4").stat().st_size}}] if episode == 6 else []}
            elif path.startswith("/stream/series/fixture:calendar-"):
                request_id = path.rsplit("/", 1)[-1].removesuffix(".json")
                identifier, season, episode = request_id.rsplit(":", 2)
                with calendar_lock:
                    history = calendar_requests.setdefault(identifier, [])
                    history.append({"method": self.command, "path": path})
                    del history[:-32]
                value = {"streams": [{"name": "Calendar fixture 720p",
                                      "title": f"Calendar target fixture S{int(season):02}E{int(episode):02} 720p English",
                                      "url": base + "/calendar-video.mp4",
                                      "behaviorHints": {"filename": f"Calendar.Target.Fixture.S{int(season):02}E{int(episode):02}.720p.ENG.mp4",
                                                        "videoSize": (folder / "ownership-large.mp4").stat().st_size}}]}
            elif path.startswith("/stream/series/fixture:nextup-fast-"):
                episode = int(path.rsplit(":", 1)[-1].removesuffix(".json"))
                lower = "nextup-fast-lower-" in path
                quality = ("2160p" if episode == 1 else "720p") if lower else "1080p"
                name = f"Next.Up.Fixture.S01E{episode:02}.{quality}.ENG.mp4"
                media = "handoff-4k.mp4" if lower and episode == 1 else f"handoff-{episode}.mp4"
                row = {"name": "[RD+] " + quality, "title": name, "url": base + f"/{media}?fast-episode={episode}",
                       "behaviorHints": {"filename": name, "audioLanguages": ["en", "ta"], "subtitleLanguages": ["es", "en"], "bingeGroup": "same-cut",
                                         "videoSize": (folder / media).stat().st_size}}
                rows = [row]
                if episode == 2:
                    delayed = dict(row, name="[TB+] 4K", provider="DMM Cast delayed fixture", url=base + "/held-file-check.mp4")
                    if "nextup-fast-timeout-" in path:
                        rows = [dict(delayed, url=delayed["url"] + f"?copy={index}") for index in range(6)]
                    else:
                        rows.append(delayed)
                value = {"streams": rows}
            elif path == "/held-file-check.mp4":
                slow_episode_check.set()
                release_episode_check.wait(timeout=45)
                self.send_error(410)
                return
            elif path == "/handoff-check/state":
                value = {"waiting": slow_episode_check.is_set(), "released": release_episode_check.is_set()}
            elif path == "/handoff-check/reset":
                slow_episode_check.clear()
                release_episode_check.clear()
                value = {"ok": True}
            elif path == "/handoff-check/release":
                release_episode_check.set()
                value = {"ok": True}
            elif path.startswith("/stream/series/fixture:nextup-binge-"):
                episode = int(path.rsplit(":", 1)[-1].removesuffix(".json"))
                def release(group, route):
                    return {"name": "Next Up fixture 720p", "title": f"Next Up Fixture S01E{episode:02} · 720p · English audio",
                            "url": base + route, "behaviorHints": {"bingeGroup": group,
                            "filename": f"Next.Up.Fixture.S01E{episode:02}.720p.ENG.mp4",
                            "videoSize": (folder / "clip.mp4").stat().st_size}}
                rows = [release("preferred", "/nextup-1.mp4")] if episode == 1 else [
                    release("other", "/nextup-2.mp4?route=other"),
                    release("preferred", "/expired-next-episode"),
                    release("preferred", "/nextup-2.mp4?route=matched")]
                value = {"streams": rows}
            elif path.startswith("/stream/series/fixture:nextup-"):
                if path.endswith(":1:2.json") and body:
                    with nextup_lock:
                        nextup_requests += 1
                    ownership_waiting.set()
                    if not ownership_release.wait(timeout=30):
                        self.send_response(504)
                        self.send_header("Content-Length", "0")
                        self.end_headers()
                        return
                    ownership_released.set()
                value = {"streams": [{"name": "Next Up fixture 720p",
                                      "title": "Next Up Fixture S01E02 · 720p · English audio",
                                      "url": base + "/nextup-2.mp4",
                                      "behaviorHints": {"filename": "Next.Up.Fixture.S01E02.720p.ENG.mp4",
                                                        "videoSize": (folder / "clip.mp4").stat().st_size}}]}
            elif path.startswith("/stream/movie/fixture:ownership-"):
                loading_clock = path.startswith("/stream/movie/fixture:ownership-loading-clock-")
                if loading_clock and body:
                    ownership_waiting.set()
                    if not ownership_release.wait(timeout=30):
                        self.send_response(504)
                        self.send_header("Content-Length", "0")
                        self.end_headers()
                        return
                blocked = path.startswith("/stream/movie/fixture:ownership-blocked-")
                held_bookmark = path.startswith("/stream/movie/fixture:ownership-bookmark-") and not ownership_release.is_set()
                if (blocked or held_bookmark) and body:
                    ownership_waiting.set()
                    if not ownership_release.wait(timeout=30):
                        self.send_response(504)
                        self.send_header("Content-Length", "0")
                        self.end_headers()
                        return
                    ownership_released.set()
                bookmark = path.startswith("/stream/movie/fixture:ownership-bookmark-")
                large = dmm and bookmark
                hints = {"filename": "Ownership.Fixture.2024.720p.ENG.mp4"}
                if not large:
                    hints["videoSize"] = (folder / "clip.mp4").stat().st_size
                value = {"streams": [{"name": "Ownership fixture 720p",
                                      "title": "Ownership Fixture (2024) · 720p · English audio",
                                      "url": base + ("/ownership-large.mp4" if large else "/ownership.mp4") +
                                          ("?loading-clock=1" if loading_clock else f"?generation={time.monotonic_ns()}" if bookmark else ""),
                                      "behaviorHints": hints}]}
                if loading_clock:
                    value["streams"] *= 500
            elif path.startswith("/stream/movie/"):
                value = {"streams": [{"name": "Fixture 720p", "url": base + "/clip.mp4",
                                      "behaviorHints": {"filename": "Fixture.Movie.2024.720p.ENG.mp4"}}]}
            else:
                value = None
            if value is not None:
                data = json.dumps(value).encode()
                mime = "application/json"
            elif path in ("/handoff-a.srt", "/handoff-b.srt"):
                data = f"1\n00:00:01,000 --> 00:00:02,000\nSidecar {path}\n\n".encode()
                mime = "application/x-subrip"
            elif re.fullmatch(r"/episode-still-[1-8]\.png", path):
                still = folder / path.lstrip("/")
                data = (still if still.is_file() else folder / "old.png").read_bytes()
                mime = "image/png"
            elif path in ("/old.png", "/new.png", "/logo.png", "/slow-logo.png", "/series-logo.png", "/detail-backdrop.png", "/detail-logo.png", "/episode-still-dark-logo.png"):
                if path == "/slow-logo.png":
                    time.sleep(1)
                    path = "/logo.png"
                data = (folder / path.lstrip("/")).read_bytes()
                mime = "image/png"
            elif path in ("/handoff-1.mp4", "/handoff-2.mp4", "/handoff-4k.mp4", "/dts-hd-tracks.mkv", "/aac-control.m4a"):
                data = (folder / path.lstrip("/")).read_bytes()
                mime = "video/x-matroska" if path.endswith(".mkv") else "audio/mp4" if path.endswith(".m4a") else "video/mp4"
            elif path in ("/calendar-video.mp4", "/clip.mp4", "/slow.mp4", "/ownership.mp4", "/ownership-large.mp4", "/nextup-1.mp4", "/nextup-2.mp4"):
                if path == "/nextup-2.mp4" and body and self.headers.get("Range") is None:
                    nextup_player_waiting.set()
                    if not nextup_player_release.wait(timeout=30):
                        self.send_response(504)
                        self.send_header("Content-Length", "0")
                        self.end_headers()
                        return
                    nextup_player_released.set()
                if path == "/slow.mp4" and body:
                    time.sleep(6)
                if path == "/ownership.mp4" and "loading-clock=1" in self.path and body and self.headers.get("Range") is None:
                    time.sleep(6)
                if path in ("/ownership.mp4", "/ownership-large.mp4"):
                    nonce = urllib.parse.parse_qs(urllib.parse.urlsplit(self.path).query).get("generation", [""])[0]
                    with nextup_lock:
                        expired = nonce in ownership_expired
                    if expired:
                        self.send_response(410)
                        self.send_header("Content-Length", "0")
                        self.end_headers()
                        return
                data = (folder / ("ownership-large.mp4" if path in ("/ownership-large.mp4", "/calendar-video.mp4") else "clip.mp4")).read_bytes()
                mime = "video/mp4"
            else:
                self.send_response(404)
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            start, end = 0, len(data) - 1
            match = re.fullmatch(r"bytes=(\d+)-(\d*)", self.headers.get("Range", ""))
            if match:
                start = int(match.group(1))
                end = min(end, int(match.group(2))) if match.group(2) else end
            self.send_response(206 if match else 200)
            self.send_header("Content-Type", mime)
            self.send_header("Accept-Ranges", "bytes")
            if path in ("/ownership.mp4", "/ownership-large.mp4"):
                self.send_header("Content-Disposition", 'attachment; filename="Ownership.Fixture.2024.720p.ENG.mp4"')
            elif path.startswith("/nextup-"):
                episode = "01" if path == "/nextup-1.mp4" else "02"
                self.send_header("Content-Disposition", f'attachment; filename="Next.Up.Fixture.S01E{episode}.720p.ENG.mp4"')
            self.send_header("Content-Length", str(end - start + 1))
            if match:
                self.send_header("Content-Range", f"bytes {start}-{end}/{len(data)}")
            self.end_headers()
            if body:
                with contextlib.suppress(BrokenPipeError, ConnectionResetError):
                    self.wfile.write(data[start:end + 1])

        @staticmethod
        def meta(base, index):
            return {"id": f"fixture:{index}", "type": "movie", "name": f"Fixture movie {index}",
                    "poster": base + "/new.png", "background": base + "/old.png",
                    "logo": base + "/logo.png", "releaseInfo": "2024", "runtime": "10 min",
                    "description": "A local synthetic test title."}
    return Handler


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="explicit emulator-NNNN serial; physical receivers are rejected")
    parser.add_argument("--disposable", required=True, action="store_true", help="authorize synthetic settings in this emulator")
    parser.add_argument("--variant", choices=("sideload", "googleTv", "play"), default="sideload")
    parser.add_argument("--apk", type=Path)
    parser.add_argument("--test-apk", type=Path)
    parser.add_argument("--benchmark-apk", type=Path, help="optimized APK to measure after seeding the test-only profile")
    parser.add_argument("--multiview", action="store_true", help="exercise two, three, and four simultaneous live feeds")
    parser.add_argument("--reset", action="store_true", help="remove the app only in the selected disposable emulator")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--test", action="append", help="runner class or class#method; repeat to select cases")
    parser.add_argument("--dts-fixture", type=Path, help="local three-track DTS-HD fixture; enables native DTS/AAC checks")
    args = parser.parse_args()
    if args.test and (args.benchmark_apk or args.multiview):
        parser.error("--test cannot be combined with benchmark or multiview mode")
    if args.dts_fixture and not args.dts_fixture.is_file():
        parser.error("Missing local DTS fixture")
    if args.benchmark_apk and args.variant == "play":
        parser.error("Play benchmarking is not supported by tv-performance.py; use sideload/googleTv "
                     "or omit --benchmark-apk for Play native checks")
    app_package = PACKAGE + (".play" if args.variant == "play" else "")
    test_package = app_package + ".test"
    if args.apk is None:
        args.apk = ROOT / f"app/build/outputs/apk/{args.variant}/debug/app-{args.variant}-debug.apk"
    if args.test_apk is None:
        args.test_apk = ROOT / f"app/build/outputs/apk/androidTest/{args.variant}/debug/app-{args.variant}-debug-androidTest.apk"
    if not re.fullmatch(r"emulator-\d+", args.serial):
        parser.error("Only an explicit local emulator serial is accepted; no physical receiver commands were sent")

    def adb(*command, timeout=60):
        return run(["adb", "-s", args.serial, *map(str, command)], timeout=timeout)

    def install(apk):
        # Offline fixtures use our signed APKs, without requiring a Google account in the clone.
        previous = adb("shell", "settings", "get", "global", "verifier_verify_adb_installs").strip()
        try:
            adb("shell", "settings", "put", "global", "verifier_verify_adb_installs", "0")
            print(adb("install", "--no-streaming", "-r", "-t", "-d", apk, timeout=180), flush=True)
        finally:
            if previous == "null":
                adb("shell", "settings", "delete", "global", "verifier_verify_adb_installs")
            else:
                adb("shell", "settings", "put", "global", "verifier_verify_adb_installs", previous)

    if adb("shell", "getprop", "ro.kernel.qemu").strip() != "1":
        parser.error("Selected target is not an emulator")
    for apk in (args.apk, args.test_apk, args.benchmark_apk):
        if apk is not None and not apk.is_file():
            parser.error(f"Missing APK: {apk}")
    args.output.mkdir(parents=True, exist_ok=True)
    if args.reset:
        # The explicit serial, QEMU check and disposable declaration precede this mutation.
        subprocess.run(["adb", "-s", args.serial, "uninstall", app_package], capture_output=True, check=False)
    for apk in (args.apk, args.test_apk):
        install(apk)

    with tempfile.TemporaryDirectory(prefix="4789-remote-ui-") as temporary:
        folder = Path(temporary)
        for name, color in [("old.png", (255, 0, 0)), ("new.png", (0, 0, 255)),
                            ("logo.png", (0, 230, 255)), ("series-logo.png", (230, 0, 255))]:
            (folder / name).write_bytes(png(*color))
        (folder / "detail-backdrop.png").write_bytes(png(15, 48, 43, 1280, 720))
        (folder / "detail-logo.png").write_bytes(png(240, 240, 240, 440, 112))
        (folder / "episode-still-dark-logo.png").write_bytes(png(12, 12, 12, 440, 112, inset=16))
        run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i",
             "color=c=red:size=1280x720:rate=24:duration=600", "-f", "lavfi", "-i",
             "sine=frequency=440:sample_rate=48000:duration=600", "-c:v", "libx264", "-preset", "ultrafast",
             "-g", "48", "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "64k", "-movflags", "+faststart",
             str(folder / "clip.mp4")], timeout=120)
        episode_track_fixtures(folder)
        four_k_handoff_fixture(folder)
        if args.dts_fixture:
            shutil.copyfile(args.dts_fixture, folder / "dts-hd-tracks.mkv")
            run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i",
                 "sine=frequency=440:sample_rate=48000:duration=10", "-c:a", "aac",
                 str(folder / "aac-control.m4a")], timeout=30)
        # A real, playable 24MiB file: append a valid ISO-BMFF free box after the original atoms.
        clip = (folder / "clip.mp4").read_bytes()
        padding = 24 * 1024 * 1024 - len(clip)
        if padding < 8:
            raise RuntimeError("Synthetic clip leaves no room for the 24MiB padding box")
        with (folder / "ownership-large.mp4").open("wb") as large:
            large.write(clip)
            large.write(struct.pack(">I4s", padding, b"free"))
            large.write(b"\0" * (padding - 8))
        port = [0]
        if args.multiview:
            for court in range(1, 5):
                run(["ffmpeg", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "testsrc2=s=480x270:r=24",
                     "-f", "lavfi", "-i", f"sine=frequency={240 * court}", "-t", "5", "-vf", f"hue=h={60 * court}",
                     "-c:v", "libx264", "-preset", "ultrafast", "-g", "24", "-c:a", "aac",
                     str(folder / f"court{court}.mp4")], timeout=30)
        server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), fixture_handler(folder, port))
        port[0] = server.server_port
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()

        def stop_instrumentation():
            # Stop the owned test process before closing its fixture server, including on timeout.
            for package in (test_package, app_package):
                subprocess.run(["adb", "-s", args.serial, "shell", "am", "force-stop", package],
                               capture_output=True, timeout=15, check=False)

        def instrument(classes, name, *extras):
            destination = args.output / name
            command = ["adb", "-s", args.serial, "shell", "am", "instrument", "-w", "-r",
                       "-e", "disposable", "true", "-e", "fixtureBase", f"http://10.0.2.2:{port[0]}",
                       *extras, "-e", "class", classes, test_package + "/androidx.test.runner.AndroidJUnitRunner"]
            returncode, output = run_logged(command, destination, stop_instrumentation)
            print(output, flush=True)
            if returncode or "FAILURES!!!" in output or "INSTRUMENTATION_FAILED" in output or not re.search(r"OK \(\d+ tests?\)", output):
                raise RuntimeError(f"Instrumentation did not pass; see {destination}")
            return output

        def pull_artifacts(destination):
            destination.mkdir(parents=True, exist_ok=True)
            result = subprocess.run(["adb", "-s", args.serial, "pull",
                f"/sdcard/Android/data/{app_package}/files/remote-checks", str(destination)],
                capture_output=True, text=True, timeout=30, check=False)
            (destination / "artifact-copy.txt").write_text(result.stdout + result.stderr)

        results = []
        try:
            if args.benchmark_apk or args.multiview:
                instrument(SETUP, "fixture-setup.txt")
            if args.benchmark_apk:
                install(args.benchmark_apk)
                print(adb("shell", "cmd", "package", "compile", "-m", "speed", "-f", app_package, timeout=120), flush=True)
                adb("shell", "input", "keyevent", "224")
                adb("shell", "am", "start", "-n", app_package + "/" + PACKAGE + ".client.ui.MainActivity")
                forwarded = int(adb("forward", "tcp:0", "tcp:8791").strip())

                def rpc(method, params=None):
                    data = json.dumps({"jsonrpc": "2.0", "id": 1, "method": method, "params": params or {}}).encode()
                    request = urllib.request.Request(f"http://127.0.0.1:{forwarded}/jsonrpc", data=data,
                                                     headers={"Content-Type": "application/json"})
                    with urllib.request.urlopen(request, timeout=5) as response:
                        value = json.load(response)
                    if "error" in value:
                        raise RuntimeError(value["error"])
                    return value.get("result")

                try:
                    deadline = time.monotonic() + 20
                    while time.monotonic() < deadline:
                        try:
                            rpc("JSONRPC.Ping")
                            break
                        except OSError:
                            time.sleep(0.1)
                    else:
                        raise RuntimeError("Emulator receiver did not become ready")
                    rpc("X4789.NowPlaying", {"title": "Remote timing fixture", "stage": "preparing", "castID": "remote-timing"})
                    rpc("Player.Open", {"item": {"file": f"http://10.0.2.2:{port[0]}/clip.mp4"}})
                    properties = {"playerid": 1, "properties": ["speed", "time", "totaltime"]}
                    deadline = time.monotonic() + 20
                    while time.monotonic() < deadline:
                        try:
                            current = rpc("Player.GetProperties", properties)
                            if current["time"].get("seconds", 0) >= 1:
                                break
                        except RuntimeError:
                            pass
                        time.sleep(0.05)
                    else:
                        raise RuntimeError("The fixture never started playing")
                    for code in (19, 22, 22, 23):
                        adb("shell", "input", "keyevent", str(code))
                        time.sleep(0.2)
                    before = rpc("Player.GetProperties", properties)
                    output = run(["python3", str(ROOT / "scripts/tv-performance.py"), "--serial", args.serial,
                                  "--keys", "DOWN,UP", "--cycles", "20", "--runs", "3", "--pause-ms", "80",
                                  "--expect-return-focus", "--output", str(args.output / "frame-samples")], timeout=240)
                    after = rpc("Player.GetProperties", properties)
                    if before["speed"] != 1 or after["speed"] != 1:
                        raise RuntimeError("Playback did not remain active during the benchmark")
                    report = json.loads(output)
                    report.update({"playback_before": before, "playback_after": after,
                        "scope": "Local emulator; optimized APK; real More-panel rows. Host key intervals are not input-to-picture latency."})
                    (args.output / "menu-timing.json").write_text(json.dumps(report, indent=2) + "\n")
                    with (args.output / "remote-menu.png").open("wb") as screenshot:
                        subprocess.run(["adb", "-s", args.serial, "exec-out", "screencap", "-p"], stdout=screenshot, check=True)
                    rpc("Player.Stop", {"playerid": 1})
                finally:
                    adb("forward", "--remove", f"tcp:{forwarded}")
            elif args.multiview:
                for origin in ("guide", "player"):
                    instrument(PACKAGE + ".PictureInPictureHomeTest#fourLiveChannelsResizeAndKeepSoundWhenTilesChange",
                               f"multiview-{origin}.txt", "-e", "multiviewFixture", f"http://10.0.2.2:{port[0]}",
                               "-e", "multiviewFromPlayer", str(origin == "player").lower())
            else:
                selectors = ",".join(args.test) if args.test else CHECKS
                if args.dts_fixture and not args.test:
                    selectors += "," + PACKAGE + ".DtsAudioDecoderPlaybackTest"
                cases = listed_tests(instrument(selectors, "test-discovery.txt", "-e", "log", "true"))
                (args.output / "suite-plan.json").write_text(json.dumps(cases, indent=2) + "\n")
                for case in cases:
                    name = case.removeprefix(PACKAGE + ".").replace("#", "-")
                    stop_instrumentation()
                    if "Success" not in adb("shell", "pm", "clear", app_package):
                        raise RuntimeError("Disposable fixture reset failed")
                    # New state for both the app and holding endpoints, even after a failed case.
                    server.RequestHandlerClass = fixture_handler(folder, port)
                    instrument(SETUP, name + "-setup.txt")
                    started = time.monotonic()
                    status = "PASS"
                    try:
                        output = instrument(case, name + ".txt")
                        if not re.search(r"OK \(1 test\)", output):
                            raise RuntimeError("Expected exactly one test")
                    except subprocess.TimeoutExpired:
                        status = "TIMEOUT"
                    except RuntimeError:
                        status = "FAIL"
                    finally:
                        pull_artifacts(args.output / name)
                        stop_instrumentation()
                    results.append({"test": case, "status": status,
                                    "seconds": round(time.monotonic() - started, 3)})
                    (args.output / "test-results.json").write_text(json.dumps(results, indent=2) + "\n")
                    print(f"{status} {case}", flush=True)
            artifacts = f"/sdcard/Android/data/{app_package}/files/remote-checks"
            result = subprocess.run(["adb", "-s", args.serial, "pull", artifacts, str(args.output)],
                                    capture_output=True, text=True, check=False)
            (args.output / "artifact-copy.txt").write_text(result.stdout + result.stderr)
            (args.output / "run.json").write_text(json.dumps({"serial": args.serial, "emulator_only": True,
                "variant": args.variant, "app_package": app_package, "test_package": test_package,
                "test_apk": str(args.test_apk.resolve()), "app_apk": str(args.apk.resolve()), "benchmark_apk": str(args.benchmark_apk.resolve()) if args.benchmark_apk else None,
                "fixture_host": "localhost via emulator host bridge", "physical_receivers_touched": False,
                "dts_fixture": str(args.dts_fixture.resolve()) if args.dts_fixture else None,
                "completed_cases": len(results), "failed_cases": sum(result["status"] != "PASS" for result in results)}, indent=2) + "\n")
            if any(result["status"] != "PASS" for result in results):
                raise RuntimeError("Native cases failed; see test-results.json and retained per-case logs")
        finally:
            stop_instrumentation()
            server.shutdown()
            server.server_close()


if __name__ == "__main__":
    main()
