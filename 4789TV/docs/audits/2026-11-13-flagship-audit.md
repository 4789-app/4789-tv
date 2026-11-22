# TV APK flagship-readiness audit — 13–14 November 2026

The scoped source audit is complete. All 24 accepted defects are fixed and committed. A measured follow-up also keeps a small player-menu optimization. Reliability, cancellation, focus ownership and recovery are stronger. **Real-TV flagship performance is not yet demonstrated.** The earlier emulator startup and memory differences were investigated; the results below separate code improvements from measurement limits.

The TV-only baseline is `244df12ed917e54de992bd0410a8e3e17c40ed2c`. The initial code/QA checkpoint is `a7c3e24813108c8ef5c4d8d5f14dec6554b92627`; the latest verified code checkpoint is `0c4c1596ab5dbbcdacc83444b0485fd2aedea095`. Task commits contain only `4789TV/` paths. Unrelated workspace work was preserved. No physical TV was operated or public release published.

**Scope and evidence.** The baseline inventory contains 572 files. The review excluded 65 phone-only entries and covered all 507 TV-product entries:

| Material | Coverage |
|---|---|
| 473 handwritten text files | All 106,262 baseline lines read; exact ranges and SHA-256 recorded |
| 2 generated profiles | All 49,237 rules structurally checked; no invalid or duplicate rules |
| 32 binary assets | Archive/font/image checks, with visual inspection where appropriate |
| Subsequent changes | Changed code, tests, comments and documents reviewed; independent reviewers found no blocking regressions |

This is reading and changed-line coverage, not proof that every possible runtime state is correct. Dependency binaries received a separate bounded review. The audit report itself is an additional document.

**Defect closure.** There were 27 candidates: 24 accepted defects (2 High, 19 Medium, 3 Low) and 3 manual or unproved items. All accepted defects are verified. The full ledger and failed attempts remain in [the local adjudication](../../.bug-hunter/flagship-20261113/adjudicated-findings.json) and [checkpoint records](../../.bug-hunter/flagship-20261113/fix-strategy.json).

| Area | Result |
|---|---|
| Verification and installer — 4 defects | Forbidden DEX strings are detected reliably; mutating probes require one selected receiver; decoder evidence belongs to the current run; installer checks actual reachability |
| File selection and networking — 2 | Season folders survive TorBox selection; cancelled catalog calls release their sockets and private-state clearing |
| Playback — 7 | Superseded preparation retires safely; accepted local runs finish required bookkeeping; system Stop captures progress; failed watched writes can retry; fractional rates and subtitle encoding/handoff remain accurate |
| UI and recovery — 5 | Calendar keeps the selected episode; missing pickers keep panels usable; IPTV Back restores the real opener; loading Back reaches cancellation; cancelled Next Up does not restart |
| Collections and phone sources — 3 | Catalog picker observes updates, supported required genres are honored, and each phone batch expires at its own deadline |
| HLS recording — 2 | Encrypted initialization uses its declared key/IV; unsupported separate audio fails clearly before a false successful recording |
| Obsolete storage — 1 | Unused plaintext playback-URL writers are removed and their exact legacy preferences are purged |

Additional improvements bound the existing manifest cache to default budgets of **512 entries / 64 MiB**, serialize publication, and preserve the previous complete entry when rename fails. Current commands, font notices, engine descriptions, API comments and draft store disclosures now match the code. Compatibility pins and dated receipts were preserved.

The three manual items remain separate: whole-catalog pagination needs a product decision; early Next Up/prefetch is a feature gap; changed-header identity lacked a concrete publication path. The baseline and final grid both moved from card 8 to card 11 at one virtualized-row boundary. The return-focus fix preserves the actual selected card; it does not claim column preservation.

**Verification.** The complete Android foundation gate and optimized Release/data gate pass. Final XML reports contain:

| Checks | Result |
|---|---|
| TV unit tests | 848 cases per Debug flavor and Sideload Release; zero failures, errors or skips |
| Shared data | 356 cases in Debug and Release; zero failures, errors or skips |
| Contract / phone boundary | 10 / 74 cases pass |
| Native UI/playback/storage | Same 26 cases pass on each of Sideload, Google TV and Play: 78 executions on the selected API 34 emulator |
| Native D-pad / decoder probe | Hidden-control, rapid-tap and backward-seek checks pass; zero decoder recoveries or terminal failures |
| Three Release APKs | Valid signatures, unchanged certificate, expected package/version/targets, non-debuggable, valid ZIP CRCs, 16 KiB ZIP/ARM64 ELF alignment, unchanged native-library bytes |

All three APKs retain version code 126, version name `0.2.27-title-logo`, and minimum API 28. Sideload targets API 28; Google TV and Play target API 34. Native test readiness and accessibility-cache observations were corrected without relaxing behavioral or motion assertions. Earlier failures were retained rather than counted as passing results. The native purge test uses real preferences and Android Keystore plus an **in-memory** Room control.

Latest artifacts and hashes are in [the follow-up artifact manifest](../../../dist/tv-flagship-audit-20261113/performance-final/artifacts.json). [APK comparison](../../.bug-hunter/flagship-20261113/final-apk-evidence-helper/performance-final-comparison.json), [native matrix](../../.bug-hunter/flagship-20261113/perf-final-matrix-result.json), and [D-pad receipt](../../.bug-hunter/flagship-20261113/perf-final-dpad-result.json) record exact inputs and outcomes. The first checkpoint's [artifacts](../../../dist/tv-flagship-audit-20261113/final/artifacts.json) remain preserved. Apple-focused source guards also passed, with zero regressions; they are not Android runtime evidence.

**First-checkpoint performance, retained history.** The same optimized, fully AOT-compiled Sideload workload ran baseline→final, then final→baseline. Each APK received 240 native More-panel keys across six runs; focus returned exactly and playback remained active. No Gradle job ran during timing.

| Measurement | Matched baseline | Final |
|---|---:|---:|
| Median initial Activity display, 6 process starts | 161.5 ms | 221.5 ms |
| Median of six main-thread frame-p95 values | 1.53 ms | 2.44 ms |
| Total frame p95 range | 17–18 ms | 17–19 ms |
| Reported jank range | 96–98% | 92.45–95.83% |
| After-Stop PSS range | 133,181–137,760 KiB | 152,760–156,377 KiB |

Those samples showed about 60 ms higher median initial display and about 19 MiB higher measured memory use. They did not establish a source cause. [Exact samples and the original interpretation](../../.bug-hunter/flagship-20261113/runtime-comparison.json) remain retained. Initial Activity display is not shelf or playback readiness; PSS is not peak memory; host key intervals are not input-to-picture latency.

**Controlled follow-up — 14 November.** Source inspection found no new Main-thread startup I/O. Traced original-baseline and first-checkpoint starts gave nearly equal initial-display medians in one matched round: 182.5 and 181.5 ms. Results varied across rounds, so the earlier 60 ms difference is not a demonstrated code regression.

The test AVD later disappeared from disk. It was recreated with API 34, 1920×1080, density 320, four cores, 1536 MiB RAM and host graphics. Both sides of the following comparison used that same recreated emulator. The saved first-checkpoint APK (`8d5f4d07`) and the new candidate each received 480 real More-panel keys in OFF→ON→OFF→ON tracing phases. All focus-return checks and visible 2x→1x speed-label checks passed. No Gradle job ran during timing.

| Follow-up measurement | Saved checkpoint | New code |
|---|---:|---:|
| Initial display median, 6 starts | 171.5 ms | 188.5 ms |
| Initial display mean | 180.8 ms | 184.7 ms |
| Untraced frame p95 | 17–18 ms | 17–18 ms |
| Untraced reported jank | 90.70–98.00% | 82.98–100.00% |
| Traced reported jank | 0–2% | 0% |
| Median Compose elapsed, three 20-second idle captures | 47.850 ms | 40.655 ms |
| Median Main-thread running time in those captures | 188.499 ms | 176.818 ms |

The change derives the menu's displayed rate from collected snapshot state. Position-only ticks no longer require the menu body to consume the full snapshot. Chapter entry still samples the current position once. Composition elapsed fell in all three repeat captures; its median fell about 15%. Main CPU values overlapped, draw elapsed was not consistently better, and frame p95 was unchanged. This is a modest composition-work improvement, not a general speed or smoothness win. Extra `Compose:recompose` trace records include conditional validation and are not body-execution counts.

The same installed APK and scene repeatedly changed from high reported jank with tracing OFF to nearly zero with tracing ON. GPU-completion/deadline counters also changed. This proves a tracing-dependent emulator result; it does not prove a host-only cause or smooth video on real TVs. These traces cover the MainActivity UI window, not decoder cadence.

Detailed memory capture identified one new spike: 18,792 KiB was `.CompilerMetadata`, which disappeared in the same process about ten seconds later. Noncompiler Dalvik Other was 6,216 KiB immediately and 6,440 KiB after settling, versus 6,308 KiB for the saved checkpoint. Android's [ART compiler arenas](https://android.googlesource.com/platform/art/+/refs/tags/android-14.0.0_r1/runtime/runtime.cc#1724) use this name, and [ART trims unused arenas after compilation](https://android.googlesource.com/platform/art/+/refs/tags/android-14.0.0_r1/compiler/jit/jit_compiler.cc#210). This captured spike was transient compiler working memory. It does not prove the older undifferentiated spike had the same cause. Native heap PSS also rose about 17 MiB after Stop in both APKs. No general memory-reduction or leak-clearance claim is made.

The [follow-up evidence summary](../../.bug-hunter/flagship-perf-20261114/followup-result.json) links exact APKs, raw traces, frame rows and repeated samples. Proposed additions to the native rate test failed at the same menu-readiness steps on the candidate and unchanged baseline; both attempts were reverted and retained. Existing native test source remains unchanged. The established Release remote fixture verified the actual 2x and 1x labels with newly created hierarchy dumps and screenshots. All 78 existing native executions and the final decoder/D-pad probe passed again.

**Remaining acceptance limits.** Selected real devices must still verify frame pacing, HDR/Dolby output, HDMI audio, OEM remote behavior, large libraries and long sessions. Recording byte-assembly tests do not certify every provider or the complete background recording service. Storage failure and Stop before recorder setup limit any universal progress-persistence claim.

Dependency triage queried 172 Maven name/version pairs, including 151 resolved runtime coordinates. A Guava version matched an advisory, but its required Java-object deserialization ingress was not established. Native versions and vendor-fix traces were checked, and published NextLib AAR bytes matched the enabled libraries. This is not a full upstream source audit, reproducible native build or absence-of-vulnerabilities guarantee. Media3/NextLib updates still require the mandated real Fire OS 7 cold-open check.

The Mac's overnight low-power hibernation paused a build for about 15 hours 20 minutes. Recorded build wall times include that pause and are not build-speed benchmarks. The owner approved a temporary 165 GB floor for this audit after approved cleanup; the normal 200 GB reserve remains the default. Source, release artifacts and personal files were preserved.

The resulting code has clearer action ownership, bounded work and verified recovery paths. It is ready for the remaining device acceptance work. A flagship performance label has not been earned by these measurements.
