/* MoonCorn service worker — installable PWA shell (APK-wrapper future-proofing).
   Strategy: network-first for navigation with offline fallback, cache-first for
   the CDN libraries (mqtt, hls.js, SoundCloud API) so repeat opens are instant.
   NEVER cache media streams or API responses — only the app shell. */
const CACHE = 'mooncorn-shell-v1';
const SHELL = [
  './',
  './index.html',
  './manifest.json'
];
const CDN = [
  'https://unpkg.com/mqtt@5.3.5/dist/mqtt.min.js',
  'https://cdn.jsdelivr.net/npm/hls.js@1.5.10/dist/hls.min.js',
  'https://w.soundcloud.com/player/api.js',
  'https://www.youtube.com/iframe_api'
];

self.addEventListener('install', (e) => {
  e.waitUntil(
    caches.open(CACHE).then(async (c) => {
      await c.addAll(SHELL);
      await Promise.allSettled(CDN.map(u => c.add(u)));
    }).then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', (e) => {
  e.waitUntil(
    caches.keys().then(keys =>
      Promise.all(keys.filter(k => k !== CACHE).map(k => caches.delete(k)))
    ).then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', (e) => {
  const url = new URL(e.request.url);
  // Only handle GET; never touch media/broker/stream traffic.
  if (e.request.method !== 'GET') return;
  if (url.pathname.startsWith('/mooncorn/')) {
    // App shell: network-first, cache fallback (keeps deploys fresh, works offline)
    if (e.request.mode === 'navigate' || url.pathname.endsWith('.html') || url.pathname.endsWith('.json')) {
      e.respondWith(
        fetch(e.request)
          .then(res => {
            const copy = res.clone();
            caches.open(CACHE).then(c => c.put(e.request, copy));
            return res;
          })
          .catch(() => caches.match(e.request).then(hit => hit || caches.match('./index.html')))
      );
    }
    // CDN libraries: cache-first (pinned versions above)
    if (CDN.some(u => url.href === u)) {
      e.respondWith(
        caches.match(e.request).then(hit => hit || fetch(e.request).then(res => {
          const copy = res.clone();
          caches.open(CACHE).then(c => c.put(e.request, copy));
          return res;
        }))
      );
    }
  }
});

// Media Session passthrough: keep the OS media card alive for audio/video playback
// (lock-screen play/pause like Brave background play, per MoonCorn docs).
self.addEventListener('message', (e) => {
  if (e.data && e.data.type === 'MEDIA_SESSION') {
    self.clients.matchAll().then(cs => cs.forEach(c => c.postMessage(e.data)));
  }
});
