#!/usr/bin/env python3
"""Exercise actual KPI and restore-correction DAO queries with SQLite."""
import re, sqlite3
from pathlib import Path
from datetime import datetime, timezone
root = Path(__file__).resolve().parents[1]
source = (root/'app/src/main/java/com/medlenx/lab/data/local/Daos.kt').read_text()
def sql(name):
    pattern = r'@Query\(\s*(?:"""((?:(?!""").)*?)"""|"([^"\n]+)")\s*\)\s*(?:suspend\s+)?fun\s+' + name + r'\('
    match = re.findall(pattern, source, re.S)
    assert len(match) == 1, (name, len(match))
    return match[0][0] or match[0][1]
def ms(day): return int(datetime.fromisoformat(day).replace(tzinfo=timezone.utc).timestamp() * 1000)
db = sqlite3.connect(':memory:')
db.executescript('''CREATE TABLE prescriptions(id INTEGER PRIMARY KEY, user_id TEXT, company_id TEXT, scan_id TEXT, restore_scan_id TEXT, sync_status TEXT, image_path TEXT, created_at INTEGER);
CREATE TABLE scanned_medicines(prescription_id INTEGER, brand_name TEXT);''')
rows = [
 (1,'alice','co','old', 'old','UPLOADED','',ms('2026-11-03')),
 (2,'alice','co','local',None,'PENDING','/photo',ms('2026-11-03')),
 (3,'bob','co','other','other','UPLOADED','',ms('2026-11-03')),
 (4,'alice','co','future',None,'UPLOADED','/future',ms('2026-12-01')),
 (5,'alice','co','legacy','legacy','UPLOADED','',ms('2026-11-03')),
]
db.executemany('INSERT INTO prescriptions VALUES (?,?,?,?,?,?,?,?)', rows)
db.executemany('INSERT INTO scanned_medicines VALUES (?,?)', [(1,'Brand'),(2,'Brand'),(3,'Brand'),(4,'Brand'),(5,'Other')])
update = sql('updateRestoredCaptureTime')
def correct(uid, company, scan, captured):
    return db.execute(update, dict(currentUid=uid, company=company, scanId=scan, capturedAt=ms(captured))).rowcount
# Cloud upload on Nov 3, original verified save Oct 31. Only an imported row can change.
assert correct('alice','wrong','old','2026-10-31') == 0
assert correct('bob','co','old','2026-10-31') == 0
assert correct('alice','co','local','2026-10-31') == 0
assert correct('alice','co','old','2026-10-31') == 1
assert correct('alice','co','old','2026-10-31') == 0
assert db.execute('SELECT image_path,created_at FROM prescriptions WHERE id=2').fetchone() == ('/photo',ms('2026-11-03'))
day = sql('observeCountBetween')
assert db.execute(day, dict(currentUid='alice',start=ms('2026-11-03'),end=ms('2026-11-04'))).fetchone()[0] == 2
month = sql('observeBrandCapturedRows')
assert db.execute(month, dict(currentUid='alice',since=ms('2026-11-01'),until=ms('2026-12-01'))).fetchall() == [('Brand',1),('Other',1)]
assert db.execute(month, dict(currentUid='alice',since=ms('2026-10-01'),until=ms('2026-11-01'))).fetchall() == [('Brand',1)]
worker = (root/'app/src/main/java/com/medlenx/lab/data/sync/UploadScanWorker.kt').read_text()
restore = (root/'app/src/main/java/com/medlenx/lab/data/sync/RestoreHistoryWorker.kt').read_text()
rules = (root/'firebase/firestore.rules').read_text()
settings = (root/'app/src/main/java/com/medlenx/lab/ui/screens/settings/SettingsViewModel.kt').read_text()
assert '"captureTimestamp" to Timestamp(Date(rx.createdAt))' in worker
assert '"timestamp" to FieldValue.serverTimestamp()' in worker
assert 'existing.contains("captureTimestamp")' in worker
assert 'doc.get("captureTimestamp")' in restore and 'doc.getTimestamp("timestamp")' in restore
assert 'dao.updateRestoredCaptureTime(uid, company, doc.id, captured)' in restore
assert "request.resource.data.captureTimestamp is timestamp" in rules
assert 'observeBrandCapturedRows(currentUid, start, end).collect' in settings
team = (root/'app/src/main/java/com/medlenx/lab/ui/screens/team/TeamViewModel.kt').read_text()
assert 'observeBrandCapturedRows(currentUid, since = monthStart, until = nextMonth).first()' in team
for file in (root/'app/src/main/java').rglob('*.kt'):
    assert not re.search(r'\.brandCapturedRows\s*\(', file.read_text()), file
print('capturetimecheck: guarded correction, day/month boundaries, UID isolation and payload wiring PASS')
