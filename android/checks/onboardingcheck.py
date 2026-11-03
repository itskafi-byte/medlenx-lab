#!/usr/bin/env python3
"""Static onboarding and company-fallback regression guards."""
from pathlib import Path
p = Path(__file__).resolve().parents[1]/'app/src/main/java/com/medlenx/lab'
gate = (p/'ui/auth/AuthGate.kt').read_text()
screen = (p/'ui/auth/CompanyOnboardingScreen.kt').read_text()
sync = (p/'data/sync/ProfileCloudSync.kt').read_text()
selection = (p/'data/local/CompanySelection.kt').read_text()
dao = (p/'data/local/Daos.kt').read_text()
analytics = (p/'ui/screens/analytics/AnalyticsViewModel.kt').read_text()
team = (p/'ui/screens/team/TeamViewModel.kt').read_text()
matcher = (p/'data/repo/MedicineMatcher.kt').read_text()
assert 'graph.profileDao.observe(currentUid)' in gate and 'CompanySelection.isAssigned(profile?.company)' in gate
assert 'CompanyOnboardingScreen(' in gate and 'CompositionLocalProvider(LocalViewModelStoreOwner provides owner)' in gate
assert 'BackHandler(enabled = true)' in screen and 'dao.canonicalCompanies()' in screen
assert 'Other (Manual Entry)' in screen and 'CompanySelection.isAssigned(company)' in screen
assert 'Source.SERVER' in sync and 'snapshot.contains("officerProfile") && remote == null' in sync
assert 'profile_sync_dirty' in sync and 'profile.copy(company = company.trim())' in sync
assert 'requireUser(context, uid)' in sync and 'profile_sync_dirty' in sync
assert 'SELECT DISTINCT canonical_company_id' in dao
assert '"unassigned"' in selection and 'value.length <= 500' in selection
assert 'else OwnCompany("", false)' in matcher
for text in (matcher, analytics, team, (p/'data/repo/AnalyticsMetrics.kt').read_text(), (p/'data/repo/TeamMetrics.kt').read_text()):
    assert 'DEFAULT_OWN_COMPANY' not in text
assert 'if (ownToken.isBlank()) "" else "%$ownToken%"' in analytics
assert 'if (ownToken.isBlank()) "" else "%$ownToken%"' in team
assert 'Square Pharmaceuticals PLC' not in analytics
print('onboardingcheck: gate, cloud safety, account scope and no guessed company PASS (static only)')
