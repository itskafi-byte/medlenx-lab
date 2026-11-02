#!/usr/bin/env python3
"""Exercise dashboard DAO SQL with real SQLite, plus static UI/flow wiring guards."""
from pathlib import Path
import re
import sqlite3
from datetime import datetime
from zoneinfo import ZoneInfo

root = Path(__file__).resolve().parents[1] / 'app/src/main/java/com/medlenx/lab'
dao = (root / 'data/local/Daos.kt').read_text()
def query(name):
    matches = re.findall(r'@Query\(\s*"""((?:(?!""").)*)"""\s*\)\s*fun\s+' + name + r'\(', dao, re.S)
    assert len(matches) == 1, name
    return matches[0]

def ms(value):
    return int(datetime.fromisoformat(value).replace(tzinfo=ZoneInfo('Asia/Dhaka')).timestamp() * 1000)

db = sqlite3.connect(':memory:')
db.executescript('''CREATE TABLE prescriptions(id INTEGER PRIMARY KEY, user_id TEXT, created_at INTEGER, doctor_name TEXT);
CREATE TABLE scanned_medicines(prescription_id INTEGER, brand_name TEXT);''')
rows = [
    (1, 'a', '2026-11-02T00:00', ' Dr A '),
    (2, 'a', '2026-11-03T12:00', 'dr a'),
    (3, 'a', '2026-11-05T12:00', 'Dr B'),
    (4, 'a', '2026-11-06T12:00', 'Dr C'),
    (5, 'a', '2026-11-08T23:59', 'Dr D'),
    (6, 'a', '2026-11-04T12:00', '  '),
    (7, 'b', '2026-11-03T12:00', 'Dr A'),
    (8, 'a', '2026-11-09T00:00', 'Dr B'),
    (9, 'a', '2026-10-31T23:59', 'Dr A'),
    (10, 'a', '2026-12-01T00:00', 'Dr A'),
]
db.executemany('INSERT INTO prescriptions VALUES (?,?,?,?)', [(i,u,ms(t),n) for i,u,t,n in rows])
db.executemany('INSERT INTO scanned_medicines VALUES (?,?)', [
    (1, ' Brand X '), (1, 'brand x'), (2, 'BRAND X'), (3, 'Brand Y'),
    (6, 'Brand Y'), (7, 'Brand X'), (9, 'Brand X'), (10, 'Brand X'),
])
week = dict(currentUid='a', since=ms('2026-11-02T00:00'), until=ms('2026-11-09T00:00'))
doctors = db.execute(query('observeDashboardTopDoctors'), week).fetchall()
assert [(n.lower(), c) for n, c in doctors] == [('dr a',2), ('dr b',1), ('dr c',1)], doctors
assert db.execute(query('observeDashboardTopDoctors'), dict(week, currentUid='b')).fetchall() == [('Dr A',1)]
month = dict(currentUid='a', since=ms('2026-11-01T00:00'), until=ms('2026-12-01T00:00'))
brands = {name.lower(): count for name,count in db.execute(query('observeDashboardBrandScans'), month)}
assert brands == {'brand x':2, 'brand y':2}, brands
assert db.execute(query('observeDashboardBrandScans'), dict(month, currentUid='c')).fetchall() == []
vm = (root / 'ui/screens/scan/ScanViewModel.kt').read_text()
ui = (root / 'ui/screens/scan/ScanHome.kt').read_text()
assert 'profileDao.observeTargets(currentUid)' in vm and 'observeDashboardBrandScans(currentUid, start, end)' in vm
assert 'observeDashboardTopDoctors(currentUid, start, end)' in vm
assert 'YearMonth.from(now)' in vm and 'now.dayOfWeek.value - 1' in vm and 'ZoneId.systemDefault()' in vm
assert 'monthJob?.cancel()' in vm and 'weekJob?.cancel()' in vm and '.take(3)' in vm
assert ui.count('MonthlyBrandTargets(vm.monthlyBrandKpis)') == 2
assert ui.count('TopDoctorsSummary(vm.weeklyTopDoctors)') == 2
assert 'Monthly Brand Targets' in ui and 'Top Doctors (This Week)' in ui and '.progressSemantics(progress)' in ui
print('dashboardcheck: UID isolation, distinct scans, dates, ranking, flow and UI wiring PASS')
