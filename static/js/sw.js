const CACHE_NAME = 'medlenx-lab-v3';
const urlsToCache = [
  '/',
  '/manifest.json',
];

self.addEventListener('install', event => {
  event.waitUntil(
    caches.open(CACHE_NAME).then(cache => cache.addAll(urlsToCache)).catch(() => {})
  );
  self.skipWaiting();
});

self.addEventListener('activate', event => {
  event.waitUntil(
    caches.keys().then(keys => Promise.all(
      keys.filter(k => k !== CACHE_NAME).map(k => caches.delete(k))
    ))
  );
  self.clients.claim();
});

self.addEventListener('fetch', event => {
  const url = event.request.url;
  if (url.includes('/api/') || event.request.method !== 'GET') {
    event.respondWith(fetch(event.request).catch(() => caches.match(event.request)));
    return;
  }
  event.respondWith(
    caches.match(event.request).then(response => response || fetch(event.request).then(res => {
      const copy = res.clone();
      caches.open(CACHE_NAME).then(cache => cache.put(event.request, copy)).catch(() => {});
      return res;
    }).catch(() => response))
  );
});

self.addEventListener('sync', event => {
  if (event.tag === 'sync-prescriptions') {
    event.waitUntil(syncOfflinePrescriptions());
  }
});

async function syncOfflinePrescriptions() {
  console.log('Syncing offline prescriptions...');
}
