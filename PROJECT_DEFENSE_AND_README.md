# MedLenX Lab — Android project defense and code guide

> Code audit of the local Android sources at the time of writing. Paths below are relative to the repository root. This is an implementation guide, not evidence of a successful build, deployed security rules, clinical validation, or a live Gemini request. The source remains authoritative. The file-by-file index and exact import lists at the end complement the architectural analysis; private helpers and generated Android code are not independent modules.

## 1. Architecture and end-to-end data flow

### Architectural style and boundaries

[**MVVM** (Model–View–ViewModel) separates displayed UI from a state holder and the underlying data/operations. **Offline-first** means local work remains usable without constant network access, not that every feature works offline. **Clean Architecture** describes separating business rules from framework details; an app can borrow the idea without enforcing every boundary.]

This is a **single Android application module**, organized by packages rather than separate Gradle feature modules. It is MVVM-like: Compose screens render state and send events to `AndroidViewModel`/ViewModel classes; repositories, DAOs and data services perform work. This is not strict Clean Architecture: ViewModels access `AppGraph`, DAOs, Firebase authentication and Android APIs directly; several repository objects also contain domain calculations. The manual composition root `data/config/AppGraph.kt` constructs shared dependencies, not Hilt. `MedLenXApp.onCreate` constructs the graph, starts catalogue import on an application `SupervisorJob + Dispatchers.IO` scope, quarantines legacy images and starts connectivity observation. `MainActivity` enables edge-to-edge and composes `MedLenXTheme` → `AuthGate` → `MedLenXShell`.

Offline-first describes **durable local capture and verified results**, not offline AI or universal cloud replication. Room plus private files are authoritative for local work; Gemini, trusted membership checks and Firestore need network. Firebase Authentication identifies the user; company membership is server-controlled, not established just by selecting a company in Settings. `AuthGate` reacts to auth state, binds account-specific state, schedules upload/restore and gates the shell behind company onboarding. New-device onboarding must check server state online when no local profile exists. Sign-out is not a data purge. Settings also provides a separate, explicitly confirmed trapped-data purge. Do not confuse a local profile/company preference with trusted organization membership.

### Session sequence, with branching

[An Android **Application** is the process-level entry point; an **Activity** hosts a user-facing window. A **UID** is the signed-in user's identifier. A **coroutine** is a Kotlin mechanism for asynchronous work that can be cancelled without blocking the UI.]

1. **Launch and identity.** [Firebase Authentication signs in a user; an auth listener reports account changes.] Android launches `MedLenXApp` and `MainActivity`. Firebase initialization is handled via `TrustedMembership.firebase`; `AuthGate` uses an auth listener. Per-user UI and synchronization are tied to the current UID. The shell routes Scan, Analytics, Hub and Team, plus Settings, Help and audit surfaces.
2. **Capture/import.** [CameraX is Android’s camera library; EXIF is image metadata that can record orientation.] `CameraViewfinder` uses CameraX; runtime camera permission is requested by the scan screen. Image picking is an alternative. `ScanViewModel.onCameraCaptured` saves an oriented full-resolution private JPEG (quality 95); `onImagePicked` copies the selected stream to a user-scoped capture file and `decodeOriented` accounts for EXIF rotation/mirroring. UID and phase checks protect against stale asynchronous results. File access and location APIs are configured in the manifest; hardware features are optional.
3. **Local geometry.** [ML Kit text recognition runs on the device; a bounding box is a rectangle around detected text.] `locateLines` invokes `DocumentLocator.locate`: ML Kit's Latin recognizer processes `InputImage.fromBitmap(bitmap, 0)`, collects text-block line bounding boxes and clips them to the bitmap. Recognized text is **not** the transcription. Initially detected boxes are selected; a user can deselect, retry detection or draw a clipped manual rectangle. Latin OCR can miss handwriting or Bengali, and a user must inspect the selection.
4. **Request.** [Gemini is a remote AI model service; a crop is a smaller rectangular image cut from the original.] `analyze()` requires Ready state, current UID, no ongoing locator/request, and 1–100 selected rectangles, preserving detected order. When offline it parks the capture and selections; otherwise it calls `ScanRepository.scan` → `MedLenXVlClient.scanPrescription`. The client is the deprecated `com.google.ai.client.generativeai:generativeai:0.9.0`, initialized with a locally configured API key, `gemini-3.8-flash`, JSON response MIME/schema and `RequestOptions(timeout = 240_000L)`. The first payload image is the full prescription for doctor/date; remaining images are ordered selected crops for medicine names. The prompt asks to mask patient identifiers, but the photograph itself may expose them to the provider.
5. **Image preparation.** [A bitmap is an in-memory image; JPEG is a compressed image format. Compression can lose detail.] `ImagePrep.bitmapUploadJpeg` scales only the full image down to a 1600px longest edge, never upscales, and JPEG-encodes each full image/crop at quality 80. The client decodes the bytes into payload bitmaps for the older SDK's `content { image(...) }` builder and releases them in `finally`; the SDK re-encodes bitmap images, creating another lossy step. Original saved files and rectangle coordinates are not replaced by these upload copies. A separate older `toUploadJpeg` helper uses quality 85; do not conflate it with the current scan request.
6. **Parsing and review.** [JSON is a structured text format; parsing converts and validates that text as app data.] A non-STOP candidate or absent text produces a failure. `GeminiScanParser.clean` removes outer Markdown fences without stripping backticks inside JSON. `parse` validates required doctor keys, date string, a true masked flag and one medicine per crop with finite confidence in [0,1]. It creates `VlScanResult`; patient note is replaced by a fixed masked note. Parse/API failures reach the UI; API exceptions are logged under `GeminiScan`. `enterVerification` enriches medicines using the cached MedEx matcher and regulatory data; doctor, medicine and GPS panels are editable. These are AI suggestions, not clinical diagnoses; human verification is essential.
7. **Durable local save.** [A UUID is a randomly generated identifier; a perceptual hash summarizes visual similarity rather than providing a cryptographic identity.] `save()` merges the user's edits into the result, re-enriches with selected MedEx IDs, computes a perceptual image hash for duplicate detection when possible and calls `saveVerified`. The repository rechecks the signed-in UID and uses a Room transaction to resolve/update the doctor, create a UUID scan with the company snapshot taken at verified save and `PENDING` status, assign `RX-<row id>` and insert ordered medicine records. No image upload is part of this transaction. Duplicate override affects metric eligibility; local audit deletion records a restore tombstone and retains the photo and cloud copy.
8. **Background upload and restore.** [A worker is a scheduled unit of background work; an acknowledgment confirms that an awaited write completed.] A save requests one-time WorkManager upload after commit; periodic per-UID work also provides recovery. The worker requires a connected network, validates current UID and server membership, and uses the **stored company snapshot**, never silently rerouting a scan to another tenant. A fixed UUID becomes the Firestore document ID under `companies/{company}/team_scans/{scanId}`. It reconciles an existing document after a lost acknowledgment, otherwise uses `set(...).await()`, and marks `UPLOADED` only after acknowledgment/reconciliation. Permission denied marks `FAILED_REJECTED`; transient exceptions retry. Empty-company records require explicit claim. Cloud data contains structured results, not prescription photos.
9. **History and team.** [A Flow is an asynchronous stream of values; a listener receives database updates until it is removed.] On authentication, `RestoreHistoryWorker` queries server-confirmed, current-member records authored by the UID in pages of 100; it rechecks account and membership, skips tombstones and existing local scan IDs and inserts restored records transactionally. It cannot restore photos that were never uploaded, or all original local detail. `TeamFeedRepository` uses `callbackFlow`, a descending timestamp listener limited to 30 server-confirmed scans and a company-scoped avatar-ID listener, cleaning up listeners on close.

**Two queues:** `queued_scans` parks images awaiting a future AI review and does not automatically submit them. Verified `PENDING` prescriptions are separately uploaded by WorkManager. Reopening a parked row restores saved rectangles when valid, otherwise runs detection again; it remains queued until verified save succeeds. An unreadable first queued image can block later ones.

## 2. Frontend — Jetpack Compose

[**Jetpack Compose** builds Android UI from Kotlin functions called composables. **State** is data that can change; Compose recomposes affected UI when observed state changes. **State hoisting** means keeping state in a parent/state holder and passing values plus event callbacks to children. A **ViewModel** holds screen state and logic across ordinary configuration changes. A **Modifier** specifies presentation or interaction such as size, padding, clipping or gestures. `remember` retains a local value across recompositions, not necessarily process death.]

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

[**SQLite** is an embedded relational database stored on the device. **Room** is an Android library that maps Kotlin entities and DAO methods to SQLite tables and queries. An **entity** describes a table, a **DAO** groups database operations, and a **migration** changes an existing schema while preserving data. A **transaction** groups changes so they commit together or roll back.]

`MedLenXDatabase` is Room version **17**, database `medlenx.db`, with migrations 1→17 registered and `exportSchema = false`. It deliberately does not enable destructive migration fallback. Entities include `medex_products`, `doctors`, `prescriptions`, `scanned_medicines`, `queued_scans`, officer profiles, brand/doctor targets, visits, error reports, manufacturer aliases and local deleted-scan tombstones. Foreign-key/index details are defined in `Entities.kt`; use `Daos.kt` for exact SQL. User-scoped query predicates and auth checks are important: multiple users' retained local data coexist on the device. A nullable unique `(user_id, restore_scan_id)` index supports idempotent restore without requiring legacy rows to have UUIDs.

[**FTS4** is SQLite’s full-text search index: it searches tokenized text rather than checking every product using an arbitrary substring comparison. A **tokenizer** decides how text is split into searchable terms; Porter also applies English-oriented stemming.]

**FTS4 implementation:** `MedexFtsEntity` is a Room `@Fts4` external-content entity backed by `medex_products`; it indexes `brand_name`, `generic`, `company` and `type` with the Porter tokenizer. Room creates the virtual table and content-sync triggers on a fresh database. Migration 16→17 creates the equivalent FTS4 table and issues the `rebuild` command to index existing catalogue rows; Room manages the sync triggers. The FTS rowid corresponds to the underlying SQLite rowid of `medex_products`, not its string `id`. `MedexDao.searchFts` joins that rowid to return full entities and binds a `MATCH` expression. Catalogue writes use `@Upsert` rather than `REPLACE`, so updates fire external-content update triggers instead of risking stale tokens from REPLACE delete-trigger semantics.

`MedexDao.searchMedex` is a Kotlin extension that turns letter/mark/digit runs into quoted prefix terms and combines them with implicit AND. Empty or punctuation-only input returns no rows; quoted user tokens cannot inject FTS query operators. Prefix matching is **not** the old arbitrary substring search: for example `nap` matches the token `Napa`, but `apa` does not; multiple terms can match across indexed columns. Porter stemming and SQLite tokenization may affect results, particularly non-Latin and punctuation-heavy input. No ranking or ordering is promised by this query; the limit applies to the returned hits. `byBrandExact` remains a separate equality query, and `ScanRepository.medexIndex()` still uses a distinct cached in-memory matcher for medicine enrichment. FTS4 is not FTS5, and neither sub-millisecond latency nor search parity is guaranteed without representative device benchmarks.

`AssetCatalogue.ensureImported` serializes import with a mutex, checks existing row count, reads bundled `assets/data/medex_full.json` (documented as 25,105 rows, ~16 MB) and writes 2,000-row batches. It exposes import state/count; because the existing-row shortcut checks only `count > 0`, an interrupted partial import is a limitation to investigate, not proof of completeness. Other JSON assets support location, regulatory, news and Hub features. Manufacturer aliases normalize reporting while retaining original values where implemented.

### Synchronization and security

[**WorkManager** schedules deferrable Android background work subject to constraints; it is not an always-running service. **Firestore** is the cloud document database. An **outbox** is a local list of changes waiting to be sent. A **tombstone** is a durable marker that prevents an item from being restored after local deletion.]

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

[A **repository** provides a boundary for accessing data and operations; this code still exposes some database and Firebase details to ViewModels. An **API** is a way for software to request services from another system. A **schema** specifies the expected shape and types of a response.]

- `ScanRepository.scan(bitmap, selectedLines)`: checks API key, delegates to the client, translates `VlOutcome` to UI progress. `queueForLater`, `updateQueuedSelection`, `removeQueued` and `drainQueue` use UID-scoped queue storage. `drainQueue` offers at most the oldest row for review and retains it until the handler says it can be removed.
- `ScanRepository.saveVerified(...)`: validates current UID and snapshot, transactionally inserts prescription/medicine records and schedules upload after commit. `resolveDoctorId` uses normalized per-user doctor identity and merges nonblank verified details. `medexIndex` avoids caching an empty index; `searchCatalogue` calls the FTS4 token-prefix search extension. `MedicineEnricher.enrich` and `MedicineMatcher` handle matching, manufacturer, image and compliance context rather than trusting a model-invented manufacturer.
- `DocumentLocator.locate`: returns geometry only; `close` releases recognizer. `ImagePrep.uploadDimensions` and `bitmapUploadJpeg` handle upload sizing and JPEG copies. `MedLenXVlClient.scanPrescription`: creates structured content and schema, observes cancellation, catches/logs API errors, checks finish reason and releases temporary bitmaps. `GeminiScanParser.clean/parse` handle fences and strict shape validation.
- `ScanCloudFields` converts local medicine/date metadata to cloud form and checks compatibility. `TeamFeedRepository.observe/listen` binds tenant-scoped listeners and filters cached/pending data. `AnalyticsMetrics`, `TeamMetrics`, `DoctorIntelligence`, `RxAudit` and `Compliance` contain domain calculations consumed by screens.

### Build, resources, configuration and limitations

`android/app/build.gradle.kts` sets namespace/application ID `com.medlenx.lab`, min SDK 26, compile SDK 37, target SDK 36, Java/Kotlin target 17, Compose/Room KSP, CameraX, ML Kit, Firebase, WorkManager and legacy Gemini. Firebase BOM 33.7.0 and enforced gRPC BOM 1.62.2 attempt to align Firestore runtime dependencies; the earlier gRPC fix is not device-verified. Packaging excludes duplicate `META-INF/INDEX.LIST` and `META-INF/DEPENDENCIES`. Debug is unminified; release currently uses debug signing and is not release-ready. `checkMedLenXAssets` guards required assets before build; refresh is explicitly opt-in. `AndroidManifest.xml` declares internet, network, camera and coarse/fine location, FileProvider and backup rules; see `res/xml` for actual backup/file-provider policy. `res/values`, `drawable`, and launcher mipmaps provide Android resources. Dataset license/provenance and accuracy should be reviewed before redistribution.

`GEMINI_API_KEY`, Firebase values and optional `MAPBOX_ACCESS_TOKEN` come from ignored `android/local.properties` or environment and become generated build configuration; the checked-in `local.properties.example` still mentions the obsolete OpenRouter key, so follow the actual build script. **APK-contained keys are extractable**. For public deployment use an appropriate secure backend or supported Firebase AI architecture and review provider data policies. Raw exception messages/Logcat can contain keys or sensitive data: redact before sharing. The full image can contain patient identifiers despite the output masking. The configured `gemini-3.8-flash` identifier is a source string, not evidence that this model exists or the user's key can access it; the UI display name still says “Gemini 2.5 Flash”. A live model list/request is needed to confirm availability. `httpClient` is passed to `MedLenXVlClient` but not used for the legacy SDK request; `RequestOptions` governs its configured timeout.

## 5. Supervisor Q&A

[These answers describe the checked-in implementation, not proof of runtime correctness or production readiness.]

1. **Why CameraX + ML Kit before Gemini?** CameraX integrates capture with Android lifecycle; on-device ML Kit gives user-selectable line geometry without treating OCR text as the transcription. Sending only selected line crops plus one context image constrains the model's medicine reading. This does not guarantee OCR catches handwriting; manual rectangles are required as fallback.
2. **How is the 25K drug index searched, and is it FTS5?** It uses Room FTS4 with an external-content table, Porter tokenizer and indexed token-prefix `MATCH`; the full records are joined on SQLite rowid. The separate enrichment matcher is in-memory. Prefix search differs from substring search, and actual latency must be measured on target devices.
3. **What happens on a Gemini timeout?** The legacy model is configured with `RequestOptions(timeout = 240_000L)`. Exceptions are logged and surfaced; if connectivity is offline when failure is processed, the capture is parked for explicit later review. No automatic paid fallback is configured. Timeout is not proof that retries or endpoint access work.
4. **How do you prevent duplicate cloud writes after a crash or lost ACK?** Each saved scan gets one UUID used as document ID. On retry the worker fetches the server document and checks identity/content compatibility before treating it as acknowledged; it only marks uploaded after `await()` or reconciliation. This is idempotent under those checks, not a distributed exactly-once guarantee.
5. **How is tenant isolation enforced?** The verified save stores the then-cached company snapshot; worker refreshes trusted membership and rejects mismatches instead of rerouting. Firestore rules are the real authorization boundary and must be deployed and tested. A local company preference is not proof of membership.
6. **Can a new device recover everything?** No. Restore downloads only the current user's server-confirmed structured scans from the current trusted company; local photos are not uploaded and unuploaded scans cannot be restored from Firestore. Some detailed local fields and duplicate links are not reconstructed.
7. **Why require human review and schema validation?** The model can hallucinate or return malformed/incomplete output. Parser checks metadata, mask flag, ordered line count and confidence range, and users correct the review cards before `saveVerified`. This limits malformed data; it does not prove medical accuracy or prevent identifying information being visible in the transmitted image.

## 6. Architectural weaknesses and technical debt roadmap

The following are **proposed future designs, not implemented features or guarantees**. They need product approval, a security review, migration planning, and tests. The existing app must not be described as already having these protections.

### 6.1 Duplicate cloud writes and idempotency

[Idempotency means retrying the same operation has the same intended effect as performing it once. A race condition happens when two operations overlap and the result depends on timing.]

**Current behavior:** `saveVerified` assigns each newly saved local prescription a client-generated random UUID. `UploadScanWorker` uses that stable UUID as the Firestore document ID, reads the server document, compares its contents if present, otherwise writes with `set(...).await()` and marks the local row uploaded only afterward. This helps reconcile a retry after a lost acknowledgment. It does **not** deduplicate two independently saved copies: each save can receive a different UUID, and overlapping workers can both observe a missing document before a write. With the same UUID they target the same document, not necessarily two document IDs; authorization rules and the outcome of concurrent writes still matter. Local perceptual-image duplicate detection and approval are separate, not a global cloud idempotency guarantee.

**Proposed direction:** use **Content-Addressable IDs**, such as a SHA-256 cryptographic hash of a canonicalized payload, combined with a **Last-Write-Wins (LWW) timestamp** for an explicitly defined conflict policy. [A content-addressable ID is derived from content; SHA-256 produces a fixed-size digest. LWW chooses a winning update based on a timestamp.] This is a design proposal, not code currently present. Before adoption, define canonical fields and ordering, tenant/owner scoping, keying or privacy protection against guessing sensitive values, how legitimate identical prescriptions remain distinct, and how edits change an ID. A hash alone cannot stop concurrent check-then-write races: atomic server-side create/transaction and tested security rules are still necessary. LWW can discard concurrent edits and device clocks can be wrong; a trusted server timestamp, tie-breaking, versioning and audit requirements need design. Existing UUID-addressed records and create/immutability Firestore rules would require a deliberate migration and rules changes; never silently change tenant ownership.

### 6.2 Local disaster recovery

[Disaster recovery means restoring usable data after loss of the original device or its storage. Encryption protects data from unauthorized reading, but it is not a backup.]

**Current behavior:** unuploaded queued captures and unsynced local prescriptions can be lost if a device is wiped, lost, or the app's private data is removed. Current Firestore restore covers only eligible previously uploaded structured results, not local photos or never-uploaded work. The app's backup rules exclude its prescription image directories and `medlenx.db` from the declared backup/transfer modes.

**Proposed direction:** an **Encrypted Local Vault Backup** using AndroidX Security (`EncryptedSharedPreferences` / `EncryptedFile`) or SQLCipher, subject to compatibility and security review. These are candidate building blocks, not current integrations; encrypted preferences are not a bulk photo/database backup system, and SQLCipher protects a database rather than automatically backing up image files. A vault located only on the same wiped device is also lost. Actual disaster recovery needs an explicitly user-authorized, independently retained backup of both records and necessary images, integrity/version checks, encryption and recoverable key management, and a tested restore process. Review current library support/deprecation status before choosing APIs; avoid promising recovery without the key and an independent copy. Do not silently upload historical local-only scans, erase originals, or weaken tenant isolation.

### 6.3 PII leaks in image upload and diagnostics

[PII is personally identifiable information: details that can identify a person. Redaction removes or obscures sensitive content before it is shared. Named Entity Recognition (NER) identifies categories such as person names in text.]

**Current behavior:** the full prescription and selected line crops are sent to Gemini. The prompt requests masked patient output and the parser replaces the patient note, but these measures do not remove identifiers visible in the original image, and doctor information is deliberately extracted. Raw error messages and Logcat stack traces may also include sensitive data or keys. The API key is embedded in the APK and extractable.

**Proposed direction:** an **On-Device Redaction Layer** using ML Kit-assisted text recognition plus an on-device NER solution to identify sensitive text and blur its image regions before upload. This is not implemented; the existing `DocumentLocator` returns text-line rectangles for selection, not PII classification or redaction. The current dependencies contain no ready-made ML Kit NER pipeline. A suitable on-device NER model/integration must be selected and validated; do not assume the text recognizer alone recognizes names or that ML Kit exposes an off-the-shelf NER API. Handwriting, Bengali, missed boxes, rotated text and false negatives make automatic redaction imperfect. Determine what doctor/date details the workflow legitimately requires and obtain consent, test on representative data, consider manual confirmation and fail-closed behavior, and avoid sending unredacted fallback images. Redact both the full image and every crop, and test that logs and exports do not reintroduce sensitive information.

### 6.4 Other constraints to explain honestly

- **Endpoint and security:** `gemini-3.8-flash` is the configured string, not a verified available model; the deprecated direct-client SDK and APK-embedded key are prototype limitations. A supported Firebase AI or secure backend design is a future migration, not an existing proxy.
- **Database migration and performance:** Room v17 FTS4 has a host SQLite check but the Room-generated migration/triggers and device behavior have not been verified here. Prefix search changes substring semantics, and no device latency benchmark substantiates a sub-millisecond claim.
- **Partial catalogue import:** the import considers any positive row count sufficient on later startup; an interrupted batch import needs a completeness/version strategy before assuming all 25K entries are present.
- **Cross-device consistency:** restore intentionally reconstructs a reduced representation without photos and currently sets duplicate linkage to null. Local tombstones do not globally delete cloud copies; in-flight upload and account-switch races require device and rules testing.


## 7. Validation and known audit caveats

No Android compilation, Kotlin tests, device execution, deployed-rule test, dependency resolution or live Gemini call was performed as part of this documentation audit. The UI model label and parts of older docs/examples are stale. The FTS migration and Room-generated trigger setup require actual compilation and device migration tests; host SQLite checks do not establish Room compatibility. Treat historical comments and documentation as context, not proof of runtime behavior. Validate migrations on real historical databases, account-switch/cancellation races, duplicate behavior across devices, Gemini model access and Firestore rules before deployment. Never uninstall or clear app data as a diagnostic for local-only records.

### 7.1 Scan state, ownership and image lifetime

`ScanUiState` is an immutable data-class snapshot held in Compose-observable state; handlers replace it with `copy`. Its fields include the current phase, image file/bitmap, detected and selected rectangles, editable doctor and medicine cards, raw parsed result, enriched data, GPS state, queue ID and error. The enum also contains `Done`, but the normal successful AI path goes to the doctor verification step; an enum value alone is not proof of a transition.

| Event | Guard/side effect | Next observable behavior |
|---|---|---|
| Pick/capture | Current UID is checked; copy or camera JPEG is written in the UID-scoped directory; an oriented bitmap is loaded. | `Ready`; `locateLines` starts, with auto-selected boxes. |
| Detection fails/empty | Cancellation propagates; error and empty results are reported without manufacturing a box. | Retry or draw a bounded manual line. |
| Analyze offline | Image path and selected coordinates are recorded in a UID-scoped queue row (`lines-v1:` JSON). | Capture remains in `Ready` with parked state; this is not a verified prescription. |
| Analyze online | Requires current user, 1–100 selected lines and no active scan/detection. | `Scanning`, then verification on valid output, or a visible failure/needs-key state. |
| Verify/save | User can modify doctor, medicines and geo. Save re-enriches edited data and selected IDs; a database transaction commits the prescription and its medicine rows. | Receipt (`Saved`); queued row is removed only after successful save. A failure keeps review state for retry. |
| Open parked item | Re-decodes one queued image; restores valid saved rectangles or runs detection again. | `Ready` for explicit confirmation; old rows without line payload are never blindly submitted. |

`UserCaptureFiles.key` hashes a UID with SHA-256 to form directory names. New stored captures use `filesDir/rx/<hash>`; temporary paths use `cacheDir/rx/<hash>`. `ownsStored` checks canonical paths, not string prefixes. `LegacyImageQuarantine` handles old paths; do not infer that every file is a backup. Current connectivity detection observes `NET_CAPABILITY_INTERNET`: this indicates network capability, **not** confirmed Gemini or Firestore reachability. It can report online while a service is unavailable; online API failures are surfaced rather than automatically parked just because they are timeouts.

### 7.2 Persistence contract and migration landmarks

| Data | Identifiers, contents and lifecycle |
|---|---|
| `medex_products` | String product ID, brand/generic/company/type plus variant and image fields. Catalogue data are bundled/imported; FTS is a search index, not the product of record. |
| `medex_fts` | FTS4 external-content columns and a rowid corresponding to the product table's SQLite rowid. Room-managed triggers synchronize content changes; migration rebuild populates existing rows. |
| `doctors` | User-scoped identity key, display name, specialty and geography. `doctor_id` on a prescription is nullable and is not a Room foreign key; legacy data may lack attribution. |
| `prescriptions` | UID, local numeric ID/Rx number, stable cloud UUID, company snapshot, status, date, image path/hash, verified doctor and location data. `restore_scan_id` is nullable and uniquely indexed with UID for restored records. |
| `scanned_medicines` | Prescription ID and line number, original/edited fields, nullable MedEx ID, normalized and raw manufacturer, match/confidence, image and audit flags. |
| `queued_scans` | UID, local image path, selected-line payload, created time and attempts; distinct from the verified-scan outbox. |
| `officer_profile`, targets, visits, reports | Local per-user profile/targets and related audit-support data. Visits have a unique target/prescription index. |
| `manufacturer_aliases`, `deleted_scan_tombstones` | Alias normalization retains historical raw company name; a tombstone prevents re-importing a locally deleted cloud scan on that device. |

The registered migration chain is in `Migrations.kt` and should not be edited retroactively. Milestones: 1→3 adds doctor linkage and territory; 3→8 adds legacy ownership defaults; 8→11 adds outbox identity/status, company snapshot and restore indexes; 11→12 preserves raw manufacturer and seeds aliases; 12→13 adds duplicate approvals and local tombstones; 13→16 adds MedEx IDs, prescription date and metadata-backfill state; 16→17 creates and rebuilds FTS4. New captures use the trusted cached company **when `saveVerified` creates the verified record**; it is not independently snapshotted at the instant the camera shutter fires. Once stored, the worker respects that value instead of routing a pending row to a newly selected company. Historical blank/unassigned values need a deliberate claim, not a silent reassignment.

`RX_FILTER_SQL` applies UID and duplicate-approval constraints alongside district, territory, specialty, MR and source filters; the time window is handled by the calling queries. `RX_FILTER_SQL_SNAPSHOT` is a different fragment used in export and does not itself include the duplicate predicate. Inspect the particular query before claiming the two are identical. The default filter window is 30 days, while the user's daily target is separately stored with default 10.

### 7.3 FTS4 search: concrete example and what to test

The DAO expression builder extracts Unicode letter/mark/number runs and builds quoted FTS terms with `*` **inside** each quote. For input `Napa Extra`, the bound expression is `"Napa*" "Extra*"`; implicit AND means both terms must match somewhere in the indexed document. SQLite's Porter tokenizer still controls how text is tokenized and stemmed, so the regex is not a replacement for tokenizer behavior. Punctuation-only input gives an empty result without executing MATCH. The bound parameter prevents raw FTS operators from becoming the query language, but punctuation, diacritics, script behavior, token prefixes, cross-column matches and order should be verified with real catalogue queries. In particular, `apa` is not the same as the old `%apa%` substring query.

On a version-16 upgrade, `MIGRATION_16_17` creates `medex_fts` with the four columns, `tokenize=porter`, and `content=medex_products`, then issues `INSERT INTO medex_fts(medex_fts) VALUES('rebuild')`. On a fresh install Room generates the FTS entity and synchronization triggers before the catalogue import. `@Upsert` avoids `INSERT OR REPLACE` behavior that can leave a stale external-content index when delete triggers are not fired. `searchFts` joins the FTS rowid back to the product row and applies a caller-provided limit; it has no `ORDER BY`/relevance ranking. This search path is separate from `MedicineMatcher`'s in-memory fuzzy index.

`python3 android/checks/medexftscheck.py` executes the migration SQL and simulates Room-style insert/update/delete triggers using the host's SQLite. `MedexSearchTest.kt` covers expression construction, but has not been run here. Neither checks the generated Room schema or the device's SQLite. Before rollout, test a fresh install **and** an upgrade of a copy of a real v16 database; verify preserved prescription/queue/photo references, index count/search before and after insert/update/delete, exact/multiword/partial/punctuation and non-Latin queries, and account isolation. Measure latency across representative devices and warm/cold caches; the implementation does not establish a sub-millisecond guarantee. Do not clear application data to perform this QA.

### 7.4 Sync protocol, cloud payload and failure analysis

1. `AuthGate` starts per-UID upload and history work, refreshes membership, watches profile/avatar data and binds `DeviceStateRepository` to the active UID; its per-UID `ViewModelStore` is cleared when the owner leaves composition. `TrustedMembership.refresh` reads `users/{uid}` and `companies/{company}/members/{uid}` from the server, requires an active member with a display name, checks the UID again and caches the company locally. Cached membership supports local decisions, not authorization by itself.
2. `UploadScanWorker` obtains the UID from work input and stops for a different current user. Connected work reads UID-filtered `PENDING` rows and checks their stored company/scan UUID. For a new document it awaits a Firestore `set` containing scan ID, doctor/specialty/territory, medicine maps, raw/parsed prescription date, scanner identity, creator UID, capture timestamp and server upload timestamp. Medicine maps hold brand, generic, confidence, company and optional MedEx product ID; no photo is sent.
3. A pre-existing document is compared against the same identity, content and compatible metadata before the local acknowledgment. A mismatch is failure, not overwrite. Permission-denied on an individual scan marks it rejected; other thrown exceptions reach retry handling. Missing membership or a nonmatching tenant has distinct handling in source; an empty company snapshot is not silently assigned. Network constraints are scheduling conditions, not proof of server authorization.
4. Backfill uses a Firestore transaction to fill missing metadata on compatible, previously uploaded owner records still available locally. `ScanCloudFields.timestamp` accepts only valid ISO `YYYY-MM-DD` and uses UTC midnight; the raw date remains available for other formats. Older cloud IDs can be stored in a parallel bounded JSON array; mismatches are not overwritten.
5. Restore uses 100-document pages ordered by document ID, server source, owner and active-membership checks, local tombstones and a Room transaction. Existing local rows are not replaced with reduced cloud representations. Malformed cloud data can be skipped; image paths on restored rows are empty. Team feed is a separate company-wide read limited to 30 recent server-confirmed documents; the avatar directory carries IDs, not uploaded profile photographs.

`AuditActions.delete` removes locally owned medicines/visits/prescription in one transaction and writes a tombstone when company and UUID exist. It does not delete a cloud record or the image file. An in-flight upload may still complete. Duplicate approval is also local; restored cloud rows currently do not preserve `duplicateOf`, so cross-device duplicate metrics need further work. The Settings purge is a different, explicitly confirmed destructive operation and can remove eligible local files.

### 7.5 Project map, resources and defense preparation

- `data/repo/MedicineMatcher.kt`, `MedicineEnricher.kt`, `Compliance.kt`, `Intelligence.kt`: product resolution, enrichment and regulatory/substitution calculations. `MedicineEnricher` gives a chosen catalogue product precedence, retains raw/model provenance, and throws if a selected preferred ID no longer resolves. Regulatory labels are data-driven flags, not a substitute for clinical/regulatory verification.
- `data/repo/AnalyticsMetrics.kt`, `TeamMetrics.kt`, `DoctorIntelligence.kt`, `RxAudit.kt`, `Geofence.kt`, `PHash.kt`: metrics, doctor/territory analysis, duplicate and location logic. The DAO and filter fragments define which records feed aggregate views; use a concrete query when defending an exact metric.
- `data/repo/PharmaHub.kt`, `NewsRepository.kt`, `RegulatoryRepository.kt`, `TripsPortfolio.kt`, `LocationRepository.kt`: bundled/domain and live-content services; their data availability and freshness vary. `MedicineImageStore` searches bundled catalogue first and can use a network fallback.
- `data/export/*` and `ui/export/FileExport.kt`: analytics document/PDF creation and export/share integration. `ui/theme`, `ui/components`, `ui/shell`, `ui/navigation` are presentation infrastructure; `ui/screens/*` are feature surfaces listed above. `data/model` declares payload and presentation models. `OpenRouterErrors.kt` and `VlResponseSchema.kt` are historical source artifacts and should not be confused with the active Gemini request path.
- Manifest permissions include internet, network state, camera and coarse/fine location; runtime permission handling is still required. `res/xml/backup_rules.xml` and `data_extraction_rules.xml` exclude `rx/`, `prescriptions/` and `medlenx.db` from the specified backup/transfer modes. `file_paths.xml` configures the non-exported FileProvider; do not interpret a share URI as a cloud backup. Icons, themes and string resources live in `res/`.
- Static check examples: `python3 android/checks/medexftscheck.py`, `python3 android/checks/scanarchitecturecheck.py`, `python3 android/checks/imports.py`, and `python3 agent/roomcheck.py`. They check selected patterns or host SQLite and are not a Kotlin build or device proof. Local Android QA should also exercise auth changes during capture/sync, rejected membership, queued image recovery, offline-to-online transitions, result verification, FTS migration, and Firestore rules with authorized and unauthorized identities.

## 8. Exact imports of the three core source files

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
import com.medlenx.lab.data.local.searchMedex
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

## 9. Source file inventory and declaration map

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
- [`data/local/Daos.kt`](android/app/src/main/java/com/medlenx/lab/data/local/Daos.kt): `DoctorDao`, `count()`, `idFor()`, `byKey()`, `update()`, `insertIgnoring()`, `all()`, `MedexDao`, `insertAll()`, `byBrandExact()`, `searchFts()`, `byCompany()`, `companies()`, `forms()`, `medexMatchExpression()`, `searchMedex()`, `PrescriptionDao`, `insert()`, `upsert()`, `countByScanId()`, `insertRestored()`, `updateRestoredCaptureTime()`, `updateRestoredPrescriptionDate()`, `updateRestoredProductId()`, `metadataBackfillCandidates()`, `markMetadataSynced()`, `relocateLegacyImage()`, `markUploaded()`, `observeTrappedCount()`, `trappedAudits()`, `deleteMedicinesForAudits()`, `deleteVisitsForAudits()`, `deleteTrappedAudits()`, `allReferencedImagePaths()`, `markRejected()`, `addDeletedScanTombstone()`, `isDeletedScan()`, `approveDuplicateForTesting()`, `deleteOwnedMedicines()`, `deleteOwnedVisits()`, `deleteOwnedPrescription()`, `pendingUploads()`, `observePendingUploads()`, `pendingUploadCount()`, `claimableAudits()`, `observeClaimableAudits()`, `assignAudit()`, `observeUnroutableUploads()`, `observeRecent()`, `observeCountBetween()`, `byId()`, `findByHash()`, `scannedSince()`, `doctorTierRows()`, `stewardshipRows()`, `offTerritoryRows()`, `scanPointRows()`, `trendRows()`, `observeBrandCapturedRows()`, `observeDashboardBrandScans()`, `observeDashboardTopDoctors()`, `geoRegionRows()`, `prescriptionCountSince()`, `prescriptionCountBetween()`, `prescriptionCountAll()`, `itemCountSince()`, `ownItemCountSince()`, `ownItemCountBetween()`, `itemCountBetween()`, `topBrandRow()`, `activeDoctorCount()`, `allDoctorCount()`, `companyItemCount()`, `companyGenerics()`, `companyBrands()`, `companyDoctors()`, `brandDoctors()`, `recentMedicineExportRows()`, `scanExportRows()`, `mostPrescribedRows()`, `companyShareRows()`, `doctorLeaderRows()`, `doctorLeaderIntelligence()`, `doctorLeaderTotal()`, `doctorLeaderMedicines()`, `liveScanRows()`, `observeAuditFeed()`, `observeAuditFeedCount()`, `specialtyCompanyCounts()`, `genericMatrixRows()`, `filterDistricts()`, `filterTerritories()`, `filterSpecialties()`, `filterMrIds()`, `insertMedicines()`, `medicinesFor()`, `recentMedicines()`, `medicineCount()`, `setRxNumber()`, `hashRows()`, `QueueDao`, `enqueue()`, `observeAll()`, `observeCount()`, `remove()`, `updatePayload()`, `markAttempt()`, `ProfileDao`, `save()`, `observe()`, `current()`, `observeTargets()`, `saveTargets()`, `clearTargets()`, `doctorTargetRows()`, `recentVisits()`, `deleteDoctorTarget()`, `RsmDao`, `addTarget()`, `removeTarget()`, `targetsForDoctor()`, `logVisit()`, `visitsFor()`, `reportError()`, `observeErrors()`, `ManufacturerAliasDao`, `canonicalCompanies()`, `canonicalFor()`
- [`data/local/DoctorIdentity.kt`](android/app/src/main/java/com/medlenx/lab/data/local/DoctorIdentity.kt): `DoctorIdentity`, `keyOf()`, `norm()`, `displayName()`
- [`data/local/Entities.kt`](android/app/src/main/java/com/medlenx/lab/data/local/Entities.kt): `MedexEntity`, `MedexFtsEntity`, `DoctorEntity`, `PrescriptionEntity`, `ScannedMedicineEntity`, `QueuedScanEntity`, `OfficerProfileEntity`, `BrandTargetEntity`, `DoctorTargetEntity`, `DoctorVisitEntity`, `ErrorReportEntity`, `ScannedItemRow`, `DoctorTierRow`, `StewardshipRow`, `OffTerritoryRow`, `ScanPointRow`, `BrandCapturedRow`, `TrendItemRow`, `DoctorTargetRow`, `AuditFeedEvent`, `SpecialtyCompanyCount`, `LiveScanFeedRow`, `RecentMedicineRow`, `GenericMatrixRow`, `PrescriptionHashRow`, `MostPrescribedRow`, `CompanyShareRow`, `DoctorLeaderRow2`, `DoctorBrandCount`, `DoctorPrescriptionCompanyCount`, `TopBrandRow`, `DrillCountRow`, `BrandDoctorRow`, `GeoRegionRow`, `DoctorVisitRow`, `ScanExportRow`, `ManufacturerAlias`, `DeletedScanTombstone`
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
