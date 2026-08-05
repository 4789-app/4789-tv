# Why you might trust this, and how to check instead

This project has no stars, no organisation behind it, and no reputation. You are being asked to
sideload an APK from a stranger onto a television that sits on your home network. You should be
suspicious. Reputation is not evidence, and a badge in a README is not evidence either — nobody
audits a badge.

So this page does not ask you to trust anything. It lists what the software actually does, and
gives you the commands to check each claim yourself.

## 1. Verify the APK came from this source

Releases are built by GitHub Actions from the commit named in the release, never uploaded from a
laptop. Each one carries a **build-provenance attestation** signed by GitHub's own identity, which
records the exact commit, workflow, and runner that produced the file.

```bash
gh attestation verify 4789tv.apk --repo saran-penna/8-tree-player
```

This fails if the file was modified after the build, built somewhere else, or built from different
source. It is the strongest check on this page, because it needs no trust in the author at all —
only in GitHub, whom you are already trusting by downloading from them.

The release notes also carry a SHA-256. Compare it:

```bash
shasum -a 256 4789tv.apk
```

A matching hash proves the file is intact. It does *not* prove where it came from — only the
attestation does that.

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

The receiver contacts the outside world in exactly one situation: when **you** press Install on a
backup player, it downloads that player's APK from the developer's own release channel.

| Host | When |
|---|---|
| `github.com` | You install Just Player or Next Player |
| `get.videolan.org` | You install VLC |
| `mirrors.kodi.tv` | You install Kodi |
| `tivimate.com` | You install TiviMate |

Nothing else leaves the box. Confirm it with the same search anyone can run:

```bash
grep -rhoE 'https?://[a-zA-Z0-9._-]+' app/src/main/java --include='*.kt' | sort -u
```

If that list ever grows without this table growing with it, treat it as a bug and file it.

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
server, or the phone's byte bridge on `127.0.0.1`. Traffic to those is not encrypted. Traffic to
the internet hosts in the table above is HTTPS regardless.

## 5. What the source cannot tell you

Being honest about the limits of the above:

- **Releases are debug-signed.** A debug signature is a placeholder, not an identity. It does not
  prove who built the APK — the attestation in §1 is what does that. Until releases are signed
  with a stable release key, you cannot verify that version N+1 came from the same author as N.
- **Reproducible builds are not implemented.** You cannot yet rebuild the APK yourself and confirm
  it is byte-identical to the published one. The attestation covers the gap for now.
- **The dependencies are not audited by anyone.** The receiver embeds FFmpeg and mpv, large C
  codebases that parse untrusted media. They are widely used and widely reviewed, but not by this
  project.

## 6. If you find something

[`SECURITY.md`](SECURITY.md) explains where to report it. Please do not open a public issue for a
vulnerability.
