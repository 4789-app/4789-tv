# 4789 TV 0.2.5

## 0.2.5

October 29, 2026 · Downloader **5873252** · package `com.fourseveneightnine.tv` · version code 49.

### New and fixed

- Library Play keeps embedded addon video links and provider-owned file IDs. It does not replace them with an IMDb title-source search.
- The matching iPhone app syncs exact saved Library torrent/file IDs and prepared TamilMV sources during pairing and later trusted updates.
- Collections always exposes **TamilMV Collection**. Imported Library/TamilMV references remain available after phone disconnection and TV restart.
- Saved TorBox files open through their exact torrent/file IDs. An absent file or episode fails clearly instead of choosing another release or the largest file.
- Saved phone source choices survive metadata-only server refreshes. Catalog updates avoid rebuilding the TV's settings services.
- Home and Detail include the current landscape checkpoint: full-width artwork, actual image ratio, preserved top edge, device-pixel image sizing and a lighter side fade.
- Includes the earlier IPTV switching, hold/release, Back-to-guide, live preview, Favorites/Watchlist and browsing changes.

### Update

Enter **5873252** in Downloader, or open [4789library.com/tv](https://4789library.com/tv), and choose **Update**. Do not uninstall or clear storage. Update the matching iPhone app too; open it on the same Wi-Fi to sync the new source references. First setup uses **Send my setup to the TV** and TV approval.

### Verification and limits

Android build/lint and unit checks passed: 702 TV tests per variant, 220 shared-data, 74 phone and 10 contract tests. The optimized APK retains the compatibility certificate, is non-debuggable, and passed ZIP/16 KiB alignment. All 12 native library entries are byte-identical to 0.2.4. iPhone exact-source transfer unit tests passed on iPhone 17 Pro and Pro Max simulators (9 each).

The user explicitly requested release for their own Onn 4K Pro test. This release has no new TV simulator or physical-device playback acceptance. Exact start time depends on TorBox readiness and the network. Uncached or deleted files cannot start instantly. The user's original addon response has not been captured.

The saved landscape geometry is included, but text/poster overlap, bright-art readability, full 4K sharpness/device checks and the wider flagship audit remain incomplete. This is a user-test checkpoint, not a claim that every original Markdown item is complete.

APK: 28,128,999 bytes. SHA-256: `3d96e22c81ab1040236256f622aa513329df16f2a355ecf2bec9fd2e1b580ecf`.

[APK](https://github.com/4789-app/4789-tv/releases/download/v0.2.5/4789tv.apk) · [Source](https://github.com/4789-app/4789-tv/releases/download/v0.2.5/first-party-source.tar.gz) · [Checksums](https://github.com/4789-app/4789-tv/releases/download/v0.2.5/SHA256SUMS.txt)
