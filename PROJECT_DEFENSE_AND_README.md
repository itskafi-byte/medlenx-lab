# MedLenX Lab — Android project defense and code guide

> Code audit of the local Android sources at the time of writing. Paths below are relative to the repository root. This is an implementation guide, not evidence of a successful build, deployed security rules, clinical validation, or a live Gemini request. The source remains authoritative. The file-by-file index and exact import lists at the end complement the architectural analysis; private helpers and generated Android code are not independent modules.

## 1. Architecture and end-to-end data flow

### Architectural style and boundaries

This is a **single Android application module**, organized by packages rather than separate Gradle feature modules. It is MVVM-like: Compose screens render state and send events to `AndroidViewModel`/ViewModel classes; repositories, DAOs and data services perform work. This is not strict Clean Architecture: ViewModels access `AppGraph`, DAOs, Firebase authentication and Android APIs directly; several repository objects also contain domain calculations. The manual composition root `data/config/AppGraph.kt` constructs shared dependencies, not Hilt. `MedLenXApp.onCreate` constructs the graph, starts catalogue import on an application `SupervisorJob + Dispatchers.IO` scope, quarantines legacy images and starts connectivity observation. `MainActivity` enables edge-to-edge and composes `MedLenXTheme` → `AuthGate` → `MedLenXShell`.

Offline-first describes **durable local capture and verified results**, not offline AI or universal cloud replication. Room plus private files are authoritative for local work; Gemini, trusted membership checks and Firestore need network. Firebase Authentication identifies the user; company membership is server-controlled, not established just by selecting a company in Settings. `AuthGate` reacts to auth state, binds account-specific state, schedules upload/restore and gates the shell behind company onboarding. New-device onboarding must check server state online when no local profile exists. Sign-out is not a data purge. Settings also provides a separate, explicitly confirmed trapped-data purge. Do not confuse a local profile/company preference with trusted organization membership.

### Session sequence, with branching

1. **Launch and identity.** Android launches `MedLenXApp` and `MainActivity`. Firebase initialization is handled via `TrustedMembership.firebase`; `AuthGate` uses an auth listener. Per-user UI and synchronization are tied to the current UID. The shell routes Scan, Analytics, Hub and Team, plus Settings, Help and audit surfaces.
2. **Capture/import.** `CameraViewfinder` uses CameraX; runtime camera permission is requested by the scan screen. Image picking is an alternative. `ScanViewModel.onCameraCaptured` saves an oriented full-resolution private JPEG (quality 95); `onImagePicked` copies the selected stream to a user-scoped capture file and `decodeOriented` accounts for EXIF rotation/mirroring. UID and phase checks protect against stale asynchronous results. File access and location APIs are configured in the manifest; hardware features are optional.
3. **Local geometry.** `locateLines` invokes `DocumentLocator.locate`: ML Kit's Latin recognizer processes `InputImage.fromBitmap(bitmap, 0)`, collects text-block line bounding boxes and clips them to the bitmap. Recognized text is **not** the transcription. Initially detected boxes are selected; a user can deselect, retry detection or draw a clipped manual rectangle. Latin OCR can miss handwriting or Bengali, and a user must inspect the selection.
4. **Request.** `analyze()` requires Ready state, current UID, no ongoing locator/request, and 1–100 selected rectangles, preserving detected order. When offline it parks the capture and selections; otherwise it calls `ScanRepository.scan` → `MedLenXVlClient.scanPrescription`. The client is the deprecated `com.google.ai.client.generativeai:generativeai:0.9.0`, initialized with a locally configured API key, `gemini-3.8-flash`, JSON response MIME/schema and `RequestOptions(timeout = 240_000L)`. The first payload image is the full prescription for doctor/date; remaining images are ordered selected crops for medicine names. The prompt asks to mask patient identifiers, but the photograph itself may expose them to the provider.
5. **Image preparation.** `ImagePrep.bitmapUploadJpeg` scales only the full image down to a 1600px longest edge, never upscales, and JPEG-encodes each full image/crop at quality 80. The client decodes the bytes into payload bitmaps for the older SDK's `content { image(...) }` builder and releases them in `finally`; the SDK re-encodes bitmap images, creating another lossy step. Original saved files and rectangle coordinates are not replaced by these upload copies. A separate older `toUploadJpeg` helper uses quality 85; do not conflate it with the current scan request.
6. **Parsing and review.** A non-STOP candidate or absent text produces a failure. `GeminiScanParser.clean` removes outer Markdown fences without stripping backticks inside JSON. `parse` validates required doctor keys, date string, a true masked flag and one medicine per crop with finite confidence in [0,1]. It creates `VlScanResult`; patient note is replaced by a fixed masked note. Parse/API failures reach the UI; API exceptions are logged under `GeminiScan`. `enterVerification` enriches medicines using the cached MedEx matcher and regulatory data; doctor, medicine and GPS panels are editable. These are AI suggestions, not clinical diagnoses; human verification is essential.
7. **Durable local save.** `save()` merges the user's edits into the result, re-enriches with selected MedEx IDs, computes a perceptual image hash for duplicate detection when possible and calls `saveVerified`. The repository rechecks the signed-in UID and uses a Room transaction to resolve/update the doctor, create a UUID scan with the capture-time company snapshot and `PENDING` status, assign `RX-<row id>` and insert ordered medicine records. No image upload is part of this transaction. Duplicate override affects metric eligibility; local audit deletion records a restore tombstone and retains the photo and cloud copy.
8. **Background upload and restore.** A save requests one-time WorkManager upload after commit; periodic per-UID work also provides recovery. The worker requires a connected network, validates current UID and server membership, and uses the **stored company snapshot**, never silently rerouting a scan to another tenant. A fixed UUID becomes the Firestore document ID under `companies/{company}/team_scans/{scanId}`. It reconciles an existing document after a lost acknowledgment, otherwise uses `set(...).await()`, and marks `UPLOADED` only after acknowledgment/reconciliation. Permission denied marks `FAILED_REJECTED`; transient exceptions retry. Empty-company records require explicit claim. Cloud data contains structured results, not prescription photos.
9. **History and team.** On authentication, `RestoreHistoryWorker` queries server-confirmed, current-member records authored by the UID in pages of 100; it rechecks account and membership, skips tombstones and existing local scan IDs and inserts restored records transactionally. It cannot restore photos that were never uploaded, or all original local detail. `TeamFeedRepository` uses `callbackFlow`, a descending timestamp listener limited to 30 server-confirmed scans and a company-scoped avatar-ID listener, cleaning up listeners on close.

**Two queues:** `queued_scans` parks images awaiting a future AI review and does not automatically submit them. Verified `PENDING` prescriptions are separately uploaded by WorkManager. Reopening a parked row restores saved rectangles when valid, otherwise runs detection again; it remains queued until verified save succeeds. An unreadable first queued image can block later ones.

## 2. Frontend — Jetpack Compose

`MedLenXShell` and `Destination` define navigation; Material 3 theme, colors, typography, shapes and dimensions are under `ui/theme`. Reusable components under `ui/components` implement cards, pills, buttons, avatars, input fields and image presentation. Composables use `Modifier` for layout, clipping, scrolling, drawing and gesture handling. State is partially hoisted: screen-level ViewModels own business state and handlers, with callbacks passed down to smaller components; local transient UI state also uses Compose `remember`. It is not a claim that every screen is perfectly stateless. Coroutines in `viewModelScope` run async work; Room Flow and Compose state cause UI updates. Navigation and auth state determine which screen is composed.

| Surface | Actual role and important components |
|---|---|
| `AuthGate`, `CompanyOnboardingScreen` | Firebase sign-in/session gate; company/profile onboarding and account-scoped startup. |
| `MedLenXShell`, `MlxTopBar`, `MlxBottomNav` | Root navigation, online/pending indicators, profile/settings access and core destinations. |
| `ScanScreen`, `ScanHome`, `CameraViewfinder` | Capture or choose photo, show online/queue states, start review and handle permission. |
| `LineSelector`, `PrescriptionImageViewer`, `ScanAnimation` | Select detected rectangles or draw missed lines; zoomable/clipped viewer and fullscreen escape; temporary animation highlights do not represent live OCR transcription. |
| `Verification`, `MedicineCard`, `GeoStrip` | Editable doctor/date, medicine and location steps, catalogue suggestions, product image, confidence and GPS status; save remains explicit. |
| `RxAuditScreen`, `AuditActionControls`, `DoctorPitchCard` | Inspect audit/compliance, duplicate actions, per-prescription controls and pitch details. |
| `AnalyticsScreen`, `FilterSheet`, `RxBreakdownSheet`, `AnalyticsCharts` | Filters, KPI/leaderboard, recent scans, market share, chart and detail drilldowns; charts use Compose drawing rather than a separate chart library. |
| `HubScreen`/`HubViewModel` | Drug index, DGDA information, news, TRIPS, health days and jobs backed by bundled data and repository logic. |
| `TeamScreen`, `TeamSections`, `TeamFeed`, `MapboxHeatmap` | Team/doctor analytics, stewardship, activity feed and map. A blank Mapbox token uses an offline canvas fallback; tiles otherwise need network. |
| `SettingsScreen`, `SettingsViewModel` | Profile, company/territory and daily target (locally persisted, default 10), account and explicit local-data actions. |
| `HelpScreen`, `PendingScreen`, `SearchOverlay` | Guide and error reports; pending work; cross-app search overlay. |

## 3. Data storage, search and background work

### Room/SQLite: what is actually present

`MedLenXDatabase` is Room version **16**, database `medlenx.db`, with migrations 1→16 registered and `exportSchema = false`. It deliberately does not enable destructive migration fallback. Entities include `medex_products`, `doctors`, `prescriptions`, `scanned_medicines`, `queued_scans`, officer profiles, brand/doctor targets, visits, error reports, manufacturer aliases and local deleted-scan tombstones. Foreign-key/index details are defined in `Entities.kt`; use `Daos.kt` for exact SQL. User-scoped query predicates and auth checks are important: multiple users' retained local data coexist on the device. A nullable unique `(user_id, restore_scan_id)` index supports idempotent restore without requiring legacy rows to have UUIDs.

**Important correction: there is no FTS5 setup in this code.** `MedexEntity` has ordinary indexes on brand, company, generic and type. `MedexDao.search` performs `LIKE '%' || :q || '%'` across those four columns with a limit; leading wildcards may prevent normal B-tree indexes from accelerating substring matches. `byBrandExact` uses a case-insensitive equality. Separately `ScanRepository.medexIndex()` awaits import and builds an in-memory `MedexIndex` for matching, with direct-asset fallback when Room is empty. Do not describe it as SQLite FTS5, quote a benchmark, or claim indexed full-text search. A future FTS5 migration would require explicit table creation, synchronization and measured search behavior.

`AssetCatalogue.ensureImported` serializes import with a mutex, checks existing row count, reads bundled `assets/data/medex_full.json` (documented as 25,105 rows, ~16 MB) and writes 2,000-row batches. It exposes import state/count; because the existing-row shortcut checks only `count > 0`, an interrupted partial import is a limitation to investigate, not proof of completeness. Other JSON assets support location, regulatory, news and Hub features. Manufacturer aliases normalize reporting while retaining original values where implemented.

### Synchronization and security

- `UploadScheduling.start`: connected-network unique periodic work every 15 minutes (`KEEP`) plus one-time work; one-shot enqueue itself is not unique. Room's persisted rows, not WorkManager payloads, are authoritative.
- `UploadScanWorker.doWork`: filters pending rows by UID, validates a server-confirmed active membership and display name, checks the stored tenant and stable UUID, reconciles pre-existing immutable fields, awaits write acknowledgment and then marks uploaded. A permanent tenant/permission rejection differs from retryable network or other transient failure. Metadata-only backfill runs for eligible previously uploaded local scans; it fills missing metadata after compatibility checks, not arbitrary old local-only scans.
- `RestoreHistoryWorker.doWork`: network-constrained unique one-time work; paginates by document ID, validates server origin, current account and tenant, skips local tombstones; inserts a bounded, reduced local representation. Timestamp falls back to server upload time for older records when capture time is absent. Restored rows do not reconstruct local photographs and currently set duplicate linkage to null.
- `TrustedMembership`, `ProfileCloudSync`, `AvatarCloudSync`: separate trusted server membership from private profile and minimal company avatar directory. Security depends on deploying `android/firebase/firestore.rules`; client checks alone do not enforce authorization. Membership approval/assignment is administrative.
- Device-local audit delete uses a tombstone to prevent re-restore on that device; it is not cloud deletion or a global deletion signal. Explicit trapped-data purge is destructive and distinct from normal sign-out.

## 4. Core modules and functions

The application is organized into `data/config` (manual dependency graph), `data/local` (entities, DAOs, migration, assets, files/preferences), `data/model` (serializable/domain structures), `data/remote` (ML Kit and Gemini), `data/repo` (scan, matching, compliance, analytics, location and content logic), `data/sync` (Firestore and workers), `data/export` (PDF/document generation), `ui` (auth, screens, components, shell/theme), and `util` (Bengali normalization). There is no separate top-level worker Gradle module: workers are Kotlin classes in `data/sync`.

### ScanViewModel — critical entry points

| Function | Contract and considerations |
|---|---|
| `onImagePicked`, `onCameraCaptured`, `decodeOriented` | Save/load private image and orient it; check current account after async work. |
| `locateLines`, `toggleDetectedLine`, `addManualLine`, `retryLocator` | Detect bounded ML Kit rectangles, maintain selection and allow manual recovery. |
| `analyze` | Validate state, bounds/count/account; choose offline parking versus repository request; show errors and route successful results to verification. |
| `parkForReplay`, `syncQueuedScans`, `replayQueued` | Store image path plus `lines-v1` JSON coordinates; reopen one queued capture for confirmation; never silently submit legacy rows. |
| `enterVerification`, `onDoctorChange`, `gotoMedicines`, `gotoGps` | Enrich result and establish editable step state; metadata comes from the full image, medicine rows from crops. |
| `onBrandChange`, `pickSuggestion`, `applyAlternative`, `verifyAgainstMedex` | Manage correction, catalogue choices and verified matching; image/company enrichment follows matched products. |
| `mergeEdits`, `save`, `findDuplicate` | Merge reviewed card edits, re-enrich selected IDs, hash for duplicate candidates and save transactionally; remove a replay queue row only after successful save. |
| `pinGps`, `onGeoChange`, `applyFix`, `reverify` | Collect/adjust location and evaluate geofence evidence; location permissions and fix availability matter. |
| `prepareClaim`, `confirmClaim`, `cancelClaim` | Explicit user action for eligible previously unstamped local rows; not automatic migration into a newly selected company. |
| `clear`, `onCleared` | Reset workspace/lifecycle resources; not a general sign-out data deletion. |

### Repository / AI functions

- `ScanRepository.scan(bitmap, selectedLines)`: checks API key, delegates to the client, translates `VlOutcome` to UI progress. `queueForLater`, `updateQueuedSelection`, `removeQueued` and `drainQueue` use UID-scoped queue storage. `drainQueue` offers at most the oldest row for review and retains it until the handler says it can be removed.
- `ScanRepository.saveVerified(...)`: validates current UID and snapshot, transactionally inserts prescription/medicine records and schedules upload after commit. `resolveDoctorId` uses normalized per-user doctor identity and merges nonblank verified details. `medexIndex` avoids caching an empty index; `searchCatalogue` calls DAO substring search. `MedicineEnricher.enrich` and `MedicineMatcher` handle matching, manufacturer, image and compliance context rather than trusting a model-invented manufacturer.
- `DocumentLocator.locate`: returns geometry only; `close` releases recognizer. `ImagePrep.uploadDimensions` and `bitmapUploadJpeg` handle upload sizing and JPEG copies. `MedLenXVlClient.scanPrescription`: creates structured content and schema, observes cancellation, catches/logs API errors, checks finish reason and releases temporary bitmaps. `GeminiScanParser.clean/parse` handle fences and strict shape validation.
- `ScanCloudFields` converts local medicine/date metadata to cloud form and checks compatibility. `TeamFeedRepository.observe/listen` binds tenant-scoped listeners and filters cached/pending data. `AnalyticsMetrics`, `TeamMetrics`, `DoctorIntelligence`, `RxAudit` and `Compliance` contain domain calculations consumed by screens.

### Build, resources, configuration and limitations

`android/app/build.gradle.kts` sets namespace/application ID `com.medlenx.lab`, min SDK 26, compile SDK 37, target SDK 36, Java/Kotlin target 17, Compose/Room KSP, CameraX, ML Kit, Firebase, WorkManager and legacy Gemini. Firebase BOM 33.7.0 and enforced gRPC BOM 1.62.2 attempt to align Firestore runtime dependencies; the earlier gRPC fix is not device-verified. Packaging excludes duplicate `META-INF/INDEX.LIST` and `META-INF/DEPENDENCIES`. Debug is unminified; release currently uses debug signing and is not release-ready. `checkMedLenXAssets` guards required assets before build; refresh is explicitly opt-in. `AndroidManifest.xml` declares internet, network, camera and coarse/fine location, FileProvider and backup rules; see `res/xml` for actual backup/file-provider policy. `res/values`, `drawable`, and launcher mipmaps provide Android resources. Dataset license/provenance and accuracy should be reviewed before redistribution.

`GEMINI_API_KEY`, Firebase values and optional `MAPBOX_ACCESS_TOKEN` come from ignored `android/local.properties` or environment and become generated build configuration; the checked-in `local.properties.example` still mentions the obsolete OpenRouter key, so follow the actual build script. **APK-contained keys are extractable**. For public deployment use an appropriate secure backend or supported Firebase AI architecture and review provider data policies. Raw exception messages/Logcat can contain keys or sensitive data: redact before sharing. The full image can contain patient identifiers despite the output masking. The configured `gemini-3.8-flash` identifier is a source string, not evidence that this model exists or the user's key can access it; the UI display name still says “Gemini 2.5 Flash”. A live model list/request is needed to confirm availability. `httpClient` is passed to `MedLenXVlClient` but not used for the legacy SDK request; `RequestOptions` governs its configured timeout.

## 5. Supervisor Q&A

1. **Why CameraX + ML Kit before Gemini?** CameraX integrates capture with Android lifecycle; on-device ML Kit gives user-selectable line geometry without treating OCR text as the transcription. Sending only selected line crops plus one context image constrains the model's medicine reading. This does not guarantee OCR catches handwriting; manual rectangles are required as fallback.
2. **How is the 25K drug index searched, and is it FTS5?** It is not FTS5. Room imports a bundled catalogue, offers SQL substring `LIKE` and indexed exact fields, while matching uses a cached in-memory `MedexIndex`. Leading-wildcard searches can scan; profile on-device before claiming performance.
3. **What happens on a Gemini timeout?** The legacy model is configured with `RequestOptions(timeout = 240_000L)`. Exceptions are logged and surfaced; if connectivity is offline when failure is processed, the capture is parked for explicit later review. No automatic paid fallback is configured. Timeout is not proof that retries or endpoint access work.
4. **How do you prevent duplicate cloud writes after a crash or lost ACK?** Each saved scan gets one UUID used as document ID. On retry the worker fetches the server document and checks identity/content compatibility before treating it as acknowledged; it only marks uploaded after `await()` or reconciliation. This is idempotent under those checks, not a distributed exactly-once guarantee.
5. **How is tenant isolation enforced?** The save stores a capture-time company snapshot; worker refreshes trusted membership and rejects mismatches instead of rerouting. Firestore rules are the real authorization boundary and must be deployed and tested. A local company preference is not proof of membership.
6. **Can a new device recover everything?** No. Restore downloads only the current user's server-confirmed structured scans from the current trusted company; local photos are not uploaded and unuploaded scans cannot be restored from Firestore. Some detailed local fields and duplicate links are not reconstructed.
7. **Why require human review and schema validation?** The model can hallucinate or return malformed/incomplete output. Parser checks metadata, mask flag, ordered line count and confidence range, and users correct the review cards before `saveVerified`. This limits malformed data; it does not prove medical accuracy or prevent identifying information being visible in the transmitted image.

## 6. Validation and known audit caveats

No Android compilation, Kotlin tests, device execution, deployed-rule test, dependency resolution or live Gemini call was performed as part of this documentation audit. Existing `android/checks/scanarchitecturecheck.py` still hard-codes the old model string and will fail against the current endpoint; the UI label and parts of older docs/examples are stale. Treat historical comments and documentation as context, not proof of runtime behavior. Validate migrations on real historical databases, account-switch/cancellation races, duplicate behavior across devices, Gemini model access and Firestore rules before deployment. Never uninstall or clear app data as a diagnostic for local-only records.

## 7. Exact imports of the three core source files

These are copied from the current source files rather than suggested dependencies. They show all explicit imports, including any currently unused ones; Kotlin's implicit/default imports and same-package declarations are not listed. They are not a claim that copying one file in isolation is sufficient.

### `ui/screens/scan/ScanViewModel.kt`

```kotlin
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import android.media.ExifInterface
import com.medlenx.lab.data.remote.DocumentLocator
import android.net.Uri
import androidx.room.withTransaction
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.medlenx.lab.MedLenXApp
import com.google.firebase.auth.FirebaseAuth
import com.medlenx.lab.data.local.UserCaptureFiles
import com.medlenx.lab.data.sync.TrustedMembership
import com.medlenx.lab.data.sync.UploadScheduling
import com.medlenx.lab.data.local.DailyTargetSettings
import com.medlenx.lab.data.local.DrillCountRow
import com.medlenx.lab.data.local.ErrorReportEntity
import com.medlenx.lab.data.local.OfficerProfileEntity
import com.medlenx.lab.data.local.PrescriptionEntity
import com.medlenx.lab.data.local.PrescriptionHashRow
import com.medlenx.lab.data.local.QueuedScanEntity
import com.medlenx.lab.data.model.EnrichedMedicine
import com.medlenx.lab.data.model.GpsFix
import com.medlenx.lab.data.model.GpsSource
import com.medlenx.lab.data.model.MatchType
import com.medlenx.lab.data.model.MedexProduct
import com.medlenx.lab.data.model.VlScanResult
import com.medlenx.lab.data.repo.MedicineEnricher
import com.medlenx.lab.data.repo.MedicineMatcher
import com.medlenx.lab.data.repo.Intelligence
import com.medlenx.lab.data.repo.PHash
import com.medlenx.lab.data.repo.RxAudit
import com.medlenx.lab.ui.components.CompanyVerification
import com.medlenx.lab.data.repo.ScanProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale
```

### `data/repo/ScanRepository.kt`

```kotlin
import com.google.firebase.auth.FirebaseAuth
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.medlenx.lab.data.sync.TrustedMembership
import com.medlenx.lab.data.sync.UploadScheduling
import androidx.room.withTransaction
import com.medlenx.lab.data.local.MedLenXDatabase
import com.medlenx.lab.data.local.AssetCatalogue
import com.medlenx.lab.data.local.DoctorDao
import com.medlenx.lab.data.local.DoctorEntity
import com.medlenx.lab.data.local.DoctorIdentity
import com.medlenx.lab.data.local.MedexDao
import com.medlenx.lab.data.local.PrescriptionDao
import com.medlenx.lab.data.local.PrescriptionEntity
import com.medlenx.lab.data.local.QueueDao
import com.medlenx.lab.data.local.QueuedScanEntity
import com.medlenx.lab.data.local.ScannedMedicineEntity
import com.medlenx.lab.data.local.UserCaptureFiles
import com.medlenx.lab.data.model.EnrichedMedicine
import com.medlenx.lab.data.model.VlScanResult
import com.medlenx.lab.data.remote.MedLenXVlClient
import com.medlenx.lab.data.remote.VlOutcome
import com.medlenx.lab.util.Bengali
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
```

### `data/remote/MedLenXVlClient.kt`

```kotlin
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.util.Log
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.Schema
import com.google.ai.client.generativeai.type.FinishReason
import com.google.ai.client.generativeai.type.RequestOptions
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.generationConfig
import com.medlenx.lab.BuildConfig
import com.medlenx.lab.data.model.VlScanResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
```

## 8. Source file inventory and declaration map

This mechanically extracted index covers every Kotlin file under `app/src/main/java/com/medlenx/lab`. It lists named classes, objects, interfaces, and named functions, including private helpers and extension functions where detectable. It is a navigation aid, not an API contract or a complete list of anonymous lambdas, properties or generated symbols. Use the linked file for parameters, imports, behavior and current implementation.

- [`MainActivity.kt`](android/app/src/main/java/com/medlenx/lab/MainActivity.kt): `MainActivity`, `onCreate()`
- [`MedLenXApp.kt`](android/app/src/main/java/com/medlenx/lab/MedLenXApp.kt): `MedLenXApp`, `onCreate()`
- [`data/config/AppGraph.kt`](android/app/src/main/java/com/medlenx/lab/data/config/AppGraph.kt): `AppGraph`, `importCatalogue()`, `create()`
- [`data/export/AnalyticsExportManager.kt`](android/app/src/main/java/com/medlenx/lab/data/export/AnalyticsExportManager.kt): `AnalyticsExportManager`, `reportFile()`, `csv()`, `safe()`, `pdf()`, `share()`
- [`data/export/ExportDocuments.kt`](android/app/src/main/java/com/medlenx/lab/data/export/ExportDocuments.kt): `ExportDocuments`, `pitchCardPdf()`, `rsmReportPdf()`, `receiptLines()`, `chamberSummaryPdf()`, `brandColor()`, `ReceiptMedicine`, `chamberSummaryFileName()`, `recentMedicinesCsv()`, `csvEscape()`, `formatConfidence()`, `orDash()`, `joined()`, `mrpLabel()`, `formatPercent()`, `stamp()`, `isoStamp()`
- [`data/export/PdfWriter.kt`](android/app/src/main/java/com/medlenx/lab/data/export/PdfWriter.kt): `PdfWriter`, `Style`, `openPage()`, `reserve()`, `closePage()`, `configure()`, `wrap()`, `fit()`, `paragraph()`, `drawText()`, `title()`, `subtitle()`, `heading()`, `body()`, `bullet()`, `gap()`, `rule()`, `table()`, `drawHeader()`, `finish()`
- [`data/local/AssetCatalogue.kt`](android/app/src/main/java/com/medlenx/lab/data/local/AssetCatalogue.kt): `CatalogueState`, `AssetCatalogue`, `ensureImported()`, `readMedexAsset()`, `readProducts()`, `readAsset()`, `toEntity()`
- [`data/local/AvatarStore.kt`](android/app/src/main/java/com/medlenx/lab/data/local/AvatarStore.kt): `AvatarStore`, `valid()`, `idKey()`, `dirtyKey()`, `observe()`, `observePending()`, `snapshot()`, `select()`, `restoreIfClean()`, `acknowledge()`
- [`data/local/CompanySelection.kt`](android/app/src/main/java/com/medlenx/lab/data/local/CompanySelection.kt): `CompanySelection`, `isAssigned()`
- [`data/local/DailyTargetSettings.kt`](android/app/src/main/java/com/medlenx/lab/data/local/DailyTargetSettings.kt): `DailyTargetSettings`
- [`data/local/Daos.kt`](android/app/src/main/java/com/medlenx/lab/data/local/Daos.kt): `DoctorDao`, `count()`, `idFor()`, `byKey()`, `update()`, `insertIgnoring()`, `all()`, `MedexDao`, `insertAll()`, `byBrandExact()`, `search()`, `byCompany()`, `companies()`, `forms()`, `PrescriptionDao`, `insert()`, `upsert()`, `countByScanId()`, `insertRestored()`, `updateRestoredCaptureTime()`, `updateRestoredPrescriptionDate()`, `updateRestoredProductId()`, `metadataBackfillCandidates()`, `markMetadataSynced()`, `relocateLegacyImage()`, `markUploaded()`, `observeTrappedCount()`, `trappedAudits()`, `deleteMedicinesForAudits()`, `deleteVisitsForAudits()`, `deleteTrappedAudits()`, `allReferencedImagePaths()`, `markRejected()`, `addDeletedScanTombstone()`, `isDeletedScan()`, `approveDuplicateForTesting()`, `deleteOwnedMedicines()`, `deleteOwnedVisits()`, `deleteOwnedPrescription()`, `pendingUploads()`, `observePendingUploads()`, `pendingUploadCount()`, `claimableAudits()`, `observeClaimableAudits()`, `assignAudit()`, `observeUnroutableUploads()`, `observeRecent()`, `observeCountBetween()`, `byId()`, `findByHash()`, `scannedSince()`, `doctorTierRows()`, `stewardshipRows()`, `offTerritoryRows()`, `scanPointRows()`, `trendRows()`, `observeBrandCapturedRows()`, `observeDashboardBrandScans()`, `observeDashboardTopDoctors()`, `geoRegionRows()`, `prescriptionCountSince()`, `prescriptionCountBetween()`, `prescriptionCountAll()`, `itemCountSince()`, `ownItemCountSince()`, `ownItemCountBetween()`, `itemCountBetween()`, `topBrandRow()`, `activeDoctorCount()`, `allDoctorCount()`, `companyItemCount()`, `companyGenerics()`, `companyBrands()`, `companyDoctors()`, `brandDoctors()`, `recentMedicineExportRows()`, `scanExportRows()`, `mostPrescribedRows()`, `companyShareRows()`, `doctorLeaderRows()`, `doctorLeaderIntelligence()`, `doctorLeaderTotal()`, `doctorLeaderMedicines()`, `liveScanRows()`, `observeAuditFeed()`, `observeAuditFeedCount()`, `specialtyCompanyCounts()`, `genericMatrixRows()`, `filterDistricts()`, `filterTerritories()`, `filterSpecialties()`, `filterMrIds()`, `insertMedicines()`, `medicinesFor()`, `recentMedicines()`, `medicineCount()`, `setRxNumber()`, `hashRows()`, `QueueDao`, `enqueue()`, `observeAll()`, `observeCount()`, `remove()`, `updatePayload()`, `markAttempt()`, `ProfileDao`, `save()`, `observe()`, `current()`, `observeTargets()`, `saveTargets()`, `clearTargets()`, `doctorTargetRows()`, `recentVisits()`, `deleteDoctorTarget()`, `RsmDao`, `addTarget()`, `removeTarget()`, `targetsForDoctor()`, `logVisit()`, `visitsFor()`, `reportError()`, `observeErrors()`, `ManufacturerAliasDao`, `canonicalCompanies()`, `canonicalFor()`
- [`data/local/DoctorIdentity.kt`](android/app/src/main/java/com/medlenx/lab/data/local/DoctorIdentity.kt): `DoctorIdentity`, `keyOf()`, `norm()`, `displayName()`
- [`data/local/Entities.kt`](android/app/src/main/java/com/medlenx/lab/data/local/Entities.kt): `MedexEntity`, `DoctorEntity`, `PrescriptionEntity`, `ScannedMedicineEntity`, `QueuedScanEntity`, `OfficerProfileEntity`, `BrandTargetEntity`, `DoctorTargetEntity`, `DoctorVisitEntity`, `ErrorReportEntity`, `ScannedItemRow`, `DoctorTierRow`, `StewardshipRow`, `OffTerritoryRow`, `ScanPointRow`, `BrandCapturedRow`, `TrendItemRow`, `DoctorTargetRow`, `AuditFeedEvent`, `SpecialtyCompanyCount`, `LiveScanFeedRow`, `RecentMedicineRow`, `GenericMatrixRow`, `PrescriptionHashRow`, `MostPrescribedRow`, `CompanyShareRow`, `DoctorLeaderRow2`, `DoctorBrandCount`, `DoctorPrescriptionCompanyCount`, `TopBrandRow`, `DrillCountRow`, `BrandDoctorRow`, `GeoRegionRow`, `DoctorVisitRow`, `ScanExportRow`, `ManufacturerAlias`, `DeletedScanTombstone`
- [`data/local/Filters.kt`](android/app/src/main/java/com/medlenx/lab/data/local/Filters.kt): `FilterState`, `FilterOptions`
- [`data/local/LegacyImageQuarantine.kt`](android/app/src/main/java/com/medlenx/lab/data/local/LegacyImageQuarantine.kt): `LegacyImageQuarantine`, `run()`, `digest()`
- [`data/local/ManufacturerAliases.kt`](android/app/src/main/java/com/medlenx/lab/data/local/ManufacturerAliases.kt): `ManufacturerAliases`, `seed()`
- [`data/local/MedLenXDatabase.kt`](android/app/src/main/java/com/medlenx/lab/data/local/MedLenXDatabase.kt): `MedLenXDatabase`, `manufacturerAliasDao()`, `doctorDao()`, `medexDao()`, `prescriptionDao()`, `queueDao()`, `profileDao()`, `rsmDao()`, `get()`, `onCreate()`
- [`data/local/Migrations.kt`](android/app/src/main/java/com/medlenx/lab/data/local/Migrations.kt): `Migrations`, `migrate()`, `migration11To12()`, `backfillTerritory()`, `backfillDoctors()`
- [`data/local/UserCaptureFiles.kt`](android/app/src/main/java/com/medlenx/lab/data/local/UserCaptureFiles.kt): `UserCaptureFiles`, `key()`, `stored()`, `temporary()`, `ownsStored()`
- [`data/model/Catalogue.kt`](android/app/src/main/java/com/medlenx/lab/data/model/Catalogue.kt): `MedexProduct`, `BdLocations`, `BdDistrict`
- [`data/model/Confidence.kt`](android/app/src/main/java/com/medlenx/lab/data/model/Confidence.kt): `ConfidenceBands`, `ConfidenceBand`, `confidenceBand()`, `needsAuditFollowUp()`
- [`data/model/Geo.kt`](android/app/src/main/java/com/medlenx/lab/data/model/Geo.kt): `BdGeo`, `GeoPoint`, `TerritoryVerdict`, `GpsSource`, `GpsFix`
- [`data/model/HubData.kt`](android/app/src/main/java/com/medlenx/lab/data/model/HubData.kt): `HealthDay`, `HealthDays`, `PharmaJob`, `PharmaJobs`, `EnrichedJob`, `JobBoard`, `HealthDayEntry`, `HealthDayDetail`, `HealthCalendar`
- [`data/model/NewsData.kt`](android/app/src/main/java/com/medlenx/lab/data/model/NewsData.kt): `NewsItem`, `NewsSeed`, `NewsFeed`
- [`data/model/RegulatoryData.kt`](android/app/src/main/java/com/medlenx/lab/data/model/RegulatoryData.kt): `NemlMolecule`, `NemlList`, `TripsMolecule`, `TripsWaiver`, `DgdaPriceRow`, `DgdaBannedRow`, `DgdaAdjustedRow`, `DgdaPrices`, `RegulatoryData`
- [`data/model/ScanModels.kt`](android/app/src/main/java/com/medlenx/lab/data/model/ScanModels.kt): `VlScanResult`, `VlDoctor`, `VlMedicine`, `VlPatientInfo`, `VlMeta`, `EnrichedMedicine`, `MatchType`, `NemlStatus`, `DgdaAlert`, `TripsWatch`, `Substitution`, `PitchCompliance`, `VerifiedDoctor`
- [`data/remote/DocumentLocator.kt`](android/app/src/main/java/com/medlenx/lab/data/remote/DocumentLocator.kt): `DocumentLocator`, `locate()`, `close()`
- [`data/remote/GeminiScanParser.kt`](android/app/src/main/java/com/medlenx/lab/data/remote/GeminiScanParser.kt): `GeminiScanParser`, `clean()`, `and`, `parse()`
- [`data/remote/ImagePrep.kt`](android/app/src/main/java/com/medlenx/lab/data/remote/ImagePrep.kt): `ImagePrep`, `uploadDimensions()`, `bitmapUploadJpeg()`, `toUploadJpeg()`, `rotate()`, `readOrientation()`
- [`data/remote/MedLenXVlClient.kt`](android/app/src/main/java/com/medlenx/lab/data/remote/MedLenXVlClient.kt): `VlOutcome`, `Success`, `Failure`, `NoKey`, `MedLenXVlClient`, `scanPrescription()`, `compressedBitmap()`, `per`, `resultSchema()`, `defaultHttpClient()`, `defaultJson()`
- [`data/remote/OpenRouterErrors.kt`](android/app/src/main/java/com/medlenx/lab/data/remote/OpenRouterErrors.kt): `OpenRouterErrors`, `Problem`, `fromResponse()`
- [`data/remote/VlResponseSchema.kt`](android/app/src/main/java/com/medlenx/lab/data/remote/VlResponseSchema.kt): `VlResponseSchema`, `accepts()`, `matches()`
- [`data/repo/AnalyticsMetrics.kt`](android/app/src/main/java/com/medlenx/lab/data/repo/AnalyticsMetrics.kt): `AnalyticsMetrics`, `pctDelta()`, `dashboardKpis()`, `mostPrescribed()`, `companyShare()`, `genericBrandMatrix()`, `doctorLeaders()`, `PrescriptionKpi`, `TopBrand`, `MarketShare`, `DoctorCoverage`, `DashboardKpis`, `MostPrescribed`, `CompanySlice`, `DoctorLeader`, `DoctorLeaderboard`, `GenericMatrixRowOut`, `GenericMatrix`
- [`data/repo/AuditActions.kt`](android/app/src/main/java/com/medlenx/lab/data/repo/AuditActions.kt): `AuditActions`, `requireOwner()`, `delete()`, `approve()`
- [`data/repo/Compliance.kt`](android/app/src/main/java/com/medlenx/lab/data/repo/Compliance.kt): `Compliance`, `norm()`, `moleculeKeyMatch()`, `wordBoundary()`, `nemlLookup()`, `isAntibiotic()`, `isBroadSpectrum()`, `resolveTherapeuticClass()`, `polypharmacyIndex()`, `priceCeilingAlert()`, `therapyBreakdown()`, `tripsLookup()`, `toWatch()`, `tripsExpiry()`, `substitutionEvidenceNotes()`, `Polypharmacy`, `TherapySlice`, `EvidenceNotes`, `DgdaStatus`
- [`data/repo/DeviceStateRepository.kt`](android/app/src/main/java/com/medlenx/lab/data/repo/DeviceStateRepository.kt): `DeviceStateRepository`, `pendingUploadsFor()`, `bindUser()`, `start()`, `onAvailable()`, `onLost()`, `setCompany()`, `refreshOnline()`
- [`data/repo/DoctorIntelligence.kt`](android/app/src/main/java/com/medlenx/lab/data/repo/DoctorIntelligence.kt): `DoctorIntelligence`, `enrich()`
- [`data/repo/Geofence.kt`](android/app/src/main/java/com/medlenx/lab/data/repo/Geofence.kt): `Geofence`, `normalize()`, `territoryBase()`, `haversineKm()`, `resolveGeoDistrict()`, `territoryCheck()`, `Evidence`
- [`data/repo/Intelligence.kt`](android/app/src/main/java/com/medlenx/lab/data/repo/Intelligence.kt): `Intelligence`, `normaliseKey()`, `gazetteLookup()`, `dgdaCheck()`, `fmtMrp()`, `findOwnBrand()`, `genericSubstitution()`, `smartPitchNote()`
- [`data/repo/LocationRepository.kt`](android/app/src/main/java/com/medlenx/lab/data/repo/LocationRepository.kt): `LocationRepository`, `locations()`, `geo()`, `districtNames()`, `upazilasFor()`, `territoriesFor()`, `specialties()`, `divisions()`, `lastKnownFix()`, `exifFix()`, `verdict()`
- [`data/repo/MedicineEnricher.kt`](android/app/src/main/java/com/medlenx/lab/data/repo/MedicineEnricher.kt): `MedicineEnricher`, `enrich()`, `enrichOne()`, `toProduct()`
- [`data/repo/MedicineImageStore.kt`](android/app/src/main/java/com/medlenx/lab/data/repo/MedicineImageStore.kt): `MedicineImageStore`, `imageFor()`, `bundled()`, `live()`
- [`data/repo/MedicineMatcher.kt`](android/app/src/main/java/com/medlenx/lab/data/repo/MedicineMatcher.kt): `MedicineMatcher`, `hamming()`, `asciiFold()`, `foldDigits()`, `normalizeBrand()`, `normalizeStrength()`, `normalizeForm()`, `companyKey()`, `OwnCompany`, `resolveOwnCompany()`, `sameCompany()`, `resolveCompany()`, `sequenceRatio()`, `matchingCharacters()`, `findLongestMatch()`, `CompanyResolution`, `MatchResult`, `MedexIndex`, `build()`, `ownCandidates()`, `Candidate`, `productById()`, `exact()`, `companyIsAmbiguous()`, `pickVariant()`, `match()`, `fuzzy()`, `variantScore()`, `round3()`
- [`data/repo/NewsRepository.kt`](android/app/src/main/java/com/medlenx/lab/data/repo/NewsRepository.kt): `NewsRepository`, `load()`, `fetchLive()`, `httpGet()`, `parseRssItems()`, `RawItem`, `readItem()`, `cleanSummary()`, `parseRssDate()`, `defaultClient()`
- [`data/repo/PHash.kt`](android/app/src/main/java/com/medlenx/lab/data/repo/PHash.kt): `PHash`, `compute()`, `hammingDistance()`, `dct1d()`, `grayscale()`, `decodeScaled()`
- [`data/repo/PharmaHub.kt`](android/app/src/main/java/com/medlenx/lab/data/repo/PharmaHub.kt): `PharmaHub`, `jobApplyUrl()`, `pharmaJobs()`, `packScore()`, `medexBrowse()`, `uniqueCompaniesFrom()`, `healthDayDetail()`, `focusKey()`, `healthDays()`, `BrowseResult`, `CompanyCount`, `CompanyList`
- [`data/repo/PyCsv.kt`](android/app/src/main/java/com/medlenx/lab/data/repo/PyCsv.kt): `PyCsv`, `escape()`, `row()`
- [`data/repo/PyMath.kt`](android/app/src/main/java/com/medlenx/lab/data/repo/PyMath.kt): `PyMath`, `round()`, `round1()`, `round2()`, `fixed2()`, `fixed1()`, `fixed0()`, `fixedCoords()`, `pyFloat()`
- [`data/repo/RegulatoryRepository.kt`](android/app/src/main/java/com/medlenx/lab/data/repo/RegulatoryRepository.kt): `RegulatoryRepository`, `data()`, `load()`
- [`data/repo/RxAudit.kt`](android/app/src/main/java/com/medlenx/lab/data/repo/RxAudit.kt): `RxAudit`, `isDuplicateHash()`, `buildMarketShare()`, `linesOf()`, `lineOf()`, `sameCompanyLoose()`, `itemsToCsv()`, `itemsToClipboard()`, `clipboardHeader()`, `pyRound()`, `round1()`, `confidencePercentOf()`, `csvEscape()`, `RxAuditLine`, `RxMarketShare`
- [`data/repo/ScanRepository.kt`](android/app/src/main/java/com/medlenx/lab/data/repo/ScanRepository.kt): `ScanProgress`, `Uploading`, `Reading`, `Done`, `Failed`, `QueuedOffline`, `NeedsApiKey`, `ScanRepository`, `scan()`, `removeQueued()`, `updateQueuedSelection()`, `queueForLater()`, `resolveDoctorId()`, `saveVerified()`, `drainQueue()`, `recentMedicines()`, `medicinesFor()`, `recentPrescriptions()`, `searchCatalogue()`, `medexIndex()`
- [`data/repo/TeamMetrics.kt`](android/app/src/main/java/com/medlenx/lab/data/repo/TeamMetrics.kt): `TeamMetrics`, `doctorTiers()`, `stewardshipSummary()`, `Acc`, `scanPoints()`, `geoHeatmap()`, `rsmTrends()`, `targetProgress()`, `DoctorTier`, `DoctorTiering`, `StewardshipDoctor`, `StewardshipTotals`, `StewardshipSummary`, `Centroid`, `ScanPoint`, `TrendWeek`, `RsmTrends`, `BrandTarget`, `BrandProgress`, `TargetProgress`, `GeoRegion`
- [`data/repo/TripsPortfolio.kt`](android/app/src/main/java/com/medlenx/lab/data/repo/TripsPortfolio.kt): `TripsPortfolio`, `build()`, `Acc`, `TerritoryVolume`, `TripsMoleculeVolume`, `TripsTotals`, `TripsPortfolioResult`
- [`data/sync/AvatarCloudSync.kt`](android/app/src/main/java/com/medlenx/lab/data/sync/AvatarCloudSync.kt): `AvatarCloudSync`, `startSession()`, `markEdited()`, `schedule()`, `watch()`, `AvatarSyncWorker`, `doWork()`
- [`data/sync/ProfileCloudSync.kt`](android/app/src/main/java/com/medlenx/lab/data/sync/ProfileCloudSync.kt): `ProfileCloudSync`, `startSession()`, `prefs()`, `auth()`, `firestore()`, `requireUser()`, `save()`, `saveCompany()`, `enqueue()`, `watch()`, `parse()`, `text()`
- [`data/sync/RestoreHistoryWorker.kt`](android/app/src/main/java/com/medlenx/lab/data/sync/RestoreHistoryWorker.kt): `RestoreHistoryWorker`, `doWork()`, `parse()`, `HistoryRestoreScheduling`, `start()`
- [`data/sync/ScanCloudFields.kt`](android/app/src/main/java/com/medlenx/lab/data/sync/ScanCloudFields.kt): `ScanCloudFields`, `timestamp()`, `restoredDate()`, `medicineMap()`, `sameMedicines()`, `compatibleMetadata()`, `encodeIds()`, `parseIds()`, `ids()`
- [`data/sync/TeamFeedRepository.kt`](android/app/src/main/java/com/medlenx/lab/data/sync/TeamFeedRepository.kt): `TeamMedicine`, `TeamScan`, `TeamFeedState`, `TeamFeedRepository`, `observe()`, `listen()`, `publish()`, `watchAvatars()`
- [`data/sync/TrustedMembership.kt`](android/app/src/main/java/com/medlenx/lab/data/sync/TrustedMembership.kt): `TrustedMembership`, `firebase()`, `prefs()`, `cachedCompany()`, `refresh()`
- [`data/sync/UploadScanWorker.kt`](android/app/src/main/java/com/medlenx/lab/data/sync/UploadScanWorker.kt): `UploadScanWorker`, `doWork()`, `UploadScheduling`, `workTag()`, `periodicName()`, `start()`, `enqueue()`
- [`ui/auth/AuthGate.kt`](android/app/src/main/java/com/medlenx/lab/ui/auth/AuthGate.kt): `AuthGate()`, `AuthScreen()`
- [`ui/auth/CompanyOnboardingScreen.kt`](android/app/src/main/java/com/medlenx/lab/ui/auth/CompanyOnboardingScreen.kt): `CompanyOnboardingScreen()`
- [`ui/components/AuditFeedCard.kt`](android/app/src/main/java/com/medlenx/lab/ui/components/AuditFeedCard.kt): `AuditMedicineChip`, `AuditFeedCard()`
- [`ui/components/Buttons.kt`](android/app/src/main/java/com/medlenx/lab/ui/components/Buttons.kt): `ButtonTone`, `MlxButton()`, `MlxIconButton()`, `MlxSegmented()`, `MlxFilterChip()`
- [`ui/components/Cards.kt`](android/app/src/main/java/com/medlenx/lab/ui/components/Cards.kt): `MlxCard()`, `SectionHeader()`, `TintedIconTile()`, `KpiCard()`, `MiniKpiTile()`, `ProgressTrack()`, `DarkHero()`
- [`ui/components/Clipboard.kt`](android/app/src/main/java/com/medlenx/lab/ui/components/Clipboard.kt): `copyToClipboard()`
- [`ui/components/CompanyPill.kt`](android/app/src/main/java/com/medlenx/lab/ui/components/CompanyPill.kt): `CompanyVerification`, `CompanyBadge()`, `CompanyPill()`
- [`ui/components/EmptyStates.kt`](android/app/src/main/java/com/medlenx/lab/ui/components/EmptyStates.kt): `MlxEmptyState()`, `MlxErrorLine()`
- [`ui/components/FlowRowCompat.kt`](android/app/src/main/java/com/medlenx/lab/ui/components/FlowRowCompat.kt): `FlowRowCompat()`
- [`ui/components/FrostedIcon.kt`](android/app/src/main/java/com/medlenx/lab/ui/components/FrostedIcon.kt): `FrostedIcon()`
- [`ui/components/MedicineThumb.kt`](android/app/src/main/java/com/medlenx/lab/ui/components/MedicineThumb.kt): `MedicineThumb()`
- [`ui/components/OfficerAvatar.kt`](android/app/src/main/java/com/medlenx/lab/ui/components/OfficerAvatar.kt): `avatarResource()`, `OfficerAvatar()`, `AvatarImage()`, `AvatarFallback()`
- [`ui/components/Pills.kt`](android/app/src/main/java/com/medlenx/lab/ui/components/Pills.kt): `PillTone`, `StatusPill()`, `RegulatoryPill()`, `RegulatoryPillRow()`, `ConfidenceBadge()`
- [`ui/components/TextField.kt`](android/app/src/main/java/com/medlenx/lab/ui/components/TextField.kt): `MlxTextField()`
- [`ui/export/FileExport.kt`](android/app/src/main/java/com/medlenx/lab/ui/export/FileExport.kt): `DocumentSaver`, `savePdf()`, `saveCsv()`, `rememberDocumentSaver()`, `onPicked()`, `PendingDocument`, `exportFileName()`
- [`ui/navigation/Destination.kt`](android/app/src/main/java/com/medlenx/lab/ui/navigation/Destination.kt): `Destination`, `Scan`, `Analytics`, `Hub`, `Team`, `Settings`, `Help`, `RxAudit`, `fromRoute()`, `HubTab`
- [`ui/screens/PendingScreen.kt`](android/app/src/main/java/com/medlenx/lab/ui/screens/PendingScreen.kt): `PendingScreen()`
- [`ui/screens/analytics/AnalyticsCharts.kt`](android/app/src/main/java/com/medlenx/lab/ui/screens/analytics/AnalyticsCharts.kt): `seriesColor()`, `donutColors()`, `MostPrescribedBarChart()`, `ShareOfVoiceDonut()`, `SpecialtyGroupedBarChart()`, `label()`
- [`ui/screens/analytics/AnalyticsData.kt`](android/app/src/main/java/com/medlenx/lab/ui/screens/analytics/AnalyticsData.kt): `BarDatum`, `DonutDatum`, `Drilldown`, `Company`, `Bucket`, `Brand`, `SpecialtyComparison`, `DoctorLeaderRow`, `LiveScanRow`, `RecentRxRow`, `RxAuditDrawer`, `split`, `KpiDatum`, `ChamberFilter`, `matches()`
- [`ui/screens/analytics/AnalyticsMapping.kt`](android/app/src/main/java/com/medlenx/lab/ui/screens/analytics/AnalyticsMapping.kt): `pct()`, `toKpiData()`, `topBrandLabel()`, `toBarData()`, `toDonutData()`, `centreSoV()`, `toLeaderRows()`, `relativeAgo()`, `toLiveScanRows()`, `toRecentRxRows()`, `formatRxStamp()`
- [`ui/screens/analytics/AnalyticsScreen.kt`](android/app/src/main/java/com/medlenx/lab/ui/screens/analytics/AnalyticsScreen.kt): `AnalyticsScreen()`, `drilldownCaption()`, `DrilldownDialog()`, `CompanyDrilldownBody()`, `BrandDrilldownBody()`, `BucketDrilldownBody()`, `DrilldownColumn()`, `DrilldownRankRow()`, `EmptyDrilldown()`, `formatDrillDate()`, `Spacer1Cell()`, `FilterBar()`, `DoctorLeaderRowView()`, `MedicineBreakdownColumn()`, `LeaderSpecialtyFilter()`, `LeaderSortMenu()`, `RxHabitIndicator()`, `MarketShareStackedBar()`, `LiveRecentScans()`, `PingDot()`, `RecentPrescriptions()`
- [`ui/screens/analytics/AnalyticsViewModel.kt`](android/app/src/main/java/com/medlenx/lab/ui/screens/analytics/AnalyticsViewModel.kt): `AnalyticsViewModel`, `exportUid()`, `resetLeader()`, `updateLeaderSearch()`, `updateLeaderSpecialty()`, `updateLeaderSort()`, `toggleLeader()`, `updateLeaderPeriod()`, `selectDoctor()`, `updateChamberFilter()`, `pageLiveScans()`, `showBreakdown()`, `markBreakdownApproved()`, `dismissBreakdown()`, `failureAsNull()`, `loadDrawer()`, `pageLeaders()`, `updateFilters()`, `clearFilters()`, `filterSpan()`, `buildExportCsv()`, `closeDrilldown()`, `openCompanyDrilldown()`, `openOthersDrilldown()`, `openBrandDrilldown()`, `openDrilldown()`, `load()`, `loadLeaderPage()`, `startOfToday()`, `AnalyticsViewModelFactory`, `create()`, `LeaderPeriod`, `startMillis()`, `LeaderSort`
- [`ui/screens/analytics/FilterSheet.kt`](android/app/src/main/java/com/medlenx/lab/ui/screens/analytics/FilterSheet.kt): `FilterSheet()`, `DropdownField()`
- [`ui/screens/analytics/RxBreakdownSheet.kt`](android/app/src/main/java/com/medlenx/lab/ui/screens/analytics/RxBreakdownSheet.kt): `RxBreakdownSheet()`, `DrawerTopBar()`, `LoadingHeader()`, `DrawerHeader()`, `DrawerBody()`, `matchesQuery()`, `SearchAndFilters()`, `ItemCards()`, `ItemCard()`, `FieldLabel()`, `PortfolioCard()`, `PortfolioChip()`, `DuplicateNote()`, `EmptyItems()`, `scannedStamp()`
- [`ui/screens/help/HelpScreen.kt`](android/app/src/main/java/com/medlenx/lab/ui/screens/help/HelpScreen.kt): `TrainingQueueStats`, `of()`, `HelpViewModel`, `submit()`, `HelpViewModelFactory`, `create()`, `HelpScreen()`, `formatQueueStamp()`
- [`ui/screens/hub/HubScreen.kt`](android/app/src/main/java/com/medlenx/lab/ui/screens/hub/HubScreen.kt): `HubScreen()`, `HealthDaySheet()`, `LabelledChips()`, `DgdaTab()`, `DgdaListCard()`, `DgdaBannedRow()`, `DgdaAdjustedRow()`, `DgdaGazetteRow()`, `bdt()`, `NewsTab()`, `FeaturedNewsCard()`, `NewsRow()`, `TripsTab()`, `TripsMoleculeCard()`, `DrugIndexTab()`, `TopCompaniesHero()`, `ProductRow()`, `HealthDaysTab()`, `CalendarGrid()`, `CalendarCell()`, `JobsTab()`, `JobKpi()`, `IconLine()`, `monthName()`, `monthShort()`, `formatDay()`, `parseHex()`
- [`ui/screens/hub/HubViewModel.kt`](android/app/src/main/java/com/medlenx/lab/ui/screens/hub/HubViewModel.kt): `HubViewModel`, `openDayDetail()`, `closeDayDetail()`, `dgda()`, `calendar()`, `browse()`, `indexRows()`, `updateBrowseCategory()`, `searchIndex()`, `loadScannedRows()`, `refreshNews()`, `reload()`, `updateTripsDays()`, `portfolio()`, `board()`, `stepMonth()`, `updateJobCategory()`, `updateJobQuery()`, `updateJobLocation()`, `updateJobDepartment()`, `HubViewModelFactory`, `create()`
- [`ui/screens/rx/AuditActionControls.kt`](android/app/src/main/java/com/medlenx/lab/ui/screens/rx/AuditActionControls.kt): `DeleteScanButton()`, `DuplicateOverrideButton()`
- [`ui/screens/rx/DoctorPitchCard.kt`](android/app/src/main/java/com/medlenx/lab/ui/screens/rx/DoctorPitchCard.kt): `PitchTarget`, `DoctorPitchCard()`, `PitchHeader()`, `CompareTable()`, `TableCell()`, `strengthForm()`, `mrpLabel()`, `formatDifference()`, `NoteBox()`, `EvidenceBox()`, `PitchFooterButton()`
- [`ui/screens/rx/RxAuditParts.kt`](android/app/src/main/java/com/medlenx/lab/ui/screens/rx/RxAuditParts.kt): `stacked`, `ClassSlice`, `folded`, `classBreakdown()`, `classBreakdownOf()`, `ClinicalStrip()`, `MarketShareCard()`, `sharePct()`, `ExportButton()`, `PillButton()`
- [`ui/screens/rx/RxAuditScreen.kt`](android/app/src/main/java/com/medlenx/lab/ui/screens/rx/RxAuditScreen.kt): `RxAuditFilter`, `RxAuditScreen()`, `AuditHeader()`, `DuplicateFraudNote()`, `AuditItemCard()`
- [`ui/screens/scan/CameraViewfinder.kt`](android/app/src/main/java/com/medlenx/lab/ui/screens/scan/CameraViewfinder.kt): `CameraViewfinder()`, `onCaptureSuccess()`, `onError()`
- [`ui/screens/scan/GeoStrip.kt`](android/app/src/main/java/com/medlenx/lab/ui/screens/scan/GeoStrip.kt): `GeoStripState`, `GeoStrip()`, `GpsIconButton()`
- [`ui/screens/scan/LineSelector.kt`](android/app/src/main/java/com/medlenx/lab/ui/screens/scan/LineSelector.kt): `ImageTransform`, `inImage()`, `imagePoint()`, `LineSelector()`
- [`ui/screens/scan/MedicineCard.kt`](android/app/src/main/java/com/medlenx/lab/ui/screens/scan/MedicineCard.kt): `MedicineCardData`, `CardSkin`, `skinFor()`, `MedicineCard()`, `PackImage()`, `FactTile()`, `NoteStrip()`, `UnderlinedTextField()`, `AlternativesPicker()`, `SubstitutionCard()`, `ProductCell()`, `PitchActionButton()`
- [`ui/screens/scan/PrescriptionImageViewer.kt`](android/app/src/main/java/com/medlenx/lab/ui/screens/scan/PrescriptionImageViewer.kt): `ViewerTransform`, `zoomIn()`, `zoomOut()`, `rotateLeft()`, `rotateRight()`, `cycleContrast()`, `fit()`, `contrastFilter()`, `PrescriptionImageViewer()`, `ViewerHintStrip()`, `ViewerTools()`
- [`ui/screens/scan/ScanAnimation.kt`](android/app/src/main/java/com/medlenx/lab/ui/screens/scan/ScanAnimation.kt): `ScanLaserOverlay()`, `ScanSkeleton()`, `SkeletonRow()`, `SkeletonBar()`, `cyanLeftEdgeAndSweep()`
- [`ui/screens/scan/ScanHome.kt`](android/app/src/main/java/com/medlenx/lab/ui/screens/scan/ScanHome.kt): `ScanHome()`, `ClaimAuditsCard()`, `CaptureHero()`, `DailyTargetCard()`, `MonthlyBrandTargets()`, `TopDoctorsSummary()`, `RecentCaptures()`, `HubFeed()`, `HubPreviewCard()`
- [`ui/screens/scan/ScanScreen.kt`](android/app/src/main/java/com/medlenx/lab/ui/screens/scan/ScanScreen.kt): `ScanScreen()`, `OfflineQueueBanner()`, `ViewerCard()`, `ActionRow()`, `VerificationArea()`
- [`ui/screens/scan/ScanViewModel.kt`](android/app/src/main/java/com/medlenx/lab/ui/screens/scan/ScanViewModel.kt): `DashboardBrandKpi`, `ScanPhase`, `ScanUiState`, `ScanViewModel`, `prepareClaim()`, `cancelClaim()`, `confirmClaim()`, `districtNames()`, `upazilasFor()`, `territoriesFor()`, `specialties()`, `onImagePicked()`, `onCameraCaptured()`, `decodeOriented()`, `locateLines()`, `toggleDetectedLine()`, `addManualLine()`, `retryLocator()`, `analyze()`, `parkForReplay()`, `syncQueuedScans()`, `replayQueued()`, `enterVerification()`, `onDoctorChange()`, `onDistrictChange()`, `gotoMedicines()`, `gotoGps()`, `backToDoctor()`, `backToMedicines()`, `mergeEdits()`, `onBrandChange()`, `selectMedicine()`, `pickSuggestion()`, `applyAlternative()`, `verifyAgainstMedex()`, `reportMisId()`, `markConversionWon()`, `showToast()`, `refreshSuggestions()`, `refreshSubstitution()`, `onDosageChange()`, `updateCard()`, `save()`, `findDuplicate()`, `clearDuplicateAlert()`, `scanAnother()`, `pinGps()`, `onGeoChange()`, `applyFix()`, `reverify()`, `onCleared()`, `clear()`, `ScanViewModelFactory`, `create()`
- [`ui/screens/scan/Verification.kt`](android/app/src/main/java/com/medlenx/lab/ui/screens/scan/Verification.kt): `PrescriptionSource`, `DoctorVerification`, `GpsVerification`, `SavedReceipt`, `FieldLabel()`, `FieldHint()`, `ConfidenceField()`, `MlxSelect()`, `VerifyThumbnail()`, `VerifyDoctorSection()`, `FullscreenPrescriptionViewer()`, `VerifyMedicinesSection()`, `VerifyGpsSection()`, `GpsField()`, `ScanSavedSection()`, `toCardData()`
- [`ui/screens/search/SearchOverlay.kt`](android/app/src/main/java/com/medlenx/lab/ui/screens/search/SearchOverlay.kt): `SearchViewModel`, `search()`, `SearchViewModelFactory`, `create()`, `SearchOverlay()`
- [`ui/screens/settings/SettingsScreen.kt`](android/app/src/main/java/com/medlenx/lab/ui/screens/settings/SettingsScreen.kt): `SettingsScreen()`, `FieldLabel()`, `SettingsDropdown()`
- [`ui/screens/settings/SettingsViewModel.kt`](android/app/src/main/java/com/medlenx/lab/ui/screens/settings/SettingsViewModel.kt): `SettingsViewModel`, `selectAvatar()`, `selectBuiltInAvatar()`, `changed()`, `updateDailyTarget()`, `preparePurge()`, `cancelPurge()`, `confirmPurge()`, `signOut()`, `updateEmployeeId()`, `updateFullName()`, `updateDivision()`, `updateTerritory()`, `updatePortfolio()`, `onCompanyQuery()`, `selectCompany()`, `updateRole()`, `addBrand()`, `updateBrand()`, `removeBrand()`, `load()`, `save()`, `TargetDraft`, `SettingsViewModelFactory`, `create()`
- [`ui/screens/team/BaseMapLayer.kt`](android/app/src/main/java/com/medlenx/lab/ui/screens/team/BaseMapLayer.kt): `project()`, `BaseMapLayer()`
- [`ui/screens/team/MapboxHeatmap.kt`](android/app/src/main/java/com/medlenx/lab/ui/screens/team/MapboxHeatmap.kt): `MapboxHeatmapLayer()`
- [`ui/screens/team/TeamFeed.kt`](android/app/src/main/java/com/medlenx/lab/ui/screens/team/TeamFeed.kt): `TeamFeed()`
- [`ui/screens/team/TeamScreen.kt`](android/app/src/main/java/com/medlenx/lab/ui/screens/team/TeamScreen.kt): `TeamScreen()`, `TeamHero()`, `HeroKpi()`, `TeamTiersSection()`, `TierCountTile()`, `TierDoctorCard()`, `TeamStewardshipSection()`, `shareTone()`, `StewardshipChamberCard()`
- [`ui/screens/team/TeamSections.kt`](android/app/src/main/java/com/medlenx/lab/ui/screens/team/TeamSections.kt): `TeamMapSection()`, `DataBubbleLayer()`, `MapBubbleSpec`, `LegendDot()`, `sovColor()`, `densityColor()`, `TeamLeaderboardSection()`, `Sparkline()`, `TeamTargetsSection()`, `TeamOffTerritorySection()`, `formatStamp()`
- [`ui/screens/team/TeamViewModel.kt`](android/app/src/main/java/com/medlenx/lab/ui/screens/team/TeamViewModel.kt): `TeamViewModel`, `updateMapMode()`, `removeDoctorTarget()`, `updateTierFilter()`, `load()`, `TeamViewModelFactory`, `create()`, `MapMode`
- [`ui/shell/BrandLockup.kt`](android/app/src/main/java/com/medlenx/lab/ui/shell/BrandLockup.kt): `BrandTile()`, `BrandLockup()`
- [`ui/shell/MedLenXShell.kt`](android/app/src/main/java/com/medlenx/lab/ui/shell/MedLenXShell.kt): `MedLenXShell()`, `runAuditAction()`, `shareSummary()`, `openUrl()`, `rxLabel()`
- [`ui/shell/MlxBottomNav.kt`](android/app/src/main/java/com/medlenx/lab/ui/shell/MlxBottomNav.kt): `MlxBottomNav()`
- [`ui/shell/MlxTopBar.kt`](android/app/src/main/java/com/medlenx/lab/ui/shell/MlxTopBar.kt): `TopBarState`, `MlxTopBar()`, `BrandHeader()`, `HeaderActions()`, `CloudStatus()`
- [`ui/theme/Color.kt`](android/app/src/main/java/com/medlenx/lab/ui/theme/Color.kt): `Mlx`, `sovColor()`, `clusterColor()`, `companyColor()`, `hslToColor()`
- [`ui/theme/Dimens.kt`](android/app/src/main/java/com/medlenx/lab/ui/theme/Dimens.kt): `MlxD`
- [`ui/theme/Shape.kt`](android/app/src/main/java/com/medlenx/lab/ui/theme/Shape.kt): `MlxShape`
- [`ui/theme/Theme.kt`](android/app/src/main/java/com/medlenx/lab/ui/theme/Theme.kt): `MedLenXTheme()`
- [`ui/theme/Type.kt`](android/app/src/main/java/com/medlenx/lab/ui/theme/Type.kt): `MlxType`
- [`util/Bengali.kt`](android/app/src/main/java/com/medlenx/lab/util/Bengali.kt): `Bengali`, `normalizeDosage()`
