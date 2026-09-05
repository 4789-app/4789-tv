# 4789 TV

## Workspace products

This directory is now a four-module Android workspace. The modules have deliberately separate
distribution and lifecycle boundaries:

| Module | Product | Current artifact | Status |
| --- | --- | --- | --- |
| `contract` | Frozen cross-platform DTO/navigation policy | JVM test library | Decodes canonical JSON fixtures; stream URLs remain ephemeral |
| `phone` | Android phone app | `phone-debug.apk` + `phone-debug.aab` | Two doors, verified catalog/artwork, local/remote Media3 playback, encrypted source configuration, URL-free resume, app-private offline import, bounded first-party TV playback controls, and checked Play listing assets; parity work remains |
| `app` | Legacy Fire/sideload TV receiver | `app-debug.apk` | Compatibility target API 28; existing package and behavior preserved |
| `tvplay` | Google Play TV receiver | QA APK + `tvplay-debug.aab` | Separate identity, target API 34, no package installer, Exo/Media3-only native closure, verified 16 KB alignment; owner signing key and store work remain |

The phone APK is an installable review build, not a completed iOS port. Its public catalog refresh
uses the same pinned Ed25519 keys, bounded immutable artifacts, byte count, and SHA-256 checks as iOS;
first paint remains bundled/offline and poster artwork loads through the verified catalog. Detail can
open a user-selected local video or fetch play-now sources from a user-configured HTTPS Stremio
manifest. The manifest is AES-GCM encrypted with Android Keystore, read-back verified, backup
excluded, and never redisplayed after saving. Source responses are bounded, non-redirecting and
cancellation-owned; unsafe/duplicate URLs are rejected and accepted resolved URLs remain memory-only.
The shared Media3 player requests audio focus, pauses when the app backgrounds, and releases once
when dismissed. Its foreground crash-loss sampler wakes only while actually playing and at a
15-second cadence; lifecycle stop and teardown save immediately without background polling. A
manually entered site-local IPv4 target can receive a fresh play-now URL over the existing 4789 TV
JSON-RPC port. The target address is device-local, the transport has fixed 3/5/6-second bounds and a
64 KiB response cap, and the media URL remains memory-only. The same bounded channel exposes
play/pause, ten-second seek, and stop controls; invalid or non-private targets fail before network
work. Users can also copy a selected authorized
local video atomically into app-private offline storage. Opaque title-derived filenames, preflight
allocation checks, a 50 GiB file cap, partial cleanup, explicit removal, and no persisted document
capability keep that process-death-safe without weakening the URL contract. Broader receiver
background/restartable downloads, broader settings,
and full accessibility/device coverage are still explicit Android parity milestones. The Play bundles are QA bundles signed with
the local debug key; they must not be uploaded to production.

Playback progress survives process death using only the catalog title ID and bounded
position/duration values. It never stores a stream URL or document capability. A user therefore
reselects the local video or resolves a fresh remote source and the player resumes automatically;
backgrounding and dismissal save
before the player pauses or releases, and near-complete progress is removed.

Owner release builds use `scripts/build-play-release.sh`. It fails closed unless all four
`FOURSEVENEIGHTNINE_PLAY_*` upload-key variables are present, then tests/lints/builds both release
AABs and verifies their signatures. Keystores and passwords are ignored local/CI secrets; there is
deliberately no checked-in or debug-key fallback.

The checked Google Play source package is in `play-store/`: separate phone/TV listing copy,
data-safety draft, 512 px icon, 1024 × 500 feature graphics, and emulator-captured screenshots.
`scripts/check-play-store-package.py` validates its mandatory dimensions and is part of both the
clean Android gate and owner-signed release workflow. The package is submission-ready source, not
evidence that either Play record exists or that an owner-signed AAB was uploaded.

`scripts/share-android-qa.sh` copies the clean committed phone/Play-TV QA APKs and AABs, store
package, hashes, and an explicit debug-signing warning to a timestamped iCloud handoff. It refuses a
dirty worktree and never labels debug-signed artifacts as production releases.

## Release status

**Latest prepared TV update: 0.1.41 / code 42**, 83,563,455 bytes, SHA-256
`b14af1cc340e5ebab60b70e7d4dfbaba5b6307f3171a0e414d8d0833d262c21e`.
This includes the complete external-font notices and preserves the established public
compatibility signing certificate for in-place updates. Clean Android gates and Den synthetic
seek acceptance passed. Publication handoff is in `docs/PUBLIC_RELEASE.md`; the canonical
coordinator must verify the new public download before switching installation links.
The older candidate hash `70b159…adedf6` is superseded by these license-complete bytes.


**4789 TV 0.1.34 is the public compatibility build. Install only the immutable tagged asset at
`https://github.com/4789-app/4789-tv/releases/download/v0.1.34/4789tv.apk`.**

The phone installer pins that URL, its exact 59,236,985-byte length, and SHA-256
`bf50c58203f8dbf325085a31fb5475f59b51a9c020984dde86b3ccfac2cdd86b`. Historical mutable
Downloader shortcuts are not the release authority and must not be replaced in place.

- Standalone Android TV/Fire TV/Google TV/Shield project: present.
- Protocol, transport, and player core: implemented in code.
- Last verified JDK 17 legacy receiver gate: passed — 276/276 unit tests, lint, and debug APK.
- Last verified full Android workspace gate: passed on 2026-08-13 — 203 clean Gradle tasks including
  frozen-contract and phone lifecycle tests, phone APK/AAB + instrumentation assembly + lint, legacy
  TV 242/242 + lint/APK, Play TV 242/242 + lint/AAB, and manifest/ABI
  boundary assertions.
- Play TV no longer packages the disabled libmpv engine or its incompatible FFmpeg 8 closure. The
  working Media3/NextLib FFmpeg 6 set is now the only native playback closure in that AAB. The gate
  rejects those colliding libraries, validates 16 KiB APK alignment, and parses every ARM64 ELF load
  segment; all five retained native libraries report a 16,384-byte minimum alignment.
- 0.1.7 launch fix: target SDK is now API 28, matching the Fire OS 7 runtime used by the Insignia
  Fire TV family; compile SDK remains current.
- 0.1.9 moves the receiver to HTTP 8090 and WebSocket 9091 so it can coexist with Kodi's
  default 8080/9090 listeners. Startup also catches any future bind failure instead of allowing an
  asynchronous server exception to terminate the Activity.
- 0.1.16 moves the receiver again, to HTTP 8791 and WebSocket 9791: Amazon system services hold
  8080 on Fire OS, which relocates Kodi's own web server to 8090 — colliding with the receiver.
  Transport start now pre-probes both ports and shields the Ktor engine coroutines, so a future
  collision is a retryable on-screen error, never a crash. The Fire OS ExoPlayer path forces
  synchronous MediaCodec (async mode deadlocks AFTDCT31 vendor decoders), streams direct HTTPS
  (no phone-side byte bridge), and the playback-error overlay can hand the stream to Just Player,
  VLC, Kodi, or TiviMate.
- 0.1.10 makes `idle-active` authoritative after EOF and clears the previous file's clock before a
  replacement open, preventing a finished two-second clip from impersonating the next cast.
- 0.1.11 waits for libmpv's `FILE_LOADED` before reporting a successful connection and records
  credential-redacted native warnings. Matching iPhone builds bridge first-party VOD from remote
  HTTPS to LAN HTTP as unchanged source bytes because the bundled Fire OS libmpv HTTPS backend can
  stall before demux. The bridge preserves source headers, gives the receiver's first request
  ownership of one-shot signed URLs, and uses a bounded chunk queue sized for high-bitrate bursts.
- The 0.1.12/0.1.13 release history introduced fresh-engine replacement and Fire OS memory
  handling. The historical 0.1.14 candidate made Stop keep the idle engine and receiver endpoint
  alive for the next cast; title replacement retires the old engine asynchronously after a bounded
  surface handoff, while low/critical memory callbacks trim cache without taking down the endpoint.
- 0.1.15 fixes the Fire OS playback stall that made every real cast time out on AFTDCT31. The
  receiver attaches the Android Surface before libmpv initialization (the bundled wrapper only
  honors `wid` pre-init), no longer creates a video window while idle (which raced a pending VO
  against surface retirement and aborted `vo=mediacodec_embed`), and the replacement handoff waits
  for the old player's destroy instead of rewriting `wid` to 0. Software decode (`hwdec=no`) is the
  verified default: MediaCodec init deadlocks mpv's core thread for H.264 and HEVC on the AFTDCT31,
  and a hardware probe + process restart made the phone's cast session drop and re-cast in a loop.
  Hardware decode remains behind `MpvReceiverStartupPolicy.HARDWARE_DECODE_ENABLED` for a future
  libmpv build with a fixed FFmpeg wrapper. The receiver also tone-maps Dolby Vision/HDR sources to
  color-safe SDR (this panel is not HDR), keeps a gentler cache floor under memory pressure instead
  of starving a playing stream into audio underruns, and buffers 2s of audio to absorb bitrate
  spikes. The opening deadline is 60 seconds and the replacement surface handoff is bounded so a
  timed-out title cannot wedge the next cast.
  Verified on the physical Insignia Fire TV: MKV and moov-at-end MP4 both reach `FILE_LOADED`,
  HEVC software playback renders visible video with audio, seek, pause/resume, stop, and repeated
  replacement opens all work without process death or cast-session loss.
- 0.1.13 adds standard Android TV remote and media-session controls on every compatible receiver:
  center toggles play/pause, left/right seek 10 seconds, and Back stops playback without closing
  4789 TV or port 8090. Opening, buffering, stopped, and retryable failure states are visible on the
  television. A receiver-owned 30-second open deadline prevents a dead source from spinning forever.
- 0.1.14 was an experimental, unpublished reliability build. Loads, status snapshots, playback
  controls, timeout recovery, audio/subtitle track reads, and replacement transactions use bounded
  JSON IPC instead of blocking MPV JNI calls. The video surface is a TextureView so the receiver's
  controls remain visible above video. It is not a replacement for the public APK until the full
  physical stream matrix passes.
- Current workspace candidate 0.1.41 (versionCode 42): `app/build/outputs/apk/debug/app-debug.apk`
  (83,563,455 bytes; SHA-256
  `b14af1cc340e5ebab60b70e7d4dfbaba5b6307f3171a0e414d8d0833d262c21e`). It contains the
  post-publication connection/audio/status corrections plus responsive QR approval, deterministic
  focus handoff, correctly bounded encrypted envelopes, chunked Keystore persistence, a scrollable
  complete Settings rail, source-specific entry routing, exact library return focus, encrypted
  Tamil MV poster/metadata transfer into durable app-private storage, a Jobs progress surface, and
  a bounded poster-to-addon-source picker with compact TMDB route fallbacks.
- Startup routing is state-aware: a receiver without an approved setup opens Pair & Sync by default;
  after approval it enters the My Stuff lobby, while the Settings icon remains available for later
  changes. “Connected” means an approved receiver setup, not a phone that must stay online.
- TV screen, network discovery, video surface, launcher icon, and TV banner: implemented.
- The public URL serves 0.1.34, verified on 2026-08-09 by downloading the APK and inspecting its
  manifest. Its SHA-256 is `bf50c58203f8dbf325085a31fb5475f59b51a9c020984dde86b3ccfac2cdd86b`;
  it predates the current workspace candidate and is therefore not byte-identical to it.
- Real-TV testing: installation, connection, 4K/HDR playback, and smoother playback were confirmed
  by the owner. The 0.1.6 Dolby Vision color correction and automatic surround need the final TV check.
- The historical 0.1.34 candidate was installed on a Hisense Android 10 TV and an Amazon
  Fire TV Android 9 box. On the Fire TV, a controlled 15-minute H.264/AAC stream reached natural end
  at 1.0× with no midstream underrun, watchdog, audio fallback, decoder failure, or fatal event. A
  following 12-command rapid-seek burst converged to the final 4:00 target and returned to ready at
  1.0×. Two Android media underrun counters occurred only during the first 160 ms of decoder startup;
  this evidence does not generalize to every codec, source, HDMI route, or receiver model.
- The intermediate workspace 0.1.35 APK was installed on Fire TV `.124` (AFTDCT31/API 28). A
  415,823-byte settings document with 820 synthetic addon sources staged successfully, focused Save
  automatically, committed a 559,163-byte Keystore-backed preference document, and returned
  `approved` in under one second without an ANR or Keystore failure. Clear confirmation and the
  post-clear Pair & Sync rail also received focus automatically. The synthetic setup was then
  cleared; the receiver was left Not configured for the owner's real IPA transfer.
- The historical exact workspace 0.1.36 APK was installed on the same Fire TV after the owner saved
  the real phone setup. Tamil MV → Review Addons & Catalogs opened the Addons destination directly;
  Addons → Keys → Metadata → Playback → About → Close all scrolled into view and accepted D-pad
  focus. Open Pair & Sync, Back, and Close restored the exact Tamil MV or Jobs origin rather than
  resetting to My Stuff. The real 42,251-byte encrypted receiver-settings preference remained
  present, and logcat contained no ANR, fatal exception, oversized transaction, or settings-save
  failure during the traversal.
- The exact workspace 0.1.38 APK above was installed over that build on the same Fire TV. D-pad
  traversal proved the left-rail icons, `TAMI` four-letter fallback, Tamil MV Refresh, entry into
  Settings, complete Settings scrolling, and exact Back restoration. The real 42,251-byte encrypted
  settings preference remained present. The poster source picker is implemented and automated-test
  covered, but physical playback awaits one pairing from the matching catalog-transfer IPA because
  the older receiver setup contains no catalog snapshot.
- The final rebuilt 0.1.38 APK above was installed again after the startup-route and durable-cache
  corrections. The configured receiver opened the My Stuff lobby with its existing posters; the
  42,251-byte encrypted settings preference survived. The unconfigured Pair & Sync default is covered
  by `TVLibrarySurfacePolicyTest` without clearing the owner's real setup on the physical box.
- Public 0.1.34 APK: built and signature-verified. It uses Android's debug
  certificate (`fe937c68…d43205`), the same key every hosted build has used, so it installs over the
  top and no box loses its receiver UUID or settings.
- Distribution: fixed v0.1.34 URL and digest above. A future release uses a new tag and updates every
  installer/test/documentation pin deliberately.

## What 4789 TV is

4789 TV is a standalone app that will act as a receiver on an Android TV, Fire TV,
Google TV, or NVIDIA Shield. You open the app on the television, then choose it from
the 4789 iPhone app. The television handles playback while the iPhone sends the
remote-control commands.

The receiver makes a best-effort attempt to reopen after a TV restart or app update. Android TV
may block that background launch on some models, so opening 4789 TV manually remains the fallback.
The receiver advertises itself only while the app is visible and its video surface is ready.

## What works in code

- The standalone Android project exists.
- The receiver protocol and network transport are implemented.
- The player core is implemented.
- The TV launcher screen, ready screen, video surface, and local discovery wiring are implemented.
- The receiver holds Android's Wi-Fi multicast lock while the visible receiver advertises itself;
  this is required for reliable NSD/mDNS discovery on Android 12 and older Sony TV software.
- The Ready screen shows the TV's IPv4 address and exposes a first-party-only
  identity response so the matching iPhone build can find it by bounded same-subnet probing when
  the router or TV firmware still suppresses Bonjour.
- Playback currently uses the Media3/Exo path on every supported brand. Its FFmpeg extension supplies
  software audio decoders while video remains on the platform decoder. libmpv is neither advertised
  nor selectable in this artifact because its FFmpeg libraries conflict with the packaged extension
  under identical SONAMEs.
- Playback uses bounded cache buffering, exact content-frame-rate hints when metadata is available,
  GPU colorspace signaling for HDR, and automatic route-aware stereo/5.1/7.1 audio. Receiver
  diagnostics are retained in private storage for ADB inspection; the on-screen diagnostic values
  are intentionally not treated as a release health signal until they are populated through a
  non-blocking path.
- Standard D-pad and media keys work across Android TV, Google TV, Fire TV, and compatible boxes;
  there is no Insignia-specific key or model check. TV-side actions publish the same receiver state
  consumed by the iPhone, so either side can pause, resume, seek, or stop the current session.
- The workspace receiver has a Compose-for-TV Settings surface in the existing graphite/cyan theme.
  It can import the versioned iPhone settings export through a one-use encrypted QR invitation or
  the Android document picker, show a value-redacted receipt, and persist only TV-compatible fields
  in an Android Keystore AES-GCM document. Optional foreground phone sync is receiver-bound,
  revisioned, and independently revocable; disconnect keeps the imported settings.
- The ready screen now opens into a D-pad-first My Stuff lobby with an expanding collection rail,
  focused hero, 2:3 high-resolution Continue Watching and Tamil MV posters, Jobs, and Settings.
  Continue Watching is live receiver data; Tamil MV consumes the approved encrypted phone snapshot
  and opens configured addon sources from a poster. The same encrypted snapshot carries every
  enabled Letterboxd list as a separate bounded shelf, with a fair per-shelf poster depth chosen to
  keep the whole transfer below 2 MiB; a configured private-catalog token remains the full server
  refresh path. Watchlist and other provider folders remain explicitly pending until their TV-local
  adapters are implemented. The visible Compose
  surface exclusively owns focus, so Settings and library actions cannot activate hidden home cards.
- Left/Right seek uses a compact bottom timeline with optimistic, coalesced ten-second movement. The
  former top seek badge is gone, delayed/out-of-order snapshots cannot flash the elapsed clock to
  zero, and Stop resets the clock before replay of the same URL.
- Version 0.1.6 identifies manually entered 4789 receivers by direct RPC, so the complete subtitle
  style controls work even when the router suppresses Bonjour. Dolby Vision profiles use a
  color-safe SDR compatibility path instead of displaying the raw pink/green DV base layer.
- Version 0.1.7 keeps that receiver behavior while using Fire OS 7's required API-28 target behavior
  so the Activity can launch on Insignia Fire TV devices.
- Version 0.1.16 uses ports 8791/9791 for every 4789 TV install (0.1.9–0.1.15 used 8090/9091,
  which Kodi occupies on Fire OS because Amazon holds 8080). Different televisions can reuse
  these fixed ports because each television has its own IP address.
- Automatic audio preserves EAC3/TrueHD Atmos-class bitstreams only when Android reports direct
  support on the active TV/ARC/eARC/AVR route; otherwise playback safely falls back to decoded PCM.
- Continue Watching resumes remote and downloaded files. The paired iPhone app remembers each
  title/episode's audio track, subtitle track or external subtitle, subtitle on/off state, and the
  shared subtitle font/color/size/position.
- Audio and subtitle tracks can be listed and selected from the iPhone. External HTTP(S)
  subtitles and the iPhone subtitle appearance payload are supported.
- Speaker-profile requests remain compatibility hints: Android's AudioTrack/HDMI route decides the
  real output, so ARC/eARC passthrough must be checked on the actual TV and sound system.
- A boot/update receiver requests best-effort relaunch. It cannot power on a fully-off television
  or override Android's background-launch policy.

The 0.1.9 receiver startup, HTTP identity response, and both listening ports were also verified over
ADB on an Insignia Fire TV model AFTDCT31.

## What still must be tested on a real TV

- Install the current workspace 0.1.41 candidate on supported TV classes beyond the physically
  verified Hisense Android 10 and Amazon Fire TV Android 9 boxes before publishing it; the
  compatibility URL currently serves the earlier public 0.1.34 bytes identified above.
- Confirm that the iPhone discovers the receiver only when the app is visible and ready.
- Test starting playback and the remote controls, including pause, resume, seek,
  speed, volume, stop, and progress updates.
- Validate Settings focus/back behavior, QR legibility and expiry, file import, receipt rejection,
  Keystore survival across process restart, trusted-sync disconnect, and rapid seek/timer behavior
  on Fire TV, Google TV, and Android TV hardware. These workspace features are not in the pinned
  public 0.1.34 APK until a new immutable release is built and published.
- Restart the TV and check whether 4789 TV reopens; open it manually if the TV blocks auto-start.
- Replace the public test APK with a release-signed build and test its Downloader shortcut.
- Verify actual Einthusan credentialed VOD, signed CDN URLs, header-sensitive sources, raw HLS with
  authenticated segments, and sustained 4K/HEVC/Dolby Vision playback on each supported receiver.

HDMI audio behavior, boot relaunch, subtitle rendering, and HDR/Dolby Vision output remain manual
gates. The current Exo path reports decoder capability and surfaces unsupported Dolby Vision instead
of claiming a universal fallback; it does not guarantee the TV's native Dolby Vision badge.
HDR10/HLG decoding remains device/codec dependent.
Play Store distribution additionally requires the owner's Play Console account and release key.

Published 0.1.34 APK SHA-256 (verified download, 2026-08-09):
`bf50c58203f8dbf325085a31fb5475f59b51a9c020984dde86b3ccfac2cdd86b`.

## Test installation with Downloader or a TV browser

The phone installer and public instructions use one immutable compatibility asset:

`https://github.com/4789-app/4789-tv/releases/download/v0.1.34/4789tv.apk`

It must be exactly 59,236,985 bytes with SHA-256
`bf50c58203f8dbf325085a31fb5475f59b51a9c020984dde86b3ccfac2cdd86b`. Do not replace an asset
behind an existing tag. A future TV build requires a new versioned tag and a deliberate update to
the phone installer's pinned URL, byte count, digest, tests, and trust documentation.

The current asset is debug-signed and has no verifiable build attestation. The hash proves exact-byte
integrity only; it does not establish source provenance or make the artifact Play-ready.

To install it:

1. Install and open **Downloader by AFTVnews** on the TV.
2. Enter the fixed v0.1.34 URL above.
3. Choose **Download**.
4. Choose **Install**, then **Open**.
5. Leave 4789 TV open on its ready screen before choosing it from the iPhone.

If Downloader shows a different size, delete its cached file and retry. Refuse the install unless the
download matches both the documented byte count and SHA-256.

## Export a crash log from the Fire TV

The diagnostic build writes `receiver-diagnostics.log` in private app storage and also leaves the
native crash details in Android's logcat. The Mac cannot pull these files unless the TV is connected
over ADB.

1. On the TV, enable **Developer Options → ADB Debugging** under **Settings → My Fire TV** and
   note the TV IP address under **About → Network**.
2. On the Mac, replace `TV_IP` below with that address and accept the authorization prompt on the TV:

```sh
adb connect TV_IP:5555
adb logcat -c
adb shell am force-stop com.fourseveneightnine.tv
adb shell monkey -p com.fourseveneightnine.tv 1
```

After the app crashes, run:

```sh
adb logcat -d -v threadtime -t 5000 > fire-tv-logcat.txt
adb shell run-as com.fourseveneightnine.tv cat files/receiver-diagnostics.log > fire-tv-startup.log
```

Send both `fire-tv-logcat.txt` and `fire-tv-startup.log`; the first captures native SIGSEGV/libmpv
crashes and the second identifies the last completed startup stage.

## Developer build on this Mac

From Terminal:

```sh
cd /path/to/4789TV
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
export ANDROID_HOME="/opt/homebrew/share/android-commandlinetools"
export ANDROID_SDK_ROOT="/opt/homebrew/share/android-commandlinetools"
export PATH="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin:/opt/homebrew/share/android-commandlinetools/platform-tools:/opt/homebrew/share/android-commandlinetools/cmdline-tools/latest/bin:$PATH"

./scripts/check-android-foundation.sh --clean
```

Expected QA artifacts:

- `phone/build/outputs/apk/debug/phone-debug.apk`
- `phone/build/outputs/bundle/debug/phone-debug.aab`
- `app/build/outputs/apk/debug/app-debug.apk`
- `tvplay/build/outputs/apk/debug/tvplay-debug.apk`
- `tvplay/build/outputs/bundle/debug/tvplay-debug.aab`

That command is the source/QA gate. A published artifact additionally requires release signing,
store metadata/policy gates, and physical-device validation.

# Installer paths

The preferred path is phone-native: in the 4789 iPhone app open **Settings → Install on a TV**.
Enter the TV's IP address, enable **Developer Options → ADB Debugging** on the TV, and accept the
one-time authorization prompt. The phone downloads the latest receiver APK and transfers it directly
over ADB. The phone and TV must be on the same local Wi-Fi; the Mac can be elsewhere, asleep, or
absent. The usual ADB port is `5555`.

The Mac tool remains available as a developer/fallback path:

`4789TV/installer/start.sh` opens a small window for putting the receiver onto a TV:
type the box's IP, press Check, press Install, watch the log. It is a local tool bound to
127.0.0.1 — it runs adb, so it must never be reachable from the LAN, and it ships with
nothing.

Preflight runs before any work: address valid → this Mac and the TV on the same /24 → the
ADB port actually open → ADB attached and trusted (the TV's on-screen prompt) → API ≥ 28 →
an APK exists. Each failure says what to do instead of failing late.

It can also fetch backup players onto the TV — Just Player, VLC, Kodi, Next Player —
each resolved live from the developer's own release channel (GitHub releases, get.videolan.org,
mirrors.kodi.tv), never an APK aggregator. It reads the TV's real ABI list first, so a 32-bit
box gets the 32-bit build, and it skips alpha/beta releases.

For Mac-side phone control, run `installer/public-link.sh`. The first run enables two macOS launchd
agents: one keeps the installer alive and one keeps the named Cloudflare Tunnel alive across
terminal exits, restarts, and Wi-Fi changes. It prints the stable Safari link at
`https://install.4789library.com/m?k=…`. Open that exact link in Safari, then use Share → Add to
Home Screen; delete an older shortcut first if it still opens with Safari's browser bar. The
Mac must remain awake and online, and the Mac—not necessarily the phone—must be on the same
local network as the TV because that fallback performs ADB from the Mac. The installer uses origin
port `14789` so it cannot collide with TBMM's port `4789`. Cloudflare is not involved in the
phone-native path.

Full device-specific installation and update guide: [INSTALL.md](INSTALL.md).
