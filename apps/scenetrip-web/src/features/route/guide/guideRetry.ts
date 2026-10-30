/*
 * 챗봇 턴을 다시 보낼지의 규칙 — iOS Models/RetryRules.swift 의 챗봇 갈래(`keyedChat`·`never`) (MZ2AZ-366 PR B, 계획 `app-retry.md` §4).
 *
 * 챗봇은 멱등이 아니다 — 토큰이 나가고 장바구니에 담는다. **멱등 키를 실었고 서버가 그 키를 알 때만** 다시 보낸다. 틀어지면
 * 모델을 두 번 부르고 한도를 두 번 깎는다. 순수 함수다 — 시계도 난수도 네트워크도 없다.
 * (조회·쓰기의 일반 규칙은 `@/api/retry.ts` 에 있다.)
 */
import { GUIDE_LIMIT_CODE } from './guideLimit'
import { IN_PROGRESS_CODE } from './guideFailure'

export const CHAT_TUNING = {
  /** 지수 백오프(초). 재시도 n 번째가 n 번째 값을 쓴다. */
  backoff: [1, 2, 4],
  /** 지터 — 간격의 ±20%. */
  jitter: 0.2,
  /** 끊김·502·503·504·500 에 자동으로 한 번. 그 뒤는 사람이 누르는 「다시 시도」(같은 키). */
  chatRetries: 1,
  /** 한 번의 요청 벽(초). 서버 벽(40초)·게이트웨이 벽(45초)보다 길다. */
  chatAttemptTimeout: 50,
  /** 재시도·되묻기까지 합친 턴 전체의 벽(초). */
  chatBudget: 110,
  /** 화면이 따로 거는 벽은 이만큼 더 길다 — 이 계층이 먼저 끝나 제 이유(끊김·503)를 말하게. */
  chatWallMargin: 5,
  /** `409 IDEMPOTENCY_IN_PROGRESS` — 서버가 같은 키를 아직 처리 중이다. 이 간격으로 같은 키로 다시 묻는다. */
  inProgressPollSeconds: 3,
  /**
   * 그 키를 처음 보낸 때부터 이 초가 지나면 더 되묻지 않는다. 서버는 처리 중인 채 1 분이 지난 키를 죽은 것으로 보고 그 뒤에 같은
   * 키로 온 요청을 새로 처리한다 — 첫 처리가 아직 살아 있으면 모델을 두 번 부르고 한도를 두 번 깎는다. 그 1 분보다 짧게 끊는다.
   */
  inProgressDeadline: 55,
  /** 되묻기 횟수의 상한. 위의 시각 벽이 먼저 닿는다(3초 간격이면 열여덟 번) — 이것은 뒤를 받치는 값이다. */
  inProgressMaxPolls: 20,
  /** `RATE_LIMITED` 를 조용히 기다리는 상한(초). */
  rateLimitMaxWait: 60,
  /** `Retry-After` 가 없을 때(게이트웨이가 낸 429) 기다릴 시간. */
  rateLimitDefaultWait: 1,
  /** 한도 대기에 더하는 지터(초)의 상한 — 줄이는 쪽으로는 쓰지 않는다(일찍 보내면 또 429 다). */
  rateLimitJitter: 1,
  /** 한도를 기다린 뒤 남은 벽이 이보다 작으면 보내지 않는다 — 보내 봐야 답을 받을 시간이 없다. */
  minAttemptSeconds: 3,
} as const

/** 한 번 보낸 결과. */
export type ChatOutcome =
  /** 응답이 없다. `unreached` 는 닿지 않은 것이 확실(연결 거부), `lost` 는 닿았는지 모름(끊김·시간 초과), `final` 은 다시 보내도 같다(오프라인·취소). */
  | { kind: 'noResponse'; nature: 'unreached' | 'lost' | 'final'; reason: 'offline' | 'unreached' | 'timedOut' }
  | { kind: 'http'; status: number; code: string | null; retryAfter: number | null }

/** 지금까지 다시 보낸 내력. */
export interface ChatHistory {
  retries: number
  retried500: boolean
  waitedForLimit: boolean
  mayHaveReached: boolean
  /** `409 처리 중` 을 받고 같은 키로 다시 물은 횟수. `retries` 와 따로 센다 — 새 처리를 일으키지 않는다. */
  polls: number
}

export const newHistory = (): ChatHistory => ({ retries: 0, retried500: false, waitedForLimit: false, mayHaveReached: false, polls: 0 })

export type ChatStep =
  /** `after` 초 뒤 같은 요청을 다시 보낸다. `forLimit` 이면 한도가 풀리기를 기다리는 것이다. */
  | { kind: 'retry'; after: number; forLimit: boolean }
  /** 서버가 같은 키를 처리 중이다(409) — `after` 초 뒤 같은 요청으로 다시 묻는다. 재시도 횟수에 넣지 않는다. */
  | { kind: 'poll'; after: number }
  | { kind: 'stop' }

type Nature = 'unreached' | 'lost' | 'unavailable' | 'serverError' | 'rateLimited' | 'inProgress' | 'final'

/** 결과의 성격. */
export function natureOf(o: ChatOutcome): Nature {
  if (o.kind === 'noResponse') return o.nature
  switch (o.status) {
    case 502:
    case 503:
    case 504:
      return 'unavailable'
    case 500:
      return 'serverError'
    // 유료 한도(`GUIDE_LIMIT_REACHED`)는 기다려도 안 풀린다 — 화면이 안내한다.
    case 429:
      return o.code === null || o.code === 'RATE_LIMITED' ? 'rateLimited' : 'final'
    case 409:
      return o.code === IN_PROGRESS_CODE ? 'inProgress' : 'final'
    default:
      return 'final'
  }
}

/**
 * - `keyed`: 이 요청이 멱등 키를 실었고 **서버가 그 키를 안다**. 아니면 자동으로 다시 보내지 않는다(옛 서버는 같은 턴을 두 번 처리한다).
 * - `elapsed`: 첫 요청을 보낸 뒤 흐른 시간(초). 한도를 기다린 시간은 뺀 값.
 * - `jitter`: -1…1. 시험은 0 을 준다.
 * - `keyAge`: 이 멱등 키를 처음 보낸 때부터 흐른 시간(초). 「다시 시도」 로 새 요청이 나가도 이어서 센다. 모르면 `elapsed`.
 */
export function nextChatStep(p: { keyed: boolean; history: ChatHistory; outcome: ChatOutcome; elapsed: number; jitter?: number; keyAge?: number | null }): ChatStep {
  const { keyed, history, outcome, elapsed, jitter = 0, keyAge = null } = p
  const T = CHAT_TUNING
  if (!keyed) return { kind: 'stop' }
  const nature = natureOf(outcome)
  // 처리 중 — 「자동 한 번」 을 이미 썼어도 묻는다. 응답을 잃고 다시 보낸 것이 바로 이 답을 받는다.
  if (nature === 'inProgress') {
    const wait = T.inProgressPollSeconds
    const withinServerWindow = (keyAge ?? elapsed) + wait < T.inProgressDeadline
    if (history.polls >= T.inProgressMaxPolls || elapsed + wait >= T.chatBudget || !withinServerWindow) return { kind: 'stop' }
    return { kind: 'poll', after: wait }
  }
  if (history.retries >= T.chatRetries) return { kind: 'stop' }
  switch (nature) {
    case 'rateLimited':
      return limitStep(outcome, history, elapsed, jitter)
    case 'unreached':
    case 'lost':
    case 'unavailable':
      return backoff(history, elapsed, jitter)
    case 'serverError':
      return history.retried500 ? { kind: 'stop' } : backoff(history, elapsed, jitter)
    default:
      return { kind: 'stop' }
  }
}

function limitStep(outcome: ChatOutcome, history: ChatHistory, elapsed: number, jitter: number): ChatStep {
  const T = CHAT_TUNING
  if (history.waitedForLimit || outcome.kind !== 'http') return { kind: 'stop' }
  const wait = outcome.retryAfter ?? T.rateLimitDefaultWait
  // 기다린 시간은 벽에서 빼지만, 그 전에 이미 벽을 거의 다 썼으면 기다려 봐야 보낼 시간이 없다.
  if (wait > T.rateLimitMaxWait || T.chatBudget - elapsed < T.minAttemptSeconds) return { kind: 'stop' }
  return { kind: 'retry', after: Math.max(0, wait) + T.rateLimitJitter * Math.min(1, Math.abs(jitter)), forLimit: true }
}

function backoff(history: ChatHistory, elapsed: number, jitter: number): ChatStep {
  const T = CHAT_TUNING
  const base = T.backoff[Math.min(history.retries, T.backoff.length - 1)]
  const delay = base * (1 + T.jitter * Math.max(-1, Math.min(1, jitter)))
  // 쉬고 나면 벽을 넘는다 — 보내 봐야 기다릴 시간이 없다.
  if (elapsed + delay >= T.chatBudget) return { kind: 'stop' }
  return { kind: 'retry', after: delay, forLimit: false }
}

/** 이번 결과를 내력에 적는다. `step` 은 방금 `nextChatStep` 이 돌려준 것. */
export function recordStep(step: ChatStep, outcome: ChatOutcome, history: ChatHistory): ChatHistory {
  const next = { ...history }
  if (step.kind === 'poll') {
    next.polls += 1
    next.mayHaveReached = true
    return next
  }
  if (step.kind !== 'retry') return next
  next.retries += 1
  if (step.forLimit) next.waitedForLimit = true
  switch (natureOf(outcome)) {
    case 'serverError':
      next.retried500 = true
      next.mayHaveReached = true
      break
    case 'lost':
    case 'unavailable':
      next.mayHaveReached = true
      break
  }
  return next
}

/** 화면이 「다시 연결하는 중」·「요청이 많아 기다리는 중」 을 말하게 하는 다시 보내기의 이유. */
export type ResendReason = 'failed' | 'inProgress' | 'rateLimited'

export function resendReason(step: ChatStep): ResendReason | null {
  if (step.kind === 'poll') return 'inProgress'
  if (step.kind === 'retry') return step.forLimit ? 'rateLimited' : 'failed'
  return null
}

/** 유료 한도 응답인가 — 키를 서버가 받아들이지 않았다(처리 전에 거절). */
export const isPaidLimit = (o: ChatOutcome): boolean => o.kind === 'http' && o.status === 429 && o.code === GUIDE_LIMIT_CODE
