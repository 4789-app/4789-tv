# Why you might trust this, and how to check instead

The canonical source and releases live at [4789-app/4789-tv](https://github.com/4789-app/4789-tv). You are being asked to
sideload an APK from a stranger onto a television that sits on your home network. You should be
suspicious. Reputation is not evidence, and a badge in a README is not evidence either — nobody
audits a badge.

So this page does not ask you to trust anything. It lists what the software actually does, and
gives you the commands to check each claim yourself.

## 1. Verify the APK came from this source

The current [v0.2.4 APK](https://github.com/4789-app/4789-tv/releases/download/v0.2.4/4789tv.apk) contains
28,096,227 bytes. Its SHA-256 is `2cd6ce4cbdf048aa1dd82b20cb3c0c5d7232750d23ffe98a9a63528b5b6a928b`.

```bash
shasum -a 256 4789tv.apk
```

A matching hash proves byte integrity. `SOURCE_PROVENANCE.json` binds the committed application
build inputs to the public source tag. It does not provide a signed build attestation or prove a
byte-identical rebuild. The native source offer preserves the pinned upstream recipes and source
archives. The 12 native library entries match 0.2.2; no native rebuild was performed for 0.2.4.

The compatibility signing certificate SHA-256 is
`fe937c685e7cd2c010af76a23c6ea4be0114b0e4146b62cc917dcd19b2d43205`.
It preserves in-place update compatibility. Its subject remains Android Debug. The APK itself is
an optimized Release build with debugging disabled. This certificate is not proof of author identity.

## 2. What it can do to your network

The receiver opens **two listening ports on your television**: HTTP `8791` and WebSocket `9791`.

**There is no authentication on those ports.** Anything on the same network can drive playback.
This is a deliberate design decision, not an oversight — the wire protocol is Kodi JSON-RPC, so
ordinary Kodi remotes keep working — but it means the receiver is only appropriate on a network
you control. Do not run it on hotel, café, dormitory, or corporate guest Wi-Fi.

Check the claim yourself, from another machine on your network:

```bash
nmap -p 8791,9791 <your-tv-ip>
```

## 3. What it sends, and where

**There is no analytics, telemetry, crash reporting, or advertising SDK.** No Firebase, no
Crashlytics, no Sentry, no Amplitude. Verify by reading
[`gradle/libs.versions.toml`](gradle/libs.versions.toml) — every dependency is listed there, and
the full resolved runtime dependency list must be reviewed for the specific build.

The receiver fetches media, artwork, subtitles, and configured catalog/addon data as those
features are used. When the user configures a Jev API key, category names and sample titles are
sent to TypeSafe over HTTPS for classification; this service is separate from media providers. The destination set depends on the user's configuration and selected source.
It also downloads backup-player APKs from their developers when the user requests installation.
Do not interpret the backup-player host list as a complete network-egress inventory.

Review `gradle/libs.versions.toml`, the receiver source, and the effective network traffic for
the exact version being installed. Never expose its unauthenticated receiver ports to the internet.

## 4. Why it asks for each permission

Android permissions are the first thing worth reading, and two of these look alarming in isolation.
Both are explained honestly here rather than buried.

| Permission | Why it is needed |
|---|---|
| `INTERNET` | Fetch the video you cast, and download a player when you ask for one |
| `ACCESS_WIFI_STATE` / `ACCESS_NETWORK_STATE` | Report the box's address so the phone can find it |
| `CHANGE_WIFI_MULTICAST_STATE` | Bonjour discovery — how the phone finds the TV without you typing an address |
| `RECEIVE_BOOT_COMPLETED` | Be ready to receive a cast after the TV restarts |
| `MODIFY_AUDIO_SETTINGS` | Audio focus, so playback pauses correctly for other apps |
| `REQUEST_INSTALL_PACKAGES` | **The alarming one.** The app can install other APKs — used only by the "Install Player" button, at `MainActivity.kt:2871`. It downloads the player you chose and hands it to Android's installer, which still asks for your confirmation. It is never invoked on its own. |

`android:usesCleartextTraffic="true"` is also set, and it is a real weakening of the default. It
exists because the receiver plays from **plain-HTTP sources on your own network** — a local media
server, or the phone's byte bridge on `127.0.0.1`. Traffic to those is not encrypted. Remote transport depends on the selected source URL; a remote HTTP source is also unencrypted.

## 5. What the source cannot tell you

Being honest about the limits of the above:

- **Releases are debug-signed.** A debug signature is a placeholder, not an identity. It does not
  prove who built the APK. A matching certificate can establish signing-key continuity even when its name says Android Debug;
  it is not proof of author identity or source provenance. The current compatibility candidate preserves the established certificate. A future production
  key would require migration from historical debug installs.
- **Reproducible builds are not implemented.** You cannot yet rebuild the APK yourself and confirm
  it is byte-identical to the published one. No verified provenance mechanism currently covers that gap.
- **The dependencies are not audited by anyone.** The current receiver embeds NextLib/FFmpeg 6 audio libraries and AndroidX native graphics libraries.
  This release excludes the disabled mpv native engine. Upstream source identification and binary
  mapping are documented; they are not a complete dependency security audit.

## 6. If you find something

[`SECURITY.md`](SECURITY.md) explains where to report it. Please do not open a public issue for a
vulnerability.

See [PUBLIC_RELEASE.md](docs/PUBLIC_RELEASE.md) for current signing/migration gates and
[NATIVE_SOURCE_AUDIT.md](docs/NATIVE_SOURCE_AUDIT.md) for the actual GPL-enabled native closure.
