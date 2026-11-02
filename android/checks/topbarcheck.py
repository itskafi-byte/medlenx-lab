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
assert 'count == null' in bar and 'count > 0' in bar and 'Icons.Filled.CloudOff' in bar
assert 'Icons.Filled.Cloud' in bar and 'Icons.Outlined.CloudUpload' in bar
assert 'DropdownMenuItem(' not in bar and 'IconButton(onClick = onSettingsClick)' in bar
assert 'contentDescription = "Settings"' in bar and 'OfficerAvatar(imageUri = avatarUri' in bar
assert 'avatarUri = settingsVm.avatarUri' in shell
settings = (root/'ui/screens/settings/SettingsScreen.kt').read_text()
vm = (root/'ui/screens/settings/SettingsViewModel.kt').read_text()
avatar = (root/'ui/components/OfficerAvatar.kt').read_text()
assert 'ActivityResultContracts.PickVisualMedia.ImageOnly' in settings and 'vm.selectAvatar(uri)' in settings
assert 'UserCaptureFiles.key(currentUid)' in vm and 'currentUser?.uid == currentUid' in vm
assert 'takePersistableUriPermission' in vm and 'FLAG_GRANT_READ_URI_PERMISSION' in vm
assert 'putString("uri", uri.toString()).putBoolean("custom_active", true).commit()' in vm and 'avatarUri = uri.toString()' in vm
assert 'SubcomposeAsyncImage' in avatar and 'error = { AvatarFallback(size) }' in avatar
assert 'releasePersistableUriPermission' not in vm
nav = (root/'ui/navigation/Destination.kt').read_text()
assert 'listOf(Scan, Analytics, Hub, Team)' in nav and 'primaryRoutes: List<Destination> = bottomBar + Settings' in nav
assert 'Destination.primaryRoutes.forEach' in shell and 'navController.navigate(Destination.Settings.route)' in shell
home = (root/'ui/screens/scan/ScanHome.kt').read_text()
hero = home.split('private fun CaptureHero(')[1].split('private fun DailyTargetCard(')[0]
assert 'Open Camera' in hero and 'Choose File' in hero and 'queued locally' not in hero and 'saved on this device' not in hero
assert '.height(16.dp)' in home and '.background(Mlx.Cyan500)' in home
assert 'DateUtils.getRelativeTimeSpanString' in home and 'Text("Processed"' in home
assert 'statusBarsPadding()' in shell and 'WindowInsets.displayCutout' in shell and 'top = 20.dp' in shell
assert 'AnimatedVisibility(' in shell and 'onQueryChange = { query = it; searchVm.search(it) }' in shell
assert 'BackHandler(onBack = onClose)' in search and 'Icons.Filled.Close' in search and '.shadow(' in search
print('topbarcheck: UID-scoped pending count and responsive search/header wiring PASS (static only)')
