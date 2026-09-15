# MedLenX Lab — Android

Native Android port of the MedLenX Lab web app (`templates/index.html`), built with
Jetpack Compose + Material 3.

## Status

| Step | Scope | State |
|---|---|---|
| 1 | Gradle project, design tokens, navigation shell | **done** |
| 2 | Data layer — MedLenX VL client, models, Room, bundled catalogue, offline queue | **done** |
| 3 | Scan flow — capture, image viewer, laser scan, GPS strip | **done** |
| 4 | Verification — doctor form, medicine cards, catalogue matching | **done** |
| 5 | Analytics — hero, filters, KPIs, charts, live scans | **done** |
| 6 | Rx Audit Summary, pitch card, exports | pending |
| 7 | Pharma Intelligence Hub — 5 sub-tabs | pending |
| 8 | RSM Command — map, tiers, leaderboard, targets, stewardship | pending |
| 9 | Settings, Help, global search, toasts, empty/error/offline states | pending |

The Scan tab is live end to end: capture → scan → verify doctor → review medicines →
GPS/territory → saved receipt. Analytics, Hub, Team and Settings still show a
placeholder naming the step that fills them, so a partially-built APK is obviously
incomplete rather than silently missing features.

**Read `NEXT_STEP_6.md` before continuing** — it records what is stubbed, what is
deliberately not ported, and the open questions carried out of Step 5.

> The toolchain is pinned to **Gradle 9.7.1 / AGP 9.3.2 / Kotlin 2.3.0**, which runs on
> the JDK 25 that Android Studio Quail 4 (2026.1.4) bundles. See "Building" below.

### Scan flow (Step 3)

- Empty state: dashed hero, 80dp gradient tile, **Choose File** (photo picker) and
  **Open Camera** (`TakePicture` via the declared FileProvider)
- Image viewer: pinch-zoom, drag-pan, rotate ±90°, contrast cycling (1.0 → 1.35 → 1.6
  → reset), fit, and a live zoom readout. Contrast uses a classic RGB-about-128
  `ColorMatrix` so faint cursive separates from the paper without clipping.
- Dual-glow radar scan: 3px laser bar with the cyan gradient and glow, plus the
  full-panel cyan wash, both on the web's 2.2s `alternate` cycle
- Shimmer skeletons with the 4dp cyan left edge, resolving as the read returns
- Bounding-box highlight mapped from the VL line index onto a vertical band
- GPS strip: Upazila / District / Territory with cascade invalidation, plus the pin
  button and the off-territory verdict
- `Geofence` is a direct port of `haversine_km`, `resolve_geo_district` (90 km radius),
  `_territory_base` and `territory_check` from `app/compliance.py`, including the rule
  that it never flags on missing data

### Verification (Step 4)

Four phases after the read completes — `VerifyDoctor`, `VerifyMedicines`, `VerifyGps`,
`Saved` — each ported from the Figma export rather than the web markup.

- **VerifyDoctor** (`VerifyDoctorSection`): 180dp thumbnail with the amber ROI box, six
  viewer controls and the zoom/pan hint pill; header with the pending-verification pill
  and close button; four confidence fields (Doctor Name, BMDC Reg No, Qualifications,
  Hospital/Chamber) that turn amber with a warning glyph below 85%; Specialty; the
  three-column District → Upazila → Territory cascade, where changing district clears
  its dependents; and Prescription Source.
- **VerifyMedicines** (`VerifyMedicinesSection`): the legend line verbatim, then one
  `MedicineCard` per line. Card skin is driven by the confidence band; brand and dosage
  are editable; the catalogue-override note appears only when the catalogue resolved a
  different brand than the scan literally read.
- **VerifyGps** (`VerifyGpsSection`): three 32dp fields, a GPS button, and one status
  line — green with coordinates when the pin resolves in territory, red with the
  geofence reason when it does not. Backed by the same `Geofence` port as Step 3.
- **ScanSaved** (`ScanSavedSection`): success toast, 56dp emerald tick, headline, the
  receipt line and two ghost actions.

`CompanyPill` renders all three states: slate "Company not identified", the
amber→orange gradient for unverified, and `#2563EB` with a 16dp monogram for verified.

## Building — read this if your Android Studio ships JDK 25

Android Studio Quail 4 (2026.1.4) bundles JetBrains Runtime **25.0.3**, and that is what
Gradle runs on by default. The pins in this repo were bumped to a set that supports it.

Verified against Gradle's own compatibility documentation
(`platforms/documentation/docs/src/docs/userguide/releases/compatibility.adoc`, read
from the `gradle/gradle` repo at each tag):

| Gradle | JVM that can *run* Gradle | Quote from the doc |
|---|---|---|
| 8.9 (pinned here) | 17-22 | - |
| 9.0.0 | 17-24 | "JVM 25 and later versions are not yet supported." |
| **9.1.0** | **17-25** | "A JVM version between 17 and 25 is required to execute Gradle." |
| 9.7.1 (latest stable) | 17-26 | - |

So **Gradle 9.1.0 is the minimum that runs on JDK 25.** Gradle 8.9 fails before it ever
reaches the Kotlin compiler.

### Where the pins came from

AGP, Kotlin, KSP, Room, the Compose BOM and every androidx / kotlinx version are copied
from **Google's own `android/nowinandroid` sample** (`gradle/libs.versions.toml`, last
updated 2026-09-02), which builds with exactly this set. Gradle 9.7.1 is both the newest
stable tag on `gradle/gradle` and the version nowinandroid's own wrapper pins.

| Pinned | Was | Now |
|---|---|---|
| Gradle | 8.9 | **9.7.1** |
| AGP | 8.5.2 | **9.3.2** |
| Kotlin | 2.0.20 | **2.3.0** |
| KSP | 2.0.20-1.0.25 | **2.3.4** |
| Room | 2.6.1 | **2.8.3** |
| Compose BOM | 2024.09.02 | **2025.09.01** |
| compileSdk / targetSdk | 34 | **36** |

Code and DSL changes that came with the bump:

- `android.kotlinOptions` **was removed in AGP 9**. The JVM target now lives on the
  top-level `kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }` extension.
- `vectorDrawables { useSupportLibrary = true }` dropped - it is a no-op at `minSdk 26`.
- `signingConfigs.getByName(...)` -> `signingConfigs.named(...).get()`.
- haze moved off its deprecated API: `haze` -> `hazeSource`, `hazeChild` -> `hazeEffect`,
  `remember { HazeState() }` -> `rememberHazeState()`. Pinned at **1.7.3**, the newest
  stable in the 1.x line; haze 2.0 renames the whole API again.
- **Vico removed from the catalog.** It was pinned but never used - the analytics charts
  are drawn with Compose `Canvas`.

### If the build still objects to the toolchain

The one thing that cannot be verified without a network connection to Google Maven is
whether Studio accepts AGP 9.3.2 as-is. If it does not:

- Take the **AGP Upgrade Assistant** prompt on first sync - it picks a mutually
  compatible AGP + Gradle + Kotlin.
- Or point Gradle at an older JDK instead:
  `Settings -> Build, Execution, Deployment -> Build Tools -> Gradle -> Gradle JDK`,
  choose 17 or 21. Everything in `gradle/libs.versions.toml` moves as a set - see the
  comment at the top of that file.

## First build

```bash
cd android
cp local.properties.example local.properties
# set sdk.dir, and OPENROUTER_API_KEY if you want real scans instead of demo mode
./gradlew assembleDebug      # or: open this folder in Android Studio
```

The Gradle wrapper is committed (`gradlew`, `gradlew.bat`,
`gradle/wrapper/gradle-wrapper.jar`), so no separate Gradle install is needed. On
Linux/macOS make it executable first if the zip lost the bit: `chmod +x gradlew`.

### Bundled datasets

The app is standalone, so it ships the same JSON the web backend served. The nine
datasets are **committed** under `app/src/main/assets/data/` — this branch is meant
to be downloaded and built on its own, with nothing outside `android/`.

Two Gradle tasks guard them:

- `checkMedLenXAssets`, wired to `preBuild`, **fails the build** if any dataset is
  missing rather than producing an APK with a silently empty drug index.
- `refreshMedLenXAssets` re-copies them from an external directory, but only when
  you ask: `./gradlew refreshMedLenXAssets -Pmedlenx.dataDir=/abs/path`. It never
  runs on its own, so a build cannot clobber the committed files.

Imported on first launch into Room: `medex_full.json` (25,105 DGDA-registered SKUs,
a top-level JSON array, ~16 MB), `bd_locations.json`, `bd_geo.json`, `neml_list.json`,
`trips_waiver.json`, `dgda_prices.json`, `health_days.json`, `pharma_jobs.json`,
`pharma_news.json`.

## API key handling

`OPENROUTER_API_KEY` is read from `android/local.properties` (git-ignored) or the
environment, and compiled into `BuildConfig`.

> **Warning.** `BuildConfig` values are extractable from an unpacked APK. Use a
> rate-limited key, or proxy the call through your own backend before a public release.

## Versions

`gradle/libs.versions.toml` pins Gradle 9.7.1 / AGP 9.3.2 / Kotlin 2.3.0 / KSP 2.3.4 /
Room 2.8.3 — a mutually compatible set, sourced from Google's `android/nowinandroid`
sample. If Android Studio offers upgrades, take them together or codegen will break.

## Design tokens

`ui/theme/` holds the palette, type scale, shapes and dimensions ported 1:1 from the
web app's `tailwind.config` and CSS. See `FIGMA_ANDROID_UI_PROMPT.md` in the repository
root for the full mapping and the anti-truncation contract.
