#!/usr/bin/env python3
"""SQLite probes for restore migration and idempotency; runtime testing still required."""
import re, sqlite3
from pathlib import Path
root = Path(__file__).resolve().parents[1]
local = root / 'app/src/main/java/com/medlenx/lab/data/local'
migrations = (local / 'Migrations.kt').read_text()
block = migrations.split('val MIGRATION_10_11')[1].split('    /**')[0]
statements = re.findall(r'db\.execSQL\("([^"\n]+)"\)', block)
assert len(statements) == 3, statements
db = sqlite3.connect(':memory:')
db.executescript('''CREATE TABLE prescriptions (id INTEGER PRIMARY KEY, user_id TEXT NOT NULL, scan_id TEXT NOT NULL, image_path TEXT NOT NULL, sync_status TEXT NOT NULL);
CREATE TABLE scanned_medicines (prescription_id INTEGER, brand TEXT);
INSERT INTO prescriptions VALUES (1, 'a', '', '/old-1', 'LOCAL_ONLY'), (2, 'a', '', '/old-2', 'LOCAL_ONLY'),
(3, 'a', 'uuid-pending', '/my-photo', 'PENDING'), (4, 'b', 'uuid-shared', '/b-photo', 'UPLOADED');''')
for sql in statements: db.execute(sql)
assert db.execute("SELECT count(*) FROM prescriptions WHERE restore_scan_id IS NULL").fetchone()[0] == 4
source = (local / 'Daos.kt').read_text()
m = re.search(r'@Query\("([^"\n]+)"\)\s*suspend fun countByScanId\(', source)
assert m
check = m.group(1)
assert db.execute(check, {'currentUid':'a','scanId':'uuid-pending'}).fetchone()[0] == 1
assert db.execute(check, {'currentUid':'a','scanId':'uuid-shared'}).fetchone()[0] == 0
with db:
    db.execute("INSERT INTO prescriptions (user_id,scan_id,image_path,sync_status,restore_scan_id) VALUES ('a','uuid-shared','','UPLOADED','uuid-shared')")
    rid = db.execute('SELECT last_insert_rowid()').fetchone()[0]
    db.execute('INSERT INTO scanned_medicines VALUES (?,?)', (rid, 'Brand'))
    assert db.execute("INSERT OR IGNORE INTO prescriptions (user_id,scan_id,image_path,sync_status,restore_scan_id) VALUES ('a','uuid-shared','','UPLOADED','uuid-shared')").rowcount == 0
    assert db.execute("INSERT OR IGNORE INTO prescriptions (user_id,scan_id,image_path,sync_status,restore_scan_id) VALUES ('b','uuid-shared','','UPLOADED','uuid-shared')").rowcount == 1
assert db.execute("SELECT image_path,sync_status FROM prescriptions WHERE id=3").fetchone() == ('/my-photo','PENDING')
assert db.execute('SELECT count(*) FROM scanned_medicines').fetchone()[0] == 1
try:
    with db:
        db.execute("INSERT INTO prescriptions (user_id,scan_id,image_path,sync_status,restore_scan_id) VALUES ('a','rollback','','UPLOADED','rollback')")
        db.execute("INSERT INTO scanned_medicines VALUES (last_insert_rowid(),'Brand')")
        raise RuntimeError('simulate child write failure')
except RuntimeError:
    pass
assert db.execute("SELECT count(*) FROM prescriptions WHERE scan_id='rollback'").fetchone()[0] == 0
assert db.execute('SELECT count(*) FROM scanned_medicines').fetchone()[0] == 1
worker = (root/'app/src/main/java/com/medlenx/lab/data/sync/RestoreHistoryWorker.kt').read_text()
assert '.whereEqualTo("createdByUid", uid)' in worker and '.get(Source.SERVER)' in worker
assert 'TrustedMembership.refresh(applicationContext, uid)' in worker
assert 'doc.metadata.hasPendingWrites()' in worker and 'dao.countByScanId(uid, doc.id)' in worker
assert 'restoreScanId = scanId' in worker and 'syncStatus = "UPLOADED"' in worker
assert 'withTransaction' in worker and 'dao.insertMedicines' in worker
assert '@Insert(onConflict = OnConflictStrategy.IGNORE)\n    suspend fun insertRestored' in source
print('historyrestorecheck: migration, UID uniqueness, pending preservation, idempotency and wiring PASS')
