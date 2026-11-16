import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { App } from './app/App'
import './design/global.css'
import { captureUtm, captureVisit, startErrorReporting } from './analytics'
import { afterDeferred, holdDeferred } from './app/deferred'
import { introDone, introStart } from './app/intro'
import { startInstallPromptCapture } from './features/keep/installPrompt'
import { hasSession } from './state/auth'

// 광고 꼬리표(utm_*)는 주소가 바뀌기 전에 세션에 적는다 — 첫 `screen_view` 보다 먼저(web-ad-landing.md §10-3-8).
captureUtm(window.location.search)
// 개발 서버에서만 — URL 뒷문(?dev-lang 등). 제품 번들에는 들어가지 않는다.
if (import.meta.env.DEV) (await import('./devFlags')).applyDevFlags()
// 설치 창(beforeinstallprompt)은 앱이 뜨자마자 올 수 있다 — 화면보다 먼저 붙잡아 둔다(web-keep-flow.md §5-2).
startInstallPromptCapture()

// 첫 화면(§11) — 처음 온 사람이면 덮개, `/go/{id}` 는 주소를 고친다. 덮개가 떠 있는 동안 분석·오류 추적·앱 덩어리는 미룬다(§10-3-2).
const start = introStart(window.location.pathname, window.location.search, { done: introDone(), signedIn: hasSession() })
// 방문 맥락(여는 방식, 재방문, 첫 화면으로 시작했나, 처음 꼬리표)을 정해 이후 모든 이벤트에 싣는다 — 주소를 고치기 전에(analytics-events.md, MZ2AZ-585).
captureVisit(start.intro !== null)
if (start.replaceTo !== null) window.history.replaceState(window.history.state, '', start.replaceTo)
if (start.intro !== null) holdDeferred()
afterDeferred(startErrorReporting)
// 탭 아이콘 — 첫 화면이면 index.html 이 붙이지 않고 남겨 둔다.
afterDeferred(() => {
  if (!document.querySelector('link[rel="icon"]')) (window as { __stIcon?: () => void }).__stIcon?.()
})

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App intro={start.intro} />
  </StrictMode>,
)
