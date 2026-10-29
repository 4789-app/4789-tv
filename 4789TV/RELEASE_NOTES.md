# 4789 TV 0.2.2

Released October 28, 2026 · Android TV, Google TV and Android-based Fire TV

Find catalogs faster and keep changing channels while a stream connects. This release includes the complete current TV application and the earlier 0.2.1 features.

## New

- Search the Catalog panel by catalog or add-on name. Your query stays when you choose a result and return.
- Groups and Channels stay beside each other in the Channels screen.

## Improved

- Down from the Channels header or tab row enters the left Groups pane. Right enters Channels; Left returns to Groups.
- A selected group has a checkmark. Remote focus remains a separate visible state.
- Channel buttons accept repeated presses while a stream loads. A later choice cancels the older tuning job.
- Holding a channel button continues moving at a controlled rate. Playback settles on the last selected channel.

## Fixed

- Loading no longer blocks later channel choices or lets an older loading job clear the new state.
- Loading artwork follows the current channel.
- Approved settings imports enable browsing without restarting the app.
- An empty catalog query can enter the catalog list with Down. A query with no matches offers a reachable Clear search action.

## Update without losing your setup

1. Open Downloader and enter **5873252**, or visit [4789library.com/tv](https://4789library.com/tv).
2. Open the APK and choose **Update** over the installed app.
3. Do not uninstall 4789 TV or clear its storage.

The package ID and compatibility signing certificate are retained. No library database or settings schema was changed by these fixes.

## Verification and known limits

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

## Downloads and source

- [APK](https://github.com/4789-app/4789-tv/releases/download/v0.2.2/4789tv.apk)
- [First-party source](https://github.com/4789-app/4789-tv/releases/download/v0.2.2/first-party-source.tar.gz)
- [Native corresponding source](https://github.com/4789-app/4789-tv/releases/download/v0.2.2/native-corresponding-source.tar.gz)
- [Source provenance](https://github.com/4789-app/4789-tv/releases/download/v0.2.2/SOURCE_PROVENANCE.json)
- [Checksums](https://github.com/4789-app/4789-tv/releases/download/v0.2.2/SHA256SUMS.txt)

Package: `com.fourseveneightnine.tv` · Version code: `46`

APK size: **28,047,075 bytes** · SHA-256:

```text
37a0d3a79cee03e1b0c477293b9e6409363b96956a6c2163365e71abf03c7b9f
```
