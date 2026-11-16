/*
 * 홈 화면 추가 권유 띠의 상태 — 정본: docs/project/plans/web-home-install.md §1 (MZ2AZ-589).
 *
 *   installNudge.request('course_created')   // 띄울 때면 띄운다(installNudgeRules.shouldNudge)
 *   installNudge.dismiss() / accept()         // 띠를 내리고 7일 쉰다
 *   useInstallNudge()                         // 지금 떠 있는 띠의 이유, 없으면 null
 *
 * 브라우저에 남기는 것: localStorage `st.installNudge` = {dismissedAt, courseNudged}, sessionStorage `st.installNudge.shown`.
 * 저장소를 못 쓰는 환경(사생활 보호 창 등)에서도 던지지 않는다 — 그때는 이번 실행 동안만 기억한다.
 */
import { useSyncExternalStore } from 'react'
import { currentKeepEnv } from './installPrompt'
import { type NudgeState, type NudgeTrigger, shouldNudge } from './installNudgeRules'

const STATE_KEY = 'st.installNudge'
const SHOWN_KEY = 'st.installNudge.shown'

let visible: NudgeTrigger | null = null
/** 저장소를 못 쓸 때의 대신. */
let memoryState: NudgeState = { dismissedAt: null, courseNudged: false }
let memoryShown = false
const listeners = new Set<() => void>()

function setVisible(next: NudgeTrigger | null): void {
  if (visible === next) return
  visible = next
  listeners.forEach((l) => l())
}

function readState(): NudgeState {
  try {
    const raw = window.localStorage.getItem(STATE_KEY)
    if (raw === null) return { dismissedAt: null, courseNudged: false }
    const v = JSON.parse(raw) as Partial<NudgeState> | null
    return {
      dismissedAt: typeof v?.dismissedAt === 'number' ? v.dismissedAt : null,
      courseNudged: v?.courseNudged === true,
    }
  } catch {
    return memoryState
  }
}

function writeState(next: NudgeState): void {
  memoryState = next
  try {
    window.localStorage.setItem(STATE_KEY, JSON.stringify(next))
  } catch {
    /* 이번 실행 동안만 기억한다 */
  }
}

function readShown(): boolean {
  try {
    return window.sessionStorage.getItem(SHOWN_KEY) === '1'
  } catch {
    return memoryShown
  }
}

function writeShown(): void {
  memoryShown = true
  try {
    window.sessionStorage.setItem(SHOWN_KEY, '1')
  } catch {
    /* 이번 실행 동안만 기억한다 */
  }
}

function snooze(): void {
  setVisible(null)
  writeState({ ...readState(), dismissedAt: Date.now() })
}

export const installNudge = {
  request(trigger: NudgeTrigger): void {
    try {
      const state = readState()
      if (!shouldNudge({ env: currentKeepEnv(), trigger, now: Date.now(), state, shownThisSession: readShown() })) return
      writeShown()
      if (trigger === 'course_created') writeState({ ...state, courseNudged: true })
      setVisible(trigger)
    } catch {
      /* 권유가 앱을 깨면 안 된다 */
    }
  },
  dismiss(): void {
    snooze()
  },
  accept(): void {
    snooze()
  },
  current(): NudgeTrigger | null {
    return visible
  },
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

export function useInstallNudge(): NudgeTrigger | null {
  return useSyncExternalStore(subscribe, installNudge.current, () => null)
}
