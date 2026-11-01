#!/usr/bin/env python3
"""Exercise actual rejection and device-wide confirmed-purge DAO SQL in SQLite."""
from pathlib import Path
import re, sqlite3
s = (Path(__file__).resolve().parents[1] / 'app/src/main/java/com/medlenx/lab/data/local/Daos.kt').read_text()
def query(name):
    matches = re.findall(r'@Query\("([^"\n]+)"\)\s*(?:suspend\s+)?fun\s+' + name + r'\(', s)
    assert len(matches) == 1, (name, matches)
    return matches[0]
db = sqlite3.connect(':memory:')
db.executescript('''CREATE TABLE prescriptions(id INTEGER PRIMARY KEY, user_id TEXT, company_id TEXT, scan_id TEXT, sync_status TEXT, image_path TEXT);
CREATE TABLE scanned_medicines(prescription_id INTEGER); CREATE TABLE doctor_visits(prescription_id INTEGER);
INSERT INTO prescriptions VALUES
(1,'a','old','x','PENDING','/a'),(2,'a','','y','PENDING','/b'),
(3,'b','old','z','FAILED_REJECTED','/c'),(4,'legacy_user','','','LOCAL_ONLY','/d'),
(5,'a','old','w','UPLOADED','/e'),(6,'b','','','LOCAL_ONLY','/f'),
(7,'legacy_user','','','UPLOADED','/g'),(8,'b','','','PENDING','/h'),
(9,'b','','','UPLOADED','/i');
INSERT INTO scanned_medicines SELECT id FROM prescriptions; INSERT INTO doctor_visits SELECT id FROM prescriptions;''')
mark=query('markRejected')
assert db.execute(mark,dict(currentUid='b',id=1,company='old',scanId='x')).rowcount == 0
assert db.execute(mark,dict(currentUid='a',id=1,company='wrong',scanId='x')).rowcount == 0
assert db.execute(mark,dict(currentUid='a',id=1,company='old',scanId='x')).rowcount == 1
assert db.execute(mark,dict(currentUid='a',id=1,company='old',scanId='x')).rowcount == 0
assert db.execute(query('pendingUploadCount'),dict(currentUid='a')).fetchone()[0] == 1
assert db.execute(query('observeTrappedCount')).fetchone()[0] == 7
confirmed=[r[0] for r in db.execute(query('trappedAudits')).fetchall()]
assert confirmed == [1,2,3,4,6,7,8]
# One acknowledged row becomes ineligible; a newly-created row wasn't confirmed.
db.execute("UPDATE prescriptions SET sync_status='UPLOADED' WHERE id=8")
db.execute("INSERT INTO prescriptions VALUES(10,'a','','','PENDING','/j')")
actual=[r[0] for r in db.execute(query('trappedAudits')).fetchall() if r[0] in confirmed]
assert actual == [1,2,3,4,6,7]
for name in ('deleteMedicinesForAudits','deleteVisitsForAudits'):
    db.execute(query(name).replace(':ids', ','.join('?' for _ in actual)),actual)
q=query('deleteTrappedAudits').replace(':ids', ','.join('?' for _ in actual))
assert db.execute(q,actual).rowcount == len(actual)
assert db.execute('SELECT id FROM prescriptions ORDER BY id').fetchall() == [(5,),(8,),(9,),(10,)]
assert db.execute('SELECT prescription_id FROM scanned_medicines').fetchall() == [(5,),(8,),(9,)]
assert db.execute('SELECT prescription_id FROM doctor_visits').fetchall() == [(5,),(8,),(9,)]
root = Path(__file__).resolve().parents[1] / 'app/src/main/java/com/medlenx/lab'
worker = (root / 'data/sync/UploadScanWorker.kt').read_text()
vm = (root / 'ui/screens/settings/SettingsViewModel.kt').read_text()
screen = (root / 'ui/screens/settings/SettingsScreen.kt').read_text()
assert worker.count('.addTag(workTag(uid))') == 2
assert 'getWorkInfosByTagFlow(UploadScheduling.workTag(currentUid))' in vm
assert 'getWorkInfosForUniqueWorkFlow(UploadScheduling.periodicName(currentUid))' in vm
assert 'WorkInfo.State.FAILED' in vm and 'info.runAttemptCount >= 2' in vm
assert 'vm.syncTrouble' in screen and "EVERY account" in screen
print('rejectedcheck: rejection guards, device-wide purge, confirmation snapshot, child cleanup and status wiring PASS')
