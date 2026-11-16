/*
 * 최소 서비스 워커(public/sw.js)를 등록한다 — 크롬이 홈 화면 설치 창을 주게(docs/project/plans/web-home-install.md §3, MZ2AZ-589).
 * 운영 빌드에서만 켠다. 등록이 실패해도 앱은 그대로다 — 던지지 않는다.
 */
export function registerServiceWorker(opts: { enabled: boolean; nav?: Navigator }): void {
  if (!opts.enabled) return
  try {
    const nav = opts.nav ?? (typeof navigator === 'undefined' ? undefined : navigator)
    if (!nav || !('serviceWorker' in nav)) return
    void Promise.resolve(nav.serviceWorker.register('/sw.js', { scope: '/' })).catch(() => {
      /* 등록 실패는 버린다 — 설치 창이 안 오면 메뉴 안내로 물러선다 */
    })
  } catch {
    /* 등록 실패는 버린다 */
  }
}
