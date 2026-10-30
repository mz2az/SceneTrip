/*
 * 실패한 요청을 다시 보낼 것인가 — iOS `RetryRules` (Models/RetryRules.swift) 의 줄인 판.
 * 순수 함수라 단위 시험이 선다(retry.test.ts).
 *
 * 다시 보낸다는 것은 같은 요청이 서버에 두 번 닿을 수 있다는 뜻이다. 조회와 PUT·DELETE 는 두 번 닿아도 결과가
 * 같다. POST 는 아니다 — 서버에 닿지 않은 것이 확실할 때(연결 거부 등)만 다시 보낸다.
 */
export const RETRY_TUNING = {
  /** 지수 백오프(초). 재시도 n 번째가 n 번째 값을 쓴다. */
  backoff: [1, 2, 4],
  /** 지터 — 간격의 ±20%. */
  jitter: 0.2,
  readRetries: 3,
  idempotentWriteRetries: 2,
  unsafeWriteRetries: 2,
  /** 한 번의 요청이 기다리는 벽(초). */
  readTimeout: 15,
  writeTimeout: 30,
  /** RATE_LIMITED 를 조용히 기다리는 상한(초). */
  rateLimitMaxWait: 60,
  rateLimitDefaultWait: 1,
} as const

export type Lane = 'read' | 'idempotentWrite' | 'unsafeWrite'

export function laneFor(method: string): Lane {
  const m = method.toUpperCase()
  if (m === 'GET' || m === 'HEAD') return 'read'
  if (m === 'PUT' || m === 'DELETE') return 'idempotentWrite'
  return 'unsafeWrite'
}

export function maxRetries(lane: Lane): number {
  switch (lane) {
    case 'read':
      return RETRY_TUNING.readRetries
    case 'idempotentWrite':
      return RETRY_TUNING.idempotentWriteRetries
    case 'unsafeWrite':
      return RETRY_TUNING.unsafeWriteRetries
  }
}

export type Outcome = { kind: 'status'; status: number; retryAfter?: number | null } | { kind: 'network'; reachedServer: boolean }

export type Decision = { retry: true; waitSeconds: number } | { retry: false }

/** `attempt` 는 지금까지 다시 보낸 횟수(0 부터). `random` 은 0~1 (시험에서 고정). */
export function decide(lane: Lane, outcome: Outcome, attempt: number, random = Math.random): Decision {
  if (outcome.kind === 'status' && outcome.status === 429) {
    const wait = outcome.retryAfter ?? RETRY_TUNING.rateLimitDefaultWait
    if (wait > RETRY_TUNING.rateLimitMaxWait) return { retry: false }
    return attempt < 2 ? { retry: true, waitSeconds: wait + random() } : { retry: false }
  }
  if (attempt >= maxRetries(lane)) return { retry: false }
  const retryableStatus = outcome.kind === 'status' && [502, 503, 504].includes(outcome.status)
  if (outcome.kind === 'status' && !retryableStatus) return { retry: false }
  // 쓰기(POST)는 서버에 닿았을 수 있으면 다시 보내지 않는다.
  if (lane === 'unsafeWrite') {
    const notReached = outcome.kind === 'network' && !outcome.reachedServer
    if (!notReached) return { retry: false }
  }
  const base = RETRY_TUNING.backoff[Math.min(attempt, RETRY_TUNING.backoff.length - 1)]
  const jitter = 1 + (random() * 2 - 1) * RETRY_TUNING.jitter
  return { retry: true, waitSeconds: base * jitter }
}
