# 4789 TV 0.2.7

October 30, 2026 · Android TV, Google TV and Android-based Fire TV · version code 51

Find content, choose sources and control playback with clearer focus, calmer artwork and more reliable return paths.

## New

- Home shows Play or Resume beside Details. Series Play selects an available episode from the title's progress and metadata. Sources remains available.
- The main live controls show supplied Now and Next information. Subtitle settings include a short appearance example.
- Hold OK on an editable collection to reach Move and other options with an ordinary remote.

## Improved

- Full-width, top-aligned landscape artwork keeps its natural ratio. Stronger text-region shading, bounded logos and synopsis, and quieter poster fallbacks improve the Home and Detail composition.
- White outlines show remote focus. Orange marks primary actions and saved choices. Green marks positive facts such as Cached. Technical badges and ordinary controls use neutral surfaces.
- Movie and series details have clearer facts, steady rating/action areas, visible Watchlist state, episode information and remembered season, order and focus.
- Sources has aligned quality, readiness, language, audio and size information. Compatibility cautions remain visible. Filters, episode choices and return positions are retained.
- Player controls give direct access to Audio, Subtitles and Sources. Seeking shows the destination time. Opening, buffering, errors and Next Episode retain the media identity and useful recovery actions.
- Live TV has clearer guide rows, channel logos, Favorites Now/Next, recording status, source forms and Multiview sound ownership. Search results name their content type.
- Search, Calendar, Collections and Settings retain more context when returning from a child screen. Setup, saved credentials and job status use more precise text.
- Artwork reads and metadata disk storage are bounded. Rating parsing and new live-guide preparation run off the UI thread. Diagnostic batches append without rereading the full file.

## Fixed

- Browse catalogs → choose catalog → Back returns to the catalog directory with its query and selection. Choosing Home clears the nested catalog view.
- Back closes the deepest open panel first. At a main destination it returns Home; Back on Home exits.
- Changing catalog filters clears results from the previous selection. Catalog search no longer moves focus out of editing when matches return.
- Shared focus outlines draw inside their bounds, protecting all four edges in clipped lists. The historical Sources mockup also has its scroll-origin defect corrected.
- Changing a source for the same episode retains its current playhead. A different title does not inherit that position.
- Pending Home Play and Next Episode work is cancelled or rejected when superseded. Stop, leaving Player and replacement playback cannot let an obsolete next-episode result reclaim the screen.
- Metadata errors preserve known title artwork and offer Retry. Broken title logos fall back to readable text.

## Update without losing your setup

Enter **5873252** in Downloader or open [4789library.com/tv](https://4789library.com/tv). Open the APK and choose **Update** over the installed app. **Do not uninstall or clear storage.**

Package ID, compatibility signing key, library schema and saved-settings storage are retained. Earlier TorBox Library, exact saved-file playback, paired Library/TamilMV transfer and single-session live preview remain included.

## Known limitations

No app tests, full lint gate, benchmarks, simulator runs or physical-device acceptance were run for 0.2.7, at the owner's request. Visual quality, remote behavior, artwork sharpness, in-place upgrade and smoothness require the owner's Onn/Google TV and Fire TV checks. Source review and successful packaging do not establish measured performance or runtime correctness.

Artwork, guide data, source readiness, codecs, HDR and audio depend on the configured providers and device. Cast-name search uses the existing title search; it is not a guaranteed actor filmography. The subtitle appearance example does not reproduce every subtitle font. No new EPG grid engine, recording engine, decoder or provider stream was added.

## Verification and security

Both optimized Sideload Release and Google TV Release APKs were compiled and packaged. The public APK is the Sideload compatibility variant, supporting Android 9/API 28 and Android-based Fire OS 7 or later. Packaging checks cover version, signing continuity, archive integrity, source mapping and native-library continuity. These are not app tests.

APK: **29,144,838 bytes**. SHA-256:

```text
c3f885dfb4dd1f3cc0f44954fa866e3e7196d0e8fb6c7a9ce6ff712feda56b78
```

The compatibility certificate SHA-256 remains `fe937c685e7cd2c010af76a23c6ea4be0114b0e4146b62cc917dcd19b2d43205`. Its subject is Android Debug; the APK is an optimized Release build with debugging disabled. Receiver ports remain unauthenticated on the local network. See [TRUST.md](https://github.com/4789-app/4789-tv/blob/v0.2.7/4789TV/TRUST.md).

## Downloads and source code

[APK](https://github.com/4789-app/4789-tv/releases/download/v0.2.7/4789tv.apk) · [First-party source](https://github.com/4789-app/4789-tv/releases/download/v0.2.7/first-party-source.tar.gz) · [Native corresponding source](https://github.com/4789-app/4789-tv/releases/download/v0.2.7/native-corresponding-source.tar.gz) · [Provenance](https://github.com/4789-app/4789-tv/releases/download/v0.2.7/SOURCE_PROVENANCE.json) · [Checksums](https://github.com/4789-app/4789-tv/releases/download/v0.2.7/SHA256SUMS.txt) · [Report an issue](https://github.com/4789-app/4789-tv/issues)
