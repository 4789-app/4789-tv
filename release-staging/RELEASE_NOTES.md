# 4789 TV 0.1.42 — compatibility update (code 43)

Sideload receiver for Android TV, Google TV, NVIDIA Shield, and Android-based Fire OS 7+.

| | |
|---|---|
| **Package** | `com.fourseveneightnine.tv` |
| **Minimum** | Android 9 / API 28 |
| **Channel** | Sideload only — not a Play Store release |

This release also delivers the **sanitized `first-party-source.tar.gz`** promised in the
0.1.41 notes. The 0.1.41 archive was withdrawn for carrying owner-specific values; this one
was exported from an audited commit and re-scanned before publication.

---

## What is new

**The remote no longer stops working.** Pressing down while a shelf was still loading could
leave the app with nothing selected — no direction, no select, not even back. Fixed.

**Shelves appear about five seconds sooner.** The receiver used to decode its whole saved
library before drawing anything. It now reads a small head copy of the visible shelf first,
measured on hardware at 6.9 s → 1.5 s to first paint.

**The artwork keeps up with you.** Moving along a row used to leave the big title,
description and backdrop stuck on the first poster.

**Wide artwork for every title that has it.** Rows that carry only a portrait poster now
fetch the real landscape art, so the top of the screen stops showing a stretched poster.

**Scores on the title page.** IMDb, TMDB, Trakt and others where they exist.

**Shelves appear as they arrive** instead of waiting for the slowest one, a held remote
button is paced, posters are remembered between sessions, artwork is drawn at full colour
depth, and small print over artwork is readable again.

**Fixes.** Settings sent from the phone take effect immediately. Opening a series twice no
longer re-asks every add-on for the episode list. One slow add-on no longer holds up the
rest.

---

## Download verification

**`4789tv.apk`** — 83,710,915 bytes

```
APK SHA-256      4fbe30d1789d423dd66d455a9a8015e8be2e5f627988fd65d32e6264032f5847
Signer cert      fe937c685e7cd2c010af76a23c6ea4be0114b0e4146b62cc917dcd19b2d43205
```

The signer certificate matches the published v0.1.34 and v0.1.41 APKs, so this update
installs in place. No data-clearing migration is required.

> [!IMPORTANT]
> That certificate is an **Android Debug certificate**, retained only for install
> compatibility. It is not a production signing identity, a SourceStamp, or a build
> attestation. Do not read it as one.

---

## Verified scope

**Passed**

- Clean Android foundation gate: 10 contract, 74 phone, 400 receiver, and 400 Play TV unit
  tests. Zero lint errors.
- The exact APK installed and exercised on an onn 4K Pro (Android 14): D-pad navigation
  through a loading shelf, cold-start shelf paint, hero follow, artwork, and scores.
- Frame timing on the same device: sweeping a rail measured 1.1% janky frames at 20 ms.
- No personal media was selected or recorded.

**Not covered**

- HDR and Dolby Vision, surround-sound equipment, codecs other than H.264/AAC, and device
  models other than the one tested above.
- Byte-identical native rebuilding is **not** claimed. `native-corresponding-source.tar.gz`
  is identical to the 0.1.41 archive; every Gradle and dependency input is unchanged between
  the two releases, only first-party Kotlin changed.

---

## Install and update

1. Open `https://4789library.com/player` in Downloader or a TV browser.
2. Download and install `4789tv.apk` from this release.
3. Allow installation for the app that opened the file.
4. Keep 4789 TV open, and connect iPhone on the same trusted local network.
5. Allow iPhone Local Network access when prompted.

Full steps for Android TV, Google TV, Shield, and Fire OS are in `4789TV/INSTALL.md`, which
ships in the source archive and in this repository.

> [!CAUTION]
> Do not uninstall to resolve an unexpected signature error. Uninstalling clears receiver
> data.
