# State — 2026-09-19

Handoff notes. Update this whenever the picture changes.

## What is built

All 9 steps of the agreed plan are implemented:

1 scaffold · 2 data/API · 3 scan · 4 verification · 5 analytics · 6 Rx Audit ·
7 Hub · 8 Team/RSM · 9 Settings/search/toasts/states/help.

81 Kotlin files, 18,772 lines. Every `origin/main` Python module has a Kotlin
counterpart — see `MAP.md`.

## Acceptance items

| # | Item | State |
|---|---|---|
| 1 | Overflow zoom behind doctor name | **Reversed** — zoom is clipped to its frame; the fullscreen viewer is the escape hatch |
| 2 | Per-medicine region box live while editing | Done |
| 3 | Train/learn from corrections | **Deferred, and now confirmed as a parity gap** — see findings |
| 4 | Verify all controls work | Two dead controls **fixed** (`344c27c`); the rest needs a device |
| 5 | Full prescription viewer + per-medicine orange box | Done |
| 6 | Editing shows suggestions + pack image | Fixed, **not device-verified** |

## Open work, in priority order

1. **Camera crash** — one cause fixed (`e161485`): the pending URI sat in plain
   `remember`, which dies with the process the camera launch kills, so the photo
   was silently discarded. **Still needs the logcat** to confirm that was the
   cause rather than something else.
2. **Device-verify rounds 2, 3, 4** — all committed, none confirmed on hardware.
3. **Long-press crop preview** — `MarketShareCard`'s footnote promises "Long-press a
   medicine name to preview the prescription crop", and **no `combinedClickable` or
   `onLongClick` exists anywhere in this project**, so the gesture does nothing on the
   Rx Audit screen, where the caption has always been. The audit drawer suppresses the
   footnote (`showCropHint = false`) rather than repeat the claim. The web's version
   crops the scan at the medicine's line band (`cropMedicineSlice`, :1745) and shows it
   in a popover; implementing it needs the saved prescription's bitmap plus that crop
   arithmetic. Either build it or drop the caption from `RxAuditScreen` too.
4. **Training queue** — persist the correction image slice; add list + stats.
5. Company drill-down, DGDA monitor — low-severity parity gaps.

### Parity directive, module 1 — brand form variations

`MedicineCardData.alternatives` reaches the card and renders under the company
badge as "N other matches for this brand" (`MedicineCard.kt`, `AlternativesPicker`).
The data was never missing: `MedicineEnricher` has always computed every catalogue
variant of the matched brand, and `EnrichedMedicine.toCardData()` dropped the list
at the card boundary - the same trap the comment above `packImage` in that function
describes. `Color.kt`'s indigo tokens were already labelled "alternatives picker".

Picking one is authoritative, as it is on the web. `mergeEdits` now folds strength,
type, company and generic back into the VL read, so the save-time re-enrichment
re-resolves onto the chosen variant instead of the original one. **That fold fixes
two pre-existing bugs beyond this feature:** the name autocomplete and "verify
against MedEx" also set those fields, and both were being discarded on save.

*Not yet built and deliberately deferred to module 2:* the web also renders the
substitution card and the DGDA flag inside the review card. `check_undefined_symbols`
cannot see a dropped field, and no check catches "the mapper forgot a field" -
building one now would report the module 2/3 fields as defects, so it belongs
after they land.

### Parity directive, module 3 — the Prescription Audit Summary drawer

`RxBreakdownSheet` was 159 lines: a doctor's name and one row per medicine. It is now
the drawer the web opens from a Recent Prescriptions row (`index.html:552`, renderer
`:2764`), fed by one assembled payload the way `GET /api/prescriptions/{id}` returns it
(`main.py:928`): header with the duplicate tag, search plus the four filter pills with
counts, the clinical strip (polypharmacy / stewardship / off-territory / therapy bar),
the four-column item table with its regulatory badges and portfolio-match expander, and
the market-share footer with the two exports.

The payload is assembled in `AnalyticsViewModel.loadDrawer`, not in the sheet, so the
pill counts, the clinical strip and the footer share all describe the same list in the
same pass - the web gets that from a single fetch.

**One row shape, two sources.** `RxAuditLine` (`RxAudit.kt`) is what the audit math
reads, and `lineOf` maps both an in-memory `EnrichedMedicine` and a saved
`ScannedMedicineEntity` onto it. Before this, `buildMarketShare`/`itemsToCsv`/
`itemsToClipboard` only accepted `EnrichedMedicine`; the drawer audits saved rows, so a
second implementation was the alternative. The `EnrichedMedicine` overloads survive as
thin adapters to the same core.

**`is_own` is recomputed, never read.** `scanned_medicines` stores an `isOwn` computed
with a strict `equals(ignoreCase)`, while the footer's share uses the loose matcher. The
web recomputes `is_own` from the saved company (`main.py:947`) for exactly this reason:
reading the stored flag would put the strict comparison in the row badge and the loose
one in the footer below it, and they would disagree on "Square Pharmaceuticals" vs
"Square Pharmaceuticals Ltd.".

**The table scrolls sideways.** Four columns do not fit a phone. The web already gives
its table `min-w-[560px]` inside an `overflow-x-auto`, so a horizontally scrolled table
is what it shows on a phone too; reflowing each row into a card would be a different
screen from the one the web and the Figma export describe. Because there is no `<table>`
to size the cells, `TABLE_WIDTH` states the width and the header, every row, the
expander and the empty state all use it - inside a `horizontalScroll` the constraints are
unbounded, so anything relying on `fillMaxWidth` there collapses instead of filling.

**Four shared parts came out of `RxAuditScreen`** (`ui/screens/rx/RxAuditParts.kt`):
`ClassSlice` + `classBreakdown`, `ClinicalStrip`, `PillButton` and `MarketShareCard`.
They were `private`, and the drawer needs all four; writing a second copy is how the
two surfaces drift. `RxAuditScreen`'s rendering is unchanged - the one behavioural knob
added is `ClinicalStrip(hideEmptyAntibiotics)`, defaulted off, which the drawer turns on
because the web hides the stewardship badge at zero in the drawer and shows it on the
audit screen. `RxAuditFilter` was already public in `RxAuditScreen`, so the drawer
imports it instead of declaring a fifth enum with the same four members.

**`PitchTarget` replaced the raw `EnrichedMedicine` in the shell.** The Doctor Pitch
sheet used to read its Rx number and doctor from `scanVm.state`; the drawer opens it for
a saved prescription, where there is no scan in progress, so those fields travel with
the substitution now.

*Not ported, deliberately:* the web's hover preview of the prescription crop (see open
work), and the `title=` tooltips on the NEML / DGDA / TRIPS badges - `dgdaReason` is
carried on the line for it, but Android has no tooltip and the detail needs a long-press
affordance to land somewhere.

### Parity directive, module 2 — generic substitution in the review stream

`MedicineCardData.substitution` reaches the card and renders as the web's
competitor → own-brand card (`MedicineCard.kt`, `SubstitutionCard`) directly under
the alternatives picker, which is the web's order (`index.html:1964-1966`). Both
actions are wired: **Copy pitch** and **Mark as won**. The price badge is the web's
per-unit BDT delta with its `(saving)` / `(premium)` suffix, gated on both gazette
prices being known exactly as the web gates it - not the percentage the brief
described, which appears nowhere in the web's code path.

`Mark as won` writes to `error_reports` through the same path as `reportMisId`,
because the web sends both down one training-queue endpoint and distinguishes them
only by `notes` (`ScanViewModel.CONVERSION_WON_NOTE`). It is **not** a weekly
performance record; the web keeps no such record, and inventing one would put a
number in a report nothing else agrees with.

`copyToClipboard` moved from `MedLenXShell` (private) to `ui/components/Clipboard.kt`
so the review card could copy without a fourth hand-rolled `setPrimaryClip`.
HubScreen still has its own inline copy for the campaign script and was left alone.

**The palette names are offset from Tailwind by one in the blue family.** `Mlx.Brand500`
is `0xFF2563EB`, which is blue-**600**, and `Brand600` is `0xFF1D4ED8`, which is
blue-**700**. Reading a token name as its Tailwind step put this card's Copy-pitch
button and Smart-pitch header one step too dark (`40d985b`). Check the hex before
matching a colour across the two codebases. The rest line up exactly once read that
way: `Brand200` is slate-200, `Ok500` is emerald-600, `Ok600` is emerald-700,
`Blue100`/`Blue200`/`BlueBg` are blue-100/blue-200/blue-50.

*Still absent from the review card:* the web also renders the DGDA flag after the
substitution card (`index.html:1966`). `EnrichedMedicine.dgdaAlert` is computed
during enrichment and, like the two fields above, is dropped by `toCardData()` -
flagged, not folded in, because module 2 was the substitution card.

## Recently fixed (committed, unverified)

- **Bug sweep of the three parity modules** — four defects, one of them user-visible.
  The check that found them is `2f5eaf9`; the fixes are in the commit that added this
  note:
  * **The Doctor Pitch sheet opened underneath the audit drawer.** The drawer is a
    `Dialog`, so it has its own window; the pitch sheet was a plain `Box` in the
    activity's window, so opening it from the drawer composed it *below* the drawer -
    dimmed under the scrim at best, and untouchable, because a dialog window takes the
    touches. Opened from the Rx Audit screen it looked right, which is why the
    audit-screen path never surfaced it. It is a `Dialog` now
    (`usePlatformDefaultWidth = false`), which reproduces the web's own relationship -
    the pitch modal is `z-[10001]` over the drawer's `z-[9999]` and the drawer stays
    open behind it. Back closes the sheet before the drawer.
  * `DoctorPitchCard.kt` had a **stray `@Composable`** between the card's KDoc and
    `data class PitchTarget`, so the annotation bound to the data class and the card's
    KDoc ended up above another KDoc - the orphaned-KDoc defect class this project keeps
    meeting. The data class moved above the card's KDoc and the annotation is gone.
  * `PortfolioRow` appended ` (saving)` / ` (premium)` to a label that already reads
    "0.45 BDT lower per unit", and the web's drawer row prints that label bare - the
    suffix belongs to the verification card's price line (`index.html:1724`). The row
    now prints the payload's label unmodified.
  * The two clipboard call sites disagreed with each other (`"Rx $rxId"` against
    `"Rx #${rxNo}"`) and neither carried the doctor, the item count or the MR that the
    web builds server-side (`main.py:1086`). One `RxAudit.clipboardHeader` builds it
    for both, keeping this app's own `RX-n` numbering.

  Verified clean in the same sweep, by a scan rather than by reading: no unresolved
  `Type.member` or `param.member` anywhere; no `@Composable` parameter defaulted to an
  empty lambda that nothing supplies; **no lambda parameter anywhere in the project
  that its own body never references**; no `private fun` referenced nowhere; every
  `Mlx` / `MlxShape` / `MlxD` / `MlxType` / `PillTone` member the three modules name
  exists; and every icon import is present (proved by injection, not by reading).

- **`e161485`** — the pending camera URI survives process death
  (`rememberSaveable`). Was the closest thing to the camera crash that is
  findable from source; logcat still wanted to confirm it.
- **`344c27c`** — the two dead controls on the medicine card are wired:
  `verifyAgainstMedex` re-runs the catalogue lookup (a retyped brand previously
  kept the manufacturer from the first pass), and `reportMisId` writes an
  `ErrorReportEntity` recording what the model read versus what the field says
  now, then toasts.

- **`dea98f9`** — uploads are now downscaled (`ImagePrep`, 1600px / JPEG 85,
  EXIF rotation baked in) and `VL_MODEL_FALLBACK` is actually used as a retry.
  Previously a 12MP capture became a 4–11 MB base64 body, and the fallback model
  declared in `build.gradle` was referenced by no code at all.
- **`05de3a7`** — `android/checks/imports.py`, the symbol-level checker.
- **`8d1a375`** — `android/checks/guard.py`, the reset guard.
- **`deadparams.py`** — a control whose callback nothing ever supplies. Three
  separate screens have now shipped a parameter declared, typed, defaulted to `{}`,
  and passed by no one, so the button rendered, took the touch, and did nothing.
  None of the three was a compile error and none was visible to a symbol check:
  the parameter *is* declared, *is* typed and *is* used, so every file reads as
  intentional and only a project-wide pass can see that nothing supplies it. It
  found `PrescriptionImageViewer.overlay`, invoked at the end of the canvas and
  supplied by nobody since the file was written; the slot is now gone rather than
  given a caller.
- **`refcheck.py`** — a **member that does not exist**. `imports.py` resolves symbols
  (does `MlxD` have an import?) and cannot tell whether `MlxD.Space7` is a member of
  `MlxD`; `audit.py` catches an argument a function does not take, but only when it is
  named. Three defects of this shape are already in this project's history:
  `PillTone.VioletSolid` written from memory while building the audit drawer (the enum
  has no such entry), `ItemRow(index = i)` with an argument the function never took, and
  plausible-but-absent tokens like `Mlx.DangerSoftBorder`. It resolves **1931
  `Type.member`** and **541 `param.member`** references across 91 files against 241
  indexed types, and exits 1 if it parses no types at all.
  Getting it to zero took eight passes, and the shape of every false start is the
  lesson: each of the first five reported a *clean tree while parsing nothing* - a
  nested declaration whose body was cut off at the next declaration, a `limit` that
  clamped brace matching, primary-constructor properties never indexed (most of the
  model layer is a data class with no body), extensions resolved against a type set
  that was still empty, companion-object members invisible, and a typed-parameter pass
  whose first parameter never matched because the parameter list was sliced from its
  opening parenthesis. Each one was caught by injecting the defect and reading the exit
  code, never by reading the pattern.

## UI decisions worth remembering

- **Screens must add NO top inset at all.** `Scaffold` measures its `topBar` slot and
  reports the full height (status bar + 63dp bar + 1dp divider) through
  `inner.calculateTopPadding()`, which the content Box in `MedLenXShell` already
  applies. Any further `padding(top = ...)` is a SECOND offset and shows up as a
  gap above the first card. Two successive "fixes" that tuned the value
  (AppBarHeight + Space2, then AppBarHeight) both failed for this reason; the
  correct value is nothing. If a gap ever reappears, look for a second offset, not
  for a wrong number.
- **The Bangladesh heatmap's base layer takes zero data parameters.** 63 district
  centroids are baked into `BaseMapLayer.kt` from `data/bd_geo.json` at authoring
  time, and it is drawn first inside the `Box` (bottom of the Z-order) with
  `DataBubbleLayer` over it. An earlier version fed it from the ViewModel, which
  re-coupled the "base" map to the async metrics load — so an empty or failed load
  produced a blank module. A base layer that depends on data is not a base layer.
  The repo holds centroids, not a polygon outline; a real outline would have to be
  sourced and bundled.
- **Four tabs plus Help and Rx Audit are flush. Scan is not, deliberately.** Scan
  still carries `AppBarHeight + Space2` (72dp) because the brief covered the four
  navigation tabs only, and the user chose to revert the Scan fix rather than let
  an unrequested screen into the change set. It is the same defect — the Scaffold
  already offsets content via `inner.calculateTopPadding()`, so the screen's own
  inset is a second offset — and it is a one-line fix whenever it is wanted.

## Known risks

- **MedEx may hotlink-protect non-browser requests.** If pack photos come back
  blank while text loads, add a browser UA to the Coil `ImageLoader` via
  `coil-network-okhttp` — the class name is still unverified, so do not add it
  blind.
- `fallbackToDestructiveMigration` will wipe local data on any schema change.
- Corrections are recorded in `error_reports` but no image slice is stored, so
  the retraining loop cannot actually be fed from the device.

## Environment

- Branch `arena/01a09bf9-medlenx-lab`; `main` intact at 171 files.
- No JVM in the sandbox and no network route to get one — **do not attempt to
  build**. The user compiles in Quail 4 | 2026.1.4 on JDK 25.
- Images cannot be read in this session. Use `adb shell uiautomator dump`.

- **Lifting composables out of a scope lambda drops the receiver.** `Modifier.align()`
  only exists on `BoxScope`, so a block moved out of a `Box`/`BoxWithConstraints`
  content lambda must be declared `BoxScope.Name(...)` or it will not compile. The
  call site needs no change: `BoxWithConstraintsScope` extends `BoxScope`. Note
  `Box(modifier = Modifier.align(...))` is the Box's own argument list and is NOT in
  scope -- only `Box(...) { content }` is. `check_scope_leak` catches this.
