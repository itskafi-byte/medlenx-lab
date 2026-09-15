# Handoff — continuing from Step 6

Written at the end of Step 5 so the next session can pick up without re-deriving
anything. Everything here was verified by reading the named file; nothing in this
repository has been compiled (no JDK/Gradle/Android SDK in the sandbox).

## Where things stand

45 Kotlin files (7,166 lines) under `android/app/src/main/java/com/medlenx/lab/`.

| Step | State |
|---|---|
| 1 Scaffold, tokens, navigation shell | done |
| 2 Data/API layer — VL client, models, Room, catalogue, offline queue | done |
| 3 Scan flow — capture, viewer, laser scan, GPS geofence | done |
| — Figma reconciliation (11 properties, 5 colours, 2 animations) | done |
| — Backdrop blur via haze 1.0.2 | done |
| 4 Verification — doctor, medicines, GPS, saved receipt | done |
| 5 Analytics — hero, filters, KPIs, 4 charts, live feed, recent list | done |
| 6-9 | pending |

> **The earlier commits no longer exist.** The sandbox was re-cloned from GitHub and
> the local-only commits (`8723813`, `3898293`, `89f15cc`, `c816b74`, `51dbeb6`,
> `0431570`) were never pushed, so they are unrecoverable — `git cat-file` reports them
> all gone and the reflog starts at the clone. Every *file* survived; the work was
> re-committed as one commit and **pushed** this time. Treat the current HEAD as the
> only valid history.

Read `FIGMA_RECONCILIATION.md` first — it is the authoritative record of how the
Compose theme maps onto the Figma export, including the two claims in it that turned
out to be wrong and were corrected.

## The Figma export

The design source is **not** in this repo. It lives at the root of branch
`itskafi-byte-patch-1`, and direct file attachments do not reach the sandbox — use the
GitHub API:

```bash
gh api "repos/itskafi-byte/medlenx-lab/contents/App.tsx?ref=itskafi-byte-patch-1" \
  --jq '.content' | base64 -d > App.tsx
```

`App.tsx` is 145 KB / 2,282 lines. **Never `cat` it** — read it in slices. Two things
that trip people up:

- The design system is expressed as **inline React `style={{}}` objects with raw
  numbers**, not Tailwind classes. Grepping for `rounded-*` or `text-*` finds nothing.
- The export's **content is mock data**. Proven: `HubHealthDays` hardcodes World COPD
  Day as day 10, while `data/health_days.json` says month 11 / day 18. Figma is the
  layout source; `data/*.json` is the content source.

## Step 6 line map

Everything Step 6 needs, with exact ranges in `App.tsx`:

| Component | Lines | Size |
|---|---|---|
| `AnalyticsOverview` | 1069–1211 | 143 |
| `LiveRecentScans` | 992–1068 | 77 |
| `RecentPrescriptions` | 748–777 | 30 |
| `FilterSheet` | 1212–1267 | 56 |
| `KPICard` | 100–127 | 28 |
| `MiniKPI` | 128–137 | 10 |
| `DarkHero` | 251–260 | 10 |
| `ProgressBar` | 240–250 | 11 |

Charts are **recharts 3.10.1** (8 chart elements in the file). The Compose `Canvas`
charts must match recharts' visual output, not an abstract spec.

Step 7 for planning: the Hub sub-tabs (`HubScreen` 1560-1590, `HubDrugIndex`,
`HubTrips`, `HubNews`, `HubJobs`, `HubHealthDays`). `SearchOverlay` 321-358 is Step 9.

**Remember the export's content is mock data.** Step 7 reads real values from
`data/*.json`; Figma supplies layout only.

## What Step 4 deliberately left stubbed

Each of these is a real gap, marked with a comment at the call site:

1. **`RecentPrescriptions` is not rendered.** `ScanSavedSection` ends after the success
   card. The export renders the shared list underneath it (App.tsx:744). It is shared
   with analytics, so it lands in Step 5 — then add it to `ScanSavedSection`.
2. **`onVerifyAgainstMedex`** is a no-op — the Medex re-check needs the Step 7 drug
   index.
3. **`onReportMisId`** is a no-op — escalation queue is Step 9.
4. **`onOpenAudit`** is a no-op — Rx Audit Summary is Step 6.
5. **`ScanViewModel.save()` does not persist.** It generates `A-{100 + medicineCount}`
   locally and sets the phase to `Saved`. The Room write and audit trail land with
   Step 6; `save()` is the single place that has to change.
6. **`repCode` is hardcoded to `"MR001"`** — it should come from the officer profile in
   Step 9 settings.
7. **Doctor field confidences are hardcoded** (96 / 92 / 88 / 85 in
   `enterVerification`). MedLenX VL returns one confidence per medicine, not per doctor
   field, so there is nothing real to bind yet.
8. **`EnrichedMedicine.toCardData()` is written but unused.** The scan flow maps from
   `VlMedicine` because that is what `VlScanResult` carries. The enriched mapper is
   waiting on the `medicine_matcher.py` port — once that exists, switch the mapping and
   the cards gain real `matchType`, catalogue resolution and compliance pills.

## Known rough edges

- **`ScanPhase.Done` is now unreachable.** `enterVerification()` moves straight to
  `VerifyDoctor`, which is what Figma does. The `Done` branches in `ViewerCard`
  (ScanScreen.kt around lines 338 and 365) are dead but harmless; they were left rather
  than editing verified Step 3 code for cosmetics.
- **`lowConfidence` on `EnrichedMedicine` still uses `< 80`** (the web threshold) while
  `confidenceBand()` now flips at 85 per Figma. Nothing reads `lowConfidence` yet. Align
  it when the matcher is ported.
- **The backdrop blur is unverified.** haze 1.0.2 was chosen by date to match the
  Compose BOM 2024.09.02 pin set, with group and version confirmed against the repo tag,
  but the dependency has never been resolved by Gradle here. The shell restructure that
  goes with it (content Box takes only the bottom inset; `ScanScreen` carries the top
  offset inside its own `verticalScroll`) touches layout every screen depends on —
  build it first and check the other four tabs are not shifted.

## Constraints that still apply

- **Nothing truncated.** Every field, badge, pill, column, footnote and empty state
  survives. Semantic values wrap rather than ellipsise.
- **48dp minimum touch target**, enforced by `MlxD.TouchTarget`. This makes some
  controls taller than the mock (segmented tabs are ~26dp in Figma); that is deliberate.
- **Vector icons, not emoji.** The export draws every icon as an emoji glyph; this was
  decided against. The one place that shows is `MlxSegmented` and the nav bar.
- **Compose, no Hilt.** Manual DI through `AppGraph.create(app)`.
- **`NavHost` must never be wrapped in `verticalScroll`** — children get unbounded
  height and every `LazyColumn` in Steps 5–8 would crash. Each screen owns its scrolling.
- **Commit only `android/`.** Check with
  `git diff --cached --name-only | grep -v '^android/'` before every commit.

## The failure mode to guard against

The recurring mistake across this build has been **inventing Compose APIs mid-write**.
Caught in Step 4 alone: `SectionLabel` (real name `SectionHeader`), `MlxShape.None`
(does not exist), `MatchType.Catalogue` (the enum is Exact/Fuzzy/Live/None),
`ViewerTransform.reset()` (real name `fit()`) and `transform.zoomLabel()` (it is a
`zoomPercent` property). Also three missing imports: `Box` in `Pills.kt`, `offset` in
`MedicineCard.kt`, `MlxD` in `ScanScreen.kt`.

After writing any file, run the audit that catches these:

```bash
cd android/app/src/main/java
# braces balanced + package matches directory
for f in $(find . -name '*.kt'); do
  o=$(tr -cd '{' <"$f"|wc -c); c=$(tr -cd '}' <"$f"|wc -c)
  [ "$o" != "$c" ] && echo "MISMATCH $f"
  p=$(grep -m1 '^package ' "$f"|sed 's/^package //')
  d=$(dirname "$f"|sed 's|^\./||;s|/|.|g'); [ "$p" != "$d" ] && echo "PKG $f"
done
# every theme member referenced actually exists
for m in $(grep -rhoE "\bMlx\.[A-Za-z0-9]+" . | sort -u | sed 's/Mlx\.//'); do
  grep -q "val $m " com/medlenx/lab/ui/theme/Color.kt || echo "Mlx.$m MISSING"
done
```

`com.medlenx.lab.BuildConfig` always reports unresolved — it is generated by AGP.
`weight`, `align` and `then` report as missing imports; they are scope members.
`getValue` / `setValue` look unused but are required by the `by` delegate.

## Building

```bash
cd android
# local.properties needs sdk.dir, and OPENROUTER_API_KEY for real scans
./gradlew assembleDebug
```

The Gradle wrapper is committed (`gradlew`, `gradlew.bat`,
`gradle/wrapper/gradle-wrapper.jar` — Gradle 9.7.1), so no separate Gradle install is
needed; `chmod +x gradlew` if the zip dropped the executable bit.

The nine JSON datasets are committed under `app/src/main/assets/data/`, so the module
builds with nothing outside `android/`. `checkMedLenXAssets` runs on `preBuild` and
fails the build if any dataset is missing, rather than producing an APK with a
silently empty drug index. `refreshMedLenXAssets` re-copies them from
`-Pmedlenx.dataDir=/abs/path` on request and never runs on its own.

**Earlier note, now superseded.** This log previously recorded that the wrapper was
absent and the datasets were copied in from the repository's `data/` directory at
build time. Both changed when the branch was made self-contained — see the commit
that removed the web app.

Without an API key the app runs in demo mode: `VlOutcome.NoKey` maps to
`ScanPhase.NeedsKey`.

## External API verification (done 2026-09-14, before Step 6)

Gradle cannot run in this sandbox (no JVM, no reachable JDK/package mirror, no compiler
download — all of `dl.google.com`, `repo.maven.apache.org`, `services.gradle.org`,
`deb.debian.org` time out). So instead of a compile, the external symbols were resolved
against **upstream GitHub source**, which is the same check a compiler does for name
resolution.

| Checked | Source of truth | Result |
|---|---|---|
| 37 `Icons.Filled.*` names | `google/material-design-icons`, 2,180 icon names | **36 OK, 1 invented** |
| `drawLine` / `drawRect` / `drawRoundRect` / `drawArc` | `androidx` `DrawScope.kt` | all params match |
| `CornerRadius(x, y)` | `androidx` `CornerRadius.kt` | factory fn confirmed |
| `animateFloat` · `tween` · `infiniteRepeatable` · `rememberInfiniteTransition` | `androidx` animation-core | all confirmed |
| `detectTransformGestures` | `androidx` `TransformGestureDetector.kt` | confirmed |
| `FlowRow` (+ `@ExperimentalLayoutApi`) | `androidx` `FlowLayout.kt` | confirmed |
| `DropdownMenu` · `DropdownMenuItem` | `androidx` m3 `Menu.kt` | confirmed |
| `haze` / `hazeChild` / `HazeStyle` | `chrisbanes/haze` 1.0.2 | confirmed |

**Bug found and fixed:** `Icons.Filled.Cog` does not exist in Material Icons. `Destination.kt`
used it for both Settings and Help. Replaced with `Icons.Filled.Settings` and
`Icons.Filled.HelpOutline` (both verified present upstream).

**Still unverified:** Gradle/AGP plugin resolution, KSP code generation (the `*_Dao_Impl` and
Room schema classes), `BuildConfig` generation, and every overload ambiguity that only a
real type-checker resolves. Those need one local `./gradlew assembleDebug`.

## Toolchain bump + confidence fix (2026-09-14)

**Confidence.** `EnrichedMedicine.lowConfidence` used a hardcoded `< 80` while the
rendered `ConfBadge` (App.tsx:70-75) flips at 85, so the property contradicted the badge
on screen. Five more sites duplicated the 70/85 magic numbers. All now derive from
`confidenceBand()` in `data/model/Confidence.kt` - the single source of truth.
`skinFor` is an exhaustive `when` over the enum, so a future fourth band is a compile
error rather than a silently mis-skinned card.

**Toolchain.** Bumped to Gradle 9.7.1 / AGP 9.3.2 / Kotlin 2.3.0 / KSP 2.3.4 /
Room 2.8.3 / compileSdk 36, sourced from Google's `android/nowinandroid` sample so the
set is known to build together. See README "Building" for the DSL changes that came
with it (`android.kotlinOptions` is gone in AGP 9) and the haze 1.7.3 migration off
`haze`/`hazeChild` onto `hazeSource`/`hazeEffect`.

## EXIF provenance on the GPS pin (2026-09-14)

`pinGps()` now falls back to the coordinates in the photo's EXIF header when
`lastKnownFix()` returns null (providers off, or no cached fix). Indoors this is
common, and the geofence was losing its GPS evidence entirely.

`GpsFix` carries a `GpsSource` (`Device` / `PhotoExif`) and the geo strip caption
says which one produced the pin, so an EXIF coordinate is never passed off as a
live fix. `reverify()` preserves the provenance when it rebuilds the fix from the
stored lat/lng.

Note this is *not* an image-orientation fix. Coil's default `BitmapFactoryDecoder`
already applies EXIF orientation (`ExifOrientationPolicy.RESPECT_PERFORMANCE`
covers jpeg/webp/heic/heif), and the bytes sent to MedLenX VL are the original file
untouched, so the tag survives. `exifinterface` was pinned but unused; it now has a
job.

## rx_audit.py ported (2026-09-14)

`app/rx_audit.py` (187 lines) is now `data/repo/PHash.kt` + `data/repo/RxAudit.kt`.
Pure and deterministic, nothing touches the DB or network, so Step 6's drawer can be
built straight on top of it.

Ported: `compute_phash` (DCT-II pHash), `hamming_distance`, `is_duplicate_hash`,
`same_company_loose`, `build_market_share`, `items_to_csv`, `items_to_clipboard`.

Three details that would have silently diverged, each checked against the Python:

- **`round()` is half-to-even.** `round(94.5)` is 94, half-up gives 95. Ported as
  `Math.rint`, not `Math.round`.
- **`round(x, 1)` rounds on the exact decimal value of the double.** `rint(v*10)/10`
  disagrees at halfway points (`round(0.05,1)` is 0.1, the scaled form gives 0.0).
  Ported as `BigDecimal(v).setScale(1, HALF_EVEN)`. Across all 860 share values
  `build_market_share` can actually produce the two agree, so this was not a live bug -
  it is correct in general rather than correct by luck.
- **A 16-hex-char digest overflows a signed Long** whenever its first digit is 8 or
  higher, so `toLongOrNull(16)` would return null and `is_duplicate_hash` would report
  "not a duplicate" for a genuine match. Ported with `BigInteger`.

NOT byte-identical: the pHash digest. Pillow resizes BICUBIC, `createScaledBitmap` uses
bilinear. The 8-bit threshold absorbs that, and a standalone app only ever compares
hashes it computed itself - but do not compare these against hashes stored by the
FastAPI backend. Documented in PHash.kt.

Still unported: `database.py` (2,528), `pharma_hub.py` (589), `medicine_matcher.py`
(362), `intelligence.py` (271).

## Step 6, part 1: RxAuditScreen (2026-09-14)

`ui/screens/rx/RxAuditScreen.kt` ports Figma `RxAuditSummary` (App.tsx:778-897) -
header card, the four filter pills, the clinical strip with its therapeutic-class
bar, the duplicate-fraud notice, the item cards, and the market-share footer with
both export buttons. Everything is derived from the real scan result via
`data/repo/RxAudit`, not the export's mock array.

Two deliberate departures from the mock, both documented in the file's KDoc:
- The export renders "Duplicate Rx Detected" on every *competitor* row
  (App.tsx:857), a placeholder bug unrelated to duplication. Driven by a real
  `duplicateOfRxIds` list instead.
- The therapeutic-class bar is computed from the medicines' own classes rather
  than the hardcoded 33/17/17/17/16.

The Figma's two confidence thresholds are both reproduced and are now named in
`data/model/Confidence.kt`: 85 colours the badge, 80 drives the "<80%" filter, the
#FFF7ED row wash and the "Verify against Medex" link.

### NOT DONE - routing and state

The screen is **not reachable yet**. `ScanScreen` builds its own `ScanViewModel`
with `viewModel(factory = ...)`, and each NavHost entry has its own ViewModelStore,
so a sibling `rx-audit` destination would get an empty scan. Sharing the result
means hoisting the ViewModel into `MedLenXShell` and passing it down to both
screens - a signature change across the shell, worth doing deliberately rather than
tacked on. Also still missing from Step 6: `DoctorPitchCard` (App.tsx:898-991).

## Step 6, part 2: DoctorPitchCard + a blocker found (2026-09-14)

`ui/screens/rx/DoctorPitchCard.kt` ports App.tsx:898-991: the gradient header with
its close button, the compliance pills, the competitor-vs-own compare grid, the
price-position note, the bioequivalence box, the pitch script, and the PDF / copy
footer. Self-contained overlay (scrim + bottom sheet at 88% height) so any screen
can show it from a flag.

The bioequivalence paragraph is a **parameter**, not the export's hardcoded copy:
the export asserts DGDA registration and full bioequivalence certification for
products that may have neither, and shipping that verbatim would put an
unverifiable clinical claim in front of a doctor.

### BLOCKER: nothing constructs an EnrichedMedicine

Wiring the audit route is blocked on a missing stage, not on navigation.

`ScanViewModel.kt:164` does `cards = result.medicines.map { it.toCardData() }`,
mapping the raw `VlMedicine` straight to the display card. `EnrichedMedicine` is
**never constructed anywhere in the codebase** - the
`EnrichedMedicine.toCardData()` overload at Verification.kt:769 has no caller,
because no enrichment ever produces one.

So the app currently skips MedEx catalogue matching entirely: no company
verification, no NEML / DGDA / TRIPS flags, no substitution. `RxAuditScreen` is
correctly typed against `EnrichedMedicine`, which is the right model for what it
renders - but there is no real data to feed it yet.

The missing stage is `app/medicine_matcher.py` (362 lines), still unported. Wiring
the route before that would mean fabricating `EnrichedMedicine` instances, i.e.
shipping mock data into a compliance screen. Recommended order: port
`medicine_matcher.py`, populate `ScanUiState` with the enriched list, then route.

## Step 6 wiring — partially done

Landed and committed:

| Piece | Commit |
|---|---|
| `MedicineMatcher.kt` — port of `app/medicine_matcher.py` (`sequenceRatio` verified against `difflib` on 36 pairs, 0 mismatches) | `97d0d7d` |
| `MedicineEnricher.kt` + `MedexDao.all()` + `ScanRepository.medexIndex()` (cached) + `ScanUiState.enriched`, populated in a now-suspend `enterVerification` | `21aa9aa` |
| `Destination.RxAudit` (`"rx-audit"`) + `fromRoute` | this commit |

`EnrichedMedicine` is now actually constructed, so the company/match-type/image
fields the audit drawer reads are real instead of defaulted. `neml`, `dgdaAlert`,
`isAntibiotic`, `broadSpectrum`, `therapeuticClass`, `tripsWatch` and
`substitution` remain at defaults until `database.py` / `pharma_hub.py` are
ported — deliberately null, not guessed.

**Still unwired:** the `composable(Destination.RxAudit.route)` block in
`MedLenXShell`, the hoisting of `ScanViewModel` out of `ScanScreen` (it is still
created inside `ScanScreen`, so each navigation recreates the store), and the
on-screen trigger. `ScanScreen`'s real signature is
`fun ScanScreen(modifier: Modifier = Modifier)`; an attempt to patch it against
assumed text failed and was abandoned rather than forced. Next pass should read
`ScanScreen.kt` and `MedLenXShell.kt` fresh before editing.

Data the route will need: `rxId` = `SavedReceipt.rxNumber`, `repId` =
`SavedReceipt.repCode`, `ownCompany` = `appGraph.deviceState.companyName`,
`medicines` = `ScanUiState.enriched`, `offTerritory` = `ScanUiState.geo.offTerritory`.
There is no officer-profile reader in `AppGraph` yet; `OfficerProfileEntity`
(`company`, `employeeId`) exists in Room and is the better long-term source.

## Step 6 wiring — complete

| Change | File |
|---|---|
| `profileDao` added to the composition root | `data/config/AppGraph.kt` |
| `officerProfile` observed from Room in its own coroutine (`observe()` never completes, so collecting it alongside the location cascade would have starved it) | `ui/screens/scan/ScanViewModel.kt` |
| `fun ScanScreen(vm, onOpenAudit, modifier)` — VM no longer created inside; `onOpenAudit` reaches the existing `ScanSavedSection` callback | `ui/screens/scan/ScanScreen.kt` |
| `MedLenXShell(topBarState, appGraph, …)` hoists `ScanViewModel`, renders `Destination.RxAudit`, hosts the `DoctorPitchCard` overlay, adds `copyToClipboard` | `ui/shell/MedLenXShell.kt` |
| Passes `appGraph` | `MainActivity.kt` |

Route inputs, each against the real signature (not assumed):
`rxId` = `SavedReceipt.rxNumber` · `repId` = `officerProfile.employeeId` falling back to
`SavedReceipt.repCode` · `ownCompany` = `officerProfile.company` · `medicines` =
`ScanUiState.enriched` · `offTerritory` = `ScanUiState.geo.offTerritory`.

`ownCompany` deliberately moved off `DeviceStateRepository.companyName`: that value only
mirrors whatever the last scan header showed and stays null until a scan runs, which
would have classified every brand as a competitor. `OfficerProfileEntity` is the
authoritative source.

`onPitchCard` is wired, not stubbed: it opens `DoctorPitchCard` when
`EnrichedMedicine.substitution` is present and toasts an explanation when it is not —
`substitution` is always null today because `pharma_hub.py` is still unported, so a
silent no-op would have looked like a dead button.

`onExportCsv` / `onCopyClipboard` both write to the system clipboard. There is no
DocumentsUI write path yet; a pasteable result beats a half-wired SAF picker.

## Backend port: compliance.py + intelligence.py

All seven previously-blank `EnrichedMedicine` fields are now populated.

| New file | Port of |
|---|---|
| `data/model/RegulatoryData.kt` | wire models for `neml_list.json` (165 molecules), `trips_waiver.json` (26), `dgda_prices.json` (32 prices / 4 banned / 3 adjusted) |
| `data/repo/Compliance.kt` | `app/compliance.py` (515 lines) minus the geofence half, already ported as `Geofence.kt` |
| `data/repo/Intelligence.kt` | `app/intelligence.py` (271 lines) |
| `data/repo/PyMath.kt` | Python `round()`/`f"{x:.2f}"` semantics, extracted so `RxAudit` no longer keeps a private copy |
| `data/repo/RegulatoryRepository.kt` | the module-level `_NEM_CACHE` / `_TRIPS_CACHE` / `_DGDA_CACHE` dicts, as one process-lifetime holder |

Three rules taken from `main.py`'s assembly rather than the module docstrings, because
the docstrings are misleading:

- `broadSpectrum = abx && isBroadSpectrum(...)` — gated on the antibiotic test, so a
  broad-spectrum keyword on a non-antibiotic row does not set it.
- `therapeuticClass` uses the NEML class *directly* when the molecule is listed, even
  when that class is blank, and only falls through to keyword resolution otherwise.
- `Compliance.norm` strips punctuation; `Intelligence.normaliseKey` does not. They are
  different functions in Python and stay different here — collapsing them would break
  brand matching on names like "Co-trimoxazole".

**Parity was verified by execution, not review.** Both Python modules were run against
the real datasets with the Kotlin transliterated back into Python and diffed:

| Check | Cases | Mismatches |
|---|---|---|
| `norm` | 205 molecule probes | 0 |
| `moleculeKeyMatch` | ~42,000 pairs | 0 |
| `nemlLookup` / `tripsLookup` | 400 pairs | 0 |
| `isAntibiotic` / `isBroadSpectrum` / `resolveTherapeuticClass` | 420 | 0 |
| `dgdaCheck` + `priceCeilingAlert` | 880 (44 brands x 4 generics x 5 MRPs) | 0 |
| `findOwnBrand` | 440 over 25,105 catalogue rows | 0 |
| `genericSubstitution` (unit diff, pitch text, label) | 401 | 0 |
| `therapyBreakdown` incl. `.05`/`.15` rounding boundary | 104 | 0 |

Two bugs this caught or that review found alongside it:

1. `unitDifferenceLabel` was built as `"… BDT " + if (x) "higher" else "lower" + " per unit"`.
   Kotlin binds the else branch greedily, so the positive case rendered without its
   " per unit" suffix. Parenthesised.
2. `smartPitchNote` was handed the trimmed generic; `main.py` passes the original
   detected dict. Now untrimmed.

Still unported, and correctly still blank: nothing in Step 6. `database.py` (2,528 lines,
SQLite aggregates) is Steps 5/8 and `pharma_hub.py` (589 lines, RSS/HTTP news, jobs,
health days) is Step 7.

## Step 7 — Hub (part 1 of 3): Health Days + Jobs

Ported the data-driven half of `pharma_hub.py`: `get_pharma_jobs` and
`get_health_days`, plus the `_COMPANY_CAREER_URL` / `_CATEGORY_DEPT` /
`_CATEGORY_TAGS` tables and `_job_apply_url`. Verified by execution against the
real module — 360 job-filter combinations and the full health-day calendar
(today/upcoming/past across several years, `upcoming_only` both ways):
**0 mismatches**.

New files: `data/model/HubData.kt`, `data/repo/PharmaHub.kt`,
`ui/screens/hub/HubViewModel.kt`, `ui/screens/hub/HubScreen.kt`. `HubViewModel`
is hoisted into `MedLenXShell` alongside `ScanViewModel` so filters and the
selected month survive navigation. Apply links open in the browser via
`ACTION_VIEW`, with a toast when no browser is present.

**The Figma health-day calendar is fabricated and has been replaced.** Its mock
lists six November days; `data/health_days.json` for 2026 holds four, on
different dates:

| | Figma mock | `health_days.json` (2026) |
|---|---|---|
| World Pneumonia Day | 1 Nov | **12 Nov** |
| World Cancer Day | 5 Nov | not present |
| World COPD Day | 10 Nov | **18 Nov** (org: GOLD) |
| World Diabetes Day | 14 Nov | 14 Nov |
| World Prematurity Day | 17 Nov | not present |
| World Children's Day | 20 Nov | not present |
| AMR Awareness Week | — | **18 Nov** |

The dataset has 33 days for the year and the Figma mock invented the rest, so
the grid renders from `byMonth` and shows an empty state for months with no
entries rather than padding the calendar.

**Not built yet.** The Drug Index, TRIPS and News tabs render an explicit
placeholder explaining why:

- *Drug Index* needs `medex_browse` / `unique_companies_from` ported against the
  Room catalogue, plus the "Currently Popular Medicines" hero, which the web app
  fills by live-scraping medex.com.bd.
- *TRIPS* needs per-molecule field volume aggregated from prescription scans;
  the Figma numbers (47 items, +38%) are invented and there is no on-device
  aggregate to replace them with yet.
- *News* is RSS fetched server-side. The standalone build has no proxy, so this
  needs a decision: fetch on-device over HTTPS, or ship the bundled
  `pharma_news.json` seed as static content and say so.

## Tooling note

The unused-import audit used for Steps 1-6 was broken: it searched the import
lines themselves, so it could never report anything. Fixed. It now finds 37,
almost all of which are `getValue`/`setValue` operator imports required by `by`
delegation. Unused imports are warnings, not errors, so none of them blocked a
build — but the check was reporting false negatives for six steps.

## Step 7 part 2: catalogue import fix + Drug Index tab

**`ensureImported()` was never called.** `AssetCatalogue` has imported the bundled
`medex_full.json` into Room since Step 2, but nothing invoked it, so
`medex_products` was empty for the life of the process. Consequences, all silent:

- `MedexDao.all()` returned nothing, so `MedexIndex` had no brands and
  `MedicineEnricher` matched no medicine. Company, match type, pack image and
  alternatives stayed null, and `genericSubstitution` could never find an own
  brand — the Step 6 enrichment was wired correctly and still could not run.
- The Hub's drug index would have rendered zero rows.

NEML / DGDA / TRIPS were unaffected because `RegulatoryRepository` reads the
assets directly instead of through Room, which is why nothing failed loudly.

`AppGraph` now owns an application-lifetime `CoroutineScope` and exposes
`importCatalogue()`; `MedLenXApp.onCreate` calls it. `ensureImported` is
idempotent so warm starts no-op.

**Drug Index tab** built on `medex_browse` + `unique_companies_from`, verified by
execution against the real module over all 25,105 rows — every category at four
limits plus 35 query/limit combinations for the company list, comparing rank
order, totals and de-duplication: **0 mismatches**.

The "Currently Popular Medicines" hero is *not* a live scrape. The web app
fetches it from medex.com.bd; the standalone build ranks the bundled catalogue
with `unique_companies_from` and the caption says so, rather than claiming a
live fetch it is not making.

`_CATEGORY_TERMS["otc"]` contains "antacid" twice in the Python source. Kept
verbatim: the duplicate cannot affect an `any` test, and silently de-duplicating
a ported table makes the diff harder to audit.

## Tooling note 2

Two audits I had been relying on were broken and reported clean results while
checking nothing: the unused-import check (searched the import lines themselves)
and the named-argument check (its regex stopped at the first `)` inside
`content: @Composable X.() -> Unit`, so it "found" no signature and silently
skipped nine composables). Both are fixed. A checker that cannot fail is worse
than no checker, because it converts an unknown into a false pass.

## Step 7 part 3: TRIPS + News, all five Hub tabs live

**TRIPS** (`database.get_trips_portfolio` → `TripsPortfolio.kt` +
`PrescriptionDao.scannedSince`). The join moves to SQL and the aggregation is
ported. Figma's numbers (47 items, +38%, "Dhaka South (9)") are invented and are
not reproduced; a fresh install shows every molecule at zero volume with the
full 26-molecule watch list still listed, because the Python appends zero-volume
molecules in dataset order so nothing is hidden.

One asymmetry preserved: index keys are built with `Compliance.norm` but the
lookup key is only lowercased and collapsed. Normalising both sides would change
which generics match.

Verified by execution — the Python aggregation lifted verbatim and diffed against
the Kotlin transliteration over 4,000 synthetic rows at five window boundaries,
plus empty / blank-generic / no-match edges: **0 mismatches** in molecule list,
territory ranking, delta percentages and totals.

**News** (`_parse_rss_items` + `get_pharma_news` → `NewsRepository.kt`). On-device
fetch of the WHO RSS feed with the bundled seed as fallback, per the decision to
prefer live news over a static snapshot. `_fetch_medex_news` and
`_fetch_dgda_news` are **deliberately not ported**: they regex-match HTML out of
medex.com.bd and the DGDA site rather than reading RSS, and an on-device HTML
scraper breaks silently whenever either site changes markup, with no server to
patch it. The curated seed covers regulatory and market headlines; the caption
says which items are live.

The port caught a real ordering bug: Python tries `%Z` before `%z`, so a
`GMT` stamp produces a naive ISO string with no offset while `+0000` produces
one ending `+00:00` — and `get_pharma_news` sorts the merged feed by that
string. Emitting a single format would reorder live items against seed items
stamped in the same second. Both formats are kept.

Known immaterial divergence: an unparseable pubDate falls back to "now" with
millisecond precision, where Python emits microseconds (`SimpleDateFormat` caps
at `SSS`). Only affects sub-second tie-breaks.

`XmlPullParser` itself could not be executed here — no JVM — so the parser is
verified by construction against Python's `findall(".//item")` semantics
(skip items with no title, 320-char summary, HTML stripped) rather than by run.

## Tooling note 3

The named-argument checker reports false positives on nested calls: it attributed
`MlxButton`'s `text` / `onClick` / `enabled` / `textStyle` to the enclosing
`SectionHeader`. Verified separately by stripping nested groups — all three
`SectionHeader` call sites pass only `title` / `icon` / `modifier` / `trailing`,
all of which exist. The checker needs a nesting-aware pass before its output can
be trusted unattended.

---

## Step 8 part 1 — Team/RSM: doctor tiering + antibiotic stewardship (this commit)

Ported two of the six RSM aggregates and built the Team screen shell.

**`data/repo/TeamMetrics.kt`** (new) — `doctorTiers` (`get_doctor_tiers`, database.py:2331)
and `stewardshipSummary` (`get_stewardship_summary`, database.py:1959). Post-query
arithmetic only; the SQL lives in `PrescriptionDao.doctorTierRows` /
`stewardshipRows`.

**Re-implementation, not transliteration.** The backend joins a `doctors` table for
specialty/district/territory; Android stores all three on the prescription row, and
has no doctor primary key, so tiering groups by `doctor_name`. Consequently the
Kotlin model drops the one field the backend returns that has no Android equivalent:
`doctor_id`.

**Two backend bugs found; one deliberately not reproduced.**

1. `get_stewardship_summary` builds its per-doctor set with `d["rx_ids"].add(True)` —
   the literal `True`, not the prescription id — so `len(rx_ids)` is always 1 and the
   "Rx audited" column reads 1 for every doctor regardless of volume. Reproducing that
   would ship a metric that is always wrong, so the Kotlin counts distinct
   prescription ids. `parity_team.py` asserts both halves: the Kotlin counter returns 9
   for a 9-prescription doctor, and the Python still returns 1.
2. Its grouping key `doctor_id or doctor_name` falls through to the name whenever
   `doctor_id` is falsy. SQLite AUTOINCREMENT starts at 1, so this cannot fire in
   production, but it is latent. Left alone; noted here.

**Verification.** `parity_team.py` monkeypatches `get_db` so the genuine Python
functions run over synthetic rows: 240 tier comparisons across 4 filter states and 40
stewardship comparisons, **0 failures**. The script also self-checks — reverting the
fixed counter to `add(True)` trips an `AssertionError`, so the checker cannot pass
silently. A first run showed "fixed rx = 1" which proved the *transcription* was adding
`doctor_id`; that was the test's bug, not the port's.

**Race fixed before it shipped.** `TeamViewModel.load()` originally read the observed
`officerProfile`, which has not necessarily emitted when `init` kicks off the first
load — every brand would have been tiered against `DEFAULT_OWN_COMPANY`. It now reads
`profileDao.current()`, captures the value into `ownCompanyUsed` so the header cannot
disagree with the rows, and the profile collector re-runs the aggregates only when the
company actually changes (`haveLoadedOnce` guards the null-on-fresh-install case,
because `null != null` is false).

**Figma copy not carried over.** The at-risk badge in `App.tsx` reads
"Duplicate Rx Detected", which describes a different failure; the flag actually means
*high volume, own-brand share below 30%*. Rendered as "At risk". Hero KPIs keep all four
slots but the roster is one officer on a standalone build, so a footnote states the
figures cover this device's 30-day audits rather than a synced 50+ MPO team.

**Still outstanding for Step 8:** `TeamMap`, `TeamLeaderboard`, `TeamTargets`,
`TeamOffTerritory` — needs `get_rsm_dashboard`, `get_rsm_trends`, `get_scan_points`,
`find_off_territory_audits`, `get_target_progress` ported first.

**Tooling note 4.** The unused-import audit now excludes `getValue`/`setValue`, which
`by mutableStateOf` consumes as operators and which the previous run reported 25 times
as false positives. Cleaned 12 genuinely unused imports (mostly stale `GeoStrip.kt`
leftovers from an earlier refactor); `GeoStrip` itself is live at `ScanScreen.kt:201`.

---

## Step 8 part 2 — Team/RSM: map, leaderboard, targets, off-territory (this commit)

All six `TeamScreen` sections now render real aggregates.

**Ported.** `get_geo_heatmap` (database.py:2428), `get_scan_points` (1805),
`get_rsm_trends` (2176), `get_target_progress` (1564), `find_off_territory_audits`
(1776) → `TeamMetrics.geoHeatmap` / `.scanPoints` / `.rsmTrends` /
`.targetProgress`, plus five new `PrescriptionDao` queries and
`ProfileDao.doctorTargetRows` / `.recentVisits` / `.deleteDoctorTarget`.

**TeamMap was a placeholder in Figma** — three bubbles at hardcoded percentages,
no data behind them. It now draws real regions from `get_geo_heatmap` (SoV mode)
and `get_scan_points` clustered by district (density mode), projected
equirectangularly over Bangladesh's bounds via `BoxWithConstraints`. No tile
provider exists offline, so the backdrop is a labelled field, not a rendered map.

**Third backend doc/code mismatch found.** `get_rsm_trends` documents `own_growth`
as "last full vs previous", but the loop reassigns `growth` on every bucket, so it
actually returns the final bucket's change over the one before. Ported as written
and annotated.

**`get_rsm_dashboard` is not portable.** It reads a `team_members` table with no
Android equivalent, so the leaderboard's roster is one officer. The row is still
the real aggregate — prescriptions, items, own/competitor split, SoV and the
week-over-week sparkline — with a footnote saying why there is only one row.

**Verification.** `parity_team2.py` (scan points 50, trends 40, targets 60) and
`parity_geo.py` (60 trials × 14 regions) run the genuine Python over synthetic
rows: **0 failures**. Six mutations were planted to prove the checkers bite —
jitter modulus ×2, bucket count, the 999% cap, the remaining-clamp and the
centroid fallback were all caught. One mutant (floor vs trunc division) survived;
it is genuinely equivalent because both sides discard negative diffs, which was
then confirmed directly.

**Caught before shipping:** `first(flow) { true }` — Kotlin extension functions
cannot be called with the receiver as a positional argument; now `.first()`. A
dead `Box` with an `offset(x = ...dp.times(0) + Modifier.let { 0.dp })` expression,
and an invented `remember_clusters()` call, both removed. The doctor-target
delete button shipped with `onClick = { }` and is now wired to
`removeDoctorTarget`.

**Pre-existing defect found, not fixed here.** `AnalyticsScreen.kt` has seven
inert controls from Step 5: Prev/Next pagination at lines 144/145 and 441/442, a
CSV export button at 367, and two filter chips at 400/401 — all
`onClick = { }`. The pagination header also shows a hardcoded "1–3 of 42".
Left alone to keep this commit scoped to Team; needs a decision.

---

## Analytics rewrite — the whole screen was on Figma mock data (this commit)

**Correction to earlier reporting.** Steps 6-8 log entries documented mock data in
the Hub, Rx Audit and Team screens but never flagged that `AnalyticsData.kt` held
**nine hardcoded collections driving the entire Analytics screen** — `SummaryKpis`,
`BarData`, `DonutData`, `DoctorLeaders`, `LiveScans`, `RecentRx`, `StackedData`,
`StackedSeries`, `LiveScanSortColumns`. Step 5 built the layout on the export's
sample numbers and never wired it. The seven inert `onClick = { }` controls were a
symptom, not the problem: the pagination read "1–3 of 42" and the feed read
"Showing 1–25 of 1,284 medicines" because there was no real count to show.

**Ported** `get_dashboard_kpis` (database.py:900), `get_most_prescribed_medicines`
(1014), `get_company_share` (1051), `get_top_doctor_prescribers` (1093) →
`data/repo/AnalyticsMetrics.kt`, plus 14 new `PrescriptionDao` queries.
`AnalyticsViewModel` now feeds the KPI strip, widget A, widget B and widget C;
leaderboard and live-feed pagination are real (`leaderTotal` / `liveTotal` drive
the captions and enable/disable Prev and Next). The chamber chips now filter the
feed against `prescriptions.prescription_source` via `ChamberFilter.matches`,
which required carrying that column through `LiveScanFeedRow`.

**A third own-company fallback exists.** `get_dashboard_kpis` and
`get_top_doctor_prescribers` default to `"Square Pharmaceuticals Ltd."`, while
`get_doctor_tiers` / `get_stewardship_summary` default to
`"Healthcare Pharmaceuticals Ltd."`. Both are reproduced as written.

**Verification.** `parity_analytics.py` drives the genuine Python through an
ordered fake cursor that **asserts a SQL marker at each call index**, so a
reordering fails loudly instead of silently comparing the wrong rows — this
immediately caught that `get_top_doctor_prescribers` issues its `is_own_company`
lookup before the count query. 190 comparisons, **0 failures**; three planted
defects (share denominator, Others threshold, zero-baseline delta) all caught.

**One deliberate normalisation:** the Python passes `company_name` through
nullable, the Kotlin model is non-null and coerces to `""`. Normalised in the
comparison; it is a JSON representation difference, not a logic difference.

**Still mock: widget D.** `get_generic_brand_matrix` is not ported, so
`StackedData` / `StackedSeries` remain the export's sample matrix — now with an
amber footnote on the card saying so. **The global filter dimensions
(district / territory / specialty / MR) are not applied to any widget yet**,
because the FilterSheet that would set them is unbuilt; every aggregate runs
unfiltered over 30 days. `onOpenFilters` toasts rather than accepting the tap
silently.

**Unverified as always:** no compile has run. 14 new `@Query` methods, including
`GROUP BY ... COLLATE NOCASE` and a nested `SELECT COUNT(*) FROM (...)`
subquery, have never been validated by Room.

---

## Step 9 — Settings, Help, global search, and a dispatch bug (this commit)

**Built.** `SettingsScreen` + `SettingsViewModel` (company autocomplete over the
distinct companies in the bundled catalogue, the five profile fields plus role,
editable monthly brand targets with real captured-count progress from
`brandCapturedRows`, save, the offline-first note and the Help entry);
`HelpScreen` + `HelpViewModel` (five scan steps, the error escalation form writing
to `error_reports` via `RsmDao.reportError`, and the BMDC & DGDA reference);
`SearchOverlay` + `SearchViewModel` (debounced search over the 25,105-row
catalogue via `MedexDao.search`, replacing the export's six hardcoded brands).
`PendingScreen` is now unreachable from the bottom bar.

### The Analytics tab never rendered

`MedLenXShell` dispatched with

```kotlin
when {
    scrollsUnderBar -> ScanScreen(...)          // scrollsUnderBar == Scan || Analytics
    dest == Destination.Analytics -> AnalyticsScreen(...)
```

`scrollsUnderBar` is true for `Destination.Analytics`, and it was the first branch,
so **the Analytics tab rendered the Scan screen and every `AnalyticsScreen` branch
was unreachable**. Step 5's UI, and the whole real-data rewrite in `7f44819`, had
never once been on screen. Fixed by dispatching on `when (dest)` and keeping
`scrollsUnderBar` as the padding concern it always was. This was not caught by any
audit because brace balance, theme refs, named args and parity all pass on code
that is simply never called — a reachability check is the missing audit.

### Other corrections in this commit

- `HelpViewModel` initially called `queueDao().reportError(...)`. `reportError` is
  on **`RsmDao`**, not `QueueDao`. I also briefly reported a suspected overload
  clash between `ProfileDao.recentVisits` and `RsmDao.recentVisits`; that was
  **wrong** — they are on different DAOs, so there is no conflict.
- The search panel used `.clickable(enabled = false, onClick = {})` to stop taps
  reaching the scrim. That does not consume the tap, so the results list would
  have dismissed itself on touch. Replaced with a `MutableInteractionSource` +
  `indication = null` clickable, which does consume it.
- `SettingsViewModel` had no setters; `vm.update { field = it }` did not exist.

**Not built: the FilterSheet.** `onOpenFilters` still toasts. The Analytics filter
dimensions (district / territory / specialty / MR) remain unapplied to every
widget, and widget D (`get_generic_brand_matrix`) is still the export's sample
matrix with its amber footnote. Those are the two known remaining gaps.

**Unverified as always:** no compile has run here.

---

## Widget D — generic vs brand matrix (this commit)

`get_generic_brand_matrix` (database.py:1227) ported to
`AnalyticsMetrics.genericBrandMatrix`, with `PrescriptionDao.genericMatrixRows`.
`StackedDatum` changed from five fixed company columns to a dynamic
`(specialty, values)` pair, and the mock `StackedData` / `StackedSeries` are gone —
the amber "sample data" footnote is no longer needed because the numbers are real.

**Two backend bugs found.** (1) The comments say "top 8 specialties" / "top 6
generics", but the code is `list(specialties)[:8]` and `list(generics_set)[:6]` —
`list()` over a `set`, whose order is hash-arbitrary and varies between processes.
They are not ranked at all. The Kotlin keeps first-seen order instead, which is
stable across renders; slice sizes are unchanged. (2) The widget is described as
generic vs *brand*, but the aggregate counts generics per specialty — there is no
brand dimension, and the Figma chart's company series do not exist in the data.

**Verification.** `parity_matrix.py`, 60 trials. Because the Python's slice is
arbitrary, the two sides legitimately choose different subsets once more than 8
specialties or 6 generics are present: 17 trials are compared cell-by-cell (no
slice bites), the rest assert slice sizes and series order. **0 failures.**

**The parity script itself needed two corrections.** It first passed a list of
routes to a fake connection that calls `.items()`, so every `mx_py` call raised and
nothing was verified — while the defect harness still printed "CAUGHT", because a
crash is a non-zero exit. It then used `<=6` for the generic cap, which passes when
loosened, and `sorted(...)` normalisation that made series order invisible. Both
strengthened; the cap and order mutations are now caught, as is the sum bug.
Planting a defect in the *assertion* rather than the code is not a valid check.

**Remaining gap: the FilterSheet.** `onOpenFilters` still toasts, and the district /
territory / specialty / MR dimensions are still unapplied to every Analytics and
Team aggregate. Threading them means adding the filter columns to roughly twenty
queries, which is a change big enough to want its own pass rather than a tail-end
edit.

---

## FilterSheet — the last gap (this commit)

Threads the global filter bar through every Analytics aggregate.

**`data/local/Filters.kt`** (new) holds `FilterState` (district, territory,
specialty, mrId, days), `FilterOptions` and `RX_FILTER_SQL`. Fifteen
`PrescriptionDao` queries gained the four dimensions plus `filterDistricts` /
`filterTerritories` / `filterSpecialties` / `filterMrIds` for the sheet's
dropdowns. `FilterSheet.kt` renders the export's four dropdowns, the five
date-range chips, the live "N filters active" count and Apply/Cancel/Reset. The
Analytics `FilterBar` badge now reports the real active count instead of the
hardcoded `2`, and `onOpenFilters` opens the sheet instead of toasting.

### A semantic bug found by executing the SQL

`parity_filters.py` runs **both** filter forms against a real in-memory SQLite
database — the shipped `(:x IS NULL OR col = :x)` idiom and the Python's
`_filter_sql` fragment — over 291 filter combinations. The first run failed 130 of
them, all involving the empty string:

```
(None, None, None, '', 1, kotlin=119, python=400)
```

`_filter_sql` guards each clause with a plain `if district:`, and in Python `''` is
falsy, so a blank contributes **no clause**. `:district IS NULL` does not catch `''`,
so a blank became a real `p.district = ''` predicate and silently matched only rows
whose column happens to be empty. Fixed to
`(:district IS NULL OR :district = '' OR p.district = :district)` in both the shared
constant and its four inline copies — 16 clauses total.

`days = null` is the Python's "all time"; the VM resolves the window from
`filters.days` and widens the previous-period comparison to match.

### The harness nearly lied twice

1. `_filter_sql` targets `d.specialty` on the backend's `doctors` table, which
   Android does not have. The fixture needed a real `doctors` table with
   `doctor_specialty` mirrored onto the prescription, so the Python runs verbatim
   and the Android denormalisation is what is actually being compared.
2. A patch run reported `AssertionError` for the Kotlin files but the harness had
   already been rewritten to the new form, so it printed **`failures: 0` against
   SQL that was never written to disk**. Neither Kotlin file had changed. Caught by
   grepping the files, then verified properly: the harness's clause string is now
   extracted-and-compared against `RX_FILTER_SQL` parsed out of the Kotlin source
   (`MATCH: True`), and the four inline copies are asserted identical to the
   constant. A parity harness must be checked against the shipped code, not assumed
   to match it.

**Scope note.** The filter bar is wired to Analytics, which is where the export puts
it. The Team/RSM aggregates keep their own fixed 30-day window — the web's RSM
endpoints take their own territory/district/specialty parameters rather than the
global bar's, so applying the global filter there would diverge from the backend.

**Unverified as always:** no compile has run here. The 15 rewritten `@Query`
methods, the new `RX_FILTER_SQL` concatenation inside annotations, and Room's
handling of nullable `String?` bind parameters are all unproven until a real build.

---

## First real compile — what the build actually found

The "no compile has run here" caveat that closed every previous section is no
longer hypothetical. The branch was downloaded and built in Android Studio
(`:app:compileDebugKotlin`), and it failed with **~60 errors**. Static auditing
across nine steps had not predicted a single one of them. Every finding below
came from the compiler, not from a re-read of the source.

### Redeclarations (5) — the class of error no audit was looking for

Kotlin forbids two top-level declarations with the same name in one package, and
`data/model/Catalogue.kt` had grown stale duplicates of models that later got
their own files:

| Name | Stale copy | Live copy |
|---|---|---|
| `TripsMolecule` | `Catalogue.kt` | `RegulatoryData.kt` |
| `HealthDay` | `Catalogue.kt` | `HubData.kt` |
| `PharmaJob` | `Catalogue.kt` | `HubData.kt` |
| `NewsItem` | `Catalogue.kt` | `NewsData.kt` |
| `MarketShare` | `RxAudit.kt` | `AnalyticsMetrics.kt` |

The four `Catalogue.kt` copies were deleted (`NemlEntry`, `DgdaEntry` and
`HealthDayFile` went with them — all three were already dead). `RxAudit.kt`'s
`MarketShare` was renamed to **`RxMarketShare`**: it is a different shape from the
Analytics one (per-prescription own/competitor split vs. a dashboard percentage),
and its only caller uses field access, so the rename needed no call-site changes.

These duplicates also produced *misleading* errors elsewhere — `PharmaJob.tags`,
`.education`, `.type`, `.division`, `.postedDaysAgo` and `NewsItem.tags` all
reported "unresolved reference" purely because the stale class was winning
resolution. Fixing the redeclaration cleared eleven errors at once.

### JVM signature clashes (16)

Every ViewModel exposed a `var x by mutableStateOf(...) private set` **and** a
`fun setX(...)`. Both compile to `setX(...)V`, so each pair was a platform
declaration clash. All 16 were renamed `set*` → `update*` with call sites updated
in `AnalyticsScreen`, `HubScreen`, `SettingsScreen`, `TeamScreen` and
`MedLenXShell`. `SettingsViewModel.setCompany` was dead (`selectCompany` is the
live path) and was deleted instead of renamed.

### Everything else the build caught

- `maxOf(3, qLen * 0.5)` — `Int` vs `Double`; now `maxOf(3.0, ...)`.
- `Icons.Filled.Description` used without its import.
- `PendingScreen`'s `when (destination)` was not exhaustive once `RxAudit` was added.
- `Modifier.weight` used in `Spacer1Cell()` outside a `RowScope`; now an extension.
- `return@runCatching` inside a `.onFailure { }` lambda — not a label, *and* wrong
  in intent: returning from the `onFailure` lambda would still have fallen through
  to `queued = true` / `saved = true`. Both became `return@launch`.
- `SectionHeader(trailing = MlxButton(...))` — `trailing` is
  `@Composable (() -> Unit)?`, not a call result.
- `ScanProgress.NeedsKey` — the member is `NeedsApiKey`.
- `MlxCard(padding = PaddingValues(12.dp))` — `padding` is a `Dp`. Three sites.
- `(d.sov / 100f).coerceIn(0f, 1f)` — `Double / Float` stays `Double`. Five sites.
- `HazeStyle(backgroundColor =, blurRadius =)` — ambiguous between the
  `tints: List<HazeTint>` and `tint: HazeTint?` overloads; `tint = null` picks one.
- `geoRegionRows(ownLike = ownLike)` referenced a variable that was never declared.
- `RecentPrescriptions` called without its required `rows`.
- Missing `remember` / `height` imports in `SearchOverlay.kt`.

### Two runtime bugs the build surfaced indirectly

1. **`AnalyticsViewModel` had no `init` block.** `load()` was only called from
   `updateFilters`, so the Analytics tab rendered empty until the user opened the
   FilterSheet and applied a filter. Added `init { load() }`.
2. **The Jobs keyword search was missing.** `jobQuery` fed
   `PharmaHub.pharmaJobs(q = ...)` but nothing could set it. `App.tsx:1465` does
   have the box (`"Company, city, keyword..."`), so the field was added to the
   Jobs tab — a truncation closed, not just a dead-code warning silenced.

### Verification method used here (and its limits)

Five checkers were written and **each was mutation-tested** — a defect was planted
in the source and the checker had to catch it, then the source was restored:

- duplicate top-level declarations → **0 real** (3 extension-receiver false positives)
- `var`/`setX` JVM signature clashes → **0**
- DAO calls that do not exist on the interface → **0 missing of 52 distinct calls**
- unused imports / invalid labelled returns / brace-depth drift vs HEAD → **0**
- named-argument mismatches and missing required args → **0**

Two checker bugs were found *by the mutation tests*, not by reading: the brace
counter mis-parsed a nested string template (`"${field.replace("\"", ...)}"`), and
the argument checker treated `->` as a generic `>` closing bracket, plus it
skipped fully-qualified call sites entirely. Without planting defects, all three
would have reported a clean tree while checking nothing.

**Still unverified.** No compile has run in this sandbox (no JVM is obtainable
here). These five checkers cover redeclarations, signature clashes, DAO methods,
imports and named arguments — they are not a type checker, and cannot see
Composable-scope errors, Room/KSP codegen, or generic inference.

### Known functional gaps (not compile errors, deliberately not silently fixed)

- **`ScanRepository.saveVerified()` is never called.** It is fully implemented and
  writes both `prescriptions` and `scanned_medicines`, but `ScanViewModel.save()`
  only builds a local `SavedReceipt` with a generated Rx number. Nothing in the
  app persists a prescription, so Analytics, Team/RSM, Hub TRIPS and every recent
  list read empty tables. This is the largest gap in the build and needs a
  decision before it is wired.
- `JobBoard.departments` is computed by `PharmaHub` but never rendered. The Figma
  export's "All departments" select is decorative (one option), so this is a
  facet with no UI rather than a lost filter.
- CSV/PDF export still toasts that it is unavailable offline.

---

## Persistence wired: `saveVerified()` is finally called

The largest gap from the previous section is closed. Before this, nothing in the
app wrote a prescription: `ScanRepository.saveVerified()` was fully implemented
but unreachable, so every Room-backed screen read empty tables forever.

### `ScanViewModel.save()` — port of `save_prescription`

1. **Perceptual-hash duplicate guard.** The captured image is hashed with
   `PHash.compute` and compared against every stored hash, keeping the *closest*
   match within `RxAudit.DUPLICATE_THRESHOLD` (8 bits), earliest row winning a tie.
   This is `find_duplicate_prescription` verbatim, including its behaviour on a
   missing hash: skip the guard rather than guess. Needs a new
   `PrescriptionDao.hashRows()` + `PrescriptionHashRow` projection mirroring the
   backend's `WHERE image_phash IS NOT NULL AND image_phash != '' ORDER BY id ASC`.
2. **Insert.** `saveVerified()` writes the header row and its itemised medicines.
   `duplicate_of` carries the matched row's id, so the audit drawer's fraud note
   (`duplicateOfRxIds`) fires only on a genuine re-scan instead of the hardcoded
   `emptyList()` it had before.
3. **Identity.** `mrId`/`repCode` come from `officerProfile.employeeId`, falling
   back to `DEFAULT_MR_ID = "MR001"` — the web's own default. Previously the
   receipt was hardcoded `"MR001"` and `"A-${100 + size}"`.

**Rx numbering.** The web app has no `rx_no` column at all; the Figma export
hardcodes "A-128". The number is now `RX-<count+1>`, computed before the insert.
Prescriptions are never deleted in this app (the only `@Delete` in the DAOs is for
`QueuedScanEntity`), so the row count is a safe monotonic sequence and there is no
read-modify-write round trip to backfill it.

### Failure handling — a trap this change would otherwise have created

A failed insert used to set `phase = ScanPhase.Failed`. That screen has no way
back, and the officer's entire verified prescription would have been stranded
there. A save failure now keeps the phase on `VerifyGps` and renders an
`MlxErrorLine` above the panel, so it is retryable. A `saving` flag guards against
double-inserts and drives the button's disabled/"Saving…" state.

### Stale aggregates after a scan

The ViewModels are hoisted and live across tabs, so a prescription saved on Scan
never reached Analytics/Team/Hub until an app restart. The shell now re-pulls on
tab entry via `LaunchedEffect(dest)`: `analyticsVm.load()`, `teamVm.load()`, and a
new `HubViewModel.reload()` (which only re-reads the scan-derived TRIPS volumes —
the bundled datasets cannot change at runtime).

### Checker gap found and closed while verifying this

The named-argument checker only collected `fun` declarations, so **data-class
constructor calls were unchecked** — including the new 19-field
`PrescriptionEntity(...)`. Extended to class constructors; it then reported 6 hits.
All 6 were checker bugs, not code bugs, from two causes:

- `strip()` deleted `/* ... */` comments without preserving newlines, so reported
  line numbers did not match the file.
- `split_top()` counted `<`/`>` as nesting brackets. An earlier fix had only
  neutralised `->`, so a comparison like `all.count { it.abxItems > 0 }` still
  decremented the depth and split arguments at the wrong level.

Both fixed; the checker is now 0 on the tree and catches a deliberately misspelled
argument in the new `PrescriptionEntity` call.

### Still open

- `JobBoard.departments` is computed by `PharmaHub` but has no UI. Adding a filter
  would mean changing ported logic, which needs a parity run against the Python
  first — not done here.
- No compile has run in this sandbox (no JVM). `hashRows()` is a new `@Query`
  returning a new projection, so its KSP-generated implementation is unproven.

## Audit pass - persistence correctness (uncommitted -> this commit)

Three real defects in the save path, found by reading the data flow rather than by a
checker. All three were silent: the app ran, saved a row, and showed a receipt.

1. **The officer's corrections were discarded.** `onBrandChange` / `onDosageChange` write
   to `state.cards`; `save()` passed `state.result`, the untouched VL read, to
   `saveVerified`. Every correction made on the verification panel was thrown away at the
   moment of saving. Fixed by `ScanViewModel.mergeEdits()`, which folds a card edit back
   only when it differs from what `toCardData()` originally displayed - so a catalogue
   default is never written back as if it were a correction. A dosage edit lands on
   `dosageNormalized`, the field `ScannedMedicineEntity.dosage` actually prefers.

2. **The resolved manufacturer was never persisted.** `saveVerified` wrote `med.company` -
   the VL's guess, which the prompt explicitly forbids, so it is blank in almost every
   read. Widget B filters `WHERE IFNULL(sm.company_name,'') != ''`, so every row saved by
   this app would have dropped out of the market-share widget, and the Live Scans feed
   would show no company. Now uses `EnrichedMedicine.company` (catalogue-resolved), which
   also makes `isOwn` correct.

3. **Every audit column was written as a zero.** `companyVerified`, `isAntibiotic`,
   `broadSpectrum`, `therapeuticClass`, `nemlListed`, `dgdaFlagged`, `tripsWatch` and
   `matchType` were hardcoded false/null/"pending" even though `MedicineEnricher` had
   already computed all of them. `saveVerified` now takes the index-aligned `enriched`
   list and persists the real values; `companyVerified` is read by `liveScanRows`, so that
   one was user-visible. `save()` re-enriches the corrected read (non-fatally) so the Rx
   Audit screen and the stored rows describe the same medicines.

### Two new static checks

- `room.py` - parses the 9 `@Entity` tables and their columns out of `Entities.kt`,
  resolves the `RX_FILTER_SQL` concatenation, then validates all **60** `@Query` strings in
  `Daos.kt` for real tables and real columns. Clean. Mutation-tested both ways (a typo'd
  column and a typo'd table are each caught). This is the first check that reaches the KSP
  codegen surface at all.
- `members.py` - resolves every `vm.x` / `vm::x` in a screen against the members actually
  declared on that ViewModel. Clean; mutation-tested.

### Checker bug worth recording

`named.py`'s `root` was **relative** (`app/src/main/java/com/medlenx/lab`), so running it
from anywhere inside the source tree scanned zero files and printed a clean `total: 0`.
Two mutation tests were mis-reported as "the checker missed both" when the checker had
simply been invoked from the wrong directory. All checkers now pin an absolute root. A
green result is only meaningful once the tool is known to have read the files.

### Still open (needs a decision, not a patch)

- **The offline queue is inert end to end.** `queueForLater` and `drainQueue` have zero
  callers, yet `MlxTopBar` renders "N queued" from `queueDao.observeCount()`. The badge can
  never be non-zero. Wiring only the enqueue would make it worse - a queue that grows and
  never clears - so both halves plus an attempt cap need to land together.
- `JobBoard.departments` is computed but not rendered.
- CSV/PDF export toasts that it is unavailable offline.

## Offline queue wired end to end

The queue was inert: `queueForLater` and `drainQueue` had zero callers, and the header's
"N queued" chip could never be non-zero. The web contract (App.tsx:402, :2147) is explicit
- "scans cache on-device when the rural network drops, then sync on reconnect" - so both
halves plus an attempt cap landed together.

- **`MedLenXApp.onCreate` now calls `graph.deviceState.start(graph.scope)`.** This was the
  root cause behind more than the queue: `DeviceStateRepository.start()` had zero callers,
  so the `ConnectivityManager` callback was never registered, `online` never left its
  initial `true`, and `queueDao.observeCount()` was never collected. The Online/Offline
  chip was decorative and nothing could ever notice the network returning.
- **`DeviceStateRepository.setCompany` also had zero callers**, so the header read "Set
  company in Settings" forever. `start()` now observes `profileDao.observe()` and pushes
  the signed-in officer's company.
- **`ScanProgress.QueuedOffline` was declared and never produced or consumed.** `analyze()`
  now yields it when there is no route to the API, and the `when` handles it by parking.
  A mid-request network drop parks too. A file that cannot be read still fails immediately
  - parking it would only burn attempts until the cap dropped it.
- **`parkForReplay` keeps `ScanPhase.Ready`**, not `Failed`. The Failed panel has no back
  button, so parking there would have stranded the officer and discarded a good capture.
- **`drainQueue` now stops after the first success** and drops rows past
  `MAX_QUEUE_ATTEMPTS = 3`. A resumed read still has to pass the verification panels, so
  only one can be handed back at a time; the rest stay parked and keep counting.
- **Auto-sync**: `ScanViewModel.init` watches `snapshotFlow { deviceState.online }` and
  replays on every transition to online. It never runs while a verification is in flight,
  and never yanks the viewer away from the capture the officer is currently looking at.
- **New `OfflineQueueBanner`** on the scan viewer, in the web banner's own colours
  (#FFFBEB / #FDE68A / #B45309 = `Warn50` / `Warn200` / `Warn600`), shown only when
  something is genuinely parked.

## Jobs department filter (truncation fixed)

The web Jobs board (App.tsx:1459-1465) has three filters: roles, **departments**, keyword.
Android had only roles, locations and keyword. `PharmaHub.pharmaJobs(department = ...)` and
`JobBoard.departments` were already fully implemented - nothing drove them. Added
`jobDepartment` state, passed it through `board()`, and rendered the chip row. This closes
the `JobBoard.departments` gap recorded earlier.

## Checker rewritten and hardened

`/tmp` is wiped between turns, so the checkers are gone again. They now live outside the
repo at `checks/audit.py` (four checks: duplicate declarations, JVM signature clashes,
named arguments, undeclared ViewModel members) with an absolute root.

Three real checker bugs were found by mutation testing, not by reading:
1. `strip()` **deleted** string literals, collapsing argument lists - `parseRssItems(xml,
   "WHO", "public-health", limit = 5)` counted 1 positional instead of 3. Literals are now
   replaced with a placeholder. This would also have hidden genuine missing-arg bugs.
2. The clash check required `by mutableStateOf` with no package prefix.
3. Both the clash and member checks scanned from a class's `{` to **end of file**, so every
   earlier class claimed every later class's members - one planted clash reported 7, and
   bodyless declarations (`data class Done(...) : ScanProgress()`) adopted the *next*
   class's body. Bodies are now brace-matched and cut off at the next column-0 declaration.

All four checks were then verified by planting one defect each: 4 planted, 4 caught, each
attributed to the right class. Clean tree reports 0.

## Noted, not changed

- `TeamSections.kt:92-93` uses `r.lat!!` / `r.lng!!`. Safe - guarded by a `.filter` on the
  same expression - but smart-cast does not survive `filter`, so it is fragile rather than
  wrong.
- `MostPrescribed.manufacturer` / `.marketSharePercent` are computed and unread. Not a
  truncation: the web's Chart A (App.tsx:1114-1124) plots only name and value.
- No compile has run here (no JVM obtainable), so all of the above is static checks plus
  reading the data flow.

## Static audit committed, queue guard tightened, polypharmacy badge fixed

### `checks/audit.py` - the project's own check

`5090274`'s message claimed "Checkers moved to checks/audit.py with an absolute root",
but no such file was ever committed and `/tmp` had been wiped, so the project had no
runnable check at all. It exists now:

    python3 android/checks/audit.py     # exits non-zero on any finding

Six checks over 77 Kotlin files: duplicate top-level declarations, JVM signature
clashes, named-argument mismatches, ViewModel member access from the UI, Room `@Query`
table/column validation across all 60 queries (the only check that reaches the KSP
codegen surface), and version-catalogue references in the `*.gradle.kts` scripts. Note what it does *not* do: it does not compile, and it does not
resolve imports, so a missing `import` or a type error still only surfaces in Gradle. Paths resolve from the script's own location, so the working
directory cannot silently turn it into a no-op - which is exactly how a checker here
once reported a clean run while reading zero files.

**Every check was mutation-tested**: a defect was planted and the check confirmed to
report it. Three bugs in the checker itself were found that way - a `rstrip()` that made
a `private`-modifier test unmatchable, `:from` bind parameters being read as the SQL
`FROM` keyword (reporting a table named `and`), and quoted SQL literals such as
`'live'` being treated as column references.

### Queue auto-replay could replace a picked image

`syncQueuedScans()` allowed `ScanPhase.Ready`, which is the state the officer is in
after picking an image and before pressing Analyse. A connectivity blip at that moment
replayed a parked capture over the top, swapping the viewer to a different prescription.
`ScanPhase.Empty` is never assigned except by `clear()`, so the guard now admits only
`Empty`, and `clear()` calls `syncQueuedScans()` - otherwise nothing re-emits on
`snapshotFlow { online }` and the second parked capture would wait for a network change
that may never come.

### Polypharmacy badge never escalated

`ClinicalStrip` rendered `"$total Meds Prescribed"` in slate at every size. The web
bands it - amber from 5 medicines, red from 8, with a warning icon - because it is a
clinical safety signal rather than a count. `Compliance.polypharmacyIndex` was already
ported for exactly this and had no callers; it now drives the tone and icon. Verified
against `app/compliance.py`: identical thresholds (8/5), labels and levels, and the web
passes `len(items)`, which is `medicines.size` here. The emoji in the ported label is
stripped in favour of a Material icon.

### Known-unused, deliberately left

`Compliance.therapyBreakdown`, `tripsExpiry`, `substitutionEvidenceNotes` and
`AnalyticsViewModel.clearFilters` have no callers. The first is superseded by
`ClinicalStrip`'s own `classBreakdown`; `clearFilters` is superseded by the Reset button
in `FilterSheet`. `NewsRepository.defaultClient()` is genuinely dead.

## AGP 9 has Kotlin built in - the `kotlin.android` plugin is now fatal

First line of the first real build of this branch:

    An exception occurred applying plugin request
    [id: 'org.jetbrains.kotlin.android', version: '2.3.0']
    > The 'org.jetbrains.kotlin.android' plugin is no longer required for Kotlin
      support since AGP 9.0.

AGP 9 embeds Kotlin support, and applying the standalone Kotlin Android plugin on top of
it is a hard error rather than a deprecation warning. Removed from all three places it
appeared - the root `plugins` block, `app/build.gradle.kts`, and the `kotlin-android`
entry in `gradle/libs.versions.toml` (deleted so it cannot be re-aliased by accident).

What stays, verified against `android/nowinandroid` on this exact AGP 9.3.2 / Kotlin
2.3.0 / KSP 2.3.4 combination rather than assumed:

| plugin | nowinandroid applies it? | ours |
| --- | --- | --- |
| `com.android.application` | yes (`AndroidApplicationConventionPlugin:32`) | kept |
| `org.jetbrains.kotlin.android` | **no - absent from their `[plugins]` entirely** | **removed** |
| `org.jetbrains.kotlin.plugin.compose` | yes (`AndroidApplicationComposeConventionPlugin:28`) | kept |
| `com.google.devtools.ksp` | yes (`AndroidRoomConventionPlugin:31`) | kept |
| `org.jetbrains.kotlin.plugin.serialization` | yes | kept |

The `kotlin { compilerOptions { jvmTarget = JVM_17 } }` block is untouched: their
`configureKotlinAndroid` configures `KotlinAndroidProjectExtension` with no Kotlin plugin
applied, so AGP registers that extension itself.

**Not verified here:** that `import org.jetbrains.kotlin.gradle.dsl.JvmTarget` resolves on
the buildscript classpath once the Kotlin plugin is gone. It should - AGP 9 brings the
Kotlin Gradle Plugin API with it - but no JVM exists in this sandbox to prove it. If the
build reports `unresolved reference: JvmTarget`, delete that import and the `kotlin { }`
block; AGP aligns the Kotlin JVM target with `compileOptions.targetCompatibility`, which
is already 17.

## Gradle 9.6 removed the `by tasks.registering(...)` delegate

Second build attempt got past plugin application and failed compiling the build script
itself:

    Line 129: val refreshMedLenXAssets by tasks.registering(Copy::class) { ... }
    Line 143: val checkMedLenXAssets by tasks.registering("checkMedLenXAssets") { ... }
              ^ Argument type mismatch: actual type is 'String', but 'KClass<Task>'
                was expected.

Two separate problems on two lines:

- Both used the property-delegate form, deprecated and now rejected - Gradle wants
  `val t = tasks.register<Type>(name) { }`.
- Line 143 was simply wrong regardless of version: `registering` takes a `KClass`, never
  a task name. It could not have compiled on any Gradle.

Both now use `tasks.register`. The returned types are unchanged (`TaskProvider<Copy>` and
`TaskProvider<Task>`), so `tasks.named("preBuild") { dependsOn(checkMedLenXAssets) }`
still works as written.

### New check: version-catalogue references

Added `check_version_catalogue()` to `checks/audit.py`, because a `libs.` accessor with no
matching alias is also a build-script compile error that stops Gradle before it reads any
source. All 29 library aliases, 4 plugin aliases and 20 versions currently resolve.
Mutation-tested with a deliberately bogus alias.

## compileSdk 36 -> 37, and the deprecations the first real compile reported

`:app:compileDebugKotlin` **succeeded** - 77 files, warnings only, no errors. The build
failed on `:app:checkDebugAarMetadata`: eleven dependencies declare `minCompileSdk 37`.
`dev.chrisbanes.haze:haze-android:1.7.3` is the root of it and pulls Compose 1.12.0 in
with it, which is why the Compose artifacts are listed too.

`compileSdk` is now 37. `targetSdk` stays at 36 on purpose: compileSdk only decides which
APIs are visible at compile time, whereas targetSdk opts into new runtime behaviour this
app has never been exercised against. AGP's own note makes the same distinction.

Deprecations, all fixed using the replacement the compiler named:

- 15 icon references across 6 files -> `Icons.AutoMirrored.Filled.*` (ArrowBack, Article,
  Assignment, HelpOutline, OpenInNew, RotateLeft, RotateRight), imports moved to
  `androidx.compose.material.icons.automirrored.filled`. The warning count in the build
  log and the number of call sites migrated are both 15.
- `Divider` -> `HorizontalDivider` (MedLenXShell, 2 call sites).
- `fallbackToDestructiveMigration()` -> `fallbackToDestructiveMigration(dropAllTables =
  true)`. The no-arg overload dropped every table, so `true` preserves the behaviour
  rather than changing it.

## Screenshots: the scan produced a doctor but no medicine rows

Device screenshots (2026-09-15) show the verification doctor panel populated while the
medicine review panel is blank, and every downstream screen (Analytics, Team, Hub) shows
zeros because nothing was persisted from the scan.

Root cause in the UI layer: `VerifyMedicinesSection` rendered `cards.forEachIndexed {…}`
with no empty state, so a read that returned zero medicines showed a silent blank panel;
and `VerifyDoctorSection` gave no indication that the medicines live on the next step, so
a successful read could look like "nothing was scanned".

Fixes:
- `VerifyDoctorSection` now takes `medicineCount` and renders "N medicines detected - tap
  Next to review them" directly above the Next button, so a read is never ambiguous.
- `VerifyMedicinesSection` now shows `MlxEmptyState` ("No medicines were detected…") with a
  "Back to doctor" action instead of a blank card.

Caveat: the model itself returning an empty `medicines` array for a given photo is outside
the app's control; these changes make that state visible and recoverable instead of silent.
