#!/usr/bin/env python3
"""Repeatable, data-preserving D-pad frame probe for a configured Android TV.

This script never installs, force-stops, clears data, or generates a baseline profile.
Use an optimized release APK and verify the focus journey on screen before comparing runs.
"""

import argparse
import json
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from datetime import datetime, timezone

PACKAGE = "com.fourseveneightnine.tv"
KEYS = {"UP": 19, "DOWN": 20, "LEFT": 21, "RIGHT": 22, "OK": 23, "BACK": 4, "MENU": 82}
ROUTES = ["Search", "Home", "Live TV", "Discover", "Collections", "Calendar", "Settings"]


def adb(serial, *args, timeout=30):
    result = subprocess.run(["adb", "-s", serial, *map(str, args)], capture_output=True,
                            text=True, timeout=timeout, check=False)
    if result.returncode:
        raise RuntimeError(result.stderr.strip() or result.stdout.strip())
    return result.stdout


def press(serial, key, pause):
    adb(serial, "shell", "input", "keyevent", KEYS[key])
    time.sleep(pause)


def focus_bounds(serial):
    adb(serial, "shell", "uiautomator", "dump", "/sdcard/Download/4789-perf-current.xml")
    root = ET.fromstring(adb(serial, "exec-out", "cat", "/sdcard/Download/4789-perf-current.xml"))
    return next((node.attrib.get("bounds", "") for node in root.iter("node")
                 if node.attrib.get("focused") == "true"), "")


def in_rail(bounds):
    match = re.match(r"\[(\d+),", bounds)
    return bool(match and 80 <= int(match.group(1)) <= 400)


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


def metric(output, pattern, required=True):
    match = re.search(pattern, output, re.MULTILINE)
    if not match and required:
        raise RuntimeError("gfxinfo metric unavailable: " + pattern)
    return int(match.group(1)) if match else None


def sample(serial, keys, cycles, pause):
    before = focus_bounds(serial)
    adb(serial, "shell", "dumpsys", "gfxinfo", PACKAGE, "reset")
    started = time.monotonic()
    for _ in range(cycles):
        for key in keys:
            press(serial, key, pause)
    input_ms = round((time.monotonic() - started) * 1000)
    output = adb(serial, "shell", "dumpsys", "gfxinfo", PACKAGE)
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
    args = parser.parse_args()
    keys = [key.strip().upper() for key in args.keys.split(",")]
    if not keys or any(key not in KEYS for key in keys):
        parser.error("--keys must contain supported D-pad key names")
    if (args.cycles < 1 or args.runs < 1 or args.pause_ms < 0 or args.settle_ms < 0
            or args.warmup_cycles < 0):
        parser.error("cycles and runs must be positive, pauses and warmup nonnegative")
    if args.route:
        go_route(args.serial, args.route)
        time.sleep(args.settle_ms / 1000)
    for _ in range(args.warmup_cycles):
        for key in keys:
            press(args.serial, key, args.pause_ms / 1000)
    samples = []
    for _ in range(args.runs):
        measured = sample(args.serial, keys, args.cycles, args.pause_ms / 1000)
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
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
