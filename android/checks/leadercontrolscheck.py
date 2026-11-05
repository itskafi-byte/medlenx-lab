#!/usr/bin/env python3
"""Guard the compact leaderboard controls and their existing state bindings."""
from pathlib import Path
root = Path(__file__).resolve().parents[1] / 'app/src/main/java/com/medlenx/lab/ui/screens/analytics'
ui = (root / 'AnalyticsScreen.kt').read_text()
vm = (root / 'AnalyticsViewModel.kt').read_text()
header = ui[ui.index('var searchOpen by remember'):ui.index('Text("Trend arrows')]
assert 'Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)' in header
assert 'horizontalArrangement = Arrangement.SpaceBetween' in header
assert header.index('LeaderSpecialtyFilter(') < header.index('LeaderSortMenu(vm)') < header.index('TabRow(')
assert 'TabRow(selectedTabIndex = vm.leaderPeriod.ordinal, modifier = Modifier.fillMaxWidth())' in header
assert 'vm.updateLeaderPeriod(period)' in header and 'ScrollableTabRow' not in header
specialty = ui[ui.index('private fun LeaderSpecialtyFilter'):ui.index('private fun LeaderSortMenu')]
assert 'All Specialties' in specialty and 'vm.updateLeaderSpecialty(specialty)' in specialty
assert 'Clear specialty filter' in specialty and 'vm.updateLeaderSpecialty("")' in specialty
assert 'TextOverflow.Ellipsis' in specialty and 'weight(1f' in specialty
sort = ui[ui.index('private fun LeaderSortMenu'):ui.index('/** Rounded average item counts')]
assert 'Sort: Defend' in sort and 'Sort: Attack' in sort
assert 'LeaderSort.entries.forEach' in sort and 'vm.updateLeaderSort(sort)' in sort
assert 'Volume (Defend)' in vm and 'Opportunity (Attack)' in vm
print('leadercontrolscheck: compact controls, selection bindings and full-width periods PASS')
