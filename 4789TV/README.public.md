# 4789 TV

A standalone video and IPTV app for Android TV, Google TV, NVIDIA Shield and Android-based Fire TV.
Browse with the TV remote, use your own add-ons and IPTV accounts, or cast from 4789 on iPhone.

## Install or update

**Current release: [0.2.7](https://github.com/4789-app/4789-tv/releases/tag/v0.2.7)** · October 30, 2026

Open Downloader and enter **5873252**, or visit [4789library.com/tv](https://4789library.com/tv).
Open the APK and choose **Update** over the existing app. **Do not uninstall or clear storage.**
The same app ID and signing key are retained, and the existing library schema and settings stores
are unchanged. No simulator playback or physical-TV acceptance was run for this release. Device checks remain pending.

[Installation guide](4789TV/INSTALL.md) · [What changed](CHANGELOG.md) · [Download APK](https://github.com/4789-app/4789-tv/releases/download/v0.2.7/4789tv.apk)

## Clearer browsing and playback

Home now shows Play/Resume and Details. Browse catalogs retains its directory, query and selected
catalog when you press Back. Main destinations return Home; Back on Home exits after local panels close.

Home and Detail keep full-width, top-aligned landscape artwork at its natural ratio. Bounded text,
logos and synopsis protect readability. Movies and series have clearer facts, ratings and actions.
Series details retain season and episode context. Sources has structured technical information,
useful cautions and retained filters. Player controls expose Audio, Subtitles and Sources directly.

White outlines show remote focus. Orange identifies primary actions and saved choices. Green marks
positive facts such as Cached. Search, Calendar, Collections, Settings and setup use the same rules.

## IPTV controls in 0.2.7

Right/Left switch Next/Previous during full-screen live video. Hold to move through names and
numbers; release to play the final selection. Back opens the current category with its channel row
visible and focused. Move through channel rows for automatic live preview. Press OK for full screen. Highlight a group to select it, then press Right to enter its channels.

The preview shares the existing video/audio session. Category and displayed-list order are kept.
[Full changes and verification limits](CHANGELOG.md#027).

## Library and TamilMV in 0.2.7

Embedded addon video links keep their exact playback identity. The matching iPhone app can sync
Library torrent/file references and prepared TamilMV sources after pairing. Open Collections →
TamilMV Collection or Library. Imported source references survive phone disconnection and TV restart;
the TV uses the synced TorBox account. A missing saved file reports an error rather than choosing
another release. Update the iPhone app too, then open it on the same Wi-Fi to send the new snapshot.

Home and Detail now use full-width landscape artwork at its natural ratio, anchored at the top.
The current refinement adds protected text regions and bounded logos. Device artwork and readability acceptance remains pending.

## Account Library, focus and TV branding

**Collections → TorBox Library** reads the whole TorBox torrent account, newest additions first.
A ready single file plays directly; multi-file packs show their actual files. Uncached items show
**Not ready** and are not acquired automatically. The paired phone-added Library shelf remains
separate. The TV can use its own synced key after the phone disconnects.

Groups, channels and live preview remain visible together. Moving channel focus tunes the preview;
OK expands it. Stremio QR import keeps focus in its own window for Add & update or Replace.
Launcher icons and TV banners now use the phone-style orange/green 4789 mark with a TV label.

## What the app does

- Home, Discover, Detail and Sources with large artwork and TV remote focus.
- TV-local add-ons and encrypted settings, with optional phone casting.
- Standalone IPTV accounts, saved catalogs, Favorites, Recents and typed search.
- Live guide over video, Now/Next information and Last channel.
- Movies and series, episode sorting, refreshed video links and saved playback position.
- Audio, subtitles, subtitle fonts, picture modes and player controls.
- Single-player live preview, plus Multiview, recording and catch-up where provider and device support permit them.

4789 TV does not supply an IPTV subscription, channels, films or add-on services.

## Supported devices and limits

Requires Android 9 / API 28 or later, or Android-based Fire OS 7 or later. It does not run on Vega OS,
Apple TV, Roku, Samsung Tizen or LG webOS. Codec, HDR and audio support depend on your TV and source.

Both optimized Release variants were compiled and packaged. No app tests, full lint gate, benchmarks,
simulator runs or physical-device acceptance were run for 0.2.7, at the owner's request. Visual,
remote, upgrade and performance acceptance remain pending on Onn/Google TV and Fire TV.
Saved title lists are local to each TV profile. Stalker catch-up is unsupported.
Read the [release limits](CHANGELOG.md#verification-and-known-limits).

## Source code and build

The app is written in Kotlin with Jetpack Compose for TV. Playback uses Media3 ExoPlayer and FFmpeg
audio fallback. Room stores the library; Android Keystore protects saved settings and IPTV accounts.
The phone-cast control interface uses Kodi JSON-RPC with project-specific extensions.

| Module | Purpose |
| --- | --- |
| `4789TV/app` | TV interface, player, IPTV and receiver |
| `4789TV/client-data` | Catalogs, metadata, artwork, settings and library data |
| `4789TV/contract` | Shared data contracts and fixture checks |
| `4789TV/phone` | Separate Android phone companion |
| `4789TV/baselineprofile` | Developer profiling tools; not a user app |

Use JDK 17 and Android SDK 36. From `4789TV/`, run:

```bash
./scripts/check-android-foundation.sh --clean
./gradlew :app:assembleSideloadRelease
```

[Developer guide](4789TV/README.md) · [Release procedure](4789TV/docs/PUBLIC_RELEASE.md)

## Verification, security and licenses

Release 0.2.7 provides an optimized, non-debuggable APK, first-party source, native source package, source provenance and
checksums. The APK is 29,144,838 bytes. A matching checksum verifies bytes; it does not prove every
runtime behavior or a reproducible native build.

```text
c3f885dfb4dd1f3cc0f44954fa866e3e7196d0e8fb6c7a9ce6ff712feda56b78
```

The existing compatibility certificate has the Android Debug subject. Local-network receiver
ports are unauthenticated. HTTP IPTV services use their configured transport. Use a trusted home
network and read [TRUST.md](4789TV/TRUST.md) and [SECURITY.md](4789TV/SECURITY.md).

See [LICENSE](4789TV/LICENSE) and [NOTICE.md](4789TV/NOTICE.md) for source and dependency terms.

## Help

[Installation and updates](4789TV/INSTALL.md) · [Release history](CHANGELOG.md) · [Report an issue](https://github.com/4789-app/4789-tv/issues)
