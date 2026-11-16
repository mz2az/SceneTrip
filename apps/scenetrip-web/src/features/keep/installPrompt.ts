/*
 * 브라우저 설치 창(`beforeinstallprompt`)을 붙잡아 두는 작은 모듈 — 담은 뒤 시트 ② 「홈 화면에 추가」(web-keep-flow.md §5-2).
 *
 *   startInstallPromptCapture()   // main.tsx 에서 한 번
 *   canPromptInstall()            // 지금 설치 창을 띄울 수 있나
 *   await promptInstall()         // 'accepted' | 'dismissed' | 'unavailable'
 *   useCanPromptInstall()         // 바뀌면 다시 그린다
 *   currentKeepEnv()              // 브라우저 값을 모아 keepRules.keepEnv 를 부른다
 *
 * 서비스 워커는 넣지 않았다 — 설치 창이 오지 않으면 `android_menu` 안내로 물러선다(§7).
 * 설치 창은 한 번 쓰면 다시 쓸 수 없다(브라우저 규칙). 쓰고 나면 버리고 다음 `beforeinstallprompt` 를 기다린다.
 */
import { useSyncExternalStore } from 'react'
import { type KeepEnv, keepEnv } from './keepRules'

/** 표준 DOM 타입에 아직 없다(크로미움 전용). */
interface BeforeInstallPromptEvent extends Event {
  prompt(): Promise<void>
  readonly userChoice: Promise<{ outcome: 'accepted' | 'dismissed'; platform?: string }>
}

let deferred: BeforeInstallPromptEvent | null = null
let started = false
const listeners = new Set<() => void>()

function setDeferred(next: BeforeInstallPromptEvent | null): void {
  if (deferred === next) return
  deferred = next
  listeners.forEach((l) => l())
}

/** 설치 창 이벤트를 붙잡기 시작한다. 여러 번 불러도 한 번만 붙는다. */
export function startInstallPromptCapture(): void {
  if (started || typeof window === 'undefined') return
  started = true
  window.addEventListener('beforeinstallprompt', (event) => {
    // 브라우저가 제 알림줄을 띄우지 않게 막고, 우리 단추가 누를 때 띄운다.
    event.preventDefault()
    setDeferred(event as BeforeInstallPromptEvent)
  })
  window.addEventListener('appinstalled', () => setDeferred(null))
}

export function canPromptInstall(): boolean {
  return deferred !== null
}

/** 설치 창을 띄운다. 붙잡은 것이 없으면 'unavailable'. */
export async function promptInstall(): Promise<'accepted' | 'dismissed' | 'unavailable'> {
  const event = deferred
  if (!event) return 'unavailable'
  setDeferred(null)
  try {
    await event.prompt()
    const choice = await event.userChoice
    return choice.outcome
  } catch {
    return 'unavailable'
  }
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

export function useCanPromptInstall(): boolean {
  return useSyncExternalStore(subscribe, canPromptInstall, () => false)
}

/** 홈 화면에서 연 앱(standalone)인가 — 표준 display-mode 또는 iOS 사파리의 `navigator.standalone`. */
function isStandalone(): boolean {
  try {
    if (typeof window !== 'undefined' && typeof window.matchMedia === 'function' && window.matchMedia('(display-mode: standalone)').matches) return true
  } catch {
    // matchMedia 가 없는 환경(jsdom 등)은 아니라고 본다.
  }
  return typeof navigator !== 'undefined' && (navigator as Navigator & { standalone?: boolean }).standalone === true
}

/** 지금 브라우저의 간직하기 환경(§2-1). */
export function currentKeepEnv(): KeepEnv {
  return keepEnv({
    userAgent: typeof navigator === 'undefined' ? '' : navigator.userAgent,
    standalone: isStandalone(),
    canPromptInstall: canPromptInstall(),
  })
}
