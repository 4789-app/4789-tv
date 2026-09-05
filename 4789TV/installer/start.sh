#!/usr/bin/env bash
# Open the 4789 TV installer window. Mac-side tool only — never shipped with the app.
cd "$(dirname "${BASH_SOURCE[0]}")" || exit 1
exec python3 serve.py "$@"
