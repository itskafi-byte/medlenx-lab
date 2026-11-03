#!/usr/bin/env python3
"""SQLite behavioral tests and static guards for enterprise analytics."""
from pathlib import Path
import re, sqlite3

root = Path(__file__).resolve().parents[1] / 'app/src/main/java/com/medlenx/lab'
dao = (root / 'data/local/Daos.kt').read_text()
def query(name):
    found = re.findall(r'@Query\(\s*"""((?:(?!""").)*)"""\s*\)\s*(?:suspend\s+)?fun\s+' + name + r'\(', dao, re.S)
    assert len(found) == 1, name
    return found[0]

db = sqlite3.connect(':memory:')
db.executescript('''
CREATE TABLE prescriptions(id INTEGER PRIMARY KEY, user_id TEXT, doctor_id INTEGER, doctor_name TEXT, chamber TEXT,
 doctor_specialty TEXT, district TEXT, territory TEXT, mr_id TEXT, prescription_source TEXT, created_at INTEGER, scan_id TEXT);
CREATE TABLE doctors(id INTEGER PRIMARY KEY);
CREATE TABLE scanned_medicines(id INTEGER PRIMARY KEY, prescription_id INTEGER, company_name TEXT);
INSERT INTO doctors VALUES(1),(2),(3),(4);
INSERT INTO prescriptions VALUES
 (1,'alice',1,'High volume','', 'Cardiology','','','','Hospital',10,''),
 (2,'alice',1,'High volume','', 'Cardiology','','','','Hospital',11,''),
 (3,'alice',1,'High volume','', 'Cardiology','','','','Hospital',12,''),
 (4,'alice',2,'Low conversion','', 'Cardiology','','','','Private',13,''),
 (5,'alice',2,'Low conversion','', 'Cardiology','','','','Private',14,''),
 (6,'alice',3,'High conversion','', 'Cardiology','','','','Hospital',15,''),
 (7,'alice',3,'High conversion','', 'Cardiology','','','','Hospital',16,''),
 (8,'bob',4,'Other user','', 'Cardiology','','','','Hospital',17,''),
 (9,'alice',4,'No meds','', 'General','','','','',18,'');
INSERT INTO scanned_medicines VALUES
 (1,1,'Other'),(2,2,'Other'),(3,3,'Other'),
 (4,4,'Own'),(5,4,'Other'),(6,5,'Other'),
 (7,6,'Own'),(8,7,'Own'),(9,8,'Own');
''')
args = dict(currentUid='alice', since=0, ownLike='%Own%', ownCompany='Own', limit=10, offset=0,
            district=None, territory=None, specialty=None, mrId=None, source=None)
leaders = db.execute(query('doctorLeaderRows'), args).fetchall()
assert [r[0] for r in leaders] == ['High volume', 'High conversion', 'Low conversion', 'No meds'], leaders
assert leaders[0][5] == 3 and leaders[1][5] == leaders[2][5] == 2
assert db.execute(query('doctorLeaderRows'), dict(args, limit=1, offset=1)).fetchone()[0] == 'High conversion'
feed = query('observeAuditFeed')
params = dict(currentUid='alice', chamber='ALL', limit=3, offset=0, doctorSelected=0, doctorId=None)
first_page = db.execute(feed, params).fetchall()
assert [r[0] for r in first_page] == [9, 7, 6]  # parent events, no-med event included
assert [r[0] for r in db.execute(feed, dict(params, offset=3)).fetchall()] == [5,4,3]
assert db.execute(query('observeAuditFeedCount'), params).fetchone()[0] == 8
assert db.execute(query('observeAuditFeedCount'), dict(params, chamber='PRIVATE')).fetchone()[0] == 3
assert db.execute(query('observeAuditFeedCount'), dict(params, currentUid='bob')).fetchone()[0] == 1
assert [r[0] for r in db.execute(feed, dict(params, doctorSelected=1, doctorId=2))] == [5,4]
assert db.execute(query('observeAuditFeedCount'), dict(params, doctorSelected=1, doctorId=2)).fetchone()[0] == 2
assert db.execute(query('observeAuditFeedCount'), dict(params, currentUid='bob', doctorSelected=1, doctorId=2)).fetchone()[0] == 0
mix = db.execute(query('specialtyCompanyCounts'), args).fetchall()
assert sorted(mix) == [('Cardiology', 'Other',5), ('Cardiology','Own',3)], mix
assert db.execute(query('specialtyCompanyCounts'), dict(args, currentUid='bob')).fetchall() == [('Cardiology','Own',1)]
assert db.execute(query('specialtyCompanyCounts'), dict(args, since=16)).fetchall() == [('Cardiology','Own',1)]
# Brand matches count distinct prescriptions, not medicine rows or substring company matches.
db.executemany('INSERT INTO scanned_medicines VALUES (?,?,?)', [(10,4,'own'), (11,5,'Owned Other')])
leaders = db.execute(query('doctorLeaderRows'), args).fetchall()
by_name = {r[0]: r for r in leaders}
assert by_name['Low conversion'][5] == 2 and by_name['Low conversion'][8] == 1
assert by_name['High conversion'][5] == 2 and by_name['High conversion'][8] == 2
assert all(r[8] is None for r in db.execute(query('doctorLeaderRows'), dict(args, ownCompany='')).fetchall())

entities = (root / 'data/local/Entities.kt').read_text()
vm = (root / 'ui/screens/analytics/AnalyticsViewModel.kt').read_text()
ui = (root / 'ui/screens/analytics/AnalyticsScreen.kt').read_text()
chart = (root / 'ui/screens/analytics/AnalyticsCharts.kt').read_text()
card = (root / 'ui/components/AuditFeedCard.kt').read_text()
mapping = (root / 'ui/screens/analytics/AnalyticsMapping.kt').read_text()
assert '@Relation(parentColumn = "id", entityColumn = "prescription_id")' in entities
assert 'observeAuditFeed(currentUid' in vm and 'observeAuditFeedCount(currentUid' in vm and 'snapshotFlow' in vm
assert '(row.brandMatchCount ?: 0).toFloat() / maxBrandVolume.coerceAtLeast(1)' in ui and 'vm.selectDoctor(doctor)' in ui
assert all(icon in ui for icon in ('Icons.Outlined.DocumentScanner','Icons.Outlined.Medication','Icons.Outlined.DonutLarge','Icons.Outlined.PersonSearch'))
assert 'MedicineMatcher.sameCompany(it.company, ownCompany)' in vm and 'Other / unknown' in chart
assert 'FlowRow(' in card and 'Text("Saved"' in card and 'AuditFeedCard(' in ui
assert 'p.userId == currentUid' in mapping and 'OfficerAvatar(imageUri = rx.officerAvatarUri' in ui
print('analyticsenterprisecheck: ranking, pagination, account isolation, grouped events and company mix PASS')
