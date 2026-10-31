/*
 * 여행 안내 시험 — iOS tests/RouteNavLimitTests(RouteNavLimitSessionTests) · TripSessionRequestTests 와 같은 흐름.
 * 서버(`requestLeg`)·위치(`FakeLocator`)·시계는 가짜다. 길찾기는 유료라 실제 호출은 어떤 경로로도 하지 않는다.
 */
import { beforeEach, describe, expect, it } from 'vitest'
import { chooseLanguage } from '@/i18n'
import { createStore } from '@/state/storage'
import { type ConsentGate } from '../guide/consentPort'
import { type LocatorLike, type LocatorState } from './locator'
import { type NextLeg } from './navModels'
import { NavLimitStore } from './navLimit'
import { type LegRequest, NavRequestError, type TripEnv, TripSession, type TripTarget } from './tripSession'
import { type NavFailure } from './navFailure'

beforeEach(() => chooseLanguage('ko'))

const tick = (): Promise<void> => new Promise((resolve) => setTimeout(resolve, 0))
const settle = async (done: () => boolean) => {
  for (let i = 0; i < 300 && !done(); i += 1) await tick()
}

class FakeLocator implements LocatorLike {
  readonly store = createStore<LocatorState>({ kind: 'asking' })
  tracking = false
  start = () => {}
  track = () => {
    this.tracking = true
  }
  stop = () => {
    this.tracking = false
  }
  inject(latitude: number, longitude: number) {
    this.store.set({ kind: 'found', latitude, longitude })
  }
}

type Reply = NextLeg | NavFailure

const ROUTE: NextLeg = { totalMinutes: 12, transfers: 0, guidanceLang: 'ko', legs: [] }
const limited = (retryAfter: number | null): NavFailure => ({ kind: 'limitReached', retryAfter })
const down: NavFailure = { kind: 'providerDown' }

/** 가상 위치(서울시청)에서 먼 곳 — 시험 도중 도착 판정이 나지 않게. */
const stop = (name: string, item: number | null): TripTarget => ({ uid: `u${item}`, serverItemId: item, unavailable: null, place: { name, latitude: 35.1587, longitude: 129.1604 } })

function rig(over: { allowed?: boolean; instant?: boolean } = {}) {
  const clock = { now: 9000, wake: false, sleeps: [] as number[], ms: 1_000_000 }
  const limits = new NavLimitStore({
    now: () => clock.now,
    sleep: async (s, signal) => {
      clock.sleeps.push(s)
      while (!clock.wake && !signal.aborted) await tick()
      clock.wake = false
    },
  })
  const sent: LegRequest[] = []
  const replies: Reply[] = []
  let release: (() => void) | null = null
  let hold = false
  const locator = new FakeLocator()
  const consent = { allowed: over.allowed ?? true, asked: 0, grant: null as (() => void) | null, deny: null as (() => void) | null }
  const gate: ConsentGate = {
    isAllowed: () => consent.allowed,
    request: (action, onCancel) => {
      consent.asked += 1
      consent.grant = () => {
        consent.allowed = true
        action()
      }
      consent.deny = () => onCancel?.()
    },
    cancel: () => {},
  }
  const env: Partial<TripEnv> = {
    limits,
    locator,
    locationConsent: gate,
    nowMs: () => clock.ms,
    every: () => () => {},
    requestLeg: async (req) => {
      sent.push(req)
      if (!over.instant) await tick() // 진짜 요청처럼 한 번 쉰다
      if (hold) await new Promise<void>((resolve) => (release = resolve))
      const next = replies.shift()
      if (!next) throw new Error('답이 없다')
      if ('kind' in next) throw new NavRequestError(next)
      return next
    },
  }
  const trip = new TripSession(env)
  return {
    trip, limits, sent, replies, clock, locator, consent,
    holdReplies: () => (hold = true),
    releaseReply: () => {
      hold = false
      release?.()
    },
  }
}

/** 요청이 끝나(또는 막혀) 실패·결과가 설 때까지. */
const answered = (trip: TripSession) => settle(() => !trip.state.asking && (trip.state.failure !== null || trip.state.result !== null))
/** 첫 위치를 넣는다 — 길찾기는 위치가 와야 나간다. */
const here = (r: ReturnType<typeof rig>) => r.locator.inject(37.5663, 126.9779)

describe('여행 안내 — 흐름', () => {
  it('시작하면 안내 중이고 위치가 오면 경로를 묻는다. 응답이 오면 결과와 알림 띠가 선다', async () => {
    const r = rig()
    r.replies.push(ROUTE)
    r.trip.start(stop('해운대', 11), 1, 7)
    expect(r.trip.state.phase).toBe('guiding')
    expect(r.locator.tracking).toBe(true)
    expect(r.sent).toEqual([]) // 위치가 아직 없다 — 지어내지 않는다
    here(r)
    await answered(r.trip)
    expect(r.sent).toEqual([{ courseId: 7, itemId: 11, latitude: 37.5663, longitude: 126.9779 }])
    expect(r.trip.state.result?.totalMinutes).toBe(12)
    expect(r.trip.state.notice).not.toBeNull()
    expect(r.limits.state.kind).toBe('clear')
    r.trip.end()
    expect(r.locator.tracking).toBe(false)
    expect(r.trip.state.phase).toBe('idle')
  })

  it('저장 전 코스(서버 id 없음)는 물어보지 않고 이유를 말한다', async () => {
    const r = rig()
    r.trip.start(stop('해운대', null), 1, null)
    here(r)
    await tick()
    expect(r.trip.state.failure).toEqual({ kind: 'unsavedCourse' })
    expect(r.sent).toEqual([])
    const r2 = rig()
    r2.trip.start(stop('해운대', 11), 1, null)
    here(r2)
    await tick()
    expect(r2.trip.state.failure).toEqual({ kind: 'unsavedCourse' })
  })

  it('숨김·폐업 줄로는 안내를 시작하지 않는다', () => {
    const r = rig()
    r.trip.start({ ...stop('폐업', 11), unavailable: 'closedPoi' }, 1, 7)
    expect(r.trip.state.phase).toBe('idle')
    expect(r.trip.isActive).toBe(false)
  })

  it('「여기 도착함」 — 도착이 되고 스탬프가 서고 부른 쪽이 알림을 받는다. 다음은 사람이 고른다', () => {
    const r = rig()
    const arrived: string[] = []
    r.trip.onArrived = (t) => arrived.push(t.place.name)
    r.trip.start(stop('해운대', 11), 1, 7)
    r.trip.arriveNow()
    expect(r.trip.state).toMatchObject({ phase: 'arrived', stamped: true, result: null, failure: null })
    expect(arrived).toEqual(['해운대'])
    r.trip.stampDone()
    expect(r.trip.state.phase).toBe('arrived') // 연출이 끝나도 다음으로 넘기지 않는다
    r.trip.arriveNow()
    expect(arrived).toEqual(['해운대']) // 안내 중이 아니면 두 번 찍지 않는다
  })

  it('반경 안에 머무르면 저절로 도착이다 — 지나가기만 한 것은 아니다', async () => {
    const r = rig()
    r.replies.push(ROUTE)
    const target: TripTarget = { uid: 'x', serverItemId: 11, unavailable: null, place: { name: '시청', latitude: 37.5663, longitude: 126.9779 } }
    r.trip.start(target, 1, 7)
    here(r)
    expect(r.trip.state.phase).toBe('guiding')
    r.clock.ms += 301_000
    r.locator.inject(37.5663, 126.97791)
    expect(r.trip.state.phase).toBe('arrived')
    r.trip.end()
  })

  it('안내 띠 힌트 — 반경 밖이면 규칙 한 줄, 안이면 남은 분', () => {
    const r = rig()
    const target: TripTarget = { uid: 'x', serverItemId: 11, unavailable: null, place: { name: '시청', latitude: 37.5663, longitude: 126.9779 } }
    r.trip.start(target, 1, 7)
    expect(r.trip.dwellHint()).toBe('반경 100 m 에 머무르면 스탬프')
    r.replies.push(ROUTE)
    here(r)
    expect(r.trip.dwellHint()).toBe('머무르면 도착 · 5분')
    r.clock.ms += 120_000
    expect(r.trip.dwellHint()).toBe('머무르면 도착 · 3분')
    r.trip.end()
  })

  it('「현재위치로」 는 카메라 신호만 올린다', () => {
    const r = rig()
    const before = r.trip.state.recenterTick
    r.trip.recenter()
    expect(r.trip.state.recenterTick).toBe(before + 1)
  })
})

describe('길찾기 한도 (RouteNavLimitSessionTests)', () => {
  it('하루 한도 — 안내는 켜진 채이고 다음 성지로 넘어가도 서버에 묻지 않는다', async () => {
    const r = rig()
    r.replies.push(limited(30000))
    r.trip.start(stop('해운대', 11), 1, 7)
    here(r)
    await answered(r.trip)
    expect(r.trip.state.failure).toEqual(limited(30000))
    expect(r.sent).toHaveLength(1)
    expect(r.sent[0].itemId).toBe(11)
    expect(r.trip.state.phase).toBe('guiding') // 여행은 멈추지 않는다
    expect(r.trip.state.target?.place.name).toBe('해운대')
    expect(r.trip.state.result).toBeNull()
    expect(r.limits.stopsAsking).toBe(true)

    // 「다시 시도」 — 막혀 있는 동안은 나가지 않는다
    r.trip.retry()
    await answered(r.trip)
    expect(r.sent).toHaveLength(1)
    expect(r.trip.state.failure?.kind).toBe('limitReached')

    // 「여기 도착함」 → 「다음 · 2번으로」 — 도착 처리는 되고 다음 구간도 서버에 묻지 않고 한도 안내로 간다
    const arrived: string[] = []
    r.trip.onArrived = (t) => arrived.push(t.place.name)
    r.trip.arriveNow()
    expect(r.trip.state.phase).toBe('arrived')
    expect(arrived).toEqual(['해운대'])
    r.trip.stampDone()
    r.trip.start(stop('광안리', 12), 2, 7)
    await answered(r.trip)
    expect(r.sent).toHaveLength(1) // 뻔히 429 인 요청을 보내지 않는다
    expect(r.trip.state.failure?.kind).toBe('limitReached')
    expect(r.trip.state.phase).toBe('guiding')
    expect(r.trip.state.target?.place.name).toBe('광안리')
    r.trip.end()
  })

  it('한도는 계정의 것 — 다른 코스의 새 안내도 같은 한도를 본다', async () => {
    const first = rig()
    first.replies.push(limited(30000))
    first.trip.start(stop('해운대', 11), 1, 7)
    here(first)
    await answered(first.trip)
    first.trip.end()

    const second = new TripSession({ limits: first.limits, locator: new FakeLocator(), every: () => () => {}, requestLeg: async (req) => { first.sent.push(req); return ROUTE }, locationConsent: { isAllowed: () => true, request: () => {}, cancel: () => {} } })
    const loc = (second as unknown as { env: TripEnv }).env.locator as FakeLocator
    second.start(stop('감천', 21), 1, 8)
    loc.inject(37.5663, 126.9779)
    await answered(second)
    expect(second.state.failure?.kind).toBe('limitReached')
    expect(first.sent).toHaveLength(1)
    second.end()
  })

  it('1분 창 — 풀려도 저절로 다시 부르지 않는다. 「다시 시도」 를 눌러야 나가고 되면 한도가 걷힌다', async () => {
    const r = rig()
    r.replies.push(limited(25), ROUTE)
    r.trip.start(stop('해운대', 11), 1, 7)
    here(r)
    await answered(r.trip)
    expect(r.limits.state).toEqual({ kind: 'reached', block: { window: 'short', until: r.clock.now + 25 } })

    await settle(() => r.clock.sleeps.length > 0)
    r.clock.now += 25
    r.clock.wake = true
    await settle(() => r.limits.state.kind === 'lifted')
    expect(r.limits.state.kind).toBe('lifted')
    expect(r.sent).toHaveLength(1) // 풀렸다고 유료 요청을 저절로 내지 않는다
    expect(r.trip.state.failure?.kind).toBe('limitReached')

    r.trip.retry()
    await settle(() => r.trip.state.result !== null)
    expect(r.sent).toHaveLength(2)
    expect(r.trip.state.failure).toBeNull()
    expect(r.trip.state.result?.totalMinutes).toBe(12)
    expect(r.limits.state.kind).toBe('clear')
    r.trip.end()
  })

  it('풀리는 때를 모르면 막지 않는다 — 「다시 시도」 가 바로 서버에 묻는다', async () => {
    const r = rig()
    r.replies.push(limited(null), ROUTE)
    r.trip.start(stop('해운대', 11), 1, 7)
    here(r)
    await answered(r.trip)
    expect(r.trip.state.failure).toEqual(limited(null))
    r.trip.retry()
    await settle(() => r.trip.state.result !== null)
    expect(r.sent).toHaveLength(2)
    expect(r.limits.state.kind).toBe('clear')
    r.trip.end()
  })

  it('잠시 장애(503)는 한도가 아니다 — 「다시 시도」 가 요청을 한 번 더 낸다', async () => {
    const r = rig()
    r.replies.push(down, ROUTE)
    r.trip.start(stop('해운대', 11), 1, 7)
    here(r)
    await answered(r.trip)
    expect(r.trip.state.failure).toEqual(down)
    expect(r.sent).toHaveLength(1)
    expect(r.limits.state.kind).toBe('clear')
    r.trip.retry()
    await settle(() => r.trip.state.result !== null)
    expect(r.sent).toHaveLength(2)
    expect(r.trip.state.failure).toBeNull()
    r.trip.end()
  })

  it('실패한 뒤에는 걸음마다 다시 묻지 않는다 — 목적지마다 한 번씩만', async () => {
    const r = rig({ instant: true })
    r.replies.push(down, down)
    r.trip.start(stop('해운대', 11), 1, 7)
    here(r)
    await answered(r.trip)
    // 같은 자리가 두 번 더 들어온다 — 실패가 서 있어 예약조차 되지 않는다
    r.locator.inject(37.5665, 126.978)
    r.locator.inject(37.5666, 126.9781)
    // 「다음」 을 눌러 실패를 비운 직후 위치가 또 온다
    r.trip.start(stop('광안리', 12), 2, 7)
    r.locator.inject(37.5667, 126.9782)
    await answered(r.trip)
    await settle(() => false)
    expect(r.sent.map((s) => s.itemId)).toEqual([11, 12])
    expect(r.trip.state.failure).toEqual(down)
    r.trip.end()
  })

  it('기다리는 사이에 계정이 바뀌었다 — 앞 계정의 429 가 새 계정을 막지 않는다', async () => {
    const r = rig()
    r.holdReplies()
    r.replies.push(limited(30000))
    r.trip.start(stop('해운대', 11), 1, 7)
    here(r)
    await settle(() => r.sent.length === 1)
    r.limits.forget() // 로그아웃·다른 계정으로 로그인
    r.releaseReply()
    await settle(() => !r.trip.state.asking)
    expect(r.limits.state.kind).toBe('clear')
    expect(r.limits.stopsAsking).toBe(false)
    expect(r.trip.state.failure).toBeNull()
    r.trip.end()
  })

  it('늦게 온 응답은 목적지가 바뀌었으면 버린다', async () => {
    const r = rig()
    r.holdReplies()
    r.replies.push(ROUTE, ROUTE)
    r.trip.start(stop('해운대', 11), 1, 7)
    here(r)
    await settle(() => r.sent.length === 1)
    r.trip.end()
    r.releaseReply()
    await settle(() => !r.trip.state.asking)
    expect(r.trip.state.result).toBeNull()
    expect(r.trip.state.phase).toBe('idle')
  })
})

describe('위치 동의', () => {
  it('동의가 없으면 서버에 위치를 보내지 않고 한 번만 묻는다. 허용하면 이어서 간다', async () => {
    const r = rig({ allowed: false })
    r.replies.push(ROUTE)
    r.trip.start(stop('해운대', 11), 1, 7)
    here(r)
    r.locator.inject(37.5664, 126.978)
    await tick()
    expect(r.sent).toEqual([])
    expect(r.consent.asked).toBe(1) // 한 사용자 요청에서 시트는 한 번만 연다
    r.consent.grant?.()
    await settle(() => r.trip.state.result !== null)
    expect(r.sent).toHaveLength(1)
    r.trip.end()
  })

  it('시트를 취소하면 동의가 필요하다고 말하고 다시 시도가 된다', async () => {
    const r = rig({ allowed: false })
    r.trip.start(stop('해운대', 11), 1, 7)
    here(r)
    await tick()
    r.consent.deny?.()
    expect(r.trip.state.failure).toEqual({ kind: 'locationConsentRequired' })
    expect(r.sent).toEqual([])
    r.trip.end()
  })
})
