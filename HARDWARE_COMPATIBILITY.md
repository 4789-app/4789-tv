# 4789 TV receiver — hardware compatibility report

> Question: does this APK work on modern streaming boxes (2024–2026), and is it worth
> abandoning the 2022 Insignia Fire TV (AFTDCT31) instead of fighting its quirks?
> Short answer: **yes on both counts.** Nothing in the APK is Fire-specific; almost every
> workaround in it exists *because* of that cheap Insignia.
> Date: 2026-08-04 · Receiver 0.1.27

---

## 1. What the APK actually requires

| Requirement | Value | Consequence |
|---|---|---|
| `minSdk` | 28 (Android 9) | Installs on anything from 2018 onward. Every box below qualifies. |
| `targetSdk` | 28 | Permissive, not restrictive. Android 14/15/16 only block `targetSdk < 23–24`, so this installs. Also means no runtime notification permission, no foreground-service-type declarations, no scoped-storage rules. |
| ABIs | `arm64-v8a`, `armeabi-v7a` | Every modern box is arm64. ✅ |
| Required feature | `android.software.leanback` **required** | Installs on TV devices only — correct for a receiver, but it will refuse to install on a phone/tablet. |
| Permissions | INTERNET, WIFI_STATE, NETWORK_STATE, MULTICAST, BOOT_COMPLETED, MODIFY_AUDIO_SETTINGS | All normal-level; none needs a runtime grant at `targetSdk 28`. No install friction. |
| Networking | mDNS (`NsdManager`) + HTTP :8791 + WebSocket :9791 | Standard Android APIs; no vendor dependency. |
| Playback | ExoPlayer/media3 1.7.1 + MediaCodec + ffmpeg audio extension | Uses only public Android APIs. |

**There is no Amazon dependency anywhere in the APK.** No Fire-specific SDK, no Amazon
services, no Appstore hooks.

## 2. Every Fire-specific hack in this codebase (all caused by the AFTDCT31)

These exist solely because of the 2022 Insignia. On better hardware they are unnecessary
— currently harmless, but they cost a little performance and a lot of complexity:

| Workaround | Why it exists | On a good box |
|---|---|---|
| `forceDisableMediaCodecAsynchronousQueueing()` | AFTDCT31 vendor decoders **deadlock** in async mode | Unnecessary; async is faster |
| mpv `hwdec=no` (global) | AFTDCT31 MediaCodec wrapper deadlocks mpv before FILE_LOADED | Unnecessary; mpv hardware decode works elsewhere |
| `MpvStreamRelay` (on-box HTTP relay) | ffmpeg's DNS resolution **hangs** on this box | Unnecessary |
| 1.5s PCM audio buffer (vs 750ms default) | Underruns → clicks on this chip | Over-buffered; adds latency |
| `targetSdk = 28` | Fire OS 7 misbehaves at a higher target | Could be raised |
| Engine failover Exo→mpv | Compensating for codec gaps | Now redundant (ffmpeg extension) |

Reading that table is the argument: **we have been engineering around one cheap box.**

## 3. Verdict per device class

### Best fit for this receiver

| Box | Price | Why it suits us | Caveats |
|---|---|---|---|
| **NVIDIA Shield TV Pro** | ~$200 | Android TV (not Google TV) → the least install friction. Mature Tegra MediaCodec with no known deadlocks. Full DV + Atmos passthrough. Gigabit Ethernet, USB. The reference sideload platform. | 2019 silicon, aging; premium price |
| **onn 4K Pro (2026)** | ~$60 | Best-performing Google TV device tested short of the Shield (Amlogic S905X5M). Full DV + Atmos, 32 GB, Wi-Fi 6. Best value by a distance. | 100 Mbps Ethernet only; 3 GB RAM; Walmart-only; certified → see §4 |
| **Google TV Streamer 4K** | ~$100 | 32 GB, gigabit Ethernet, first-party update cadence | Slightly slower than the onn |
| **Homatics Box R 4K Plus** | ~$100 | 4 GB RAM, HDMI 2.1, full DV/Atmos chain — the AV-enthusiast pick | Mostly EU availability |
| **AOSP boxes (e.g. Ugoos)** | varies | Not Google-certified → immune to the verification changes in §4; maximum sideload freedom long-term | Less polished, slower updates |

### Do not build the future on Fire TV
Amazon has confirmed future Fire TV Sticks run **Vega OS**, which is not Android — the
4K Plus / 4K Max are the **last sideloadable Fire devices**. A receiver APK has no future
on that platform regardless of how well we fix the current box.

## 4. Two external risks worth knowing

1. **Google developer verification** — announced 2025, regional enforcement from
   **September 2026**, global 2027. It would require developers to verify identity before
   apps install on *certified* devices (Shield, onn, Google TV Streamer). Expectation for US
   users is extra warning prompts rather than a lockout, and Google has already softened the
   original proposal. **Non-certified AOSP boxes are unaffected** — that is the hedge.
2. **Android 16 local-network protections** — newer Android tightens local-network access.
   Our receiver is entirely local-network (mDNS + LAN HTTP), so if we ever raise `targetSdk`
   we must re-verify discovery and the byte bridge on a 16+ device.

## 5. Code blockers found while writing this report

| Issue | Status |
|---|---|
| `automaticEngine` returned **Mpv for every non-Amazon box** — and mpv cannot run in this build (FFmpeg ABI clash), so the APK would have failed on a Shield/onn/Streamer | **FIXED** (`ba561404`) — ExoPlayer is now the default on every brand |
| libmpv (FFmpeg n8.1) and the ffmpeg extension (6.0) ship identical SONAMEs; a `pickFirst` currently packages one set | **OPEN** — resolve by removing libmpv, or by build flavors, or by rebuilding the extension against 8.1 |
| Transport dies with the Activity (no foreground Service) | **OPEN** — matters more on boxes where users switch apps |

## 6. Recommendation

**Buy an onn 4K Pro (2026) at ~$60 and treat the Insignia as a compatibility floor, not the
target.** It is the cheapest way to find out how much of our remaining complexity is real
versus AFTDCT31-shaped. If it behaves, the Shield is the premium version of the same answer
and the AOSP route is the long-term sideload hedge.

Expected on that hardware, with no code changes:
- DV and HDR handled by the box's own decoder (it advertises full DV)
- Atmos/TrueHD/DTS-HD **bitstreamed** to a receiver where the chain supports it
- No engine failover, no relay, no deadlock workarounds firing

## 7. Test plan for a new box (do this before trusting it)

1. `adb connect <ip>` → `adb install -r app-debug.apk`
2. Cold open, then `X4789.GetReceiverInfo` — confirm `hardwareVideoCodecs` includes
   `dolbyvision` and `audioCodecs.passthrough` is non-empty (an empty list means the probe
   could not run and must be treated as *unknown*, never as *unsupported*).
3. Cast three streams: a 4K HEVC/DV title, a DTS-HD or TrueHD REMUX, and a plain 1080p AAC file.
4. Read `receiver-diagnostics.log` and check:
   - `exo.decoder.video` is a hardware name (`OMX.*` / `c2.*`, **not** `ff*`)
   - `exo.decoder.audio` is absent for passthrough, or `ff*` only where the box lacks a decoder
   - `exo.audio.trackInit encoding=` 5/6/7/8/14 = bitstream; 2 = decoded PCM
   - no `notify.playbackError`, no `engine.failover`
5. Measure open → `firstFrameRendered`. On the AFTDCT31 this is **4–6 s**; a modern box
   should be materially faster.
