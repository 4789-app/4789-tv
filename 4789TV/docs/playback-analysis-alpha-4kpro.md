# 4789 TV Playback Analysis — Alpha on onn 4K Pro (.181)

- **Date:** 2026-08-28. Corrected against the raw log the same day.
- **Device:** onn 4K Pro (`192.0.2.181:5555`). Android 14, SDK 34.
- **App:** 4789 TV APK (`com.fourseveneightnine.tv`).
- **Title:** *Alpha* (2018). 4K HEVC, `3840x2160`, `8433.376s`, `cast=F25CC708`.
- **Log:** `files/receiver-diagnostics.log`, pulled over ADB.

---

## 1. Summary

Three things went wrong. Two of them share one cause, and the third was a false alarm from the phone.

1. **A 2.07-second cold start**, of which 1.19s was spent buffering a position nobody was going to
   watch. The receiver prepared the source at 0 and waited for `STATE_READY`. Only then did the
   phone's seek to 25:44 arrive, and it flushed everything the receiver had just loaded.
2. **A full stop and re-cast 0.5 seconds after the first frame.** The phone's stall watchdog
   decided a healthy 4K stream was dead. It was not; the watchdog was measuring the wrong thing.
3. **A black flash 6.3 seconds into the film**, when auto frame rate matching finally learned the
   content was 24fps and re-linked HDMI. The rate should have been known during startup; the first
   measuring window was poisoned by the same prepare-at-0-then-seek behaviour as (1).

An earlier draft of this document blamed the slow start on the network. That was wrong — see §4.

---

## 2. Empirical log timeline (`cast=F25CC708`)

```text
13:12:43.695 | exo.prepare      | cast=F25CC708 stage=resolving      <-- cast requested
13:13:01.862 | exo.prepare      | cast=F25CC708 stage=preparing      <-- 18.2s spent resolving
13:13:02.126 | exo.build        | queueing=asynchronous targetBufferBytes=134217728 sdk=34
13:13:02.130 | exo.open.begin   | cast=F25CC708 generation=1
13:13:02.141 | exo.stateChanged | state=2 (Buffering) posMs=0
13:13:02.459 | exo.bandwidth    | estimateBps=7385407 sampleBytes=5928 sampleMs=10   <-- seed sample
13:13:03.321 | exo.seek.submit  | targetMs=1544457                   <-- 1.19s buffering position 0
13:13:03.334 | exo.seek.discontinuity | oldMs=0 newMs=1544457        <-- and throwing it away
13:13:03.348 | exo.decoder.video| c2.amlogic.hevc.decoder generation=1
13:13:03.455 | exo.audio.trackInit | encoding=6 channelMask=252 bufferSize=1152000
13:13:04.092 | exo.audio.trackInit | encoding=6 channelMask=252 bufferSize=1152000   <-- second build
13:13:04.200 | exo.firstFrameRendered | generation=1                 <-- 2.07s after open.begin
13:13:04.482 | exo.stateChanged | state=3 (Playing) posMs=1544480
13:13:04.699 | exo.stateChanged | state=1 (Idle)                     <-- phone sent Player.Stop
13:13:04.854 | exo.videoSize    | 0x0 par=1.0                        <-- surface cleared: black
13:13:05.117 | overlay.rescued  | phase=Paused startup=Ready         <-- phase race, see §5
13:13:05.259 | exo.open.begin   | cast=F25CC708 generation=2
13:13:06.122 | exo.seek.discontinuity | oldMs=0 newMs=1542457        <-- same waste, 2s earlier
13:13:06.340 | exo.firstFrameRendered | generation=2
13:13:10.691 | exo.frameRate.unmatched | measured=0.030              <-- window spanned the seek
13:13:12.687 | exo.frameRate.measured  | measured=24.004 snapped=24.000  <-- HDMI re-link, mid-film
13:13:41.723 | exo.bandwidth    | estimateBps=35289880
13:13:51.745 | exo.bandwidth    | estimateBps=99852696
```

There is **no** `exo.idle.unexplained` record at 13:13:04.699. That matters: `stop()` sets the phase
to `Stopped` before `p.stop()` precisely so the idle handler stays quiet. Its absence is the proof
that the phone asked for the stop — the receiver did not die.

---

## 3. Root cause A — prepare at 0, then seek

`OpenMediaRequest` had no start position, so the receiver always prepared at 0 and waited for the
phone's `Player.Seek` to arrive afterwards.

Cost, measured on this session:

| Leg | Generation 1 | Generation 2 |
|---|---|---|
| `open.begin` → seek arrives (buffering position 0, all discarded) | 1.19s | 0.85s |
| seek → first frame (re-fetch at the real position, decoder init) | 0.87s | 0.23s |
| **total to first frame** | **2.07s** | **1.08s** |

It is not only slow. Media3 renders a frame from position 0 on the way past, which is a flash of the
wrong scene *and* the thing that poisons the frame-rate measuring window (§6).

**Fix:** `X4789.NowPlaying` now carries an optional `resumeSeconds`, `OpenMediaRequest` carries
`startPositionMs`, and `open()` calls `setMediaSource(source, startPositionMs)`. The phone still
sends its follow-up seek — a receiver too old to know the key needs it — and a current receiver
recognises it as the position it already opened at and skips the flush (`exo.seek.redundant`).

---

## 4. What was NOT the cause: the network

The earlier draft read `estimateBps=7385407` as the link being throttled to 7.38 Mbps. It is not a
measurement of the link. It is the bandwidth meter's estimate after **one 5,928-byte sample over
10ms** — the first bytes off the socket. The same session later reported 35.3 Mbps and 99.9 Mbps on
the same source. The network was never the constraint.

Two other readings in that draft were also off:

- `channels=252` is not a channel count. It is an `AudioFormat` channel **mask** (`0xFC` = 5.1). The
  log field has been renamed `channelMask=` so nobody reads it as "252 channels" again.
- The mode switch on this box does **not** go through `preferredDisplayModeId`. That path is for
  API < 30 (Fire OS 7, Hisense). The onn is SDK 34, so it uses `Surface.setFrameRate` with
  `CHANGE_FRAME_RATE_ALWAYS`.

---

## 5. Root cause B — the phone stopped a healthy stream

The re-cast at 13:13:05 was `KodiCastSession.observeRescueProgress()` firing its
`castSameSourceRetried` path: stop, re-open, resume two seconds earlier (`position - 2`, which is
exactly `1544457 - 2000 = 1542457`).

It was a false positive, for two independent reasons:

1. **The stall clock ran through startup.** `lastRescueAdvanceAt` was seeded when the cast was
   requested (13:12:43.7), and `observeRescueProgress()` is only reached once the receiver has a
   player. The 18.2 seconds the receiver spent *resolving the source* therefore counted against an
   18-second stall budget. The budget was already spent before the first frame existed.
2. **The baseline was the requested position, not an observed one.** `lastRescuePosition` was seeded
   from `item.startAt` (1544.457). When the receiver reported 1544.480, that is 23ms of real
   progress — below the 0.5s "advanced" threshold. A correct resume is indistinguishable from a dead
   stream under that rule.

**Fix (phone):** time with no receiver player no longer counts as a stall, and the baseline starts at
`-1` ("nothing observed yet") so the first reported position becomes the baseline rather than
evidence. The genuine mid-film stall case is unchanged and still covered by its test.

---

## 6. Root cause C — the mid-film HDMI black flash

The container states no frame rate for this file, so the receiver counts rendered frames over a
48-frame window and snaps the result. Two things went wrong:

1. **The first window spanned a discontinuity.** It included the position-0 frame from §3, so it
   averaged across a 25-minute gap and produced `measured=0.030` — discarded as unmatched. A second
   window was needed, and the rate did not arrive until **6.3 seconds after the first frame**.
2. **Nothing stopped a non-seamless switch that late.** By then the picture was up, so
   `Surface.setFrameRate(..., CHANGE_FRAME_RATE_ALWAYS)` re-linked HDMI over a scene the viewer was
   watching.

**Fix:** the measuring window now restarts at any frame more than 250ms after the previous one, so it
can never span a seek, a discontinuity, or the position-0 frame. And a non-seamless switch is only
requested within `SurfaceFrameRatePolicy.NON_SEAMLESS_GRACE_MILLIS` (4s) of the picture appearing —
48 frames at 24fps is 2.0s, so the normal path still fits. After that the panel is left alone:
judder beats blacking out a scene nobody asked to have blacked out.

---

## 7. Root cause D — the phase race that left a black screen

At 13:13:05.117 the receiver recorded `overlay.rescued | phase=Paused`. That should have been
impossible: `stop()` had just set `Stopped`.

`onIsPlayingChanged(false)` answers on a coroutine, so its `Paused` landed *after* the synchronous
stop handler. `Paused` means "a film is up, keep the picture", so the home screen was never drawn —
and the surface had already been cleared to 0x0. The `enforceSomethingOnScreen` safety net was the
only thing that put an interface back.

**Fix:** `ExoPhaseOverwritePolicy` — a late transport callback may set `Playing`/`Paused` only when
the current phase is still a live one. `Stopped`, `Ended`, `Error` and `Idle` are final answers, and
the callback is dropped for a superseded generation as well.

---

## 8. Still open

- **Nothing here helps until the phone build ships.** The receiver only learns a start position when
  the phone sends `resumeSeconds`. A receiver-only install still prepares at 0, so §3, §11 and §12
  stay exactly as they were. The stall watchdog of §5 is phone-side too.
- **18.2 seconds to resolve a source** before the receiver is asked to open anything. That was the
  largest single number in this session by a wide margin, and it is phone-side. Partly fixed — §10.
- **The double `exo.audio.trackInit`** was root cause A again, not a separate defect — §11.
- **The libmpv engine** now honours the resume position — §12.

---

## 9. Verification commands

```bash
adb connect 192.0.2.181:5555
```

```bash
adb -s 192.0.2.181:5555 shell run-as com.fourseveneightnine.tv cat files/receiver-diagnostics.log
```

```bash
adb -s 192.0.2.181:5555 logcat -v time | grep -E "ExoPlayer|com.fourseveneightnine|c2.amlogic"
```

---

## 10. The 18-second resolve

Two kinds of source reach `ExternalPlayer.resolveDirectURL`, and only one of them was ever short-
circuited. That is why direct TorBox and indexer sources feel instant while addon sources do not.

| Source | What the resolve does |
|---|---|
| TorBox / ★Mine | Mints the CDN link with a direct API call. The probe that follows is a `GET` against a link the debrid already has hot. A replay inside 5 minutes skips it entirely via `ResolveCache`. |
| Addon (aiostreams) | One opaque `GET` against a `/playback/` proxy URL. It waits for two slow things in series: the proxy unrestricting the link, then the CDN answering. No cache of any kind. |

The second wait buys nothing. The redirect **is** the minted link: once the proxy answers
`302 Location: <cdn>`, the work the probe existed to trigger is finished. Waiting for the CDN to
answer a two-byte range request adds a whole round trip to a cold edge — and it is a round trip the
receiver is about to make for itself moments later.

**Fix:** `ResolveProbeDelegate` stops the redirect chain the first time it leaves the host we asked,
and treats that target as the resolved URL. A chain with no cross-host hop — the TorBox path, or a
direct CDN link — is untouched and still waits for its `2xx`, so the fast path keeps the behaviour
it had. The probe now also logs the chain (`chain=302@412ms->cdn.example`), so the next cast says
which hop owns the remaining time instead of reporting one opaque total.

**Second fix — the replay cache.** `ResolveCache` was scoped to `torbox://` sentinels, so a resume,
an Up-Next, or a rescue of an addon source re-ran the whole resolve every time. It now caches addon
proxy URLs on the same five-minute TTL, keyed on the origin URL the app stores and re-casts from.

That was a deliberate scoping decision, changed on 2026-08-28 with the owner's agreement. The stated
risk — an addon link may be single-use, so a cached replay could hand the TV a dead link — is real,
and is handled by invalidation rather than by avoidance. A slate redirect is caught by
`isExpiredSlate`. Anything else is caught by `publishPlaybackFailure`, which now drops the entry for
the failed cast's origin so the next attempt re-mints.

---

## 11. The double `exo.audio.trackInit`

Every start built its AudioTrack twice, ~500ms apart, with byte-identical parameters. Counting it
across the whole log settles what it was:

| Start | Seek arrived before the first frame | AudioTracks built |
|---|---|---|
| 11 ordinary casts | no | 1 (or 0, on a title whose audio started later) |
| Alpha generation 1 | yes | 2 |
| Alpha generation 2 | yes | 2 |

It is root cause A wearing a different hat. The receiver prepared at 0, so the audio renderer built a
sink for position 0. The phone's seek then arrived, flushed it, and a second sink was built at the
real position — the audio equivalent of the wasted video buffering and the wrong-scene frame. The
`exo.firstFrame.repeat` that follows the second build is the same flush seen from the video side.

No separate fix is needed: opening at the resume position removes the seek that caused it. Each
`exo.audio.trackInit` now records `posMs`, so the next capture proves it — one build, at the resume
position, instead of one at 0 and one at the resume position.

**One real defect did turn up while tracing it.** `STATE_READY` issued `player.seekTo()` directly.
That call was invisible in diagnostics, which is most of why this took so long to attribute, and it
did not clear `pendingSeekTargetMs` — so a coalesced seek was sent by `STATE_READY` and then sent
again by its own deferred runnable. Two seeks, two audio flushes, two AudioTracks. A waiting seek now
goes through `issuePendingSeek()` like every other seek, and a seek already sent is not re-sent
(Media3 queues a seek from any state, so re-issuing only bought another flush). The safety net for a
player sitting somewhere we did not ask for is kept, and now records `exo.seek.reissue`.

---

## 12. The libmpv engine

`startPositionMs` reached the ExoPlayer path first. mpv now honours it too, so a cast that fails over
to the second engine no longer pays the cost §3 describes.

mpv takes it as `start`. Two load paths need it, and they differ:

- The native path passes per-file options, so `start=<seconds>` joins `force-media-title` and
  `http-header-fields` in the options map. Per-file options touch nothing else.
- The IPC path cannot use that options map on the Fire OS build, so `start` is set as a property
  before `loadfile` — mpv reads it when it opens the file.

`start` is GLOBAL on the IPC path, which is the trap. A title that set it and left it would hand its
resume point to whatever played next. So every open states its own answer, including `none` for
"from the beginning". There is no cleanup step to forget, and no failure path that can leak a value.

---

## 13. What the box said after the receiver build was installed

Installed on `.181` at 14:43. A cast landed straight away, and the new `posMs` field settled §11
without argument:

```text
14:43:48.339 | exo.open.begin      | startPositionMs=0        <-- phone cannot send it yet
14:43:51.169 | exo.audio.trackInit | posMs=0                  <-- a sink for the START of the film
14:43:51.308 | exo.seek.issue      | targetMs=314237
14:43:51.443 | exo.firstFrameRendered
14:43:51.953 | exo.audio.trackInit | posMs=314237             <-- thrown away, rebuilt at 5:14
14:43:52.174 | exo.audio.trackInit | posMs=314237             <-- and once more
```

The platform log for the same seconds says what that costs, and it is worse than lost time. Each
sink is a full HDMI passthrough stream, opened and torn down:

```text
14:43:51.096 AudioFlinger openOutput  ... OUT_HDMI ... format E_AC3 ... RAW_DIRECT
14:43:51.339 patch_mgr_create_patch   Patch 73: mix -> OUT_HDMI
14:43:51.352 out_flush_new            io 1373
14:43:51.476 patch_mgr_release_patch  Patch 73
14:43:51.479 out_set_parameters       closing=true
14:43:51.907 AudioFlinger openOutput  ... OUT_HDMI ... (again)
```

Every open and close raises a `controlC0` UEvent that the box's own `ShowHdrAudioLogoService`
answers — the television re-announces the audio format on HDMI. That is an audio re-link at the
start of every resumed cast, and it is the likeliest source of the click the deeper audio buffer was
chasing. Two `AudioTrack ... disabled due to previous underrun, restarting` warnings follow at
14:43:53 and 14:43:54.

The same log also confirms the wrong-scene frame directly, rather than by inference:

```text
14:43:52.031 VideoRenderQualityTracker: Rendered frame is earlier than the next expected frame
             (1000000000000, 1000314250000)
```

A frame whose timestamp is position **0** was rendered after the player had already seeked to
314.25s. The position-0 pipeline was still draining.

So the three sinks, the repeated first frame, the stale frame and the HDMI re-link are one event:
the position-0 pipeline being built and then torn down. `startPositionMs` never builds it.

**What the same install already fixed.** `exo.frameRate.unmatched measured=0.030` is gone; the
measuring window no longer spans the seek. The rate arrived 5.17s after the picture, which is past
`NON_SEAMLESS_GRACE_MILLIS`, so the mode change was refused and the viewer got no mid-film black
flash. Once the seek goes away the window should complete about 2s after the first frame and fit
inside the grace, which is the case that both matches the rate AND does it while the screen is
still black.

**What it also exposed.** `overlay.rescued phase=Playing startup=Ready`, 81ms after the first frame.
The safety net that guarantees something is on screen asked `playbackActive`, which is fed from the
controller's event stream and had not caught up, so it drew the home screen over a film that had
just started. It now also accepts the phase as evidence of a picture
(`ReceiverPlaybackPhasePolicy.showsPicture`), which is set in the same render pass. `Stopped`,
`Ended`, `Error` and `Idle` still let the net fire — that black screen is why it exists.

---

## 14. The “ran out of video memory” alert after seeking

The alert photographed on `.181` was not reliable evidence of a new allocation failure. The kernel
had recorded real `heap-gfx` allocation failures during the earlier 4K Dolby Vision cast, but the
receiver later reused that explanation for a different observation: a full buffer and a playhead
that appeared not to advance. The later alerts followed backward seeks:

```text
15:33:08 seek 950s -> 0s -> 474s
15:33:21 alert at 482s                 # about 8.5s of healthy post-seek progress

15:38:37 seek 2728s -> 422s
15:38:49 alert at 432s                 # about 9.8s of healthy post-seek progress
```

The decoder-stall watchdog retained the pre-seek high position. A backward seek therefore remained
“behind” its old baseline even while the new playhead advanced normally. After the old 12-second
wall-clock deadline, healthy playback met the stall predicate. The specific video-memory message
also overclaimed the cause: full-buffer/no-progress distinguishes a decoder/render-pipeline stall
from network starvation, but it does not prove ENOMEM.

The receiver now treats a submitted seek, its discontinuity, and the first rendered post-seek frame
as explicit lifecycle boundaries. Each rebases the progress clock; deferred and settling seeks
suppress stall action for a bounded interval, while ordinary playhead progress clears settlement even
if a vendor omits a callback. Pause and every public open also rebase/reset the relevant state.

A genuine full-buffer frozen-playhead stall gets one receiver-owned recovery per public open. The
old player is detached from the view and surface and synchronously released before a replacement is
built at the captured position, with play intent plus audio/subtitle selections carried forward. A
second stall is terminal and uses the weaker, accurate message “The TV video decoder stopped.” A
decoder that initializes but reaches end-of-stream without presenting any frame follows the same
bounded recovery/terminal path.

Later link-switch evidence widened the ownership rule. A 3840x2160 AVC link fell through to the
FFmpeg software video renderer and measured only 14.841 fps. The following supported HEVC link then
failed both `c2.amlogic.hevc.decoder` and `c2.android.hevc.decoder` initialization because the
`SurfaceView` BufferQueue was `already connected`. A later AVC link failed the same way. Restarting
the receiver immediately made the same 3840x2160 HEVC source initialize on
`c2.amlogic.hevc.decoder`, render its first frame, and measure 30.013 fps. The weaker explanation
consistent with all observations is unsafe decoder/surface ownership across arbitrary public link
replacement—not missing HEVC support and not a HEVC-specific defect.

Every public link now fully retires the outgoing player before building the next one. A supported
video decoder initialization failure gets one release-before-rebuild retry; a second failure is
terminal and retains the actual video MIME in phone telemetry. FFmpeg remains available for audio
only: its video renderer is not registered, so an over-limit 4K stream cannot degrade into a broken
software-decoded slideshow. The selector also no longer force-selects tracks beyond renderer
capabilities. Video errors recommend another video source/codec, never AC3/EAC3/AAC audio.

This matches the useful part of large streaming services' strategy—bounded same-source retry,
complete decoder ownership turnover, then source/codec fallback—without pretending the receiver can
change an arbitrary file's encode ladder. The GTA-style image in the failure photograph was the
landscape artwork URL staged by the phone for that title, not a decoded frame from the failed stream.

Physical proof on the onn 4K Pro after installation:

```text
pre-seek position: 15.676s
coalesced burst + backward + forward seeks: 90s/78s/84s -> 6s -> 42s
post-seek position after crossing the old threshold: 42.005s -> 55.948s
diagnostics: opens=1 defers=1 seeks=5 frames=4 decoderRecoveries=0 terminalFailures=0
SEEK_REGRESSION_PASS

link switch: AVC -> HEVC -> AVC
diagnostics: opens=3 freshPlayers=3 firstFrames=3 errors=0 ffmpegVideo=0
surface `already connected` events: 0
LINK_SWITCH_PASS

adversarial 3840x2160 AVC: c2.amlogic.avc.decoder + first frame, ffmpegVideo=0
```

The repeatable probe is `4789TV/scripts/verify-decoder-seek-recovery.sh`. The clean Android
foundation matrix also passed all 203 Gradle tasks plus manifest, native 16 KB alignment, and Play
package checks.

---

## 15. Large-file seek latency (2026-08-31)

The latest `.181` capture included a 4K Dolby Vision seek from `615178ms` to `1238445ms`. The
receiver issued the seek at `15:10:51.263`, rendered the requested frame at `15:10:52.271`, and
returned to ready at `15:10:52.425`: about one second to picture, with no decoder error or recovery.
The seek-window transfer sample was `99,671,543` bytes over `14,887ms` (`53.6Mbps`). That proves the
active CDN/device path was well below the broadband plan's headline rate, but it does not prove the
network caused the remaining latency.

The receiver was still using Media3's default exact seek. For inter-frame 4K video, exact seek can
fetch a prior synchronization frame and decode forward until the requested timestamp. That makes a
bounded sync window a plausible optimization, not a proven cause from this one sample. After a
first review rejected a global ±5-second experiment, the retained change is scoped to non-live UHD
video after its decoded/declared size is known. Initial resume, live streams, and smaller files keep
exact seeking. UHD uses Media3's native `SeekParameters` with a bounded ±2-second synchronization-
frame window, matching the receiver's pre-existing callback-ownership bound. Rendered-frame
acceptance now requires both the analytics position and current player position, when present, to
agree with the latest target. Seek commands are serialized: a newer scrub target stays pending until
the active seek presents its first frame, so overlapping sync windows cannot make an older callback
look current. The latest target then starts while audio remains muted across the handoff. A missing
vendor first-frame callback releases that ownership after 12 seconds, advances to the pending target
if one exists, or restores audio if it does not. `exo.seek.mode` records `mode=sync2s` only when UHD
actually enables the window, so the next user-run 4K capture has trustworthy mode evidence.

No playback test was run on `.181` after this change, per owner instruction. The receiver APK was
only built and installed; the next physical evidence will come from the owner's cast/seek session.
