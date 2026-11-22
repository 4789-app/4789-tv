#!/usr/bin/env python3
"""Repeatable, data-preserving D-pad frame probe for a configured Android TV.

This script never installs, force-stops, clears data, or generates a baseline profile.
Use an optimized release APK and verify the focus journey on screen before comparing runs.
"""

import argparse
import csv
import json
import math
import re
import subprocess
import time
import uuid
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path

PACKAGE = "com.fourseveneightnine.tv"
KEYS = {"UP": 19, "DOWN": 20, "LEFT": 21, "RIGHT": 22, "OK": 23, "BACK": 4, "MENU": 82}
ROUTES = ["Search", "Home", "Live TV", "Discover", "Collections", "Calendar", "Settings"]


def adb(serial, *args, timeout=30):
    result = subprocess.run(["adb", "-s", serial, *map(str, args)], capture_output=True,
                            text=True, timeout=timeout, check=False)
    if result.returncode:
        raise RuntimeError(result.stderr.strip() or result.stdout.strip())
    return result.stdout


def require_foreground(serial):
    activities = adb(serial, "shell", "dumpsys", "activity", "activities")
    resumed = [line for line in activities.splitlines()
               if "mResumedActivity:" in line or "topResumedActivity=" in line]
    if not resumed or not any(PACKAGE + "/" in line for line in resumed):
        raise RuntimeError("4789 is not foreground; refusing to send keys to another app")


def press(serial, key, pause):
    require_foreground(serial)
    adb(serial, "shell", "input", "dpad", "keyevent", KEYS[key])
    time.sleep(pause)


def focus_bounds(serial):
    require_foreground(serial)
    remote = f"/sdcard/Download/4789-perf-{uuid.uuid4().hex}.xml"
    try:
        adb(serial, "shell", "uiautomator", "dump", remote)
        root = ET.fromstring(adb(serial, "exec-out", "cat", remote))
    finally:
        adb(serial, "shell", "rm", "-f", remote)
    require_foreground(serial)
    node = next((node for node in root.iter("node") if node.attrib.get("focused") == "true"), None)
    if node is None or node.attrib.get("package") != PACKAGE:
        raise RuntimeError("4789 content does not own focus; journey is invalid")
    bounds = node.attrib.get("bounds", "")
    match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds)
    if not match or (match.group(1) == "0" and match.group(2) == "0"):
        raise RuntimeError("Root-window or missing focus is not a content measurement")
    return bounds


def in_rail(bounds):
    match = re.match(r"\[(\d+),\d+\]\[(\d+),", bounds)
    return bool(match and 80 <= int(match.group(1)) <= 200 and int(match.group(2)) <= 400)


def go_route(serial, route):
    press(serial, "MENU", 0.5)
    if not in_rail(focus_bounds(serial)):
        press(serial, "MENU", 0.5)
    if not in_rail(focus_bounds(serial)):
        raise RuntimeError("Navigation rail did not receive focus")
    for _ in range(len(ROUTES) + 1):
        press(serial, "UP", 0.08)
    for _ in range(ROUTES.index(route)):
        press(serial, "DOWN", 0.08)
    press(serial, "OK", 0.6)
    if in_rail(focus_bounds(serial)):
        press(serial, "RIGHT", 0.3)
    if in_rail(focus_bounds(serial)):
        raise RuntimeError("Content did not receive focus; refusing a navigation-rail measurement")


def metric(output, pattern, required=True):
    match = re.search(pattern, output, re.MULTILINE)
    if not match and required:
        raise RuntimeError("gfxinfo metric unavailable: " + pattern)
    return int(match.group(1)) if match else None


def ui_thread_frame_times(output):
    """Main-thread work through render submission, kept separate from emulator GPU pacing."""
    header = None
    values = []
    for line in output.splitlines():
        if line.startswith("Flags,"):
            header = next(csv.reader([line]))
            continue
        if header is None or not re.match(r"^\d+,", line):
            continue
        row = dict(zip(header, next(csv.reader([line]))))
        try:
            if int(row["Flags"]) != 0:
                continue
            start, end = int(row["HandleInputStart"]), int(row["SyncQueued"])
        except (KeyError, ValueError):
            continue
        if start > 0 and end >= start:
            values.append((end - start) / 1_000_000)
    return values


def percentile(values, fraction):
    if not values:
        return None
    ordered = sorted(values)
    return round(ordered[math.ceil(len(ordered) * fraction) - 1], 3)


def sample(serial, keys, cycles, pause, raw_path=None):
    before = focus_bounds(serial)
    if not before or in_rail(before):
        raise RuntimeError("Content focus is required; refusing a missing-focus or navigation-rail measurement")
    adb(serial, "shell", "dumpsys", "gfxinfo", PACKAGE, "reset")
    started = time.monotonic()
    for _ in range(cycles):
        for key in keys:
            press(serial, key, pause)
    input_ms = round((time.monotonic() - started) * 1000)
    require_foreground(serial)
    output = adb(serial, "shell", "dumpsys", "gfxinfo", PACKAGE, "framestats")
    if raw_path is not None:
        raw_path.write_text(output)
    ui_times = ui_thread_frame_times(output)
    after = focus_bounds(serial)
    frames = metric(output, r"^\s*Total frames rendered:\s*(\d+)")
    if frames == 0:
        raise RuntimeError("No rendered frames; focus journey is invalid")
    janky = metric(output, r"^\s*Janky frames:\s*(\d+)")
    return {
        "frames": frames,
        "input_ms": input_ms,
        "effective_key_interval_ms": round(input_ms / (cycles * len(keys)), 1),
        "janky_frames": janky,
        "janky_percent": round(100 * janky / frames, 2),
        "p50_ms": metric(output, r"^\s*50th percentile:\s*(\d+)"),
        "p95_ms": metric(output, r"^\s*95th percentile:\s*(\d+)"),
        "frozen_frames": metric(output, r"^\s*Number of frozen frames:\s*(\d+)", False),
        "slow_ui_frames": metric(output, r"^\s*Number Slow UI thread:\s*(\d+)", False),
        "slow_draw_frames": metric(output, r"^\s*Number Slow issue draw commands:\s*(\d+)", False),
        "gpu_p95_ms": metric(output, r"^\s*95th gpu percentile:\s*(\d+)", False),
        "ui_thread_frame_count": len(ui_times),
        "ui_thread_p50_ms": percentile(ui_times, 0.5),
        "ui_thread_p95_ms": percentile(ui_times, 0.95),
        "ui_thread_frame_ms": ui_times,
        "focus_before": before,
        "focus_after": after,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="adb device serial")
    parser.add_argument("--route", choices=ROUTES, help="open a top-level route before probing")
    parser.add_argument("--keys", required=True, help="comma-separated D-pad keys, e.g. RIGHT,LEFT")
    parser.add_argument("--cycles", type=int, default=15, help="repetitions of --keys")
    parser.add_argument("--runs", type=int, default=2)
    parser.add_argument("--pause-ms", type=int, default=80)
    parser.add_argument("--warmup-cycles", type=int, default=0,
                        help="unmeasured key cycles after route entry to settle focus")
    parser.add_argument("--settle-ms", type=int, default=1000,
                        help="wait after route entry before recording frames")
    parser.add_argument("--expect-return-focus", action="store_true",
                        help="fail if each key cycle does not end on its starting focus bounds")
    parser.add_argument("--output", type=Path, help="new directory for raw frame rows and the JSON report")
    args = parser.parse_args()
    keys = [key.strip().upper() for key in args.keys.split(",")]
    if not keys or any(key not in KEYS for key in keys):
        parser.error("--keys must contain supported D-pad key names")
    if (args.cycles < 1 or args.runs < 1 or args.pause_ms < 0 or args.settle_ms < 0
            or args.warmup_cycles < 0):
        parser.error("cycles and runs must be positive, pauses and warmup nonnegative")
    if args.output is not None:
        try:
            args.output.mkdir(parents=True, exist_ok=False)
        except FileExistsError:
            parser.error("--output already exists; choose a new directory to preserve prior evidence")
    if args.route:
        go_route(args.serial, args.route)
        time.sleep(args.settle_ms / 1000)
    for _ in range(args.warmup_cycles):
        for key in keys:
            press(args.serial, key, args.pause_ms / 1000)
    samples = []
    for index in range(args.runs):
        raw_path = args.output / f"run-{index + 1}-gfxinfo.txt" if args.output is not None else None
        measured = sample(args.serial, keys, args.cycles, args.pause_ms / 1000, raw_path)
        if args.expect_return_focus and measured["focus_before"] != measured["focus_after"]:
            raise RuntimeError("Focus did not return to its start; journey is invalid: "
                               f"{measured['focus_before']} -> {measured['focus_after']}")
        samples.append(measured)
    result = {
        "at": datetime.now(timezone.utc).isoformat(),
        "serial": args.serial,
        "route": args.route or "current screen",
        "keys": keys,
        "cycles": args.cycles,
        "pause_ms": args.pause_ms,
        "samples": samples,
    }
    if args.output is not None:
        result["raw_frame_directory"] = str(args.output.resolve())
        (args.output / "result.json").write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
