#!/usr/bin/env bash
# Stage an immutable, reviewed package in the canonical source repository.
# The release coordinator publishes the draft after physical acceptance.
set -euo pipefail
REPO="4789-app/4789-tv"
[[ $# == 2 ]] || { echo "Usage: $0 vVERSION PACKAGE_DIRECTORY" >&2; exit 64; }
TAG="$1"
PACKAGE="$(cd "$2" && pwd)"
[[ ! -e "$PACKAGE/DO_NOT_PUBLISH_PRIVATE_SOURCE.txt" ]] || {
  echo "Private source staging is blocked from publication; replace with audited public export" >&2
  exit 1
}
[[ "$TAG" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "Invalid tag" >&2; exit 64; }
for file in 4789tv.apk SHA256SUMS.txt RELEASE_NOTES.md SOURCE_PROVENANCE.json first-party-source.tar.gz native-corresponding-source.tar.gz; do
  [[ -s "$PACKAGE/$file" ]] || { echo "Missing release input: $file" >&2; exit 1; }
done
python3 - "$PACKAGE" <<'VERIFY'
import hashlib
from pathlib import Path
import re
import sys
root = Path(sys.argv[1])
required = {'4789tv.apk', 'RELEASE_NOTES.md', 'SOURCE_PROVENANCE.json',
            'first-party-source.tar.gz', 'native-corresponding-source.tar.gz'}
seen = set()
for line in (root / 'SHA256SUMS.txt').read_text().splitlines():
    match = re.fullmatch(r'([0-9a-f]{64})  ([A-Za-z0-9_.-]+)', line)
    if not match:
        raise SystemExit('Invalid checksum manifest entry')
    digest, name = match.groups()
    if name not in required or name in seen:
        raise SystemExit('Unexpected or duplicate checksum entry: ' + name)
    if hashlib.sha256((root / name).read_bytes()).hexdigest() != digest:
        raise SystemExit('Checksum mismatch: ' + name)
    seen.add(name)
if seen != required:
    raise SystemExit('Manifest does not cover every release input')
VERIFY
# Never create a repository, tag, or overwrite an existing asset here.
# Both lightweight and annotated tags must resolve to the candidate's canonical commit.
OBJECT="$(gh api "repos/$REPO/git/ref/tags/$TAG" --jq '.object.sha')"
TYPE="$(gh api "repos/$REPO/git/ref/tags/$TAG" --jq '.object.type')"
while [[ "$TYPE" == tag ]]; do
  NEXT="$(gh api "repos/$REPO/git/tags/$OBJECT" --jq '.object.sha')"
  TYPE="$(gh api "repos/$REPO/git/tags/$OBJECT" --jq '.object.type')"
  OBJECT="$NEXT"
done
[[ "$TYPE" == commit ]] || { echo "Tag does not resolve to a commit" >&2; exit 1; }
# Canonical exports have different history. Compare every build input Git object, preserving
# source_commit as the original build/export origin instead of relabelling it as canonical.
TREE_FILE="$(mktemp)"
trap 'rm -f "$TREE_FILE"' EXIT
gh api "repos/$REPO/git/trees/$OBJECT?recursive=1" > "$TREE_FILE"
python3 - "$PACKAGE/SOURCE_PROVENANCE.json" "$TREE_FILE" <<'PROVENANCE'
import json, sys
provenance = json.load(open(sys.argv[1]))
tree = json.load(open(sys.argv[2]))
if tree.get('truncated'):
    raise SystemExit('Canonical tree response is incomplete')
required = {f'4789TV/{name}' for name in (
    'app', 'contract', 'phone', 'tvplay', 'gradle', 'licenses',
    'build.gradle.kts', 'settings.gradle.kts', 'gradle.properties', 'gradlew', 'gradlew.bat'
)} | {'App/FourSevenEightNine/Resources/Fonts', 'docs/contract-samples'}
expected = provenance.get('build_input_git_objects', {})
actual = {entry['path']: entry['sha'] for entry in tree['tree']}
if set(expected) != required or any(actual.get(path) != oid for path, oid in expected.items()):
    raise SystemExit('Build inputs differ from canonical tag or source mapping is incomplete')
PROVENANCE
: "${ANDROID_HOME:?Android SDK required for APK metadata verification}"
BADGING="$("$ANDROID_HOME/build-tools/36.0.0/aapt2" dump badging "$PACKAGE/4789tv.apk")"
[[ "$BADGING" == *"package: name='com.fourseveneightnine.tv'"* && "$BADGING" == *"versionName='${TAG#v}'"* ]] || {
  echo "APK package/version does not match release tag" >&2; exit 1;
}
SIGNATURE="$("$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --print-certs "$PACKAGE/4789tv.apk")"
# Preserve the established public compatibility signing identity for in-place updates.
EXPECTED_CERT="fe937c685e7cd2c010af76a23c6ea4be0114b0e4146b62cc917dcd19b2d43205"
grep -Fq "certificate SHA-256 digest: $EXPECTED_CERT" <<<"$SIGNATURE" || {
  echo "APK does not preserve the public compatibility certificate" >&2; exit 1;
}
if gh release view "$TAG" --repo "$REPO" >/dev/null 2>&1; then
  echo "Release already exists; refusing replacement" >&2
  exit 1
fi
gh release create "$TAG" --repo "$REPO" --verify-tag --draft \
  --title "4789 TV ${TAG#v}" --notes-file "$PACKAGE/RELEASE_NOTES.md" \
  "$PACKAGE/4789tv.apk" "$PACKAGE/SHA256SUMS.txt" \
  "$PACKAGE/SOURCE_PROVENANCE.json" "$PACKAGE/first-party-source.tar.gz" \
  "$PACKAGE/native-corresponding-source.tar.gz"
