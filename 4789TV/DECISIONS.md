# 4789 TV MVP Decisions

These decisions freeze the receiver scaffold scope for the medium **4789 TV**.

- `4789TV` is a standalone Gradle Android TV project inside this workspace and outside the iOS build.
- The MVP is URL-only and single-player.
- The receiver exposes HTTP on `:8791` and WebSocket on `:9791`. (History: launched on 8080/9090,
  moved to 8090/9091 in 0.1.9 to dodge Kodi, moved again in 0.1.16 because Amazon system services
  hold 8080 on Fire OS, which pushes Kodi itself onto 8090 — colliding with the receiver and
  crashing it at launch. 8791/9791 collide with nothing observed on Fire OS.)
- 0.1.29: the waiting screen is dressed. `X4789.NowPlaying` carries optional `artworkURL`
  (16:9 backdrop) and `posterURL`, which the receiver fetches itself and fades in behind
  "Opening…" / "Buffering…" / "Playback stopped" — a cast takes seconds of connect + probe + codec
  init, and the title's own art is the difference between a wait that looks alive and one that
  looks hung. Artwork is NOT routed through `ReceiverController` (no engine needs it); it goes to
  the Activity through `NowPlayingArtwork`. URLs are http(s)-only and length-capped, the fetch
  subsamples to the panel width in RGB_565 (a 3840px backdrop is a 60MB ARGB allocation on a stick
  with a 256MB heap), and any failure leaves the overlay exactly as it was. Both fields are
  optional, so an older phone emits byte-identical frames. The launcher icon, the leanback banner
  and the on-screen mark are now the phone app's own 4789 logo with a glowing "TV" beneath it.
- 0.1.28 (Fire OS/Exo path): the receiver has its own player UI on the television
  (`PlayerControlsView`), modelled on Just Player — seek bar with an accelerating D-pad scrub, audio
  and subtitle pickers, fit/crop/stretch, and fractional speed. Fractional speed is deliberately a
  LOCAL contract (`ReceiverController.setSpeedMultiplier`): the Kodi wire protocol the phone speaks
  has integer speeds and stays untouched. Two latency/quality knobs move with it: playback now
  starts on 700 ms of buffer instead of 2500 ms (`ExoStartupBufferPolicy` — `minBufferMs` is
  unchanged at 15 s, so the loader still fills behind the picture and only the black-screen wait
  shrinks), and a burst of seeks collapses into one player seek (`SeekCoalescingPolicy`, 250 ms).
  The seek burst mattered because ExoPlayer re-initialises the AudioTrack on every seek — one live
  scrub produced six `exo.audio.trackInit` records in a second, each a chance to click or gap.
- 0.1.19 (Fire OS/Exo path): subtitle track selection applies the resolved override ONCE (the old
  loop left its counter un-incremented on a match, so every later text group overwrote the choice
  and any pick landed on the last track); sideloaded subtitles rebuild the source from the original
  open request instead of `currentMediaItem`, which carried none of the authenticated OkHttp factory
  or headers, and the added track is selected explicitly once it appears. Subtitle fonts map per
  face instead of collapsing seven of the phone's fourteen choices onto one fallback — Futura and
  Avenir now render in bundled **Jost\*** (OFL geometric revival; Apple's Futura cannot ship here),
  Trebuchet/Verdana/Gill Sans use nearest-class Android families, Georgia→Fraunces, Menlo→JetBrains.
  Volume drives the device output (`STREAM_MUSIC`, needs `MODIFY_AUDIO_SETTINGS`) rather than the
  player's own gain; Fire TV Edition sets refuse app volume writes on their fixed output route, so
  the receiver detects the refusal and applies the level as player gain, keeping the phone's slider
  audible on every television.
- 0.1.17 (Fire OS/Exo path) restores parity the mpv→ExoPlayer port had silently dropped:
  cues render through a `SubtitleView` (ExoPlayer decodes cues but draws nothing on its own, so the
  receiver was reporting subtitle tracks the television never showed) styled by the existing
  `X4789.SubtitleStyle` sidechannel (family/colorHex/size/lift, same payload the mpv path honours);
  video is letterboxed by an `AspectRatioFrameLayout` fed from `onVideoSizeChanged` including
  pixel-aspect-ratio, instead of a bare MATCH_PARENT SurfaceView that stretched SD and anamorphic
  titles; pause is reported honestly (Kodi speed 0 = paused — `playbackParameters.speed` stays 1.0
  while paused, which made the phone's scrubber run on and snap back), `playWhenReady` changes are
  broadcast so a pause issued mid-rebuffer reaches the phone, `Player.SetSpeed 0` maps to pause, and
  auto-start applies only to the first READY of an open (it previously re-asserted on every rebuffer
  and silently un-paused the user). A dropped video track (Dolby Vision profile 5 on a non-DV panel)
  now raises the error overlay instead of playing audio over a black screen.
- 0.1.20 (Fire OS/Exo path): Media3 moves 1.5.1 → 1.10.1. The two `@UnstableApi` surfaces the Fire OS
  workarounds ride on (`forceDisableMediaCodecAsynchronousQueueing`, the widened
  `DefaultAudioTrackBufferSizeProvider`) both survive the bump; the buffer-size setter is deprecated
  but still stores state, so the audio cushion is intact. media3-ui's `AspectRatioFrameLayout` /
  `SubtitleView` / `CaptionStyleCompat` now require an explicit `@OptIn(UnstableApi::class)`, which
  `MainActivity` carries the same way `ExoReceiverController` already did.
- 0.1.24 (Fire OS/Exo path): **never narrow the audio sink's capabilities from a probe that could
  not run.** `AndroidDirectAudioProbe.supportedMpvCodecs` returns an empty list below API 29 —
  meaning "unknown", not "nothing supported". The mpv path tolerates that, because there an empty
  list only means "ask for no passthrough". The ExoPlayer route added in 0.1.20 read the same empty
  list as "this device supports only PCM" and built `AudioCapabilities` accordingly, which tore down
  a working E-AC3 5.1 output on AFTDCT31 68ms after `startOutput()`, then ended playback. It was
  broken on every Fire OS 7 box — i.e. all of them. `ExoAudioRoutePolicy.resolve` now takes
  `routeProbeAvailable` and returns `Automatic` when the platform cannot report the route.
- 0.1.24 (Fire OS/Exo path): a settings push must never tear down live playback. The 0.1.20 route
  change released and rebuilt the player to apply a profile immediately; a speaker profile routinely
  arrives while a title is playing. The sink's capabilities are fixed at construction, so the route
  is now recorded and applied at the next open — which is when the phone sends it in the normal flow
  anyway.
- 0.1.23: Media3 1.10.1 was tried in 0.1.22 and reverted, but **the stated reason was wrong and the
  revert may be unnecessary.** The `ClassNotFoundException` for `android.view.Choreographer$VsyncCallback`
  in `VideoFrameReleaseHelper` is logged by ART at level `I` and is benign — a frame rendered
  successfully one millisecond later. The actual fault was the audio-capabilities bug above, which
  existed on 1.5.1 too. Re-attempt the bump as a single isolated change and install-test it on the
  box before believing either outcome. Stuck-player detection went out with the revert
  (`StuckPlayerException` and `setStuckBufferingDetectionTimeoutMs` are 1.9+ API) and can return
  with it.
- **Process rule, learned the hard way (2026-08-03):** `testDebugUnitTest`/`lintDebug`/`assembleDebug`
  cannot see a Fire OS 7 runtime fault. Any change to the media stack — engine, sink, capabilities,
  or a dependency version — must be installed on the real box and a title played through *from a
  cold open* before it is called verified. Watching an already-playing title survive proves nothing:
  it started under the previous build. Read `startOutput`/`stopOutput` in logcat, not just
  `firstFrameRendered` — the broken build rendered a frame and then killed audio 68ms later.
- 0.1.22 (both paths): the DNS-SD record is re-published when this box's own address changes. The
  HTTP and WebSocket listeners bind `0.0.0.0` and survive an address change on their own; the
  advertisement does not, because Android registered it against the address held at the time. After
  a router reboot or DHCP lease change the record still resolves, to an address nothing answers on —
  the box appears in the phone's list and then fails every connection. A default-network callback
  watches the routable link addresses (link-local and loopback are ignored, since a box can gain or
  drop those without its real address changing) and re-registers after a 1.5s coalescing delay,
  because Android emits a burst of callbacks while a lease settles. Re-registering is `stop()` then
  `start()`, the sequence the existing planner already serialises, so it cannot race a second
  listener. Adds `ACCESS_NETWORK_STATE`.
- 0.1.22 (both paths): the engine is overridable via `Settings.SetSettingValue` on `x4789.engine`
  (`auto`/`mpv`/`exo`, persisted, applied at next Activity start), and the running engine is
  reported by `X4789.GetReceiverInfo`. The brand check remains the default because it encodes tested
  behaviour, but it was previously a law: a Fire TV could never run libmpv and a Shield could never
  run ExoPlayer, so a device-specific playback fault had no comparison to test against and no
  workaround short of a rebuild. The setting is handled in the dispatcher, not in a controller — a
  controller cannot replace itself — and an unrecognised value is a parameter error rather than a
  silent no-op. `auto` is stored as no-override so a later default change is picked up.
- 0.1.21 (Fire OS/Exo path): a wedged player recovers itself once. Media3 1.9+ reports a stalled
  player as `StuckPlayerException`, but its 10-minute default timeout suits a phone in a pocket, not
  a television where a frozen spinner is indistinguishable from a crashed box. The detection window
  is 45s — comfortably above the 15s minimum buffer, so a slow-but-live stream survives — and the
  first stall of a title triggers one silent reopen at the same position. A second stall, or one
  that repeats within 15s, reaches the error overlay instead: an endless retry loop looks exactly
  like the freeze it was meant to fix and burns the network while hiding it. The budget resets per
  open, not per session.
- 0.1.21 (both paths): `X4789.GetReceiverInfo` now reports `hardwareVideoCodecs`, so the phone can
  rank sources against the decoders THIS television actually has rather than guessing. Support is
  read from the device's own `MediaCodecList` and never inferred from the API level — Fire OS 7
  reports API 28 but Amazon backported the AV1 media constants, so an SDK-gated check rejects
  hardware that decodes AV1 perfectly well (the trap Jellyfin hit). Below API 29, where
  `isHardwareAccelerated` does not exist, vendor decoders are distinguished from Google's reference
  decoders by the OMX/c2 naming convention. An older receiver omits the field, so the phone must
  treat absent and empty alike.
- 0.1.20 (Fire OS/Exo path): `applySetting` is implemented instead of returning success for
  everything. ExoPlayer has no live passthrough switch — `DefaultAudioSink` decides passthrough
  versus decode purely from the `AudioCapabilities` it was constructed with — so the phone's speaker
  profile is resolved (`ExoAudioRoutePolicy`) into the capabilities the sink is built with, and a
  profile change that actually alters the resolved route rebuilds the player at the current position.
  Automatic remains the default and defers to the platform. Kodi keys that describe Kodi's own audio
  engine now report `false` rather than claiming a success this path never delivered. The per-codec
  key map is shared with the mpv path (`DirectAudioPolicy.codecSettings`) so the engines cannot drift.
- 0.1.16 (Fire OS/Exo path): MediaCodec is forced synchronous (AFTDCT31 vendor decoders deadlock in
  async callback mode — the same platform bug that forced mpv's `hwdec=no`), transport start
  pre-probes both ports and shields Ktor engine coroutines so an occupied port degrades to a
  retryable error instead of a process crash, and the playback-error overlay offers "Open in
  Just Player / VLC / Kodi / TiviMate" handoff buttons (also reachable via `X4789.OpenExternal` and
  listed by `X4789.GetExternalPlayers` / `GetReceiverInfo.externalPlayers`). The iPhone now sends
  receivers on 8791 the direct stream URL (headers via the Kodi `|` suffix); the phone-side byte
  bridge remains only for legacy 8090 receivers and local files.
- Network Service Discovery (NSD) is active only while a visible, attached `Surface` is ready.
- Playback remains Activity-owned so a real visible Surface always exists. Version 0.1.4 adds a
  best-effort `BOOT_COMPLETED`/package-update launch request; manual launch remains the fallback.
- Distribution is sideload-only.
- Audio selection, subtitle selection, external subtitles, and subtitle styling are implemented.
- Automatic is the default first-party audio mode. Android intersects the stream codec with the
  active route's direct-playback capabilities on Android 10+, preserving supported 5.1/7.1 and
  Atmos-class EAC3/TrueHD while retaining decoded PCM as the never-silent fallback.
- HDR10/HLG use the existing `gpu-next` Android surface path. Dolby Vision profiles use a color-safe
  SDR compatibility policy because mpv's Android GPU output does not guarantee native DV metadata;
  native Dolby Vision passthrough and its TV badge remain a future player-architecture gate.
- Continue Watching is phone-owned. The local-file HTTP server supports single byte ranges, and
  per-title/per-TV semantic audio/subtitle choices are bounded to the 500 most recent records.
- Cast Connect, DLNA, and AirPlay are separate non-Android receiver legs, not part of this APK.
- HDR decode is allowed through the TV's MediaCodec path, but the APK cannot guarantee the entire
  panel/HDMI HDR chain. Play Store distribution requires owner-controlled signing and Play Console.
- A Downloader shortcut may be created only after a release APK is hosted at an immutable versioned
  HTTPS URL and verified by exact byte count and SHA-256. The shortcut is convenience only; the
  versioned URL and digest remain authoritative.
- Never replace an asset behind an existing tag or mutable shortcut. A new build requires a new tag,
  an owner-controlled release signature/AAB for Play, and deliberate installer/test/docs updates.
- 0.1.34 (Exo path): opt-in AI upscaling. `x4789.upscale` (boolean, phone settings sidechannel,
  persisted) routes the picture through Snapdragon GSR v1 — the edge-direction variant, BSD-3 from
  Qualcomm — as a media3 `GlEffect` via `ExoPlayer.setVideoEffects`. Scale is an aspect-preserving
  fit into the panel capped at the shader's honest 2x (`UpscalePolicy`); native-or-larger content
  declares the effect a no-op so 4K titles never pay for a copy pass. SGSR needs GLES 3.1
  (`textureGather`) and an SDR signal — pre-3.1 drivers and HDR titles get a passthrough copy, and
  any pipeline failure downgrades to plain playback rather than killing the open. Deliberately
  opt-in and applied on the NEXT open only: `setVideoEffects` must precede `prepare()`, an empty
  effects list still drags every frame through an OpenGL video graph (so untouched players keep
  the zero-copy MediaCodec path), and a stick-class Mali-G31 pays ~14 ms/frame for the pass —
  fine for 24 fps film, fatal for 60 fps. CNN upscalers (Anime4K/FSRCNNX) were rejected for this
  hardware class; Moonlight's measurements on the same GPU tier drove the choice. On-box A/B and
  frame-drop verification still owed.
- 0.1.34 REVISION (2026-08-04): the upscale broke colours on native-4K titles on the Fire TV, so
  the design changed in the same release. Root cause: the Fire TV renders its UI at 1920x1080
  (display override), so `scaleFor(4K → 1080p UI)` = 1.0 and the SGSR shader correctly no-oped —
  but the media3 effects pipeline itself reroutes every frame through an OpenGL video graph whose
  colour conversion differs from the zero-copy MediaCodec-to-surface path, and that conversion
  changed the picture for EVERY title while the setting was on, native-4K included. Fix: the
  pipeline is now attached LAZILY — only from `onVideoSizeChanged`/`onVideoInputFormatChanged`,
  and only when the decoded size proves the title would actually be enlarged (scale ≥ 1.05) AND
  the signal is SDR (BT.2020+PQ/HLG never enters the pipeline at all). Native-resolution and 4K
  titles never touch GL; the zero-copy path is the default for them regardless of the setting.
  Disabling (phone sidechannel or the new on-TV pill) tears the pipeline down immediately, so the
  current picture's colours return without reopening the title; enabling applies on the next
  open. The TV gains a pill-check toggle in `PlayerControlsView` (✓ Upscale, accent checkmark)
  that persists through the same `x4789.upscale` pref the phone sidechannel writes. Also fixed a
  pre-existing broken KDoc in `PasteUrlPolicy` (the markdown `video/*` nested a block comment and
  silently deleted the whole paste policy from the build). Gate: assembleDebug + 195/195 unit
  tests green (5 new `shouldApply` cases). Installed to Fire TV 192.0.2.124 (0.1.34) with the
  pref reset to off; the owner re-enables from the TV pill. On-box visual A/B of the pill still
  owed (needs a live cast).
- 0.1.34 REVISION 2 (2026-08-04): the TV pill now cycles THREE states — Off / ✓ Upscale (auto:
  attach only when the frame is genuinely enlarged, scale ≥ 1.05) / ✓ Upscale 1080p (force: also
  run the edge-directed pass at 1:1 on 1080p-class frames as a sharpener feeding the panel
  scaler). Force is deliberately capped: never touches frames taller than 1080p (4K-native stays
  on the zero-copy path — the colour regression), never HDR, and the phone's boolean sidechannel
  maps to auto/off so FORCE survives phone writes. At 1:1 the pass costs ~1/4 of the 2x fragment
  work (~3.5 ms/frame on stick GPUs), so 60 fps content is safe in force mode. Preference
  migrates from the boolean era via `upscaleMode` string key. Gate: assembleDebug + 200/200 unit
  tests (mode decision cases). Installed to Fire TV 192.0.2.124; owner's auto-on choice
  survived the migration. Visual A/B of the 1:1 sharpen still owed (owner-led).
