<div align="center">

<img src="assets/logo-mark.svg" width="96" height="96" alt="">

# 4789 TV

**A focused receiver for Android TV, Google TV, Fire TV, and NVIDIA Shield.**

[![build](https://github.com/4789-app/4789-tv/actions/workflows/build.yml/badge.svg)](https://github.com/4789-app/4789-tv/actions/workflows/build.yml)
[![secret scan](https://github.com/4789-app/4789-tv/actions/workflows/secret-scan.yml/badge.svg)](https://github.com/4789-app/4789-tv/actions/workflows/secret-scan.yml)
[![license: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-3f9d64)](LICENSE)

[Install](#install) · [Trust](TRUST.md) · [Security](SECURITY.md) · [Build](#build) · [Contribute](CONTRIBUTING.md)

</div>

4789 TV runs on the television and receives a playback request from 4789 on iPhone or another
compatible Kodi JSON-RPC controller. The television plays the media; the phone can remain the place
to choose a title, control playback, and follow progress.

Sideloaded only. It is not distributed through a television app store.

## Install

The current public compatibility release is **v0.1.34**.

On the television, open Downloader or a browser and use:

```text
https://github.com/4789-app/4789-tv/releases/latest/download/4789tv.apk
```

Allow installation from that app when Android asks, install 4789 TV, then open it and leave the
receiver screen visible. Keep the television and iPhone on the same local network.

The immutable v0.1.34 APK is 59,236,985 bytes with SHA-256:

```text
bf50c58203f8dbf325085a31fb5475f59b51a9c020984dde86b3ccfac2cdd86b
```

Verify downloaded bytes before installing. The release was created by GitHub Actions and includes
its checksum and provenance information:

```bash
gh attestation verify 4789tv.apk --repo 4789-app/4789-tv
```

See the [visual installation guide](https://4789library.com/player) and [trust model](TRUST.md).

## What the compatibility release does

- Receives playback over the local network and reports position, duration, speed, volume, and
  buffering state.
- Supports television-remote play/pause, ten-second seeking, and stop without closing the receiver.
- Exposes audio and subtitle tracks to compatible controllers.
- Uses Media3/FFmpeg with a libmpv compatibility path for formats the primary engine cannot play.
- Applies color-safe fallback behavior for HDR and Dolby Vision profiles when the display path
  cannot present them natively.
- Can hand an unsupported stream to an installed external television player where that player
  accepts the format.

Capabilities depend on the television, Android/Fire OS version, decoder, HDMI/audio route, and the
media itself. The project does not claim universal format, HDR, or lossless-audio support.

## How it fits together

| Stage | Behavior |
| --- | --- |
| Discovery | Advertises a Kodi-compatible receiver over the local network; a bounded direct probe can help when multicast discovery is suppressed |
| Control | Accepts Kodi JSON-RPC plus namespaced 4789 extensions |
| Transport | HTTP on `8791`; WebSocket on `9791` |
| Playback | Media3/NextLib FFmpeg with the documented compatibility engine policy |

The non-default ports avoid common Fire OS and Kodi service collisions. See [DECISIONS.md](DECISIONS.md)
for the history.

## Hardware boundary

The compatibility floor is a 2022 Insignia Fire TV (`AFTDCT31`) running Fire OS 7. The project is
also exercised on newer Android/Google TV hardware, but device-specific codec, audio, and HDR
behavior still requires physical verification. See [HARDWARE_COMPATIBILITY.md](HARDWARE_COMPATIBILITY.md).

## Build

Use JDK 17:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew clean testDebugUnitTest lintDebug assembleDebug
```

The current repository reflects the public v0.1.34 source history. The next v0.1.41 release remains
in preparation and is not represented by the current Latest download until its exact source,
signing, GPL dependency-source, and physical-device gates pass.

## License

4789 TV is licensed under GPL-3.0 because the distributed receiver links GPL-covered playback
components. [NOTICE.md](NOTICE.md) identifies the dependency and font licenses. A release must make
the complete corresponding source for its exact APK available with the same immutable tag.

## Documentation

- [TRUST.md](TRUST.md) — release integrity, network behavior, and permissions
- [SECURITY.md](SECURITY.md) — private vulnerability reporting
- [CONTRIBUTING.md](CONTRIBUTING.md) — build and contribution requirements
- [CONTEXT.md](CONTEXT.md) — receiver vocabulary and boundaries
- [DECISIONS.md](DECISIONS.md) — significant compatibility decisions
- [HARDWARE_COMPATIBILITY.md](HARDWARE_COMPATIBILITY.md) — verified device behavior
- [docs/RELEASE_HISTORY.md](docs/RELEASE_HISTORY.md) — release history

4789 supplies receiver software, not media, streams, credentials, or a content catalog. Users are
responsible for the media and services they choose to use.
