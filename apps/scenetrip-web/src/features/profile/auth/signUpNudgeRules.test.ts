/*
 * MZ2AZ-564 — 비회원에게 가입을 권하는 알약의 순수 규칙. 정본: docs/project/plans/web-signup-nudge.md §2·§3·§4.
 *
 * 구현이 따라야 할 모양(계획서가 열어 둔 자리는 가장 단순하게 정했다):
 *
 *   export type NudgeTrigger = 'saved' | 'views'
 *   export interface NudgeState { detailViews: number; dismissedAt: number | null; sessionsShown: number }
 *   export interface NudgeContext {
 *     signedIn: boolean; onHome: boolean; protoOn: boolean
 *     savedPlaces: number; likedWorks: number; now: number; shownThisSession: boolean
 *   }
 *   export function nudgeVisible(state: NudgeState, ctx: NudgeContext): boolean
 *   export function nudgeMessage(savedPlaces: number, likedWorks: number, lang: Lang): string
 *   export function readNudgeState(): NudgeState                    // localStorage 'scenetrip.signupNudge', 깨지면 기본값, 던지지 않는다
 *   export function nudgeShownThisSession(): boolean                // 이번 세션(모듈이 처음 읽힌 뒤)에 markShown 이 불렸나
 *   export function countDetailView(signedIn: boolean): void        // 로그인했으면 아무 일도 없다
 *   export function markShown(trigger: NudgeTrigger): void          // 세션당 한 번만 sessionsShown+1 · signup_nudge_view
 *   export function dismiss(trigger: NudgeTrigger, now?: number): void  // dismissedAt = now(기본 Date.now()) · signup_nudge_dismiss
 *
 * 이벤트는 `@/analytics` 의 `track(webEvent.signupNudge…(trigger))` 로 보낸다(이 시험은 `@/analytics` 의 track 을 가로챈다).
 * 「세션」은 페이지를 새로 연 한 번이다 — 시험에서는 vi.resetModules() 뒤 다시 import 해서 새 세션을 흉내 낸다.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/analytics', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/analytics')>()
  return { ...actual, track: vi.fn() }
})

import type { NudgeContext, NudgeState } from './signUpNudgeRules'
import { nudgeCompact } from './signUpNudgeRules'

const KEY = 'scenetrip.signupNudge'
const DAY = 86_400_000
const THREE_DAYS = 259_200_000

/** 새 세션 — 모듈을 다시 읽어 「이번 세션에 보였나」 를 비운다. 저장소(localStorage)는 그대로다. */
async function newSession() {
  vi.resetModules()
  const nudge = await import('./signUpNudgeRules')
  const analytics = await import('@/analytics')
  const track = vi.mocked(analytics.track)
  track.mockClear()
  return { nudge, track }
}

const baseState: NudgeState = { detailViews: 0, dismissedAt: null, sessionsShown: 0 }
const baseCtx: NudgeContext = {
  signedIn: false,
  onHome: true,
  protoOn: false,
  savedPlaces: 1,
  likedWorks: 0,
  now: 10 * DAY,
  shownThisSession: false,
}

beforeEach(() => {
  localStorage.clear()
})
afterEach(() => {
  localStorage.clear()
})

describe('nudgeVisible — §2 보이는 조건(모두 참이어야 한다)', () => {
  it('기본: 비회원·홈·담은 장소 있음·닫은 적 없음·세션 0 → 보인다', async () => {
    const { nudge } = await newSession()
    expect(nudge.nudgeVisible(baseState, baseCtx)).toBe(true)
  })

  it('1. 로그인했으면 안 보인다', async () => {
    const { nudge } = await newSession()
    expect(nudge.nudgeVisible(baseState, { ...baseCtx, signedIn: true })).toBe(false)
  })

  it('2. 홈이 아니면(다른 탭·덮개) 안 보인다', async () => {
    const { nudge } = await newSession()
    expect(nudge.nudgeVisible(baseState, { ...baseCtx, onHome: false })).toBe(false)
  })

  it('2. 새 홈 화면(protoOn)이어도 보인다 — 자리만 바뀐다(계획 §1, 2026-11-13 수정)', async () => {
    const { nudge } = await newSession()
    expect(nudge.nudgeVisible(baseState, { ...baseCtx, protoOn: true })).toBe(true)
  })

  describe('3. 담은 게 있거나 상세를 3 번 이상 열었다', () => {
    it('담은 장소·찜 둘 다 0, 상세 2 번 → 안 보인다', async () => {
      const { nudge } = await newSession()
      expect(nudge.nudgeVisible({ ...baseState, detailViews: 2 }, { ...baseCtx, savedPlaces: 0, likedWorks: 0 })).toBe(false)
    })

    it('담은 것 0, 상세 정확히 3 번 → 보인다', async () => {
      const { nudge } = await newSession()
      expect(nudge.nudgeVisible({ ...baseState, detailViews: 3 }, { ...baseCtx, savedPlaces: 0, likedWorks: 0 })).toBe(true)
    })

    it('찜한 작품만 1 개 → 보인다', async () => {
      const { nudge } = await newSession()
      expect(nudge.nudgeVisible(baseState, { ...baseCtx, savedPlaces: 0, likedWorks: 1 })).toBe(true)
    })

    it('담은 장소만 1 곳 → 보인다', async () => {
      const { nudge } = await newSession()
      expect(nudge.nudgeVisible(baseState, { ...baseCtx, savedPlaces: 1, likedWorks: 0 })).toBe(true)
    })
  })

  describe('4. 닫은 지 3 일(259_200_000 ms)이 지났다', () => {
    it('닫은 적 없음(null) → 보인다', async () => {
      const { nudge } = await newSession()
      expect(nudge.nudgeVisible({ ...baseState, dismissedAt: null }, baseCtx)).toBe(true)
    })

    it('정확히 3 일 뒤 → 보인다', async () => {
      const { nudge } = await newSession()
      const now = baseCtx.now
      expect(nudge.nudgeVisible({ ...baseState, dismissedAt: now - THREE_DAYS }, { ...baseCtx, now })).toBe(true)
    })

    it('3 일에서 1 ms 모자라면 → 안 보인다', async () => {
      const { nudge } = await newSession()
      const now = baseCtx.now
      expect(nudge.nudgeVisible({ ...baseState, dismissedAt: now - THREE_DAYS + 1 }, { ...baseCtx, now })).toBe(false)
    })

    it('방금 닫았으면 → 안 보인다', async () => {
      const { nudge } = await newSession()
      expect(nudge.nudgeVisible({ ...baseState, dismissedAt: baseCtx.now }, baseCtx)).toBe(false)
    })
  })

  describe('5. 보인 세션이 3 번 미만이거나, 이번 세션에 이미 보이기 시작했다', () => {
    it('sessionsShown 2 → 보인다', async () => {
      const { nudge } = await newSession()
      expect(nudge.nudgeVisible({ ...baseState, sessionsShown: 2 }, baseCtx)).toBe(true)
    })

    it('sessionsShown 3, 이번 세션에 아직 안 보임 → 안 보인다', async () => {
      const { nudge } = await newSession()
      expect(nudge.nudgeVisible({ ...baseState, sessionsShown: 3 }, { ...baseCtx, shownThisSession: false })).toBe(false)
    })

    it('sessionsShown 3, 이번 세션에 이미 보였음 → 계속 보인다', async () => {
      const { nudge } = await newSession()
      expect(nudge.nudgeVisible({ ...baseState, sessionsShown: 3 }, { ...baseCtx, shownThisSession: true })).toBe(true)
    })
  })
})

describe('readNudgeState — 저장 값 읽기(깨졌거나 없으면 기본값, 던지지 않는다)', () => {
  it('없으면 기본값', async () => {
    const { nudge } = await newSession()
    expect(nudge.readNudgeState()).toEqual({ detailViews: 0, dismissedAt: null, sessionsShown: 0 })
  })

  it('JSON 이 아니면 기본값', async () => {
    localStorage.setItem(KEY, '{not json')
    const { nudge } = await newSession()
    expect(() => nudge.readNudgeState()).not.toThrow()
    expect(nudge.readNudgeState()).toEqual({ detailViews: 0, dismissedAt: null, sessionsShown: 0 })
  })

  it('객체가 아니면(null·배열·숫자) 기본값', async () => {
    const { nudge } = await newSession()
    for (const raw of ['null', '[]', '42', '"x"']) {
      localStorage.setItem(KEY, raw)
      expect(nudge.readNudgeState()).toEqual({ detailViews: 0, dismissedAt: null, sessionsShown: 0 })
    }
  })

  it('숫자가 아니거나 음수인 횟수는 0', async () => {
    localStorage.setItem(KEY, JSON.stringify({ detailViews: 'many', sessionsShown: -2, dismissedAt: null }))
    const { nudge } = await newSession()
    const s = nudge.readNudgeState()
    expect(s.detailViews).toBe(0)
    expect(s.sessionsShown).toBe(0)
    expect(s.dismissedAt).toBeNull()
  })

  it('정상 값은 그대로 읽는다', async () => {
    localStorage.setItem(KEY, JSON.stringify({ detailViews: 4, sessionsShown: 2, dismissedAt: 1234 }))
    const { nudge } = await newSession()
    expect(nudge.readNudgeState()).toEqual({ detailViews: 4, dismissedAt: 1234, sessionsShown: 2 })
  })
})

describe('nudgeMessage — §3 문구', () => {
  it('ko: 담은 장소가 있으면 장소 수', async () => {
    const { nudge } = await newSession()
    expect(nudge.nudgeMessage(1, 0, 'ko')).toBe('담아 둔 1곳, 로그인하면 어느 기기서든 이어져요')
    expect(nudge.nudgeMessage(5, 3, 'ko')).toBe('담아 둔 5곳, 로그인하면 어느 기기서든 이어져요')
  })

  it('ko: 담은 장소 0, 찜한 작품이 있으면 작품 수', async () => {
    const { nudge } = await newSession()
    expect(nudge.nudgeMessage(0, 2, 'ko')).toBe('찜한 작품 2개, 로그인하면 어느 기기서든 이어져요')
  })

  it('ko: 둘 다 0', async () => {
    const { nudge } = await newSession()
    expect(nudge.nudgeMessage(0, 0, 'ko')).toBe('로그인하면 길찾기·후기까지 다 돼요')
  })

  it('en: 장소 — 1 이면 spot, 아니면 spots', async () => {
    const { nudge } = await newSession()
    expect(nudge.nudgeMessage(1, 0, 'en')).toBe('Sign in to keep your 1 saved spot on any device')
    expect(nudge.nudgeMessage(2, 0, 'en')).toBe('Sign in to keep your 2 saved spots on any device')
    expect(nudge.nudgeMessage(3, 7, 'en')).toBe('Sign in to keep your 3 saved spots on any device')
  })

  it('en: 작품 — 1 이면 title, 아니면 titles', async () => {
    const { nudge } = await newSession()
    expect(nudge.nudgeMessage(0, 1, 'en')).toBe('Sign in to keep your 1 liked title on any device')
    expect(nudge.nudgeMessage(0, 4, 'en')).toBe('Sign in to keep your 4 liked titles on any device')
  })

  it('en: 둘 다 0', async () => {
    const { nudge } = await newSession()
    expect(nudge.nudgeMessage(0, 0, 'en')).toBe('Sign in for directions and reviews')
  })
})

describe('countDetailView — §4 상세 열기', () => {
  it('비회원이면 detailViews 를 1 올리고 저장한다', async () => {
    const { nudge } = await newSession()
    nudge.countDetailView(false)
    nudge.countDetailView(false)
    expect(nudge.readNudgeState().detailViews).toBe(2)
    expect(JSON.parse(localStorage.getItem(KEY)!).detailViews).toBe(2)
  })

  it('로그인했으면 올리지 않는다', async () => {
    const { nudge } = await newSession()
    nudge.countDetailView(true)
    expect(nudge.readNudgeState().detailViews).toBe(0)
  })

  it('깨진 저장 값 위에서도 0 에서 다시 센다', async () => {
    localStorage.setItem(KEY, 'garbage')
    const { nudge } = await newSession()
    nudge.countDetailView(false)
    expect(nudge.readNudgeState().detailViews).toBe(1)
  })
})

describe('markShown — §4 처음 보임(세션당 한 번)', () => {
  it('처음 부르면 sessionsShown+1, signup_nudge_view(trigger) 한 번', async () => {
    const { nudge, track } = await newSession()
    expect(nudge.nudgeShownThisSession()).toBe(false)
    nudge.markShown('saved')
    expect(nudge.readNudgeState().sessionsShown).toBe(1)
    expect(nudge.nudgeShownThisSession()).toBe(true)
    expect(track).toHaveBeenCalledTimes(1)
    expect(track).toHaveBeenCalledWith({ name: 'signup_nudge_view', params: { trigger: 'saved' } })
  })

  it('같은 세션에서 다시 불러도 세지 않고 보내지 않는다', async () => {
    const { nudge, track } = await newSession()
    nudge.markShown('views')
    nudge.markShown('views')
    nudge.markShown('saved')
    expect(nudge.readNudgeState().sessionsShown).toBe(1)
    expect(track).toHaveBeenCalledTimes(1)
    expect(track).toHaveBeenCalledWith({ name: 'signup_nudge_view', params: { trigger: 'views' } })
  })

  it('새 세션이면 다시 한 번 센다', async () => {
    const first = await newSession()
    first.nudge.markShown('saved')
    const second = await newSession()
    expect(second.nudge.nudgeShownThisSession()).toBe(false)
    second.nudge.markShown('saved')
    expect(second.nudge.readNudgeState().sessionsShown).toBe(2)
    expect(second.track).toHaveBeenCalledTimes(1)
  })

  it('다른 저장 값(detailViews·dismissedAt)은 건드리지 않는다', async () => {
    localStorage.setItem(KEY, JSON.stringify({ detailViews: 5, sessionsShown: 1, dismissedAt: 77 }))
    const { nudge } = await newSession()
    nudge.markShown('views')
    expect(nudge.readNudgeState()).toEqual({ detailViews: 5, dismissedAt: 77, sessionsShown: 2 })
  })
})

describe('dismiss — §4 닫기', () => {
  it('dismissedAt = now, signup_nudge_dismiss(trigger) 를 보낸다', async () => {
    const { nudge, track } = await newSession()
    nudge.dismiss('saved', 123_456)
    expect(nudge.readNudgeState().dismissedAt).toBe(123_456)
    expect(track).toHaveBeenCalledWith({ name: 'signup_nudge_dismiss', params: { trigger: 'saved' } })
  })

  it('now 를 주지 않으면 지금 시각', async () => {
    const { nudge } = await newSession()
    vi.useFakeTimers()
    try {
      vi.setSystemTime(new Date(2026, 10, 13, 12, 0, 0))
      nudge.dismiss('views')
      expect(nudge.readNudgeState().dismissedAt).toBe(new Date(2026, 10, 13, 12, 0, 0).getTime())
    } finally {
      vi.useRealTimers()
    }
  })

  it('닫은 뒤 3 일 안에는 nudgeVisible 이 거짓이다', async () => {
    const { nudge } = await newSession()
    const now = 20 * DAY
    nudge.dismiss('saved', now)
    const state = nudge.readNudgeState()
    expect(nudge.nudgeVisible(state, { ...baseCtx, now: now + DAY })).toBe(false)
    expect(nudge.nudgeVisible(state, { ...baseCtx, now: now + THREE_DAYS })).toBe(true)
  })
})

describe('nudgeCompact — 담은 사람의 새 홈에서는 작은 원(web-ad-landing.md §10-3-11)', () => {
  it.each<[boolean, number, boolean]>([
    [true, 1, true],
    [true, 0, false],
    [false, 3, false],
  ])('protoOn=%s 담은 수=%d → %s', (protoOn, saved, want) => {
    expect(nudgeCompact(protoOn, saved)).toBe(want)
  })
})
