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
3. **Parity directive, module 3** — the three-module brief; modules 1 and 2 have
   landed, see below. Module 3 grows `RxBreakdownSheet` into the full Prescription
   Audit Summary. Scope and references are in
   `findings/2026-09-26-parity-directive-audit.md`.
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

*Still absent from the review card:* the web also renders the DGDA flag after the
substitution card (`index.html:1966`). `EnrichedMedicine.dgdaAlert` is computed
during enrichment and, like the two fields above, is dropped by `toCardData()` -
flagged, not folded in, because module 2 was the substitution card.

## Recently fixed (committed, unverified)

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
