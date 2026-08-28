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

window.MedLenXOffline = {
  checkOnline, saveToLocalStorage, queueOfflineScan, pendingOfflineScans,
};
