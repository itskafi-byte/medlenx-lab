// Offline-first helper for MedLenX Lab
// IndexedDB queue for rural centres with weak connectivity.

const MEDLENX_DB = 'MedLenXOfflineDB';
const MEDLENX_STORE = 'prescriptions';

console.log('MedLenX Offline.js loaded - Offline-first PWA enabled');

function checkOnline() {
  return navigator.onLine;
}

function saveToLocalStorage(key, data) {
  try {
    localStorage.setItem(key, JSON.stringify(data));
  } catch (e) {
    console.error('localStorage failed', e);
  }
}

function openOfflineDb() {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(MEDLENX_DB, 1);
    req.onupgradeneeded = (e) => {
      const db = e.target.result;
      if (!db.objectStoreNames.contains(MEDLENX_STORE)) {
        db.createObjectStore(MEDLENX_STORE, { keyPath: 'id', autoIncrement: true });
      }
    };
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

async function queueOfflineScan(imageData, meta) {
  const db = await openOfflineDb();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(MEDLENX_STORE, 'readwrite');
    tx.objectStore(MEDLENX_STORE).add({
      imageData, meta, timestamp: new Date().toISOString(), synced: false,
    });
    tx.oncomplete = () => resolve(true);
    tx.onerror = () => reject(tx.error);
  });
}

async function pendingOfflineScans() {
  const db = await openOfflineDb();
  return new Promise((resolve) => {
    const tx = db.transaction(MEDLENX_STORE, 'readonly');
    const req = tx.objectStore(MEDLENX_STORE).getAll();
    req.onsuccess = () => resolve((req.result || []).filter((x) => !x.synced));
    req.onerror = () => resolve([]);
  });
}

/** Mark a stored scan as synced once the server has accepted it. */
function markSynced(id) {
  return openOfflineDb().then((db) => new Promise((resolve) => {
    const tx = db.transaction(MEDLENX_STORE, 'readwrite');
    const store = tx.objectStore(MEDLENX_STORE);
    const get = store.get(id);
    get.onsuccess = () => {
      const item = get.result;
      if (item) {
        item.synced = true;
        item.syncedAt = new Date().toISOString();
        store.put(item);
      }
    };
    tx.oncomplete = () => resolve(true);
    tx.onerror = () => resolve(false);
  }));
}

/** Replay the offline queue against the real /api/scan endpoint.
 *  Converts the stored data-URL back to a File and posts it as multipart. */
async function flushOfflineQueue() {
  if (!navigator.onLine) return 0;
  const pending = await pendingOfflineScans();
  let synced = 0;
  for (const item of pending) {
    try {
      const dataUrl = item.imageData || '';
      if (!dataUrl.startsWith('data:image')) continue;
      const blob = await (await fetch(dataUrl)).blob();
      const file = new File([blob], 'offline-rx.jpg', { type: blob.type || 'image/jpeg' });
      const fd = new FormData();
      fd.append('file', file);
      if (item.meta) {
        Object.entries(item.meta).forEach(([k, v]) => fd.append(k, v));
      }
      const r = await fetch('/api/scan', { method: 'POST', body: fd });
      if (r.ok) {
        await markSynced(item.id);
        synced += 1;
      }
    } catch (e) {
      console.warn('Offline flush item failed', e);
    }
  }
  return synced;
}

function renderOfflineBar(count) {
  let bar = document.getElementById('offlineSyncBar');
  if (count > 0) {
    if (!bar) {
      bar = document.createElement('div');
      bar.id = 'offlineSyncBar';
      bar.className = 'fixed bottom-0 left-0 right-0 z-50 lg:left-[260px] flex items-center justify-center gap-2 px-4 py-2 bg-slate-900 text-white text-[12px] font-medium shadow-lg';
      document.body.appendChild(bar);
    }
    bar.style.display = 'flex';
    bar.innerHTML = `<i class="fas fa-cloud-arrow-up"></i> 📱 ${count} Prescription${count > 1 ? 's' : ''} Cached Offline — Will Auto-Sync on Network Connection`;
  } else if (bar) {
    bar.style.display = 'none';
  }
}

window.MedLenXOffline = {
  checkOnline, saveToLocalStorage, queueOfflineScan, pendingOfflineScans,
  flushOfflineQueue, renderOfflineBar,
};
