#!/usr/bin/env python3
"""SQLite alias migration and export-query regressions; no Android runtime required."""
from pathlib import Path
import json, re, sqlite3
app = Path(__file__).resolve().parents[1] / 'app/src/main'
root = app / 'java/com/medlenx/lab'
seed = json.loads((app/'assets/manufacturer_aliases.json').read_text())
assert len(seed) == 10
aliases = {}
for item in seed:
    for name in item['aliases'] + [item['canonical']]:
        key = name.strip().lower()
        assert key not in aliases or aliases[key] == item['canonical']
        aliases[key] = item['canonical']
db = sqlite3.connect(':memory:')
db.executescript('''
CREATE TABLE manufacturer_aliases(alias_string TEXT NOT NULL COLLATE NOCASE PRIMARY KEY, canonical_company_id TEXT NOT NULL);
CREATE TABLE prescriptions(id INTEGER PRIMARY KEY, user_id TEXT, created_at INTEGER, doctor_name TEXT, doctor_specialty TEXT);
CREATE TABLE scanned_medicines(id INTEGER PRIMARY KEY, prescription_id INTEGER, line_number INTEGER, company_name TEXT, brand_name TEXT, generic TEXT);
INSERT INTO prescriptions VALUES (1,'alice',100,'Dr A','Cardiology'),(2,'bob',200,'Dr B','Surgery'),(3,'alice',300,'Dr C','');
INSERT INTO scanned_medicines VALUES (1,1,1,'  aCmE LaB  ','A, "B"',''),(2,1,2,'Unknown Co','X',''),(3,2,1,'Square','Y','');
ALTER TABLE scanned_medicines ADD COLUMN raw_company_name TEXT;
''')
db.executemany('INSERT INTO manufacturer_aliases VALUES (?,?)', aliases.items())
db.execute('UPDATE scanned_medicines SET raw_company_name=company_name')
db.execute('''UPDATE scanned_medicines SET company_name = (SELECT canonical_company_id FROM manufacturer_aliases
 WHERE alias_string = TRIM(scanned_medicines.company_name) COLLATE NOCASE)
 WHERE EXISTS (SELECT 1 FROM manufacturer_aliases WHERE alias_string = TRIM(scanned_medicines.company_name) COLLATE NOCASE)''')
assert db.execute('SELECT company_name, raw_company_name FROM scanned_medicines ORDER BY id').fetchall() == [
 ('ACME Laboratories Ltd.', '  aCmE LaB  '), ('Unknown Co','Unknown Co'), ('Square Pharmaceuticals PLC','Square')]
assert db.execute('SELECT canonical_company_id FROM manufacturer_aliases WHERE alias_string = TRIM(?) COLLATE NOCASE', ('sK+f',)).fetchone()[0] == 'Eskayef Pharmaceuticals Ltd.'
assert db.execute('SELECT canonical_company_id FROM manufacturer_aliases WHERE alias_string = TRIM(?) COLLATE NOCASE', ('%',)).fetchone() is None
dao = (root/'data/local/Daos.kt').read_text()
query = re.search(r'@Query\("""(.*?)"""\)\s*suspend fun scanExportRows', dao, re.S).group(1)
# Find the last Query marker if earlier multiline declarations precede this query.
query = query.split('@Query("""')[-1]
rows = db.execute(query, {'currentUid':'alice'}).fetchall()
assert len(rows) == 3 and rows[0][1] == 'Dr C' and rows[1][3] == 'A, "B"'
assert all(row[1] != 'Dr B' for row in rows)
migration = (root/'data/local/Migrations.kt').read_text()
repository = (root/'data/repo/ScanRepository.kt').read_text()
restore = (root/'data/sync/RestoreHistoryWorker.kt').read_text()
export = (root/'data/export/AnalyticsExportManager.kt').read_text()
shell = (root/'ui/shell/MedLenXShell.kt').read_text()
assert 'ManufacturerAliases.seed(context, db)' in migration and 'raw_company_name = company_name' in migration
assert 'manufacturerAliasDao().canonicalFor(originalCompany)' in repository
assert 'rawCompanyName = original' in restore and 'manufacturerAliasDao().canonicalFor(it)' in restore
assert all(x in export for x in ('PdfDocument()', 'scanExportRows(uid)', 'Intent.ACTION_SEND', 'FLAG_GRANT_READ_URI_PERMISSION', 'PyCsv.row', 'avatarResource', 'Active Doctors'))
assert 'onExportCsv =' in shell and 'onExportPdf =' in shell
assert '<cache-path name="reports" path="reports/"' in (app/'res/xml/file_paths.xml').read_text()
print('aliasexportcheck: seed, preservation, exact matching, UID-scoped exports and sharing wiring PASS')
