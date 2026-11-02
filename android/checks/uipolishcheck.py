#!/usr/bin/env python3
"""Static data-binding and layout regressions; not a Compose/device test."""
from pathlib import Path
root = Path(__file__).resolve().parents[1] / 'app/src/main/java/com/medlenx/lab'
ui = (root/'ui/screens/analytics/AnalyticsScreen.kt').read_text()
vm = (root/'ui/screens/analytics/AnalyticsViewModel.kt').read_text()
chart = (root/'ui/screens/analytics/AnalyticsCharts.kt').read_text()
card = (root/'ui/components/AuditFeedCard.kt').read_text()
avatar = (root/'ui/components/OfficerAvatar.kt').read_text()
assert 'vm.leaders.maxOfOrNull { it.prescriptions }' in ui and '.coerceAtLeast(1)' in ui
assert '(row.rxCount.toFloat() / topDoctorCount.coerceAtLeast(1)).coerceIn(0f, 1f)' in ui
assert 'animateFloatAsState(targetValue = targetProgress' in ui
assert 'LinearProgressIndicator(progress = { progress }' in ui and '.clip(RoundedCornerShape(4.dp))' in ui
assert 'leaderTopVolume' not in vm
# Verify representative expected ratios independently of Compose.
def ratio(value, rows):
    maximum = max(1, max(rows, default=0))
    return min(1, max(0, value / maximum))
assert [ratio(n, [10, 4, 0]) for n in [10, 4, 0]] == [1, .4, 0]
assert ratio(0, []) == 0
section = chart[chart.index('fun SpecialtyGroupedBarChart'):]
assert 'rememberTextMeasurer()' in section and 'labelWidth' in section
assert 'Modifier.width(available * fraction).height(16.dp)' in section
assert 'Spacer(Modifier.width(8.dp))' in section and 'Arrangement.SpaceBetween' not in section
assert 'Arrangement.spacedBy(4.dp)' in section
assert 'fallbackName = rx.officerName' in ui and 'CircleCropTransformation()' in avatar
assert 'URLEncoder.encode(it, "UTF-8")' in avatar and '&background=random&bold=true' in avatar
assert '!imageUri.isNullOrBlank() && !photoFailed' in avatar and 'resource != null' in avatar
assert 'profileDao.observe(currentUid)' in vm and 'currentCompanyName' in ui
assert 'manufacturer.isNotEmpty() && company.isNotEmpty() && manufacturer.equals(company, ignoreCase = true)' in ui
assert 'primaryContainer' in card and 'onPrimaryContainer' in card and 'surfaceVariant' in card
print('uipolishcheck: progress, chart geometry, avatar fallback and own-brand binding PASS (static only)')
