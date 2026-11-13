# MedLenX Lab

MedLenX Lab is an Android prescription-audit prototype for reviewing medicine lines, matching them against a bundled catalogue, and exploring local and company-scoped analytics. The Android application is in [`android/`](android/); the detailed code audit and supervisor guide is in [PROJECT_DEFENSE_AND_README.md](PROJECT_DEFENSE_AND_README.md).

## Features

- CameraX capture or image import, on-device ML Kit line selection, and manually drawn missing lines.
- Gemini-assisted extraction with editable doctor, medicine and location review before saving.
- Room-based local records, bundled medicine catalogue, offline capture queue and background synchronization of verified results.
- Analytics, prescription audit, drug information, team activity, profile settings and exports.

The app sends the full prescription image and selected crops to Gemini. Structured results may be shared with approved members of the same company; prescription photos are not uploaded by the scan sync worker. AI output must be reviewed by a person and is not a clinical diagnosis.

## Getting started

1. Open `android/` in Android Studio and install the Android SDK required by the Gradle configuration (currently compile SDK 37).
2. Configure `android/local.properties` or environment variables: `GEMINI_API_KEY` for scanning, `FIREBASE_API_KEY`, `FIREBASE_APP_ID`, and `FIREBASE_PROJECT_ID` for Firebase. `MAPBOX_ACCESS_TOKEN` is optional. Android Studio normally configures `sdk.dir`.
3. Configure Firebase Authentication and Firestore, deploy [`android/firebase/firestore.rules`](android/firebase/firestore.rules), and arrange administrator-approved company membership before testing team sync. See [`android/README.md`](android/README.md) for project-specific setup; its sample properties still refer to an older OpenRouter integration, so use the names in the build script.
4. Build and run from Android Studio or, from `android/`, run `./gradlew assembleDebug` (Windows: `gradlew.bat assembleDebug`). The app's Gradle task checks for required bundled datasets.

The current source requests `gemini-3.8-flash`; its availability for a given API key has not been verified. The Android Google AI SDK used here is deprecated. The key is embedded in the APK and can be extracted: use an expendable restricted key for local prototypes, and a secure backend or supported Firebase AI architecture before public distribution. Review privacy and provider data policies, since masking the output does not remove patient details from the image sent to the provider. Redact sensitive information before sharing logs.

## Architecture and storage

Compose screens and ViewModels use a manually wired `AppGraph`, repositories, Room and private image files. Verified local scans are queued for network-constrained WorkManager upload; parked offline captures must be reopened for review. A server-confirmed membership and Firestore rules govern company-scoped sharing. History restore cannot recover local-only images or records never uploaded.

The bundled medicine catalogue is imported into Room. Search uses a Room-managed FTS4 external-content index and token-prefix `MATCH`; medicine enrichment also uses a separate in-memory matcher. Prefix search differs from arbitrary substring search, and performance has not been benchmarked. See the [defense guide](PROJECT_DEFENSE_AND_README.md) for the full flow, code index, exact core imports, known limitations and technical Q&A.

## Validation status

Documentation was prepared from source without compiling, running device tests or calling Gemini. In particular, the current endpoint and Firestore deployment require verification. The FTS migration and Room-generated triggers still require local compilation and device testing. Do not uninstall the app or clear its data to troubleshoot if local-only records must be retained.
