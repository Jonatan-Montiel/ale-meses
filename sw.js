/* Service worker de «Nuestro lugar»: solo guarda la cáscara de la página (index.html, manifest, iconos)
   para que abra sin internet. NUNCA toca api.github.com, gists, ntfy ni ningún otro dominio, ni data.bin, fotos o audios:
   todo eso sigue yendo directo a la red como siempre (el contenido ya se guarda cifrado en el propio celular). */
const V = 'nl-shell-v1';
const SCOPE = self.registration.scope;
const INDEX = new URL('index.html', SCOPE).href;
const STATIC = ['manifest.webmanifest', 'icons/icon-192.png', 'icons/icon-512.png', 'icons/apple-touch-icon.png', 'icons/maskable-512.png'].map(p => new URL(p, SCOPE).href);

self.addEventListener('install', e => {
  e.waitUntil((async () => {
    const c = await caches.open(V);
    await Promise.all([INDEX, ...STATIC].map(u => c.add(new Request(u, { cache: 'reload' })).catch(() => {})));
    await self.skipWaiting();
  })());
});

self.addEventListener('activate', e => {
  e.waitUntil((async () => {
    for (const k of await caches.keys()) if (k.startsWith('nl-shell-') && k !== V) await caches.delete(k);
    await self.clients.claim();
  })());
});

self.addEventListener('fetch', e => {
  const req = e.request;
  if (req.method !== 'GET') return;
  const url = new URL(req.url);
  if (url.origin !== self.location.origin) return;            // otros dominios (GitHub, ntfy, fuentes): ni se tocan
  if (!url.href.startsWith(SCOPE)) return;
  const isIndex = req.mode === 'navigate' || url.pathname === new URL(INDEX).pathname || url.pathname === new URL(SCOPE).pathname;
  if (isIndex) { e.respondWith(networkFirst(e, req)); return; }
  if (STATIC.includes(url.origin + url.pathname)) { e.respondWith(staleWhileRevalidate(e, req)); return; }
  // todo lo demás (data.bin, fotos, apk, sw.js…): directo a la red
});

async function networkFirst(e, req) {
  const c = await caches.open(V);
  const net = fetch(req).then(r => { if (r && r.ok && r.type === 'basic') c.put(INDEX, r.clone()); return r; });
  net.catch(() => {});
  e.waitUntil(net.catch(() => {}));
  try {
    return await Promise.race([net, new Promise((_, ko) => setTimeout(() => ko(new Error('lento')), 5000))]);
  } catch (err) {
    const hit = await c.match(INDEX);
    return hit || net;
  }
}

async function staleWhileRevalidate(e, req) {
  const c = await caches.open(V);
  const hit = await c.match(req);
  const net = fetch(req).then(r => { if (r && r.ok) c.put(req, r.clone()); return r; }).catch(() => null);
  if (hit) { e.waitUntil(net); return hit; }
  return (await net) || Response.error();
}
