import { useState } from 'react'
import { BrowserRouter } from 'react-router'
import { OverlayRootContext } from '@/design/overlay'
import { IntroShell } from './IntroShell'

/**
 * 모바일은 화면 가득, 데스크톱은 가운데 폰 폭(390) 컨테이너 — 시트·알림은 이 안으로 포탈된다.
 * (react-query 의 QueryClientProvider 는 쓰는 곳이 없어 뺐다 — 첫 화면 JS 를 줄인다, web-ad-landing.md §10-3-2.)
 */
export function App({ intro }: { intro: { contentId: number; fromGo: boolean } | null }) {
  const [overlayRoot, setOverlayRoot] = useState<HTMLElement | null>(null)
  return (
    <BrowserRouter>
      <div className="app-frame" ref={setOverlayRoot}>
        <OverlayRootContext.Provider value={overlayRoot}>{overlayRoot && <IntroShell initialIntro={intro} />}</OverlayRootContext.Provider>
      </div>
    </BrowserRouter>
  )
}
