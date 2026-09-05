# SPEC — On-TV Player Installer + State-Preserving Handoff

> Summary: Put the player installer *on the television* (no Mac, no adb), auto-detect what is
> already installed, show a "ready to hand off" affordance under the TV player, and hand the
> stream over carrying position, subtitle, audio track and fit — then take the position back
> when the viewer returns.
> Tags: #tv #handoff #installer #player #spec
> Status: draft
> Created: 2026-08-04
> Sources: `4789TV/installer/serve.py`, `4789TV/app/src/main/java/com/fourseveneightnine/tv/player/ExternalPlayerIntentPolicy.kt`, `4789TV/app/src/main/java/com/fourseveneightnine/tv/protocol/RpcDispatcher.kt:364`, `4789iOS/Sources/FourSevenEightNineCore/KodiRPC.swift:389`

---

## 0. What already ships (do not re-build this)

Roughly 60% of the described feature exists today. This spec only adds the missing parts.

| Piece | Status | Evidence |
|---|---|---|
| Curated, source-verified player catalog (Just Player, VLC, Kodi, Next Player) | **Built** | `4789TV/installer/serve.py:40` — every entry resolves from the developer's own release channel or F-Droid, never an APK aggregator |
| ABI-aware APK resolution + version sort | **Built** | `serve.py:91` `resolve_player`, `serve.py:131` `version_key` |
| IP preflight: adb present, same subnet, port reachable, SDK ≥ 28 | **Built** | `serve.py:390` `preflight`, `serve.py:311` `local_ipv4s`, `serve.py:338` `port_open` |
| Install a player onto the box | **Built, Mac-side only** | `serve.py:644` `do_install_player` → `adb install -r` |
| Detect which players are installed on the TV | **Built (two ways)** | `serve.py:618` `do_players` (adb `pm list packages`); on-box `ExternalPlayerIntentPolicy.installedPlayers()` |
| Handoff RPC, phone → TV | **Built** | `X4789.GetExternalPlayers`, `X4789.OpenExternal` (`RpcDispatcher.kt:78`) |
| Per-player intent dialect (MX-style / VLC / URL-only) | **Built** | `ExternalPlayerIntentPolicy.HandoffDialect` |
| Carry **position** across the handoff | **Built** | `RpcDispatcher.kt:384` — phone playhead wins, receiver playhead is the fallback |
| Carry **subtitle** (URL + name, pre-enabled) | **Built** | `handoffExtras`, `subs`/`subs.name`/`subs.enable` |
| Carry **HTTP headers** (signed links keep working) | **Built** | `headers[]` extra |
| Tell the phone where the video went | **Built** | `X4789.OnExternalHandoff` → `KodiCastSession.swift:3185` |

**What is missing** is exactly the four things this spec covers: on-TV install, the readiness
affordance, audio-track + fit fidelity, and the return trip.

---

## 1. User story

> I'm on the couch. The DV remux won't play on this box. The TV says "Just Player would handle
> this — install it?" I press OK once. It downloads and installs. The handoff button under the
> player turns green. I press it, Just Player opens at 47:12 with my subtitle on and my audio
> track selected. I watch. I back out. The TV shows me at 1:02:30 in Continue Watching.

No Mac. No adb. No IP typed. No settings menu.

---

## 2. Gaps this spec closes

| # | Gap | Why it hurts |
|---|---|---|
| **G1** | Install requires a Mac running `serve.py` + adb over the network | The person on the couch cannot fix their own playback failure |
| **G2** | No visible handoff state — the viewer cannot tell whether a target exists before pressing | Pressing handoff with nothing installed is a dead end |
| **G3** | Audio track is not carried | A 5.1 English remux with a Telugu default track hands off to the wrong language |
| **G4** | Aspect/fit is not carried | Zoom/fit choice resets, worst on 2.39:1 on a 16:9 panel |
| **G5** | Position never comes **back** | The viewer's real progress is lost the moment they hand off; Continue Watching is stale |
| **G6** | `launch()` uses `FLAG_ACTIVITY_NEW_TASK` + `startActivity` | Structurally cannot receive a result — G5 is blocked on this |

---

## 3. Design

### P1 — On-TV installer

**The constraint that shapes everything:** an Android app cannot silently install an APK. It
needs `REQUEST_INSTALL_PACKAGES` and the system shows a confirmation dialog. That dialog is
D-pad navigable on Android TV and Fire TV, so "one click → confirm → installed" is achievable,
but *silent* install is not, and no spec should promise it.

Second constraint: Fire TV ships with **Apps from Unknown Sources OFF**. Until the viewer
enables it for 4789 TV in Settings → My Fire TV → Developer Options, every install attempt
fails. This is a one-time, per-device setup step and the UI must teach it rather than fail
opaquely.

Flow:

1. **Catalog** — port `serve.py`'s `PLAYERS` list into the app as a versioned JSON asset,
   `assets/players.json`, with the same "developer channel or F-Droid only" rule written into
   the file header. Ship it in the APK so the box works offline-ish; refresh opportunistically
   from the phone.
2. **Resolve** — the *phone* resolves the concrete APK URL for the box's ABI (it already has
   the network stack and the release-channel logic; the TV should not grow a GitHub API client).
   New RPC: `X4789.ResolvePlayerDownload`.
3. **Prompt** — when playback fails with a codec/DV taxonomy verdict
   (`PlaybackErrorTaxonomy`) and no capable player is installed, the existing error overlay
   grows one row: *"Install Just Player — handles Dolby Vision and DTS-HD"*.
4. **Download** — `DownloadManager` to app-private storage, progress on the overlay. Verify
   the SHA-256 recorded in `players.json`; a mismatch aborts and reports. **Never install an
   APK whose hash was not pinned at catalog-build time.**
5. **Install** — `PackageInstaller` session, or `ACTION_VIEW` on a `FileProvider` URI. The
   system dialog appears; the viewer confirms once.
6. **Confirm** — a `PACKAGE_ADDED` receiver flips the handoff affordance (P2) without a
   restart.

Manifest additions: `REQUEST_INSTALL_PACKAGES`. A `FileProvider` authority and paths XML.
`targetSdk` is 28 today, so package-visibility filtering does not apply and
`installedPlayers()` keeps working; **if `targetSdk` is ever raised to ≥ 30 this breaks
silently** and needs a `<queries>` block listing the five packages.

The Mac-side `serve.py` path stays. It is the recovery path for a box where unknown sources
cannot be enabled, and it is how the receiver itself gets installed in the first place.

### P2 — Handoff readiness affordance

One control under the TV player, three states:

| State | Look | Condition |
|---|---|---|
| Hidden | — | Nothing installed, and current playback is healthy |
| Amber "Install a player" | dim | Nothing installed, playback failed or is degraded |
| Green "Hand off to Just Player" | lit | ≥ 1 capable player installed |

"Capable" is not "installed" — rank by `HandoffDialect`: `MxStyle` > `Vlc` > `UrlOnly`, which
is the existing `KNOWN_PLAYERS` order. Prefer the target that preserves the most state, and
name it on the button so the viewer knows where they are going.

The same three states mirror to the phone in `X4789.GetReceiverInfo` (`externalPlayers` is
already in that payload, `RpcDispatcher.kt:330`) so the phone's cast bar can show the same
affordance.

### P3 — Carry more state

Extend `HandoffContext` and `OpenExternalParams` with two fields:

```kotlin
data class HandoffContext(
    val positionMillis: Long = 0,
    val subtitleURL: String? = null,
    val subtitleName: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val audioTrackIndex: Int? = null,     // NEW — 0-based stream index within the file
    val audioTrackLanguage: String? = null, // NEW — ISO-639 fallback when index is unreliable
    val fitMode: FitMode = FitMode.Default, // NEW — Default | Fit | Fill | Zoom
)
```

Wire mapping, per dialect. Only claim what the target actually reads:

| Field | Just Player / Next Player (MX) | VLC | Kodi / TiviMate |
|---|---|---|---|
| position | `position` (int ms) — **shipping** | `extra_position` (long ms) — **shipping** | — |
| subtitle | `subs` / `subs.name` / `subs.enable` — **shipping** | `subtitles_location` — **shipping** | — |
| headers | `headers` (String[] pairs) — **shipping** | — | — |
| **audio track** | `audio.index` / `audio.name` (MX-style) | — | — |
| **fit mode** | `decode_mode` is unrelated; **no documented API** | — | — |

Honest conclusion: **audio track is deliverable for the MX dialect only, and fit mode is not
deliverable at all** by intent extras. Do not ship a spec line that promises otherwise. The
correct handling for fit is to send the *stream's* aspect metadata in the title/URL and accept
that the target player applies its own default — and to say so in the UI copy: "subtitle,
position and audio track carry over; picture-fit resets."

`audioTrackIndex` must be the container stream index, not the mpv `aid`. mpv's `aid` counts
audio tracks from 1; MX-style `audio.index` counts *all* streams from 0. Convert at the mpv
edge, in `MpvReceiverController`, not at the intent edge.

### P4 — The return trip (the hard one)

Just Player and MX-style players return `Activity.RESULT_OK` with `position`, `duration` and
`end_by` extras — **but only to a caller that used `startActivityForResult`**. The current
implementation cannot receive that:

```kotlin
// ExternalPlayerIntentPolicy.launch — today
addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
context.startActivity(intent)
```

`FLAG_ACTIVITY_NEW_TASK` + a plain `startActivity` from a non-Activity context discards the
result contract entirely. G5 is blocked on G6.

Change:

1. `ExternalPlayerIntentPolicy` stops calling `startActivity`. It becomes pure — it builds the
   `Intent` and nothing else (`buildIntent` already is; `launch` is the offender). This also
   makes it unit-testable without Robolectric, matching how the rest of the `player` package
   is written.
2. `MainActivity` owns an `ActivityResultLauncher` registered with
   `ActivityResultContracts.StartActivityForResult()`. The RPC path routes the launch through
   the Activity, dropping `FLAG_ACTIVITY_NEW_TASK`.
3. On result: read `position` (ms) and `end_by`. Emit a new notification
   `X4789.OnExternalReturn { positionSeconds, durationSeconds, completed }`.
4. The phone folds that into Continue Watching using its existing resume-write path — the same
   one `ResumePoint` feeds.

Fallback when the target returns nothing (VLC, Kodi, TiviMate, or a user who force-quit):
`OnExternalReturn` is emitted with `positionSeconds = null`. The phone keeps the pre-handoff
position rather than writing a zero. **A missing result must never be written as "watched 0%".**

Ordering caveat: `dispatchOpenExternal` currently stops the receiver's own player immediately
after announcing the handoff (`RpcDispatcher.kt:430`). That stays — but the resume point must
be captured **before** the stop, because after the stop `controller.snapshot()` no longer has
a playhead. This is the same class of bug the two-way-sync comment at `RpcDispatcher.kt:406`
documents.

---

## 4. Protocol additions

All additive. Every field optional, so an old receiver and an old phone both degrade to
today's behaviour.

```
X4789.GetInstallablePlayers  → { players: [{ id, name, package, why, installed, capable }] }
X4789.ResolvePlayerDownload  { id, abi } → { url, sha256, version }   // phone resolves, TV consumes
X4789.InstallPlayer          { id, url, sha256 } → { started: bool }
X4789.OnPlayerInstallProgress  (notify) { id, phase, percent, error? }
X4789.OpenExternal           + audioTrackIndex, audioTrackLanguage   // extends existing
X4789.OnExternalReturn       (notify) { positionSeconds?, durationSeconds?, completed }
```

`X4789.InstallPlayer` returns `started`, not `installed` — the system dialog is the gate and
the receiver cannot know the outcome synchronously. `OnPlayerInstallProgress` with
`phase: "installed"` is the truth, driven by the `PACKAGE_ADDED` receiver.

---

## 5. Milestones

| # | Scope | Gate |
|---|---|---|
| M1 | P4 refactor: `launch` goes pure, `MainActivity` owns the result launcher, `OnExternalReturn` emitted | Unit tests on `buildIntent`; on-box: hand off to Just Player, back out at a known timestamp, assert the notification |
| M2 | P3 audio track through the MX dialect + `aid`→stream-index conversion | `ExternalPlayerIntentPolicyTest` extension; on-box: multi-audio MKV lands on the right track |
| M3 | P2 readiness affordance, three states, mirrored into `GetReceiverInfo` | Human eyes on the box (design-taste gate — no model judge) |
| M4 | P1 on-TV installer: catalog asset, resolve RPC, download + hash verify, `PackageInstaller`, unknown-sources teaching screen | On-box on **both** a Fire TV (unknown sources off by default) and a Google TV box |

M1 first because M3 and M4 are decoration on a handoff that still loses the viewer's progress.

Gate per `CLAUDE.md §4.1`: this is all `4789TV/` Kotlin, so the gate is the TV module's unit
tests plus on-box verification. The iOS Core additions to `KodiRPC.swift` bring
`swift run ContractCheck` into scope; `KodiRPC` is not a frozen wire DTO, so no
`contract-samples` refresh is required.

---

## 6. Risks

- **Unknown sources off** is the single most likely field failure, and it is invisible: the
  install just does not happen. The teaching screen is not optional polish.
- **Hash pinning vs. auto-update**: pinning SHA-256 in `players.json` means the catalog goes
  stale when a developer publishes a new release. Accept staleness — an unpinned install of a
  video player is a rootkit with a play button (`serve.py:36` already states this rule for the
  Mac path; the on-TV path must not be weaker).
- **`targetSdk` bump** silently breaks `installedPlayers()` via package-visibility filtering.
  Add a `<queries>` block pre-emptively even at `targetSdk 28`; it is inert and prevents a
  future regression.
- **Amazon store policy** does not apply — 4789 TV is sideloaded. If that ever changes, P1
  cannot ship as designed.

## 7. Non-goals

- Silent install. Not possible; not attempted.
- Uninstalling players from the TV.
- Handing off to a player on a *different* device (that is casting, already covered).
- Preserving picture-fit. No target player exposes it. Say so in the UI.
