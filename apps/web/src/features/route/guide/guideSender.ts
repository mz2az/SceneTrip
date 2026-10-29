/*
 * 챗봇 한 턴을 서버에 보내고, 규칙(`guideRetry.ts`)대로 다시 보내거나 되묻는다 — iOS RouteGuide.send + RetryingSession 의 챗봇 갈래.
 *
 * 실제 호출은 `defaultTransport` 하나뿐이고, 공용 클라이언트(`@/api/client`)의 `api.POST('/guide/chat')` 를 쓴다 — 개발 중에는
 * `.env.local` 의 안전장치(`VITE_BLOCK_PATHS=/guide`)가 막는다. 시험·화면 확인은 가짜 전송(`env.post`)을 끼워 넣는다.
 *
 * 공용 클라이언트가 자동으로 다시 보내지 않게 `NO_RETRY_HEADER` 를 단다 — 다시 보내는 것은 여기서만, **멱등 키를 실었고 서버가 키를
 * 아는 때에 한해** 한다(키 없이 다시 보내면 이력이 두 번 쌓이고 `cart.add` 가 두 번 저장된다).
 */
import type { components } from '@/api/generated/schema'
import { NO_RETRY_HEADER, api } from '@/api/client'
import { installHeader } from '@/state/install'
import type { Lang } from '@/i18n'
import { type GuideFailure, classifyHttp, classifyNoResponse } from './guideFailure'
import { CHAT_TUNING, type ChatHistory, type ChatOutcome, type ResendReason, newHistory, nextChatStep, recordStep, resendReason } from './guideRetry'
import { recordResponse, retryAfterOf } from './rateLimit'
import type { GuideChatRequest } from './guideTurn'
import { type RoutePlace, fromGuide } from '../poi/poiPlace'

export type GuideChatReply = components['schemas']['GuideChatReply']
export type GuideEffect = components['schemas']['GuideEffect']
export type GuideUiDirective = components['schemas']['GuideUiDirective']

/** 한 턴의 답 — `reply`·`places` 는 시트가 그리고, `effects`·`ui` 는 편집 화면이 적용한다. 모르는 명령은 무시한다. */
export interface GuideAnswer {
  reply: string
  /** 모델이 실제로 부른 도구. 화면에 보여 준다 — 근거 없이 답한 것을 알아볼 수 있어야 한다. */
  tools: string[]
  places: RoutePlace[]
  seconds: number
  effects: GuideEffect[]
  ui: GuideUiDirective[]
  route: GuideChatReply['route']
}

export function answerOf(reply: GuideChatReply): GuideAnswer {
  return {
    reply: reply.reply, tools: reply.toolsUsed.map((t) => t.tool), places: reply.places.map(fromGuide), seconds: reply.tookSeconds,
    effects: reply.effects, ui: reply.ui, route: reply.route ?? null,
  }
}

/** 서버가 답한 것 — 상태·헤더·본문. */
export interface WireResponse {
  status: number
  headers: Pick<Headers, 'get'>
  body: unknown
}

/** 응답을 받지 못했다 — 왜인지. */
export class TransportError extends Error {
  constructor(readonly reason: 'offline' | 'network') {
    super(`guide transport: ${reason}`)
  }
}

/** 보내는 쪽이 던지는 실패 — 화면이 이유별로 말한다. */
export class GuideSendError extends Error {
  constructor(readonly failure: GuideFailure) {
    super(`guide send: ${failure.kind}`)
  }
}

export interface SenderEnv {
  /** 한 번 보낸다. 응답이 없으면 `TransportError`. */
  post(request: GuideChatRequest, key: string, lang: Lang, source: string): Promise<WireResponse>
  nowMs(): number
  /** 쉰다. 취소되면 일찍 깬다. */
  sleep(ms: number, signal: AbortSignal): Promise<void>
  random(): number
}

/** 이 멱등 키를 처음 보낸 때(ms) — 「다시 시도」 로 새 요청이 나가도 이어서 센다(서버의 「처리 중 1 분」 경계는 키를 처음 보낸 때부터다). */
const keyClock = new Map<string, number>()
const KEY_CLOCK_MAX = 32

function keyStarted(key: string, nowMs: number): number {
  const known = keyClock.get(key)
  if (known !== undefined) return known
  if (keyClock.size >= KEY_CLOCK_MAX) {
    const oldest = [...keyClock.entries()].sort((a, b) => a[1] - b[1])[0]
    if (oldest) keyClock.delete(oldest[0])
  }
  keyClock.set(key, nowMs)
  return nowMs
}
export const forgetKeyClock = (key: string): void => void keyClock.delete(key)

const CHAT_PATH = '/guide/chat'

/** 공용 클라이언트로 실제로 보낸다. */
export const defaultTransport: SenderEnv['post'] = async (request, key, _lang, source) => {
  try {
    const result = await api.POST(CHAT_PATH, {
      params: { header: { ...installHeader(), 'Idempotency-Key': key } },
      body: request,
      headers: { [NO_RETRY_HEADER]: '1', 'X-SceneTrip-Coordinate-Source': source },
    })
    return { status: result.response.status, headers: result.response.headers, body: result.data ?? result.error ?? null }
  } catch {
    throw new TransportError(typeof navigator !== 'undefined' && navigator.onLine === false ? 'offline' : 'network')
  }
}

const defaultEnv: SenderEnv = {
  post: defaultTransport,
  nowMs: () => Date.now(),
  sleep: (ms, signal) =>
    new Promise<void>((resolve) => {
      if (signal.aborted) return resolve()
      const t = setTimeout(done, ms)
      function done() {
        clearTimeout(t)
        signal.removeEventListener('abort', done)
        resolve()
      }
      signal.addEventListener('abort', done)
    }),
  random: Math.random,
}

const codeOf = (body: unknown): string | null => {
  const c = (body as { code?: unknown } | null)?.code
  return typeof c === 'string' ? c : null
}

const isOk = (status: number): boolean => status >= 200 && status < 300

export interface SendArgs {
  request: GuideChatRequest
  key: string
  lang: Lang
  /** 서버가 멱등 키를 아는가 — 알면 재시도·되묻기를 한다. 모르면 한 번만 보내고 50초에 끊는다(옛 서버). */
  keyed: boolean
  source?: string
  /** 창을 닫는 등 화면이 그만 기다리게 했다. */
  signal: AbortSignal
  onResend?: (reason: ResendReason) => void
}

/**
 * 만들어 둔 요청을 보낸다. 「전송」 도 「다시 시도」 도 이리로 온다. 벽: 키가 있으면 턴 전체 110+5초, 없으면 50초에 끊고 다시 보내지 않는다.
 * 실패는 `GuideSendError` 로 던지고, 답이 오면 `GuideAnswer`.
 */
export async function sendGuideTurn(args: SendArgs, env: SenderEnv = defaultEnv): Promise<GuideAnswer> {
  const T = CHAT_TUNING
  const { request, key, lang, keyed, signal } = args
  const source = args.source ?? 'device'
  const wallMs = (keyed ? T.chatBudget + T.chatWallMargin : T.chatAttemptTimeout) * 1000
  const attemptMs = T.chatAttemptTimeout * 1000

  // 바깥에서 끊거나(창 닫기) 벽을 넘기면 기다리던 것을 모두 깨운다.
  const turn = new AbortController()
  let wallHit = false
  const onOuter = () => turn.abort()
  if (signal.aborted) turn.abort()
  signal.addEventListener('abort', onOuter)
  const wallTimer = setTimeout(() => {
    wallHit = true
    turn.abort()
  }, wallMs)

  const startedAt = env.nowMs()
  const keyStart = keyStarted(key, startedAt)
  let history: ChatHistory = newHistory()
  /** 한도(`RATE_LIMITED`)를 기다린 시간 — 벽에서 세지 않는다. */
  let waitedForLimitMs = 0

  const stopped = (): GuideSendError => new GuideSendError(classifyNoResponse(wallHit ? 'timedOut' : 'interrupted'))
  try {
    for (;;) {
      if (turn.signal.aborted) throw stopped()
      let outcome: ChatOutcome
      let response: WireResponse | null = null
      try {
        response = await raceAttempt(env.post(request, key, lang, source), attemptMs, turn.signal)
        outcome = { kind: 'http', status: response.status, code: codeOf(response.body), retryAfter: retryAfterOf(response.headers) }
        recordResponse(CHAT_PATH, response.headers, env.nowMs())
      } catch (e) {
        if (e instanceof AttemptStopped) {
          if (e.why === 'cancelled') throw stopped()
          outcome = { kind: 'noResponse', nature: 'lost', reason: 'timedOut' } // 시간을 넘겼다 — 닿았는지 모른다
        } else if (e instanceof TransportError) {
          // 오프라인은 1·2·4초 안에 돌아오지 않는다 — 다시 보내지 않는다. 그 밖의 끊김은 닿았는지 모른다.
          outcome = e.reason === 'offline' ? { kind: 'noResponse', nature: 'final', reason: 'offline' } : { kind: 'noResponse', nature: 'lost', reason: 'unreached' }
        } else {
          throw e
        }
      }
      if (response && isOk(response.status)) return answerOf(response.body as GuideChatReply)

      // 서버가 이 키를 받아들이지 않았다(429 — 처리 전에 거절) — 다음에 보낼 때부터 다시 센다.
      if (outcome.kind === 'http' && outcome.status === 429) keyClock.delete(key)

      const elapsed = (env.nowMs() - startedAt - waitedForLimitMs) / 1000
      const keyAge = (env.nowMs() - keyStart) / 1000
      const step = nextChatStep({ keyed, history, outcome, elapsed, keyAge, jitter: env.random() * 2 - 1 })
      if (step.kind === 'stop') throw new GuideSendError(failureOf(outcome))
      history = recordStep(step, outcome, history)
      const reason = resendReason(step)
      if (reason) args.onResend?.(reason)
      const waitMs = step.after * 1000
      if (step.kind === 'retry' && step.forLimit) waitedForLimitMs += waitMs
      await env.sleep(waitMs, turn.signal)
    }
  } finally {
    clearTimeout(wallTimer)
    signal.removeEventListener('abort', onOuter)
  }
}

function failureOf(o: ChatOutcome): GuideFailure {
  if (o.kind === 'http') return classifyHttp({ status: o.status, code: o.code, retryAfter: o.retryAfter })
  return classifyNoResponse(o.reason)
}

class AttemptStopped extends Error {
  constructor(readonly why: 'timeout' | 'cancelled') {
    super(why)
  }
}

/** 한 번의 요청이 기다리는 벽과 취소를 건다. 공용 클라이언트가 요청을 끊을 수 없어(자체 AbortController) 기다리기를 그만두는 것이다 — 서버는 계속 일할 수 있고, 같은 키로 다시 물으면 그 답을 받는다. */
function raceAttempt<T>(work: Promise<T>, ms: number, cancel: AbortSignal): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const timer = setTimeout(() => finish(() => reject(new AttemptStopped('timeout'))), ms)
    const onAbort = () => finish(() => reject(new AttemptStopped('cancelled')))
    let done = false
    function finish(settle: () => void) {
      if (done) return
      done = true
      clearTimeout(timer)
      cancel.removeEventListener('abort', onAbort)
      settle()
    }
    if (cancel.aborted) return onAbort()
    cancel.addEventListener('abort', onAbort)
    work.then(
      (v) => finish(() => resolve(v)),
      (e) => finish(() => reject(e)),
    )
  })
}
