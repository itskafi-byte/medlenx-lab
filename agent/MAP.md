# File map

Kotlin package root is `com/medlenx/lab/` under
`android/app/src/main/java/`. Line counts as of 2026-09-19 (81 files, 18,772
lines).

## Web → Android pairing

The port is feature-for-feature against the Python/Flask app on `origin/main`.
When you need the reference behaviour, read the Python file on the left.

| `origin/main` | Android | Notes |
|---|---|---|
| `app/medlenx_client.py` | `data/remote/MedLenXVlClient.kt` | OpenRouter VL call, JSON fence-stripping, truncation guard, fallback model retry |
| `app/medicine_matcher.py` | `data/repo/MedicineMatcher.kt` | brand/strength/form normalisation, MedexIndex fuzzy match |
| `app/compliance.py` | `data/repo/Compliance.kt` | NEML, antibiotic, polypharmacy, price ceiling, TRIPS, geo/territory |
| `app/intelligence.py` | `data/repo/Intelligence.kt` | DGDA check, own-brand find, generic substitution, pitch note |
| `app/rx_audit.py` | `data/repo/RxAudit.kt`, `data/repo/PHash.kt` | duplicate pHash, market share, CSV/clipboard |
| `app/pharma_hub.py` | `data/repo/PharmaHub.kt`, `data/repo/NewsRepository.kt` | news, jobs, health days, browse |
| `app/database.py` | `data/local/Daos.kt` + `Entities.kt` | 10 tables, 70 queries |
| `templates/index.html` | `ui/screens/**` | the UI being reproduced |

## Kotlin files by responsibility

### Entry / shell
| File | Lines | Role |
|---|---|---|
| `MainActivity.kt` | 34 | Compose entry |
| `MedLenXApp.kt` | 31 | app class, connectivity callback, queue drain |
| `ui/shell/MedLenXShell.kt` | 429 | nav host, insets, route mapping. `else -> PendingScreen` |
| `ui/shell/MlxTopBar.kt` | 236 | top bar |
| `ui/shell/MlxBottomNav.kt` | 94 | bottom nav, iterates `Destination.bottomBar` |
| `ui/navigation/Destination.kt` | 56 | Scan, Analytics, Hub, Team, Settings, Help, RxAudit |

### Data
| File | Lines | Role |
|---|---|---|
| `data/config/AppGraph.kt` | 110 | manual DI root |
| `data/local/Entities.kt` | 421 | 10 `@Entity` + 20 projection rows. **Columns are snake_case via `@ColumnInfo`** |
| `data/local/Daos.kt` | 937 | 6 DAOs, 70 `@Query`. Validated by `agent/roomcheck.py` |
| `data/local/MedLenXDatabase.kt` | 54 | Room database, `version = 3`. Real migrations (1→2 doctors, 2→3 `doctors.territory`); `fallbackToDestructiveMigration` stays registered for any version with no route |
| `data/local/AssetCatalogue.kt` | 144 | imports `medex_full.json` into Room |
| `data/local/Filters.kt` | 115 | filter state, `RX_FILTER_SQL` + `RX_FILTER_SQL_SNAPSHOT`, `SOURCE_OPTIONS` |
| `data/remote/MedLenXVlClient.kt` | 286 | VL client |
| `data/remote/ImagePrep.kt` | 92 | downscale to 1600px / JPEG 85 + EXIF rotation before upload |
| `data/repo/MedicineMatcher.kt` | 431 | matching |
| `data/repo/MedicineEnricher.kt` | 162 | builds the enriched medicine list the cards render from |
| `data/repo/MedicineImageStore.kt` | 93 | bundled catalogue first, live Medex fetch as fallback |
| `data/repo/Compliance.kt` | 429 | compliance rules |
| `data/repo/Intelligence.kt` | 289 | DGDA / substitution / pitch |
| `data/repo/RxAudit.kt` | 165 | share, CSV, clipboard |
| `data/repo/AnalyticsMetrics.kt` | 283 | KPI math |
| `data/repo/TeamMetrics.kt` | 442 | RSM metrics |
| `data/repo/PharmaHub.kt` | 320 | hub data |
| `data/repo/TripsPortfolio.kt` | 163 | TRIPS |
| `data/repo/Geofence.kt`, `LocationRepository.kt`, `ScanRepository.kt`, `DeviceStateRepository.kt`, `RegulatoryRepository.kt`, `NewsRepository.kt`, `PHash.kt`, `PyMath.kt` | | supporting |

### Screens
| File | Lines | Role |
|---|---|---|
| `ui/screens/scan/ScanScreen.kt` | 601 | capture + phases |
| `ui/screens/scan/ScanViewModel.kt` | 720 | the scan state machine. **Enrich first, then build cards** |
| `ui/screens/scan/Verification.kt` | 1107 | doctor + medicine verification |
| `ui/screens/scan/MedicineCard.kt` | 501 | editable card, ROI box, suggestions |
| `ui/screens/scan/PrescriptionImageViewer.kt` | 228 | fullscreen viewer (the zoom escape hatch) |
| `ui/screens/analytics/AnalyticsScreen.kt` | 708 | KPIs, charts, filters |
| `ui/screens/rx/RxAuditScreen.kt` | 694, `DoctorPitchCard.kt` 440 | Rx audit |
| `ui/screens/hub/HubScreen.kt` | 1152 | drug index, news, jobs, health days, TRIPS |
| `ui/screens/team/TeamScreen.kt` | 461, `TeamSections.kt` 654 | RSM command |
| `ui/screens/settings/SettingsScreen.kt` | 325 | settings, entry point to Help |
| `ui/screens/help/HelpScreen.kt` | 217 | guide + error escalation |
| `ui/screens/PendingScreen.kt` | 34 | placeholder — now unreachable, all 7 destinations have screens |

### Theme / components
`ui/theme/` (Color, Dimens, Shape, Theme, Type) and `ui/components/` (Buttons,
Cards, CompanyPill, EmptyStates, FlowRowCompat, MedicineThumb, Pills,
TextField).

`MedicineThumb.kt`: reads `LocalContext.current` in the composable **body** and
remembers on it — never move that read inside `remember { }`.

## Two traps in this codebase

1. **`ScanViewModel` must enrich before building cards.** Building cards from the
   raw VL read instead of the enriched list is what caused the pack-image bugs
   that survived two earlier "fixes".
2. **Entity columns are snake_case, Kotlin fields are camelCase.** Always
   `@ColumnInfo(name = "...")`. A query using the Kotlin field name will not
   compile.
