# Group F — calendar, search, settings, keyboard

Date: 2026-09-21. Source: `app/AUDIT_2026-09-21.md` §1, §2, §4 (Group F), §5.
Spec read: `docs/design/TV_DESIGN_SPEC.md` §12, §13, §14.

Files changed, all inside Group F's own set:

- `app/src/main/java/com/fourseveneightnine/tv/client/ui/screens/calendar/CalendarScreen.kt`
- `app/src/main/java/com/fourseveneightnine/tv/client/ui/screens/calendar/CalendarMonth.kt`
- `app/src/main/java/com/fourseveneightnine/tv/client/ui/screens/search/SearchScreen.kt`
- `app/src/main/java/com/fourseveneightnine/tv/client/ui/screens/search/SearchSections.kt`
- `app/src/main/java/com/fourseveneightnine/tv/client/ui/screens/settings/SettingsControls.kt`
- `app/src/main/java/com/fourseveneightnine/tv/client/ui/screens/settings/SettingsOverlays.kt`
- `app/src/main/java/com/fourseveneightnine/tv/client/ui/screens/settings/SettingsScreen.kt`
- `app/src/main/java/com/fourseveneightnine/tv/client/ui/screens/settings/SettingsPage.kt`
- `app/src/main/java/com/fourseveneightnine/tv/client/ui/screens/settings/SettingsPages.kt`
- `app/src/main/java/com/fourseveneightnine/tv/client/ui/components/TvKeyboard.kt`

Tests added or extended:

- `app/src/test/.../ui/components/TvKeyboardTest.kt` (new, 6 tests)
- `app/src/test/.../ui/screens/settings/SettingsPanelFocusTest.kt` (new, 5 tests)
- `app/src/test/.../ui/screens/settings/JobScheduleTest.kt` (new, 5 tests)
- `app/src/test/.../ui/screens/calendar/CalendarMonthTest.kt` (+4 layout tests)
- `app/src/test/.../ui/screens/search/SearchSectionsTest.kt` (+3 recents tests)

No file outside Group F was touched. Nothing was committed. The TV was not touched.

---

## What changed, finding by finding

### F53 — no settings choice could be picked (P0)

`ChoicePanel` held no `FocusRequester`. Every choice panel opened with focus still on the
`SettingRow` behind the scrim. `SidePanel`'s LEFT handler sits on a column that was not focused.
So LEFT did nothing either, and BACK was the only key that worked. This killed seven panels: Audio
language, Engine, AI upscaling, Canvas, Discover layout, Poster size, and the add-on move menu.

Fix. `ChoicePanel` now holds one requester. It attaches it to the row a new pure rule names, then
requests it from `LaunchedEffect(header)`. The rule is `ChoiceFocus.initialIndex`: the chosen row,
or the first row when nothing is chosen. The rows are a plain `Column`, never a lazy list. So the
requester is attached by the time the effect runs.

### F56 — the calendar grid ran off the bottom

Measured down the old column, the grid ran y 258 to 1098 on a 1080 canvas. Rows 5 and 6 fell below
the safe box.

Fix. Every band is now a fixed height. The numbers live in `CalendarMonth.Layout`, so a test adds
the column up rather than the box:

| Band | y |
|---|---|
| Title, month label, Prev and Next | 54..120 |
| Rest under the title | 120..132 |
| Weekday header | 132..170 |
| Rest | 170..180 |
| Grid, six rows at a 140 pitch | 180..1012 |

Prev and Next carry a 6 px top pad so they land at y 60, as spec §13.2 has them.

### F57 — the error block painted over the weekday header

`ErrorState` is about 150 px tall and sat inside a 64 dp `Box`. A `Box` does not clip, so it drew
straight over the weekday header and calendar row 1.

Fix. The status block moved out of the column to the screen root, beside the day panel.

- Loading: one line at y 104, height 28. It fits the rest the spec leaves under the title.
- Empty and Error: these carry a 60 px button, so they are drawn at y 300, over the grid, which
  draws empty behind them. Spec §13.6 asks for exactly that on Error.
- On a failure the Retry button takes the ring. The grid behind it stays focusable, so the remote
  works either way. Pressing Retry hands focus straight back to the grid rather than waiting for
  the shell's 900 ms rescue net.

### F58 — the calendar held 44 MB of poster bitmaps

`PosterRequest.poster` pins Coil to 342 px. The grid draws 42 cells of three 44 px thumbs. So up to
126 bitmaps were decoding at about eight times the width they are drawn at.

Fix, three parts.

1. Thumbs ask for 92 px (`THUMB_REQUEST_WIDTH`), the smallest TMDB rung above the drawn size. That
   is about a 14x cut in decoded bytes per thumb. 92 is under `TmdbSize.POSTER_WIDTH`, so the
   low-RAM `RGB_565` path still applies.
2. The day panel's 120 px still asks for 154 px, not the 780 px `PosterRequest.wide` was pinning.
3. The per-title episode cache was an unbounded `ConcurrentHashMap`. It is now an access-ordered
   `LinkedHashMap` capped at 60 titles behind a lock, so walking a year of months cannot grow it
   without end.

`TmdbSize` lives in `client-data`, which Group F does not own, so the two widths are named as
constants in `CalendarScreen.kt` and passed to the public `PosterRequest(url, width)` constructor.
Adding `TmdbSize.THUMB_WIDTH` is still worth doing when `client-data` is next open.

### F59 — the followed-series scan ran on the main thread

`followedSeries` reads `library.collections()` and then one `library.collection(id)` per collection.
Ten collections is ten Room subscriptions and ten list maps on the frame thread as the screen opens.

Fix. `withContext(Dispatchers.IO) { followedSeries(client) }`. The audit suggested
`Dispatchers.Default`; IO is the right pool for database reads, and the point — off the dispatcher
that answers the remote — is the same.

### F60 — a held Backspace deleted one character

`KeyCap.onKeyEvent` returned false for anything that was not a key up, so every repeat Android
sends during a hold was dropped.

Fix. `KeyCap` takes a `repeats` flag, set on Backspace only. A repeating key acts on key down and
swallows the matching key up. One press is still exactly one stroke. The gate is the pure
`KeyRepeat.shouldFire(repeatCount, now, lastFired)`. The first press always fires. A repeat fires
once 60 ms have passed (spec §0.9.6). The last-fired time is a plain `longArrayOf`, not state, so a
held key does not recompose the key under itself.

Letters and Space do not repeat. Spec §12.7.4 names Backspace only, and a repeating letter key
would type "AAAA" from one lean on the remote.

### F61 — LEFT walked out of the modal keyboard

`ModalKeyboard`'s root `Box` had no focus fence. LEFT from key column 1 reached the settings list
behind the scrim. OK there threw the half-typed address away.

Fix. `.focusProperties { exit = { FocusRequester.Cancel } }.focusGroup()`, the same fence
`CollectionsCommon.kt:186` already uses.

### F62 — the licences panel could not be scrolled

The body was a scrolling `Column` holding one `Text`. Nothing inside was focusable, so the scroll
container never saw a D-pad key.

Fix. `.focusRequester(body).focusable()` on the scrolling column, requested on entry. The
`produceState` value was renamed `text` so it no longer clashes with the requester's name.

### F70 — the settings pane was translucent and too tall

`TvColor.Elevated.copy(alpha = 0.55f)` is a 1024 x 922 alpha blend every frame, and
`fillMaxHeight()` ran the pane past the safe bottom at y 1026.

Fix. The flat `elevated` token and a fixed `height(866.dp)`, which is spec §14.2.

### F75 (settings half) — accent on a settings line

"No key or password is inside the code." was inked in `accent`. Spec §0.5 reserves accent for Play.
It is now `textMuted`.

The other half of F75, the My Cloud glyph in `CollectionsScreen.kt`, belongs to Group E.

### F76 — copy

- `CalendarScreen` empty state: "Open the rail" is now "Browse Discover" (spec §13.6). The label
  named the chrome rather than the place it goes.
- `ErrorState` hardcodes "Try again". Spec §13.6 and §12.5 both say "Retry". `ErrorState` lives in
  `Components.kt`, which Group B owns. So Calendar and Search now call `StateBlock` directly with
  `actionLabel = "Retry"`. `ErrorState` itself still says "Try again" for every other caller.
  Changing it is a one-word edit for Group B.
- `TvKeyboard`: the wide key is "Backspace", not "Delete" (spec §12.2). The 188 px key fits it.

### F77 — the Jobs page was missing a column and rebuilt a formatter per row

Fix, two parts.

1. A sixth column, "Next run". The value comes from a new pure `JobSchedule.nextRunLabel`. It is
   the last run plus the scheduler's 6-hour period. It reads "in 4h", "in 30m", "soon", or an em
   dash when the job has never run. One clock read per composition of the page, not one per row.
2. `clockTime` held a `SimpleDateFormat` per call. It is now one per thread in a `ThreadLocal`.
   `SimpleDateFormat` is not safe to share, so a single shared instance was not an option.

The six columns are sized 300 / 130 / 130 / 110 / 110 / 116. That is the 896 px the pane leaves
inside its 24 px side padding. Spec §14.9's offsets put "Next run" at +890, which leaves 30 px. That
is not enough for "in 4h", so the columns were tightened to fit it instead.

### F78 — the recent list blocked the frame that drew Search

`SettingsKeys.readStringList` blocked on the preference file inside a `remember` initializer, and
the write ran on the main dispatcher after the IO block had returned.

Fix. Seeded empty, filled from a `LaunchedEffect` on IO, and the write wrapped in
`withContext(Dispatchers.IO)`. The seed only lands when nothing has been written since, so a search
opened while the file was still being read is not overwritten.

### F82 — the results band was 884 px wide

The screen padded `end = 96.dp`, so the band stopped at x 1824. Spec §12.2 runs it from x 940 to
x 1920 on purpose, so the fourth poster shows a wider slice.

Fix. The end padding is gone. The keyboard column is a fixed 680 px and needs none, so the band now
measures 980 px.

### F83 — every prefix of a query was stored as a recent search

`p1-search.png` shows "DUNE", "DUN" and "DU" as three separate rows. The recent entry was written
on every query change.

Fix, two parts.

1. The write moved to the moment a result is opened, not the moment a search answers.
2. `SearchSections.recordRecent` now drops any stored entry that is a prefix of the new one, case
   insensitively. That clears anything a previous build already stored.

---

## Rules held

- **Every panel keeps something focusable.** The choice panel focuses a row. The licences panel
  focuses its scrolling body. The calendar's failure state focuses Retry, and the 42 grid cells stay
  focusable behind it. The modal keyboard has its keys.
- **No `FocusRequester` inside a lazy item.** The choice panel's rows are a plain `Column`; the
  calendar grid is plain `Column`s and `Row`s; both are always composed.
- **Focus never leaks to the rail.** The 2 px `RailEdge` strip stays on Search, Calendar and
  Settings. The modal keyboard is fenced with `exit = { FocusRequester.Cancel }`.
- **Copy.** No "Oops", "Sorry", "Please" or "!" in any of these files. Every label names the thing
  it does.

## Verification

```
cd "/Users/saranpenna/4789 iOS/4789TV"
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :app:compileSideloadReleaseKotlin -q
```

Green. Zero errors in any Group F file for the whole run.

```
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :app:testSideloadDebugUnitTest \
  --tests '*calendar*' --tests '*search*' --tests '*settings*' --tests '*TvKeyboard*'
```

89 tests, 0 failures. New coverage: calendar layout heights (4), keyboard repeat gate (6). Also the
settings panel focus model (5), the Jobs "Next run" label (5), and the recent-search prefix drop (3).

**On-box verify still owed.** These checks from audit §5 need the onn 4K Pro and a
`sideloadRelease` build, and this run did not touch the TV:

| Fix | Check |
|---|---|
| F53 | Settings → Look → Canvas: the ring is in the panel on entry, and DOWN moves it. |
| F56, F57 | Screencap Calendar, and Calendar with the network off. Row 6 ends above y 1020; the error block does not touch the weekday header. |
| F58 | `adb shell dumpsys meminfo com.fourseveneightnine.tv` after opening Calendar. Total PSS at least 30 MB lower. |
| F59 | Cold-open Calendar, read `gfxinfo` over the first second. No frame over 32 ms. |
| F60 | Hold Backspace in Search for one second and count the characters that go. |
| F61 | Open the settings keyboard, press LEFT from key column 1. The ring stays in the keyboard. |

## Nearby defects found and not fixed (other groups own them)

1. `ErrorState` in `ui/components/Components.kt` hardcodes "Try again". Spec §12.5, §13.6 and §15.5
   all say "Retry". Group B owns the file; Calendar and Search now route around it, but Discover,
   Detail, Streams and Collections still show the wrong word.
2. `ClientNav` has no `openDiscover`. The calendar's "Browse Discover" button opens the rail, which
   is one press short of Discover. The rail is the only route a screen has today.
3. `SidePanel` in `Components.kt` puts its LEFT handler on the panel column. That only works once
   focus is inside the panel. That is why F53 locked the remote out rather than just annoying. A
   fence on the scrim would make every panel safe at once, not one at a time.
4. `RailEdge` lives in `screens/search/SearchScreen.kt` and is imported by Calendar and Settings. It
   is a shared chrome part in a screen file.
