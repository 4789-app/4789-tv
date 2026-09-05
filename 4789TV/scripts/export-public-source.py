#!/usr/bin/env python3
"""Export committed Android sources and their external build inputs, never the working tree."""
import gzip
import hashlib
import io
import json
from pathlib import Path
import subprocess
import sys
import tarfile

ROOT = Path(__file__).resolve().parents[2]
INPUTS = ['4789TV', 'App/FourSevenEightNine/Resources/Fonts', 'docs/contract-samples']
BUILD_INPUTS = [f'4789TV/{name}' for name in (
    'app', 'contract', 'phone', 'tvplay', 'gradle', 'licenses',
    'build.gradle.kts', 'settings.gradle.kts', 'gradle.properties', 'gradlew', 'gradlew.bat'
)] + INPUTS[1:]

def git(*args):
    return subprocess.check_output(['git', '-C', str(ROOT), *args])

def main():
    if len(sys.argv) != 3:
        raise SystemExit('Usage: export-public-source.py COMMIT OUTPUT_DIRECTORY')
    commit = git('rev-parse', '--verify', sys.argv[1] + '^{commit}').decode().strip()
    destination = Path(sys.argv[2]).resolve()
    destination.mkdir(parents=True, exist_ok=True)
    archive = destination / 'first-party-source.tar.gz'
    if archive.exists():
        raise SystemExit('Refusing to overwrite existing source archive')
    raw = git('archive', '--format=tar', commit, '--', *INPUTS)
    with tarfile.open(fileobj=io.BytesIO(raw)) as source:
        names = source.getnames()
        required = [f'4789TV/{module}/build.gradle.kts' for module in ('app', 'contract', 'phone', 'tvplay')]
        required += ['4789TV/gradle/wrapper/gradle-wrapper.jar', '4789TV/LICENSE']
        for name in required:
            if name not in names:
                raise SystemExit('Missing source input: ' + name)
        for name in names:
            parts = Path(name).parts
            if any(part in ('build', '.gradle', 'local.properties') for part in parts) or name.endswith(('.apk', '.aab', '.keystore', '.jks')):
                raise SystemExit('Forbidden generated/private export input: ' + name)
    archive.write_bytes(gzip.compress(raw, mtime=0))
    provenance = {'source_commit': commit, 'archive': archive.name,
                  'sha256': hashlib.sha256(archive.read_bytes()).hexdigest(),
                  'inputs': INPUTS, 'native_corresponding_source': 'separate archive required',
                  'build_directory': '4789TV',
                  'build_input_git_objects': {
                      path: git('rev-parse', commit + ':' + path).decode().strip()
                      for path in BUILD_INPUTS
                  }}
    (destination / 'SOURCE_PROVENANCE.json').write_text(json.dumps(provenance, indent=2) + '\n')
    print(json.dumps(provenance, indent=2))

if __name__ == '__main__':
    main()
