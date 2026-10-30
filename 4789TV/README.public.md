# 4789 TV

A standalone video and IPTV app for Android TV, Google TV, NVIDIA Shield and Android-based Fire TV.
Browse with the TV remote, use your own add-ons and IPTV accounts, or cast from 4789 on iPhone.

## Install or update

**Current release: [0.2.4](https://github.com/4789-app/4789-tv/releases/tag/v0.2.4)** · October 29, 2026

Open Downloader and enter **5873252**, or visit [4789library.com/tv](https://4789library.com/tv).
Open the APK and choose **Update** over the existing app. **Do not uninstall or clear storage.**
The same app ID and signing key are retained, and the existing library schema and settings stores
are unchanged. No simulator playback or physical-TV acceptance was run for this release. Device checks remain pending.

[Installation guide](4789TV/INSTALL.md) · [What changed](CHANGELOG.md) · [Download APK](https://github.com/4789-app/4789-tv/releases/download/v0.2.4/4789tv.apk)

## IPTV controls in 0.2.4

Right/Left switch Next/Previous during full-screen live video. Hold to move through names and
numbers; release to play the final selection. Back opens the current category with its channel row
visible and focused. Select a row for live preview, then choose **Watch full screen**.

The preview shares the existing video/audio session. Category and displayed-list order are kept.
[Full changes and verification limits](CHANGELOG.md#024).

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

Version0.2.4 passed clean/final Android builds, lint,700 TV unit tests per variant,215 client-data,
74 phone and10 contract tests. Source and APK-integrity checks passed. Simulator playback testing
was not run for Issue2. Physical remote timing, focus, live providers, audio and native viewport
resizing remain unverified. The wider audit and separate landscape-poster correction remain open.
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

Release 0.2.4 provides an optimized, non-debuggable APK, first-party source, native source package, source provenance and
checksums. The APK is 28,096,227 bytes. A matching checksum verifies bytes; it does not prove every
runtime behavior or a reproducible native build.

```text
2cd6ce4cbdf048aa1dd82b20cb3c0c5d7232750d23ffe98a9a63528b5b6a928b
```

The existing compatibility certificate has the Android Debug subject. Local-network receiver
ports are unauthenticated. HTTP IPTV services use their configured transport. Use a trusted home
network and read [TRUST.md](4789TV/TRUST.md) and [SECURITY.md](4789TV/SECURITY.md).

See [LICENSE](4789TV/LICENSE) and [NOTICE.md](4789TV/NOTICE.md) for source and dependency terms.

## Help

[Installation and updates](4789TV/INSTALL.md) · [Release history](CHANGELOG.md) · [Report an issue](https://github.com/4789-app/4789-tv/issues)
