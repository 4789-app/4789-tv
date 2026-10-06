# 4789 TV changelog

Each release uses the same sections: New, Improved, Fixed, Update instructions, Known limitations,
Verification and Downloads. This page describes user-visible changes. Source and build details
are linked from each release.

## 0.2.1

Released October 6, 2026 · Android TV, Google TV, NVIDIA Shield and Android-based Fire TV

This update improves TV browsing, the player and standalone IPTV. Your TV can keep its own
IPTV accounts and library without a connected phone.

### New

- **Live TV guide:** browse channels, categories, Favorites, Recents and Search over the playing video. Use Last channel to return to the previous channel.
- **Broader search:** results separate live channels, programmes, movies, series and categories.
- **Episode sorting:** choose Latest episode, Latest air date or Oldest episode. Long M3U series can include more than 2,000 episodes.
- **Stalker support:** browse movies, series, seasons and episodes, with native programme information and refreshed playback links.
- **Player options:** audio, subtitles, subtitle fonts, picture fit, restart and source information. Preview, Multiview, recording and catch-up depend on provider and device support.

### Improved

- **TV layout:** larger posters, clearer focus, wrapped source names and descriptions, and a consistent Home, Detail and Sources style.
- **Saved artwork and catalogs:** poster caching, prefetch and encrypted TV-local catalogs reduce repeated loading.
- **Category ordering:** saved priorities put Telugu, cricket and sports before Hindi, Tamil and English groups. Mixed and dubbed content can still need review.

### Fixed

- Retry refreshes IPTV movie and episode links and carries the saved playback position.
- Held seeking uses timed steps. Returning from an episode no longer resets its list position.
- Cache Clear prevents an older request from writing its result back into the cleared cache.
- A failed early refresh no longer replaces the full saved library with its startup preview.
- On-demand categories are saved to disk. Multiview cannot offer an extra slot beyond the stream limit.
- Back stops playback and returns to the previous screen. The opening button release no longer accidentally pauses playback.

### Update without losing your setup

1. Open Downloader and enter **5873252**, or visit [4789library.com/tv](https://4789library.com/tv).
2. Open the APK and choose **Update** over the installed app.
3. **Do not uninstall 4789 TV or clear its storage.**

This release keeps the existing app ID and signing key. The library database schema and encrypted
settings storage are unchanged. This supports an in-place update that retains your settings,
accounts, Favorites and watch history. If Android reports a signature mismatch, stop and contact
[support](https://github.com/4789-app/4789-tv/issues); do not uninstall to force the update.

### Known limitations

- This release passed offline tests and package checks. A physical TV upgrade, remote, layout and sustained playback test was not available.
- Stalker catch-up is not supported. Recording, catch-up, muted preview and Multiview are available only when their requirements are met.
- Category labels do not verify every title's original language. Dolby Vision, Dolby Atmos and HDR10+ brand artwork remains incomplete.
- Text clarity and smoothness on every screen still need TV measurements. No new instant-playback or frame-rate claim is made.

### Verification and security

- 687 tests passed in each TV Debug variant and the Sideload Release suite; 214 data tests passed.
- The clean Android build, signature, APK integrity and 16 KiB alignment checks passed.
- The APK secret scan found no matches. Five source-scan flags were verified as synthetic test values.
- Dependency and native advisory checks found no confirmed reachable issue in the checked candidate set. This is not a guarantee against every vulnerability.
- The compatibility signing certificate still has the Android Debug subject. The app's local-network control ports are unauthenticated, and HTTP IPTV sources use their configured transport. Use a trusted home network; see [trust and security details](https://github.com/4789-app/4789-tv/blob/main/4789TV/TRUST.md).

### Downloads and source code

- [APK](https://github.com/4789-app/4789-tv/releases/download/v0.2.1/4789tv.apk)
- [First-party source](https://github.com/4789-app/4789-tv/releases/download/v0.2.1/first-party-source.tar.gz)
- [Native source package](https://github.com/4789-app/4789-tv/releases/download/v0.2.1/native-corresponding-source.tar.gz)
- [Source provenance](https://github.com/4789-app/4789-tv/releases/download/v0.2.1/SOURCE_PROVENANCE.json)
- [Checksums](https://github.com/4789-app/4789-tv/releases/download/v0.2.1/SHA256SUMS.txt)

Package: `com.fourseveneightnine.tv` · Version code: `45` · APK: `28,047,079 bytes`

SHA-256:

```text
3d3ee2b28d110d808f300155b2a745f4b27c12a3402eb13e08000c4b99a91597
```

These notes are the maintained, user-facing summary. The original release receipt remains in the
published assets. The APK, source tag and checksummed release files have not been replaced.


## 0.2.0 — September 24, 2026

### New

- A full TV client with Search, Home, Discover, Collections, Calendar and Settings.
- Detail, Sources and Player screens open from a selected title. Phone casting remains available.

### Improved

- Filled navigation icons and tighter left-rail spacing.
- Selected poster titles appear below the focus ring.

### Update without losing your setup

Use the matching signing key and install over the existing app. Do not uninstall or clear storage.
This is a previous release; Downloader code 5873252 now serves 0.2.1.

[0.2.0 release](https://github.com/4789-app/4789-tv/releases/tag/v0.2.0)

## Earlier releases

See the original release records for their changes and verification limits:

- [0.1.42](https://github.com/4789-app/4789-tv/releases/tag/v0.1.42)
- [0.1.41](https://github.com/4789-app/4789-tv/releases/tag/v0.1.41)
- [0.1.34](https://github.com/4789-app/4789-tv/releases/tag/v0.1.34)
