#!/usr/bin/env python3
"""Static guards for scan viewer control wiring; device gesture QA is required."""
from pathlib import Path
root = Path(__file__).resolve().parents[1]/'app/src/main/java/com/medlenx/lab/ui/screens/scan'
viewer = (root/'PrescriptionImageViewer.kt').read_text()
geo = (root/'GeoStrip.kt').read_text()
verify = (root/'Verification.kt').read_text()
screen = (root/'ScanScreen.kt').read_text()
assert 'detectTapGestures(onDoubleTap' in viewer
assert 'event.changes.count { it.pressed } >= 2' in viewer
assert all(x in viewer for x in ('event.calculateZoom()', 'event.calculatePan()', 'event.calculateRotation()', '.clip(MlxShape.Medium)'))
assert all(x in viewer for x in ('"Zoom in" to', '"Zoom out" to', '"Rotate left" to', '"Rotate right" to', '"Change contrast" to', '"Reset image" to'))
assert 'FullscreenPrescriptionViewer(state.imageUri, transform' in screen
assert 'ViewerTools(transform) { fullscreen = true }' in screen
assert 'ViewerTools(t) { fullscreen = true }' in verify and 'ViewerTools(transform, onFullscreen)' in verify
assert 'text = "GPS"' not in geo and 'text = "GPS"' not in verify
assert geo.count('GpsIconButton(onPinGps)') == 2 and verify.count('GpsIconButton(onCaptureGps)') == 2
assert 'contentDescription = "Use GPS for location"' in geo
assert 'import androidx.compose.foundation.rememberScrollState' in screen
print('viewergesturescheck: gesture, accessible controls, fullscreen and inline GPS wiring PASS (static only)')
