#!/usr/bin/env python3
"""Static UI wiring and actual UID-filtered pending-count SQL; not a UI test."""
from pathlib import Path
import re, sqlite3
root = Path(__file__).resolve().parents[1] / 'app/src/main/java/com/medlenx/lab'
dao = (root/'data/local/Daos.kt').read_text()
match = re.findall(r'@Query\("([^"\n]+)"\)\s*fun observePendingUploads\(', dao)
assert len(match) == 1
conn = sqlite3.connect(':memory:')
conn.executescript("""CREATE TABLE prescriptions(user_id TEXT, sync_status TEXT);
INSERT INTO prescriptions VALUES ('a','PENDING'),('a','FAILED_REJECTED'),('b','PENDING'),('a','LOCAL_ONLY');""")
assert conn.execute(match[0], {'currentUid':'a'}).fetchone()[0] == 1
assert conn.execute(match[0], {'currentUid':'b'}).fetchone()[0] == 1
assert conn.execute(match[0], {'currentUid':'c'}).fetchone()[0] == 0
state = (root/'data/repo/DeviceStateRepository.kt').read_text()
bar = (root/'ui/shell/MlxTopBar.kt').read_text()
shell = (root/'ui/shell/MedLenXShell.kt').read_text()
search = (root/'ui/screens/search/SearchOverlay.kt').read_text()
assert 'prescriptionDao.observePendingUploads(uid)' in state and 'it.first == uid' in state
assert 'queueDao.observeCount(uid)' in state  # Processing queue remains independent.
assert '"MedLenX Lab"' in bar and 'Icons.Filled.Search' in bar and 'OutlinedTextField' not in bar
assert 'count == null' in bar and 'PillTone.Amber' in bar and 'PillTone.Slate' in bar
assert 'statusBarsPadding()' in shell and 'WindowInsets.displayCutout' in shell and 'top = 20.dp' in shell
assert 'AnimatedVisibility(' in shell and 'onQueryChange = { query = it; searchVm.search(it) }' in shell
assert 'BackHandler(onBack = onClose)' in search and 'Icons.Filled.Close' in search and '.shadow(' in search
print('topbarcheck: UID-scoped pending count and responsive search/header wiring PASS (static only)')
