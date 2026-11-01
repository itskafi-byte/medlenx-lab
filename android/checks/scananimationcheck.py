#!/usr/bin/env python3
"""Static regression guards for honest non-streaming scan presentation."""
from pathlib import Path

root = Path(__file__).resolve().parents[1] / 'app/src/main/java/com/medlenx/lab/ui/screens/scan'
vm = (root / 'ScanViewModel.kt').read_text()
screen = (root / 'ScanScreen.kt').read_text()
animation = (root / 'ScanAnimation.kt').read_text()
request = vm[vm.index('    fun analyze()'):vm.index('    private suspend fun parkForReplay')]
verify = vm[vm.index('    private suspend fun enterVerification'):vm.index('    // ---- verification navigation')]
assert 'progressIndeterminate = true' in request
assert request.index('scanRepository.scan(bytes)') < request.index('enterVerification(outcome.result)')
assert 'is ScanProgress.Failed' in request and 'phase = ScanPhase.Failed' in request
assert 'catch (e: CancellationException)' in request
assert 'scanJob?.cancel()' in vm[vm.index('    fun clear()'):]
assert verify.index('result = result, progressIndeterminate = false') < verify.index('MedicineEnricher.enrich(')
assert verify.index('MedicineEnricher.enrich(') < verify.index('progress = 0.85f') < verify.index('revealedMedicines = i') < verify.index('phase = ScanPhase.VerifyDoctor')
assert 'state.progressIndeterminate' in screen and 'LinearProgressIndicator' in screen
assert 'Illustrative stages and highlights' in screen and 'Illustrative scan animation' in screen
for text in ('Uploading image...', 'Detecting text regions...', 'Decoding handwriting...'):
    assert text in screen
assert 'Cross-referencing drug database' in verify
assert 'cards.take(revealedMedicines)' in animation and 'result.doctor.name' in animation
assert 'size.height' in animation and 'not model-provided detections' in animation
print('Scan animation static guards passed')
