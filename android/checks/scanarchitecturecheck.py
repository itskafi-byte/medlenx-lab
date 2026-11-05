#!/usr/bin/env python3
"""Static and SQLite guards for strict scan schema, ID persistence and CameraX wiring."""
from pathlib import Path
import json, re, sqlite3
root = Path(__file__).resolve().parents[1] / 'app/src/main/java/com/medlenx/lab'
read = lambda x: (root/x).read_text()
schema_src = read('data/remote/VlResponseSchema.kt')
schema = json.loads(schema_src.split('"""',2)[1])
assert schema['type'] == 'json_schema' and schema['json_schema']['strict'] is True
contract = schema['json_schema']['schema']
def properties(obj, expected):
    assert set(obj['properties']) == set(expected) == set(obj['required'])
    assert obj['additionalProperties'] is False
properties(contract, ['doctor','medicines','patient_info','meta'])
props = contract['properties']
properties(props['doctor'], ['name','bmdc_no','qualifications','hospital','department','specialty','chamber','district','upazila','territory'])
properties(props['medicines']['items'], ['brand_name','generic_name','raw_text','form','type','strength','dosage','dosage_bengali','dosage_normalized','frequency','company','confidence','bbox'])
properties(props['patient_info'], ['masked','note'])
properties(props['meta'], ['total_medicines','legibility','language_mix'])
assert props['patient_info']['type'] == ['object','null'] and props['meta']['type'] == ['object','null']
assert props['meta']['properties']['legibility']['type'] == ['number','null']
client = read('data/remote/MedLenXVlClient.kt')
assert 'put("response_format", VlResponseSchema.responseFormat)' in client
assert 'put("require_parameters", true)' in client and 'FREE_FALLBACK_MODEL' in client
assert 'parseScanJson(text)' in client and 'VlOutcome.Failure' in client
assert 'VlResponseSchema.accepts(json.parseToJsonElement(content))' in client
assert 'required.any { it !in value }' in schema_src and 'value.keys.any { it !in properties }' in schema_src
migrations = read('data/local/Migrations.kt')
stmt = re.search(r'val MIGRATION_13_14.*?db.execSQL\("([^\"]+)"\)', migrations, re.S).group(1)
db = sqlite3.connect(':memory:')
db.execute('CREATE TABLE scanned_medicines(id INTEGER PRIMARY KEY, brand_name TEXT)')
db.execute("INSERT INTO scanned_medicines VALUES(1,'Original OCR')")
db.execute(stmt)
assert db.execute('SELECT brand_name, medex_product_id FROM scanned_medicines').fetchone() == ('Original OCR', None)
db.execute("INSERT INTO scanned_medicines(brand_name, medex_product_id) VALUES('Edited','medex-42')")
assert db.execute('SELECT medex_product_id FROM scanned_medicines WHERE id=2').fetchone()[0] == 'medex-42'
assert 'version = 14' in read('data/local/MedLenXDatabase.kt') and 'Migrations.MIGRATION_13_14' in read('data/local/MedLenXDatabase.kt')
assert 'medexProductId = pick?.id' in read('data/repo/MedicineEnricher.kt')
assert 'checkNotNull(index.productById(preferredId))' in read('data/repo/MedicineEnricher.kt')
vm = read('ui/screens/scan/ScanViewModel.kt')
assert 'preferredIds = cards.map { it.medexProductId }' in vm
assert vm.count('medexProductId = product.id.takeIf') >= 2
assert 'medexProductId = null' in vm and 'medexProductId = audit?.medexProductId' in read('data/repo/ScanRepository.kt')
camera = read('ui/screens/scan/CameraViewfinder.kt')
for needle in ('PreviewView(', 'AndroidView(', 'ImageCapture.Builder()', 'bindToLifecycle(', 'capture.takePicture(executor',
               'ImagePrep.toUploadJpeg(bytes, image.imageInfo.rotationDegrees)', 'finally { image.close() }',
               'provider?.unbind(preview, capture)', 'executor.shutdown()', 'framing guide only'):
    assert needle in camera, needle
screen = read('ui/screens/scan/ScanScreen.kt')
assert 'CameraViewfinder(' in screen and 'ActivityResultContracts.TakePicture()' not in screen
assert 'ActivityResultContracts.PickVisualMedia()' in screen and 'vm.onCameraCaptured(bytes, uid)' in screen
assert 'FirebaseAuth.getInstance().currentUser?.uid != expectedUid' in vm
assert 'scanRepository.scan(bytes, alreadyPrepared = state.uploadPrepared)' in vm
print('scanarchitecturecheck: schema, migration, IDs and CameraX wiring PASS (static/SQLite only)')
