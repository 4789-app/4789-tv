# 4789 TV

A video receiver for Android TV, Google TV, Fire TV, and NVIDIA Shield. It runs on the
television, and something else tells it what to play.

It speaks **Kodi JSON-RPC**, so it is not locked to one companion app. Any Kodi remote or any app
that can cast to Kodi already works with it — Yatse, Kore, Stremio, Syncler, and the rest. On the
network it presents itself as a Kodi box, because on the wire it is one.

Sideloaded only. Not in any store.

## Install

On the television, open Downloader (or any browser) and go to:

```
https://github.com/4789-app/4789-tv/releases/download/v0.1.34/4789tv.apk
```

Allow installs from unknown sources when prompted. Open the app once so it claims its ports —
after that, remotes find it on their own.

**Before you install anything from a stranger, verify the exact reviewed bytes.** The iOS installer
accepts only v0.1.34 at 59,236,985 bytes with SHA-256:

```bash
bf50c58203f8dbf325085a31fb5475f59b51a9c020984dde86b3ccfac2cdd86b
```

This proves byte integrity, not build provenance. GitHub attestation verification currently returns
404 and the release-body hash contradicts the downloadable asset, so no provenance claim is made.
[TRUST.md](TRUST.md) explains what the app talks to, why it asks for each permission, and what
the source *cannot* tell you.

## What it does

- Plays what a remote sends it, and reports position, duration, speed, volume, and buffering back
- **Media3 ExoPlayer** with NextLib FFmpeg audio decoders; the candidate retains libmpv source/native dependencies, but its conflicting FFmpeg closure is not a supported fallback
- Tone-maps Dolby Vision and HDR down to colour-safe SDR, because the box often decodes what the
  panel cannot display
- Standard remote control: centre toggles play/pause, left/right seek ten seconds, Back stops
  without killing the app
- Hands a stream off to Just Player, Next Player, VLC, mpv, Kodi, or TiviMate when playback fails,
  carrying the position and subtitle across where the target supports it
- Downloads and installs those players for you, from each developer's own release channel

## How it fits together

| Stage | What happens |
|---|---|
| Discovery | Advertises `_xbmc-jsonrpc-h._tcp` over Bonjour; a direct HTTP probe also works when a router blocks multicast |
| Wire | Kodi JSON-RPC. Project-specific calls hide behind an `X4789.` prefix that older clients ignore |
| Transport | Ktor: HTTP `8791`, WebSocket `9791`. Both ports are probed at startup, so a collision is a message on screen rather than a crash |
| Playback | ExoPlayer + NextLib FFmpeg; hardware/format support varies |

Ports are `8791` and `9791` rather than Kodi's usual `8080`/`9090` because Amazon system services
hold `8080` on Fire OS, which pushes Kodi's own server onto `8090`. The receiver has moved ports
twice for this reason — [DECISIONS.md](DECISIONS.md) has the history.

## Hardware

The compatibility floor is a 2022 **Insignia Fire TV (`AFTDCT31`)** running Fire OS 7. Most of the
awkward code in the player exists because of that one box: synchronous MediaCodec instead of
async, software decode as the verified default, the Android Surface attached before libmpv
initialises. See [HARDWARE_COMPATIBILITY.md](HARDWARE_COMPATIBILITY.md) before deciding any of it
is unnecessary.

Newer hardware is not the problem. The old, cheap, vendor-patched hardware is.

## Build

JDK 17 — not 21, not 24.

```bash
cd 4789TV
./scripts/check-android-foundation.sh --clean
```

There is also a Mac-side installer in [`installer/`](installer/) that pushes builds to a TV over
ADB and can drive it from a phone. Read the security notes in [SECURITY.md](SECURITY.md) before
exposing it beyond `127.0.0.1` — it installs software on televisions, and that is exactly as
sharp as it sounds.

## Licence

**GPL-3.0**, and not by preference: the receiver links NextLib, which is GPL-3.0 and compiles
FFmpeg into the APK. Distributing a build therefore obliges offering the complete source, which
is why this repository exists. [NOTICE.md](NOTICE.md) lists every component and its terms.

## Documentation

- [TRUST.md](TRUST.md) — verify the build, the egress inventory, the permission justifications
- [SECURITY.md](SECURITY.md) — threat model and how to report a vulnerability
- [CONTRIBUTING.md](CONTRIBUTING.md) — the build gate and the rules that are not style preferences
- [CONTEXT.md](CONTEXT.md) — the vocabulary; read it before changing code
- [DECISIONS.md](DECISIONS.md) — why things are the way they are
- [docs/RELEASE_HISTORY.md](docs/RELEASE_HISTORY.md) — what changed in each build, and why

Release preparation and complete installation/migration instructions: [docs/PUBLIC_RELEASE.md](docs/PUBLIC_RELEASE.md).

Full device-specific installation and update guide: [INSTALL.md](INSTALL.md).
