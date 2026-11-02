# 4789 TV 0.2.11

November 2, 2026 · Android TV, Google TV and Android-based Fire TV · version code 55

Return to the right control, browse larger movie and series posters, and change episodes or sources with less backtracking.

## New

- Subtitle Appearance is available directly beside Audio and Subtitles. Its shortcut also stays at the top of the subtitle panel, above long language lists.
- The player shows reported source formats below the title, including Dolby Vision and Dolby Atmos logos when those facts are available. These describe the source; they do not confirm the television's actual HDR or audio output.
- IPTV movies offer Latest added and Title A–Z sorting. Latest added is the default and uses the provider's added timestamp. Titles without that timestamp follow dated entries.

## Improved

- IPTV movies and series use larger poster grids instead of wide rows with tiny artwork. The live guide gives more space to the video preview.
- Series details use clearer season tabs and show more synopsis text. Only overflowing descriptions need the inline “… more” link.
- Player buttons combine icons with readable labels and current values. Next episode is directly available when a following episode is known.
- Live channel startup keeps a cover until playback is ready. Physical testing is still needed to establish whether this removes the reported first-second jerks on each provider and box.

## Fixed

- Genre and Catalog picker Back/Left return to their opener. Header Back/Left moves Genre → Catalog → Anime → Series → Movies. OK opens or selects the focused control. Movies retains the normal Home exit and main-menu entry.
- Catalog Search Down leaves the keyboard for the remembered matching row. With no matches, it reaches Clear search. Delayed, empty and failed results keep header focus.
- IPTV guide category changes keep the displayed channel list in sync. Left/Back from channels returns to the active group. Back from full-screen playback preserves the selected channel and guide viewport.
- Detail Down from Play reaches seasons before episode ordering. Moving Up from episodes restores the header and title area.
- Selecting a catalog episode opens that episode's Sources. Back from a selected source restores its row and filters, so another source is easy to try. Automatic Play still uses source ranking; it does not measure which source is fastest.
- Back hides visible player controls, including when the seek bar has focus. Open panels close first; leaving playback happens after controls are hidden. Explicit Stop remains available.

## Update without losing your setup

Enter **5873252** in Downloader or open [4789library.com/tv](https://4789library.com/tv). Open the APK and choose **Update**. **Do not uninstall or clear storage.** The application ID and established compatibility signing key are retained.

Existing sync-v2 iPhone builds remain compatible. The iPhone installer source pin is updated separately; this APK publication does not include a new iPhone IPA.

## Known limitations

The exact optimized 0.2.11 APK has not been installed on a physical television in this release step. No physical receiver is connected. The owner requested publication; Onn/Fire TV remote behavior, populated upgrades, provider startup, HDR, surround sound and long sessions remain owner checks.

Earlier emulator checks validate the development builds and named navigation flows. They are not physical-device or exact Release-byte acceptance. Fast repeated input can still miss frames on other screens. One earlier Sony HLS decoder fault remains unresolved. Provider timestamps and format facts can be absent or incorrect. Stalker catch-up remains unsupported.

## Verification and security

The clean Android foundation gate passed in 2m 39s (294 tasks: 289 executed, five up-to-date): contract 10 tests, Android phone 74, and each TV Debug flavor 716. All 2,232 test executions passed with zero failures, errors or skips. Debug lint and manifest, installer-boundary, native alignment and package checks passed.

Release checks passed in 3m 56s (236 tasks: 213 executed, 23 up-to-date). Shared-data Debug and Release each passed 224 tests; Sideload Release passed 716 tests. All reported tests had zero failures, errors or skips. Full Sideload Release lint and all three optimized TV Release APKs passed.

All three optimized APKs passed version/package, non-debuggable state, compatibility signature, ZIP integrity and 16 KiB alignment checks. Embedded optimization profiles are present. All 12 native library entries match public 0.2.10; its matching native corresponding-source offer is retained. No native rebuild was performed.

The existing development-build checks include 19 Google TV Android 14 Discover cases and seven player Back cases. The Discover cases cover picker return, both header directions, type activation, delayed/empty/error responses, grid and Detail return, Home catalog-directory return and typed-search recovery. Player checks cover seek-bar/toolbar hiding, panel priority, source return, Stop and playback errors.

Public APK: **29,830,541 bytes**. SHA-256: `1604563c807b09f5c26b26e765cabad65532cafd16fdac6c2638e61faff82323`.

Compatibility certificate SHA-256: `fe937c685e7cd2c010af76a23c6ea4be0114b0e4146b62cc917dcd19b2d43205`. Its subject remains Android Debug. It preserves update continuity and does not prove author identity. Local-network playback-control ports remain unauthenticated. See [TRUST.md](https://github.com/4789-app/4789-tv/blob/v0.2.11/4789TV/TRUST.md).

## Downloads and source code

[APK](https://github.com/4789-app/4789-tv/releases/download/v0.2.11/4789tv.apk) · [First-party source](https://github.com/4789-app/4789-tv/releases/download/v0.2.11/first-party-source.tar.gz) · [Native corresponding source](https://github.com/4789-app/4789-tv/releases/download/v0.2.11/native-corresponding-source.tar.gz) · [Provenance](https://github.com/4789-app/4789-tv/releases/download/v0.2.11/SOURCE_PROVENANCE.json) · [Checksums](https://github.com/4789-app/4789-tv/releases/download/v0.2.11/SHA256SUMS.txt) · [Report an issue](https://github.com/4789-app/4789-tv/issues)
