/*
 * 가이드가 답하지 못한 이유 — iOS RouteTab/RouteGuideFailure.swift. 계약이 정한 응답별로 갈린다
 * (`POST /guide/chat` · `POST /guide/plan`, docs/api/errors.md).
 *
 * 「준비 중」은 서버가 없던 때의 말이다 — 401 도 503 도 「준비 중」이면 사용자는 가입을 해야 하는지 잠시 뒤 다시 눌러야 하는지
 * 알 수 없다. 응답이 없을 때는 왜 없는지를 가른다(MZ2AZ-366) — 기기가 오프라인인지, 서버에 닿지 못했는지, 기다리다 그만둔 것인지.
 */
import { tr } from '@/i18n'
import { GUIDE_LIMIT_CODE, blockMessage, makeBlock } from './guideLimit'

export type GuideFailure =
  | { kind: 'locationConsentRequired' }
  | { kind: 'consentRequired' }
  /** 가입해야 부를 수 있다 (401). */
  | { kind: 'signInRequired' }
  /** 로그인은 했는데 세션이 깨졌다 (401, `SIGN_IN_REQUIRED` 가 아닌 코드). */
  | { kind: 'sessionExpired' }
  /** 요청이 계약을 어겼다 (400). 서버의 message 는 화면에 그대로 띄우지 않는다. */
  | { kind: 'badRequest' }
  /** 에이전트가 응답하지 않는다 (503 `GUIDE_UNAVAILABLE`, 게이트웨이의 502·504). 규칙 기반으로 조용히 떨어지지 않는다. */
  | { kind: 'unavailable' }
  /** 기다리다 그만뒀다 — 턴 전체의 벽을 넘겼거나, 요청이 시간을 넘겼거나, 서버가 끝내 「처리 중」(409)이다. */
  | { kind: 'timedOut' }
  | { kind: 'offline' }
  /** 서버에 닿지 못했다(연결 거부·끊김) — 기기 탓이 아니다. */
  | { kind: 'unreachable' }
  /** 분당 요청 한도(429 `RATE_LIMITED`, 또는 `code` 없는 게이트웨이의 429). */
  | { kind: 'rateLimited' }
  /** 답을 기다리던 중에 화면이 그만 기다리게 했다(가이드 창을 닫음). */
  | { kind: 'interrupted' }
  /** `422 IDEMPOTENCY_KEY_REUSED` — 같은 멱등 키에 다른 내용을 보냈다. 앱 버그다. */
  | { kind: 'keyReused' }
  /** 챗봇 한도(429 `GUIDE_LIMIT_REACHED`). `retryAfter` 는 풀릴 때까지의 초, 없으면 모른다. 자동으로 다시 보내지 않는다. */
  | { kind: 'limitReached'; retryAfter: number | null }
  | { kind: 'other'; status: number }

export const IN_PROGRESS_CODE = 'IDEMPOTENCY_IN_PROGRESS'
export const KEY_REUSED_CODE = 'IDEMPOTENCY_KEY_REUSED'

/** 「다시 시도」 가 무엇을 보내야 하나 (MZ2AZ-366). */
export type RetryKind = 'sameKey' | 'newKey' | 'none'

/** 서버가 응답한 것 — 상태·본문 `code`·`Retry-After`. */
export interface HttpFailure {
  status: number
  code: string | null
  retryAfter: number | null
}

/** 응답이 없었다 — 왜인지. */
export type NoResponse = 'offline' | 'unreached' | 'timedOut' | 'interrupted' | 'locationConsent' | 'consent'

/** 401 을 가입 요청과 세션 깨짐으로 가른다 (AuthRules.action). */
function unauthorized(code: string | null): GuideFailure {
  switch (code) {
    case 'ACCESS_TOKEN_EXPIRED':
    case 'ACCESS_TOKEN_INVALID':
    case 'SESSION_REQUIRED':
    case 'REFRESH_TOKEN_INVALID':
      return { kind: 'sessionExpired' }
    default:
      return { kind: 'signInRequired' }
  }
}

/** 오류를 계약 응답으로 분류한다. */
export function classifyHttp({ status, code, retryAfter }: HttpFailure): GuideFailure {
  switch (status) {
    case 400:
      return { kind: 'badRequest' }
    case 401:
      return unauthorized(code)
    case 409:
      if (code === IN_PROGRESS_CODE) return { kind: 'timedOut' }
      return { kind: 'other', status }
    case 422:
      if (code === KEY_REUSED_CODE) return { kind: 'keyReused' }
      return { kind: 'other', status }
    case 429:
      if (code === GUIDE_LIMIT_CODE) return { kind: 'limitReached', retryAfter }
      // 분당 한도. 공통 계층이 `Retry-After` 뒤에 한 번 다시 보내 본 뒤다.
      if (code === null || code === 'RATE_LIMITED') return { kind: 'rateLimited' }
      return { kind: 'other', status }
    case 502:
    case 503:
    case 504:
      return { kind: 'unavailable' }
    default:
      return { kind: 'other', status }
  }
}

export function classifyNoResponse(reason: NoResponse): GuideFailure {
  switch (reason) {
    case 'offline':
      return { kind: 'offline' }
    case 'unreached':
      return { kind: 'unreachable' }
    case 'timedOut':
      return { kind: 'timedOut' }
    case 'interrupted':
      return { kind: 'interrupted' }
    case 'locationConsent':
      return { kind: 'locationConsentRequired' }
    case 'consent':
      return { kind: 'consentRequired' }
  }
}

/** 다시 해 볼 만한가. 서버 사정·끊김·시간 초과는 그렇다 — 요청이 틀렸거나(400) 로그인이 필요하면(401) 아니다. */
export function failureRetry(f: GuideFailure): RetryKind {
  switch (f.kind) {
    case 'unavailable':
    case 'timedOut':
    case 'offline':
    case 'unreachable':
    case 'rateLimited':
    case 'interrupted':
    // 한도에 걸린 턴은 서버가 처리하지 않았다(키도 지웠다) — 풀린 뒤 같은 키로 다시 보낸다. 단추는 풀린 뒤에만 뜬다.
    case 'limitReached':
    case 'consentRequired':
    case 'locationConsentRequired':
      return 'sameKey'
    case 'keyReused':
      return 'newKey'
    // 500 은 공통 계층이 한 번 다시 보내 본 뒤다 — 그래도 사람이 한 번 더 해 볼 수 있다.
    case 'other':
      return f.status === 500 ? 'sameKey' : 'none'
    case 'signInRequired':
    case 'sessionExpired':
    case 'badRequest':
      return 'none'
  }
}

/** 챗봇 한도인가 — 대화창은 이것을 실패 줄이 아니라 한도 안내로 그린다. */
export const isLimit = (f: GuideFailure): boolean => f.kind === 'limitReached'

/** 화면에 보이는 한 줄. 안드로이드와 같은 문구여야 한다. */
export function failureMessage(f: GuideFailure): string {
  switch (f.kind) {
    case 'locationConsentRequired':
      return tr('위치기반서비스 이용에 동의한 뒤 다시 시도해 주세요.')
    case 'consentRequired':
      return tr('AI 기능 사용을 허용한 뒤 다시 시도해 주세요.')
    case 'signInRequired':
      return tr('여행 가이드는 가입한 분만 쓸 수 있어요')
    case 'sessionExpired':
      return tr('로그인이 풀렸어요. 다시 로그인해 주세요')
    case 'badRequest':
      return tr('요청을 처리하지 못했어요. 조건을 바꿔 다시 해 주세요')
    case 'unavailable':
      return tr('가이드가 잠시 응답하지 않아요. 잠시 뒤 다시 물어봐 주세요')
    case 'timedOut':
      return tr('답이 너무 오래 걸려 기다리기를 멈췄어요. 잠시 뒤 다시 물어봐 주세요')
    case 'offline':
      return tr('인터넷 연결을 확인해 주세요.')
    case 'unreachable':
      return tr('서버에 연결하지 못했어요. 잠시 뒤 다시 시도해 주세요')
    case 'rateLimited':
      return tr('요청이 많아요. 잠시 뒤 다시 시도해 주세요')
    case 'interrupted':
      return tr('답을 기다리다 멈췄어요. 다시 시도해 주세요')
    case 'limitReached':
      // 대화창은 풀리는 때까지 남은 시간을 다시 세어 보인다 — 이것은 받은 순간의 말이다.
      return blockMessage(makeBlock(f.retryAfter, 0), 0)
    case 'keyReused':
      return tr('가이드 요청을 처리하지 못했어요 (%d)', 422)
    case 'other':
      return tr('가이드 요청을 처리하지 못했어요 (%d)', f.status)
  }
}
