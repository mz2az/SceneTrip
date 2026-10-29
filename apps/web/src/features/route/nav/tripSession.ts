/*
 * 여행 중 안내 — iOS RouteTab/TripSession.swift (계획 trip-mode.md §8, 2026-09-03 · main 이식 MZ2AZ-307).
 *
 * 편집 화면의 지도가 곧 여행 지도라 상태를 화면 밖 객체로 뺐다. 여기는 **목적지 하나**를 들고 경로를 받고, 머무름으로 도착을 재고, 스탬프를 알린다.
 *
 * ## 경로는 서버가 준다
 * 계약 `POST /navigation/next-leg` 만 부른다 — 서버가 카카오를 부르고 키도 서버에만 있다(ADR 0009). 그래서 목적지는 **저장된 코스의 항목**
 * (courseId + serverItemId)이어야 하고 코스가 `active` 여야 한다. 안 될 때는 `NavFailure` 가 이유를 말한다. **누를 때만 부른다** — 유료라서다.
 *
 * ## 도착해도 다음으로 넘기지 않는다
 * 성지에 왔으면 둘러보고 즐기는 시간이 있고 얼마나 머물지는 앱이 모른다. 그래서 도착 뒤에는 `arrived` 로 서서 기다리고, **사람이 「다음으로」를 눌러야** `start()` 가
 * 다시 불린다. 밖과 닿는 자리(`TripEnv`)는 모두 주입한다 — 시험·화면 확인이 가짜로 바꿔 끼운다(유료 호출은 어떤 경로로도 실행하지 않는다).
 */
import { NO_RETRY_HEADER, api } from '@/api/client'
import { tr } from '@/i18n'
import { installHeader } from '@/state/install'
import { createStore, useStore, type Store } from '@/state/storage'
import { createConsentGate, type ConsentGate } from '../guide/consentPort'
import { type NavFailure, classifyNavFailure } from './navFailure'
import { type NavResult, type NextLeg, navResultFromContract } from './navModels'
import { type NavLimitStore, sharedNavLimits } from './navLimit'
import { distanceMeters } from './geo'
import { BrowserLocator, type LocatorLike } from './locator'
import { ARRIVE_RADIUS_METERS, TripArrival, dwellSeconds } from './tripMode'

/** 안내할 목적지 — 코스의 한 줄(`RouteStop`)이 구조로 만족한다. */
export interface TripTarget {
  uid: string
  /** 서버가 준 아이템 id. 저장 전이면 null. */
  serverItemId: number | null
  /** 숨김·폐업 표시. 있으면 안내하지 않는다. */
  unavailable: unknown
  place: { name: string; latitude: number; longitude: number }
}

export type TripPhase = 'idle' | 'guiding' | 'arrived'

export interface TripSpot {
  latitude: number
  longitude: number
}

export interface TripState {
  target: TripTarget | null
  phase: TripPhase
  /** 서버가 준 안내. 아직 안 왔으면 null. */
  result: NavResult | null
  /** 안 된 이유. 결과가 오면 null 로 돌아간다. */
  failure: NavFailure | null
  asking: boolean
  /** 도착 스탬프 연출이 떠 있는가. */
  stamped: boolean
  /** 지금 위치. 못 받았으면 null — 지어내 찍지 않는다. */
  here: TripSpot | null
  /** 5초 박자 — 머무름 재판정과 안내 띠의 「N분」 갱신용. 값 자체는 뜻이 없다. */
  dwellTick: number
  /** 카메라를 「나와 목적지」로 되돌리라는 신호. 값이 바뀔 때 지도가 움직인다. */
  recenterTick: number
  /** 「길 안내를 위해 카카오에 현재 위치를 보냈어요.」 띠를 띄울 요청 번호. 4초 뒤 `hideNotice`. */
  notice: number | null
}

/** 서버가 응답한 오류 — 상태·본문 `code`·`Retry-After`. */
export class NavRequestError extends Error {
  constructor(readonly failure: NavFailure) {
    super(`navigation: ${failure.kind}`)
  }
}

export interface LegRequest {
  courseId: number
  itemId: number
  latitude: number
  longitude: number
}

export interface TripEnv {
  /** 서버에 다음 구간을 묻는다. 실패는 `NavRequestError`. */
  requestLeg(req: LegRequest): Promise<NextLeg>
  locator: LocatorLike
  limits: NavLimitStore
  /** 위치기반서비스 동의 — 경로 계산을 위해 카카오에 위치를 보내므로 동의 없이는 묻지 않는다. */
  locationConsent: ConsentGate
  nowMs(): number
  /** 5초 박자를 건다. 취소 함수를 돌려준다. */
  every(ms: number, action: () => void): () => void
}

/** 공용 클라이언트로 실제로 보낸다 — 개발 중에는 `.env.local` 의 안전장치(`VITE_BLOCK_PATHS=/navigation`)가 막는다. */
export async function defaultRequestLeg(req: LegRequest): Promise<NextLeg> {
  let result
  try {
    result = await api.POST('/navigation/next-leg', {
      params: { header: installHeader() },
      body: req,
      // 길찾기는 유료다 — 공용 계층이 자동으로 다시 보내지 않는다. 사람이 「다시 시도」 를 누른다.
      headers: { [NO_RETRY_HEADER]: '1' },
    })
  } catch {
    throw new NavRequestError({ kind: 'unreachable' })
  }
  if (result.response.ok && result.data) return result.data
  const code = (result.error as { code?: unknown } | undefined)?.code
  const retry = Number(result.response.headers.get('Retry-After'))
  throw new NavRequestError(
    classifyNavFailure({ status: result.response.status, code: typeof code === 'string' ? code : null, retryAfter: Number.isFinite(retry) && result.response.headers.get('Retry-After') !== null ? retry : null }),
  )
}

const defaultEnv = (): TripEnv => ({
  requestLeg: defaultRequestLeg,
  locator: new BrowserLocator(),
  limits: sharedNavLimits,
  locationConsent: createConsentGate('location'),
  nowMs: () => Date.now(),
  every: (ms, action) => {
    const id = setInterval(action, ms)
    return () => clearInterval(id)
  },
})

const initial: TripState = {
  target: null, phase: 'idle', result: null, failure: null, asking: false, stamped: false, here: null, dwellTick: 0, recenterTick: 0, notice: null,
}

/** 5초마다 머무름을 다시 센다 — 가만히 서 있으면 위치 업데이트가 안 온다(25 m 이동 필터). 반경 안에 5분을 있어도 판정이 다시 돌지 않아 스탬프가 영영 안 찍힌다. */
const DWELL_TICK_MS = 5000

export class TripSession {
  readonly store: Store<TripState> = createStore<TripState>(initial)
  /** 목적지 번호(1부터). */
  targetNumber = 0
  /** 이 목적지가 든 코스의 서버 id. 계약이 목적지를 코스 항목으로만 받으므로 필요하다. */
  courseId: number | null = null
  /** 스탬프가 찍혔다 — 부르는 쪽이 코스 상태와 서버에 「다녀옴」을 남긴다. */
  onArrived: ((target: TripTarget) => void) | null = null

  private arrival: TripArrival
  private stopTracking: Array<() => void> = []
  private requestId = 0
  private readonly env: TripEnv

  constructor(env?: Partial<TripEnv>) {
    this.env = { ...defaultEnv(), ...env }
    this.arrival = new TripArrival()
  }

  get state(): TripState {
    return this.store.get()
  }
  get limits(): NavLimitStore {
    return this.env.limits
  }
  get isActive(): boolean {
    return this.state.target !== null
  }

  private patch(p: Partial<TripState>): void {
    this.store.set((s) => ({ ...s, ...p }))
  }

  /** 새 사용자 동작이다 — 앞 요청의 늦은 응답과 동의 대기를 무효로 한다 (LocationDirectionsConsent.beginRequest). */
  private beginRequest(): number {
    this.env.locationConsent.cancel()
    this.asked = false
    this.patch({ notice: null })
    return (this.requestId += 1)
  }
  private asked = false

  /**
   * 이 성지로 안내를 시작한다(다시 시작해도 된다 — 도착 뒤 「다음으로」가 이것을 부른다). `courseId` 는 저장된 코스의 서버 id —
   * 없으면(저장 전) 경로를 못 받고 그 이유를 보인다.
   */
  start(target: TripTarget, number: number, courseId: number | null): void {
    if (target.unavailable !== null) return
    this.beginRequest()
    this.targetNumber = number
    this.courseId = courseId
    this.arrival = new TripArrival()
    this.patch({ target, phase: 'guiding', result: null, failure: null, stamped: false, recenterTick: this.state.recenterTick + 1 })
    this.beginTracking()
    const { here } = this.state
    if (here) void this.load(here)
  }

  /** 안내를 끝낸다. 위치 받기도 멈춘다 — 여행 종료·화면 닫기. */
  end(): void {
    this.beginRequest()
    this.patch({ target: null, phase: 'idle', result: null, failure: null, stamped: false })
    this.endTracking()
  }

  /** 「다시 시도」 — 결과와 실패를 비우고 지금 자리에서 다시 묻는다. */
  retry(): void {
    this.beginRequest()
    this.patch({ result: null, failure: null })
    const { here } = this.state
    if (here) void this.load(here)
  }

  /** 「여기 도착함」 — 머무름을 기다리지 않고 지금 찍는다. 머무를 시간이 없거나 GPS 가 튈 때의 탈출구다. 저절로 찍힐 때도 같은 길을 지난다. */
  arriveNow(): void {
    const { phase, target } = this.state
    if (phase !== 'guiding' || !target) return
    // 안내가 끝났다 — 안내 띠는 도착으로 바뀐다. 다음은 사람이 고른다.
    this.patch({ phase: 'arrived', result: null, failure: null, stamped: true })
    this.onArrived?.(target)
  }

  /** 스탬프 연출이 끝났다. `arrived` 그대로 선다. */
  stampDone(): void {
    this.patch({ stamped: false })
  }

  /** 「현재위치로」 — 카메라를 나와 목적지로 되돌린다. */
  recenter(): void {
    this.patch({ recenterTick: this.state.recenterTick + 1 })
  }

  hideNotice(id: number): void {
    if (this.state.notice === id) this.patch({ notice: null })
  }

  /** 위치 동의 시트를 취소했다 — 동의 없이는 길을 못 묻는다. */
  cancelLocationRequest(): void {
    if (!this.env.locationConsent.isAllowed()) this.patch({ failure: { kind: 'locationConsentRequired' } })
  }

  // ---------------------------------------------------------------- 위치

  private beginTracking(): void {
    if (this.stopTracking.length > 0) return
    const { locator } = this.env
    this.stopTracking.push(
      locator.store.subscribe(() => {
        const s = locator.store.get()
        if (s.kind === 'found') this.observe({ latitude: s.latitude, longitude: s.longitude })
      }),
    )
    locator.track()
    const first = locator.store.get()
    if (first.kind === 'found') this.observe({ latitude: first.latitude, longitude: first.longitude })
    this.stopTracking.push(
      this.env.every(DWELL_TICK_MS, () => {
        this.patch({ dwellTick: this.state.dwellTick + 1 })
        const { here } = this.state
        if (here) this.autoStampIfArrived(here)
      }),
    )
  }

  private endTracking(): void {
    this.stopTracking.forEach((cancel) => cancel())
    this.stopTracking = []
    this.env.locator.stop()
  }

  private observe(spot: TripSpot): void {
    this.patch({ here: spot })
    // 실패한 뒤에는 걸음마다 다시 묻지 않는다 — 경로 없음(422)·가입 필요(401)는 걸어도 같은 답이고, 잠시 장애(503)는 사람이 「다시 시도」를 누른다. 유료 호출이다.
    const s = this.state
    if (s.phase === 'guiding' && s.result === null && s.failure === null && !s.asking) void this.load(spot)
    this.autoStampIfArrived(spot)
  }

  /** 목적지 반경 안에 **머무르면** 저절로 스탬프가 찍힌다. 지나가기만 한 것은 도착이 아니다. */
  private autoStampIfArrived(spot: TripSpot): void {
    const { phase, target } = this.state
    if (phase !== 'guiding' || !target) return
    const meters = distanceMeters(spot, target.place)
    if (this.arrival.observe(meters, this.env.nowMs())) this.arriveNow()
  }

  // ---------------------------------------------------------------- 경로

  /** 경로를 백엔드 계약으로 받는다. */
  private async load(spot: TripSpot): Promise<void> {
    const s = this.state
    // 실패가 서 있으면 묻지 않는다 — 위치가 연달아 와 이 함수가 두 번 예약됐을 때, 앞의 것이 실패로 끝난 직후 뒤의 것이 유료 요청을 또 내지 않게.
    // 다시 묻는 길(`start`·`retry`)은 실패를 먼저 비운다.
    if (s.phase !== 'guiding' || !s.target || s.result !== null || s.failure !== null || s.asking) return
    const target = s.target
    // 계약은 목적지를 저장된 코스의 항목으로만 받는다. 저장 전 코스에는 서버 id 가 없다.
    const itemId = target.serverItemId
    if (this.courseId === null || itemId === null) {
      this.patch({ failure: { kind: 'unsavedCourse' } })
      return
    }
    // 한도에 걸려 있는 동안은 서버에 묻지 않는다(MZ2AZ-366 D) — 뻔히 429 인 유료 창구를 두드리지 않고 바로 지도 앱으로 넘기는 단추를 보인다.
    // 코스 시작·「N번으로 길찾기」·「다음」·핀 카드·「다시 시도」·걸음마다의 첫 요청이 전부 이 함수를 지나므로 여기 한 곳이면 된다.
    if (this.env.limits.stopsAsking) {
      this.patch({ failure: { kind: 'limitReached', retryAfter: this.env.limits.secondsLeft } })
      return
    }
    const requestId = this.requestId
    // 위치기반서비스 동의 — 한 사용자 요청에서 시트는 한 번만 연다.
    if (!this.env.locationConsent.isAllowed()) {
      if (!this.asked) {
        this.asked = true
        this.env.locationConsent.request(
          () => {
            if (this.requestId === requestId) void this.load(this.state.here ?? spot)
          },
          () => this.cancelLocationRequest(),
        )
      }
      return
    }
    this.patch({ asking: true })
    const epoch = this.env.limits.epoch
    try {
      const leg = await this.env.requestLeg({ courseId: this.courseId, itemId, latitude: spot.latitude, longitude: spot.longitude })
      // 기다리는 사이 목적지나 계정이 바뀌면 옛 응답을 버린다.
      if (!this.ownsResponse(requestId, epoch)) return
      const loaded = navResultFromContract(leg, target.place.name)
      this.patch({ result: loaded, failure: null, notice: requestId, recenterTick: this.state.recenterTick + 1 })
      this.env.limits.succeeded()
    } catch (e) {
      if (!this.ownsResponse(requestId, epoch)) return
      const failure: NavFailure = e instanceof NavRequestError ? e.failure : { kind: 'unreachable' }
      this.patch({ failure })
      // 자동으로 다시 부르지 않는다. 풀리는 때를 적어 두고, 그때까지는 위의 문이 요청을 막는다.
      if (failure.kind === 'limitReached') this.env.limits.reach(failure.retryAfter)
    } finally {
      this.patch({ asking: false })
      // 요청 중 start·retry 가 번호를 바꿨으면 최신 목적지를 이어서 묻는다. end·도착 뒤에는 보내지 않는다.
      const after = this.state
      if (this.requestId !== requestId && after.phase === 'guiding' && after.here) void this.load(after.here)
    }
  }

  private ownsResponse(requestId: number, epoch: number): boolean {
    return this.state.phase === 'guiding' && this.requestId === requestId && this.env.limits.epoch === epoch
  }

  // ---------------------------------------------------------------- 안내 띠 글

  /** 안내 띠의 힌트 — 반경 안이면 「머무르면 도착 · N분」, 아니면 규칙 한 줄. */
  dwellHint(): string {
    void this.state.dwellTick // 5초마다 다시 계산되게 묶어 둔다.
    const dwelt = this.arrival.dwelt(this.env.nowMs())
    if (dwelt !== null) {
      const left = Math.max(0, Math.ceil((dwellSeconds() - dwelt) / 60))
      return left === 0 ? tr('도착 확인 중') : tr('머무르면 도착 · %d분', left)
    }
    return tr('반경 %d m 에 머무르면 스탬프', ARRIVE_RADIUS_METERS)
  }
}

export function useTripState(session: TripSession): TripState {
  return useStore(session.store, (s) => s)
}
