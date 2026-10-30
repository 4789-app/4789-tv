# 4789 TV 0.2.6

## 0.2.6

October 29, 2026 · Downloader **5873252** · package `com.fourseveneightnine.tv` · version code 50.

### New and fixed

- **Collections → TorBox Library** loads every account page and sorts newest additions first. It works with the TV's saved TorBox key after the phone disconnects.
- Ready single-file items play their exact torrent/file directly. Multi-file or unclear episode packs show the actual provider filenames and IDs. Saved ambiguous sources never select the largest video automatically.
- Uncached releases show **Not ready**. Cache inspection does not add or acquire them. Add the release yourself, then refresh.
- File-specific history and Retry retain the same hash/file after restart or link expiry. A season-zero special no longer rejects the complete paired catalog.
- Moving focus through channels automatically changes the one live preview. OK expands it full screen. Repeated navigation cancels earlier loading work rather than queuing it.
- Highlighting an unlocked group selects it. Right enters a permanently composed channel-list target. The preview keeps groups, channels and video visible together. Locked groups still require unlocking.
- Stremio QR import uses its own modal window. Add & update, Replace and Cancel keep focus inside that window instead of Settings behind it.
- Matching orange/green **4789 TV** launcher icons, adaptive/round icon support, five legacy densities and 320×180 / 640×360 TV banners.
- Includes all saved TV work from this session: 0.2.5 exact Library/TamilMV sync and natural-ratio landscape geometry, 0.2.4 IPTV controls/preview, and earlier Favorites/Watchlist and browsing fixes.

### Update

Enter **5873252** in Downloader or open [4789library.com/tv](https://4789library.com/tv), then choose **Update**. Do not uninstall or clear storage. For prepared TamilMV/phone Library references, update the matching iPhone IPA too and open it on the same Wi-Fi. The native TorBox Library reads the account directly with the saved key.

### Verification and limits

Release build/lint and Android foundation checks passed: 704 TV unit tests per variant, 224 shared-data, 74 phone and 10 contract tests. APK version, compatibility certificate, non-debuggable status and 16 KiB alignment passed. All 12 native library entries match 0.2.5. The iPhone transfer/installer checks pass on the two iPhone destinations; the final IPA uses the repository's mandatory iCloud pipeline.

The user requested release for their own Onn 4K Pro test. No new Android simulator or physical TV focus, playback, launcher-mask, HDR or audio acceptance is claimed. TorBox readiness and network speed determine start time. The current landscape geometry is included; foreground overlap, bright-art readability and the wider flagship Markdown audit remain incomplete.

APK: 29,048,759 bytes. SHA-256: `d7d2a2043ecd3faeeb67b3fb6cd7b3fd7a1adecbb40e9033ab5db706e9c3e63e`.

[APK](https://github.com/4789-app/4789-tv/releases/download/v0.2.6/4789tv.apk) · [Source](https://github.com/4789-app/4789-tv/releases/download/v0.2.6/first-party-source.tar.gz) · [Checksums](https://github.com/4789-app/4789-tv/releases/download/v0.2.6/SHA256SUMS.txt)
