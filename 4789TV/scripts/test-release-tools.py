#!/usr/bin/env python3
"""Offline regression checks for release staging; no GitHub writes or Android device changes."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile

PUBLISH = Path(__file__).with_name('publish-release.sh')
NAMES = ['4789tv.apk', 'RELEASE_NOTES.md', 'SOURCE_PROVENANCE.json',
         'first-party-source.tar.gz', 'native-corresponding-source.tar.gz']
with tempfile.TemporaryDirectory() as tmp:
    root = Path(tmp)
    for name in NAMES:
        (root / name).write_text('fixture')
    (root / 'SOURCE_PROVENANCE.json').write_text(json.dumps({'source_commit': 'a' * 40}))
    binary = root / 'bin'
    binary.mkdir()
    gh = binary / 'gh'
    gh.write_text('''#!/bin/sh
case "$*" in
  *object.sha*) echo bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb;;
  *object.type*) echo commit;;
  *git/trees*) echo '{"truncated":false,"tree":[]}';;
  *) echo 'Unexpected GitHub operation' >&2; exit 99;;
esac
''')
    gh.chmod(0o755)
    env = dict(os.environ, PATH=str(binary) + os.pathsep + os.environ['PATH'])
    def rejects(manifest, message):
        (root / 'SHA256SUMS.txt').write_text(manifest)
        result = subprocess.run(['bash', str(PUBLISH), 'v0.1.41', str(root)],
                                env=env, capture_output=True, text=True)
        assert result.returncode != 0 and message in result.stdout + result.stderr, result
    digest = hashlib.sha256(b'fixture').hexdigest()
    rejects(digest + '  4789tv.apk\n', 'every release input')
    rejects('0' * 64 + '  ../private\n', 'Invalid checksum')
    complete = ''.join(hashlib.sha256((root / name).read_bytes()).hexdigest() + '  ' + name + '\n' for name in NAMES)
    rejects(complete, 'differ from canonical tag')
    (root / 'DO_NOT_PUBLISH_PRIVATE_SOURCE.txt').write_text('Private staging')
    rejects(complete, 'Private source staging is blocked')
print('PASS release staging rejects incomplete hashes, path traversal, and mismatched source tags')

# A bounded receiver log may lose old lines during a test. Timestamp filtering keeps new events.
rotated = "2026-09-04T20:00:01.000-05:00 old\n2026-09-04T20:01:02.000-05:00 new\n"
result = subprocess.check_output(['awk', '-v', 'start=2026-09-04T20:01:00.000-05:00', '$1 > start'],
                                 input=rotated, text=True)
assert result == "2026-09-04T20:01:02.000-05:00 new\n"
print('PASS physical probe timestamp boundary survives log trimming')

# Release inventory must cover every checked-in module, including profile generation inputs.
import re
EXPORT = Path(__file__).with_name('export-public-source.py')
settings = Path(__file__).resolve().parents[1] / 'settings.gradle.kts'
modules = re.findall(r'include\(":([^"\)]+)"\)', settings.read_text())
assert 'tvplay' not in modules
for module in modules:
    assert "'" + module + "'" in EXPORT.read_text(), module
    assert "'" + module + "'" in PUBLISH.read_text(), module
print('PASS source export/provenance includes all current Gradle module trees')
