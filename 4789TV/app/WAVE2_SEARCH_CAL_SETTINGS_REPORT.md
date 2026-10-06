# Wave 2 — Search, Calendar, Settings, first run

What one agent built in `:app` during wave 2: the Search screen, the Calendar screen, the
seven-page Settings shell and the first-run flow. Home, Discover, Detail, Streams, the player
and Collections belong to other agents and are not touched here.

---

## 1. Files

### New

| File | What it holds |
|---|---|
| `client/ui/screens/search/SearchSections.kt` | Pure merge, section order, count line and the recent list (spec §12) |
| `client/ui/screens/search/SearchScreen.kt` | The screen: query field, `TvKeyboard`, results band, `RailEdge` |
| `client/ui/screens/calendar/CalendarMonth.kt` | Pure month grid, air-date parsing, "+n", initial focus, keep-column |
| `client/ui/screens/calendar/CalendarScreen.kt` | The screen: month header, weekday row, 6 × 7 grid, day panel |
| `client/ui/screens/settings/SettingsPage.kt` | The seven pages and the status-dot rule |
| `client/ui/screens/settings/SettingsKeys.kt` | Every preference key this TV owns, plus their encode and decode |
| `client/ui/screens/settings/SettingsControls.kt` | `SettingRow`, `ReadoutRow`, `PaneNote`, `StatusDot`, `ChoicePanel` |
| `client/ui/screens/settings/SettingsScreen.kt` | The two-pane shell, page list, panel and dialog host |
| `client/ui/screens/settings/SettingsPages.kt` | The seven page bodies |
| `client/ui/screens/settings/SettingsOverlays.kt` | Modal TV keyboard ("Add by URL") and the licences panel |
| `client/ui/screens/settings/FirstRunSteps.kt` | Pure first-refresh step ladder |
| `client/ui/screens/settings/FirstRunScreen.kt` | First run: QR, receipt, progress card, "Start watching" |
| `app/src/main/assets/licences.txt` | What About → Licences reads |

### Changed

| File | Change |
|---|---|
| `client/ui/AppRoot.kt` | Four things. `showRail = topLevel != null`, so the rail is drawn on Settings. The Settings route became `Route.Settings.PATTERN` with a `page` argument. `PairSync` now hosts `FirstRunScreen`. Imports adjusted. Nothing else in the file. |

Not touched: `Screens.kt`, `ClientGraph.kt`, `AppGraph.kt`, `Routes.kt`, `ClientNav.kt`,
`PlayFlow.kt`, `MainActivity.kt`, `Components.kt`, `Focus.kt`.
`ui/settings/TVSettingsSurface.kt` is left alone. It is now unreachable from the client shell.
Whoever does the dead-code sweep can delete it. The pairing coordinator calls were ported, not
the file.

---

## 2. Preference keys

They all live in `AppGraph.presentationPreferences`, the `receiver_presentation` file, beside
the receiver's existing `auto_frame_rate_enabled`. Constants are in `SettingsKeys.kt`.

| Key | Type | Default | Meaning | Who reads it |
|---|---|---|---|---|
| `discover_rows` | boolean | `false` | Discover draws rows instead of the grid | the Discover agent |
| `canvas` | string | `slate` | `slate` or `black` | `TvTheme(blackCanvas =)` — not wired yet, see §6 |
| `poster_size` | string | `normal` | `normal` or `compact` | the Home and Discover agents |
| `reduce_motion` | boolean | `false` | Every duration in the tree becomes zero | `TvTheme(reduceMotion =)` — not wired yet |
| `screen_fit_percent` | float | `5.0` | Safe-box trim, 3–7 in half steps (spec §19.5) | whoever owns the safe box |
| `auto_next` | boolean | `true` | Play the next episode when one ends | the player agent |
| `audio_language` | string | `Original` | Preferred audio language, by display name | the streams agent |
| `catalog_order_override` | string | absent | JSON array of catalog uids. Empty means the phone's order | Home, later |
| `extra_addons` | string | absent | JSON array of `{"name","url"}` typed in on the TV | the add-on registry, later |
| `recent_searches` | string | absent | JSON array of queries, newest first, capped at five | Search only |

Two notes for whoever picks these up.

1. The JSON is kotlinx.serialization, not `org.json`. The Android JSON classes are stubs. They
   throw on a plain JVM (a test run without an emulator), and these shapes are what the
   settings tests are about.
2. A catalog uid is `AddonCatalog.uid(manifestURL)`. `SettingsKeys.movedBlocks` writes the whole
   flat list when an add-on moves, so a partial override never exists.

---

## 3. Tests

44 new tests, all green, in
`app/src/test/java/com/fourseveneightnine/tv/client/ui/screens/{search,calendar,settings}/`.

| File | Tests | Covers |
|---|---|---|
| `search/SearchSectionsTest.kt` | 12 | Two-character floor. Section order. Empty sections removed. First answer wins on a repeat. `tv` and `show` fold into Series. Count line. Recent list cap and case folding. Per-row cap. |
| `calendar/CalendarMonthTest.kt` | 10 | Six rows of seven. Monday start. Outside days not focusable. Episodes on their air date, in air order. "+n". Episode line fallback. Initial focus ladder. Keep-column on a month change. Labels. |
| `settings/SettingsPageListTest.kt` | 6 | The seven pages in order, slug routing, dot rules |
| `settings/SettingsKeysTest.kt` | 7 | Screen-fit stepping and clamping, order round trip, broken JSON, extra add-ons, single move, block move |
| `settings/FirstRunStepsTest.kt` | 9 | Row order. Nothing starts before the settings save. The add-on counter. No add-ons. A failed add-on still settles. Singular wording. Posters wait on catalogs. A failed catalog fails the posters. |

Run:

```bash
cd "4789TV" && JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :app:testSideloadDebugUnitTest \
  --tests 'com.fourseveneightnine.tv.client.ui.screens.search.*' \
  --tests 'com.fourseveneightnine.tv.client.ui.screens.calendar.*' \
  --tests 'com.fourseveneightnine.tv.client.ui.screens.settings.*'
```

The whole `:app` suite is 534 tests with 3 failures, all in `ProgressRecorderTest`, which
belongs to the playback agent.

There were no tests under `app/src/test/.../ui/settings/`, so none were adjusted.

Compile gate: `./gradlew :app:compileSideloadDebugKotlin` is clean.

---

## 4. How the D-pad works on these screens

The one design decision worth writing down.

Compose runs its directional focus search **after** the key handlers, so a screen root that
consumes LEFT stops focus moving inside the screen at all. `TopLevelScaffold` does exactly that,
which is right for a screen with one column and wrong for a keyboard, a month grid or a
two-pane list. None of these three screens use it.

Instead each screen draws a 2 px focusable strip at its left edge, `RailEdge` in
`SearchScreen.kt`. It sits nearer than the rail's own items, so LEFT at column 1 lands on it, it
opens the rail and hands focus straight on. LEFT anywhere else is an ordinary focus move.

Everything else follows the wave-0 rules: `focusRestorer()` on both lazy lists in Search,
`key()` per item everywhere, no `FocusRequester` inside a lazy item, and the initial focus of
each screen on a node that is always composed — the "A" key, a calendar cell (all 42 are
composed, always), the first Settings row.

---

## 5. Device proof — Den TV (onn 4K Pro, `192.168.4.22:5555`)

Built with `:app:assembleSideloadDebug`, installed with `adb install -r`, started at
`com.fourseveneightnine.tv/com.fourseveneightnine.tv.client.ui.MainActivity`, driven with
`adb shell input keyevent`. The receiver's own pairing was left alone.

Screenshots are in
`/private/tmp/claude-501/-Users-saranpenna-4789-iOS/4a928fab-f5fe-4aa4-8353-f04625051500/scratchpad/`.

| Shot | What it proves |
|---|---|
| `k-02-search.png` | Search draws: title, query field with the "Type a title" placeholder, the 6 × 7 keyboard, "Type a title to search" on the right. Initial focus is on "A", as spec §12.3 asks. |
| `k-04-dune.png` | "DUNE" typed on the on-screen keyboard. "31 results". A Movies row (Dune: Part One 2021, Part Two 2024, Part Three 2026, Dune 1984) and a Series row below, both with posters, titles and years. Focus is still on the keyboard: the results never stole it. |
| `m-03-calendar.png` | Calendar: "September 2026", Prev and Next, the Mon-first weekday header, six rows of seven. Today, the 21st, carries the ring and a cyan day number. Days outside the month are muted. The empty line reads "Nothing scheduled. Follow a series to fill this." — correct, because Continue Watching is empty and the one collection holds no titles. |
| `n-02-settings.png` | The Settings two-pane shell with the rail drawn beside it. Seven page rows. The Add-ons pane lists ten add-ons with a health dot, name, manifest host, On or Off, and a catalog count each. |
| `n-03-pair.png` | Pair & Sync reads "Trusted phone: connected". The receipt is counts only — Addons & Catalogs 10, Debrid & API Keys 9, Metadata & AI 5, Playback 2 — and no value is drawn. The four actions and the Clear-setup note are there. |
| `q-01-look.png` | About: Version 0.2.0 (44), Receiver id `7c62…39b3`, Ports 8791 / 9791, Box "onn 4K Pro Streaming Device, Android 14", Panel 1920 × 1080 · 59 Hz, Engine EXO, plus the Licences and Export diagnostics buttons. |

Not reached on the box: the Accounts, Playback, Look and Jobs panes, "Refresh now", and the
first-run flow. Two things got in the way, neither of them in these files.

1. **Home blocks the main thread**, so the client ANRs and Android force-finishes it within
   about ten seconds of a key press while Home is loading. Five ANRs in a row. Every trace has
   the same stack:

   ```
   "main" prio=5 tid=1 Runnable
     at okhttp3.HttpUrl$Builder.parse$okhttp   (kotlinx JsonTreeReader.read in one trace)
     at ...client.data.addons.HttpSupportKt.getText(HttpSupport.kt:99)
     at ...client.data.addons.StremioClient.catalog(StremioClient.kt:92)
     at ...client.ui.screens.home.HomeViewModel$loadCatalogs$2$1.invokeSuspend(HomeViewModel.kt:438)
     at androidx.compose.ui.platform.AndroidUiDispatcher.performTrampolineDispatch
   ```

   `HomeViewModel.loadCatalogs` runs `services.client.catalog(...)` inside `scope.launch`, where
   `scope` is the Compose scope. The receiver's own log agrees: every `home.catalog.result`
   line is stamped `thread=main`. That is the Home agent's file and is not touched here. A
   background task has been raised for it. Waiting about 40 seconds after launch lets the load
   finish, and the app is then stable — that is how the shots above were taken.

   The same trap was avoided in Search: the whole fan-out runs inside
   `withContext(Dispatchers.IO)`.

2. **The box is shared.** `adb logcat` shows repeated
   `Killing …com.fourseveneightnine.tv… due to from pid N` and `due to installPackageLI` —
   other agents force-stopping and reinstalling the app mid-walk. Several runs ended on the
   Google TV launcher for that reason, not because of anything on screen.

Human or on-device verify pending for the four panes, "Refresh now" and first run.

---

## 6. Open issues

1. **Home blocks the main thread.** See §5. Until it is fixed, nothing on this box can be
   walked for the first half minute after launch, these screens included.
2. **Look settings are stored but not yet applied.** `canvas` and `reduce_motion` need
   `TvTheme(reduceMotion =, blackCanvas =)` in `AppRoot.kt`, and `screen_fit_percent` needs the
   safe box. Both are lines of `AppRoot.kt` outside this agent's ownership, so the values are
   written and read back but change nothing yet.
3. **First run is untested on the box.** The Den TV holds the owner's real pairing and the
   brief forbids clearing it, so the `PairSync` route was never reached. The pairing step, the
   receipt and the progress ladder are covered by `FirstRunStepsTest` and by reading the code.
   Human or on-device verify pending.
4. **No numeric pairing code.** Spec §2.2 draws a "4789 20" code box. The pairing coordinator
   only issues a QR whose fragment carries the secret, so there is no code a viewer could read
   out. The box was replaced by a focusable "Show a new code" row, which is the action the spec
   gave that box anyway.
5. **Calendar empty state says "Open the rail", not "Browse Discover".** `ClientNav` has no
   `openDiscover`, and `ClientNav.kt` is not this agent's file.
6. **Version comes from `PackageManager`, not `BuildConfig`.** `buildConfig` is not enabled in
   `app/build.gradle.kts` and that is a shared file. `PackageManager.getPackageInfo` gives the
   same two numbers.
7. **Diagnostics export writes to the app's own external Downloads folder**
   (`Android/data/com.fourseveneightnine.tv/files/Download/receiver-diagnostics.log`), not the
   shared one. This build declares no storage permission, and asking for one to write a log
   would be the wrong trade. `adb pull` and a file manager both reach it.
8. **Add-on enable and disable is a readout.** The settings document comes from the phone and is
   read-only here, so the page shows On or Off and says where to change it. OK on a row opens
   the move menu instead.
9. **Jobs items and generation come from the snapshot sources only.** The two scheduler job rows
   have no item count of their own, so those cells read as a dash.
10. `ui/settings/TVSettingsSurface.kt` is now dead. Left in place rather than deleted, because a
    delete is a bigger change than this task asked for.
11. **LEFT on Home does not always open the rail.** Seen repeatedly while walking the box: LEFT
    from the first Home card sometimes opens the rail and sometimes moves focus nowhere. That is
    `TopLevelScaffold` and the Home screen, not these files, but it makes the rail hard to reach.
