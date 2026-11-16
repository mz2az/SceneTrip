/*
 * 최소 서비스 워커 — 크롬이 「홈 화면에 추가」 설치 창(beforeinstallprompt)을 주게 하려고 둔다(docs/project/plans/web-home-install.md §3, MZ2AZ-589).
 * 아무것도 저장(캐시)하지 않는다. 페이지 이동 요청만 그대로 네트워크로 보내고, 나머지 요청은 건드리지 않는다.
 */
self.addEventListener('install', () => {
  self.skipWaiting()
})

self.addEventListener('activate', (event) => {
  event.waitUntil(self.clients.claim())
})

self.addEventListener('fetch', (event) => {
  if (event.request.mode !== 'navigate') return
  event.respondWith(fetch(event.request))
})
