#!/usr/bin/env python3
"""Exercise the actual claim SQL against an in-memory, two-user SQLite fixture."""
from pathlib import Path
import re
import sqlite3

path = Path(__file__).resolve().parents[1] / 'app/src/main/java/com/medlenx/lab/data/local/Daos.kt'
source = path.read_text()

def sql_for(function: str) -> str:
    pattern = r'@Query\("([^"\n]+)"\)\s*(?:suspend\s+)?fun\s+' + function + r'\('
    matches = re.findall(pattern, source)
    assert len(matches) == 1, (function, len(matches))
    return matches[0]

count = sql_for('observeClaimableAudits')
update = sql_for('assignAudit')
pending = sql_for('pendingUploadCount')
db = sqlite3.connect(':memory:')
db.executescript('''
CREATE TABLE prescriptions(id INTEGER PRIMARY KEY, user_id TEXT, company_id TEXT, sync_status TEXT, scan_id TEXT);
INSERT INTO prescriptions VALUES
(1,'a','','LOCAL_ONLY',''), (2,'a','','PENDING','existing-uuid'),
(3,'a','prior-company','PENDING','prior-uuid'), (4,'b','','LOCAL_ONLY',''),
(5,'legacy_user','','LOCAL_ONLY',''), (6,'a','','UPLOADED','uploaded-uuid');
''')
assert db.execute(count, {'currentUid': 'a'}).fetchone()[0] == 2
assert db.execute(pending, {'currentUid': 'a'}).fetchone()[0] == 2
with db:
    for row_id in range(1, 7):
        db.execute(update, {'id': row_id, 'currentUid': 'a', 'company': 'approved', 'newScanId': f'new-{row_id}'})
rows = db.execute('SELECT id, user_id, company_id, sync_status, scan_id FROM prescriptions ORDER BY id').fetchall()
assert rows[0][2:] == ('approved', 'PENDING', 'new-1')
assert rows[1][2:] == ('approved', 'PENDING', 'existing-uuid')
assert rows[2][2:] == ('prior-company', 'PENDING', 'prior-uuid')
assert rows[3][2:] == ('', 'LOCAL_ONLY', '') and rows[4][2:] == ('', 'LOCAL_ONLY', '')
assert rows[5][2:] == ('', 'UPLOADED', 'uploaded-uuid')
assert db.execute(pending, {'currentUid': 'a'}).fetchone()[0] == 3
print('claimcheck: account isolation, immutable routing, UUID preservation and pending count PASS')
