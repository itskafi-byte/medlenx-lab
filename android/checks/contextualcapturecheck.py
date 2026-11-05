#!/usr/bin/env python3
"""Static guards for contextual leaderboard search, filter visibility and capture routing."""
from pathlib import Path
root = Path(__file__).resolve().parents[1] / 'app/src/main/java/com/medlenx/lab'
ui = (root/'ui/screens/analytics/AnalyticsScreen.kt').read_text()
vm = (root/'ui/screens/analytics/AnalyticsViewModel.kt').read_text()
shell = (root/'ui/shell/MedLenXShell.kt').read_text()
assert 'LeaderSearchBar' not in ui
header = ui[ui.index('var searchOpen by remember'):ui.index('val doctorList =')]
assert 'Icons.Filled.Search' in header and 'AnimatedContent(targetState = searchOpen' in header
assert 'if (searchOpen) vm.updateLeaderSearch("")' in header
assert 'LeaderSpecialtyFilter(vm)' in header
specialty = ui[ui.index('private fun LeaderSpecialtyFilter'):ui.index('/** Rounded average item counts')]
assert 'primaryContainer' in specialty and 'Modifier.border(1.dp, MaterialTheme.colorScheme.primary' in specialty
assert 'if (active) TextButton(onClick = { vm.updateLeaderSpecialty("") })' in specialty
assert '!vm.leaderLoading && vm.leaderError == null && vm.leaderTotal == 0' in ui
assert '0 results for ${vm.leaderSpecialty}' in ui
assert 'leaderLoading = true' in vm and 'leaderLoading = false' in vm
hero = ui[ui.index('private fun HeroBanner'):ui.index('/** The filter summary card')]
assert '.clickable(onClick = onCapture)' in hero and 'Drop prescription or click' not in hero
assert 'rememberLauncherForActivityResult(ActivityResultContracts.GetContent())' in shell
assert 'analyticsImagePicker.launch("image/" + "*")' in shell and 'scanVm.onImagePicked(uri)' in shell
assert 'scanVm.state.phase == ScanPhase.Empty' in shell and 'currentUser?.uid == uid' in shell
print('contextualcapturecheck: header search, resettable specialty, empty state and account-guarded picker PASS (static only)')
