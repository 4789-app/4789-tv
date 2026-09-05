# 4789 TV 0.1.41 — compatibility update (code 42)

Android TV / Google TV / Shield and Android-based Fire OS 7+ sideload receiver.
Package: `com.fourseveneightnine.tv`. Android 9/API 28 minimum; this is not a Play Store release.

## What is included

- TV-native title/season/episode/source selection, Continue Watching and D-pad controls.
- Media3 playback with NextLib FFmpeg audio decoders, seeking and track controls.
- Complete bundled external-font license texts and first-party/native source archives.
- Full installation, update, local-network and troubleshooting documentation.

## Download verification

`4789tv.apk`: **83,563,455 bytes**

SHA-256: `8b402f7e5fccfe6020d80d7a4535943eef787c80abc99591483ab1bb44ad9625`

Signer certificate SHA-256: `fe937c685e7cd2c010af76a23c6ea4be0114b0e4146b62cc917dcd19b2d43205`

The certificate matches the published v0.1.34 compatibility APK and existing Den installation,
so the update can install in place. It is an **Android Debug certificate**, retained for
compatibility; do not interpret it as production signing identity, a SourceStamp, or build
attestation. No data-clearing migration is required for this compatibility update.

## Verified scope

Clean Android foundation gate passed: 10 contract, 74 phone, 382 receiver, 382 Play TV tests;
zero lint errors. Repository check passed. Exact APK installed and byte-matched on Den onn 4K
Pro (Android 14). Generated H.264/AAC playback and rapid forward/backward seeking passed:
playhead advanced 42.005 → 56.020 seconds; four seek discontinuities, five accepted frames,
zero decoder recoveries or terminal failures. No personal media was selected or recorded.

HDR/Dolby Vision, surround equipment, other codecs and all device models are not covered by
that test. The first physical probe failed because its line-count boundary did not survive
bounded-log trimming; the corrected timestamp probe passed. Both results remain in the handoff.
Native source archives include pinned upstream recipes and dependencies; byte-identical native
rebuilding is not claimed, and the original upstream moving-master helper revision is unknown.

## Install and update

Use the canonical versioned GitHub release's `4789tv.apk`. In Downloader or a TV browser, open
`https://4789library.com/player`, then download and install the APK. Allow installation for the
app opening that file. Keep 4789 TV open and connect iPhone on the same trusted local network;
allow iPhone Local Network access. Full Android/Google TV/Shield and Fire OS steps, Vega OS
exclusion, manual receiver setup, and troubleshooting are in `4789TV/INSTALL.md` in the source
archive and the public repository. Do not uninstall to resolve an unexpected signature error.

The canonical release coordinator must attach the source archives, verify a signed-out download,
and update the site/iPhone installer pins together. Previous v0.1.34 assets remain immutable.

**Private staging notice:** this package is not approved for upload. The first-party source archive
contains owner-specific examples in docs/tests/fixtures. The release coordinator is sanitizing
public-only copies, recording the mapping, and rebuilding/testing that canonical source before
publication. Remove this staging note only after those checks pass.

## Final committed-source clean rebuild

The receiver clean rebuild passed (55 tasks,53 executed,2up-to-date;7m32s). The exact clean APK
is 83,563,455bytes with SHA-256 `8b402f7e5fccfe6020d80d7a4535943eef787c80abc99591483ab1bb44ad9625`. It is installed and remote-hash-matched on
Den; the generated H264/AAC rapid-seek probe passed again on these exact clean bytes.
`physical-clean-passed.log` records the final run. No decoder recovery/terminal failure occurred.

The earlier incremental APK `b14af1cc340e5ebab60b70e7d4dfbaba5b6307f3171a0e414d8d0833d262c21e` is preserved separately. All800 ZIP entries
have identical payload hashes between the two builds; entry ordering differs, so the complete
APK hashes differ. This is not a byte-reproducible-build claim. Canonical publication must use
its own sanitized-source build and compare the complete entry manifest and signer; exact final
public bytes still need recorded installation/acceptance.
