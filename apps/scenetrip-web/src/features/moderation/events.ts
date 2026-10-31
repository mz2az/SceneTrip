/*
 * 차단·풀기가 일어났다는 앱 전체 신호 — iOS `ModerationEvents` (MZ2AZ-461).
 * 화면마다 받아 둔 리뷰·방문자 사진에 그 사람의 것이 남아 있을 수 있다(검색 탭의 촬영지 상세는 탭을 옮겨도 살아 있다).
 * 리뷰·사진을 쥔 화면은 `useModerationChange` 로 이것을 보고 방문자 사진을 비운 뒤 다시 받는다.
 */
import { useEffect, useRef } from 'react'
import { createStore, useStore } from '@/state/storage'

export interface ModerationEvents {
  /** 바뀔 때마다 오른다. */
  version(): number
  changed(): void
  subscribe(listener: () => void): () => void
}

export function createModerationEvents(): ModerationEvents {
  const store = createStore({ version: 0 })
  return {
    version: () => store.get().version,
    changed: () => store.set((s) => ({ version: s.version + 1 })),
    subscribe: (l) => store.subscribe(l),
  }
}

export const moderationEvents: ModerationEvents = createModerationEvents()

/** iOS `.onModerationChange { … }` — 어디서든 차단·풀기가 일어나면 `action` 을 부른다(처음 그릴 때는 부르지 않는다). */
export function useModerationChange(action: () => void): void {
  const ref = useRef(action)
  ref.current = action
  useEffect(() => {
    let last = moderationEvents.version()
    return moderationEvents.subscribe(() => {
      const now = moderationEvents.version()
      if (now !== last) {
        last = now
        ref.current()
      }
    })
  }, [])
}

/** 화면이 버전 숫자를 직접 읽고 싶을 때. */
export function useModerationVersion(): number {
  return useStore(
    { get: () => ({ v: moderationEvents.version() }), set: () => {}, subscribe: moderationEvents.subscribe },
    (s) => s.v,
  )
}
