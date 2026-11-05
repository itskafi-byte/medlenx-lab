#!/usr/bin/env python3
"""SQLite regression checks for local duplicate eligibility, delete and tombstones."""
from pathlib import Path
import re, sqlite3
root = Path(__file__).resolve().parents[1] / 'app/src/main/java/com/medlenx/lab'
dao = (root / 'data/local/Daos.kt').read_text()
def sql(name):
    pattern = r'@Query\(\s*(?:"""((?:(?!""").)*?)"""|"([^"\n]+)")\s*\)\s*(?:suspend\s+)?fun\s+' + name + r'\('
    found = re.findall(pattern, dao, re.S)
    assert len(found) == 1, name
    return found[0][0] or found[0][1]

db = sqlite3.connect(':memory:')
db.executescript('''
CREATE TABLE prescriptions(id INTEGER PRIMARY KEY AUTOINCREMENT, user_id TEXT NOT NULL, company_id TEXT NOT NULL, scan_id TEXT NOT NULL,
 rx_no TEXT, created_at INTEGER, duplicate_of INTEGER, doctor_name TEXT);
CREATE TABLE scanned_medicines(prescription_id INTEGER, brand_name TEXT);
CREATE TABLE doctor_visits(prescription_id INTEGER,user_id TEXT,target_id INTEGER);
CREATE TABLE doctor_targets(id INTEGER,user_id TEXT,doctor_name TEXT,specialty TEXT,monthly_target INTEGER);
INSERT INTO prescriptions(id,user_id,company_id,scan_id,rx_no,created_at,duplicate_of,doctor_name) VALUES
 (1,'alice','co','original','RX-1',5,NULL,'Dr A'),(2,'alice','co','dup','RX-2',5,1,'Dr A'),
 (3,'bob','co','other','RX-3',5,1,'Dr B');
INSERT INTO scanned_medicines VALUES(1,'Brand'),(2,'Brand'),(3,'Brand');
INSERT INTO doctor_targets VALUES(7,'alice','Dr A','General',5);
INSERT INTO doctor_visits VALUES(1,'alice',7),(2,'alice',7),(3,'bob',7);
''')
# Exercise the actual shipped migration SQL on a v12-style database.
migration = (root/'data/local/Migrations.kt').read_text().split('val MIGRATION_12_13',1)[1].split('/**',1)[0]
for stmt in re.findall(r'db\.execSQL\("([^"]+)"\)', migration): db.execute(stmt)
assert db.execute('SELECT duplicate_approved FROM prescriptions').fetchall() == [(0,), (0,), (0,)]
params = dict(currentUid='alice',start=0,end=10,since=0,until=10)
assert db.execute(sql('observeCountBetween'),params).fetchone()[0] == 1
assert db.execute(sql('observeBrandCapturedRows'),params).fetchall() == [('Brand',1)]
assert db.execute(sql('doctorTargetRows'),params).fetchone()[-1] == 1
assert db.execute(sql('approveDuplicateForTesting'),dict(currentUid='bob',id=2)).rowcount == 0
assert db.execute(sql('approveDuplicateForTesting'),dict(currentUid='alice',id=2)).rowcount == 1
assert db.execute(sql('observeCountBetween'),params).fetchone()[0] == 2
assert db.execute(sql('observeBrandCapturedRows'),params).fetchall() == [('Brand',2)]
assert db.execute(sql('doctorTargetRows'),params).fetchone()[-1] == 2
# Delete original: linked duplicate retains both its audit reference and approval.
db.execute('INSERT INTO deleted_scan_tombstones VALUES(?,?,?)',('alice','co','original'))
db.execute(sql('deleteOwnedMedicines'),dict(currentUid='alice',id=1))
db.execute(sql('deleteOwnedVisits'),dict(currentUid='alice',id=1))
assert db.execute(sql('deleteOwnedPrescription'),dict(currentUid='alice',id=1)).rowcount == 1
assert db.execute('SELECT duplicate_of,duplicate_approved FROM prescriptions WHERE id=2').fetchone() == (1,1)
assert db.execute(sql('isDeletedScan'),dict(currentUid='alice',company='co',scanId='original')).fetchone()[0] == 1
assert db.execute(sql('isDeletedScan'),dict(currentUid='bob',company='co',scanId='original')).fetchone()[0] == 0
assert db.execute(sql('isDeletedScan'),dict(currentUid='alice',company='other',scanId='original')).fetchone()[0] == 0
assert db.execute(sql('deleteOwnedPrescription'),dict(currentUid='alice',id=3)).rowcount == 0
assert db.execute('SELECT COUNT(*) FROM scanned_medicines WHERE prescription_id=3').fetchone()[0] == 1
assert db.execute(sql('doctorTargetRows'),params).fetchone()[-1] == 1
restore = (root/'data/sync/RestoreHistoryWorker.kt').read_text()
assert restore.index('db.withTransaction') < restore.index('dao.isDeletedScan') < restore.index('parse(doc, uid, company)')
actions = (root/'data/repo/AuditActions.kt').read_text()
assert actions.index('db.withTransaction') < actions.index('dao.addDeletedScanTombstone') < actions.index('dao.deleteOwnedPrescription')
assert 'delete(' not in actions.split('suspend fun delete(',1)[1].split('suspend fun approve(',1)[0].replace('deleteOwned', '') # no file deletion
assert 'duplicateApproved' in (root/'ui/screens/analytics/RxBreakdownSheet.kt').read_text()
print('auditactionscheck: migration, eligibility, approval, account isolation, tombstones and delete PASS')
