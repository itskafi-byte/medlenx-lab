#!/usr/bin/env python3
"""Static guards and actual SQLite probes; no Android build or Firestore emulator."""
import re, sqlite3
from pathlib import Path
root = Path(__file__).resolve().parents[1]
base = root/'app/src/main/java/com/medlenx/lab'
def read(p): return (base/p).read_text()
dao = read('data/local/Daos.kt')
def query(name):
    match = re.search(r'@Query\("([^"\n]+)"\)\s*suspend fun '+name+r'\(', dao)
    assert match, name
    return match.group(1)
db = sqlite3.connect(':memory:')
db.executescript('''
CREATE TABLE prescriptions(id INTEGER PRIMARY KEY, user_id TEXT, company_id TEXT, scan_id TEXT, restore_scan_id TEXT, sync_status TEXT, prescription_date TEXT);
CREATE TABLE scanned_medicines(id INTEGER PRIMARY KEY, prescription_id INTEGER, line_number INTEGER, brand_name TEXT, medex_product_id TEXT);
INSERT INTO prescriptions VALUES (1,'alice','co','orig',NULL,'UPLOADED','17/02/26'),(2,'alice','co','rest','rest','UPLOADED',NULL),(3,'bob','co','other','other','UPLOADED',NULL),(4,'alice','co','pending',NULL,'PENDING',NULL);
INSERT INTO scanned_medicines VALUES(1,1,1,'Napa','sku-1'),(2,2,1,'Napa',NULL),(3,3,1,'Napa',NULL),(4,4,1,'Napa',NULL);
''')
migration = read('data/local/Migrations.kt').split('val MIGRATION_15_16')[1].split('val MIGRATION_14_15')[0]
sql = re.search(r'db.execSQL\("([^"]+)"\)', migration).group(1)
db.execute(sql)
assert db.execute('SELECT metadata_synced FROM prescriptions').fetchall() == [(0,)]*4
params = dict(currentUid='alice', company='co', scanId='rest', lineNumber=1, brand='Napa', productId='sku-1')
assert db.execute(query('updateRestoredProductId'), params).rowcount == 1
assert db.execute(query('updateRestoredProductId'), dict(params,productId='wrong')).rowcount == 0
assert db.execute(query('updateRestoredProductId'), dict(params,scanId='other')).rowcount == 0
assert db.execute(query('updateRestoredProductId'), dict(params,scanId='pending')).rowcount == 0
assert db.execute(query('updateRestoredProductId'), dict(params,scanId='orig')).rowcount == 0
assert db.execute(query('updateRestoredPrescriptionDate'), dict(currentUid='alice',company='co',scanId='rest',date='17/02/26')).rowcount == 1
assert db.execute(query('updateRestoredPrescriptionDate'), dict(currentUid='alice',company='co',scanId='rest',date='wrong')).rowcount == 0
rows = db.execute(query('metadataBackfillCandidates'),dict(currentUid='alice',company='co',afterId=0)).fetchall()
assert [r[0] for r in rows] == [1]
assert db.execute(query('markMetadataSynced'),dict(currentUid='bob',company='co',id=1,scanId='orig')).rowcount == 0
assert db.execute(query('markMetadataSynced'),dict(currentUid='alice',company='co',id=1,scanId='orig')).rowcount == 1
assert not db.execute(query('metadataBackfillCandidates'),dict(currentUid='alice',company='co',afterId=0)).fetchall()
selector = read('ui/screens/scan/LineSelector.kt')
vm = read('ui/screens/scan/ScanViewModel.kt')
for s in ['detectDragGestures', 'inImage', 'coerceIn', 'floor(', 'ceil(', 'onAdd(rect)', 'detectTapGestures']:
    assert s in selector
for s in ['fun addManualLine', 'state.detectedLines + clipped', 'state.selectedLines + index', 'scanRepository.scan(bitmap, boxes)', 'lines-v1:']:
    assert s in vm
client = read('data/remote/MedLenXVlClient.kt')
build = (root/'app/build.gradle.kts').read_text()
assert 'com.google.genai:google-genai-kotlin:1.0.0' in build and 'com.google.ai.client.generativeai:generativeai' not in build
for s in ['Client(apiKey = apiKey, httpOptions = HttpOptions(timeout = 240_000)).use', 'Part(inlineData = Blob', 'imagePart(bitmap)', 'imagePart(crop)', 'responseSchema = resultSchema()', 'FinishReason.STOP']:
    assert s in client
cloud = read('data/sync/ScanCloudFields.kt')
upload = read('data/sync/UploadScanWorker.kt')
restore = read('data/sync/RestoreHistoryWorker.kt')
rules = (root/'firebase/firestore.rules').read_text()
for s in ['prescription_date_raw', 'medex_product_id', 'parseIds', 'LocalDate.parse', 'ZoneOffset.UTC']:
    assert s in cloud
for s in ['ScanCloudFields::medicineMap', 'ScanCloudFields.timestamp', 'firestore.runTransaction', 'markMetadataSynced', 'requireCompany = false']:
    assert s in upload
for s in ['ScanCloudFields.ids', 'ScanCloudFields.restoredDate', 'updateRestoredProductId', 'updateRestoredPrescriptionDate']:
    assert s in restore
assert "resource.data.createdByUid == request.auth.uid" in rules
assert "affectedKeys().hasOnly(['prescription_date', 'prescription_date_raw', 'medex_product_ids'])" in rules
assert "!('medex_product_ids' in resource.data)" in rules and 'allow delete: if false' in rules
print('hybridresiliencecheck: SQLite isolation/idempotency and static gesture/SDK/cloud rules PASS')
