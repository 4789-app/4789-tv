# Why you might trust this, and how to check instead

The canonical source and releases live at [4789-app/4789-tv](https://github.com/4789-app/4789-tv). You are being asked to
sideload an APK from a stranger onto a television that sits on your home network. You should be
suspicious. Reputation is not evidence, and a badge in a README is not evidence either — nobody
audits a badge.

So this page does not ask you to trust anything. It lists what the software actually does, and
gives you the commands to check each claim yourself.

## 1. Verify the APK came from this source

The reviewed v0.1.34 asset is pinned by exact byte count and SHA-256. GitHub's asset endpoint returns
59,236,985 bytes with SHA-256
`bf50c58203f8dbf325085a31fb5475f59b51a9c020984dde86b3ccfac2cdd86b`.

```bash
shasum -a 256 4789tv.apk
```

The current release has no verifiable GitHub attestation: verification returns 404, and its release
body lists a different SHA. Therefore this check establishes exact-byte integrity only. It does not
establish who built the file or which source commit produced it.

The release notes also carry a SHA-256. Compare it:

```bash
shasum -a 256 4789tv.apk
```

A matching hash proves the file is intact. Provenance remains unverified until the release metadata
and a valid attestation or stable release signature are repaired.

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
there are fewer than twenty.

The receiver fetches media, artwork, subtitles, and configured catalog/addon data as those
features are used. The destination set depends on the user's configuration and selected source.
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
- **The dependencies are not audited by anyone.** The receiver embeds FFmpeg and mpv, large C
  codebases that parse untrusted media. They are widely used and widely reviewed, but not by this
  project.

## 6. If you find something

[`SECURITY.md`](SECURITY.md) explains where to report it. Please do not open a public issue for a
vulnerability.

See [PUBLIC_RELEASE.md](docs/PUBLIC_RELEASE.md) for current signing/migration gates and
[NATIVE_SOURCE_AUDIT.md](docs/NATIVE_SOURCE_AUDIT.md) for the actual GPL-enabled native closure.
