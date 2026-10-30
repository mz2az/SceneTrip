import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { useState } from 'react'
import { BrowserRouter } from 'react-router'
import { OverlayRootContext } from '@/design/overlay'
import { AppRoot } from './AppRoot'

const queryClient = new QueryClient({
  defaultOptions: { queries: { staleTime: 30_000, retry: false, refetchOnWindowFocus: false } },
})

/** 모바일은 화면 가득, 데스크톱은 가운데 폰 폭(390) 컨테이너 — 시트·알림은 이 안으로 포탈된다. */
export function App() {
  const [overlayRoot, setOverlayRoot] = useState<HTMLElement | null>(null)
  return (
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <div className="app-frame" ref={setOverlayRoot}>
          <OverlayRootContext.Provider value={overlayRoot}>{overlayRoot && <AppRoot />}</OverlayRootContext.Provider>
        </div>
      </BrowserRouter>
    </QueryClientProvider>
  )
}
