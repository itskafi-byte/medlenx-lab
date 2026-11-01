# Firestore rules tests

These tests require Node.js, Firebase CLI and a Firestore emulator. Install the
Firebase CLI separately, and from this directory run `npm install --no-save
firebase @firebase/rules-unit-testing`, then from `android/firebase` run:

```
firebase emulators:exec --only firestore --project demo-medlenx --config firebase.test.json "node --test tests/security.test.mjs"
```

They exercise the proposed rules only. They do not test the Android client,
offline queue, device cache, or sign-out flow. No tests have been run here.
