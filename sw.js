const VERSION = 'docviewer-v2';
const SHELL = [
  './',
  'index.html',
  'manifest.webmanifest',
  'icon-192.png',
  'icon-512.png',
  'lib/pdf.min.js',
  'lib/pdf.worker.min.js',
  'lib/xlsx.full.min.js'
];

self.addEventListener('install', (e) => {
  e.waitUntil(caches.open(VERSION).then((c) => c.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', (e) => {
  e.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== VERSION && k !== 'shared').map((k) => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', (e) => {
  const req = e.request;
  const url = new URL(req.url);

  // 공유 시트로 넘어온 파일 받기
  if (req.method === 'POST' && url.pathname.endsWith('/share')) {
    e.respondWith((async () => {
      try {
        const fd = await req.formData();
        const f = fd.get('file');
        if (f) {
          const cache = await caches.open('shared');
          await cache.put(
            new URL('shared-file', self.registration.scope).href,
            new Response(f, { headers: {
              'x-filename': encodeURIComponent(f.name || 'shared'),
              'content-type': f.type || 'application/octet-stream'
            } })
          );
        }
      } catch (err) { /* 무시 */ }
      return Response.redirect(new URL('./?shared=1', self.registration.scope).href, 303);
    })());
    return;
  }

  if (req.method !== 'GET') return;

  // 캐시 우선, 없으면 네트워크 (앱 파일이 바뀌면 VERSION을 올리세요)
  e.respondWith(
    caches.match(req, { ignoreSearch: true }).then((hit) => hit || fetch(req))
  );
});
