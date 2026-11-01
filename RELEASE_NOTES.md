# 4789 TV 0.2.9

November 1, 2026 · Android TV, Google TV and Android-based Fire TV · version code 53

Keep the cinematic interface from 0.2.7 while strengthening playback ownership, saved-source identity and private setup cleanup.

### Improved

- Playback, retry, seek, recording and live-preview jobs check that they still own the current session before applying late results.
- Provider input, receiver requests, artwork decode and cached metadata have explicit bounds. Repeated catalog revision checks retain only small replay metadata.
- Full player-menu labels remain available for reading and accessibility. Episode dates include the year. Same-named catalog providers retain distinct stable keys.

### Fixed

- Catalog contents and their authenticated replay counter share one atomic save. Settings, catalog and disconnect actions use the same effective replay floor.
- Setup removal waits for private cleanup. Failed cleanup reports an error and blocks replacement setup, including direct Stremio import.
- Trusted phone sync v2 authenticates receiver identity and revision. Changed revisions and old v1 messages are rejected. Existing paired keys remain usable.
- Source cache classification respects negative markers, exact file indices and episode boundaries. A known missing episode cannot silently choose a different file.
- Late playback, guide, subtitle, profile and provider results cannot replace newer owned state. Cancelled subtitle and recording operations cancel their network calls.
- Live recording details observe updated channel state. Existing navigation, focus, artwork and source-display refinements remain included.

### Update instructions

Enter **5873252** in Downloader, or open [4789library.com/tv](https://4789library.com/tv). Choose **Update** over the installed app. **Do not uninstall or clear storage.** Update the matching iPhone app too before sending settings or catalogs; both ends require sync v2. Existing saved trust keys remain valid. Revoked trust or a legacy replay counter that prevents new sync may require pairing again. Keep TV storage.

### Known limitations

Physical Onn/Google TV and Fire TV acceptance remains owner testing. Visual quality, remote behavior, upgrades, provider playback and measured smoothness are not established by source review or automated checks. Stalker catch-up remains unsupported. The Play-flavor local build is not a Play Store submission.

The compatibility certificate retains the Android Debug subject. Local-network playback-control ports remain unauthenticated; trusted settings/catalog sync is a separate authenticated protocol. See [TRUST.md](TRUST.md).

### Verification

The full Android foundation gate passed: contract 10 tests, Android phone 74, and each TV Debug variant 707, all with zero failures. All three TV Debug lint gates and manifest, installer-boundary, native-library and 16 KiB checks passed. Shared-data Debug and Release each passed 224 tests; TV Sideload Release passed 707 tests and its full lint gate. All reported tests had zero failures. Final packaging results and exact checksums are recorded in the versioned release assets.

### Downloads

[APK](https://github.com/4789-app/4789-tv/releases/download/v0.2.9/4789tv.apk) · [First-party source](https://github.com/4789-app/4789-tv/releases/download/v0.2.9/first-party-source.tar.gz) · [Native source](https://github.com/4789-app/4789-tv/releases/download/v0.2.9/native-corresponding-source.tar.gz) · [Provenance](https://github.com/4789-app/4789-tv/releases/download/v0.2.9/SOURCE_PROVENANCE.json) · [Checksums](https://github.com/4789-app/4789-tv/releases/download/v0.2.9/SHA256SUMS.txt)

Optimized Sideload, Google TV and local Play Release builds passed (6m6s,236 tasks). APK identity, compatibility certificate, non-debuggable state, archive integrity and 16 KiB alignment passed. All 12 native libraries match 0.2.7; the matching native source archive is retained.

The public APK is **29,193,990 bytes**. SHA-256:

```text
c09febd1c85dc38837b5ca712e8ff1fa1fb9949d8c0f062c059f5e82ec468804
```

This is an owner-test compatibility shipment. No physical-TV acceptance or measured TV smoothness claim is made. Update both TV and the matching iPhone app for sync v2.
