# CONTEXT — 4789 TV receiver

> Summary: The ubiquitous language of the 4789 TV receiver. Read this file before you
> change code in `4789TV/`. Use these words exactly as they are defined here.
> Tags: #context #tv #receiver #ubiquitous-language
> Updated: 2026-08-04
> Sources: `4789TV/README.md`, `4789TV/DECISIONS.md`, `4789TV/HARDWARE_COMPATIBILITY.md`,
> `app/src/main/java/com/fourseveneightnine/tv/protocol/ReceiverContract.kt`

---

## 1. What this area is

4789 TV is an Android app. It runs on a television. It plays video.

The user opens 4789 TV on the television. The user then selects it in the 4789 iPhone app.
The television plays the video. The iPhone sends the commands.

The app is sideloaded. It is not in any store.

---

## 2. Roles

| Word | Meaning |
|---|---|
| **receiver** | The 4789 TV app. It runs on the television and plays the video. Always say "receiver", never "the TV app" or "the server". |
| **phone** | The 4789 iPhone app. It sends commands. It owns Continue Watching. It is authoritative for the playhead during a handoff. |
| **box** | The physical hardware. Examples: a Fire TV stick, an NVIDIA Shield, an onn 4K Pro. |
| **panel** | The television screen. The panel and the box have different abilities. A box can decode HDR when the panel cannot show it. Keep the two words apart. |
| **AFTDCT31** | The 2022 Insignia Fire TV. It is the compatibility floor, not the target. Almost every workaround in this codebase exists because of this one box. See `HARDWARE_COMPATIBILITY.md` §2. |
| **Fire OS 7** | Amazon's Android 9 build. It reports API 28. Never infer ability from that number — Amazon backported media constants. |

---

## 3. The wire

| Word | Meaning |
|---|---|
| **wire protocol** | Kodi JSON-RPC. The phone speaks it. Stock Kodi clients must keep working. |
| **vendor method**, **vendor event** | A method or event with the `X4789.` prefix. Stock Kodi never sends or emits it. A phone that does not know it must still work. Examples: `X4789.GetReceiverInfo`, `X4789.OnPlaybackError`, `X4789.OnExternalHandoff`. |
| **sidechannel** | A vendor path that carries data the Kodi protocol has no field for. Examples: `X4789.SubtitleStyle`, and settings written through `Settings.SetSettingValue`. |
| **snapshot** | `ReceiverSnapshot`. The playback state at one instant: position, duration, speed, volume, mute, buffered. |
| **identity response** | A first-party-only HTTP reply on port 8791. The phone uses it to find a receiver by direct probe when the router blocks Bonjour. |
| **ports** | HTTP `8791`. WebSocket `9791`. These are fixed. Do not change them without reading the port history in `DECISIONS.md` — the app has moved ports twice because of collisions with Amazon services and with Kodi. |
| **local contract** | A `ReceiverController` method that the on-TV controls use and the wire protocol does not. Example: `setSpeedMultiplier` for 0.75x and 1.25x. The Kodi wire has integer speeds only, and it stays untouched. |

**Rule:** every new field must be optional. An older phone and an older receiver must both still
work. State this in the code comment when you add one.

---

## 4. Playback

| Word | Meaning |
|---|---|
| **engine** | The code that decodes the video and draws it. Two engines exist: **Exo** and **mpv**. |
| **Exo path** | ExoPlayer, through Media3. This is the default on every brand. |
| **mpv path** | libmpv. It is selectable but unsafe to select in this build: libmpv ships FFmpeg n8.1 and the ffmpeg extension ships 6.0 under identical SONAMEs. Only one set can be packaged. |
| **controller** | `ReceiverController`. The interface both engines implement. The RPC layer talks to this and never to an engine directly. |
| **engine failover** | One silent retry from Exo to mpv after a codec failure. One attempt per open. A source or network failure never retries — a dead stream is dead on every engine. See `EngineFailoverPolicy`. |
| **open** | To start playing a URL. |
| **cold open** | An open from a stopped state. Only a cold open proves a media-stack change. A title that is already playing started under the previous build. |
| **replacement open** | A new title starts while another title plays. The old engine retires after a bounded surface handoff. |
| **surface** | The Android `Surface`. Video draws into it. The receiver advertises itself only while a visible surface is ready. |
| **playback phase** | `ReceiverPlaybackPhase`. What the television shows: Idle, Opening, Buffering, Playing, Paused, **Ended**, Stopped, Error. |
| **Ended** | The film ran to its own end. NOT the same as **Stopped**, which is what a viewer or the phone asked for. Ended raises the end-of-film plate (replay / next episode / done); Stopped raises the "ready for your iPhone" screen. Only the Exo path reports it — mpv's `endFile` cannot tell a natural end from a stop. |
| **next up** | `NextUpItem`, an OPTIONAL field on `X4789.NowPlaying`. The episode to offer when this one finishes. The receiver has no catalogue and NEVER guesses what follows; with nothing sent, the end plate offers a replay alone. |
| **chapters** | `chapterSeconds`, an OPTIONAL array on `X4789.NowPlaying`, in seconds from the start. They come from the phone because ExoPlayer does not surface a container's chapter list. An empty list draws the plain rail. A malformed one is IGNORED, never rejected — refusing the call would cost the viewer their film to protect a tick mark. |
| **taxonomy** | `PlaybackErrorTaxonomy`. It turns a raw player failure into a category and a human sentence. A decoder-init MIME type beats an error code. |

---

## 5. Audio

| Word | Meaning |
|---|---|
| **passthrough** | Send the audio bitstream to the sound system without decoding it. The AVR or soundbar decodes it. |
| **route** | The audio path out of the box: the television speakers, ARC, eARC, or an AVR. |
| **direct playback support** | What Android reports the active route accepts. Read with `getDirectPlaybackSupport` (API 33) or `isDirectPlaybackSupported` (API 29). |
| **probe** | A question asked of the device at runtime. `AndroidDirectAudioProbe` and `VideoCodecProbe` are probes. |
| **speaker profile** | The set of audio preferences the phone sends. It is a request, not a command. Android decides the real output. |
| **automatic** | The default audio mode. It defers to the platform. |
| **decoded PCM** | The fallback. It is never silent. Playback falls back to it whenever passthrough is not possible. |

**The most important rule in this area.** An empty probe result means **UNKNOWN**. It never means
"nothing supported". Below API 29 Android cannot report the route at all. In 0.1.24 the Exo path
read an empty list as "this box supports only PCM", built `AudioCapabilities` from it, and killed a
working E-AC3 5.1 output 68 ms after `startOutput()`. It was broken on every Fire OS 7 box.
Never narrow capabilities from a probe that could not run.

---

## 6. Picture

| Word | Meaning |
|---|---|
| **color-safe SDR** | The Dolby Vision fallback. The receiver tone-maps the source to BT.709 and BT.1886. See `DolbyVisionColorPolicy`. |
| **native Dolby Vision** | The television's own DV decode and its DV badge. **The receiver does not do this.** Never write or say that it does. Profile 5 has no HDR10 base layer, and the Android GPU path cannot request a DV swapchain. |
| **upscale mode** | `UpscaleMode`: `OFF`, auto, or force-1080p. It runs Snapdragon GSR v1 as a Media3 `GlEffect`. |
| **pill** | A control on the bar in `PlayerControlsView`. Every pill carries its own LABEL AND CURRENT VALUE (`Audio · ENG 5.1`, `Speed 1x`) — never a bare glyph, because a viewer cannot check a setting they have to change in order to read. Four sit on the bar: play/pause, audio, subtitles, and **More**. |
| **the More rail** | `OptionRailView`, the choice list on the right-hand edge. It holds speed, picture size, upscaling and hand-off, and it also serves the audio and subtitle pickers. It replaced `AlertDialog`, which drew a phone-sized card in the platform's theme — unreadable from a sofa. The `More` pill prints the values it is hiding. |
| **zero-copy path** | MediaCodec draws straight to the surface. Any Media3 effects pipeline leaves this path, and its colour conversion differs. That difference changed the picture on native-4K titles in 0.1.34. The pipeline now attaches lazily, and only when the frame is genuinely enlarged. |

---

## 7. Handoff

**Handoff** means: give the stream to a different app **on the same television**.

Handoff is not casting. Casting moves video between devices. Handoff moves it between apps on one
device.

Handoff is not a stop. The video is still on that television. The phone must keep its session and
say where the video went. A phone that tears down its cast bar leaves the viewer with no controls
and no way back.

| Word | Meaning |
|---|---|
| **target**, **target player** | The app that receives the handoff. Examples: Just Player, VLC, Kodi, TiviMate, Next Player, mpv. |
| **external player** | A handoff-capable app that is installed on this box. Listed by `ExternalPlayerIntentPolicy.installedPlayers()`. |
| **dialect** | How much handoff state a target accepts. `MxStyle` takes position, subtitle and headers. `Vlc` takes position and a subtitle path. `UrlOnly` takes the URL alone. Rank targets by dialect, because the best target is the one that loses the least. |
| **handoff context** | `ExternalHandoffContext`. What travels with the stream: position, subtitle URL, subtitle name, headers. |
| **return trip** | The position coming back from the target when the viewer leaves it. This does not work yet. See `docs/specs/on-tv-installer-handoff.md`. |

---

## 8. Discovery

| Word | Meaning |
|---|---|
| **advertise** | Publish the DNS-SD record so the phone can find this receiver. The receiver advertises only while the app is visible and its surface is ready. |
| **re-publish** | Register the record again after this box's own IP address changes. Android binds the record to the address held at the time. After a DHCP change the record resolves to an address nothing answers on. |
| **multicast lock** | Android's Wi-Fi multicast lock. mDNS discovery is unreliable without it on Android 12 and older Sony software. |

---

## 9. Distribution

| Word | Meaning |
|---|---|
| **Downloader code** | `6364713`. A stable numeric address from AFTVnews Downloader. The asset behind it is replaced in place. The code never changes. |
| **the release tag** | `v0.1.0-test`. The Downloader code resolves to this tag. It is **not** `v0.1.6-test`, even though such a tag exists. Resolve the code and read the target before you publish. |
| **the gate** | `./gradlew clean testDebugUnitTest lintDebug assembleDebug` with JDK 17. |
| **on-box verification** | Install on real hardware and play a title from a cold open. |

**The process rule, learned on 2026-08-03.** The gate cannot see a Fire OS 7 runtime fault. Any
change to the media stack — engine, sink, capabilities, or a dependency version — is not verified
until it plays on the real box from a cold open. Read `startOutput` and `stopOutput` in logcat, not
only `firstFrameRendered`. The broken 0.1.24 build rendered a frame and then killed the audio 68 ms
later.

---

## 10. Words we do not use

Each row below is a real mistake this project made. Do not repeat it.

| Do not say | Say instead | Why |
|---|---|---|
| "the cast failed" | "playback failed" | Device logs proved that no cast ever failed for a network reason. Every failure was a playback failure the phone could not see. |
| "connection lost" | The real cause, from the taxonomy | Example: "this television has no DTS decoder". A missing decoder is not a lost network. |
| "native Dolby Vision" | "color-safe SDR" | The receiver tone-maps. It does not decode DV natively. |
| "the handoff stopped playback" | "the handoff moved playback to <player>" | The video is still on the television. |
| "the probe says the route supports nothing" | "the probe could not run, so the route is unknown" | Empty means unknown. |
| "verified" after unit tests | "the gate passed; on-box verification is owed" | Unit tests cannot see a runtime fault. |
| "the TV supports HDR" | Name the box or the panel | They differ. Be specific. |

---

## 11. Where truth lives

Read in this order. A later file never overrules an earlier one.

1. `/CLAUDE.md` — the repository contract and the invariants.
2. `4789TV/CONTEXT.md` — this file. The words.
3. `4789TV/DECISIONS.md` — why each release chose what it chose. Append-only in spirit.
4. `4789TV/HARDWARE_COMPATIBILITY.md` — what each class of box can do.
5. `4789TV/HANDOVER.md` — the current state.
6. `4789TV/README.md` — build, publish, and install steps.
7. `4789TV/docs/specs/` — work that is planned but not built.

When the code and this file disagree, the code wins. Correct this file in the same change.
