# Firebase team sharing: setup and current status

**Implemented, not yet device-verified.** New audits use a UID-bound Room
outbox and WorkManager. The Team tab listens for team scans after validating
membership with the server. No prescription images are uploaded. Do not use
this for production clinical data until the rules and Android flows are tested.

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
   `/`). The app uses the administrator-assigned ID, not the editable local
   company selection, for cloud routing.
6. A trusted administrator must provision both `users/{firebaseUid}` with a
   string `companyId` and `companies/{companyId}/members/{firebaseUid}` with
   boolean `active: true` and string `displayName` in the Firebase console or
   using a trusted Admin SDK. The company ID must be identical in both paths.
   Obtain the UID from the Authentication Users page after account creation.
   Clients can create or update only their own `officerProfile` map in the user
   document; they cannot change `companyId` or the membership document. If a
   profile-only user document already exists, provision `companyId` with a merge
   so that the saved profile is not overwritten.
   To revoke membership, set `active` to `false` or delete the member document.
   Offline copies already downloaded to devices cannot be remotely erased immediately.
7. Review `firestore.rules`. It grants team-scan reads only to active members of
   that company and permits only authenticated, attributed creates. The rules
   are **not a tested deployment**: they deny
   all unmatched paths and could break other applications sharing this database.
   Merge them carefully into any existing rules and test with the Firestore
   emulator, including cross-company access, revoked members, forged identity,
   malformed payloads and offline/reconnect behavior, before publishing.

The rules disallow an image field at the top level, but Firestore rules cannot
iterate over an arbitrary medicine list to fully validate each nested object.
Do not rely on these rules alone to prevent malicious clients from placing
sensitive content inside medicine fields. Production validation and any stronger
content restrictions require a trusted server-side ingestion path.

## How sync behaves

- Settings saves the officer card, daily scan target, and monthly brand targets
  locally and merges an `officerProfile` map into `users/{uid}`. This display
  company is not the administrator-managed `companyId`. Android Firestore's
  default persistent cache queues profile writes offline. A server-confirmed
  profile is restored on sign-in/startup or reconnect, without replacing an
  unsent local save or in-progress Settings edits. After a local save, further
  cloud restores wait until the next sign-in/startup to avoid stale callbacks.
  Concurrent edits on different
  devices are last-write-wins, not a field-level conflict resolver. A backup can
  be lost if the app is uninstalled before the queued write reaches the server.
  A small crash window exists between the separate Room and preferences commits;
  re-entry retries dirty local data. Permission errors require the updated rules
  to be deployed; local saves continue even if the cloud write is rejected.
- Firestore rules cannot validate each element of the monthly-target list. Treat
  client-written profile data as untrusted; the app validates its shape on restore.


- Sign in online after an administrator provisions both membership documents.
  The app saves the last server-verified company for this UID and stamps it on
  each new audit. Firestore rules still enforce current membership at upload.
- New saved audits enter the Room outbox. A network-constrained one-shot worker
  uploads them; periodic work and the next sign-in recover missed scheduling.
  Fixed UUIDs plus server-side reconciliation handle a lost acknowledgement under
  the append-only rules. Sign-out retains local records and does not clear
  Firestore persistence or delete pending writes. Sign-out is blocked while
  this account has any pending uploads, including those that require review.
- Audits saved before this implementation have no company snapshot. They remain
  pending or local-only and are never automatically assigned to the currently
  selected company. Audits saved before the first verified company lookup also
  remain pending without a route. On the Scan screen, the officer can explicitly
  assign their own unassigned audits after an online company verification. Rows
  belonging to `legacy_user` are excluded because their ownership is unknown.
  A server-confirmed change or loss of membership marks stamped pending audits
  `FAILED_REJECTED` instead of rerouting them. A permission-denied response for
  an individual stamped upload does the same; transient errors retry. Rejected
  audits no longer block sign-out. Unstamped pending audits still need claiming.
  If membership cannot be verified (for example while offline), pending rows can
  still block sign-out. A lost upload acknowledgement can mean a rejected audit
  already exists in the cloud; local cleanup does not delete cloud records.
- Settings offers a separately confirmed Clear Trapped Local Data action when
  applicable. It removes device-wide unassigned `legacy_user` audits and this
  signed-in account's rejected audits, plus safely deletable, unshared local
  image files. It never removes other accounts' rejected audits. Shared or unsafe
  image paths are retained and reported; an interruption after the Room commit
  can leave orphaned files. Sign-out itself never performs this cleanup.
- Team activity displays server-confirmed results only. It deliberately hides
  cached feed results while offline to avoid exposing Firestore's shared disk
  cache after an account switch. Other local features remain offline-first.

## Before production

Run the rules emulator tests in `tests/README.md`, build the Android app, and
exercise two users in two companies, sign-out and re-entry, offline capture,
process death after write, membership revocation and company changes. Inspect
pending/error behavior and the Room migration on an existing device. Cached
Firestore data already on a device cannot be remotely erased immediately. The
rules validate top-level fields but cannot fully validate arbitrary nested
medicine lists; use a trusted ingestion service for stronger validation.
