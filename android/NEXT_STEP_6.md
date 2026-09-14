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

`gradlew` and `gradle-wrapper.jar` are absent from the repo and cannot be generated in
the sandbox — run `gradle wrapper` locally once, or open the project in Android Studio.
The `copyMedLenXAssets` task pulls the nine JSON files from `../data/` into
`assets/data/` at build time; override with `-Pmedlenx.dataDir=`.

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
