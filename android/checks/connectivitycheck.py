#!/usr/bin/env python3
"""Static connectivity/queue guards; Android runtime tests are still required."""
from pathlib import Path
base = Path(__file__).resolve().parents[1] / 'app/src/main/java/com/medlenx/lab'
read = lambda f: (base / f).read_text()
observer = read('data/repo/NetworkConnectivityObserver.kt')
for needle in ('NET_CAPABILITY_INTERNET', 'NET_CAPABILITY_VALIDATED', 'registerDefaultNetworkCallback', 'awaitClose', 'unregisterNetworkCallback', 'onCapabilitiesChanged', 'onLost', 'distinctUntilChanged'):
    assert needle in observer, needle
vm = read('ui/screens/scan/ScanViewModel.kt')
section = vm.split('    fun analyze() {', 1)[1].split('    private suspend fun parkForReplay', 1)[0]
assert section.index('if (!networkObserver.isConnected())') < section.index('scanRepository.scan(bitmap, boxes)')
assert 'ScanProgress.QueuedOffline -> {' in section and 'parkForReplay(file)' in section
assert 'if (state.parked && FirebaseAuth.getInstance().currentUser?.uid == currentUid)' in section
assert 'Device offline. Scan saved to queue.' in section
assert 'networkObserver.observe().collect { online = it }' in read('data/repo/DeviceStateRepository.kt')
print('connectivitycheck: validated-network gate and successful-park notification PASS (static only)')
