# 4789 TV

## Release status

**4789 TV 0.1.24 is the public build. The asset behind Downloader code 6364713 is replaced in place,
so no new Downloader link is ever required.**

> **The Downloader code resolves to the `v0.1.0-test` release tag — NOT `v0.1.6-test`.** This README
> claimed `v0.1.6-test` until 2026-08-04, and a publish that trusted it uploaded to a release nothing
> points at: the TVs kept downloading the old 62MB asset while the upload "succeeded". Resolve
> `https://aftv.news/6364713` and read the target before publishing; do not trust this file.

- Standalone Android TV/Fire TV/Google TV/Shield project: present.
- Protocol, transport, and player core: implemented in code.
- Last verified JDK 17 gate: passed — `clean testDebugUnitTest lintDebug assembleDebug`.
- Unit tests, lint, and debug assembly pass at the 0.1.13 local release gate.
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
  handling. In the current 0.1.14 candidate, Stop keeps the idle engine and receiver endpoint
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
- 0.1.24 local APK: `app/build/outputs/apk/debug/app-debug.apk` (SHA-256
  `04b59f21be0f657ef255df49763a7098d233fd1e2864e266bb6081b933d463b9`). Published 2026-08-04; the byte-identical file is
  what Downloader code 6364713 serves.
- TV screen, network discovery, video surface, launcher icon, and TV banner: implemented.
- The public URL serves 0.1.24, verified by downloading it back and comparing its SHA-256 against
  the local build — not by trusting the upload's success message.
- Real-TV testing: installation, connection, 4K/HDR playback, and smoother playback were confirmed
  by the owner. The 0.1.6 Dolby Vision color correction and automatic surround need the final TV check.
- Public 0.1.24 APK: built and signature-verified at the path below. It uses Android's debug
  certificate (`fe937c68…d43205`), the same key every hosted build has used, so it installs over the
  top and no box loses its receiver UUID or settings.
- Downloader code: **6364713** (stable compatibility address, updated to each verified APK).

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
- Playback currently uses the proven software libmpv path on the tested Fire OS AFTDCT31. The
  bundled MediaCodec paths stalled before FILE_LOADED on that device, so hardware decode is not
  advertised as a universal fallback. Software playback follows the TextureView size, synchronizes
  video to audio, uses bounded cache settings, and drops late video frames when necessary.
- Playback uses bounded cache buffering, exact content-frame-rate hints when metadata is available,
  GPU colorspace signaling for HDR, and automatic route-aware stereo/5.1/7.1 audio. Receiver
  diagnostics are retained in private storage for ADB inspection; the on-screen diagnostic values
  are intentionally not treated as a release health signal until they are populated through a
  non-blocking path.
- Standard D-pad and media keys work across Android TV, Google TV, Fire TV, and compatible boxes;
  there is no Insignia-specific key or model check. TV-side actions publish the same receiver state
  consumed by the iPhone, so either side can pause, resume, seek, or stop the current session.
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

- Install the built test APK and open it on a real supported TV or streaming box. The public
  compatibility URL still serves 0.1.13; local 0.1.14 remains experimental.
- Confirm that the iPhone discovers the receiver only when the app is visible and ready.
- Test starting playback and the remote controls, including pause, resume, seek,
  speed, volume, stop, and progress updates.
- Restart the TV and check whether 4789 TV reopens; open it manually if the TV blocks auto-start.
- Replace the public test APK with a release-signed build and test its Downloader shortcut.
- Verify actual Einthusan credentialed VOD, signed CDN URLs, header-sensitive sources, raw HLS with
  authenticated segments, and sustained 4K/HEVC/Dolby Vision playback on each supported receiver.

HDMI audio behavior, boot relaunch, subtitle rendering, and HDR/Dolby Vision output remain manual
gates. Version 0.1.6 intentionally tone-maps
Dolby Vision to color-safe SDR on the current mpv Android path; it does not guarantee the TV's native
Dolby Vision badge. HDR10/HLG decoding remains enabled where the Sony/Android codec stack supports it.
Play Store distribution additionally requires the owner's Play Console account and release key.

Published 0.1.13 diagnostic APK SHA-256:
`3675cb2c18a58d2bd828a362bab03b585102c9cc8769042f6a9f2a2ea51e16d0`.

## Test installation with Downloader or a TV browser

The public debug-signed APK is hosted at the **`v0.1.0-test`** tag. The tag is historical and never
changes, so existing Downloader shortcuts keep working — only the asset behind it is replaced:

`https://github.com/sarantorus/4789-tv-downloads/releases/download/v0.1.0-test/app-debug.apk`

A `v0.1.6-test` release also exists in that repo and is NOT what the Downloader code serves. Publish
to `v0.1.0-test`.

To publish a new build:

```bash
gh release upload v0.1.0-test app/build/outputs/apk/debug/app-debug.apk \
  --repo sarantorus/4789-tv-downloads --clobber
# then WAIT for the CDN and verify the bytes, not the size:
curl -sL -o /tmp/live.apk "https://github.com/sarantorus/4789-tv-downloads/releases/download/v0.1.0-test/app-debug.apk"
shasum -a 256 /tmp/live.apk app/build/outputs/apk/debug/app-debug.apk   # must match
```

GitHub's CDN serves the previous asset for several minutes after a successful upload. The API
reporting the new size is not evidence that a TV will receive it.

To install it:

1. Install and open **Downloader by AFTVnews** on the TV.
2. Enter Downloader code **6364713** (or the public test URL above).
3. Choose **Download**.
4. Choose **Install**, then **Open**.
5. Leave 4789 TV open on its ready screen before choosing it from the iPhone.

Code **6364713** is a stable address whose asset is replaced in place, so it always serves the latest
verified APK even though Downloader displays the historical `v0.1.0-test` URL.

If Downloader shows the OLD size, it cached the previous file: open its **Files** screen, delete
`app-debug.apk`, then download again.

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
cd "<your clone>"
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
export ANDROID_HOME="/opt/homebrew/share/android-commandlinetools"
export ANDROID_SDK_ROOT="/opt/homebrew/share/android-commandlinetools"
export PATH="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin:/opt/homebrew/share/android-commandlinetools/platform-tools:/opt/homebrew/share/android-commandlinetools/cmdline-tools/latest/bin:$PATH"

./gradlew clean testDebugUnitTest assembleDebug
```

Expected debug APK:

`<your clone>/app/build/outputs/apk/debug/app-debug.apk`

That command is the release gate. The published APK must pass it and Android signature verification.

# Installer (Mac-side only)

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
