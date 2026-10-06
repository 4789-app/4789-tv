# Wave 2 — Detail, Streams and the local play flow

Date: 2026-09-21. Module: `:app`. Channel built and tested: `sideload`.

This wave fills two screens and the path between them. Detail (spec §9) and Streams (spec §10) are
real. `PlayFlow` is no longer a stub. A Play press now searches every add-on and ranks the rows. It
auto-picks when the owner's rules allow it. It resolves the pick through TorBox or Real-Debrid,
opens the player, and writes Continue Watching while the title runs.

---

## 1. Files

### New

| File | What it holds |
|---|---|
| `client/playback/HardwareCodecs.kt` | `names()`: the box's own hardware video decoders, lower case, read once per process. |
| `client/playback/NextUp.kt` | `NextUpPlan`, and which episode comes next by season and episode order. |
| `client/playback/PlayPlanner.kt` | The two decisions a Play press makes, with no Android in them. |
| `client/playback/ProgressRecorder.kt` | The 15 second write schedule, plus `ProgressSink` and its library adapter. |
| `client/ui/screens/detail/DetailViewModel.kt` | Detail's state, the Play target rules and every string built from a number. |
| `client/ui/screens/detail/EpisodesRow.kt` | Seasons chips and the episode cards. |
| `client/ui/screens/detail/CastRow.kt` | The cast rail. |
| `client/ui/screens/detail/MoreLikeThis.kt` | The similar-titles rail. |
| `client/ui/screens/streams/StreamsViewModel.kt` | The search, the filters, the count chip and the insertion order. |
| `client/ui/screens/streams/StreamRow.kt` | One 960 × 104 source row. |
| `client/ui/screens/streams/ReasonPane.kt` | The right-hand readout. |

### Changed

| File | Change |
|---|---|
| `client/playback/PlayFlow.kt` | `StubPlayFlow` replaced by `DefaultPlayFlow`. `PlayRequest` and `PlayResult` are untouched. |
| `client/ClientGraph.kt` | One line: `DefaultPlayFlow(this, graph)` in place of `StubPlayFlow()`. |
| `client/ui/screens/detail/DetailScreen.kt` | The placeholder became the screen. |
| `client/ui/screens/streams/StreamsScreen.kt` | The placeholder became the screen. |
| `client/ui/screens/player/PlayerScreen.kt` | A Sources pill, a Next Up plate and a local Next pill. Nothing else changed. |
| `client/ui/components/Components.kt` | Appended `TvFocusableWithMenu` at the end of the file. No existing composable was touched. |

`PlayFlow` gained three members, all with defaults, so `StubPlayFlow` and every existing caller
still compile unchanged:

```kotlin
suspend fun playRow(row: StreamRow, request: PlayRequest): PlayResult = PlayResult.ShowList
val nextUp: StateFlow<NextUpPlan?> get() = noNextUp
suspend fun playNextUp(): PlayResult = PlayResult.ShowList
```

---

## 2. The hook AppRoot must wire

`PlayerScreen` has no `ClientNav`, so it cannot open the source list by itself. It takes one new
parameter, defaulted to null:

```kotlin
onOpenSources: (() -> Unit)? = null,
```

While it is null the Sources pill is **missing**, not disabled. Wire it in `AppRoot.kt` like this:

```kotlin
composable(Route.Player.path) {
    PlayerScreen(
        session = session,
        // … every existing argument, unchanged …
        onLeave = { navController.navigateTop(Route.Home.path) },
        onOpenSources = {
            val media = graph.playback.lastOpenMedia() ?: return@PlayerScreen
            // The title now playing. `ClientGraph.playFlow.nextUp` names the show for a series;
            // for a film the orchestrator needs the route args it opened the player from.
            nav.openStreams(type, id, season, episode)
        },
    )
}
```

The shell owns which title is playing, so it supplies `type`, `id`, `season` and `episode`. Nothing
in this wave needs changing for that.

---

## 3. What each piece does

### `DefaultPlayFlow`

`play(request)` waits up to 3 seconds for the settings services, then:

1. `forceList` returns `ShowList` at once. The Streams screen runs its own search, so searching
   here first would only cost the add-ons a second round of calls.
2. `StreamSearch.search(...)` is collected. Every published state is ranked with `StreamRanker` and
   offered to `AutoPick`.
3. The first state that produces a `PlayBest` cancels the search. A box with eleven sources in hand
   starts on the eleventh rather than waiting out the slowest add-on's 12 second deadline. Every
   "show the list" answer before the search is done is provisional, so only a commit ends it.
4. `DebridResolver.resolve(row, season, episode)`, then `PlaybackSession.open(request, Origin.Local)`.
5. `LibraryRepository.recordLocalPlay(...)`, then a `ProgressRecorder` job for this title.

`playRow(row, request)` skips steps 2 and 3. The Streams screen calls it when a viewer picked a row.

Failure sentences come from `client-data/API-A.md` word for word:

| Outcome | Sentence |
|---|---|
| No settings after 3 s | Pair your iPhone first. |
| `MissingApiKey` | Add a debrid key in Settings. |
| `NotCached` | That copy is not on your debrid account yet. |
| `Stale`, `Error`, a failed open | That source did not answer. Try another. |

A resolved URL goes into one `OpenMediaRequest` and nowhere else. No log line, no Room row, no
diagnostics field.

### `ProgressRecorder`

It follows one local playback until it ends. A 15 second tick writes progress; pause, stop and end
write at once and are never dropped. At 95% it calls `markWatched` instead, so a finished title
leaves Continue Watching. It writes only while the origin is Local, because a phone cast already
mirrors its own position through `X4789.SetRecents` and a local row would win the merge.

**A bug the tests caught.** The interval gate started from `Long.MIN_VALUE`, so `now - lastWriteAt`
overflowed to a negative number and the first write of every title was dropped. It is now a
nullable field.

### `NextUp`

Order comes from the season and episode numbers, never from the order an add-on listed its videos
in: Cinemeta interleaves specials and TMDB puts them last, so following the array lands on a
special in the middle of a season. Specials are never "next". The last episode of the last season
returns null.

`OpenMediaRequest.nextUp` needs a URL, and a local next episode has no URL yet — spec §11.7 says its
sources resolve while the plate counts down. So the plan rides on `PlayFlow.nextUp` instead, and the
phone's own `nextUp` field keeps working exactly as it did.

### Detail

Spec §9: full-bleed backdrop with both gradients, a 720 px left column with logo or title, meta
line, ratings chips in the fixed IMDb / Trakt / TMDB order, a four-line synopsis that opens a panel
when it is truncated, the action row Play / Sources / Trailer / More, the hint line under it, the
480 × 720 poster, seasons chips, episodes, cast and More Like This.

Play on a series resolves in the order §9.6 gives: the episode in progress, else the next unwatched
one, else season 1 episode 1. The button says which: "Resume 1h 12m in", "Play S1 E1", "Resume S2 E4".

The Trailer button is missing when there is no YouTube id, and missing again when no app on the box
answers the intent. A disabled button is a small lie about what the screen can do.

### Streams

Spec §10: filter chips (All, Cached, 4K, 1080p, up to three languages from the results, Other), the
live count chip, 960 × 104 rows keyed by row id, the ring and a fill change for focus with no scale,
and the right pane reading `RankedRow.reasons` and `StreamRanker.facts`.

New rows land below the focused row and nothing on screen moves. A re-sort that would lift a row
above the focused one is held until focus leaves the list, and then it lands in one step with no
animation.

---

## 4. Focus rules

- `focusRestorer()` on every rail and on the source list.
- No `FocusRequester` inside any lazy item. Detail's initial focus is the Play button; Streams' is
  the "All" chip, and it moves into the list once, only after rows exist.
- Detail is a `verticalScroll` column, not a `LazyColumn`. It has at most four rails, and the Play
  button must hold the screen's one requester. A requester inside a lazy item detaches when that
  item scrolls out of view, which is the fault in `tv-dpad-focus-destroyed-by-loading-shelf.md`. The
  rails themselves are still `LazyRow`s, which is where the item counts are.
- `key()` on every item. `@Immutable` on every UI model.

---

## 5. Gate

```
cd "/Users/saranpenna/4789 iOS/4789TV" && JAVA_HOME=/opt/homebrew/opt/openjdk@17 \
  ./gradlew :app:testSideloadDebugUnitTest :app:lintSideloadDebug :app:assembleSideloadDebug
```

BUILD SUCCESSFUL. 536 unit tests, 0 failures, 0 errors. Lint clean, `SuspiciousIndentation`
included.

56 of those tests are new:

| File | Tests | Covers |
|---|---|---|
| `playback/PlayPlannerTest.kt` | 6 | Commit, refuse, too few sources, early cancel, the failure sentences |
| `playback/NextUpTest.kt` | 9 | Mid-season, season boundary, last episode, specials, array order, a film |
| `playback/ProgressRecorderTest.kt` | 8 | The 15 s schedule with a fake clock, pause and stop, 95%, a new title |
| `ui/screens/detail/DetailPlayTest.kt` | 16 | Play target and label, watched tick, progress bar, every format rule |
| `ui/screens/streams/StreamsRulesTest.kt` | 17 | Filters, the three-language cap, insertion order, the count chip |

---

## 6. On the Den TV

onn 4K Pro, `192.168.4.22:5555`, paired, 11 add-ons.

**Verified on the box:**

| Check | Result |
|---|---|
| Install and launch | resumed, no crash, no `FATAL EXCEPTION` in the log |
| Detail opens from the Discover grid | yes |
| Hero | logo artwork, "2026 · 1h 40m · Action, Adventure, Mystery · PG-13", 480 × 720 poster |
| Ratings row | IMDb 6.3, Trakt 67%, TMDB 70% — live from MDBList, in the fixed order |
| Action row | Play in orange, then Sources, Trailer, More, with the hint line under them |
| Trailer button | present, so the YouTube intent resolves on this box |
| Cast rail | real photos, names and roles; OK fires |
| Detail loading state | hero blocks and a rail of skeleton cards |
| Focus ring and D-pad movement between rails | yes |

Screenshots: `d-home.png`, `d-detail-movie.png`, `d-streams.png` (Detail's loading state),
`d-start.png`, `d-detail2.png` in
`/private/tmp/claude-501/-Users-saranpenna-4789-iOS/4a928fab-f5fe-4aa4-8353-f04625051500/scratchpad/`.

**Not verified on the box, and why:** the Streams screen, the auto-pick and resolve path, the Next
Up plate and the progress writes. The Den TV is shared with three other agents. It was reinstalled
under this session four times during the run, and it is now playing Google TV live content, so the
screen belongs to something else. Driving it further would have taken the picture off someone. These
four need a device pass on a box nobody else is using.

---

## 7. Open issues

1. **Per-episode watched history does not exist.** `watch_progress` holds one row per title
   (`API-B.md`), so the episode ticks and the "next unwatched" rule can only read the last episode
   the box played. An episode watched three weeks ago shows no tick. A per-episode table is a
   library change, not a screen change.
2. **The Next Up plate only appears at Ended.** Spec §11.7 also asks for it at 30 seconds left.
   Reading the playhead is a socket round trip on the mpv path, and this app deliberately polls it
   only while the control bar is up. A 30 second plate needs a cheap remaining-time signal from the
   engine.
3. **The auto-pick card says "Checking your add-ons", not "AIOStreams · 1080p · cached".**
   `AutoPick` publishes its choice only when it commits, so there is no running best to name.
   Spec §10.5 wants line 2 updated in place; that needs the ranker to publish its current top row.
4. **"Copy link" on a source row does nothing but toast.** A resolved link is a capability and is
   never written down, and the box has no clipboard a viewer can reach.
5. **"Mark all previous watched" is not in the episode menu.** With one progress row per title it
   would have no effect. It follows issue 1.
6. **"Open on your iPhone" is not in the More panel.** There is no wire call for it yet.
7. **OK on a cast card toasts instead of searching.** Search belongs to another agent this wave.
8. **A toast longer than about 40 characters is cut.** `Toast` in `Components.kt` is one line at
   640 px. Not this wave's file to change.
9. **Home's poster OK does not open Detail yet.** Seen on the box: OK on a Home shelf card did
   nothing. Discover's grid opens Detail correctly. That is the Home agent's wiring.
10. **Nothing wires `onOpenSources`.** Section 2 above gives the exact hook.

---

## 8. One bug found in someone else's area

`Toast` is `maxLines = 1` in a 640 px box, so any message over roughly 40 characters is cut with an
ellipsis. Several sentences this wave passes to `nav.toast(...)` are longer than that. It is a
component change and belongs to whoever owns `Components.kt` next.
