# Wave 2 — Collections

Date: 2026-09-21. Module: `:app`. Channel built and tested: `sideload`.
Screens: the folder grid, one collection, the editor, and the add-to-collection checklist.

---

## 1. Files

All new. Nothing outside the collections folder was touched.

| File | Lines | What it holds |
|---|---|---|
| `client/ui/screens/collections/CollectionsModel.kt` | 328 | Every rule, with no drawing: folder order, move swaps, sort, checklist, sourced refresh, accents. |
| `client/ui/screens/collections/CollectionsCommon.kt` | 313 | The card that knows a held OK from a tapped one, the modal keyboard, the drill-in frame, posters. |
| `client/ui/screens/collections/CollectionsScreen.kt` | 399 | The folder grid, spec §5. |
| `client/ui/screens/collections/CollectionDetailScreen.kt` | 678 | One collection, spec §6. Also the two system folders. |
| `client/ui/screens/collections/CollectionEditorScreen.kt` | 684 | The editor, spec §7. |
| `client/ui/screens/collections/CollectionSources.kt` | 127 | Where a sourced folder's titles come from. |
| `client/ui/screens/collections/AddToCollection.kt` | 263 | The checklist, spec §8. |
| `test/.../collections/CollectionsModelTest.kt` | 388 | 29 tests. |

`AddToCollection.kt` keeps the two names Home and Detail already call:
`CollectionCandidate(canonicalId, mediaType, title, posterUrl)` and
`AddToCollectionSheet(candidate, onDismiss)`.

## 2. What each screen does

**Folder grid.** Four columns of 380 by 214 cards. System folders first, then yours by sort
index, then "New collection" last. OK opens the folder. A held OK starts move mode. MENU opens
the editor. Move mode swaps with the arrows, saves on OK, and puts the order back on BACK.

**Collection screen.** Header with the accent bar, name, count and source line. Six-column
poster grid. Sort panel with Added, Title and Year. Edit button. OK opens Detail. A held OK
opens the item menu: remove, add to another, open details, and move. Move is missing on a
sourced folder, which cannot be reordered.

**Continue Watching** reads `library.continueWatching()`, not stored rows, because that is where
the data lives. PLAY on a card calls `playFlow.play(...)` and falls back to the Streams screen
when the result is `ShowList`.

**My Cloud** says what it is waiting for: "File list comes in a later build." It is drawn only
when a debrid key is present, and removed rather than greyed when it is not.

**Editor.** Six rows on the left, a readout on the right. Rename uses the TV keyboard. Accent
shows eight swatches with their hex. Pin toggles from the row itself. Add source offers Manual,
an add-on catalog and a Letterboxd list. Reorder leaves for the collection screen in move mode.
Delete asks first, with Cancel focused. Every change saves at once.

**Checklist.** A side panel of your folders with a tick where the title already is. Each OK
writes at once. A sourced folder is dimmed and its count reads "sourced". The last row makes a
new collection and puts the title straight into it.

## 3. Tests

29 tests, all green.

```bash
cd "/Users/saranpenna/4789 iOS/4789TV" && JAVA_HOME=/opt/homebrew/opt/openjdk@17 \
  ./gradlew :app:testSideloadDebugUnitTest \
  --tests 'com.fourseveneightnine.tv.client.ui.screens.collections.*'
```

| Area | Cases | What is checked |
|---|---|---|
| Folder order | 5 | System folders first; My Cloud dropped with no debrid key; count wording; the empty state. |
| Move mode | 5 | Arrow travel, the swap, both ends, and that BACK lands on the order it started from. |
| Sort | 6 | Added is the stored order; Title ignores case and the year; Year is newest first with unknowns last. |
| Checklist | 3 | Your folders only; add, remove and the ignored sourced row; where focus lands. |
| Sourced refresh | 4 | A sourced folder is replaced and renumbered; a manual folder is never touched; repeats collapse. |
| Refs and accents | 6 | Catalog refs round trip; eight accents round trip through hex; a bad accent falls back. |

The build gate is green too:

```
./gradlew :app:compileSideloadDebugKotlin   BUILD SUCCESSFUL
./gradlew :app:assembleSideloadDebug        BUILD SUCCESSFUL
```

## 4. Den TV proof

Box: onn 4K Pro at `192.168.4.22:5555`, 32-bit, 1920 by 1080.
Build installed: `app-sideload-debug.apk`, started at
`com.fourseveneightnine.tv/com.fourseveneightnine.tv.client.ui.MainActivity`.

Every step below was driven with `adb shell input keyevent` and read back from a screenshot.
Screenshots are in
`/private/tmp/claude-501/-Users-saranpenna-4789-iOS/4a928fab-f5fe-4aa4-8353-f04625051500/scratchpad/`.

| Step | Screenshot | Result |
|---|---|---|
| Collections, nothing made yet | `e-05-grid-empty.png` | "No collections yet" with the New collection button focused. |
| Name it on the remote | `e-06-typed.png` | "SUNDAY NIGHT" typed key by key. Focus stayed inside the keyboard. |
| Editor opens | `e-08-editor.png` | Six rows. Reorder greyed, Delete in red, the pane reading out Rename. |
| Accent pane | `e-09-accent-pane.png` | Eight swatches, two rows of four, hex under each, current one ringed. |
| Accent chosen | `e-11-accent-picked.png` | Dot turned teal, toast "Accent changed." |
| Pin turned on | `e-12-pinned.png` | Row reads On, pane reads Pinned, toast "Pinned to Home." |
| Add source pane | `e-13-source-pane.png` | Three kinds with their lines, Manual ticked. |
| Source saved | `e-15-picker.png` | Row reads Add-on catalog, toast "Source saved." |
| Back to the grid | `e-16-grid-one-folder.png` | 3 collections. Teal bar and pin marker on the folder. |
| Killed and relaunched | `e-17-persisted.png` | Home row 2 shows the pinned folder. |
| Collections after the restart | `e-18-grid-after-restart.png` | Same three folders, same accent, same pin. |

Kill and relaunch was `adb shell am force-stop com.fourseveneightnine.tv` followed by a fresh
`am start`. Name, accent, pin and source all came back.

One defect was found on the box and fixed in this run. Holding LEFT inside the modal keyboard
walked focus out to the navigation rail, then DOWN walked the rail to Settings, and the
half-typed name was thrown away. The overlay now refuses to let focus leave it
(`focusProperties { exit = { FocusRequester.Cancel } }` in `KeyboardOverlay`). The retest is
`e-06-typed.png`.

A second fix came from the same run. A held OK now fires while the key is still down, using the
key's repeat count, rather than waiting for the key to come up.

## 5. Open issues

1. **`LibraryRepository` cannot change a folder's source.** It has
   `create(name, accent, kind, sourceRef)` and no `setSource`. The editor therefore makes the row
   again with the new kind and deletes the old one. Name, accent and the pin carry over. The
   folder moves to the end of the grid order, which is the one visible cost. A
   `setSource(id, kind, sourceRef)` on the repository would remove this.
2. **The library row has no year.** `CollectionItem` carries no year column, so a known year is
   written into the stored title as a trailing `(2024)` and read back for the Year sort. A year
   column would be cleaner.
3. **MDBList is missing on purpose.** Nothing in `client-data` fetches an MDBList list. The
   editor says "MDBList is not on this box yet." rather than offering a row that does nothing.
4. **Move mode cannot push a folder past the system folders.** `moveCollection` orders your
   folders only, so an UP or DOWN that would cross into the system block is refused.
5. **Accent returns focus to the first row, not the Accent row.** Spec §7.4 says the Accent row.
6. **Four device checks are owed.** Move mode on the grid, the collection screen itself, the
   add-to-collection checklist, and a frame count for a 30-press sweep. Another agent was
   driving the same box at the same time: key presses landed in the Google TV launcher and in
   YouTube, and one build replaced another mid-run. The numbers a shared box gives are not
   evidence about one screen. Human or on-device verify pending for these four.
7. **Nothing was committed**, as asked.
