// Offline-first helper for MedLenX Lab
// Handles IndexedDB storage for prescriptions when offline

console.log('MedLenX Offline.js loaded - Offline-first PWA enabled');

function checkOnline() {
  return navigator.onLine;
}

// Save to localStorage as fallback if IndexedDB fails
function saveToLocalStorage(key, data) {
  try {
    localStorage.setItem(key, JSON.stringify(data));
    console.log('Saved to localStorage', key);
  } catch (e) {
    console.error('localStorage failed', e);
  }
}
