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

Charts are **recharts 3.10.1** (8 chart elements in the file). The Vico ports must
match recharts' visual output, not an abstract spec.

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
