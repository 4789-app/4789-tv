# Wave 2 — Home and Discover

Date: 2026-09-21. Module: `:app`. Channel built and tested: `sideload`.
Box: Den TV, onn 4K Pro, `192.168.4.22:5555`, Android 14, 32-bit, 1920 x 1080, 11 add-ons paired.

Home and Discover are built to `docs/design/TV_DESIGN_SPEC.md` §3 and §4. Both draw real data from
the box's own add-ons and from the signed catalog snapshots. Five defects were found by running the
app on the box, not by reading it. All five are fixed.

---

## 1. Files

### New, owned by this wave

| File | What it holds |
|---|---|
| `client/ui/screens/home/HomeViewModel.kt` | UI models, `HomePlan` (all the row rules, free of Compose and Android), and the view model. |
| `client/ui/screens/home/HomeScreen.kt` | The screen: backdrop, hero, shelf band, focus, overlays, key map. |
| `client/ui/screens/home/HomeHero.kt` | Backdrop, hero strip, hero skeleton, shelf skeleton. |
| `client/ui/screens/home/HomeRows.kt` | `TvCardRow`, the poster, continue, folder and See-all cards, the column pivot. |
| `client/ui/screens/home/CatalogGrid.kt` | The "See all" overlay: one catalog as a full grid, over Home. |
| `client/ui/screens/discover/DiscoverViewModel.kt` | `DiscoverPlan` (picker and paging rules) and the view model. |
| `client/ui/screens/discover/DiscoverScreen.kt` | The screen: title, chips, collapsed strip, grid, panels, Rows variant. |
| `client/ui/screens/discover/DiscoverGrid.kt` | The 6-column grid, its cell, and the grid skeleton. |
| `client/ui/screens/discover/CatalogPicker.kt` | The chips, the collapsed strip, the Catalog panel, the Genre panel. |
| `app/src/test/.../home/HomePlanTest.kt` | 14 tests. |
| `app/src/test/.../discover/DiscoverPlanTest.kt` | 9 tests. |

### Edited

`client/ui/components/Components.kt` — appended only, as the brief allows. Two new composables and
one constant at the end of the file, plus two import lines. No existing composable was changed.

- `TvArtwork` — one sized image over a placeholder block. The title is the placeholder and it goes
  the moment the poster paints.
- `TvFocusableCard` — `TvFocusable` plus long-OK and a focus callback, which is what a browse card
  needs and what the hero follows.
- `LONG_OK_MILLIS = 600`.

Nothing outside the two screen packages and that one append was touched.

---

## 2. What the screens do

### Home, spec §3

- Backdrop of the focused card, crossfaded 190 ms after 140 ms of rest. During a fast sweep it holds
  the last settled image and never blanks.
- Hero: logo or Inter Bold 56 title, one meta line (`2026 · Drama · IMDb 7`), two lines of synopsis,
  one Play/Resume button. UP from row 1 reaches the button.
- Row 1 Continue Watching: 16:9 cards, 6 px progress bar, "S2 E4 · 22 min left".
- Row 2 pinned collections: folder cards with the accent bar and up to three poster slivers.
- Rows 3+: add-on catalogs in `catalogOrder`, then the snapshot shelves in the order the store
  already puts them (Tamil MV, Letterboxd, New From Friends, TMDB).
- Each row: header, `updated 27 August` label where the data is older than 30 minutes, and a
  See all card at the end of a catalog row.
- Progressive paint: cached shelves and the library rows paint first. Add-on catalogs stream in
  after. A row keeps the slot its key was given, so a row arriving never moves the ring.
- Skeleton on first paint, with a real focusable button so the remote is never dead.
- OK opens Detail, long-OK opens `AddToCollectionSheet`, OK on a Continue card resumes through
  `clientGraph.playFlow`, PLAY or PAUSE starts the same flow wherever focus sits.

### Discover, spec §4

- Three chip pickers: Type (Movies · Series · Anime), Catalog, Genre. Catalog and Genre open a side
  panel with the current choice focused.
- 6-column 2:3 grid, paging by `skip`, next page asked for when the focused row passes 70% of the
  loaded rows. Appending never moves the focused cell.
- The chips band collapses to the 72 px strip when focus enters the grid. UP from grid row 0
  expands it again and returns to the last used chip.
- Rows variant behind `presentationPreferences` boolean `discover_rows`, one rail per genre of the
  chosen catalog.

### The "See all" decision

Spec §3.5 sends See all to Discover, set to that catalog. `ClientNav` has no route that carries a
catalog, and three other agents are building against that interface. Rather than widen it, See all
opens the whole catalog as the same 6-column grid over Home. BACK closes it and the ring is where the
viewer left it. If the coordinator would rather have the route, it is a one-line change here plus one
argument on `openDiscover`.

---

## 3. Tests

```
cd "4789TV" && JAVA_HOME=/opt/homebrew/opt/openjdk@17 \
  ./gradlew :app:testSideloadDebugUnitTest :app:lintSideloadDebug
```

BUILD SUCCESSFUL. 536 unit tests, 0 failures, 0 errors. Lint clean, including
`SuspiciousIndentation`, which is an error in this build.

23 of those tests are new and all run on a plain JVM.

| Area | Cases |
|---|---|
| Row order, spec §3.4 | Continue, then collections, then catalogs, then shelves. An empty Continue row is removed. |
| Catalog planning | Registry order kept, duplicates dropped, a stated `catalogOrder` chooses the rows, no stated order caps them. |
| Progressive insertion | A result fills its own row and moves nothing else. Three results landing back to front keep the planned order. A row that answers empty is removed. A failure removes a row that had nothing and keeps one that did. |
| Continue card text | "S2 E4 · 22 min left". An unknown duration says only what it knows. Over an hour reads in hours and minutes. |
| Hero | The button label per card state. A missing meta field drops itself and its separator. |
| Discover picker | Choices keep registry order, a type chip filters, the genre is kept when the new catalog has it and cleared when it does not. |
| Discover paging | The 70% line, an empty grid never asks, a part-filled last row still counts. |
| Collapsed strip | Names every chosen value, drops the empty ones. Chip labels truncate at 28 and 20. |

---

## 4. On the box

Install, launch, drive with `adb shell input keyevent`, read `dumpsys gfxinfo`.

| Check | Result |
|---|---|
| Real posters from the box's own add-ons | Yes, Home and Discover both |
| Add-on catalogs answer | 12 rows, 20 to 100 items each |
| Snapshot shelves answer | 124 rows after the first refresh |
| Stale label | `updated 27 August`, `updated 17h ago`, 16 px after the header |
| Hero follows the ring | Title, year, genre, rating, synopsis, all from the focused card |
| Hero waits 140 ms | Yes; a fast sweep leaves the backdrop on the last settled image |
| Focus ring visible, and holds | Three screencaps 2 s apart, identical md5 `b7469b90a5961572e4b22c03de5e8443` |
| Row pivot | Cards 1 and 2 stay still; from card 3 the row slides to column 3 |
| Discover grid pages | Row 14 of the grid shows page-2 titles; nothing already drawn moved |
| Chips band collapses and expands | Strip reads `Discover · Movies · Popular`; UP from row 0 brings the chips back |
| Catalog panel | Grouped by add-on, current choice focused with a tick |
| Catalog change reloads | Chip reads `TMDB Popular`, new grid, back at row 1 |
| Crashes | 0 `AndroidRuntime` lines |
| ANRs | 0 |

### Frames

30 RIGHT presses along one Home row, posters already decoded:

| Metric | Value | Target (plan §9) |
|---|---|---|
| Frames | 216 | |
| Janky | 15 (6.9%) | ≤ 3% |
| 50th | 19 ms | ≤ 24 ms |
| 90th | 20 ms | |
| 95th | 23 ms | |
| Missed vsync | 5 | |
| GPU 50th | 9 ms | |

The median is inside the budget. The janky share is not, and the remaining jank is the focus scale
animation plus the posters the row pulls in at its edges.

For comparison, the same sweep before the two fixes in §5 measured 100 janky frames of 134 (74.6%) at
an 85 ms median. Wave 0's rail sweep measured 100% at 32 ms.

Crossing rows is still expensive. 10 DOWN and 10 UP over rows visited for the first time measured
58.6% janky at a 46 ms median. Each new row composes seven cards and starts seven image loads. That
is a first-visit cost and it does not repeat.

The Discover grid, 24 RIGHT presses wrapping across rows: 28.5% janky, 32 ms median, 4 ms GPU.

### Screenshots

In the session scratchpad:
`c-home.png`, `c-home-sweep.png`, `c-discover.png`, `c-discover-collapsed.png`,
`c-discover-paged.png`, `c-discover-chips.png`, `c-discover-panel.png`,
`c-discover-catalog-changed.png`.

---

## 5. Five defects found on the box

Each of these was found by running the app, not by reading it.

1. **Every catalog fetch ran on the main thread, and the box gave five ANRs.**
   `rememberCoroutineScope()` is the main dispatcher. OkHttp answers off the main thread. But the
   continuation resumes on the dispatcher that launched it, and that is where a 2 MB catalog body is
   parsed into 100 objects. Twelve of those at once froze the box. Reported by the Settings agent.
   Every fetch now runs on `Dispatchers.IO`, four at a time behind a semaphore. The mapping of up to
   124 shelves runs on `Dispatchers.Default`. Every `home.catalog.result` line now names a worker
   thread, and there is no ANR.

2. **Home planned 342 catalog rows.** `AddonRegistry.catalogs()` returns every catalog every add-on
   declares and sorts it. It does not choose. On the owner's box that is 342 catalogs. That means 342
   network calls and 342 rows on a screen walked with a D-pad. `catalogOrder` is now read as a choice
   as well as an order. With a stated order Home draws exactly those catalogs. With none it draws the
   first 12. The constant is `HomePlan.CATALOG_ROW_CAP`.

3. **The column pivot hid the row header.** `PivotSpec.column()` pins the focused node's top to the
   band top. The focused node is a card, not a row. The ring therefore sat on card one of a shelf whose name
   had scrolled off the screen. `rememberShelfColumnPivot()` in `HomeRows.kt` offsets the pivot by
   one header block plus one gap.

4. **UP from grid row 0 did nothing.** The handler asked for chip focus while the band was still
   collapsed. The chips were not composed, so the request landed on a requester with no node behind
   it. The band is now forced open first, and the focus request waits for that composition.

5. **The placeholder title read through the poster.** A crossfading bitmap is translucent while it
   arrives, so drawing the title under the image was not enough. `TvArtwork` now drops the title on
   the image's `onSuccess`.

### Two performance fixes, both measured

- **Every card ran a coroutine to watch its own focus.** `collectIsFocusedAsState` costs one
  coroutine and one flow collection per card, and a browse row holds forty. Replaced with
  `onFocusChanged` writing one boolean. That alone moved the row sweep from 74.6% janky at 85 ms to
  6.9% at 19 ms.
- **The hero swap recomposed the whole screen on every focus move.** Reading the focused card with a
  `by` delegate in the screen's own scope recomposed the hero, the backdrop and every shelf. That ran
  once per key press. The settle now reads it through `snapshotFlow` inside a `LaunchedEffect(Unit)`.
  The read happens in the coroutine, so a move costs only the two cards whose ring changed.
- The full-screen `Modifier.blur` was dropped. It needs API 31, which the Fire OS 7 floor of this
  fleet does not have. Where it does exist it re-runs a render effect every frame. That cost 15 ms of
  GPU while a row was swept. The backdrop is fetched at 500 px and stretched across 1920 instead.
  That is the same soft image on every box. GPU median fell from 15 ms to 9 ms.

---

## 6. Open issues

1. **Home has 137 rows.** The private snapshot delivers 124 Letterboxd shelves, one per list. Spec
   §3.4 asks for one shelf per username. Nothing is capped here. Dropping 120 of the owner's lists is
   his call, not mine. The rows are lazy, so they cost nothing until they are reached. But 137 rows
   is not a home screen. Either the snapshot sends fewer shelves, or Home takes a cap the owner sets
   in Settings.

2. **Two rows can share a title.** Two add-ons each declare a catalog called "Featured", so Home
   draws two rows called "Featured". The row keys are distinct, so nothing breaks. The headers should
   carry the add-on name when a title repeats.

3. **The janky share is over budget.** 6.9% against the 3% target, with the median inside it. The
   rest is the focus scale animation and edge-of-row poster loading. Plan §9 measures this in the P4
   pass, on the AFTDCT31, which is the real floor. Not measured on that box.

4. **`AddToCollectionSheet` is still the stub.** Long-OK calls it with the right
   `CollectionCandidate` and it dismisses at once, which is what the stub does. It will work when the
   collections agent fills it.

5. **`playFlow` is still `StubPlayFlow`.** Every Play press returns `ShowList`, so Home opens the
   Streams screen. The resume path carries `resumeFromMs`, the season and the episode, and is
   untested end to end until the streams agent lands `DefaultPlayFlow`.

6. **The Rows variant is built but not seen.** `discover_rows` has no switch in Settings yet, so the
   only way to turn it on is to write the preference. The code path compiles and is wired.

7. **`ClientGraph.start()` never asks the server for a snapshot.** It hydrates from disk and
   schedules the six-hourly worker. A box that has never saved a generation therefore shows no
   shelves at all. Home now calls `scheduler.onForeground()` itself. That is the sequence
   `client-data` `API-B.md` documents for Home. The call belongs in `ClientGraph.start()` instead,
   which is not my file.

8. **The Den TV was shared with three other agents for most of this work.** Six measurements were
   thrown away because the app was reinstalled or navigated under me. Every number in §4 comes from a
   run where the process id was unchanged across the window.

---

## 7. Not done

- No device test of long-OK, because `AddToCollectionSheet` is a stub that dismisses at once.
- No test of the Continue Watching row with real data. The box has no local progress and the phone
  has pushed no recents, so the row was empty in every run.
- No test of the Rows variant on the box, for the reason in §6.6.
- Nothing was committed, as the brief asked.
