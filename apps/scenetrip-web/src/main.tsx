import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { App } from './app/App'
import './design/global.css'
import { captureHandoff, captureUtm, captureVisit, startErrorReporting } from './analytics'
import { afterDeferred, holdDeferred } from './app/deferred'
import { introDone, introStart } from './app/intro'
import { installNudge } from './features/keep/installNudge'
import { startInstallPromptCapture } from './features/keep/installPrompt'
import { registerServiceWorker } from './app/serviceWorker'
import { hasSession } from './state/auth'
import { isWebShellUserAgent } from './shared/webShell'

// 광고 꼬리표(utm_*)는 주소가 바뀌기 전에 세션에 적는다 — 첫 `screen_view` 보다 먼저(web-ad-landing.md §10-3-8).
captureUtm(window.location.search)
// 앱 안 브라우저에서 넘어온 익명 id(`aid`)를 이어받고 주소에서 지운다(analytics-events.md 「이벤트 보강」, MZ2AZ-586).
captureHandoff()
// 개발 서버에서만 — URL 뒷문(?dev-lang 등). 제품 번들에는 들어가지 않는다.
if (import.meta.env.DEV) (await import('./devFlags')).applyDevFlags()
// 설치 창(beforeinstallprompt)은 앱이 뜨자마자 올 수 있다 — 화면보다 먼저 붙잡아 둔다(web-keep-flow.md §5-2).
startInstallPromptCapture()

// 첫 화면(§11) — 처음 온 사람이면 덮개, `/go/{id}` 는 주소를 고친다. 덮개가 떠 있는 동안 분석·오류 추적·앱 덩어리는 미룬다(§10-3-2).
const start = introStart(window.location.pathname, window.location.search, { done: introDone(), signedIn: hasSession(), shell: isWebShellUserAgent(navigator.userAgent) })
const search = window.location.search
if (start.replaceTo !== null) window.history.replaceState(window.history.state, '', start.replaceTo)
if (start.intro !== null) holdDeferred()
// 방문 맥락(여는 방식, 재방문, 첫 화면으로 시작했나, 처음 꼬리표)을 정해 이후 모든 이벤트에 싣는다(analytics-events.md, MZ2AZ-585).
// 첫 화면 미루기 뒤에 — 먼저 부르면 재방문 이벤트가 덮개 동안 분석 라이브러리를 받게 한다(MZ2AZ-586). 주소는 고치기 전의 것을 쓴다.
// 다시 온 사람의 새 세션이면 홈 화면 추가를 권한다(web-home-install.md §1, MZ2AZ-589).
if (captureVisit(start.intro !== null, search)) installNudge.request('return_visit')
// 크롬이 홈 화면 설치 창을 주게 최소 서비스 워커를 등록한다 — 운영 빌드에서만(§3).
registerServiceWorker({ enabled: import.meta.env.PROD })
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
