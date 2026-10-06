# Wave 0 — the new TV client shell

Date: 2026-09-21. Module: `:app`. Channel built and tested: `sideload`.

Wave 0 replaces the old View shell with a Compose one. The receiver half keeps its behaviour. It
now lives beside the screen instead of inside it. The six top-level screens are frames with real
empty states. The next wave fills them.

---

## 1. What moved where

New code lives in `com.fourseveneightnine.tv.client`. No `:receiver-core` module was made, by the
orchestrator's decision. `protocol/`, `transport/`, `discovery/`, `player/`, `settings/`,
`startup/` and `catalog/` stay where they were.

| New file | What it holds |
|---|---|
| `client/App.kt` | `TvApplication`, which extends `ReceiverApplication` and adds `AppGraph`. |
| `client/AppGraph.kt` | Hand wiring for the whole app. Every field is lazy. |
| `client/playback/PlaybackSession.kt` | The one owner of `ReceiverController`. |
| `client/playback/ReceiverHost.kt` | Everything the old Activity did that was not drawing. |
| `client/receiver/ReceiverService.kt` | Foreground service that keeps the bound ports alive. |
| `client/data/RecentsStore.kt` | Moved from `ui/RecentsStore.kt`. Package changed, nothing else. |
| `client/ui/MainActivity.kt` | One Activity: the picture layer and the remote. |
| `client/ui/VideoStage.kt` | The `SurfaceView`, the aspect frame and the cue renderer. |
| `client/ui/ShellState.kt` | What the Compose tree has drawn, for D-pad routing. |
| `client/ui/AppRoot.kt` | Rail, navigation host and the focus rescue net. |
| `client/ui/theme/Tokens.kt` | Colours, type, shapes, space, geometry, motion, `TvTheme`. |
| `client/ui/components/Focus.kt` | Focus ring, focus scale, pivot rules. |
| `client/ui/components/Components.kt` | Cards, chips, badges, buttons, panels, dialog, toast, bars, skeletons, empty and error blocks. |
| `client/ui/components/TvKeyboard.kt` | The 6 by 7 key grid. |
| `client/ui/nav/Routes.kt` | Every route, and which six carry the rail. |
| `client/ui/nav/NavRail.kt` | The rail and the cast status chip. |
| `client/ui/screens/Screens.kt` | The frame and the five placeholder screens. |
| `client/ui/screens/settings/SettingsScreen.kt` | Hosts the existing `TVSettingsSurface`. |
| `client/ui/screens/player/PlayerScreen.kt` | The player, its chrome, its panels and its plates. |

These pieces moved out of `MainActivity.kt` and into `ReceiverHost.kt`. None changed behaviour.

- The `Surface` attach and its ready timeout.
- `ReceiverLifecyclePlanner`, and transport start and stop.
- DNS-SD advertising, only while a ready `Surface` is visible.
- The network monitor and the record re-publish.
- `EngineOverridePort` and `ExternalPlayerPort`.
- The media session.
- Auto frame rate, on both the modern and the legacy path.
- Resume points on the 15 second tick.
- The `X4789.SetRecents` mirror.
- The capability probe and the playback status queue.

These moved into `PlaybackSession.kt`: every route into playback, the origin of a play, the track
list, the snapshot, and the handoff to another app.

These moved into `VideoStage.kt`: the black letterbox backing, the aspect fit modes, and the
subtitle style from the `X4789.SubtitleStyle` sidechannel.

### Two deliberate departures from the brief

**1. The Activity owns the `SurfaceView`, not the player route.** The receiver advertises itself
only while a ready `Surface` is visible. A `Surface` created by the player route would mean no
`Surface` on Home. A phone could then not find this television from any other screen, and the
cold-open proof would fail. The video layer is therefore built once and never reparented.
Reparenting a `SurfaceView` destroys its `Surface`. `PlayerScreen` draws over that layer. It asks the layer for
the two things a player screen may set: the picture's fit, and where the subtitles sit.

**2. The rail is not drawn on Settings.** Settings still hosts `TVSettingsSurface`, the proven
pairing screen from the old shell. That screen draws edge to edge and has its own Close. Drawing
the rail over it puts two navigations on one screen. The rail returns to Settings when the next
wave rebuilds those pages as the two-pane layout in the plan's §6.10.

---

## 2. What was deleted

| Deleted | Lines | Why |
|---|---|---|
| `ui/MainActivity.kt` | 4,958 | Replaced by `client/ui/MainActivity.kt` plus `ReceiverHost`. |
| `ui/TVLibrarySurface.kt` | 3,056 | The old six-destination rail. Replaced by `NavRail`. |
| `ui/PlayerControlsView.kt` | 1,117 | Replaced by the Compose control bar. |
| `ui/EndedPlateView.kt` | 385 | Replaced by the Ended and Next Up plates. |
| `ui/OptionRailView.kt` | 338 | Replaced by `SidePanel`. |
| `ui/SlimSeekBar.kt` | 216 | Replaced by the Compose seek track. |
| `ui/PlaybackArtwork.kt` | 68 | Only the old waiting screen used the blurred extension. |
| `ui/paste/PasteUrlDialog.kt`, `ui/paste/PasteUrlPolicy.kt` | 168 | Search owns finding a title from the next wave on. |
| `tvplay/` | whole module | Replaced by the `play` product flavour on `:app`. |

Two test files went with the code they tested.

- `ui/TVLibrarySurfacePolicyTest.kt` tested `TVLibrarySurfacePolicy`, which lived in
  `TVLibrarySurface.kt`. Its one still-useful case, `refreshedFocusIndex`, exists identically on
  `TVReceiverPresentationPolicy`. It now runs against that.
- `ui/paste/PasteUrlPolicyTest.kt` tested the deleted paste dialog.

Two kept policy files were trimmed, not rewritten.

- `TVReceiverPresentationPolicy` lost `HomeNavigationState`, `selectDestination` and `moveDown`.
  All three modelled the old six-destination rail. All three named the deleted
  `TVLibraryDestination`. One test case went with them.
- `TvTokens.Type` now names Inter. `TvTokens.accentFor` went with the deleted enum.

Fonts: Bricolage Grotesque and Figtree are gone, with their licence files. Inter Bold and SemiBold
were copied from the iOS resources. Inter Regular and Medium came from the Inter 4.1 release on
GitHub under the OFL. `licenses/OFL-Inter.txt` ships in both channels.

`PlayerInstaller` no longer takes the Activity class. It takes an `Activity`, a scope and two
callbacks. Both versions still present the same shape, and the Play dex still has no download
hosts in it.

---

## 3. The API the next wave builds on

### `AppGraph` — `com.fourseveneightnine.tv.client.AppGraph`

Built once in `TvApplication`, reached with `context.appGraph`. Every field is lazy. A television
process is created far more often than it is used.

- `settingsStore: EncryptedTVSettingsStore`, `catalogCache: AtomicTVTamilMVCatalogCache`
- `deviceIdentity: ReceiverDeviceIdentity`, `advertiser: NsdAdvertiser`
- `networkMonitor: ReceiverNetworkMonitor`, `pairing: TVSettingsPairingCoordinator`
- `resumeStore: ResumePointStore`, `recentsStore: RecentsStore`
- `artworkLoader: ArtworkLoader`, `enginePreferences: SharedPreferences`
- `presentationPreferences: SharedPreferences`, `controller: SwappableReceiverController`
- `playback: PlaybackSession`

### `PlaybackSession` — `com.fourseveneightnine.tv.client.playback.PlaybackSession`

It implements `ReceiverController` by delegating to the engine proxy, so `RpcDispatcher` holds
this object. A phone `Player.Open` lands on `open(request)` and is recorded as `Origin.Phone`. The
dispatcher never needs to know that origins exist.

State, all read-only:

- `uiScope: CoroutineScope`
- `origin: StateFlow<Origin?>`, with `enum class Origin { Phone, Local }`
- `phase: StateFlow<ReceiverPlaybackPhase>`
- `snapshot: StateFlow<ReceiverSnapshot>`
- `tracks: StateFlow<ReceiverTracks>`
- `diagnosticsState: StateFlow<PlaybackDiagnostics>`
- `surfaceReadyState: StateFlow<Boolean>`
- `nowPlayingArt: StateFlow<NowPlayingArt?>`
- `cues: StateFlow<List<Cue>>`, `videoAspectRatio: StateFlow<Float>`,
  `subtitleStyle: StateFlow<ReceiverSubtitleStyle>`
- `openRequests: SharedFlow<Origin>` — one per accepted open. The shell navigates on it.

Commands:

- `suspend fun open(request: OpenMediaRequest): Result<Unit>` — the wire path. Records `Origin.Phone`.
- `suspend fun open(request: OpenMediaRequest, origin: Origin): Result<Unit>`
- `suspend fun stop(): Result<Unit>`
- `fun attachSurfaceHolder(holder: SurfaceHolder, width: Int, height: Int)`, `fun detachSurface()`
- `suspend fun refreshSnapshot(): ReceiverSnapshot`, `suspend fun refreshTracks(): ReceiverTracks`
- `suspend fun togglePlayPause(): Result<Int>`
- `suspend fun seekTo(positionMillis: Long): Result<Unit>`
- `suspend fun seekRelative(offsetSeconds: Double): Result<Unit>`
- `suspend fun setSpeedMultiplier(speed: Float): Result<Float>`
- `suspend fun selectAudioTrack(index: Int): Result<Unit>`
- `suspend fun selectSubtitleTrack(selection: SubtitleSelection): Result<Unit>`
- `fun setUpscale(mode: UpscaleMode)`
- `fun installedPlayers(): List<InstalledExternalPlayer>` — ranked by dialect. Call it off the main thread.
- `suspend fun externalHandoff(player: InstalledExternalPlayer): Boolean`

To start a title from a card, call `open(request, Origin.Local)`. The shell navigates to the
player by itself.

### `ReceiverHost` — `com.fourseveneightnine.tv.client.playback.ReceiverHost`

Activity scoped. It exposes `state: StateFlow<ReceiverState>`, `settingsConfigured`,
`settingsLoaded`, `resumeOffer`, `receiverName`, `autoFrameRate` (read and write) and
`notifyLocalControl(action)`. It takes the lifecycle calls `onStart`, `onStop`, `onDestroy` and
`onTrimMemory`, plus the three `Surface` callbacks.

### `ShellState` — `com.fourseveneightnine.tv.client.ui.ShellState`

`playerVisible`, `chromeVisible`, `overlayVisible`, `playerCommands`, `openRail`,
`restoreContentFocus`, and the derived `playbackOwnsDpad`. A screen sets what it has drawn. The
Activity reads this and nothing else to decide who owns the D-pad.

---

## 4. Gate

```
cd "/Users/saranpenna/4789 iOS/4789TV" && JAVA_HOME=/opt/homebrew/opt/openjdk@17 \
  ./gradlew :app:testSideloadDebugUnitTest :app:lintSideloadDebug :app:assembleSideloadDebug
```

BUILD SUCCESSFUL. 384 unit tests, 0 failures, 0 errors, 0 skipped. Lint clean, including
`SuspiciousIndentation`, which is an error in this build.

Sixteen test cases were removed, and only those. `TVLibrarySurfacePolicyTest` held 4 and
`PasteUrlPolicyTest` held 11; both went with the code they tested. One case inside
`TVReceiverPresentationPolicyTest` went with the old rail model. Nothing else in the suite
changed.

`:app:assemblePlayDebug` also builds. The Play APK is 64 MB against the sideload APK's 85 MB. The
per-variant packaging rule keeps the mpv native files out of it.

---

## 5. On the box

Den TV, onn 4K Pro, `192.168.4.22:5555`, Android 14, 32-bit only, display forced to 1920 x 1080.

| Check | Result |
|---|---|
| Install and launch | `com.fourseveneightnine.tv/.client.ui.MainActivity` resumed, no crash |
| DNS-SD while Home shows | `_xbmc-jsonrpc-h._tcp` advertised, port 8791 |
| `X4789.GetReceiverInfo` while Home shows | answers, with the full codec profile |
| `JSONRPC.Ping` | `pong` |
| `Player.Open` from the wire alone | navigates to the player and plays |
| Picture after 8 s | video, chrome hidden, "iPhone is driving" chip top left |
| `Player.GetProperties` time and speed | 6.0 s then 10.1 s, speed 1 |
| `exo.firstFrameRendered` in the diagnostics log | present, generation 1 |
| `Player.Stop` | the Stopped plate, with the mark, the receiver name and the IP |
| BACK from the plate | Home |
| LEFT on Home | the rail opens to 376 px, Home focused, labels shown |
| RIGHT | the rail closes and focus returns to the content |
| Three screencaps 2 s apart while idle | identical md5 `55cd4b2013afae9275360873f0d50708` |
| Pair and Sync from the rail | opens, shows CONNECTED, the panes take focus |

Frame numbers, from `dumpsys gfxinfo`:

| Sweep | Frames | Janky | 50th | 90th | Missed vsync |
|---|---|---|---|---|---|
| 30 focus moves down and up the open rail | 30 | 30 (100%) | 32 ms | 34 ms | 0 |
| 15 rail open and close cycles | 291 | 145 (50%) | 40 ms | 125 ms | 57 |

Read the first row with care. Thirty key presses produced exactly thirty frames. That is the best
possible count: no frame is wasted. Each of those frames costs about 32 ms, so `gfxinfo` calls
every one of them janky. The GPU sits at 2 to 3 ms, so the cost is on the UI thread.

The second row improved during this run. The width animation was scoped to one background node.
The logo, the six items, the divider and the cast chip no longer re-lay-out every frame. That
moved the sweep from 74% janky at a 65 ms median to 50% at 40 ms. It is still over budget. The
plan's §9 targets are measured against real rows on the AFTDCT31 in the P4 pass. This belongs
there.

---

## 6. Four defects found and fixed on the box

Each of these was found by running the app, not by reading it.

1. **The confirm dialog was a focus trap.** `TvDialog` drew two buttons and focused neither. OK
   did nothing and only BACK could leave. The safe choice now takes focus on entry, which spec
   §15.2 asks for anyway.
2. **A new open kept the last title's chrome.** A "Stop the film?" dialog raised over one title
   was still up over the next one. A new media URL now clears the panel, the dialog, the bar and
   the seek badge.
3. **Stopped never drew.** `player.stop()` sets the phase to Stopped. media3 then delivers
   `STATE_IDLE` on the same thread, and the engine's idle handler overwrites Stopped with Idle a
   moment later. Stopped is a value that exists for one instant. The plate now follows what the
   viewer sees: once a title has left Idle, Idle means it stopped.
4. **Going Home from the player did nothing.** `popUpTo(startDestination) { saveState = true }`
   with `restoreState = true` saves the popped stack under the destination it popped to. It
   restores that stack on the way in. Popping the player and then navigating to Home therefore put
   the player back on top. The log said `nav.go to=home from=player` while the Stopped plate stayed up.
   Top-level moves now use a plain `popUpTo(home)`.

One more, found the same way. **Compose runs its directional focus search on the key down event.**
A LEFT handler that waited for key up had already lost. Focus jumped into the rail's nearest item
by geometry, and the rail never opened. Directions are now consumed on key down in the screen
frame, the rail items and the side panel.

---

## 7. Open issues

These are known and left for the next waves.

1. **Rail frame cost.** About 32 ms per focus move, and 40 ms per frame across the open and close
   tween. The GPU is at 2 to 3 ms. It needs a trace, which is the P4 pass.
2. **The control bar has no Sources pill.** No stream list exists until Wave 2. A pill that opens
   nothing is worse than one that is missing. The Next pill is present and works, because the
   phone sends the next episode on the open request.
3. **The Next Up plate is not built.** Ended with a next episode draws the Ended plate with a
   "Next episode" button, not the countdown card in spec §11.7.
4. **The resume crumb is written but never offered.** `ReceiverHost.resumeOffer` loads on start
   and is exposed. Nothing draws it. The new spec has no resume screen, so the offer needs a home.
5. **The error plate leads with "Try again", not "Try another source".** No source list exists
   yet. The taxonomy sentence and the external player buttons follow the spec.
6. **`TvKeyboard` and `PivotSpec` are written but not yet used.** Search and the browse rows
   arrive in Waves 1 and 3.
7. **Pairing could only be checked in its paired state.** The Den TV is the owner's paired
   receiver. Clearing its setup to watch a QR code appear would have destroyed the stored keys, so
   the invitation path was not re-run. The code is the unchanged `TVSettingsPairingCoordinator`
   and `TVSettingsSurface`.
8. **Nearby, not ours, not touched.** `ExoReceiverController` treats any media of 60 seconds or
   less as a debrid error card and raises an error plate over it. A real 52 second trailer played
   fine and still got the plate. That rule wants a second signal.
9. **`app/src/main/assets/Jost-Variable.ttf`** is still mounted through the iOS font directory and
   nothing uses it. It is a font asset, not code, so it was left alone.
