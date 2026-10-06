# Group A — stop the vertical movement

Date: 2026-09-21. Audit: `app/AUDIT_2026-09-21.md` §1, §2, §3, §4 (Group A), §5, §6.
Spec read: `docs/design/TV_DESIGN_SPEC.md` §0.4, §0.9, §1, §3.
Handover read: `app/FIX_B_REPORT.md` (Notes for Group A).

Files owned and changed:

- `app/src/main/java/com/fourseveneightnine/tv/client/ui/screens/home/HomeViewModel.kt`
- `app/src/main/java/com/fourseveneightnine/tv/client/ui/screens/home/HomeScreen.kt`
- `app/src/main/java/com/fourseveneightnine/tv/client/ui/screens/home/HomeRows.kt`
- `app/src/main/java/com/fourseveneightnine/tv/client/ui/screens/home/HomeHero.kt`
- `app/src/main/java/com/fourseveneightnine/tv/client/ui/AppRoot.kt`
- `app/src/test/java/com/fourseveneightnine/tv/client/ui/screens/home/HomePlanTest.kt`

Nothing else was opened. `CatalogGrid.kt` is Group C's and was not touched. Nothing was committed.
The TV box was not touched.

Four things were kept as asked. The sharp w1280 `HomeBackdrop` in its 1140 x 620 top-right box,
with Coil's own crossfade. `HomePlan.MAX_LETTERBOXD_ROWS`. The first-row focus retry loop. And the
rescue net's "ask the screen first" call in `AppRoot.kt`.

---

## Finding by finding

### F02 — a row that answers empty no longer deletes itself under the ring (P0)

The defect: `applyResult` returned `null` and `applyFailure` filtered the row out. Every row below
then jumped up 410 px. Twelve catalogs answer over about eight seconds, so it happened more than
once per cold start.

- `HomeRow.Posters` gained `val removed: Boolean` — `HomeViewModel.kt:125`.
- `applyResult` marks instead of dropping — `HomeViewModel.kt:283`.
- `applyFailure` marks instead of dropping — `HomeViewModel.kt:293`.
- New `HomePlan.visibleRows(rows, focusedKey)` decides when a marked row may go —
  `HomeViewModel.kt:189`. A removed row at or above the focused row keeps its place and its 410 px
  block. Only rows below the ring leave. With nothing focused they all leave at once, because there
  is no ring to protect.
- The screen reports the focused row to the view model — `HomeScreen.kt:260`. The view model side
  is `onRowFocused` at `HomeViewModel.kt:634`. It publishes only when the row changes, so a move
  along a row costs nothing.
- `Modifier.animateItem()` on each row — `HomeScreen.kt:387`. An allowed removal now slides 190 ms
  rather than cutting.
- A removed row draws the same block, still: `ShelfSkeletonRow(sweep = index < 2 && !row.removed)`
  — `HomeScreen.kt:436`.

This is spec §3.7's rule exactly: "If focus is inside or below that row, the removal waits until
focus moves above it."

### F03 — the rescue net no longer scrolls the band to the top (P0)

`HomeScreen.kt:137-151`. `shell.restoreContentFocus` used to call `rowsFocus.requestFocus()` on the
`LazyColumn`. That lands on item 0 when the restorer's saved card is gone.

It now scrolls the remembered row back under the band top first. Then it requests. The column's
`focusRestorer()` now has the saved card composed, so the ring returns to it and nothing scrolls
further. `scrollToItem(row)` reproduces the pivot position anyway: spec §3.5 pins the focused row's
top at y 578. The net never lands on row 1 when a lower row had the ring.

The row index is a plain `intArrayOf(-1)`, written from the card's own `onFocused`
(`HomeScreen.kt:261`). Not a `State`. A `State` read in the screen's scope would recompose the
hero, the backdrop and every shelf on each vertical move.

The policy is pure and tested. `HomePlan.restoreRow(lastFocusedRow, rowCount)` —
`HomeViewModel.kt:206`. `-1` means "never touched", and row 1 is right then. A stale index from a
longer list is clamped, never thrown.

Fixing F02 removes most of the trigger. The saved card now survives.

### F04 — Continue and Collections keep their slots from the first publish (P1)

- `HomePlan.rows` always emits both rows, empty or not — `HomeViewModel.kt:173`.
- An empty reserved row draws nothing and takes no height — `HomeScreen.kt:386`.
- The 36 px rest zone moved off the column's `verticalArrangement.spacedBy`. It is now each drawn
  row's own bottom padding — `HomeScreen.kt:387`. `spacedBy` gives a gap to a zero-height item too.
  Two reserved rows would have held 72 px of nothing above row 1.

Both rows keep their keys. A late Room emission is now a row growing in place. It is no longer an
insert at index 0 that reorders every key below it. Spec §3.9.9 still holds: an empty Continue row
is not drawn.

### F05 — the hero holds its card, and a dead Play button greys out (P1)

- `HomeFolderCardView` and `SeeAllCard` no longer pass `(null, null)`. They report the row and leave
  the hero's settled card alone — `HomeScreen.kt:415-421`, `:438-445`.
- The hero action is disabled when there is no settled card — `HomeHero.kt:133`. Spec §3.9.15.

### F12 — the hero text crossfade no longer blends two full bands (P1)

`HomeHero.kt:167-193`. `Crossfade` wrapped two children. Each filled the 1700 x 524 band. Every
settle composited 890,000 pixels twice for 190 ms on a fill-bound GPU.

It is now one `Animatable` alpha on one layer. The content swaps at the alpha's low point. The
layer is sized to the text it holds, 880 x 400. The alpha is read inside `graphicsLayer {}`, so an
animation frame updates the layer and nothing recomposes. Reduce Motion snaps.

### F20 — the content behind the expanded rail is dimmed (P1)

`AppRoot.kt:320-336`, constant at `:428`. A `TvColor.Canvas` scrim at 60%. It fades in over the
rail's own 180 ms. It is drawn between the `NavHost` and the `NavRail`. Its alpha is read inside
`graphicsLayer`.

It is a plain `Box` with no focus target and no pointer input. It cannot take or block focus. It is
composed only while `railOpen`, as briefed.

**This is a departure from spec §1.6**, which says "the content behind does not move, dim or blur".
Spec §1.8.2's reason for not *pushing* the content stands, and was kept. Record the dim.

### F23 — Home has an error state (P1)

- `HomePlan.failed(settled, catalogRows, rows)` — `HomeViewModel.kt:217`. True when three things
  hold: the first-paint deadline has passed, every planned catalog has answered, and nothing
  produced a row. A box with no add-ons at all is the empty state, not this one.
- `publishNow` uses it — `HomeViewModel.kt:600`.
- The branch carries spec §3.7's exact copy, a Retry button and an "Open Jobs" ghost button —
  `HomeScreen.kt:279-290`.
- `retry()` now re-arms the first-paint deadline and puts every planned row back to loading —
  `HomeViewModel.kt:648`. The screen answers with a skeleton. Emptying the row list, as it did,
  dropped the screen straight to "Add a catalog add-on". That is the wrong sentence for a retry
  that has not answered yet.

### F29 — two add-ons declaring the same catalog name are told apart (P1)

`HomeViewModel.kt:254-264`. A name more than one add-on uses gets `" · <add-on>"` appended. A name
only one add-on uses is left alone. A catalog whose name already is the add-on's name is not
doubled up.

### F31 — the "See all" card matches spec §3.3 (P2)

`HomeRows.kt:386-396`, `:404`. The `"›"` glyph in `HeroTitle` is gone. A drawn 44 px chevron is
centred at y 140. It is a `Canvas` with two round-capped 4 dp strokes in `textSecondary`. The label
moved from y 190 to y 200.

### F33 — the state blocks sit where the spec draws them (P2)

`HomeScreen.kt:341-348`. `Box(fillMaxSize, Center)` centred a 600 px block inside a column that is
already inset 220 px. It landed at x 770. `StatePlate` pins it to x 440 inside the column, which is
x 660 on screen. That is spec §3.7's "x 660..1260". All three state blocks use it.

### F34 — the Continue and folder card blocks measure what §0.4 says (P2)

- `HomeContinueCardView`: title `height(28.dp)`, meta `height(22.dp)` — `HomeRows.kt:305`, `:313`.
  169 + 12 + 28 + 4 + 22 = 235, the spec's card block. The row block is now 291, not 295.
- `HomeFolderCardView`: name `height(28.dp)`, count `height(20.dp)` — `HomeRows.kt:362`, `:369`.
  214 + 8 + 28 + 20 = 270. The row block is now 326, not 334.
- `rowBlockHeight` (`HomeRows.kt:410`) already held 291 / 326 / 410. It needed no correction. It is
  also dead code: nothing in the app calls it. Flagged, not removed.

### F47 — `settingsLoaded` is written under the publish mutex (P2)

`HomeViewModel.kt:514`. It is now a field set inside `publish { }` and read by `publishNow`. A
publish landing between the old read and its write can no longer lose that publish's rows.

### F52 — one `PosterRequest` per card, not per recomposition (P2)

`HomeRows.kt:237` for `PosterRequest.poster`. `HomeRows.kt:274` for `PosterRequest.wide`, per Group
B's note.

### F01 — the focus block in `HomeScreen.kt` (P0; Group C owns the overlay itself)

- The content column is a deactivated focus group while the grid overlay is up —
  `HomeScreen.kt:240`. The rows stayed focusable behind an opaque box. The ring sat on a card
  nobody could see and the D-pad scrolled a hidden row.
- It is written as
  `then(if (overlayUp) focusProperties { canFocus = false } else Modifier).focusGroup()`. It only
  ever switches the subtree OFF. A group told it CAN focus becomes a focus target of its own, and
  the ring lands on the column instead of a card.
- `shell.overlayVisible` is set while the overlay is up — `HomeScreen.kt:315`.
- The screen frame stops turning LEFT into the rail and PLAY into playback while the overlay owns
  the remote — `HomeScreen.kt:214`.

Group C owns the overlay taking focus, in `CatalogGrid.kt`.

### Audit §6 — `TvCardRow` swallowed LEFT at card 1

`HomeRows.kt:83`, `:199`, `:209`, plus the call sites in `HomeScreen.kt`. `focusedIndex` was
written only by an `onFocusChanged` on a wrapper `Box`. That can still hold the previous card's
index when focus arrives through `focusRestorer()` rather than a key press. LEFT then moved inside
the row instead of bubbling, and the rail never opened.

The index is now written from the card's own `onFocused`, which fires first. `TvCardRow` provides
a new `LocalCardFocusReport` around every card, and the card calls it.

The wrapper's `onFocusChanged` is kept as the second writer. `DiscoverScreen.kt:260` also calls
`TvCardRow`, and that file is Group C's. Changing the lambda's arity would have broken their
compile. Both writers set the same value.

### Group B's handover

- **F21** applied, both lines together. `contentPadding = PaddingValues(start = 16.dp, end = 96.dp)`
  at `HomeRows.kt:165`, and `+ RowStartInsetPx` on the row pivot at `:154`. The constant is named at
  `:115`. Card 1 now starts 16 px in. That covers the ring's 7 px and the 1.06 scale's 7.1 px. The
  pivot moved with it, so rows still park the focused card where spec §0.3 says.
- **F22** left alone. `ShelfHeaderGap` is still 16 dp, as instructed.
- **F31**, **F34** and **F52** done as their notes describe. See above.
- **F32's call site** done. The hero action now carries a 24 px drawn play triangle —
  `HomeHero.kt:136`, glyph at `:144`. It takes `OnAccent`, or `TextMuted` when the button is
  disabled. `HomeHeroSkeleton`'s button was left without one.

---

## Tests

`app/src/test/java/com/fourseveneightnine/tv/client/ui/screens/home/HomePlanTest.kt`. 26 tests, 0
failures. Thirteen are new or rewritten.

The row-removal policy:

- a row that answers empty is marked, not dropped from the list
- a failure marks a row that had nothing and keeps one that did, with its items and `loading` right
- an empty row stays as a placeholder while the ring is on it or below it
- an empty row goes once the ring is above it
- with nothing focused, every empty row goes at once — and the same for a focus key no longer in
  the list
- a row still loading is never dropped

The rescue-focus policy:

- the rescue net returns to the row that had the ring, not row 1
- a band nothing has touched restores to row 1, for both `-1` and `0`
- a row index left over from a longer list is clamped, never thrown

Reserved slots, duplicate names and the error state:

- continue and collections keep their slots while they are empty, and neither can take the ring
- two add-ons declaring the same catalog name get told apart
- a name only one add-on uses is left alone
- every catalog answering with nothing is the error state; before the deadline it is not
- one row with items is not the error state, and neither is having no add-ons
- a catalog still in flight is not the error state

Two old tests were rewritten rather than deleted, because their rule changed.
`an empty continue row is removed, not drawn empty` became the reserved-slot test.
`a row that answers empty is removed` became the marking test.

---

## Build state

```
cd 4789TV && JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :app:compileSideloadReleaseKotlin -q
```

**Green.** One earlier run failed on `CollectionDetailScreen.kt` and `CatalogGrid.kt`. Both were
mid-edit by other groups. I waited and re-ran clean. One error was mine: `animateItem` needs a
`LazyItemScope` receiver on the local `itemsIndexed` helper. Fixed.

```
./gradlew :app:testSideloadDebugUnitTest --tests 'com.fourseveneightnine.tv.client.ui.screens.home.*'
```

**Green. 26 tests, 0 failures.**

Nothing was committed.

---

## What could not be done

- **On-box measurement.** The brief says not to touch the TV over adb. The orchestrator measures.
  Audit §5 names three checks for this group. Three screencaps two seconds apart on a cold Home
  must give the same md5 (F02, F03, F04). A screencap with the rail expanded must show the content
  dimmed (F20). A screencap with the ring on card 1 must show the same clearance on all four sides
  (F21).
- **F04 still lets the band grow.** A reserved row that fills later still pushes the rows below it
  down by its block height. That is what the audit's own fix asks for: "let them grow in place".
  What it removes is the key reorder, and the focus and scroll damage of an insert at index 0. Both
  Room flows emit in the first second, before a viewer has moved. It is therefore not under the
  ring in practice.
- **F02 still slides rows when the ring is at the top.** A cold start puts focus on row 1. A
  catalog below answering empty then drops that row, and the rows under it slide up over 190 ms.
  That is spec §3.7 and §3.6. The focused row itself never moves. The rescue-net snap and the
  removals under the ring are gone.
- **F05's third option not taken.** The audit offers a better idea for a folder row: show the
  folder name and count in the hero slot. That is a new hero variant, not a fix. It was left out.
- **F21's preferred fix not taken.** Spec §3.3 asks for one ring per row, drawn by a shared,
  unclipped overlay that reads the focused card's bounds. Group B's two-line `contentPadding`
  stop-gap was applied instead, as its note asks. The overlay is still owed. It is Group A's file.

## Nearby, not fixed (reported per CLAUDE.md)

- `rowBlockHeight` (`HomeRows.kt:410`) has no callers. It went dead when the band stopped measuring
  rows itself.
- `HomeState.focusableRows` (`HomeViewModel.kt:145`) has no callers either.
- `client-data/.../images/ImageLoaderFactory.kt`. This is the other half of F14. Group B flagged it
  as unowned by any group in audit §4. Since then someone has raised the memory cache to 30%, with
  a 15% share kept for a low-RAM box. That half is done. Check whether the `RGB_565` half was done
  with it.
- `AppRoot.kt:152` sets `shell.openRail` on every recomposition of `Shell`, outside any effect. It
  is harmless today. It is still a side effect in composition.
