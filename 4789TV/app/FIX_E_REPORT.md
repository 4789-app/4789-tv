# Group E — collections, fix report

Date: 2026-09-21. Source: `app/AUDIT_2026-09-21.md` §4 Group E.
Files changed: the six under
`app/src/main/java/com/fourseveneightnine/tv/client/ui/screens/collections/`, plus
`app/src/test/java/com/fourseveneightnine/tv/client/ui/screens/collections/CollectionsModelTest.kt`.
Nothing outside that folder was touched. The box was not touched.

Gates: `:app:compileSideloadReleaseKotlin` green.
`:app:testSideloadDebugUnitTest --tests 'com...collections.*'` green, 35 tests.
On-box verify is still owed. Every geometry number below was read from the code and the spec, not
measured on a panel.

---

## What changed, per finding

### F54 — a dead collection id kills the remote (P0)

`CollectionDetailScreen.kt`. The old gate turned `loadedOnce` true only when the row arrived.
An id that never resolves drew a skeleton for ever. Nothing on that screen took focus.

Two changes. The wait is now bounded: after 3 s with no row the screen shows the toast "That
collection is gone." and goes back. And `LoadingCollection` now draws a focusable "Back to
collections" ghost button, so the remote has a target from the first frame rather than after the
timer. That frame draws no rail, so without the button the only cure was force-quitting the app.

### F55 — RIGHT in the collection editor can throw (P0)

`CollectionEditorScreen.kt`. Two parts, as the audit asked.

1. The row writes `focusedRow` from `onFocusedChange`, which fires in the frame the ring moves, not
   from a `LaunchedEffect` a frame later.
2. The RIGHT destination is guarded: `right = swatchFocus` is set only while `focusedRow` is the
   Accent row, and `right = sourceKindFocus` only while it is the Add source row. A destination
   inside `focusProperties` throws out of Compose's own focus search, where a `runCatching` of ours
   cannot reach it.

### F63 — picking an accent threw the ring to Rename

New `accentRowFocus`, attached to the Accent row. `onAccent` asks for that one, not `firstRowFocus`,
which is row 0.

### F64 — the third poster sliver was cut off

`CollectionsCommon.kt`. 40 px is now a pitch, not a gap. A `Box` offsets each sliver by
`index * 40`, drawn in list order so the later one lands on top. The block is 200 px inside the
380 px card, against 392 before. `FolderGrid.sliverOffset` and `sliverBlockWidth` hold the numbers.
A test checks they fit.

### F65 — the collection grid drifted off its columns

`CollectionDetailScreen.kt`. The grid is pinned to `CollectionGrid.BAND_WIDTH` (1516 = six 236 px
cards plus five 20 px gaps) instead of `fillMaxSize`. Columns now land on 220, 476, 732, 988, 1244
and 1500. A test checks the arithmetic.

### F66 — the Add-to-collection footer was pushed off the panel

`AddToCollection.kt`. The list is `weight(1f).verticalScroll(...)`. The panel column carries
54 px of bottom padding. That puts the footer at y 1000 to 1026, as spec §8.2 draws it.

### F67 — long-OK started move mode instead of opening the editor

`CollectionsScreen.kt`. Long-OK on a folder now opens the editor, per spec §5.4. Folder move mode
moved to MENU. It is the rarer of the two jobs. A system folder answers neither.

### F68 — wrong type roles

The count line under the screen title is Space Mono 20 (`CountLineStyle`), not `Meta`. The folder
name is 24 SemiBold (`FolderNameStyle`), not `CardTitle` at 22 Medium.

### F69 — the folder grid sat 28 px low with a long row pitch

The line heights are now explicit: name 24, count 20. That makes the cell block 270 and the row
pitch 302, against 280 and 312 before.

The 28 px offset came from `TopLevelScaffold`, which rests 36 px under its title where spec §5.2
rests 10. Five other screens share that file, and it is not this group's to change. So the
content column is pulled up by 26 px here instead (`SCAFFOLD_GAP_TRIM`). The count line lands at
y 112 and the grid band at y 160. **This is the one edit in the group that would be cleaner in
another group's file.** If Group F or a later pass adds a gap variant to `TopLevelScaffold`, delete
the offset and pass the variant.

### F71 — the sort choice reset on every open

Stored per collection in `presentationPreferences` under `collection_sort_<id>`, per spec §6.7.3.
The key and the parse live in `CollectionSort` in the model file, with a test. An unknown stored
value reads as Added rather than throwing.

### F72 — focus bookkeeping ran a frame late

All three sites (`CollectionsScreen.kt` folder card and New card, `CollectionEditorScreen.kt` row)
now pass `onFocusedChange` instead of `LaunchedEffect(focused)`. The LEFT handler on the folder grid
can no longer read a card-stale index and open the rail from column 2.

### F73 — every card ran a flow collector

`LongPressFocusable` holds a plain boolean written by `onFocusChanged`. The
`MutableInteractionSource` and `collectIsFocusedAsState` are gone, so a grid of folders no longer
pays one coroutine and one flow collection per card.

### F74 — a hex colour was parsed on every read

`FolderCardModel.accent` and `ChecklistRow.accent` are stored `val`s, computed at construction.
They sit in the class body, not the constructor, so they stay out of `equals` and `copy`.
The three other parses inside composition are wrapped in `remember`: the collection header, the
editor's accent circle, and the eight swatches. The swatches now read the palette already parsed.

### F79 — two effects keyed on the whole detail

`CollectionDetailScreen.kt`. The dead-id and delete effects key on `collectionId` and a
`missing: Boolean`, so no emission costs a deep compare of the item list.

### F80 — a poster with no url drew an empty block

`PosterCell` calls `TvArtwork` instead of `Poster`. The title now sits inside the placeholder and
goes the moment the image paints.

### F81 — focus was placed when data arrived, in three places

One effect per screen, keyed on `Unit`, placing the ring once and never again. A shared
`placeEntryFocus` retries over frames until a node takes the focus, then stops.
The Add-to-collection panel waits up to 600 ms for the first rows before it decides the viewer has
none. It no longer focuses "New collection" and then jumps to row 1.

Also: the rail's `restoreContentFocus` on the folder screen now points at whichever node is drawn.
It always pointed at the grid, which does not exist on the empty screen.

### F75 (My Cloud half) — green `cached` on a system glyph

`CollectionsScreen.kt`. The My Cloud glyph is `textMuted`, per spec §5.3. Spec §0.5 reserves green
`cached` for "this stream is already on the debrid box". The settings half of F75 belongs to
Group F.

---

## Source compatibility

`AddToCollectionSheet(candidate: CollectionCandidate, onDismiss: () -> Unit)` and
`CollectionCandidate` are unchanged. Home and Detail still compile against them.

`LongPressFocusable` gained one optional parameter, `onFocusedChange`. It is internal to the
collections package and every call site is in this group.

---

## Not done, and why

- **On-box verify.** Human or on-device verify pending. The audit's §5 row for F54 and F55 needs
  a panel. Edit a collection's source, press BACK to it, then press DOWN and RIGHT together on the
  Accent row. Nothing in this environment can press a remote.
- **The scaffold gap (F69).** Covered above. The offset is a workaround for a shared file.
- **The 3 s grace (F54).** Chosen to match the audit's wording. It is a guess at how long a local
  database read may take on a cold start. A slow box could show the button for the full 3 s.

## Nearby, not fixed

- `CollectionsScreen`'s own loading grid still has nothing focusable. It reads a local database and
  answers fast, so the audit did not list it, but it is the same class of defect as F54.
- A system folder's accent bar uses whatever accent the row stores. Spec §5.3 says it should be
  `textMuted`. Only the glyph was in scope here.
- `writeSource` in the editor still deletes a row and makes a new one, because
  `LibraryRepository` has no `setSource`. That is what makes a dead id reachable at all. F54 now
  handles the symptom; a `setSource(id, kind, sourceRef)` on the repository would remove the cause.
