# 4789 TV 0.2.3


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
