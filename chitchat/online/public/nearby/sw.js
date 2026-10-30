/* Cache only the app shell. Never store messages, names, Bluetooth data or third-party pages. */
const CACHE = 'chitchat-bluefy-v1';
const FILES = ['./', './index.html', './style.css', './app.mjs', './link.mjs', './protocol.mjs', './manifest.webmanifest', './icon.svg', './install.html'];
self.addEventListener('install', event => { event.waitUntil(caches.open(CACHE).then(cache => cache.addAll(FILES)).then(() => self.skipWaiting())); });
self.addEventListener('activate', event => { event.waitUntil(caches.keys().then(keys => Promise.all(keys.filter(key => key.startsWith('chitchat-bluefy-') && key !== CACHE).map(key => caches.delete(key)))).then(() => self.clients.claim())); });
self.addEventListener('fetch', event => {
  const request = event.request, url = new URL(request.url), root = new URL('./', self.location.href);
  if (request.method !== 'GET' || url.origin !== root.origin || !FILES.some(file => new URL(file, root).href === url.href)) return;
  event.respondWith(caches.open(CACHE).then(async cache => (await cache.match(request)) || fetch(request)));
});
