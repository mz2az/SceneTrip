/*
 * 새 판이 배포됐으면 「새로고침」 을 권한다. 화면으로 돌아올 때와 10분마다 확인한다. 「나중에」 를 누르면 그 판은 다시 묻지 않는다.
 */
import { useEffect, useRef, useState } from 'react'
import { GlassDialog } from '@/features/route/ui/GlassDialog'
import { tr } from '@/i18n'
import { currentEntry, isNewer, latestEntry } from './updateCheck'

const EVERY_MS = 10 * 60 * 1000

export function UpdatePrompt() {
  const [latest, setLatest] = useState<string | null>(null)
  const dismissed = useRef<string | null>(null)

  useEffect(() => {
    const current = currentEntry()
    if (current === null) return // 개발 서버 — 번들이 없다.
    let alive = true
    const check = async () => {
      const found = await latestEntry()
      if (alive && isNewer(current, found) && found !== dismissed.current) setLatest(found)
    }
    const onVisible = () => {
      if (document.visibilityState === 'visible') void check()
    }
    document.addEventListener('visibilitychange', onVisible)
    const timer = window.setInterval(() => void check(), EVERY_MS)
    return () => {
      alive = false
      document.removeEventListener('visibilitychange', onVisible)
      window.clearInterval(timer)
    }
  }, [])

  const later = () => {
    dismissed.current = latest
    setLatest(null)
  }
  return (
    <GlassDialog
      open={latest !== null}
      variant="alert"
      title={tr('새 버전이 나왔어요')}
      message={tr('새로고침하면 최신 버전으로 바뀌어요.')}
      buttons={[
        { label: tr('나중에'), role: 'cancel', onPress: later },
        { label: tr('새로고침'), onPress: () => window.location.reload() },
      ]}
      onClose={later}
    />
  )
}
