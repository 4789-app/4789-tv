#!/usr/bin/env python3
"""
Receiver Diagnostics Log Analyzer for 4789 TV.
Pulls receiver-diagnostics.log via ADB from Android TV, classifies log entries
into severity tiers (CRITICAL, WARNING, INFO), and formats a Markdown summary.
"""
import sys
import subprocess
import re
from datetime import datetime

def fetch_log(tv_ip="192.168.0.106:5555"):
    cmd = [
        "adb", "-s", tv_ip, "shell", "run-as", "com.fourseveneightnine.tv",
        "cat", "files/receiver-diagnostics.log"
    ]
    try:
        res = subprocess.run(cmd, capture_output=True, text=True, timeout=10)
        if res.returncode == 0 and res.stdout.strip():
            return res.stdout.strip().splitlines()
    except Exception as e:
        print(f"Error fetching log via ADB: {e}", file=sys.stderr)
    return []

def classify_logs(lines):
    criticals = []
    warnings = []
    infos = []

    for line in lines:
        if not line.strip():
            continue
        lower = line.lower()
        if "unsupported" in lower or "fatal=true" in lower or "error" in lower or "exoplayer.error" in lower or "underrun" in lower:
            criticals.append(line)
        elif "debriderrorcard" in lower or "rescued" in lower or "unmatched" in lower or "stalled" in lower:
            warnings.append(line)
        else:
            infos.append(line)

    return criticals, warnings, infos

def main():
    tv_ip = sys.argv[1] if len(sys.argv) > 1 else "192.168.0.106:5555"
    lines = fetch_log(tv_ip)
    if not lines:
        print(f"No logs retrieved from {tv_ip}.")
        return

    criticals, warnings, infos = classify_logs(lines)

    print("# 4789 TV Diagnostic Log Summary Report")
    print(f"**Target TV**: `{tv_ip}` | **Total Log Entries Analyzed**: {len(lines)}\n")

    print("## 🚨 Critical Events (" + str(len(criticals)) + ")")
    if criticals:
        for c in criticals[-10:]:
            print(f"- `{c}`")
    else:
        print("- *No critical errors found in log buffer.*")

    print("\n## ⚠️ Warnings & Anomalies (" + str(len(warnings)) + ")")
    if warnings:
        for w in warnings[-10:]:
            print(f"- `{w}`")
    else:
        print("- *No warnings detected.*")

    print("\n## ℹ️ Recent System Activity (" + str(len(infos)) + ")")
    for i in infos[-5:]:
        print(f"- `{i}`")

if __name__ == "__main__":
    main()
