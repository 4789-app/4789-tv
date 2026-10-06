# Group B — the shared focus and skeleton cost

Date: 2026-09-21. Audit: `app/AUDIT_2026-09-21.md` §1, §2, §4 (Group B), §5.
Spec read: `docs/design/TV_DESIGN_SPEC.md` §3.3, §15.1, §15.4, §16.

Files owned and changed:

- `app/src/main/java/com/fourseveneightnine/tv/client/ui/components/Focus.kt`
- `app/src/main/java/com/fourseveneightnine/tv/client/ui/components/Components.kt`
- `app/src/test/java/com/fourseveneightnine/tv/client/ui/components/SkeletonSweepTest.kt` (new)
- `app/src/test/java/com/fourseveneightnine/tv/client/ui/components/PaintedArtworkTest.kt` (new)

Nothing else was touched. `TvKeyboard.kt` belongs to Group F and was not opened.
Nothing was committed. The TV box was not touched.

---

## Finding by finding

### F06 — focus scale recomposes the card every frame (already fixed, verified, kept)

`Focus.kt:95-102`. `animateFloatAsState` is assigned to a plain `val`, not read by `by`, and the
value is read as `value.value` inside the `graphicsLayer {}` lambda. That is a draw-phase read, so
an animation frame updates the layer and nothing recomposes. No change was needed and none was
made, beyond the pivot below.

### F07 — one shimmer clock, one cached brush, at most six sweeping blocks

Change, `Components.kt:604-656` (`Skeleton`) and `:664-711` (new `SkeletonSweep` object).

Three separate costs were removed.

1. **The per-block infinite transition is gone.** `rememberInfiniteTransition` plus `animateFloat`
   invalidated the block's own composition scope every frame. The clock is now one process-wide
   `MutableFloatState` in `SkeletonSweep` (`Components.kt:674`). It is driven by
   `withInfiniteAnimationFrameNanos`. Every sweeping block drives it with the same frame time.
   The writes are therefore identical. The state notifies once per frame however many blocks are
   up. All of them drive it on purpose. The sweep then cannot freeze when one block leaves.
2. **The `Brush` is built once, not per frame.** It was
   `Brush.horizontalGradient(colorStops = arrayOf(...))` inside the composable body, which
   allocated a brush and a `Pair` array on every frame. It is now built inside
   `Modifier.drawWithCache` (`Components.kt:637-646`). It is rebuilt only when the block's size
   changes. The moving part is a `translate(left = …)` of a fixed-size band. `Size` is a value
   class and `translate` is inline, so a frame allocates nothing.
3. **The number of animated blocks is capped at six.** `SkeletonSweep.MAX_SWEEPING_BLOCKS`
   (`Components.kt:676`). A block asks for a slot in a `DisposableEffect` and gives it back on
   dispose. A block with no slot draws a still `elevated` fill. That is what spec §15.4 asks for
   below the first two rows anyway. Six is the hero's three blocks plus the first three posters.

A cold Home drew seventeen independent infinite animations. It now draws six, at about a third of
the gradient fill and with no per-frame recomposition or allocation at all.

The phase is read **only** inside `onDrawBehind`. Reading `SkeletonSweep.phase` in composition
would put the clock straight back into the frame.

### F13 and F14 — a poster that comes back must not flash its title or fade again

Change, `Components.kt:786-800` and `:826-861` (`TvArtwork` and the new `PaintedArtwork` object).

The flag was `remember(request.url) { mutableStateOf(false) }`. A `LazyRow` item that scrolls out
of the composed window loses its `remember`, so a poster walked ten cards right and ten back came
home with `painted = false`, drew its title over the bitmap, and let Coil fade the bitmap in again.

- `PaintedArtwork` (`Components.kt:841-861`) is an app-level LRU of the urls this session has
  already drawn: a `LinkedHashMap` with `accessOrder = true` and `removeEldestEntry` at
  `MAX_URLS = 512`. It holds urls, not bitmaps, so it is a few tens of kilobytes. This is the
  app-level painted-url cache the brief asked for, without `rememberSaveable`.
  `android.util.LruCache` was not used. It is an Android stub and will not run in a JVM test.
- `TvArtwork` seeds `painted` from that cache (`Components.kt:792-793`), so the title and the
  placeholder fill never come back on a re-entry.
- The crossfade is switched off per request when the url is already known painted
  (`Components.kt:797-800`). Coil already skips its transition for a memory-cache hit. It runs
  the full 190 ms for a **disk** hit. On this box's small heap a row walked twice is a disk hit.
  First paint keeps the 190 ms fade. Every re-entry after that is instant.
- `onSuccess` writes the url into the cache (`Components.kt:820-823`).

The last commit's **`TvArtwork` placeholder change** is kept (`Components.kt:802`). The
`posterPlaceholder` background is applied only while `painted` is false. An opaque bitmap then
never sits on an opaque fill.

**Not done, and outside Group B's two files.** Audit F14 items 2 and 3 ask for two more changes.
Raise `MEMORY_CACHE_PERCENT` from 0.15 to 0.25. Use `RGB_565` for every poster, not only on a
low-RAM box. Both live in `client-data/.../images/ImageLoaderFactory.kt:34`, `:92`. That file has
no owner in §4's seven groups. It is a two-line change. It is also the other half of the owner's
"flickering posters" complaint. **Someone has to be given it.**

### F21 — the ring on card 1 is clipped

Group B cannot fix this one. The note for Group A is below.

A `LazyRow` clips its content on the scroll axis. The card draws the ring. Only the row can relax
that clip. The row is `HomeRows.kt`, which Group A owns. The audit offers
two fixes: the shared overlay, and the `contentPadding` stop-gap. Neither can be written in
`Focus.kt` or `Components.kt`. Drawing the ring **inset** would have stopped the clipping everywhere. It also
puts the cyan on top of 7 px of poster art. That breaks spec §16.13, which draws the ring 2 px
outside the target. It was not done. The exact change is in **Notes for Group A** below.

Group B did the part it could. The scale half of the overflow is now bounded vertically (F22).
The ring's own geometry is unchanged: 4 px cyan plus the 1 px canvas outline.

### F22 — the ring crosses the shelf header

Change, `Focus.kt:66` and `Focus.kt:101`.

`tvFocusScale` now sets `transformOrigin = TransformOrigin(0.5f, 0f)` — horizontally centred,
pinned to the **top** edge. The card grows down and sideways and never up. The ring's top edge now
sits a fixed 7 px above the card at any scale. That is a 2 px gap, a 4 px ring and a 1 px outline.
`ShelfHeaderGap` is 16 dp, which leaves 9 px clear. Downward it needs 354 × 0.06 + 7 = 28 px, and
`RowGap` is 36.

> **The audit's own fix is wrong and was not copied.** §2 F22 says
> `TransformOrigin(0.5f, 1f)` and "the card then grows down". In Compose `pivotFractionY = 1f` is
> the **bottom** edge: scaling about it holds the bottom still and pushes the top up, which is the
> defect, harder. `0f` is the top edge and is what grows the card downward.

The ring itself is untouched: 4 px `TvColor.Focus` 2 px outside the target, 1 px `TvColor.Canvas`
outside that, spec §16.13.

### F30 — the NEW badge

`Components.kt:323-326`. `Badges.New` was `accent` fill, `accent` ink and an `accent` border. It is
now `BadgeSpec("NEW", TvColor.Canvas.copy(alpha = 0.78f), TvColor.TextPrimary)`. That is exactly
what the audit's §2 P2 list writes. It follows spec §3.3, and spec §0.5, where accent is Play
only.

Spec §16.6 still lists an accent-filled NEW badge. **§3.3 and §16.6 disagree. The spec needs
correcting.** §3.3 is the section that names the Home poster, so §3.3 was followed.

### F32 — the hero action's weight and glyph

`Components.kt:348-353` (new constants), `:371` (new parameter), `:392-396` and `:404-419`.

- Spec §16.7: primary and destructive buttons are **24 SemiBold**, secondary and ghost 24 Medium.
  `TvButton` drew every kind in `TvType.ControlLabel`, which is Medium. The weight is now picked by
  kind. `FilledLabelStyle` is a file-level `val`, built once, not per recomposition.
- `TvButton` gained an optional `glyph: (@Composable () -> Unit)?` slot, drawn in a 24 dp box with
  a 12 dp gap before the label, spec §3.3. The inner `Box` became a `Row` to carry it.
- The hero's play triangle is **not** wired up here: `HomeHero.kt` is Group A. See the note below.

### F49 — Toast width

`Components.kt:557-568`. 720 dp → 640 dp, spec §15.1. At 720 the toast also sat 40 px left of
where the spec draws it. `AppRoot.kt:349` pins its left edge to x 640, and 640 wide is centred on
the 1920 canvas. The glyph and label insets already match §15.1: 32 px to the glyph, then 28 plus
16 to the label. They are now commented as such.

### F51 — a button's ring at one width and its fill at another

`Components.kt:92-98`. `TvFocusable`'s `Box` now passes `propagateMinConstraints = true`.

A `Box` drops the incoming minimum constraints by default, so `Modifier.width(240.dp)` on the
caller's modifier reached the ring and never reached the content: the ring drew at 240 and the
orange fill wrapped the label at about 227. Passing the minimums through means the fill is the
width the caller asked for, which is the width the ring is drawn at. With no width given the
minimum is zero, so every card, chip and intrinsic-width button still wraps its content.

> **The audit's suggested fix was not used.** §2 F51 says "add `Modifier.fillMaxWidth()` to the
> inner `Box` at `Components.kt:377`". That stretches a button to whatever space it is measured
> in. Inside `Arrangement.spacedBy` in a `Row`, the first button would take the rest of the row and
> push its siblings off. `propagateMinConstraints` stretches **only** when the caller fixed a
> width. That is the actual defect. One line also fixes `TvDialog` (240), `FindingCard` (280) and
> every `StateBlock` action.

---

## Notes for Group A

These changes belong in `HomeRows.kt` and `HomeHero.kt`. Group B does not edit them.
Each note below is the exact change.

### F21 — card 1's ring is clipped (needed, Group B cannot do it)

`HomeRows.kt:139` and `:128`. Two lines, and they must land together.

```kotlin
// :139  was: contentPadding = PaddingValues(end = 96.dp)
contentPadding = PaddingValues(start = 16.dp, end = 96.dp),

// :128  was: val pivotPx = with(density) { PivotSpec.RowPivotWithinRowDesignPx.dp.toPx() }
val pivotPx = with(density) { (PivotSpec.RowPivotWithinRowDesignPx + 16f).dp.toPx() }
```

Card 1 then starts at x 236, and its ring needs 7 px plus 236 × 0.03 = 7.1 px of sideways growth,
so 14.1 of the 16 px. The pivot moves with it, to 528 px inside the row, which is the audit's
number. Without the second line every row parks its focused card 16 px left of spec §0.3.

The audit's preferred fix — one shared ring drawn by an unclipped `Box` that wraps the `LazyRow`,
reading the focused card's bounds from `onGloballyPositioned` — is also Group A's, and it is the
one spec §3.3 asks for ("Drawn once per row by a shared overlay"). It also removes one `drawBehind`
node per card. The two-line version above is the stop-gap.

### F22 — already handled in `Focus.kt`, nothing to do in `HomeRows.kt`

Do **not** raise `ShelfHeaderGap` from 16 dp (`HomeRows.kt:68`). The pivot change in `tvFocusScale`
means the card no longer grows upward at all, so 16 dp is now 9 px more than the ring needs. Two
fixes for one defect would move every row 4 px and break `rowBlockHeight`.

Note the downward consequence: a focused poster now reaches 28 px below its own box. `RowGap` is
36 dp so rows are fine, but if a shelf is the last thing in the `LazyColumn`, check that the column
does not clip the bottom of the ring.

### F31 — the "See all" card (`HomeRows.kt:333-345`)

Group B's part is nothing: `SeeAllCard` draws its own chevron and label and does not go through
`Components.kt`. Per the audit, replace the `"›"` `Text` in `HeroTitle` with a drawn 44 px chevron
(a `Canvas`, two lines, `TvColor.TextSecondary`, stroke 4 dp, `StrokeCap.Round`) centred at y 140,
and move the "See all" label's offset from y 190 to y 200.

### F32 — the hero's play glyph (`HomeHero.kt:129-134`)

`TvButton` now takes the glyph. Pass it:

```kotlin
TvButton(
    label = actionLabel(),
    onClick = onAction,
    kind = ButtonKind.Primary,
    glyph = {
        Canvas(Modifier.size(24.dp)) {
            val path = Path().apply {
                moveTo(2.dp.toPx(), 0f)
                lineTo(size.width - 2.dp.toPx(), size.height / 2f)
                lineTo(2.dp.toPx(), size.height)
                close()
            }
            drawPath(path, color = onAccentColour)
        }
    },
    modifier = actionModifier.offset(y = 414.dp).width(240.dp),
)
```

Hoist the `Path` into a `remember` if the hero recomposes often. The 24 SemiBold half of F32 is
already done inside `TvButton` and needs nothing at the call site.

`HomeHero.kt:208` has a second `TvButton` — leave it without a glyph unless its own spec asks for
one.

### F34 — row block heights (`HomeRows.kt:259-270`, `:307-319`, `:354-358`)

Group B changed no card text. The audit's numbers stand: set explicit heights on the two text lines
in `HomeContinueCardView` (12 + 28 + 4 + 26 = 70 against the spec's 66) and `HomeFolderCardView`
(8 + 30 + 26 = 64 against 56), then correct `rowBlockHeight`.

### F52 — one `PosterRequest` per recomposition (`HomeRows.kt:211`)

Unchanged by Group B, and now cheaper for a different reason: the F06 pivot work means a focus move
no longer recomposes the card at all, so most of these allocations are already gone. For the rest,
wrap the call:

```kotlin
val poster = remember(card.posterUrl) { PosterRequest.poster(card.posterUrl) }
```

Same at `HomeRows.kt:246` for `PosterRequest.wide(item.stillUrl)`.

---

## Tests

Two new JVM test classes, both pure logic, no Compose and no Android stubs.

`app/src/test/java/com/fourseveneightnine/tv/client/ui/components/SkeletonSweepTest.kt`

- the phase starts at −30% and ends just short of 130% of the block's width (spec §15.4)
- the phase repeats every 1600 ms and never runs backwards inside a period
- a period of zero cannot divide by zero
- at most six blocks sweep at once, a released slot goes to the next block, and a release without
  an acquire cannot drive the count negative
- `tick` turns frame nanos into a phase

`app/src/test/java/com/fourseveneightnine/tv/client/ui/components/PaintedArtworkTest.kt`

- an unseen url has not painted; a marked url stays painted after its item leaves the window
- a null url is never painted and never stored
- two TMDB sizes of the same poster are two urls
- the cache is bounded at 512 and evicts the **least recently used** url, not the oldest
- marking the same url ten times does not grow the cache

---

## Build state

`cd 4789TV && JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :app:compileSideloadReleaseKotlin`

Baseline before this work: green. After this work: **green**.

Three earlier runs failed. Every error was in a file another group was editing at that moment, and
none was in `Focus.kt`, `Components.kt` or the two new tests. Seen and waited out:
`DetailScreen.kt`, `CollectionsCommon.kt`, `SettingsOverlays.kt`, `DiscoverScreen.kt:185`,
`HomeScreen.kt:234`, `StreamsScreen.kt:175`. The fourth run was clean.

`./gradlew :app:testSideloadReleaseUnitTest --tests "…ui.components.*"`: **15 new tests, 0
failures**. `SkeletonSweepTest` 8, `PaintedArtworkTest` 7. `TvKeyboardTest` (Group F) still passes
its 6.

Nothing was committed.

On-box verification is owed and was not attempted. The brief says not to touch the TV over adb.
Audit §5 names the checks:

| Fix | Check | What must change |
|---|---|---|
| F06, F22 | 24 RIGHT presses along a Home row, `dumpsys gfxinfo \| grep -A4 Janky` | compose stage p90 under 12 ms, from 45; janky share under 15%; the ring never touches the shelf header |
| F07 | cold start, `gfxinfo` over the first 3 s | no frame over 32 ms during the skeleton; GPU median under 8 ms |
| F13, F14 | walk one row right 10 cards, then left 10 | no title text reappears over a poster already seen, and no second fade |
| F21 | screencap with the ring on card 1 | the ring stands the same distance off on all four sides — **needs Group A's `contentPadding`** |
| F49, F51 | screencap a toast, and the Stopped plate in the player | toast 640 wide; the ring and the orange fill share an edge |
