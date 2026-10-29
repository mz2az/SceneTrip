/*
 * 일정짜기(`POST /guide/plan`)가 답하지 못한 이유를 사용자 문구로 — iOS RouteGuideFailure · GuideLimit (MZ2AZ-366).
 * 「준비 중」은 서버가 없던 때의 말이다 — 401 도 503 도 「준비 중」이면 가입을 해야 하는지 잠시 뒤 다시 눌러야 하는지 알 수 없다.
 * 안드로이드와 같은 문구여야 한다. 계약이 정한 응답별로 가른다.
 */
import { ApiFailure } from '@/api/failure'
import { tr } from '@/i18n'

export const GUIDE_LIMIT_CODE = 'GUIDE_LIMIT_REACHED'
/** `Retry-After` 가 이 초 이하면 「N분 뒤」, 넘으면 「내일」. */
const SHORT_WINDOW_SECONDS = 3600

export type PlanFailureKind =
  | 'consentRequired' | 'locationConsentRequired' | 'signInRequired' | 'sessionExpired' | 'badRequest' | 'unavailable'
  | 'timedOut' | 'offline' | 'unreachable' | 'rateLimited' | 'interrupted' | 'limitReached' | 'other'

export interface PlanFailure {
  kind: PlanFailureKind
  status?: number
  retryAfter?: number | null
}

export function classifyPlanFailure(error: unknown): PlanFailure {
  if (!(error instanceof ApiFailure)) return { kind: 'unreachable' }
  const { statusCode: status, apiCode: code } = error
  if (status === null) {
    if (error.name === 'AbortError') return { kind: 'timedOut' }
    return { kind: error.isOffline ? 'offline' : 'unreachable' }
  }
  switch (status) {
    case 400: return { kind: 'badRequest' }
    case 401: return { kind: code === 'SIGN_IN_REQUIRED' ? 'signInRequired' : 'sessionExpired' }
    case 409: return code === 'REQUEST_IN_PROGRESS' ? { kind: 'timedOut' } : { kind: 'other', status }
    case 429:
      if (code === GUIDE_LIMIT_CODE) return { kind: 'limitReached', retryAfter: error.retryAfterSeconds }
      return code === null || code === 'RATE_LIMITED' ? { kind: 'rateLimited' } : { kind: 'other', status }
    case 502: case 503: case 504: return { kind: 'unavailable' }
    default: return { kind: 'other', status }
  }
}

/** 한도 안내 한 줄 — 1 시간 안에 풀리면 몇 분 뒤인지, 그보다 멀면 내일, 모르면 날짜를 지어내지 않는다. */
export function limitMessage(retryAfter: number | null | undefined): string {
  if (retryAfter == null) return tr('지금은 가이드에게 물어볼 수 없어요. 잠시 뒤 다시 시도해 주세요')
  if (retryAfter <= SHORT_WINDOW_SECONDS) {
    return tr('가이드에게 잠시 많이 물어봤어요. %d분 뒤에 다시 물어볼 수 있어요', Math.max(1, Math.ceil(retryAfter / 60)))
  }
  return tr('오늘은 가이드에게 물어볼 수 있는 횟수를 다 썼어요. 내일 다시 물어봐 주세요')
}

export function planFailureMessage(f: PlanFailure): string {
  switch (f.kind) {
    case 'locationConsentRequired': return tr('위치기반서비스 이용에 동의한 뒤 다시 시도해 주세요.')
    case 'consentRequired': return tr('AI 기능 사용을 허용한 뒤 다시 시도해 주세요.')
    case 'signInRequired': return tr('여행 가이드는 가입한 분만 쓸 수 있어요')
    case 'sessionExpired': return tr('로그인이 풀렸어요. 다시 로그인해 주세요')
    case 'badRequest': return tr('요청을 처리하지 못했어요. 조건을 바꿔 다시 해 주세요')
    case 'unavailable': return tr('가이드가 잠시 응답하지 않아요. 잠시 뒤 다시 물어봐 주세요')
    case 'timedOut': return tr('답이 너무 오래 걸려 기다리기를 멈췄어요. 잠시 뒤 다시 물어봐 주세요')
    case 'offline': return tr('인터넷 연결을 확인해 주세요.')
    case 'unreachable': return tr('서버에 연결하지 못했어요. 잠시 뒤 다시 시도해 주세요')
    case 'rateLimited': return tr('요청이 많아요. 잠시 뒤 다시 시도해 주세요')
    case 'interrupted': return tr('답을 기다리다 멈췄어요. 다시 시도해 주세요')
    case 'limitReached': return limitMessage(f.retryAfter)
    case 'other': return tr('가이드 요청을 처리하지 못했어요 (%d)', f.status ?? 0)
  }
}
