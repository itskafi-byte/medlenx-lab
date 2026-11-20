# MedLenX Lab: a beginner’s textbook and project defense manual

**Reading rule:** each important specialist term is followed by a short explanation in square brackets. A name in backticks [the special formatting used here for an exact name in the code] points to something you can search for in the project. Explanations describe the checked-in Android app [the program installed on an Android phone], not a promise that it has passed medical, security, or device testing.

**Important:** this guide describes source code [the files programmers wrote]. It does not establish that the app compiles [is successfully converted into a runnable program], that either named online model is available, that its answers are accurate, or that access rules have been deployed [installed in the actual online service]. The app is a prototype [an experimental implementation], not a medical diagnostic device.

## 1. App architecture and the complete user journey

### 1.1 What this app is trying to do

An officer photographs or selects a prescription, previews the full image, asks an online model to read it, corrects the result, and saves the verified record on the phone. The app can then send eligible structured records to colleagues in the same approved company. It also offers medicine information, analytics [summaries calculated from recorded information], team views and exports [files generated to share or retain].

The app uses Kotlin [the programming language used for the Android code] and is located in `android/app/src/main/java/com/medlenx/lab` [the folder containing the app's human-written Kotlin files]. There is one application module [one main unit built into the Android app], not a separate server module for each screen.

### 1.2 The organizational pattern

The project is MVVM-like [Model–View–ViewModel-like: data and rules, screen presentation, and a screen's state/behavior are largely separated]. A **model** [a Kotlin description of data] includes classes such as `VlScanResult` [the structure holding the model’s extracted doctor and medicines]. A **view** [the part drawn for the user] includes `ScanScreen` [the scanning screen]. A **ViewModel** [a screen-oriented holder for state and actions that survives ordinary screen recreation] includes `ScanViewModel` [the object coordinating capture, extraction and review].

The project is not strict Clean Architecture [a design that rigorously keeps business rules independent of platform and external services]. Some ViewModels [screen state holders] directly reach Android APIs [phone-provided programming interfaces], Firebase [Google's online app services], and database objects. This matters in a defense: say “MVVM-like with practical repository boundaries,” not “perfect Clean Architecture.”

The manual dependency graph [the code that constructs objects and supplies the objects they need] is `AppGraph` [the central construction point]. It creates shared services [objects responsible for particular work], including the database, scan client and connectivity observer. It is not Hilt [a library that can generate dependency wiring automatically]. `MedLenXApp` [the process-wide application entry point] creates this graph, launches catalogue import, starts connectivity observation and initiates legacy-image handling. `MainActivity` [the Android screen-hosting entry point] displays the theme [shared appearance settings], authentication gate and app shell [the shared navigation frame].

### 1.3 Why offline-first matters here

Offline-first [designing the app so important local work can continue when a connection is unavailable] helps a field officer who may photograph prescriptions in buildings or territories with unreliable connectivity. Preserving the original capture avoids requiring the officer to repeat the visit just because a remote service is unreachable. A locally saved, reviewed record can wait for later upload.

This is not full offline AI [automatic extraction without an online service]. The present model call still needs the internet, and a parked capture must be reopened for explicit review. “Offline-first” also does not mean “safe from device loss”: the photo and unuploaded record remain dependent on the device until an appropriate independent backup exists.

### 1.4 Start-to-finish timeline

1. **Start:** Android [the phone operating system] starts `MedLenXApp` [the application entry point], then `MainActivity` [the screen host]. The shared catalogue starts importing on an IO scope [a background-work context intended for file and database operations].
2. **Identify the user:** `AuthGate` [the component deciding whether sign-in and onboarding are complete] watches Firebase Authentication [Google's sign-in service]. A UID [the signed-in account's unique identifier] separates local records. A locally selected company is not the same as administrator-approved online membership.
3. **Capture or choose:** `CameraViewfinder` [the camera screen] uses CameraX [Android's library for camera preview and capture]. The user can instead pick an image. Camera permission [the user's permission for the app to access the camera] is requested at runtime [while the app is running]. The saved image is stored in a per-user private folder [an app-owned location organized by account].
4. **Preview:** `PrescriptionImageViewer` [the zoomable image preview] shows the full image. The active path no longer calls `DocumentLocator` [the older local line detector] or requires choosing rectangles.
5. **Gate the request:** `NetworkConnectivityObserver` [the component watching the phone’s default network] checks Android validated-network status [the operating system’s indication that the connection passed an internet check]. Offline extraction parks the image path; a toast [a short popup] confirms successful parking. This does not prove that OpenRouter is reachable.
6. **Prepare the picture:** `ImagePrep.bitmapUploadJpeg()` [the upload preparation function] scales the full image to at most 1,280 pixels [picture elements] on its longest edge without enlarging smaller pictures, then uses JPEG [a compressed image format] quality 78. A temporary scaled bitmap [an in-memory image] is recycled [its native image memory is released] in a finally block [cleanup that runs even if encoding fails]. The saved original is not replaced.
7. **Ask the model:** `MedLenXVlClient` [the remote extraction client] sends one prompt [instruction] and one Base64 image [image bytes converted to printable text] to OpenRouter [a model-routing service]. It tries `google/gemma-4-31b-it:free` [the requested primary identifier] and, after failure and a 2.5-second cancellable pause [a wait that stops on cancellation], `nvidia/nemotron-3.5-super-vl:free` [the requested fallback identifier]. Availability and image support remain unverified.
8. **Check the reply:** `GeminiScanParser` [the local response validator, named for an older provider] removes outer Markdown fences [triple-backtick text wrappers] and checks JSON [structured text], required doctor fields, a masked-patient flag, date, medicine fields and confidence [the model’s score] between zero and one. The medicine array [ordered list] may contain any number of entries, including zero.
9. **Review:** `enterVerification()` [the function that prepares the editable review screen] uses the local catalogue to enrich [attach matching information to] each medicine. The user can correct doctor details, medicine details and location. A suggested match is not proof of clinical correctness.
10. **Save:** `save()` [the review confirmation action] merges edits and recalculates matching data. `ScanRepository.saveVerified()` [the data-layer save operation] checks the active UID, writes the prescription and ordered medicine rows in one transaction [a group of database changes that succeeds or fails together], and creates a stable scan UUID [a randomly generated identifier used for the same saved scan].
11. **Send later:** `UploadScanWorker` [the scheduled background task that uploads verified results] reads pending records for the signed-in user, verifies trusted company membership and sends eligible structured data to Firestore [Google's online document database]. `RestoreHistoryWorker` [the task that brings back eligible previously uploaded records] performs the reverse for the user's own records. Neither worker uploads prescription photographs.

### 1.5 The two different queues

`queued_scans` [the local list of captures waiting for extraction] stores an image path; older rows may also contain unused line selections. Reopening does not upload automatically. `PENDING` prescriptions [verified records waiting for cloud synchronization] are separate and already reviewed.

## 2. Frontend implementation and frameworks

### 2.1 How a screen is described

Jetpack Compose [Android's toolkit for describing native screens as Kotlin functions that react to changing data] is used for the app's main presentation. A composable [a Kotlin function that describes a piece of screen content] can render a button, card, screen or other visual component. State [the current data a screen observes, such as the scan status] changes over time; recomposition [Compose recalculating affected screen content when observed state changes] updates what the user sees.

State hoisting [keeping shared state in a parent or state holder and passing values plus event callbacks to children] is used in many places: `ScanViewModel.state` [the current scan-screen snapshot] is passed to screen components along with actions such as selection or save. A callback [a function passed to another component to be called later] tells the state holder what the user did. Some short-lived controls also keep local state using `remember` [a Compose tool for retaining a value across screen recalculations]. The code does not make every composable completely stateless.

A Modifier [a Compose instruction attached to a visual component] controls details such as size, padding [space around content], clipping [preventing drawing outside a boundary], scrolling and touch behavior. Material 3 [Google's design-component library] supplies standardized components; `ui/theme` [the folder of appearance definitions] supplies shared colors, text sizes and shapes.

Coroutines [Kotlin tasks that can pause while waiting without blocking the calling thread] perform longer operations such as reading files or waiting for a network answer. `viewModelScope` [the lifetime-managed scope attached to a ViewModel] cancels its work when that holder is cleared. Flow [a stream that emits values over time] is used for database and connectivity updates. Cancellation [stopping work that is no longer needed] is preserved through the client; cancellation is not treated as an ordinary failure requiring a fallback.

### 2.2 Why Compose rather than traditional XML layouts

Traditional XML layouts [separate markup files that describe Android screen structures] often pair a layout file with code that finds and updates visual elements. Compose [the Kotlin-based screen toolkit described above] makes it possible to express presentation, state observation and reusable controls together in Kotlin. This project’s code uses that approach consistently across its navigation, review cards, theme and charts. It is a reasonable explanation of the design choice, **not** a measured claim that Compose is always faster or that a traditional layout could not implement the same features. The project still has XML resource files [configuration files used by Android] for items such as provider paths and backup rules.

### 2.3 Screen-by-screen tour

| Screen or component | What the code uses it for |
|---|---|
| `AuthGate` [the sign-in gate] | Shows sign-in or company onboarding until the account and local profile conditions are met. It starts account-specific observers and background work after sign-in. |
| `CompanyOnboardingScreen` [company selection screen] | Collects the local company/profile choice; online membership is still controlled separately by administrators. |
| `MedLenXShell` [the surrounding app frame] | Presents the main destinations, top bar [upper navigation and status area] and bottom navigation [the lower row of main destinations]. |
| `ScanHome` [scan landing area] | Starts the capture flow and shows scan-related summaries. |
| `ScanScreen` [the capture and review workspace] | Hosts camera/picker actions, the image preview, network/queue state and verification phases. |
| `CameraViewfinder` [the camera preview and shutter] | Binds camera preview and picture capture to the screen lifetime; releasing the dialog unbinds its camera uses. |
| `PrescriptionImageViewer` [zoomable prescription preview] | Shows the entire image before extraction; the old line selector is not used by the active scan path. |
| `PrescriptionImageViewer` [the photo viewing component] | Displays the image with zoom [magnification] and a full-screen viewing option. |
| `Verification` and `MedicineCard` [the editable review components] | Let the officer inspect and correct doctor, medicine and location details before the local save. |
| `RxAuditScreen` [the detailed prescription review screen] | Presents recorded medicines and audit information, with local duplicate approval and local deletion actions. |
| `AnalyticsScreen` [the statistics screen] | Displays filters, summaries, charts [visual representations of counts], leaderboards [ranked lists] and drill-downs [more detailed views]. |
| `HubScreen` [the information center] | Provides the drug index, regulatory information, news, jobs, health days and the TRIPS tracker [a view of selected pharmaceutical trade-related information]. |
| `TeamScreen` and `TeamFeed` [team-information screens] | Show team statistics and a company-scoped activity feed [a recent ordered list of shared events]. |
| `SettingsScreen` [the configuration screen] | Edits profile information and local targets; sign-out and the separately confirmed destructive purge are distinct actions. |
| `HelpScreen` [the in-app guide] | Displays help and supports reporting a misidentified medicine. |
| `SearchOverlay` [the search panel shown over another screen] | Searches the local medicine catalogue with a typing delay [a short wait before each search to avoid querying for every keystroke]. |

## 3. Backend, local data and FTS4 search

### 3.1 “Backend” in this Android project

A backend [the part that stores, processes or supplies data behind a screen] here is partly **on the phone**: repositories [objects that coordinate access to data and operations], local files and a database. The separate online portions are OpenRouter [the model-routing service] and Firebase [the sign-in and cloud data services]. There is no custom model proxy [a server owned by this app that hides the model key] in the checked-in Android app.

SQLite [a small relational database engine stored on the device] stores tables [named sets of rows], each containing columns [named fields]. Room [an Android library that maps Kotlin classes and query methods to SQLite] helps define and access those tables. An entity [a Kotlin class describing a stored table] includes `PrescriptionEntity` [a reviewed prescription record]. A DAO [Data Access Object: an interface containing database operations] includes `PrescriptionDao` [the interface used to query and update prescriptions]. A migration [an upgrade step that changes an existing database without intentionally wiping it] moves the schema [the structure of the database] from one version to another.

`MedLenXDatabase` [the application's Room database definition] is version 18. Its registered migrations cover versions 1 through 18; destructive fallback [automatically deleting an incompatible database to start over] is not enabled. `medlenx.db` [the local database file] contains prescriptions, medicines, doctors, queue entries, profiles, targets, visits, reports, aliases and deletion markers. The photo itself lives in an app-owned file; a database row contains its path [the location of that file], not the image bytes [the image’s raw stored data].

### 3.2 The drug catalogue and FTS4

`AssetCatalogue` [the importer for bundled reference data] reads `medex_full.json` [the bundled medicine catalogue file, described in the project as approximately 25,105 items]. It writes batches [groups of records] into `medex_products` [the medicine table]. A mutex [a lock preventing concurrent work from running the same critical section] serializes the import. A limitation: the importer treats an already positive row count as ready, so an interrupted partial import needs a more reliable completion check in future.

FTS4 [SQLite’s version-four full-text search facility] implements full-text search [searching an index of processed words instead of repeatedly looking through every text value]. `MedexFtsEntity` [the Room declaration for the search table] indexes brand, generic name, company and type. It uses Porter tokenization [a word-processing method with English-oriented stemming, which groups some word forms] and prefix indexes [extra search structures for beginnings of words] of lengths 2, 3 and 4.

A token [a word-like unit recognized for indexing] has a prefix [its beginning]. Thus a query like `nap` may find `Napa`, while `apa` is not guaranteed to find the middle of that word. The app converts input into quoted prefix terms [search terms safely surrounded by quotation marks and marked with `*`], then combines them with implicit AND [all terms must match, possibly in different indexed columns]. `searchFts()` [the database search operation] uses `MATCH` [the SQLite operator for querying a full-text index] and joins the matching search rows back to the full medicine rows by `rowid` [SQLite's internal row number]. The query does not declare a relevance ranking [a score-based ordering of results].

The old `LIKE` query [a text comparison that supported substring patterns such as `%napa%`] could require broad table scans [checking many rows rather than locating terms through a suitable index], especially with a leading wildcard [a pattern allowing arbitrary text before the search term]. Repeated broad scans can cost processor time and energy, but this project did not measure that cost. It is **not accurate** to say the previous query necessarily blocked the main thread [the thread responsible for screen interaction]: it was a suspend DAO operation [a database function intended to run without blocking its caller]. FTS4 is an intended performance improvement, not evidence of guaranteed sub-millisecond [less than one thousandth of a second] performance or identical substring behavior.

Room manages external-content triggers [automatic database actions that keep the search index in step with the original product table]. Migration 16→17 creates and rebuilds the search table; migration 17→18 recreates it with the short-prefix indexes and rebuilds it again. `@Upsert` [a Room instruction to insert a new item or update an existing one] is used for catalogue writes to avoid a replacement behavior that could leave stale indexed terms. These migration and trigger behaviors still need actual Android device testing.

### 3.3 The connectivity gate, often called a circuit breaker

A conventional circuit breaker [a protective mechanism that temporarily stops requests after repeated failures and later allows a trial request] normally has failure counts and timed recovery. This app instead has a **connectivity gate** [a check that prevents starting a scan request when Android reports no validated default network]. `NetworkConnectivityObserver.observe()` [the stream of connectivity states] listens for network changes and releases its listener [the registered callback] when collection stops. `ScanViewModel.analyze()` [the start-scan action] samples connectivity immediately before the first request.

This can avoid futile requests while the phone is offline and therefore may reduce unnecessary radio work [wireless communication activity]. It does not prove a particular service is reachable, eliminate all timeouts, or implement a failure-count/timer-based circuit breaker. A network can disappear immediately after the check. A host-specific error [failure reaching a particular online service] may still happen on a validated network. The automatic fallback [the second model attempt] is made after a primary failure and does not perform a separate connectivity check.

### 3.4 Background synchronization and tenant boundaries

WorkManager [Android's library for scheduling deferrable background tasks] starts one-time upload requests and unique periodic work [a recurring named task that is not duplicated under the chosen policy] every 15 minutes, subject to a connected-network constraint [a requirement that a network is present]. This schedule is not an exact timer: Android may defer work.

`UploadScanWorker` [the verified-record upload task] reads `PENDING` [waiting to upload] rows for the current account, checks server-confirmed company membership, and uses the saved company snapshot [the trusted company value recorded when verification was saved]. It writes to the corresponding company’s scan collection [the group of cloud documents for that company]. It waits for acknowledgement [confirmation that the requested write completed] before marking a row `UPLOADED` [successfully acknowledged locally]. A permission denial [the server refusing access] marks applicable records `FAILED_REJECTED` [a locally recorded authorization failure]; transient failures [errors that may be temporary] generally request a retry.

`RestoreHistoryWorker` [the history-download task] retrieves server-confirmed records belonging to the signed-in user, rechecks membership, skips local tombstones [markers that suppress restoring a locally deleted record], and saves reduced records locally. It cannot restore never-uploaded scans or original photos. `TeamFeedRepository` [the company activity reader] observes up to 30 recent server-confirmed shared records. Firestore rules [server-enforced permissions] in `android/firebase/firestore.rules` must be deployed and tested: UI checks [checks made only in the app screen] cannot alone establish security.

## 4. Single-image AI pipeline and engineering pivot

### 4.1 What “hybrid” means here

The current flow combines local image preparation [resizing and encoding on the phone] with remote vision extraction [reading an image using an online model]. ML Kit [Google’s on-device machine-learning toolkit] and the older line detector remain in the source but are not called in the active scan path.

The chain is CameraX [Android camera library] or gallery selection → image preview → JPEG compression [lossy image encoding] at 1280 pixels and quality 78 → Base64 [printable encoding of bytes] → OpenRouter API [remote request interface] with one full image → dynamic JSON validation [checking an ordered list of any length] → human review → Room save.

The request uses OkHttp [an Android networking library] for HTTPS POST [encrypted data submission] to `https://openrouter.ai/api/v1/chat/completions` [the routing endpoint]. It contains one text part [instruction] and exactly one `image_url` part [embedded image], prefixed `data:image/jpeg;base64,` [the image data format]. `temperature=0.1` [a low randomness setting] and `max_tokens=4096` [the maximum output-token budget] are requested; no strict remote response schema [provider-enforced output format] is attached.

The requested primary is `google/gemma-4-31b-it:free` [the first model identifier]; the fallback is `nvidia/nemotron-3.5-super-vl:free` [the second model identifier]. On a network, HTTP [web protocol], or parsing failure the app waits 2.5 seconds and retries once with the same image. A fixed delay does not guarantee a rate-limit reset or prevent HTTP 429 [too many requests]. HTTP status and provider error.message [provider-supplied error text] are logged with the key redacted, but such messages could contain sensitive information. Both model identifiers and image support need live verification.

### 4.2 Engineering pivot: from multi-crop to one image

Device testing reported a SIGABRT [native process abort] attributed to memory exhaustion on a 4 GB RAM [working memory] phone when encoding around 15 crops [separate image regions], plus irrelevant header crops and upstream HTTP 422 [provider rejection] with multiple images. These are reported observations, not a proven universal 15-image provider limit. The pivot removes the active local ML Kit line-selection step and sends one image per attempt to reduce the number of simultaneous encoded images and avoid multi-image requests.

Downscaling [reducing image dimensions] lowers upload-image memory and typically reduces bytes, but the original decoded image, JPEG bytes, Base64 string, JSON and HTTP copies can still consume substantial memory. A native abort cannot always be caught in Kotlin. The change does not guarantee the reported crash is fixed; profile the affected device. Small handwriting may become harder to read at 1,280 pixels.

The prompt [model instruction] asks the service to list every medicine under Rx [the prescription section] in top-to-bottom order and ignore headers and patient labels. This is not a technical guarantee of correct extraction or privacy. Human verification remains essential; compare real-world accuracy and payload sizes before claiming improvement.

### 4.3 Response handling and human verification

`OpenRouterScanProtocol.assistantText()` [the function extracting model text] rejects a present non-stop completion reason [an indication that the answer ended abnormally]. `GeminiScanParser.clean()` [the outer-fence cleanup] preserves backticks inside strings. `GeminiScanParser.parse()` [the validator] requires doctor, masked patient, date and medicine fields, validates confidence, and accepts a dynamic medicine count [a list that can vary in length]. Frequency and duration are combined into the existing frequency field so both reach review. No provider-enforced JSON schema is sent.

`MedicineEnricher` [the component adding catalogue and regulatory details] uses `MedexIndex` [the in-memory medicine matching structure] and selected product IDs [identifiers for chosen catalogue products]. Before saving, edited medicines are re-enriched so a selected identifier is not stored with stale matching data. The user remains responsible for inspecting the result.

## 5. Architectural weaknesses and future roadmap

The following ideas are proposals [possible future work], not features implemented by the current app. A good defense separates present behavior from a plan.

### 5.1 Duplicate cloud writes

Idempotency [the property that repeating an operation has the same intended effect as doing it once] is partly addressed by saving one client-generated UUID [a random identifier created on the phone] and reusing it for retries. The worker compares a previously existing cloud document before accepting a lost acknowledgement. But two independent saves can obtain different UUIDs, and two workers can race [overlap in time while checking and writing]. Identical-looking prescriptions are not automatically one cloud record.

A proposed content-addressable ID [an identifier computed from selected content] could use SHA-256 [a cryptographic function that produces a fixed-size digest from input]. A proposed last-write-wins policy [a rule choosing the update with the latest accepted timestamp] could resolve specified conflicts. Neither is currently implemented. Hashes alone do not make a read-then-write atomic [indivisible] or protect sensitive content from guessing. A safe design needs canonicalization [a consistent representation of the input], company/user scoping, an atomic server operation, trusted time, a plan for real identical prescriptions, and an explicit privacy and migration review. Last-write-wins can discard a legitimate concurrent edit.

### 5.2 Local disaster recovery

Disaster recovery [restoring usable data after the device or its storage is lost] is incomplete. Unuploaded scans, queued images and local photos can be lost if the app's private storage is wiped. Cloud restore [retrieval of already uploaded structured records] cannot restore photos that were never uploaded.

A proposed encrypted local vault [a protected store for backup data] could use AndroidX Security [Android libraries for protecting app data, subject to current support status] or SQLCipher [a library offering encrypted SQLite databases]. Encryption [making data unreadable without the right key] alone is **not a backup**: if the only encrypted copy is on the wiped phone, that copy is gone too. A real solution also needs an independently retained copy, user consent, protected and recoverable keys, integrity checks [tests that data has not been damaged or altered], and a tested restore process. `EncryptedSharedPreferences` [a protected small key-value settings store] is not a replacement for a full photo and database backup. No future backup should silently upload historical local-only scans.

### 5.3 Personally identifiable information

PII [personally identifiable information: information identifying a person] may be visible in the full prescription. Masking the returned patient field does not erase the transmitted image. The same image is sent again if fallback is attempted. API keys [credentials] embedded in the app can be extracted; provider error logs can also contain sensitive information.

A proposed on-device redaction layer [hiding sensitive image regions before sending] could combine ML Kit text recognition [local text detection] with NER [named-entity recognition, software that identifies names or other sensitive entities]. Neither redaction nor NER is implemented in the active flow. Handwriting and Bengali text can be missed; safe deployment needs measured accuracy, manual confirmation and fail-closed behavior [not transmitting if redaction is uncertain].

### 5.4 Additional engineering limitations

- The requested model IDs [the exact strings sent to OpenRouter] are not a verified model catalogue [a list of currently available services]. There is no live request or evidence that the fallback supports images.
- The connectivity gate [the pre-request network check] is not a conventional timed circuit breaker, and it cannot prevent all host-specific failures or guarantee lower battery use.
- FTS4 [the local full-text search facility] has host-side checks [tests using the development machine's SQLite], but a real Android build, generated trigger verification and migration testing remain necessary.
- Restore creates a reduced record and currently does not restore duplicate linkage [the local association to an earlier similar prescription]. Tombstones only govern local restoration, not global cloud deletion.
- Firebase Authentication [the online identity service] and Firestore rules [the cloud access controls] must be configured and tested; a local setting is not trusted membership.

### 5.5 Short supervisor questions and answers

1. **Why pivot away from ML Kit multi-crop extraction?** Testing reported a native memory abort on 4 GB hardware, header-noise crops, and 422 responses on multi-image requests. One full image reduces image-count pressure but is not proof of a crash fix or of a universal provider cap.
2. **Why send a full image?** It provides page context for doctor, date and medicine extraction without many crop encodings, but exposes the whole page to the provider and requires careful human review.
3. **Why FTS4 [SQLite full-text search] instead of leading-wildcard LIKE [substring search]?** An index of tokens can reduce broad repeated text scanning and supports word-prefix lookup. The old searches were suspend operations, and this project has not measured a main-thread block, battery saving or sub-millisecond latency.
4. **What happens when a request fails?** The client waits 2.5 seconds and tries the configured fallback once with the same image, unless cancelled. Both failures are shown using safe error guidance; provider error messages are logged with the key redacted and may themselves be sensitive. A wait cannot guarantee a rate-limit reset.
5. **What stops offline requests?** The validated-network gate [the Android connectivity check] can park a capture before the first model request. It is not a timed failure-count circuit breaker and cannot guarantee the remote host is reachable.
6. **How are local and cloud copies kept consistent?** A reviewed scan gets a stable UUID [random identifier] and a stored company value; the upload worker checks membership and acknowledges a successful or reconciled write. This reduces duplicate retries, but is not a complete race-free or cross-device deduplication guarantee.
7. **Can a wiped device recover everything?** No. Uploaded structured records may be restored under the right account and company, but unuploaded scans and local photos cannot be reconstructed from the present cloud sync.

### 5.6 How to find the code

The app's packages [folders grouping related code] are `data/config` [object construction], `data/local` [database, files and bundled data], `data/model` [data structures], `data/remote` [image preparation, local detection and the model client], `data/repo` [data coordination and calculation], `data/sync` [cloud workers and membership], `data/export` [document output], `ui` [screens, components and appearance], and `util` [small shared helpers]. Workers [scheduled background jobs] live in `data/sync`; there is no separate worker application module.

The most important source files [files to open while defending the program] are `MedLenXApp.kt`, `MainActivity.kt`, `AppGraph.kt`, `ScanViewModel.kt`, `ScanRepository.kt`, `DocumentLocator.kt`, `ImagePrep.kt`, `MedLenXVlClient.kt`, `GeminiScanParser.kt`, `Entities.kt`, `Daos.kt`, `Migrations.kt`, `MedLenXDatabase.kt`, `UploadScanWorker.kt`, `RestoreHistoryWorker.kt`, `TrustedMembership.kt`, `ScanCloudFields.kt` and `TeamFeedRepository.kt`. Their exact Kotlin imports [the lines naming classes brought into a file] are at the top of each source file; those lines are the authoritative list when code changes.

**Validation status:** static checks [source-pattern and host-database checks] are useful but are not Android compilation [building the phone app], device testing [running it on a phone], an access-rule deployment [installing the cloud permissions], or a live model call. No such build, device test, deployment or live request was performed for this update.

## 6. Step-by-step development guide: building the actual project

This guide explains how the checked-in project is assembled and how you can build it on your own computer. It is not evidence that the commands were run in this session. Preserve existing local-only data during testing; never uninstall or clear app data just to diagnose a problem.

### Phase 1 — Set up the project and tools

1. Install Android Studio [the development environment used to edit and build Android apps] and open the `android/` directory [the Android project folder] from this repository [the version-controlled collection of project files].
2. Inspect `android/settings.gradle.kts` [the file declaring build repositories and modules], `android/app/build.gradle.kts` [the app's build configuration], and `android/gradle/libs.versions.toml` [the file pinning dependency versions]. A dependency [a library that the app uses] must be resolved [found and downloaded by the build tool].
3. Install Android SDK 37 [the Android development platform used to compile this app]. The configuration currently has `compileSdk = 37` [the APIs available at build time], `minSdk = 26` [the oldest supported Android API level], and `targetSdk = 36` [the Android behavior level the app opts into].
4. Use the committed Gradle wrapper [the project-provided launcher for its pinned build tool], configured for Gradle 9.7.1. The checked-in version catalogue pins Android Gradle Plugin 9.3.2 [the Android build plugin], Kotlin 2.3.0, KSP 2.3.4 [the code-generation processor], and Room 2.8.3. Java 17 bytecode [the compiled instruction format targeted by the project] is configured separately from the JDK [Java Development Kit: the runtime/tools used to run Gradle]. Confirm that your installed toolchain works; this audit did not build it.
5. Let Studio create `android/local.properties` [a local, ignored configuration file] with `sdk.dir` [the Android SDK location]. Add `OPENROUTER_API_KEY` [the local key compiled into the prototype], `FIREBASE_API_KEY` [Firebase's project API key], `FIREBASE_APP_ID` [the Firebase app identifier], and `FIREBASE_PROJECT_ID` [the Firebase project identifier]. `MAPBOX_ACCESS_TOKEN` [the optional map-service token] enables the online map; a blank token uses the app’s canvas fallback [a locally drawn alternative]. Never commit real secrets.
6. Understand BuildConfig [generated constants included in the app]: values read from local properties are compiled into the app and can be extracted. Restrict the key for local prototyping; a production design needs a secure server or another appropriate protected approach.

### Phase 2 — Configure cloud identity and data

1. Create or select a Firebase project [a configuration container for Google app services] and enable the sign-in method used by the app. Configure Firestore [the cloud document store].
2. Review `android/firebase/firestore.rules` [the server access rules] and deploy them using your approved Firebase process. Test with an authorized account and an unauthorized account. Do not turn off rules merely to make a demo work.
3. A trusted administrator must provision `users/{uid}.companyId` [the user's assigned company field] and `companies/{company}/members/{uid}` [the company's membership document] with active membership and a display name. Local onboarding does not grant cloud membership.
4. Obtain an OpenRouter key [the model-service credential], configure it only on your own machine, and verify that the exact primary and fallback identifiers support vision [image input] and the selected account. No live availability was verified here. Review the service and provider privacy terms before using a real prescription.

### Phase 3 — Build the local database and the drug index

1. Define entities [Kotlin table descriptions] in `Entities.kt` [the local data declarations]. Include products, doctors, prescriptions, scanned medicines, queue entries, profiles, targets, visits, reports, aliases and tombstones.
2. Define DAOs [interfaces containing database operations] in `Daos.kt` [the database query file]. Use UID-scoped queries [queries restricted to the active user's identifier] where the underlying records are private.
3. Define `MedLenXDatabase` [the database container] and register all historical migrations [the version-to-version upgrade steps]. Never rewrite a migration already shipped to devices. Preserve `LOCAL_ONLY` [records not automatically eligible for upload] and `legacy_user` [unassigned historical ownership] instead of guessing a new owner.
4. Define `MedexFtsEntity` [the full-text search declaration] with FTS4 [SQLite full-text search version four], the Porter tokenizer [the word-processing rule] and prefix indexes [short-word-beginning indexes] of 2, 3 and 4. Keep the product table as external content [the original source of actual product data].
5. Keep migration 16→17 [the initial FTS table creation] and migration 17→18 [the prefix-index rebuild]. Test a clean install and an upgrade of a copy of a real old database. Room-generated triggers [automatic synchronization operations produced by the library] and the runtime migration must be tested on Android; the development-machine check is not equivalent.
6. Check that the nine datasets listed in `bundledDatasets` [the build's required reference-file list] exist under `android/app/src/main/assets/data/` and that `manufacturer_aliases.json` [the bundled manufacturer-name mapping] exists under `assets/`. `checkMedLenXAssets` [the build task checking for these files] fails early if required files are missing. Review dataset provenance and permission to redistribute.
7. Implement `AssetCatalogue.ensureImported()` [the guarded batch importer], then `MedexDao.searchMedex()` [the safe query builder] and `searchFts()` [the indexed database operation]. Verify blank input, punctuation, partial words, multiple words, non-Latin text and catalogue changes. Benchmark [measure performance under defined conditions] on the intended phones.

### Phase 4 — Build capture and full-image preview

1. Register `MedLenXApp` [the application entry point], `MainActivity` [the screen host], camera and network permissions [Android's declared capabilities], and the FileProvider [the controlled mechanism for sharing selected files] in `AndroidManifest.xml` [the app's Android configuration file].
2. Implement `CameraViewfinder` [the camera screen] with CameraX [the camera library]. Bind preview and capture to the lifecycle [the screen's active/inactive lifetime], and release them when the view closes. Support image selection as another input path.
3. Store original captures in per-user app files and decode orientation [correct visual rotation and mirroring]. Do not overwrite the original with the compressed upload image.
4. After capture or image picking, place the oriented image directly into Ready [the state in which the user can preview and confirm extraction]. Do not call the legacy `DocumentLocator` [old text-rectangle detector].
5. Implement `PrescriptionImageViewer` [the zoomable image view]. Check clipping [keeping content inside its container], rotation and large images. The old `LineSelector` [rectangle-selection component] is not used here.

### Phase 5 — Build the OpenRouter networking layer

1. Keep OkHttp 4.12.0 [the pinned networking library] and the `OPENROUTER_API_KEY` [the prototype credential] in the build configuration. The former direct Google AI SDK [the old Google-provided model library] is not used by this client.
2. Implement `ImagePrep.bitmapUploadJpeg()` [the upload-image preparation function]. Resize the full image to a longest edge of 1,280 pixels without enlargement; compress it as JPEG [a compressed image format] at quality 78. Recycle only a temporary scaled bitmap [an in-memory image], in a finally block [guaranteed cleanup].
3. Convert the JPEG byte array [image bytes] using `Base64.NO_WRAP` [single-line printable encoding]. Construct one text item and exactly one `image_url` item [JSON image object]. Set `temperature` [randomness] to 0.1 and `max_tokens` [output budget] to 4096, without strict `response_format` [provider-enforced schema].
4. Use `google/gemma-4-31b-it:free` [primary] and `nvidia/nemotron-3.5-super-vl:free` [fallback]. Wait 2.5 seconds after failure then retry once, preserving cancellation [a request to stop]. This wait cannot ensure a rate-limit reset; the same image is resent to the second provider.
5. Extract `choices[0].message.content` [the returned model text] and pass it to `GeminiScanParser` [the local validator]. Strip outer fences, validate required fields and confidence, and accept any number of medicine entries. Never treat a valid-looking response as clinically verified.
6. Add tests [repeatable behavior checks] for a single image, payload settings, dynamic list lengths, malformed replies, primary failure, fallback and cancellation. Kotlin tests were not run during this change.

### Phase 6 — Build the user interface and review flow

1. Create `MedLenXTheme` [shared visual settings], `AuthGate` [sign-in/onboarding gate], `MedLenXShell` [the main screen frame] and navigation destinations [named app screens].
2. Implement `ScanViewModel` [the scan state holder]. Model Ready [image ready], Scanning [request in progress], verification and Saved [successful local save] states using `ScanUiState` [the data snapshot for the scan screen]. Use user checks around asynchronous work [work completing later] to avoid showing one account’s result to another.
3. Add the validated-network gate [the pre-request connectivity check]. Offline analysis parks a queue record and announces success only when parking succeeds. An unreadable queued first image can block later queued images; test this behavior rather than assuming automatic recovery.
4. Build doctor, medicine and location review panels [editable screens for confirming the extracted result]. Re-enrich edited medicine choices against the local catalogue, compute a duplicate candidate when the image hash [a compact visual fingerprint] is available, and save in a Room transaction [all-or-nothing database operation].
5. Implement the separate Analytics [statistics], Hub [reference information], Team [team summaries], Settings [profile and targets], Help [guidance] and Rx Audit [prescription review] screens. The user can deliberately delete an audit locally; that action does not delete its cloud copy or photo. Sign-out does not silently erase local data.

### Phase 7 — Build background synchronization and security

1. Implement `TrustedMembership.refresh()` [the server membership verification operation]. Store only the server-verified company as the trusted company snapshot [the company recorded for the verified scan]; a local company choice is not sufficient.
2. Implement `UploadScanWorker` [the scheduled upload job] using a network constraint [a requirement for a connection], per-user `PENDING` rows [the active account's reviewed records awaiting upload], stable UUID document names [cloud document identifiers] and awaited writes. Keep provider-specific permission errors separate from retryable network errors.
3. Implement `RestoreHistoryWorker` [the download job] with server-confirmed membership, owner filtering, bounded pages [limited batches of cloud records], local tombstones [markers preventing unwanted reappearance] and transactional local insertion. Do not claim that it restores photos or unsent work.
4. Implement `TeamFeedRepository` [the shared team activity reader] with listeners [registrations for ongoing cloud updates] and cleanup on cancellation. Confirm deployed Firestore rules [cloud permissions] enforce the same company boundaries.
5. Exercise two accounts, a membership rejection, an account switch during an operation, offline capture, an acknowledged upload, and history restore. Use synthetic data [invented test data] and do not share actual prescriptions in test logs.

### Phase 8 — Build, run, and defend the result

1. On your own machine, synchronize the project [ask Gradle to resolve the project's configuration and libraries]. From `android/`, run `./gradlew assembleDebug` [build the debug app on Linux/macOS] or `gradlew.bat assembleDebug` [the Windows equivalent]. The debug APK [installable Android package] is normally under `app/build/outputs/apk/debug/`.
2. Run `./gradlew :app:testDebugUnitTest` [the debug local unit-test task] or its Windows equivalent, then run relevant Android device tests [tests executing on an emulator or phone]. These commands are instructions for you; no build was performed here. The release configuration currently uses a debug signing key [a development-only app signature] and is not production-ready.
3. From the repository root, run `python3 android/checks/medexftscheck.py` [the host SQLite check] and `python3 android/checks/connectivitycheck.py` [the static network-gate check]. These are supplementary; they do not prove Android-generated database code or live networking works.
4. With an approved test account and synthetic prescription [invented test data], test capture, preview, extraction, fallback, correction, save, upload and restore. Test offline parking and reopening both old and new queue rows. Profile memory and readability on the 4 GB device.
5. Test a provider-specific failure while Android still reports validated connectivity; the gate cannot prevent that error, and the app can attempt its fallback. Verify each model’s current availability and permitted image input directly with the provider. Redact credentials and personal details before sharing logs.
6. Present the distinction between implementation, intended benefit and measured result. The app now sends one image per attempt; this is intended to reduce memory and avoid multi-image provider rejection, but no new device or live-provider result proves either outcome.
