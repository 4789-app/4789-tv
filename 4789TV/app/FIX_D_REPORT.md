# Group D — Detail and Streams

Date: 2026-09-21. Source: `app/AUDIT_2026-09-21.md` §2 and §4, Group D.
Files touched: `ui/screens/detail/DetailScreen.kt`, `ui/screens/detail/DetailViewModel.kt`,
`ui/screens/streams/StreamsScreen.kt`, `ui/screens/streams/ReasonPane.kt`,
`ui/screens/streams/StreamRow.kt`, and one new test file.
Nothing outside those paths was edited. The TV was not touched.

---

## What changed

### F11 — Detail backdrop overdraw (P1, fixed)

`Backdrop` drew a full 1920 x 1080 image, a 1560 x 1080 wash and a 1920 x 380 wash on every
frame. That is about 4,480,000 pixels on a 2,070,000 pixel panel, before one rail.

It now copies `HomeHero.kt` `HomeBackdrop`. A sharp w1280 image covers the top-right
1220 x 900 px, which is the area the left column and the rails leave clear. Both fades live
inside that box: 620 px on the left, 320 px at the bottom. It paints no canvas fill of its
own, because the screen's own `Box` already painted one.

Pixels written per frame drop from about 4,480,000 to about 2,050,000.

The image request is now `PosterRequest.backdrop(url)`, the same call Home makes. It asks
for w1280 and keeps the full colour depth, so the gradient does not band at 3 m.

### F24 — the left column sat 80 px high (P1, fixed)

The page's top padding is the poster's 120 px. The spec puts the poster at y 120 and the
logo block at y 200. An 80 px `Spacer` is now the first child of the left column. The poster
is a sibling in the same `Row`, so it keeps y 120.

### F25 — nothing named the title once the page scrolled (P1, fixed)

The scroll state is now held by the screen. A `snapshotFlow` writes one boolean; nothing
reads the scroll value inside composition, so scrolling does not recompose the page.

The strip sits outside the scrolling column. Title at 32 SemiBold, meta under it, a rule at
y 214. Its alpha and the backdrop's dim are read inside `graphicsLayer`, so both tweens cost
a layer value and not a recomposition. The backdrop drops to 35% while the strip is up.

`DetailScroll.showStrip` is pure and tested. The threshold has a 40 px dead band.
A page parked on the boundary does not flash the strip on and off (§9.10.11).

### F27 — developer copy on the cast row (P1, fixed)

Was "Search for X is in the search wave." Now "Searching by cast needs your iPhone."
The shell has no search route that takes a query, so the toast names the device that can do
the job. This is the wording the Streams refusal already uses.

### F41 — the poster fallback title read through the image (P2, fixed)

`DetailArtwork` drew the fallback text under the image for ever. A crossfading bitmap is
see-through while it arrives, so the words read straight through it. It now hides on
`onSuccess`, which is `TvArtwork`'s rule in `Components.kt`.

### F42 — the Cast and More Like This rails jumped (P2, fixed)

Both rails drew a skeleton while the page loaded. Cast and similar titles arrive inside the
one `Meta`. The skeleton was therefore a promise the page could not keep. A title with no cast lost
the block, and More Like This jumped up about 252 px.

A rail is now drawn once it has something in it, and after that it never moves. The hero and
the episodes row still carry the loading state, so the page is never bare.

Departure from the spec: §9.8 asks these two rails for five skeleton cards each. They no
longer get one. Movement under the ring was judged the worse fault. See "Open" below.

### F43 — the ratings chip was one plain string (P2, fixed)

Was `TvChip("IMDb 7.8")`, one string in 22 Medium. It is now a local `RatingChip`: the
provider in 20 Medium `textSecondary`, a 6 px gap, then the score in Space Mono 22
`textPrimary`. Height 36, radius 8, fill `elevated`, 16 px padding, 12 px apart (§9.4).

### F44 — the poster had no badge column (P2, drawn, no data yet)

The column is built: top-right of the poster, 12 px in, chips 28 tall, 6 px apart, at most
three. `DetailBadges.of` is pure and tested. Dolby Vision takes the one HDR slot.

It draws nothing today, and that is the honest state. Cached, 4K and HDR are facts about a
**source**. Only a stream search knows them, and Detail does not run one. A search asks
every add-on on every page open, which would undo the work in F11. The column draws the
moment something hands it a list. Nothing invents one here. See "Open" below.

### F17 — Streams recomposed whole on every row move (P1, fixed)

`var focused by remember { ... }` was read by the screen body, so every ring move recomposed
the title, the count chip, the filter row, all seven rows and the pane.

It is now a state object, and `ReasonPane` takes `selected: () -> RankedRow?`. The read
happens inside the pane. A ring move now recomposes the pane and the two rows that swap
their fill. Rows were already keyed by row id, and that is unchanged.

### F18 — the chip requester lived inside a lazy item (P1, fixed)

`chipFocus` was on the "All" chip, which is an item inside a `LazyRow`. With enough language
chips the row scrolls, "All" leaves the window, and `shell.restoreContentFocus` aims a
request at a node that is no longer attached. That is the dead-remote fault in
`tv-dpad-focus-destroyed-by-loading-shelf.md`.

The requester now sits on the `LazyRow` itself, with `focusRestorer()`. The request lands on
the chip the viewer last used, or on the first one.

### F45 — the add-on name was cut with no ellipsis (P2, fixed)

`row.addonName.take(18)` produced "MediaFusion | Midn" with nothing to say it had been cut.
The `take` is gone. A 250 dp width cap plus `maxLines = 1` and an ellipsis do the cutting.

### Copy

Both new strings were checked with the `writing-for-interfaces` skill and changed on its
verdict. The pane's footer also moved from "long-OK copies the link" (F26) to
"OK plays · hold OK for more". "long-OK" is spec shorthand; the app says "hold OK"
everywhere a viewer meets it, and "row menu" is developer language.

### Kept

`PivotSpec.columnKeepVisible()` on the Detail column is untouched, so the hero stays on
screen at entry. `StreamIds.forStreams` in `StreamsViewModel` is untouched.

---

## Verification

| Check | Result |
|---|---|
| `./gradlew :app:compileSideloadReleaseKotlin` | green |
| `./gradlew :app:testSideloadReleaseUnitTest`, detail and streams | 42 tests, 0 failures |

New tests, all in `app/src/test/.../detail/DetailChromeTest.kt`:

- `DetailScrollTest`, 5 tests: the strip stays away, arrives, holds through the dead band,
  and goes below it.
- `DetailBadgesTest`, 4 tests: order, the one HDR slot, and the cap of three.

**On-box verify is owed.** Nothing here was measured on the onn 4K Pro, because the box was
out of scope. The audit §5 numbers to take there:

- F11: open Detail, hold DOWN eight times, read `gfxinfo`. GPU median under 8 ms, from 13.
- F17: 20 DOWN presses in the Streams list. Compose stage p90 under 12 ms.
- F24, F25: screencap Detail at rest and scrolled. The action row at y 640, the strip at y 54.
- F18: open the rail from Streams and close it. The ring returns to a chip, never nowhere.

---

## Open

1. **F44 has no data source.** The column needs a cached, 4K and HDR fact for the title.
   Today only a stream search knows those. One cheap answer: keep the last search's top row
   per title in memory and read it here. That work sits in `client-data`, which Group D does
   not own.
2. **F42 dropped two rail skeletons.** The other fix is to hold the Cast slot open for ever
   once drawn. That leaves a 252 px hole on a title with no cast. The trade is the owner's
   call.
3. **The row menu holds little.** Long-OK on a source row opens Play, which OK already
   does, and Copy link, which refuses. Spec §10.6 wants four rows there. The new hint is
   accurate about a menu that adds little.

## Nearby, not fixed

- `Components.kt:573` lets a toast run to two lines; spec §15.1 says one line of 42
  characters. Copy is being written against two different numbers. Group B owns that file.
- `CastRow` and `MoreLikeThis` still take a `loading` flag. Nothing passes it now. It can go
  with the next edit to those files.
