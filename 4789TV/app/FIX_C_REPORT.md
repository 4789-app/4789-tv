# Fix C — Discover

Date: 2026-09-21. Group C of `app/AUDIT_2026-09-21.md`.

Files changed, all of them Group C's own:

- `app/src/main/java/com/fourseveneightnine/tv/client/ui/screens/discover/DiscoverScreen.kt`
- `app/src/main/java/com/fourseveneightnine/tv/client/ui/screens/discover/DiscoverGrid.kt`
- `app/src/main/java/com/fourseveneightnine/tv/client/ui/screens/discover/CatalogPicker.kt`
- `app/src/main/java/com/fourseveneightnine/tv/client/ui/screens/discover/DiscoverViewModel.kt`
- `app/src/main/java/com/fourseveneightnine/tv/client/ui/screens/home/CatalogGrid.kt`
- `app/src/test/java/com/fourseveneightnine/tv/client/ui/screens/discover/DiscoverPlanTest.kt`

Nothing else was touched. Nothing was committed. The TV was not touched.

---

## What changed, finding by finding

### F01 — the See-all overlay takes the ring

`CatalogGridOverlay` now holds a `FocusRequester` on the grid itself and asks for it as soon as the
first page lands. A grid with no cells has nothing to focus, so the request waits for data.

The overlay is also fenced: `focusProperties { exit = { FocusRequester.Cancel } }` plus
`focusGroup()` on its root. A key the grid does not answer can no longer walk the ring out to the
Home rows drawn underneath. BACK still closes it, and Home restores the card the viewer came from.

Group A owns the other half in `HomeScreen.kt`: blocking the rows behind and setting
`shell.overlayVisible`.

### F08 — the band collapse no longer re-measures the grid

The offset was an animated `Dp` read in the composable body. Every frame of the 180 ms collapse
recomposed the screen and re-measured the grid. It is now an animated float in pixels. It is read
inside `offset { }`, which runs in the layout pass only.

### F09 — the grid fits inside the safe area

The grid box was `fillMaxSize` and then pushed down 162 px, so it ran 162 px below the panel. It now
carries an explicit height: `DiscoverPlan.gridHeightDp(collapsed)`, which is 1026 minus the band
top. The grid ends at the safe bottom whether the band is open or shut. The height comes from the
settled state, not from the tween, so it is measured once per collapse and not once per frame.

### F10 — the grid does not jump when data lands

Every vertical number now lives in `DiscoverPlan`. The real cell and the skeleton both read it:
poster 354, gap 10, title 24, gap 2, meta 22. Cell block 412, row pitch 440, skeleton spacer 86.
Both text lines carry an explicit height and a matching line height. A tall font can no longer
change the pitch. A test asserts that poster height plus skeleton spacer equals the row pitch.

The spec's §4.2 prose says 410 and 438. Its own parts add up to 412 and 440. The code follows the
parts. That is 2 px per row, so the spec should be corrected, not the cells.

### F15 — only visible cells fetch

`DiscoverPlan.requestWindow` gives the index range that may start a poster request: what is on
screen, plus one row of look-ahead. A cell waits until it is inside that window. It then rests
140 ms before it asks Coil for anything. The wait is cancelled if the ring flies past it. Once a
cell has asked it never un-asks. Dropping a request would blank a poster in view.

### F16 — the Catalog panel is a lazy list

It was a plain scrolling `Column` over every catalog. The owner's box declares 342 of them. It is
now a `LazyColumn` with no focus requester inside an item. The requester sits on the list, which is
always composed. The list is scrolled to the current choice before the request, so `focusRestorer`
puts the ring there. Rows are built by `DiscoverPlan.panelRows`, a plain function with tests.

### F35 — the count line is drawn

`DiscoverPlan.countLine` returns "Loading" while a page is on the wire. It returns "1,284 titles"
once the whole catalog is in hand. It returns nothing when the total is unknown. `CatalogPage`
carries no total from the add-on. The only honest count is the one we hold once the add-on says
there is no further page. Spec §4.11.2 asks for that: drop the line rather than guess it.

### F36 — the Catalog chip shows its rest fill

`selected = state.selected != null` in place of `selected = true`.

### F37 — the band text crossfades

The collapsed strip and the title-plus-chips block are now two sides of one `Crossfade` on the same
180 ms tween as the slide. It snaps when Reduce Motion is on.

### F38 — three inks in the collapsed strip

`DiscoverPlan.summaryParts` returns the line as runs with an ink each: the chosen values, the word
that names the screen, and the separators. `CollapsedChipsBand` builds one `AnnotatedString` from
them. `summaryStrip` is now that same list joined, so the plain-string callers are unchanged.

### F39 — a chip change keeps the old grid

`reload()` no longer empties the item list. The grid holds the old cells until the new page lands.
It no longer blanks to a skeleton for as long as the add-on takes. A page-1 failure clears the list
and shows the error. A page-2 failure keeps page 1.

Not done: the 190 ms crossfade of the whole grid that spec §4.8 asks for. It needs two grid slots
on screen at once. A second `LazyVerticalGrid` with the same item keys is a focus hazard, for a P2
gain. The blanking, which is what the viewer sees, is gone.

### F40 — the error state offers another catalog

The error branch is now a `StateBlock`. It holds a primary "Retry" and a ghost "Pick another
catalog" that opens the Catalog panel. The Retry button takes focus when the state appears, so the
screen always holds the remote. The label reads "Retry", which is what spec §4.9 and §13.6 ask for.
The shared `ErrorState` still hardcodes "Try again". That is F76, and Group B owns the file.

### F50 — the rails filter is hoisted

`rails.filter { ... }` ran inside the `LazyColumn` content lambda. It is now a `remember(rails)`.

---

## Checks run

| Check | Result |
|---|---|
| `./gradlew :app:compileSideloadReleaseKotlin` | BUILD SUCCESSFUL |
| `./gradlew :app:testSideloadDebugUnitTest --tests 'com...discover.*'` | 20 tests, 0 failures |

New tests in `DiscoverPlanTest.kt`:

- paging threshold, including the guard while page 1 is still on the wire;
- the request window: look-ahead, both clamps, an empty list, and a cell past the window;
- geometry: skeleton pitch equals cell pitch, and the grid ends at the safe bottom either way;
- the count line, the collapsed strip's three inks, and the Catalog panel's rows and entry row.

On-box verify is owed. None of this was measured on the onn 4K Pro; audit §5 lists the commands
for F08, F09, F10, F15 and F16.

## Left for others

- **F36, the 24 px list glyph on the Catalog chip.** `TvChip` takes a label and no glyph.
  `Components.kt` is Group B's.
- **F76 in the shared error block.** `ErrorState` hardcodes "Try again". Discover now bypasses it.
- **Spec §4.2 arithmetic.** The cell parts add to 412 and the pitch to 440, not 410 and 438.
- **A total in `CatalogPage`.** Without one the count line only appears on a catalog that fits in a
  single page. `client-data` is not Group C's.
- **Nearby, not fixed:** the empty state's "Clear genre" button is not focused on entry. A catalog
  that answers empty leaves nothing holding the remote below the chips. Spec §4.9 says that button
  is focused.
