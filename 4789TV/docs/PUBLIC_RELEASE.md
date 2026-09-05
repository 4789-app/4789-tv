# Canonical TV release procedure

The public repository is **4789-app/4789-tv**. The immutable v0.1.34 compatibility
asset remains available. Do not overwrite old release assets or redirect their bytes.

## Source and builds

Export an exact committed revision with `scripts/export-public-source.py COMMIT OUTPUT`.
Extract it at repository root: retain `4789TV/`, `App/FourSevenEightNine/Resources/Fonts/`,
and `docs/contract-samples/`. Run Gradle from `4789TV/` with JDK 17 and Android SDK 36.
All four Gradle modules are necessary even for the receiver build. The source exporter
includes only committed inputs; it excludes unrelated iOS application source and site work.

Before pushing any release tag, replace/disable the canonical root
`.github/workflows/release.yml`: its historical tag trigger published debug APKs automatically.
The retired stub in this source tree removes all tag triggers and write permissions.

Copy `publication/tv-release-candidate.yml` to the canonical repository's
`.github/workflows/tv-release-candidate.yml`. Configure the `tv-release` environment
with `TV_COMPAT_KEYSTORE_BASE64`: the established public compatibility keystore, retained
as a secret. Never generate a fresh runner debug key. The workflow checks certificate SHA-256
`fe937c685e7cd2c010af76a23c6ea4be0114b0e4146b62cc917dcd19b2d43205`, runs the full clean
Android gate and uploads the debug-variant compatibility APK with source and checksums.
It does not publish. This retains in-place update compatibility; it does not turn the Android
Debug certificate into production identity assurance or provide reproducible-build attestation.

Local builds on the owner's Mac first run the repository's `scripts/storage-preflight.sh`.
Use the existing build directories. The full gate is
`./scripts/check-android-foundation.sh --clean`. Preserve the existing signing material;
do not create a new default debug key after cache cleanup.

Before tagging, include complete native corresponding source described in
[NATIVE_SOURCE_AUDIT.md](NATIVE_SOURCE_AUDIT.md). First-party Kotlin plus Maven sources
JARs alone do not cover the native binaries. Keep font license texts in `licenses/`.

## Signing and existing installations

The public v0.1.34 APK, historical test-receiver 0.1.41/code42 install, and current clean debug build
share certificate SHA-256 `fe937c685e7cd2c010af76a23c6ea4be0114b0e4146b62cc917dcd19b2d43205`.
This was verified from the public download (whose APK SHA-256 matches the published pin).
The 0.1.41 compatibility release preserves that key and supports in-place installation.
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
map binds all four module trees, licenses, Gradle configuration/wrapper, fonts and fixtures
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
