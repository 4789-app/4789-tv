# 4789 TV 0.2.10

November 2, 2026 · Android TV, Google TV and Android-based Fire TV · version code 54

Move through live channels with fewer playback interruptions, keep remote focus when returning from menus, and see corrected branding.

## Improved

- Live channel preview waits for 250 ms of steady focus. Fast scrolling skips intermediate stream opens. Pressing OK still opens the selected channel immediately.
- Settings keeps visited panes ready while that screen is open. Returning to a pane restores its last control.
- The startup/navigation optimization profile now ships in every TV flavor. Opening Sources caches the covered Detail artwork while the drawer is visible.
- Dense guide rows retain their focus outline without resizing each channel on every move.

## Fixed

- Back from Settings popups returns to the original control instead of opening the main menu unexpectedly.
- Left from Live TV reaches the main menu. Guide Right reaches the program list; Left returns to the same channel.
- The first Down press in the live player guide moves focus instead of being lost during screen handoff.
- Pending focus previews cannot take playback back after Stop or a phone starts a different session.
- HLS streams include their required Media3 playback module.
- The in-app mark uses the orange/green 4789 TV logo. Adaptive icon padding keeps the complete mark visible. The TMDB badge uses the official asset geometry and colors.

## Update without losing your setup

Enter **5873252** in Downloader or open [4789library.com/tv](https://4789library.com/tv). Open the APK and choose **Update**. **Do not uninstall or clear storage.** The package ID, compatibility signing key and saved-settings storage are retained.

Sync v2 from 0.2.9 remains unchanged. Keep the matching sync-v2 iPhone app for settings/catalog transfer. This APK release does not require a new phone protocol.

## Known limitations

The owner will install the final 0.2.10 APK manually. Its exact signed bytes have not been installed on a physical TV in this release step. Earlier measurements used accepted 0.2.9 development packages on one Sony with synthetic local data. Onn/Google TV, Fire TV, populated upgrades, real providers, HDR, surround sound and long sessions still need owner checks.

Settings and the standalone guide still show missed frames under fast repeated remote input. The live-preview improvement is not a claim that every screen is smooth. The small preview needs physical confirmation; screenshots alone did not establish its appearance. One extended Sony HLS run hit a decoder fault whose cause remains unresolved. Stalker catch-up remains unsupported.

## Verification and security

The clean Android foundation gate passed: contract 10 tests, Android phone 74, and each TV Debug flavor 707. Shared-data Debug and Release each passed 224 tests. Sideload Release passed 707 tests and its full lint gate. All reported tests had zero failures, errors or skips. All three optimized TV Release APKs built successfully.

All three APKs passed version/package, non-debuggable state, compatibility signature, ZIP integrity and 16 KiB alignment checks. Their optimization profiles are present. All 12 native library entries match public 0.2.9; its matching native source archive is retained. No native rebuild was performed.

Public APK: **29,800,953 bytes**. SHA-256: `1803640652268ea089a85b36fbf6ed7f0b137172e078b4ae210423251c7f9900`.

Earlier controlled live-preview comparisons reduced the 95th-percentile frame time from 250–300 ms to 23–29 ms on the tested route. That is about a 90% reduction in the long frame-time tail. It is one synthetic workload on one device, not a general device benchmark. Settings/menu focus, guide pane return, immediate OK, Stop and phone takeover were checked separately.

The APK preserves compatibility certificate SHA-256 `fe937c685e7cd2c010af76a23c6ea4be0114b0e4146b62cc917dcd19b2d43205`. Its subject remains Android Debug; it preserves update continuity and does not prove author identity. Playback-control ports remain unauthenticated on the trusted local network. Settings/catalog sync v2 is separate. See [TRUST.md](https://github.com/4789-app/4789-tv/blob/v0.2.10/4789TV/TRUST.md).

## Downloads and source code

[APK](https://github.com/4789-app/4789-tv/releases/download/v0.2.10/4789tv.apk) · [First-party source](https://github.com/4789-app/4789-tv/releases/download/v0.2.10/first-party-source.tar.gz) · [Native corresponding source](https://github.com/4789-app/4789-tv/releases/download/v0.2.10/native-corresponding-source.tar.gz) · [Provenance](https://github.com/4789-app/4789-tv/releases/download/v0.2.10/SOURCE_PROVENANCE.json) · [Checksums](https://github.com/4789-app/4789-tv/releases/download/v0.2.10/SHA256SUMS.txt) · [Report an issue](https://github.com/4789-app/4789-tv/issues)
