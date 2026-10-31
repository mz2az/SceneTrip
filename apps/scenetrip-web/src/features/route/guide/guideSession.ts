/*
 * 가이드와의 **한 대화** — iOS RouteTab/RouteGuideSession.swift.
 *
 * ## 왜 창 밖에 두나
 * 창 안에 두었더니 닫을 때마다 대화가 사라졌다(2026-08-27 사용자 지적). 물어보고 지도를 보려면 창을 내려야 하는데, 내리면 방금 물은 것이
 * 없어져 처음부터 다시 물어야 했다. 화면 밖에서 들면 창은 그것을 보여 주기만 한다 — 열고 닫는 것과 대화가 이어지는 것이 따로 논다.
 *
 * ## 방 번호(`sessionId`)도 여기 있다
 * 서버가 이 값으로 「앞 턴에 보여 준 장소」를 기억한다. 창이 들고 있으면 닫을 때마다 새 방이 되어 「거기 어떻게 가요」가 무엇을 가리키는지
 * 서버도 잊는다. 다만 **대화는 코스의 것이다**(`bind`, 2026-09-17) — 새 코스를 열었는데 앞 코스에서 추천받은 「AI 장소」가 찍히면 안 된다.
 *
 * 밖과 닿는 자리(`GuideEnv`)는 모두 주입한다 — 시험이 가짜로 바꿔 끼운다.
 */
import { appEvent, track } from '@/analytics'
import { currentLang, tr, type Lang } from '@/i18n'
import { createStore, useStore, type Store } from '@/state/storage'
import { type GuideFailure, classifyNoResponse, isLimit } from './guideFailure'
import { type LimitBlock, type Quota, blockIsOver, blockStopsSending, makeBlock, quotaFromEntry, uptimeSeconds } from './guideLimit'
import { type GuideTurnMessage, buildRequest, type GuideContext } from './guideRequest'
import { type GuideAnswer, GuideSendError, sendGuideTurn } from './guideSender'
import { type CoordinateSource, type PendingTurn, makeTurn, stageIsBusy, stageIsResending, turnFailing, turnResending, turnRetried } from './guideTurn'
import type { ResendReason } from './guideRetry'
import { type LedgerEntry, ledgerEntry, serverKnowsLimits } from './rateLimit'
import { type RoutePlace } from '../poi/poiPlace'

/** 챗봇 한도(429 `GUIDE_LIMIT_REACHED`)에 걸렸나 (MZ2AZ-366 C). */
export type GuideLimitState =
  | { kind: 'clear' }
  /** 걸려 있다. 풀리는 때를 알면 그때까지 전송이 잠긴다. */
  | { kind: 'reached'; block: LimitBlock }
  /** 풀렸다 — 「이제 다시 물어볼 수 있어요」. 걸렸던 질문이 남아 있으면 「다시 시도」 가 같은 키로 보낸다. */
  | { kind: 'lifted' }

export interface GuideEnv {
  /** 만들어 둔 턴을 보낸다. 다시 보내기·되묻기를 시작할 때 `onResend` 를 부른다. */
  send(turn: PendingTurn, signal: AbortSignal, onResend: (reason: ResendReason) => void): Promise<GuideAnswer>
  /** 서버가 멱등 키를 아는가 — 이번 실행에서 `RateLimit-*` 를 본 적이 있는가(계획 §4-5). */
  serverKeepsKeys(): boolean
  lang(): Lang
  newKey(): string
  /** 사람이 질문을 보냈다(분석). 「다시 시도」 는 같은 질문이라 세지 않는다. */
  asked(): void
  /** 연속 시계의 지금(초) — 한도의 잠금과 「N분 뒤」 는 이것으로 잰다. 기기 시계를 돌려도 변하지 않는다. */
  now(): number
  /** 벽시계(ms) — 장부의 「다시 차는 때」(벽시계로 적혀 있다)를 읽는 순간에 연속 시계로 옮기는 데만 쓴다. */
  wall(): number
  /** 서버가 마지막 챗봇 응답에 실어 준 남은 양(`RateLimit-*`). 없으면(옛 서버) null. */
  quota(): LedgerEntry | null
  /** 한도가 풀릴 때까지 쉰다. 취소되면 일찍 깬다. */
  sleep(seconds: number, signal: AbortSignal): Promise<void>
}

/** UUID — 대화의 열쇠(`sessionId`)와 멱등 키. */
export function newUuid(): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) return crypto.randomUUID()
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0
    return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16)
  })
}

const sleepFor = (seconds: number, signal: AbortSignal): Promise<void> =>
  new Promise((resolve) => {
    if (signal.aborted) return resolve()
    const t = setTimeout(done, seconds * 1000)
    function done() {
      clearTimeout(t)
      signal.removeEventListener('abort', done)
      resolve()
    }
    signal.addEventListener('abort', done)
  })

export const defaultGuideEnv: GuideEnv = {
  send: (turn, signal, onResend) =>
    sendGuideTurn({ request: turn.request, key: turn.key, lang: turn.lang, keyed: turn.keyed, source: turn.source, signal, onResend }),
  serverKeepsKeys: serverKnowsLimits,
  lang: currentLang,
  newKey: newUuid,
  asked: () => track(appEvent.askGuide()),
  now: uptimeSeconds,
  wall: () => Date.now(),
  quota: () => ledgerEntry('guide'),
  sleep: sleepFor,
}

export interface GuideState {
  turns: readonly GuideTurnMessage[]
  /** 보내는 중이거나 다시 보낼 수 있는 턴 (MZ2AZ-366). 답이 왔거나 다시 해도 같은 실패면 비운다. */
  pending: PendingTurn | null
  /** 챗봇 한도의 상태. 대화가 아니라 계정의 것이다 — 코스를 바꿔도(`bind`·`clear`) 남는다. */
  limit: GuideLimitState
  /** 서버가 마지막으로 알려 준 남은 양 — 입력창 위 한 줄의 재료. 이것도 계정의 것이다. */
  quota: Quota | null
  /** 마지막 답이 부른 도구. 화면에 보여 준다. */
  tools: readonly string[]
  /** 마지막 답이 찾아 준 곳. */
  places: readonly RoutePlace[]
  /** 그중 사용자가 고른 것. 지도에서 빨갛고 크게 그려진다. */
  picked: RoutePlace | null
  failure: GuideFailure | null
  /** 마지막 답 전체 — 편집 화면이 `effects`·`ui` 를 적용한다. `answerTick` 이 바뀔 때 읽는다(같은 답을 두 번 적용하지 않게 값이 아니라 횟수로 알린다). */
  lastAnswer: GuideAnswer | null
  answerTick: number
}

const initialState: GuideState = {
  turns: [], pending: null, limit: { kind: 'clear' }, quota: null, tools: [], places: [], picked: null, failure: null, lastAnswer: null, answerTick: 0,
}

export class GuideSession {
  readonly store: Store<GuideState> = createStore<GuideState>(initialState)
  private sessionId = newUuid()
  private courseKey: string | null = null
  private flight: AbortController | null = null
  /** 지금 떠 있는 가이드 창들. 비어 있으면 다시 보내기·되묻기를 기다리지 않는다(`setAttended`). */
  private attendants = new Set<string>()
  private limitWake: AbortController | null = null
  private quotaSource: LedgerEntry | null = null
  /** 계정이 바뀐 횟수 — 보낸 뒤에 계정이 바뀐 요청의 결과는 버린다. */
  private accountEpoch = 0
  private nextTurnId = 1

  constructor(private env: GuideEnv = defaultGuideEnv) {}

  /** 개발 전용 — `devFake.ts` 가 전송을 가짜로 바꿔 끼운다(유료 `/guide/chat` 을 부르지 않고 상태만 재현). */
  setEnv(env: GuideEnv): void {
    this.env = env
  }

  get state(): GuideState {
    return this.store.get()
  }

  private patch(p: Partial<GuideState>): void {
    this.store.set((s) => ({ ...s, ...p }))
  }

  // ---------------------------------------------------------------- 읽기

  get isEmpty(): boolean {
    return this.state.turns.length === 0
  }

  /** 답을 기다리는 중인가. 그동안은 전송이 잠긴다 — 두 번 누름은 여기서 막힌다. */
  get asking(): boolean {
    const p = this.state.pending
    return p !== null && stageIsBusy(p.stage)
  }

  /** 한도가 풀리기를 기다리는 중인가. 풀리는 때를 모르면(서버가 `Retry-After` 를 안 줌) 잠그지 않는다. */
  get isLimited(): boolean {
    const { limit } = this.state
    return limit.kind === 'reached' && blockStopsSending(limit.block, this.env.now())
  }

  /** 실패한 턴을 다시 보낼 수 있나 — 「다시 시도」 단추를 보일지. 한도에 걸린 턴은 풀린 뒤에만. */
  get canRetry(): boolean {
    return this.state.pending?.stage.kind === 'failed' && !this.isLimited
  }

  /** 연속 시계의 지금(초) — 화면이 남은 분을 다시 셀 때 쓴다. */
  nowSeconds(): number {
    return this.env.now()
  }

  private get attended(): boolean {
    return this.attendants.size > 0
  }

  // ---------------------------------------------------------------- 보내기

  /** 「전송」. 새 턴이고 새 멱등 키다. 실패해 남아 있던 턴은 버린다 — 그 질문은 대화에 남는다. */
  async ask(text: string, here: { latitude: number; longitude: number }, context: GuideContext | null, source: CoordinateSource = 'device'): Promise<void> {
    const question = text.trim()
    this.refreshLimit()
    if (!question || this.asking || this.isLimited) return
    const turns: GuideTurnMessage[] = [...this.state.turns, { id: this.nextTurnId++, role: 'user', text: question }]
    this.patch({ failure: null, turns })
    this.setLimit({ kind: 'clear' }) // 새 질문이다 — 한도 안내는 답(또는 또 한 번의 429)이 다시 정한다
    // 요청은 여기서 한 번 만든다 — 위치·화면 상태·이력이 박힌 채로. 다시 보낼 때 이것을 그대로 쓴다.
    this.patch({
      pending: makeTurn({
        key: this.env.newKey(),
        request: buildRequest({ history: turns, here, sessionId: this.sessionId, context }),
        lang: this.env.lang(),
        keyed: this.env.serverKeepsKeys(),
        source,
      }),
    })
    this.env.asked()
    await this.fly()
  }

  /** 「다시 시도」. 같은 턴을 같은 키로 다시 보낸다 — 질문을 대화에 또 적지 않는다. */
  async retry(): Promise<void> {
    this.refreshLimit()
    const pending = this.state.pending
    if (!this.canRetry || !pending) return
    const turn = turnRetried(pending, this.env.lang(), this.env.newKey)
    if (!turn) return
    this.patch({ failure: null })
    this.setLimit({ kind: 'clear' })
    this.patch({ pending: turn })
    await this.fly()
  }

  /**
   * 가이드 창이 열렸다·닫혔다. 닫히면 기다리던 다시 보내기·되묻기를 끊는다 — 보는 사람이 없는데 뒤에서 몇십 초씩 되묻지 않는다.
   * 처음 보낸 요청은 끊지 않는다(묻고 지도를 보려고 창을 내린다). 끊긴 턴은 남아 있어, 다시 열어 「다시 시도」 를 누르면 같은 키로 이어진다.
   * 창마다 제 값을 든다 — 마지막 창이 사라져야 닫힌 것이다.
   */
  setAttended(open: boolean, panelId: string): void {
    if (open) {
      this.attendants.add(panelId)
      this.refreshLimit() // 앱이 뒤에 있던 사이에 풀렸을 수 있다
    } else {
      this.attendants.delete(panelId)
    }
    const p = this.state.pending
    if (!this.attended && p && stageIsResending(p.stage)) this.flight?.abort()
  }

  /** 공통 계층이 이 턴을 다시 보내려고(또는 다시 물으려고) 쉬기 시작했다. */
  noteResend(key: string, reason: ResendReason): void {
    const turn = this.state.pending
    if (!turn || turn.key !== key || !stageIsBusy(turn.stage)) return
    this.patch({ pending: turnResending(turn, reason) })
    if (!this.attended) this.flight?.abort()
  }

  private async fly(): Promise<void> {
    const turn = this.state.pending
    if (!turn) return
    const controller = new AbortController()
    this.flight = controller
    const epoch = this.accountEpoch
    let result: { ok: true; answer: GuideAnswer } | { ok: false; failure: GuideFailure }
    try {
      result = { ok: true, answer: await this.env.send(turn, controller.signal, (reason) => this.noteResend(turn.key, reason)) }
    } catch (e) {
      const failure = e instanceof GuideSendError ? e.failure : classifyNoResponse('unreached')
      result = { ok: false, failure }
    }
    // 기다리는 사이에 코스가 바뀌었거나 대화를 지웠다 — 늦게 온 답을 남의 대화에 적지 않는다.
    if (this.state.pending?.key !== turn.key) return
    this.flight = null
    // 기다리는 사이에 계정이 바뀌었다 — 앞 계정의 답도 한도(429)도 지금 계정의 것이 아니다. 통째로 버린다.
    if (epoch !== this.accountEpoch) {
      this.patch({ pending: null })
      return
    }
    this.readQuota()

    if (result.ok) {
      const { answer } = result
      const turns: GuideTurnMessage[] = [...this.state.turns, { id: this.nextTurnId++, role: 'assistant', text: answer.reply === '' ? tr('답을 받지 못했습니다.') : answer.reply }]
      this.setLimit({ kind: 'clear' })
      // 장소를 새로 찾아 왔을 때만 목록을 갈아 끼운다 — 「어디 기준이야?」 같은 되물음에는 장소가 안 실려 오는데, 그때 목록까지 지우면 방금 받은
      // 추천과 ⊕ 담기 단추가 채팅 한 번에 사라진다(2026-08-27 사용자 지적). 고른 것을 놓는 것도 그때만이다.
      const found = answer.places.length > 0
      this.patch({
        pending: null, tools: answer.tools, turns, lastAnswer: answer, answerTick: this.state.answerTick + 1,
        ...(found ? { places: answer.places, picked: null } : {}),
      })
      return
    }
    // 계약 응답별로 갈라 말한다 — 401 가입 · 503 잠시 뒤 · 시간 초과 · 오프라인 · 연결 실패.
    const failure = result.failure
    this.patch({ failure, pending: turnFailing(turn, failure) })
    // 다시 해 볼 만한 실패면 턴을 남긴다 — 「다시 시도」 가 같은 키로 보낸다.
    this.setLimit(failure.kind === 'limitReached' ? { kind: 'reached', block: makeBlock(failure.retryAfter, this.env.now()) } : { kind: 'clear' })
  }

  // ---------------------------------------------------------------- 챗봇 한도

  /** 풀리는 때가 지났으면 잠금을 푼다. 창을 열 때와 보내기 직전에도 본다 — 타이머는 앱이 멈춰 있으면 늦는다. */
  refreshLimit(): void {
    const { limit } = this.state
    if (limit.kind !== 'reached' || !blockIsOver(limit.block, this.env.now())) return
    this.setLimit({ kind: 'lifted' })
  }

  /** 계정이 바뀌었다 — 한도와 남은 양은 앞 계정의 것이다. 떠 있는 요청도 앞 계정의 것이라 끊고, 늦게 돌아온 결과는 `fly` 가 버린다. */
  forgetLimit(): void {
    this.accountEpoch += 1
    this.flight?.abort()
    this.setLimit({ kind: 'clear' })
    this.quotaSource = null
    this.patch({ quota: null })
    if (this.state.failure && isLimit(this.state.failure)) this.patch({ failure: null, pending: null })
  }

  /** 응답이 실어 온 남은 양. 헤더가 없었으면(옛 서버, 멱등 키로 되돌려 준 답) 장부가 앞의 값을 그대로 든다 — 옮겨 둔 시각을 다시 계산하지 않는다. */
  private readQuota(): void {
    const entry = this.env.quota()
    if (entry === this.quotaSource || (entry && this.quotaSource && sameEntry(entry, this.quotaSource))) return
    this.quotaSource = entry
    this.patch({ quota: entry ? quotaFromEntry(entry, this.env.wall(), this.env.now()) : null })
  }

  private setLimit(limit: GuideLimitState): void {
    this.limitWake?.abort()
    this.limitWake = null
    this.patch({ limit })
    if (limit.kind !== 'reached' || limit.block.until === null) return
    const until = limit.block.until
    const wake = new AbortController()
    this.limitWake = wake
    void (async () => {
      // 시계가 어긋나 일찍 깨면 남은 만큼 더 쉰다.
      while (!wake.signal.aborted) {
        const left = until - this.env.now()
        if (left <= 0) break
        await this.env.sleep(left, wake.signal)
      }
      if (!wake.signal.aborted) this.refreshLimit()
    })()
  }

  // ---------------------------------------------------------------- 대화

  /** 편집 화면이 뜰 때 부른다. 다른 코스면 대화·AI 장소·방 번호를 새로 시작한다. 같은 코스를 다시 열면 그대로 이어진다. */
  bind(key: string): void {
    if (key === this.courseKey) return
    this.courseKey = key
    this.clear()
    this.sessionId = newUuid()
  }

  /** 저장 전 코스가 서버 id 를 얻었다 — 같은 코스다. 열쇠만 바꾸고 대화는 둔다. */
  rekey(key: string): void {
    this.courseKey = key
  }

  get boundCourse(): string | null {
    return this.courseKey
  }

  /** 대화를 처음부터 다시. 방 번호는 그대로 둔다 — 서버가 기억하는 장소까지 지울 이유는 없다. */
  clear(): void {
    // 보내던 턴도 버린다 — 떠 있는 요청을 끊고, 늦게 온 답은 `fly` 가 적지 않는다.
    this.flight?.abort()
    this.flight = null
    // 한도는 계정의 것이라 남는다. 다만 「이제 다시 물어볼 수 있어요」 는 지운 대화의 말이다.
    const limit: GuideLimitState = this.state.limit.kind === 'lifted' ? { kind: 'clear' } : this.state.limit
    this.patch({ pending: null, turns: [], tools: [], places: [], picked: null, failure: null, lastAnswer: null, limit })
  }

  setPicked(place: RoutePlace | null): void {
    this.patch({ picked: place })
  }
}

const sameEntry = (a: LedgerEntry, b: LedgerEntry): boolean => a.limit === b.limit && a.remaining === b.remaining && a.resetsAtMs === b.resetsAtMs

/** 앱에 하나뿐인 대화 창구. 계획 화면에서 묻던 것을 길찾기에서 이어 묻고, 돌아와도 그대로다(2026-08-28 사용자 요청). */
export const sharedGuide = new GuideSession()

/** 컴포넌트가 대화 상태를 구독한다. */
export function useGuideState(session: GuideSession): GuideState {
  return useStore(session.store, (s) => s)
}

