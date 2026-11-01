#!/usr/bin/env python3
"""Exercise actual rejection and purge DAO SQL with account/child isolation."""
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
INSERT INTO prescriptions VALUES (1,'a','old','x','PENDING','/a'),(2,'a','','y','PENDING','/b'),(3,'b','old','z','FAILED_REJECTED','/c'),(4,'legacy_user','','','LOCAL_ONLY','/d'),(5,'a','old','w','UPLOADED','/e');
INSERT INTO scanned_medicines VALUES(1),(3),(4); INSERT INTO doctor_visits VALUES(1),(3),(4);''')
mark=query('markRejected')
assert db.execute(mark,dict(currentUid='b',id=1,company='old',scanId='x')).rowcount == 0
assert db.execute(mark,dict(currentUid='a',id=1,company='wrong',scanId='x')).rowcount == 0
assert db.execute(mark,dict(currentUid='a',id=1,company='old',scanId='x')).rowcount == 1
assert db.execute(mark,dict(currentUid='a',id=1,company='old',scanId='x')).rowcount == 0
assert db.execute(query('pendingUploadCount'),dict(currentUid='a')).fetchone()[0] == 1
assert db.execute(query('observeTrappedCount'),dict(currentUid='a')).fetchone()[0] == 2
ids=[r[0] for r in db.execute(query('trappedAudits'),dict(currentUid='a')).fetchall()]
assert ids == [1,4]
for name in ('deleteMedicinesForAudits','deleteVisitsForAudits'):
    db.execute(query(name).replace(':ids', ','.join('?' for _ in ids)),ids)
q=query('deleteTrappedAudits').replace(':ids', ':id1,:id2')
assert db.execute(q,dict(currentUid='a',id1=1,id2=4)).rowcount == 2
assert db.execute('SELECT id FROM prescriptions ORDER BY id').fetchall() == [(2,),(3,),(5,)]
assert db.execute('SELECT prescription_id FROM scanned_medicines').fetchall() == [(3,)]
assert db.execute('SELECT prescription_id FROM doctor_visits').fetchall() == [(3,)]
print('rejectedcheck: guarded transition, pending count, scoped purge and child cleanup PASS')
