# Firebase team sharing: setup and current status

**Not yet functional.** The repository currently contains the Firestore dependency
and a proposed rules baseline, not the upload pipeline, team feed, legacy-scan
migration, or account-safe cache handling. Do not use this as a production sync
feature or deploy these rules without testing and integrating them with your
project's other rules. The app still saves audits locally and does not upload them.

## Initial project setup

1. In the Firebase console, create or select a project. Register an Android app
   with package name `com.medlenx.lab`.
2. Enable **Authentication → Sign-in method → Email/Password**. Create the
   necessary accounts. Self-registration alone must not grant company access.
3. Create a Cloud Firestore database in the appropriate region. Select production
   mode; do not use open test-mode rules. Consider retention, access auditing,
   consent and applicable prescription-data privacy requirements before storing
   any real audit data.
4. From Firebase project settings, obtain the Android app's API key, app ID and
   project ID. Set `FIREBASE_API_KEY`, `FIREBASE_APP_ID` and
   `FIREBASE_PROJECT_ID` in the ignored `android/local.properties`, following
   `android/local.properties.example`. These values identify the project; they
   are embedded in the APK and are **not secrets**. Never put service-account
   credentials in the app or in Git.

## Membership and proposed rules

5. Choose a stable, unique company document ID. Do not use a freely editable
   display name as proof of membership. A display name is neither guaranteed to
   be unique nor safe as a Firestore document path (for example, it may contain
   `/`). An eventual implementation needs an administrator-assigned mapping
   from the local company selection to this ID.
6. A trusted administrator must provision both `users/{firebaseUid}` with a
   string `companyId` and `companies/{companyId}/members/{firebaseUid}` with
   boolean `active: true` and string `displayName` in the Firebase console or
   using a trusted Admin SDK. The company ID must be identical in both paths.
   Obtain the UID from the Authentication Users page after account creation.
   Clients cannot create or modify either document under the proposed rules.
   To revoke membership, set `active` to `false` or delete the member document.
   Offline copies already downloaded to devices cannot be remotely erased immediately.
7. Review `firestore.rules`. It grants team-scan reads only to active members of
   that company and permits only authenticated, attributed creates. The rules
   are a **baseline for a future client**, not a tested deployment: they deny
   all unmatched paths and could break other applications sharing this database.
   Merge them carefully into any existing rules and test with the Firestore
   emulator, including cross-company access, revoked members, forged identity,
   malformed payloads and offline/reconnect behavior, before publishing.

The rules disallow an image field at the top level, but Firestore rules cannot
iterate over an arbitrary medicine list to fully validate each nested object.
Do not rely on these rules alone to prevent malicious clients from placing
sensitive content inside medicine fields. Production validation and any stronger
content restrictions require a trusted server-side ingestion path.

## Work still required before enabling sync

- Bind the signed-in UID to the server-assigned `users/{uid}.companyId` and
  approved membership, and observe membership changes. The current app does not
  yet perform this lookup.
- Make the local Room write and cloud submission crash-safe. Firestore
  persistence alone cannot make a Room write and a Firestore write atomic.
  Surface rejected writes. Sign-out currently retains local data and must not
  delete unsynced work. A Firestore instance must be terminated before clearing
  persistence; clearing it deletes pending writes, so that cannot be added to
  sign-out without a durable alternative for pending uploads.
- Partition all user-local data by authenticated UID, including profile, scans,
  medicines, targets, queue, preferences and images. The current database is
  shared across sign-ins and is not yet partitioned. New prescription rows now
  record the signed-in UID, while historical rows are marked `legacy_user`.
  Recent-audit, Analytics and several Team queries, profiles, brand targets and
  the image-processing queue now use UID predicates. The shared doctor table,
  legacy-image migration, and other paths still need a complete isolation audit;
  this is **not total isolation**. Legacy images are retained, but recent capture
  previews only open images in the current user's scoped directory. Any later
  cloud upload of existing local scans requires a deliberate migration.
- Isolate/clear account-sensitive cache and UI across sign-ins. Cached Firestore
  results and pending writes require deliberate treatment on shared devices;
  server rules cannot revoke data already cached offline.
- Implement the real-time feed, read-only audit details, visible failure/status
  handling, and update existing “cloud sync unavailable” labels only when the
  behavior is actually working.
- Build and run emulator/security tests plus two-account, two-company, offline,
  restart, revocation and shared-device tests before production rollout.
