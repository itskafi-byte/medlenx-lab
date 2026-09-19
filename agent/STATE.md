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
3. **Training queue** — persist the correction image slice; add list + stats.
4. Company drill-down, DGDA monitor — low-severity parity gaps.

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
