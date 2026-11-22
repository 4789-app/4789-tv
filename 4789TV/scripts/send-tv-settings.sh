#!/usr/bin/env bash
set -euo pipefail

if (( $# < 2 || $# > 3 )); then
    echo "Usage: $0 <4789 settings JSON> <ADB device serial> [ADB server port]" >&2
    exit 2
fi

export_file=$1
device_serial=$2
adb_port=${3:-5037}
if ! python3 - "$export_file" <<'PY'
import json
import sys

try:
    with open(sys.argv[1], "rb") as export:
        data = export.read(512 * 1024 + 1)
    if len(data) > 512 * 1024:
        sys.exit(1)
    document = json.loads(data.decode("utf-8"))
    version = document.get("version")
    if document.get("format") != "4789-settings" or type(version) is not int or version != 1:
        sys.exit(1)
except (OSError, ValueError, AttributeError):
    sys.exit(1)
PY
then
    echo "Choose a valid 4789 settings export no larger than 512 KiB." >&2
    exit 2
fi

adb_target=(adb -P "$adb_port" -s "$device_serial")
package=com.fourseveneightnine.tv
inbox="/sdcard/Android/data/$package/files/Download"
destination="$inbox/4789-settings.json"
pending="$inbox/.4789-settings-$(uuidgen).pending"
if [[ $("${adb_target[@]}" shell "pm path $package") != package:* ]]; then
    echo "4789 TV is unavailable on that ADB device." >&2
    exit 1
fi
if ! "${adb_target[@]}" shell "mkdir -p '$inbox' && test ! -e '$destination'"; then
    echo "Check the TV connection and import any settings already waiting on the TV." >&2
    exit 1
fi

trap '"${adb_target[@]}" shell "rm -f $pending" >/dev/null 2>&1 || true' EXIT
"${adb_target[@]}" push "$export_file" "$pending" >/dev/null
"${adb_target[@]}" shell "mv -nT '$pending' '$destination' && test ! -e '$pending'"
echo "On the TV, open Settings → Pair & Sync → Import from a file. Review and approve the import."
