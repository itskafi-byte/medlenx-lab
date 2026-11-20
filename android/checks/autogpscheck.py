#!/usr/bin/env python3
"""GPS/branding static guards, plus bundled hierarchy sanity checks; no Android runtime."""
from pathlib import Path
import json
root = Path(__file__).resolve().parents[1]
base = root / 'app/src/main/java/com/medlenx/lab'
repo = (base/'data/repo/LocationRepository.kt').read_text()
geo = (base/'data/repo/Geofence.kt').read_text()
vm = (base/'ui/screens/scan/ScanViewModel.kt').read_text()
ui = (base/'ui/screens/scan/ScanScreen.kt').read_text()
for item in ('ACCESS_FINE_LOCATION', 'ACCESS_COARSE_LOCATION', 'getCurrentLocation', 'requestSingleUpdate',
             'withTimeoutOrNull', 'invokeOnCancellation', 'elapsedRealtimeNanos', 'Geocoder.GeocodeListener',
             'getFromLocation', 'resolveAddressFromGps'):
    assert item in repo, item
for item in ('resolveGeoDistrict', 'resolveHierarchy', 'territoryBase', 'geocoderDistrict'):
    assert item in geo, item
for item in ('autoGps(file)', 'locationRepository.exifFix(it)', 'locationRepository.lastKnownFix()',
             'locationRepository.currentFix()', 'manualGeoRevision', 'manuallyEditedGeo',
             'officerProfile?.territory.orEmpty()', 'geoJob?.cancel()'):
    assert item in vm, item
assert 'vm.autoGps()' in ui and 'vm.pinGps()' in ui and 'locationPermissionAsked' in ui
assets = root/'app/src/main/assets/data'
bdgeo = json.loads((assets/'bd_geo.json').read_text())
loc = json.loads((assets/'bd_locations.json').read_text())
assert 'districts' in bdgeo and 'Dhaka' in bdgeo['districts'] and 'Dhaka' in loc['districts']
assert not any(k in bdgeo for k in ('upazilas', 'territories'))
for p in (root/'app/src/main/java').rglob('*.kt'):
    for line in p.read_text().splitlines():
        if 'openrouter' in line.lower(): assert 'https://openrouter.ai/api/v1/chat/completions' in line, (p, line)
for p in (root/'app/src/main/res').rglob('*'):
    if p.is_file(): assert 'openrouter' not in p.read_text(errors='ignore').lower(), p
build = (root/'app/build.gradle.kts').read_text()
assert build.index('localProps.getProperty("MEDLENX_VL_API_KEY")') < build.index('localProps.getProperty("OPENROUTER_API_KEY")')
assert 'System.getenv("MEDLENX_VL_API_KEY")' in build and 'System.getenv("OPENROUTER_API_KEY")' in build
assert 'firstOrNull { !it.isNullOrBlank() }' in build
print('autogpscheck: GPS wiring, data limits, branding and key fallback PASS (static only)')
