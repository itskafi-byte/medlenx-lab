const CACHE_NAME = 'medlenx-lab-v2';
const urlsToCache = [
  '/',
  '/static/css/tailwind.css',
  '/manifest.json'
];

self.addEventListener('install', event => {
  event.waitUntil(
    caches.open(CACHE_NAME).then(cache => cache.addAll(urlsToCache))
  );
});

self.addEventListener('fetch', event => {
  event.respondWith(
    caches.match(event.request).then(response => {
      // Return cached or fetch, but for API always fetch
      if (event.request.url.includes('/api/')) {
        return fetch(event.request).catch(() => response);
      }
      return response || fetch(event.request);
    })
  );
});

self.addEventListener('sync', event => {
  if (event.tag === 'sync-prescriptions') {
    event.waitUntil(syncOfflinePrescriptions());
  }
});

async function syncOfflinePrescriptions() {
  // Would sync IndexedDB prescriptions when online
  console.log('Syncing offline prescriptions...');
}
