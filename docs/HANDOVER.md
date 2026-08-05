# Handover — 4789 TV receiver, 2026-08-04

Read this, then `DECISIONS.md`, then `HARDWARE_COMPATIBILITY.md`. Branch `feat/mac-parity-p1-3`,
20 commits today, all gated green. Everything below was verified on real hardware, not inferred.

---

## What was wrong, and what actually fixed it

The owner's complaint was "casting keeps losing connection, worse than Kodi". **Device logs
proved no cast ever failed for a network reason.** Every failure was a playback failure the
phone could not see:

| Symptom | Real cause | Fix |
|---|---|---|
| "Connection lost", random stops | Receiver's errors never left the TV | `X4789.OnPlaybackError` (`50f3ee1e`, `9526656e`) |
| DTS-HD / TrueHD titles die | ExoPlayer had **no software audio decoder** | ffmpeg extension (`847fcaf9`) |
| Streams end ~10s in | Phone killed its own HTTP stream on a 2nd connection | `c393fdd4` |
| "Open player, come back → stopped" | One dropped poll counted as a failed fling | `24243f3b` |
| 4 fps slideshow after a failure | Failover sent HD/4K to a **software-only** mpv | `b8f45c09` |
| Wrong Dolby Vision colours | Box has no DV decoder; only human eyes can see it | live decoder switch (`734d4721`) |

**The root architectural insight:** Kodi runs *hardware video + software audio* — two independent
decoders. Our ExoPlayer asked MediaCodec for both, so one missing audio decoder killed the whole
title. We now do what Kodi does, using the same FFmpeg (via `nextlib-media3ext`, the prebuilt
extension Just Player ships). Proven on the box:

```
exo.decoder.video | OMX.MS.AVC.Decoder        ← hardware
exo.decoder.audio | ffmpegLavc60.3.100-dca    ← DTS in software
```

## Current state

- **Engine:** ExoPlayer everywhere (`ba561404`). media3 **1.7.1** — the old 1.5.1 pin was retired
  after cold-open proof on the box; its stated revert reason was wrong, exactly as `DECISIONS.md`
  0.1.23 suspected.
- **Audio:** hardware passthrough untouched (E-AC3 5.1 bitstreams to the AVR — confirmed live);
  DTS/DTS-HD/TrueHD decode in software when the chip can't. mpv path got `audio-channels=auto`
  (it was downmixing 5.1 → 2.0).
- **Two boxes on the LAN:** Fire TV `192.168.0.124` (AFTDCT31, 32-bit-ish, **no** DV decoder,
  **no** DTS/TrueHD) and Hisense `192.168.0.106` (Android 10, **32-bit only**, native DV, native
  DTS/DTS-HD **passthrough**). The Hisense is markedly better hardware.
- **Tools:** `installer/start.sh` (small window: IP → Check → Install, plus backup-player
  downloads) and `install-tv.sh <ip>` (one-shot CLI).

## ⚠️ The one open trap

`libmpv` (FFmpeg **n8.1**) and `nextlib-media3ext` (FFmpeg **6.0**) ship the same SONAMEs with
incompatible ABIs. Only one set can be packaged, so `app/build.gradle.kts` has a **temporary
`jniLibs pickFirst`**. Consequences:

- **The mpv engine must not be selected.** `automaticEngine` returns Exo for every brand, which
  is what protects this. Do not reintroduce an Mpv default without resolving the clash.
- Resolve it one of three ways: **(a)** drop libmpv (−24 MB; loses DV tone-mapping, which is
  useless here anyway since mpv is software-only), **(b)** product flavours (`fire` vs `standard`),
  or **(c)** rebuild the extension against FFmpeg 8.1 so one copy serves both.

**Do not delete libmpv casually.** The owner pushed back on this, correctly: mpv is a *better*
player than ExoPlayer, and our 4 fps was **our own fault** — `hwdec=no` is forced globally
because of one Fire TV's broken vendor decoder (`MpvReceiverStartupPolicy.HARDWARE_DECODE_ENABLED
= false`). On a Shield or the Hisense, mpv with hardware decode would be excellent. Worth
re-testing `hwdec=mediacodec-copy` on the box before any decision — the surface-attach ordering
has changed since that finding.

---

## Built since (gates green, NOT yet verified on the box)

### 1. Startup latency — `bufferForPlaybackMs` 2500 → 700
`ExoStartupBufferPolicy`. `minBufferMs` stays 15 s: the loader never stops, so the buffer keeps
filling behind the picture — only the black-screen wait shrinks. Rebuffer resume is 2 s (a source
that already stalled should not resume on 700 ms). **Verify on the box:** `exo.open.surface` →
`exo.firstFrameRendered` in `receiver-diagnostics.log`. Baseline to beat: **4.5 s** (1080p),
**5.9 s** (4K).

### 2. Seek coalescing — one seek per burst
`SeekCoalescingPolicy` (250 ms window) in `ExoReceiverController.submitSeek`. First seek of a burst
goes through instantly; anything inside the window folds into one deferred seek to the newest
target, and relative seeks chain off the *pending* target so ten quick ±10 s presses still add up.
The snapshot reports the pending target, so the phone's scrubber does not snap back. The on-TV bar
is a second net: it commits on key-UP, so a held D-pad is one seek, not one per repeat.
**Verify:** one `exo.audio.trackInit` per scrub, not six.

### 3. On-TV player UI
`PlayerControlsView` + `PlayerControlsPolicy` (Just Player's TV layout, built programmatically like
the rest of the Activity). Seek bar with accelerating D-pad scrub (10 s → 30 s → 60 s while held),
audio + subtitle pickers, resize cycle (fit / crop / stretch), fractional speed 0.5x–2x.
- Raised by D-pad UP/DOWN/CENTER or any play/pause/seek; auto-hides after 4.5 s; BACK dismisses the
  bar instead of stopping the film (`TvRemoteKeyPolicy(controlsVisible = true)`).
- Fractional speed rides `ReceiverController.setSpeedMultiplier` — the Kodi wire contract's integer
  `setSpeed` is untouched, because that is what the phone speaks.
- Hidden whenever the startup/error overlay owns the screen: the external-player rescue buttons must
  not compete for the D-pad.
- The old one-line text toast (`playback_controls`) is gone, replaced by this.

### 4. Waiting screen + branding
`X4789.NowPlaying` now carries `artworkURL` / `posterURL` (iOS sends the Title's backdrop, falling
back to the cast item's poster). `ArtworkLoader` fetches and subsamples it; the overlay fades it in
behind Opening / Buffering / Stopped only, never under live video. Launcher icon, banner and the
startup mark are the real 4789 logo + glowing "TV".

### 5. Smaller, still open
- Phone-side **"Open in Just Player"** button — the handoff carries position/subtitle/headers now
  (`d93785b8`), but the iOS remote has no button for it.
- Phone should use `hardwareVideoCodecs` to **stop offering DV releases** to a box without a DV
  decoder (it decodes the field already, and ignores it).
- Audio-track choice cannot travel a handoff — no external player accepts it reliably. Accepted.
- **Cast bar missing on the phone** while a stream plays — reported, unreproduced; `devicectl`
  cannot pull logs from the sideloaded app.

## Rules learned the hard way today

1. **Gradle exit codes lie through a pipe** — check `${pipestatus[1]}`, not the last command.
2. **New AppTests files need `xcodegen` on BOTH specs** before they will run.
3. The iOS test target is `FourSevenEightNineTests`, not `AppTests`.
4. **media3 delivers `STATE_IDLE` synchronously** — set the playback phase *before* `player.stop()`
   or a normal title switch reads as an unexplained death (`df9e6ddf`).
5. Reinstalling the APK **kills whatever the owner is watching**. Ask first.
6. Verify on hardware before believing a banked "known truth" — two of them were wrong today
   (the media3 pin, and mpv's supposed adequacy as a fallback).
