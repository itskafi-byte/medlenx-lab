#!/usr/bin/env python3
"""Static regression guards; emulator/device tests are still required."""
from pathlib import Path
root = Path(__file__).resolve().parents[1]
sync = (root / 'app/src/main/java/com/medlenx/lab/data/sync/ProfileCloudSync.kt').read_text()
settings = (root / 'app/src/main/java/com/medlenx/lab/ui/screens/settings/SettingsViewModel.kt').read_text()
auth = (root / 'app/src/main/java/com/medlenx/lab/ui/auth/AuthGate.kt').read_text()
rules = (root / 'firebase/firestore.rules').read_text()
assert '.collection("users").document(uid).set(payload, SetOptions.merge())' in sync
assert '"companyId" to' not in sync
for key in ('company', 'employeeId', 'fullName', 'designation', 'division', 'territory', 'portfolio', 'dailyTarget', 'monthlyTarget'):
    assert '"' + key + '"' in sync and "'" + key + "'" in rules
assert 'profile_sync_dirty' in sync and 'uid in editedThisSession' in sync
assert '!snapshot.metadata.isFromCache' in sync and '!snapshot.metadata.hasPendingWrites()' in sync
assert 'ProfileCloudSync.save(app, profile, rows, target)' in settings
assert 'ProfileCloudSync.watch(context.applicationContext, currentUid)' in auth
assert "affectedKeys().hasOnly(['officerProfile', 'avatarId'])" in rules
assert 'request.auth.uid == userId' in rules
print('profilesynccheck: scoped merge, restore guards and rules contract PASS (static only)')
