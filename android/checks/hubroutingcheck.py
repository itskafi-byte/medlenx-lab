#!/usr/bin/env python3
"""Static guards for finite, exclusive Hub routing and data-aware cards."""
from pathlib import Path
root = Path(__file__).resolve().parents[1] / 'app/src/main/java/com/medlenx/lab'
screen = (root/'ui/screens/hub/HubScreen.kt').read_text()
hub = screen[screen.index('fun HubScreen('):screen.index('// ══════════════════════════════ HEALTH DAY SHEET')]
assert 'rememberSaveable { mutableIntStateOf(0) }' in hub
assert 'ScrollableTabRow(selectedTabIndex = tabIndex' in hub and 'onClick = { tabIndex = index }' in hub
assert 'Crossfade(targetState = tabs[tabIndex]' in hub and 'Modifier.weight(1f).fillMaxWidth()' in hub
for tab in ('Index','Trips','News','Jobs','HealthDays','Dgda'):
    assert f'HubTab.{tab} -> ' in hub
assert 'verticalScroll(rememberScrollState())' in hub and 'HubTab.News -> NewsTab' in hub
index = screen[screen.index('private fun DrugIndexTab'):screen.index('private fun TopCompaniesHero')]
assert index.index('MlxTextField(') < index.index('25K+ Drug Index & Search') < index.index('Total medicines')
for label in ('Total medicines','Dosage forms','Companies','DGDA registered'):
    assert f'MiniKpiTile("{label}"' in index
assert '"DGDA registered", "Unavailable"' in index
assert 'Registration is not independently verified' in index
companies = screen[screen.index('private fun TopCompaniesHero'):screen.index('private fun ProductRow')]
assert 'StatusPill(text = "${entry.brands} brands", tone = PillTone.Amber)' in companies
news = screen[screen.index('private fun NewsTab'):screen.index('// ═══════════════════════════════════════ TRIPS')]
jobs = screen[screen.index('private fun JobsTab'):screen.index('private fun JobKpi')]
assert 'LazyColumn(' in news and 'itemsIndexed(items)' in news and 'item.source' in news and 'item.publishedAt' in news
assert 'LazyColumn(' in jobs and 'items(board.jobs)' in jobs and 'vm.jobs?.source' in jobs and 'job.deadline' in jobs
assert 'FrostedIcon(' in news and 'FrostedIcon(' in jobs and 'verify availability' in jobs
trips = screen[screen.index('private fun TripsTab'):screen.index('// ═══════════════════════════════════ DRUG INDEX')]
assert '1 January 2033' in trips and '24 November 2029' in trips and 'whichever is earlier' in trips
assert 'destination-country law and licences' in trips and 'p.context' not in trips
health = screen[screen.index('private fun HealthDaysTab'):screen.index('// ═══════════════════════════════════════ JOBS')]
assert 'CalendarGrid(' in health and 'vm.openDayDetail(entry.day)' in health and 'Verify dates before planning' in health
print('hubroutingcheck: routed panels, index, lazy feeds, calendar and qualified TRIPS status PASS')
