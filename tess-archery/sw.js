/* Quiver service worker - offline shell. Range day, no signal, still works. */
const CACHE = 'quiver.' + '0e69ad234f';
const SHELL = ['./', './index.html', './manifest.json', './assets/app-icon.svg',
  './assets/icon-192.png', './assets/icon-512.png', './assets/icon-maskable-512.png',
  './assets/apple-touch-icon.png', './assets/hero.jpg', './assets/emblem.png', './assets/jay.png'];

self.addEventListener('install', e => {
  e.waitUntil(caches.open(CACHE).then(c => c.addAll(SHELL)).then(() => self.skipWaiting()));
});
self.addEventListener('activate', e => {
  e.waitUntil(caches.keys().then(ks => Promise.all(ks.filter(k => k !== CACHE).map(k => caches.delete(k)))).then(() => self.clients.claim()));
});
self.addEventListener('fetch', e => {
  if (e.request.method !== 'GET') return;
  e.respondWith(
    caches.match(e.request).then(hit => hit || fetch(e.request).then(res => {
      const copy = res.clone();
      caches.open(CACHE).then(c => c.put(e.request, copy)).catch(() => {});
      return res;
    }).catch(() => caches.match('./index.html')))
  );
});
