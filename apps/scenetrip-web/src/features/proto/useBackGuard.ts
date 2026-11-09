/*
 * 브라우저 「뒤로」 를 한 번 붙잡는다 — 저장하지 않은 계획에서 뒤로 가면 화면이 통째로 닫혀 고친 것이 사라졌다(2026-11-09 사용자 지적).
 *
 * 켜지면 지금 주소와 같은 기록을 하나 더 쌓는다. 뒤로를 누르면 그 한 칸만 빠지므로(주소는 그대로) 다시 쌓고 `onBack` 을 부른다.
 * 쌓는 기록은 라우터의 상태(키·순번)를 그대로 복사해, 라우터는 같은 자리로 보고 화면을 바꾸지 않는다.
 * 나가기로 했으면 `release` 로 쌓은 칸을 먼저 걷어 낸 뒤 이어서 닫는다 — 그러지 않으면 닫은 뒤 「뒤로」 가 이 화면으로 되돌아온다.
 */
import { useCallback, useEffect, useRef } from 'react'

const MARK = '__protoBackGuard'

export function useBackGuard(active: boolean, onBack: () => void) {
  const latest = useRef(onBack)
  latest.current = onBack
  const mark = useRef<number | null>(null)

  useEffect(() => {
    if (!active) return
    const id = Date.now()
    const push = () => window.history.pushState({ ...(window.history.state ?? {}), [MARK]: id }, '', window.location.href)
    // 한 박자 늦게 쌓는다 — 개발 모드는 붙였다 떼고 다시 붙이는데, 그 사이에 쌓고 걷으면 걷는 「뒤로」 가 늦게 도착해 사용자가 누른 것으로 보였다.
    const timer = window.setTimeout(() => {
      mark.current = id
      push()
    }, 0)
    const onPop = () => {
      if (mark.current !== id) return
      push()
      latest.current()
    }
    window.addEventListener('popstate', onPop)
    // 탭 닫기·새로고침도 묻는다(문구는 브라우저가 정한다).
    const onUnload = (e: BeforeUnloadEvent) => e.preventDefault()
    window.addEventListener('beforeunload', onUnload)
    return () => {
      window.clearTimeout(timer)
      window.removeEventListener('popstate', onPop)
      window.removeEventListener('beforeunload', onUnload)
      if (mark.current === id) {
        mark.current = null
        if ((window.history.state as Record<string, unknown> | null)?.[MARK] === id) window.history.back()
      }
    }
  }, [active])

  /** 쌓아 둔 칸을 걷어 내고(뒤로 한 칸) 끝나면 `then`. 붙잡고 있지 않으면 바로 `then`. */
  const release = useCallback((then: () => void) => {
    const id = mark.current
    mark.current = null
    if (id === null || (window.history.state as Record<string, unknown> | null)?.[MARK] !== id) {
      then()
      return
    }
    const done = () => {
      window.removeEventListener('popstate', done)
      then()
    }
    window.addEventListener('popstate', done)
    window.history.back()
  }, [])

  return { release }
}
