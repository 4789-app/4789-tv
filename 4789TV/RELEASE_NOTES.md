# 4789 TV 0.2.12

November 2, 2026 · version code 56

Screen changes now use brief, coordinated motion. Returning to a title keeps more of the screen state you left.

## New

- Shared opening and closing transitions for panels, menus and full-screen browse overlays.

## Improved

- Screen entry and Back use short fades and restrained movement. Main-menu destinations stay anchored.
- Live TV tabs, movie/series categories, Discover content, seasons and calendar months use bounded content fades.
- Settings waits briefly for category focus to settle. Right and OK open the selected pane immediately.
- Player controls fade and move a small distance together. The live guide fades around the existing video surface.
- Reduced Motion disables the new movement and fades, including artwork fades. Returning artwork avoids repeat disk-cache fades when already shown.

## Fixed

- Panels keep their pixels through closing while releasing focus, actions and Back handling immediately. Rapid reopen restores the prior control.
- Home retains the previous hero artwork on return from Detail.
- Detail retains loaded metadata while another screen is above it. Returning from a related title restores its card and scroll position.
- Replaced Discover catalog requests are cancelled. Old catalog cards cannot accept actions under a new category label.

## Update without losing your setup

Enter **5873252** in Downloader or open [4789library.com/tv](https://4789library.com/tv). Choose **Update**. Do not uninstall or clear storage. The application ID and established compatibility signing key are retained.

Existing sync-v2 phone builds remain compatible. This release includes no iPhone IPA.

## Known limitations

The owner requested this APK for visual testing. No physical TV is connected. Physical-remote feel, populated upgrades, cold/warm returns, fast repeated input, guide/full-screen video resizing, real providers, HDR/audio and long sessions remain owner checks. No measured improvement in current TV frame timing is claimed. The video surface, native engines and dependencies are unchanged. One earlier Sony HLS decoder fault remains unresolved.

## Verification and security

The clean Android foundation gate passed: 2,232 test executions with zero failures, errors or skips, three TV Debug flavors, lint and package-boundary checks. Optimized Release unit tests, full Sideload Release lint and all three Release APK builds passed. APK signatures, version codes, ZIP integrity, 16 KiB alignment, embedded optimization profiles and all 12 unchanged native-library entries were verified. Physical TV frame timing remains unmeasured.

This compatibility APK retains certificate SHA-256 `fe937c685e7cd2c010af76a23c6ea4be0114b0e4146b62cc917dcd19b2d43205`. Its subject remains Android Debug. It preserves update continuity and does not prove author identity. Local-network playback-control ports remain unauthenticated. See [TRUST.md](https://github.com/4789-app/4789-tv/blob/v0.2.12/4789TV/TRUST.md).

## Downloads and source code

[APK](https://github.com/4789-app/4789-tv/releases/download/v0.2.12/4789tv.apk) · [First-party source](https://github.com/4789-app/4789-tv/releases/download/v0.2.12/first-party-source.tar.gz) · [Native source](https://github.com/4789-app/4789-tv/releases/download/v0.2.12/native-corresponding-source.tar.gz) · [Provenance](https://github.com/4789-app/4789-tv/releases/download/v0.2.12/SOURCE_PROVENANCE.json) · [Checksums](https://github.com/4789-app/4789-tv/releases/download/v0.2.12/SHA256SUMS.txt)
