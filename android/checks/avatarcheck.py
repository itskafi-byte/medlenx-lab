#!/usr/bin/env python3
"""Static guards for account-scoped avatar storage, sync and resource mapping."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET
root = Path(__file__).resolve().parents[1]
java = root / 'app/src/main/java/com/medlenx/lab'
store = (java/'data/local/AvatarStore.kt').read_text()
sync = (java/'data/sync/AvatarCloudSync.kt').read_text()
feed = (java/'data/sync/TeamFeedRepository.kt').read_text()
ui = (java/'ui/components/OfficerAvatar.kt').read_text()
settings = (java/'ui/screens/settings/SettingsScreen.kt').read_text()
vm = (java/'ui/screens/settings/SettingsViewModel.kt').read_text()
team = (java/'ui/screens/team/TeamFeed.kt').read_text()
rules = (root/'firebase/firestore.rules').read_text()
ids = [f'avatar_{i:02}' for i in range(1, 9)]
assert re.findall(r'"(avatar_\d\d)" -> R.drawable\.avatar_\d\d', ui) == ids
for ident in ids:
    assert f'"{ident}"' in store
    tree = ET.parse(root/f'app/src/main/res/drawable/{ident}.xml')
    assert tree.getroot().tag == 'vector' and len(tree.getroot()) >= 7
    assert 'android:fillColor="none"' not in (root/f'app/src/main/res/drawable/{ident}.xml').read_text()
assert 'preferencesDataStore(name = "officer_avatars")' in store
assert 'UserCaptureFiles.key(uid)' in store and 'dirtyKey(uid)' in store
assert 'AvatarStore.select(app, currentUid, id)' in vm and 'AvatarCloudSync.schedule(app, currentUid)' in vm
assert 'FLAG_GRANT_READ_URI_PERMISSION' in vm and 'custom_active' in vm  # Existing local photo retained.
assert 'NetworkType.CONNECTED' in sync and 'ExistingWorkPolicy.APPEND_OR_REPLACE' in sync
assert 'auth.currentUser?.uid != uid' in sync and 'AvatarStore.acknowledge' in sync
assert '!value.metadata.isFromCache && !value.metadata.hasPendingWrites()' in sync
assert 'uid in edited' in sync and 'restoreIfClean' in sync
assert 'batch.set(user, mapOf("avatarId" to selected)' in sync and 'batch.set(directory, mapOf("avatarId" to selected))' in sync
assert "match /users/{userId}" in rules and 'request.auth.uid == userId' in rules
assert "match /companies/{companyId}/avatars/{userId}" in rules and 'allow read: if isMember(companyId)' in rules
assert "keys().hasOnly(['avatarId'])" in rules and 'getAfter(' in rules and "^avatar_0[1-8]$" in rules
assert 'whereIn(FieldPath.documentId(), ids)' in feed and 'createdByUid' in feed
assert 'snapshot.metadata.isFromCache' in feed and 'generation != avatarGeneration' in feed
assert 'fallbackName = scan.officer.takeIf { scan.avatarResolved' in team
assert 'https://ui-avatars.com/api/?name=$encodedName&background=random&bold=true' in ui and 'URLEncoder.encode(it, "UTF-8")' in ui
assert 'ui-avatars.com' in settings and 'Use local photo' in settings and 'vm.avatarSyncPending' in settings
assert 'FirebaseStorage' not in sync and 'FirebaseStorage' not in feed
print('avatarcheck: resource, UID-scoped storage, sync, access and fallback guards PASS (static only)')
