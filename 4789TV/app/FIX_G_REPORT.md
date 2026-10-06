# Fix report — Group G (rail, player, build)

Audit: `app/AUDIT_2026-09-21.md` §4, Group G. Findings F19, F20, F28, F46, F48.
Nothing was installed or run on a television. No commit was made.

---

## What changed

| ID | File | Change |
|---|---|---|
| F20 | `app/src/main/java/com/fourseveneightnine/tv/client/ui/nav/NavRail.kt` | The expanded panel is now the flat `elevated` token with a 1 px right border in `TvColor.Border`, per spec §1.2. It used to be a horizontal gradient from `elevated` to `elevated` — the same fill through a shader, with no edge. |
| F28 | `.../ui/screens/player/PlayerScreen.kt` | A side panel opens with the ring on the row that is already chosen, else on row 0. The focus request is keyed on the track lists, so it runs again when the tracks land. The Audio panel draws a focusable "Loading tracks" row while the list is empty. |
| F46 | `.../ui/screens/player/PlayerScreen.kt` | The Aspect pill and the Aspect rows read `stringResource(mode.labelRes())` instead of the enum identifier. The strings already existed (`controls_fit`, `controls_crop`, `controls_stretch`). |
| F48 | `app/src/main/java/com/fourseveneightnine/tv/ui/DpadThrottle.kt` | `Modifier.composed` is gone. `throttleDpadRepeats` is a plain composable extension that returns `onPreviewKeyEvent` with a remembered handler, so the modifier element compares equal and the node is updated in place. |
| F19 | new `baselineprofile/` module, `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `app/build.gradle.kts` | The `:baselineprofile` module plan §9 requires, with the Macrobenchmark generator. |

### F20 detail

The rail's own items keep `focusProperties { canFocus = expanded }`, so a collapsed rail is
never focusable. That was already correct and was left alone.

The scrim over the content behind the rail is **not** in this change. It is one line in
`AppRoot.kt:312`, which Group A owns. Group G did not touch that file.

### F48 detail — the gate numbers

The gate stays at 80 ms horizontal and 112 ms vertical. Spec §0.9.6 says 60 ms.

The audit (§2, F48) says to edit the spec, not the code. The reason: on the onn 4K Pro a 60 ms
gate still runs the focus search faster than the frame it feeds.

`docs/design/TV_DESIGN_SPEC.md` is not a Group G file, so that edit is **owed**. Spec §0.9.6
needs to read 80 ms horizontal, 112 ms vertical, with the measurement as the reason. A pointer to
this report sits in the code beside the two constants.

---

## F19 — the baseline profile

### Files

- `settings.gradle.kts` — `include(":baselineprofile")`.
- `gradle/libs.versions.toml` — added only. Versions `benchmark = 1.4.1`,
  `profileinstaller = 1.4.1`, `androidxTestExtJunit = 1.3.0`, `uiautomator = 2.3.0`; the four
  matching libraries; plugins `android-test` (AGP 8.12.2) and `androidx-baselineprofile`.
- `build.gradle.kts` (root) — the two new plugins declared `apply false`. **This file is outside
  the Group G list.** It could not be avoided: Gradle refuses `com.android.test` with a version
  in a subproject while AGP is already on the build classpath without one. The change is two
  lines in the `plugins` block and touches nothing else.
- `baselineprofile/build.gradle.kts` — a `com.android.test` module, `targetProjectPath = ":app"`,
  minSdk 28, JDK 17, and the same `channel` flavour dimension `:app` uses (`sideload`, `play`).
  Without the matching dimension Gradle cannot resolve which app variant a run belongs to.
- `baselineprofile/src/main/java/.../BaselineProfileGenerator.kt` — the generator.
- `app/build.gradle.kts` — three lines: `alias(libs.plugins.androidx.baselineprofile)`,
  `implementation(libs.androidx.profileinstaller)` and
  `baselineProfile(project(":baselineprofile"))`. The release build type with R8 and the
  `composeCompiler { stabilityConfigurationFiles }` block are untouched.

### The journey the generator drives

`pressHome`, launch, wait for idle. Then UiAutomator D-pad key events.

12 RIGHT along a Home row. That is more than the composed window, so the row recycles.
One DOWN and one RIGHT across a row boundary. OK into Detail. OK into Streams.
Four DOWN along the stream list. BACK twice.

`includeInStartupProfile = true`, so a startup profile is written as well.

The package under test comes from the `targetAppId` instrumentation argument the plugin passes,
so both channels work from one test.

### The command for the orchestrator — run this ON the box

Panel awake, one box attached, no other agent on it:

```sh
cd "/Users/saranpenna/4789 iOS/4789TV"
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :app:generateSideloadReleaseBaselineProfile
```

### Where the profile lands

`saveInSrc = true` and `baselineProfileOutputDir = generated/baselineProfiles` (both read back
from `:app:printBaselineProfileExtensionForVariantSideloadRelease`), so the run writes:

```
app/src/sideloadRelease/generated/baselineProfiles/baseline-prof.txt
app/src/sideloadRelease/generated/baselineProfiles/startup-prof.txt
```

The next `:app:assembleSideloadRelease` picks them up with no further wiring. Commit both files.

### What the generate task does on its own

The plugin adds two build types derived from `release`: `nonMinifiedRelease` (R8 off, so the
profile names real classes) and `benchmarkRelease`. `generateSideloadReleaseBaselineProfile`
builds `sideloadNonMinifiedRelease`, installs it with the test APK, runs the journey, merges the
result and copies it into `src/`.

---

## Verification

| Check | Result |
|---|---|
| `./gradlew :baselineprofile:compileSideloadNonMinifiedReleaseKotlin` | GREEN |
| `:app:generateSideloadReleaseBaselineProfile` exists | YES, with `copySideloadReleaseBaselineProfileIntoSrc` |
| `./gradlew :app:assembleSideloadNonMinifiedRelease --dry-run` | GREEN. No R8 and no resource-shrink task in the graph, so the release build type's `isShrinkResources = true` does not break the generator's variant. |
| `./gradlew :app:compileSideloadReleaseKotlin` | GREEN |

Both commands, for the record:

```sh
cd "/Users/saranpenna/4789 iOS/4789TV"
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :app:compileSideloadReleaseKotlin -q
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :baselineprofile:compileSideloadNonMinifiedReleaseKotlin
```

The app compile was run six times while six other agents edited the same module. The earlier runs
failed, and every error named a file Group A, C, D, E or F owns and was mid-edit. No run ever
named `NavRail.kt`, `PlayerScreen.kt` or `DpadThrottle.kt`. The last run was clean.

## On-device verification owed

- F20: screencap with the rail expanded. The panel edge must read as an edge. The scrim behind it
  is Group A's line.
- F28: open the Audio panel the instant playback starts. The ring must be on a row, on the chosen
  track once the tracks arrive.
- F46: the Aspect pill must read "Aspect · Fit", and the panel rows "Fit", "Crop", "Stretch".
- F19: `receiver-diagnostics.log`, `app.start` to `home.firstShelf` under 1.5 s (plan §9).
