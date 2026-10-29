/*
 * 길찾기 **한도 안내의 규칙** — iOS RouteTab/RouteNavLimit.swift (MZ2AZ-366 D, 계획 `app-retry.md` §5-3·§12).
 *
 * 서버는 길찾기를 1 분 창과 하루 창으로 센다(`PaidQuota` — 기본 분당 10, 하루 300). 넘으면 `429 NAVIGATION_LIMIT_REACHED` — 두 창의 `code` 가
 * 같다. 가르는 것은 `Retry-After`(그 창이 끝날 때까지의 초)뿐이다. 챗봇의 `GuideLimit` 과 같은 꼴이고 경계만 다르다.
 * 어느 쪽이든 **자동으로 다시 부르지 않고**, 그 구간을 지도 앱으로 넘길 수 있게 한다(`externalDirections.ts`).
 * 전부 값만 보는 순수 함수다. 시각은 연속 시계의 초(`uptimeSeconds`)로 밖에서 넣는다.
 */
import { tr } from '@/i18n'
import { createStore, useStore, type Store } from '@/state/storage'
import { uptimeSeconds } from '../guide/guideLimit'

export const NAV_LIMIT_CODE = 'NAVIGATION_LIMIT_REACHED'

/**
 * `Retry-After` 가 이 초 이하면 「곧 다시」, 넘으면 「오늘은 다 썼어요」. 1 분 창은 그 분이 끝날 때 풀리므로 길어야 60초다. 그보다 긴 것은 하루 창뿐이다.
 * (챗봇의 경계가 3,600초인 것은 그쪽 짧은 창이 1 시간이어서다.)
 */
export const NAV_SHORT_WINDOW_SECONDS = 60

export type NavWindow = 'short' | 'day' | 'unknown'

export function navWindow(seconds: number | null): NavWindow {
  if (seconds === null) return 'unknown'
  return seconds <= NAV_SHORT_WINDOW_SECONDS ? 'short' : 'day'
}

/** 한도에 걸린 상태 — 429 를 받은 순간에 만든다. */
export interface NavBlock {
  readonly window: NavWindow
  /** 풀리는 때 — 연속 시계의 초. 서버가 `Retry-After` 를 주지 않았으면 모른다(null) — 그때는 다시 부르는 것을 막지 않는다(풀 길이 없다). */
  readonly until: number | null
}

export const makeNavBlock = (retryAfter: number | null, now: number): NavBlock => ({
  window: navWindow(retryAfter),
  until: retryAfter === null ? null : now + Math.max(0, retryAfter),
})

export const navBlockIsOver = (b: NavBlock, now: number): boolean => b.until !== null && now >= b.until

/** 지금 부르면 뻔히 429 인가 — 그동안은 서버에 묻지 않고 바로 넘기는 단추를 보인다. */
export const navBlockStopsAsking = (b: NavBlock, now: number): boolean => b.until !== null && !navBlockIsOver(b, now)

/** 풀릴 때까지 남은 초(올림). 모르거나 이미 풀렸으면 null. */
export function navBlockSecondsLeft(b: NavBlock, now: number): number | null {
  if (b.until === null || b.until <= now) return null
  return Math.ceil(b.until - now)
}

/** 안내 한 줄. 안드로이드와 같은 문구여야 한다. */
export function navLimitMessage(window: NavWindow): string {
  switch (window) {
    case 'short':
      return tr('길찾기를 잠시 많이 썼어요. 1분 안에 다시 찾을 수 있어요. 지도 앱에서 이어서 볼 수도 있어요')
    case 'day':
      return tr('오늘은 앱 안 길찾기를 다 썼어요. 지도 앱에서 이어서 볼 수 있어요')
    case 'unknown':
      return tr('지금은 앱 안에서 길을 찾을 수 없어요. 지도 앱에서 이어서 볼 수 있어요')
  }
}

// ---------------------------------------------------------------- 상태 (NavLimitStore)

export type NavLimitState =
  | { kind: 'clear' }
  /** 걸려 있다. 풀리는 때를 알면 그때까지 서버에 묻지 않는다. */
  | { kind: 'reached'; block: NavBlock }
  /** 풀렸다 — 「이제 앱 안에서 다시 길을 찾을 수 있어요」. 사람이 「다시 시도」 를 눌러야 다시 묻는다. */
  | { kind: 'lifted' }

export interface NavLimitEnv {
  /** 연속 시계의 지금(초). 기기 시계를 돌려도 변하지 않는다. */
  now(): number
  /** 풀릴 때까지 쉰다. 취소되면 일찍 깬다. */
  sleep(seconds: number, signal: AbortSignal): Promise<void>
}

const defaultEnv: NavLimitEnv = {
  now: uptimeSeconds,
  sleep: (seconds, signal) =>
    new Promise((resolve) => {
      if (signal.aborted) return resolve()
      const t = setTimeout(done, seconds * 1000)
      function done() {
        clearTimeout(t)
        signal.removeEventListener('abort', done)
        resolve()
      }
      signal.addEventListener('abort', done)
    }),
}

/**
 * 길찾기 한도의 상태 — 앱에 하나, 계정의 것이다 (MZ2AZ-366 D). 안내(`TripSession`)는 편집 화면마다 새로 생기지만 한도는 계정에 걸린다. 다른 코스를
 * 열어도 남아 있어야 뻔히 429 인 요청을 또 보내지 않는다. 메모리에만 든다 — 새로 고치면 잊고, 다음 요청이 다시 429 를 받아 같은 안내가 뜬다.
 * 로그인·로그아웃·탈퇴 때 잊는다(`forget`).
 */
export class NavLimitStore {
  readonly store: Store<NavLimitState> = createStore<NavLimitState>({ kind: 'clear' })
  /** 계정이 바뀐 횟수 — 보낸 뒤에 계정이 바뀐 길찾기 요청의 결과는 버린다. */
  epoch = 0
  private wake: AbortController | null = null

  constructor(private readonly env: NavLimitEnv = defaultEnv) {}

  get state(): NavLimitState {
    return this.store.get()
  }

  /** 지금 부르면 뻔히 429 인가. 풀리는 때가 지났으면 먼저 푼다 — 타이머는 페이지가 멈춰 있으면 늦는다. */
  get stopsAsking(): boolean {
    this.refresh()
    const s = this.state
    return s.kind === 'reached' && navBlockStopsAsking(s.block, this.env.now())
  }

  /** 걸려 있다면 풀릴 때까지 남은 초. 요청을 보내지 않고 한도 안내를 다시 세울 때 쓴다. */
  get secondsLeft(): number | null {
    const s = this.state
    return s.kind === 'reached' ? navBlockSecondsLeft(s.block, this.env.now()) : null
  }

  /** 서버가 429 `NAVIGATION_LIMIT_REACHED` 로 답했다. */
  reach(retryAfter: number | null): void {
    this.set({ kind: 'reached', block: makeNavBlock(retryAfter, this.env.now()) })
  }

  /** 길찾기가 됐다 — 한도 안이다. */
  succeeded(): void {
    this.set({ kind: 'clear' })
  }

  /** 풀리는 때가 지났으면 푼다. */
  refresh(): void {
    const s = this.state
    if (s.kind === 'reached' && navBlockIsOver(s.block, this.env.now())) this.set({ kind: 'lifted' })
  }

  /** 계정이 바뀌었다 — 한도는 앞 계정의 것이다. 떠 있던 요청의 늦은 결과는 부른 쪽이 `epoch` 을 견줘 버린다. */
  forget(): void {
    this.epoch += 1
    this.set({ kind: 'clear' })
  }

  private set(next: NavLimitState): void {
    this.wake?.abort()
    this.wake = null
    this.store.set(next)
    if (next.kind !== 'reached' || next.block.until === null) return
    const until = next.block.until
    const wake = new AbortController()
    this.wake = wake
    void (async () => {
      // 시계가 어긋나 일찍 깨면 남은 만큼 더 쉰다.
      while (!wake.signal.aborted) {
        const left = until - this.env.now()
        if (left <= 0) break
        await this.env.sleep(left, wake.signal)
      }
      if (!wake.signal.aborted) this.refresh()
    })()
  }
}

/** 앱에 하나뿐인 길찾기 한도. */
export const sharedNavLimits = new NavLimitStore()

export function useNavLimitState(limits: NavLimitStore): NavLimitState {
  return useStore(limits.store, (s) => s)
}
