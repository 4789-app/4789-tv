# Canonical TV release procedure

The public repository is **4789-app/4789-tv**. The immutable v0.1.34 compatibility
asset remains available. Do not overwrite old release assets or redirect their bytes.

## Source and builds

Export an exact committed revision with `scripts/export-public-source.py COMMIT OUTPUT`.
Extract it at repository root: retain `4789TV/`, `App/FourSevenEightNine/Resources/Fonts/`,
and `docs/contract-samples/`. Run Gradle from `4789TV/` with JDK 17 and Android SDK 36.
The exported source must include app, contract, phone, client-data and baselineprofile, matching settings.gradle.kts. Provenance binds all five module trees. The source exporter
includes only committed inputs; it excludes unrelated iOS application source and site work.

Before pushing any release tag, replace/disable the canonical root
`.github/workflows/release.yml`: its historical tag trigger published debug APKs automatically.
The retired stub in this source tree removes all tag triggers and write permissions.

Copy `publication/tv-release-candidate.yml` to the canonical repository's
`.github/workflows/tv-release-candidate.yml`. Configure the `tv-release` environment
with `TV_COMPAT_KEYSTORE_BASE64`: the established public compatibility keystore, retained
as a secret. Never generate a fresh runner debug key. The workflow checks certificate SHA-256
`fe937c685e7cd2c010af76a23c6ea4be0114b0e4146b62cc917dcd19b2d43205`, runs the full clean
Android gate and uploads the optimized Sideload Release compatibility APK with source and checksums.
It does not publish. Add the audited native source archive and complete checksum manifest before
using the publisher. The CI artifact alone is not a complete release package. This retains in-place update compatibility; it does not turn the Android
Debug certificate into production identity assurance or provide reproducible-build attestation.

Local builds on the owner's Mac first run the repository's `scripts/storage-preflight.sh`.
Use the existing build directories. The full gate is
`./scripts/check-android-foundation.sh --clean`. Preserve the existing signing material;
do not create a new default debug key after cache cleanup.

Before tagging, include complete native corresponding source described in
[NATIVE_SOURCE_AUDIT.md](NATIVE_SOURCE_AUDIT.md). First-party Kotlin plus Maven sources
JARs alone do not cover the native binaries. Keep font license texts in `licenses/`.

## Signing and existing installations

The public v0.1.34 APK, historical test-receiver 0.1.41/code42 install, and public 0.2.3 Release build
share certificate SHA-256 `fe937c685e7cd2c010af76a23c6ea4be0114b0e4146b62cc917dcd19b2d43205`.
This was verified from the public download (whose APK SHA-256 matches the published pin).
The 0.2.3 compatibility release preserves that key and supports in-place installation.
Its certificate subject remains Android Debug; disclose that limitation in release notes.

A future production-key release is a separate migration. A different key under the same
application ID cannot update existing installs in place. Do not uninstall an owner's
configured receiver automatically: uninstalling clears local settings. Preserve the setup
and let the owner choose migration time. Production-key migration is not required for this
explicitly labelled compatibility release.

## Device acceptance and publication

Install the exact signed bytes on a suitable test receiver. Verify package/version,
certificate, remote base.apk hash, launch, discovery, playback, pause/resume/seek,
audio and subtitles, and stop/reopen. Record unsupported/unexercised device cases.
Do not record media unless explicitly requested; never use explicit content for evidence.
Keep prior test-receiver install/health evidence separate from acceptance of a newly signed build.

Create an immutable public source tag only after the checks pass. The package must contain
`4789tv.apk`, `first-party-source.tar.gz`, `native-corresponding-source.tar.gz`,
`SOURCE_PROVENANCE.json`, `RELEASE_NOTES.md`, and a complete `SHA256SUMS.txt`.
`scripts/publish-release.sh vVERSION PACKAGE` stages a draft against that existing tag in
4789-app/4789-tv. The source provenance retains the original `source_commit`. Its `build_input_git_objects`
map binds all five module trees, licenses, Gradle configuration/wrapper, fonts and fixtures
to the canonical tag, allowing different repository history without relabelling the origin.
The canonical recursive Git tree must match every recorded build input object. APK
package/version and established compatibility signature are also checked.
The script never creates a personal repository, silently creates a tag, or clobbers
assets. The release coordinator reviews the draft and publishes it. Verify a signed-out
download before updating site links and the iOS installer's pinned URL, size and SHA-256.

## Installation documentation for the release

1. On Android TV/Google TV/Fire TV, download the versioned `4789tv.apk` from the canonical
   GitHub release in Downloader or a browser. Enable installation for that downloading app
   when Android asks. The sideload receiver requires Android 9/API 28 or newer.
2. Install and open 4789 TV. Put the phone and receiver on the same trusted local network.
3. Select the receiver in the phone's casting controls. If discovery fails, check network
   isolation and the receiver's shown address; its HTTP port is 8791 and WebSocket port 9791.
4. For ADB installation, enable developer/network debugging, connect to the TV, and run
   `adb -s TV_IP:5555 install -r 4789tv.apk`. A signature mismatch requires the migration
   procedure above; do not resolve it by silently clearing the app.
5. Compare the downloaded APK to the release's checksum and signing certificate. A matching
   hash proves byte integrity. Read `TRUST.md` for current provenance and network limitations.


## User-facing release documentation

Use `CHANGELOG.md` as the maintained user-facing release history. The GitHub release body uses
that version's entry. The website uses a shorter entry with the same headings and facts. README,
INSTALL and the download-link manifest must name the same current version, URL, size and checksum.

For each release, use this order:

1. Version, release date and one short explanation of the user benefit.
2. **New**:new user actions or capabilities.
3. **Improved**:changes to an existing experience.
4. **Fixed**:the problem and observable correction.
5. **Update without losing your setup**:where to download, choose Update, do not uninstall/clear.
6. **Known limitations**:missing features and untested device cases.
7. **Verification and security**:checks that actually passed, their scope and material limits.
8. **Downloads and source code**:APK, source packages, provenance, checksums and support.

Use full sentences, normal spacing, consistent UI names and dates such as October 6, 2026. Describe
what users will see before implementation details. Keep developer process/owner instructions in
internal receipts. Never imply physical tests, performance figures or supported providers passed
when they did not. Skip empty headings rather than inventing changes.

Keep published APKs, source tags and checksummed asset snapshots immutable. A later wording change
may update the maintained changelog, README, install guide, website and GitHub release body; link
the original technical receipt rather than replacing its assets. Historical release entries must
not describe an old version as the current Downloader destination.


## 0.2.4 validation record

Issue2 was explicitly requested for publication without simulator testing. Record clean/final
builds, unit/lint/source checks and exact APK integrity independently. Do not reuse0.2.3 emulator
evidence as0.2.4 runtime acceptance. Physical TV/provider, viewport, remote and audio checks remain
pending and are disclosed in the release notes. The separate Issue1 artwork work is excluded.

## 0.2.5 validation record

Optimized sideload release and lint passed (98 tasks, 2m 2s). Final Android foundation passed (289 tasks, 1m 35s). TV variants each passed 702 unit tests, shared data 220, phone 74, contract 10. APK: 28,128,999 bytes, SHA-256 `3d96e22c81ab1040236256f622aa513329df16f2a355ecf2bec9fd2e1b580ecf`. Compatibility certificate, non-debuggable state and 16 KiB ZIP alignment passed. All 12 native entries match 0.2.4. The user explicitly requested release for their own Onn 4K Pro test; physical playback acceptance is pending. Current landscape geometry is included, while foreground overlap/readability and the wider audit remain open. New paired source references require the matching iPhone app.

## 0.2.6 validation record

Complete account Library, actual-file selection, stable saved-file identity, Stremio modal focus, group/channel automatic preview, and matching TV launcher assets are included. Version code 50. Release build/lint passed; four TV variants each passed 704 tests, client-data 224, Android phone 74, contract 10. iPhone transfer/installer checks passed 12/0 on each of two destinations. Exact APK: 29,048,759 bytes; SHA-256 `d7d2a2043ecd3faeeb67b3fb6cd7b3fd7a1adecbb40e9033ab5db706e9c3e63e`. Certificate, non-debuggable state and 16 KiB alignment passed. All 12 native entries match 0.2.5. Physical testing was explicitly deferred to the user; broader artwork/flagship acceptance remains incomplete.
