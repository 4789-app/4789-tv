# 4789 TV

A standalone video and IPTV app for Android TV, Google TV, NVIDIA Shield and Android-based Fire TV.
Browse with the TV remote, use your own add-ons and IPTV accounts, or cast from 4789 on iPhone.

## Install or update

**Current release: [0.2.12](https://github.com/4789-app/4789-tv/releases/tag/v0.2.12)** · November 2, 2026

Open Downloader and enter **5873252**, or visit [4789library.com/tv](https://4789library.com/tv).
Open the APK and choose **Update** over the existing app. **Do not uninstall or clear storage.**
The same app ID and signing key are retained, and the existing library schema and settings stores
are unchanged. Named navigation flows passed on Google TV Android 14 development builds. The exact optimized APK and physical-TV acceptance remain owner checks.

[Installation guide](4789TV/INSTALL.md) · [What changed](CHANGELOG.md) · [Download APK](https://github.com/4789-app/4789-tv/releases/download/v0.2.12/4789tv.apk)

## Screen transitions in 0.2.12

Version 0.2.12 adds coordinated screen, panel and content transitions. Home and Detail preserve return state. Player controls fade around the existing video surface. The owner will test physical TV feel; no current frame-time improvement is claimed.

## Earlier navigation and playback work

Version 0.2.12 adds direct subtitle Appearance, reported source-format logos and larger IPTV poster grids. Movies default to provider Latest added, with Title A–Z available. Discover Back/Left follows Genre → Catalog → Anime → Series → Movies; OK activates the focused control. Guide group/channel return, Detail season navigation, episode-specific Sources return and player Back behavior are corrected. Physical TV acceptance remains pending. See [release notes](4789TV/RELEASE_NOTES.md).

## Earlier reliability work

Phone-to-TV settings and catalog updates use authenticated sync v2. Update both apps; existing
paired keys remain valid. Catalog contents and their replay counter save together. Setup removal
waits for private cleanup and reports failures before accepting a replacement account. Playback,
provider requests, artwork caches and recording jobs have tighter ownership and bounds.

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

Release verification and known limits are recorded in the [0.2.12 changelog](CHANGELOG.md#0212).
Visual quality, remote behavior, upgrades and measured performance still need owner acceptance
on Onn/Google TV and Fire TV.
Saved title lists are local to each TV profile. Stalker catch-up is unsupported.
Read the [release limits](CHANGELOG.md#0212).

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

Release 0.2.12 provides an optimized, non-debuggable APK, first-party source, native source package, source provenance and
checksums. The APK is 29,863,305 bytes. A matching checksum verifies bytes; it does not prove every
runtime behavior or a reproducible native build.

```text
1eec9fdecfdf8646cff7f59ee6b07478d7ce2236e598cde6926d5dff17d20ecb
```

The existing compatibility certificate has the Android Debug subject. Local-network receiver
ports are unauthenticated. HTTP IPTV services use their configured transport. Use a trusted home
network and read [TRUST.md](4789TV/TRUST.md) and [SECURITY.md](4789TV/SECURITY.md).

See [LICENSE](4789TV/LICENSE) and [NOTICE.md](4789TV/NOTICE.md) for source and dependency terms.

## Help

[Installation and updates](4789TV/INSTALL.md) · [Release history](CHANGELOG.md) · [Report an issue](https://github.com/4789-app/4789-tv/issues)
