#!/usr/bin/env python3
"""Static guards for leaderboard search and the Analytics dashboard's capture-free layout."""
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
assert 'private fun HeroBanner' not in ui and 'Prescription Capture' not in ui and 'onCapture' not in ui
screen = ui[ui.index('fun AnalyticsScreen('):ui.index('SectionHeader(title = "Top Summary KPIs"')]
assert 'FilterBar(' in screen and '.verticalScroll(scrollState)' in screen
assert 'analyticsImagePicker' not in shell and 'analyticsPickerUid' not in shell
assert 'top = if (dest == Destination.Analytics) 16.dp else 20.dp' in shell
scan = (root/'ui/screens/scan/ScanScreen.kt').read_text()
assert 'ActivityResultContracts.TakePicture()' in scan and 'ActivityResultContracts.PickVisualMedia()' in scan
assert 'uri?.let(vm::onImagePicked)' in scan
print('contextualcapturecheck: search, specialty, capture-free dashboard and Scan picker PASS (static only)')
