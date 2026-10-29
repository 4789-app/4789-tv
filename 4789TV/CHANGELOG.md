# 4789 TV changelog

Each release uses the same sections: New, Improved, Fixed, Update instructions, Known limitations,
Verification and Downloads. This page describes user-visible changes. Source and build details
are linked from each release.

## 0.2.3

Released October 29, 2026 · Android TV, Google TV and Android-based Fire TV

Save titles from the remote, keep full cover images, and keep your place while browsing.

### New

- Hold the centre Select button on a Home, Discover or Search poster. Choose Favorites or Watchlist, then press Select to add or remove it. Changes save at once.
- Nonempty Favorites and Watchlist lists appear as poster rows on Home. See all opens the complete list.
- Discover's Catalog panel includes local Favorites and Watchlist choices for Movies, Series and Anime.

### Improved

- Home rows move with a short animation. Returning to a row keeps the previous poster and horizontal position.
- A later remote move replaces an older scroll. Opening the menu cancels a pending move. Reduced Motion keeps direct movement.
- Backgrounds use one bounded image request. The delayed original-size replacement and unnecessary list copying were removed.
- The guide applies saved visibility and group-order rules. Source form fields support Up and Down after the keyboard closes.

### Fixed

- Portrait and landscape artwork fit their image areas. Full source images retain their top and bottom; space may remain beside a portrait in a wide area.
- Save panels receive focus and return to the originating poster. Empty saved lists offer a recovery action.
- Returning from Home See all restores its original control, even if a saved row changed above it.
- Pending row moves use stable row identity when empty rows disappear.
- Global Search retains entered text when returning from Detail.

### Before and after

| Before | After |
| --- | --- |
| The save checklist did not offer system Favorites or Watchlist. | Both lists are reachable from the remote and appear on Home and in Catalog. |
| Some wide image areas cut off cover artwork. | The complete image fits, with margins where needed. |
| Row moves snapped and reset to the first poster. | Moves animate and keep the selected poster. |

### Update without losing your setup

1. Open Downloader and enter **5873252**, or visit [4789library.com/tv](https://4789library.com/tv).
2. Open the APK and choose **Update** over the installed app.
3. Do not uninstall 4789 TV or clear its storage.

The app ID, compatibility signing key, library schema and encrypted settings storage are retained.
Favorites and Watchlist are TV-local and separate for each profile. This update does not add phone synchronization for these lists.

### Verification and known limits

- The full clean Android foundation gate passed. Each of three Debug TV variants and Sideload Release passed 698 tests. Client data passed 215 tests; phone passed 74; contract passed 10. There were no failures, errors or skipped tests.
- The exact release APK updated the Google TV Android 14 ARM64 emulator in place. Its installed hash matched; receiver identity and saved Watchlist were retained. Held Select, saving and return focus passed on those bytes.
- Generated edge-marker images verified full covers on Home, Discover, collections, Detail and IPTV movie/series lists. All active client image paths were checked for explicit cropping.
- Generated H.264/AAC playback, pause/resume, seek, audio selection, external subtitle on/off and stop/reopen passed on the exact APK.
- The APK is optimized and non-debuggable. ZIP integrity, 16 KiB alignment and the established certificate passed. All 12 native library entries match 0.2.2; the matching native source archive is retained.
- The wider flagship Markdown audit remains in progress. Physical onn 4K Pro smoothness, overscan, hardware codecs, HDR/audio routes, providers and full accessibility remain on-device checks.
- The compatibility build retains target API 28 and the Android Debug certificate subject. Installation can show an older-Android warning. Keep Play Protect enabled; see [INSTALL.md](INSTALL.md) and [TRUST.md](TRUST.md).
- Local-network receiver control ports remain unauthenticated. Stalker catch-up remains unsupported. Recording, preview and Multiview depend on the provider and device.
- No new native rebuild or byte-for-byte reproducible-build attestation is claimed.

### Downloads and source

- [APK](https://github.com/4789-app/4789-tv/releases/download/v0.2.3/4789tv.apk)
- [First-party source](https://github.com/4789-app/4789-tv/releases/download/v0.2.3/first-party-source.tar.gz)
- [Native corresponding source](https://github.com/4789-app/4789-tv/releases/download/v0.2.3/native-corresponding-source.tar.gz)
- [Source provenance](https://github.com/4789-app/4789-tv/releases/download/v0.2.3/SOURCE_PROVENANCE.json)
- [Checksums](https://github.com/4789-app/4789-tv/releases/download/v0.2.3/SHA256SUMS.txt)

Package: `com.fourseveneightnine.tv` · Version code: `47`


## 0.2.2

Released October 28, 2026 · Android TV, Google TV and Android-based Fire TV

Find catalogs faster and keep changing channels while a stream connects. This release includes the complete current TV application and the earlier 0.2.1 features.

### New

- Search the Catalog panel by catalog or add-on name. Your query stays when you choose a result and return.
- Groups and Channels stay beside each other in the Channels screen.

### Improved

- Down from the Channels header or tab row enters the left Groups pane. Right enters Channels; Left returns to Groups.
- A selected group has a checkmark. Remote focus remains a separate visible state.
- Channel buttons accept repeated presses while a stream loads. A later choice cancels the older tuning job.
- Holding a channel button continues moving at a controlled rate. Playback settles on the last selected channel.

### Fixed

- Loading no longer blocks later channel choices or lets an older loading job clear the new state.
- Loading artwork follows the current channel.
- Approved settings imports enable browsing without restarting the app.
- An empty catalog query can enter the catalog list with Down. A query with no matches offers a reachable Clear search action.

### Update without losing your setup

1. Open Downloader and enter **5873252**, or visit [4789library.com/tv](https://4789library.com/tv).
2. Open the APK and choose **Update** over the installed app.
3. Do not uninstall 4789 TV or clear its storage.

The package ID and compatibility signing certificate are retained. No library database or settings schema was changed by these fixes.

### Verification and known limits

- The clean Android foundation gate passed. Each of the three debug TV variants and the optimized Sideload Release variant passed 689 tests. The contract passed 10 tests, phone passed 74 tests, and shared client data passed 214 tests.
- An in-place emulator update from the public 0.2.1 APK preserved the receiver identity and saved app state. Installed APK bytes matched the release file. Playback, pause/resume, seeking, audio selection, external subtitle on/off, and stop/reopen passed with generated test media.
- APK alignment, ZIP integrity and the established signing certificate were verified. All 12 native library entries are byte-identical to 0.2.1. The release build is optimized and is not debuggable.

- Catalog search, clearing, query return, Groups/Channels focus, delayed channel loading, and held input were tested in the official Google TV Android 14 ARM64 emulator.
- A 1,000-episode fixture verified existing latest/oldest sorting, held scrolling, playback and return to the same row. Episode sorting was already present in 0.2.1.
- The full flagship experience is not complete. The TV audit records 21 passes and 81 unchecked items. A physical onn 4K Pro remote, hardware codecs, HDR/audio routes, live provider compatibility, accessibility, and the remaining guide flows still need checks.
- The compatibility APK retains target API 28. Google TV can show an older-Android-version warning during installation; see the [install guide](https://github.com/4789-app/4789-tv/blob/main/4789TV/INSTALL.md). Play Protect does not need to be disabled.
- Stalker catch-up remains unsupported. Recording, catch-up, preview and Multiview depend on the provider and device.
- The compatibility certificate still has the Android Debug subject. Local-network control ports remain unauthenticated. Use a trusted home network; read [TRUST.md](https://github.com/4789-app/4789-tv/blob/main/4789TV/TRUST.md).
- Native source archives and source-to-binary mapping accompany the release. No new native rebuild or byte-for-byte reproducible-build attestation is claimed.

### Downloads and source

- [APK](https://github.com/4789-app/4789-tv/releases/download/v0.2.2/4789tv.apk)
- [First-party source](https://github.com/4789-app/4789-tv/releases/download/v0.2.2/first-party-source.tar.gz)
- [Native corresponding source](https://github.com/4789-app/4789-tv/releases/download/v0.2.2/native-corresponding-source.tar.gz)
- [Source provenance](https://github.com/4789-app/4789-tv/releases/download/v0.2.2/SOURCE_PROVENANCE.json)
- [Checksums](https://github.com/4789-app/4789-tv/releases/download/v0.2.2/SHA256SUMS.txt)

Package: `com.fourseveneightnine.tv` · Version code: `46`


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
- The optimized, non-debuggable Release APK passed the clean Android build, signature, APK integrity and 16 KiB alignment checks.
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
This is a previous release; Downloader code 5873252 serves the latest release listed above.

[0.2.0 release](https://github.com/4789-app/4789-tv/releases/tag/v0.2.0)

## Earlier releases

See the original release records for their changes and verification limits:

- [0.1.42](https://github.com/4789-app/4789-tv/releases/tag/v0.1.42)
- [0.1.41](https://github.com/4789-app/4789-tv/releases/tag/v0.1.41)
- [0.1.34](https://github.com/4789-app/4789-tv/releases/tag/v0.1.34)
