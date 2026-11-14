import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { App } from './app/App'
import './design/global.css'
import { startErrorReporting } from './analytics'
import { applyDevFlags } from './devFlags'
import { startInstallPromptCapture } from './features/keep/installPrompt'

applyDevFlags()
startErrorReporting()
// 설치 창(beforeinstallprompt)은 앱이 뜨자마자 올 수 있다 — 화면보다 먼저 붙잡아 둔다(web-keep-flow.md §5-2).
startInstallPromptCapture()

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
