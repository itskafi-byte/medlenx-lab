#!/usr/bin/env python3
"""Static and SQLite guards for strict scan schema, ID persistence and CameraX wiring."""
from pathlib import Path
import json, re, sqlite3
root = Path(__file__).resolve().parents[1] / 'app/src/main/java/com/medlenx/lab'
read = lambda x: (root/x).read_text()
client = read('data/remote/MedLenXVlClient.kt')
for needle in ('modelName = "gemini-2.5-flash"', 'responseMimeType = "application/json"', 'responseSchema = resultSchema()',
               'image(bitmap)', 'image(crop)', 'medicines.size == boxes.size', 'patient_info', 'prescription_date',
               'FinishReason.STOP', 'score in 0.0..1.0', 'Schema.arr("medicines"'):
    assert needle in client, needle
assert 'OPENROUTER' not in client
locator = read('data/remote/DocumentLocator.kt')
for needle in ('TextRecognition.getClient', 'InputImage.fromBitmap(bitmap, 0)', '.flatMap { it.lines }', 'line.boundingBox'):
    assert needle in locator, needle
selector = read('ui/screens/scan/LineSelector.kt')
for needle in ('Canvas(', 'detectTapGestures', 'ContentScale.Fit', 'bitmap.width * scale', 'onToggle'):
    assert needle in selector, needle
migrations = read('data/local/Migrations.kt')
stmt = re.search(r'val MIGRATION_13_14.*?db.execSQL\("([^\"]+)"\)', migrations, re.S).group(1)
db = sqlite3.connect(':memory:')
db.execute('CREATE TABLE scanned_medicines(id INTEGER PRIMARY KEY, brand_name TEXT)')
db.execute("INSERT INTO scanned_medicines VALUES(1,'Original OCR')")
db.execute(stmt)
assert db.execute('SELECT brand_name, medex_product_id FROM scanned_medicines').fetchone() == ('Original OCR', None)
db.execute("INSERT INTO scanned_medicines(brand_name, medex_product_id) VALUES('Edited','medex-42')")
assert db.execute('SELECT medex_product_id FROM scanned_medicines WHERE id=2').fetchone()[0] == 'medex-42'
assert 'version = 15' in read('data/local/MedLenXDatabase.kt') and 'Migrations.MIGRATION_13_14' in read('data/local/MedLenXDatabase.kt')
assert 'medexProductId = pick?.id' in read('data/repo/MedicineEnricher.kt')
assert 'checkNotNull(index.productById(preferredId))' in read('data/repo/MedicineEnricher.kt')
vm = read('ui/screens/scan/ScanViewModel.kt')
assert 'preferredIds = cards.map { it.medexProductId }' in vm
assert vm.count('medexProductId = product.id.takeIf') >= 2
assert 'medexProductId = null' in vm and 'medexProductId = audit?.medexProductId' in read('data/repo/ScanRepository.kt')
camera = read('ui/screens/scan/CameraViewfinder.kt')
for needle in ('PreviewView(', 'AndroidView(', 'ImageCapture.Builder()', 'bindToLifecycle(', 'capture.takePicture(executor',
               'BitmapFactory.decodeByteArray(bytes, 0, bytes.size)', 'finally { image.close() }',
               'provider?.unbind(preview, capture)', 'executor.shutdown()', 'framing guide only'):
    assert needle in camera, needle
screen = read('ui/screens/scan/ScanScreen.kt')
assert 'CameraViewfinder(' in screen and 'ActivityResultContracts.TakePicture()' not in screen
assert 'ActivityResultContracts.PickVisualMedia()' in screen and 'vm.onCameraCaptured(bitmap, uid)' in screen
assert 'FirebaseAuth.getInstance().currentUser?.uid != expectedUid' in vm
assert 'scanRepository.scan(bitmap, boxes)' in vm

stmt15 = re.search(r'val MIGRATION_14_15.*?db.execSQL\("([^\"]+)"\)', migrations, re.S).group(1)
db.execute('CREATE TABLE prescriptions(id INTEGER PRIMARY KEY, doctor_name TEXT)')
db.execute("INSERT INTO prescriptions VALUES(1, 'Original doctor')")
db.execute(stmt15)
assert db.execute('SELECT doctor_name, prescription_date FROM prescriptions').fetchone() == ('Original doctor', None)
assert 'Migrations.MIGRATION_14_15' in read('data/local/MedLenXDatabase.kt')
assert 'prescriptionDate = doctor.prescriptionDate' in vm
assert 'return false // keep queue row' in vm and 'lines-v1:' in vm

print('scanarchitecturecheck: schema, migration, IDs and CameraX wiring PASS (static/SQLite only)')
