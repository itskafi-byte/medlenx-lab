#!/usr/bin/env python3
"""Static data-binding and layout regressions; not a Compose/device test."""
from pathlib import Path
root = Path(__file__).resolve().parents[1] / 'app/src/main/java/com/medlenx/lab'
ui = (root/'ui/screens/analytics/AnalyticsScreen.kt').read_text()
vm = (root/'ui/screens/analytics/AnalyticsViewModel.kt').read_text()
chart = (root/'ui/screens/analytics/AnalyticsCharts.kt').read_text()
card = (root/'ui/components/AuditFeedCard.kt').read_text()
avatar = (root/'ui/components/OfficerAvatar.kt').read_text()
assert '(row.brandMatchCount ?: 0).toFloat() / MONTHLY_TARGET' in ui and 'MONTHLY_TARGET = 10f' in ui
assert 'animateFloatAsState(targetValue = targetProgress' in ui
assert 'LinearProgressIndicator(progress = { progress }' in ui and '.clip(RoundedCornerShape(4.dp))' in ui
assert 'drawStopIndicator = {}' in ui and 'strokeCap = StrokeCap.Butt' in ui
assert 'Surface(onClick = { onDoctorClick(row.name) }' in ui and 'vm.selectDoctor(doctor)' in ui
assert 'selectedDoctor' in vm and 'doctor?.doctorId' in vm
assert all(label in ui + vm for label in ('This Week', 'This Month', 'All Time', 'illustrative', 'Avg Share:', 'Competitor:'))
assert 'row.rxCount >= 20' in ui and 'row.rxCount >= 10' in ui
assert [min(1, max(0, count / 10)) for count in (0,4,10,20)] == [0, .4, 1, 1]
section = chart[chart.index('fun SpecialtyGroupedBarChart'):]
assert 'rememberTextMeasurer()' in section and 'labelWidth' in section
assert 'Modifier.width(available * fraction).height(16.dp)' in section
assert 'Spacer(Modifier.width(8.dp))' in section and 'Arrangement.SpaceBetween' not in section
assert 'Arrangement.spacedBy(4.dp)' in section
assert 'Modifier.weight(1f, fill = false)' in section and 'Color(0xFF607D8B)' in section and 'label(count, row.own + row.other)' in section
assert 'row.brandMatchCount?.toString()' in ui and ' / 10 Rx Target' in ui
assert 'period Rx / 10 monthly target' not in ui
assert 'Modifier.fillMaxWidth().padding(top = 8.dp).height(8.dp)' in ui
assert 'MaterialTheme.colorScheme.primary' in ui and 'MaterialTheme.colorScheme.surfaceVariant' in ui
live_audit = (root/'ui/screens/rx/RxAuditScreen.kt').read_text()
saved_audit = (root/'ui/screens/analytics/RxBreakdownSheet.kt').read_text()
assert 'LowConfidence("Low Conf. (<80%)")' in live_audit
assert live_audit.index('        ClinicalStrip(') < live_audit.index('        MarketShareCard(') < live_audit.index('        filtered.forEach { med ->')
assert saved_audit.index('ClinicalStrip(') < saved_audit.index('MarketShareCard(') < saved_audit.index('ItemCards(')
assert 'onExportCsv = {' in saved_audit and 'onCopyClipboard = {' in saved_audit
assert 'fallbackName = rx.officerName' in ui and 'CircleCropTransformation()' in avatar
assert 'URLEncoder.encode(it, "UTF-8")' in avatar and '&background=random&bold=true' in avatar
assert '!imageUri.isNullOrBlank() && !photoFailed' in avatar and 'resource != null' in avatar
assert 'profileDao.observe(currentUid)' in vm and 'currentCompanyName' in ui
assert 'manufacturer.isNotEmpty() && company.isNotEmpty() && manufacturer.equals(company, ignoreCase = true)' in ui
assert 'primaryContainer' in card and 'onPrimaryContainer' in card and 'surfaceVariant' in card
print('uipolishcheck: progress, chart geometry, avatar fallback and own-brand binding PASS (static only)')
