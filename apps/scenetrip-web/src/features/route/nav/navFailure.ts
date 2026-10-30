/*
 * 길찾기가 안 된 이유 — iOS RouteTab/RouteNavFailure.swift. 계약이 정한 응답별로 갈린다 (`POST /navigation/next-leg`, docs/api/errors.md).
 *
 * 앞서는 `catch` 하나가 모든 오류를 「준비 중」으로 뭉뚱그렸다 — 서버가 서자 거짓말이 됐다. 401 도 503 도 「준비 중」이면 사용자는 가입을 해야 하는지
 * 잠시 뒤 다시 눌러야 하는지 알 수 없다. 판별은 두 겹이다: HTTP 코드로 갈래를 나누고, 같은 코드 안에서 뜻이 갈리는 것(422 의 경로 없음/정류장 없음)은
 * 본문의 `ApiError.code` 로 본다. 연결 자체가 실패하면 서버 탓이 아니라 네트워크 탓이라 따로 말한다.
 */
import { tr } from '@/i18n'
import { NAV_LIMIT_CODE, navLimitMessage, navWindow } from './navLimit'

export type NavFailure =
  /** 가입해야 부를 수 있다 (401). */
  | { kind: 'signInRequired' }
  /** 로그인은 했는데 세션이 깨졌다 (401, `SIGN_IN_REQUIRED` 가 아닌 코드). */
  | { kind: 'sessionExpired' }
  /** 코스나 항목이 서버에 없다 (404). 저장 전 코스, 지운 항목. */
  | { kind: 'notFound' }
  /** 코스를 시작하지 않았다 (409). 「시작」을 누르면 통한다. */
  | { kind: 'courseNotActive' }
  /** 경로가 없다 (422). 다시 불러도 같다 — 섬·산속, 정류장 없는 자리. */
  | { kind: 'noRoute'; code: string }
  /** 제공자(카카오)가 응답하지 않거나 한도를 넘었다 (503). 잠시 뒤 다시. */
  | { kind: 'providerDown' }
  /** 서버에 닿지 못했다. */
  | { kind: 'unreachable' }
  /** 길찾기 한도(429 `NAVIGATION_LIMIT_REACHED`). `retryAfter` 는 풀릴 때까지의 초, 없으면 모른다. 자동으로 다시 부르지 않는다. */
  | { kind: 'limitReached'; retryAfter: number | null }
  /** 분당 요청 한도(429 `RATE_LIMITED`, 또는 `code` 없는 게이트웨이의 429). 길찾기는 유료라 공통 계층이 다시 보내지 않는다 — 사람이 「다시 시도」 를 누른다. */
  | { kind: 'rateLimited' }
  | { kind: 'other'; status: number }
  // 앱 안에서 정하는 것 — 서버를 부르기 전에 걸러진다.
  /** 챗봇 가게로 갈아탄 목적지. 계약이 활성 코스의 항목만 받으므로 코스 밖 좌표는 물을 수 없다. */
  | { kind: 'detourUnsupported' }
  | { kind: 'locationConsentRequired' }
  /** 저장 전 코스라 서버 항목 id 가 없다. */
  | { kind: 'unsavedCourse' }

export interface NavHttpFailure {
  status: number
  code: string | null
  retryAfter: number | null
}

/** 401 은 만료·폐기(세션)와 가입 요청으로 갈린다 (AuthRules.action). */
function unauthorized(code: string | null): NavFailure {
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

/** 응답을 계약 응답으로 분류한다. `status` 가 null 이면 연결 실패(음수 코드)다. */
export function classifyNavFailure(f: { status: number | null; code?: string | null; retryAfter?: number | null }): NavFailure {
  const { status, code = null, retryAfter = null } = f
  if (status === null || status <= 0) return { kind: 'unreachable' }
  switch (status) {
    case 401:
      return unauthorized(code)
    case 404:
      return { kind: 'notFound' }
    case 409:
      return { kind: 'courseNotActive' }
    case 422:
      return { kind: 'noRoute', code: code ?? 'ROUTE_NOT_FOUND' }
    case 429:
      // 429 는 `code` 로 갈린다 — 길찾기 한도, 분당 한도(`code` 없는 게이트웨이의 것 포함), 그 밖.
      if (code === NAV_LIMIT_CODE) return { kind: 'limitReached', retryAfter }
      if (code === null || code === 'RATE_LIMITED') return { kind: 'rateLimited' }
      return { kind: 'other', status: 429 }
    case 503:
      return { kind: 'providerDown' }
    default:
      return { kind: 'other', status }
  }
}

/** 화면에 보이는 한 줄. 안드로이드와 같은 문구여야 한다. */
export function navFailureMessage(f: NavFailure): string {
  switch (f.kind) {
    case 'signInRequired':
      return tr('길찾기는 가입한 분만 쓸 수 있어요')
    case 'sessionExpired':
      return tr('로그인이 풀렸어요. 다시 로그인해 주세요')
    case 'notFound':
    case 'unsavedCourse':
      return tr('저장된 코스의 장소에서만 길찾기를 부를 수 있어요')
    case 'courseNotActive':
      return tr('코스를 시작한 뒤에 길찾기를 쓸 수 있어요')
    case 'noRoute':
      return f.code === 'NO_TRANSIT_NEARBY' ? tr('근처에 정류장이 없어 대중교통 경로를 찾지 못했어요') : tr('여기서는 경로를 찾지 못했어요')
    case 'providerDown':
      return tr('길찾기 서비스가 잠시 응답하지 않아요. 잠시 뒤 다시 시도해 주세요')
    case 'unreachable':
      return tr('연결하지 못했어요. 인터넷 연결을 확인하고 잠시 뒤 다시 시도해 주세요')
    case 'limitReached':
      return navLimitMessage(navWindow(f.retryAfter))
    case 'rateLimited':
      return tr('요청이 많아요. 잠시 뒤 다시 시도해 주세요')
    case 'other':
      return tr('길찾기를 처리하지 못했어요 (%d)', f.status)
    case 'detourUnsupported':
      return tr('코스 밖 장소로의 길찾기는 아직 지원하지 않아요')
    case 'locationConsentRequired':
      return tr('위치기반서비스 이용에 동의한 뒤 다시 시도해 주세요.')
  }
}

/**
 * 「다시 시도」 단추를 보일 것인가. 다시 불러도 같은 답이 오는 것(경로 없음·가입 필요·코스 밖)에는 단추를 두지 않는다 — 눌러도 달라지지 않는 단추는
 * 사용자를 두 번 실망시킨다. 한도(`limitReached`)는 여기서 거짓이다 — 풀린 뒤에만 다시 부를 수 있고, 그것은 한도 안내가 따로 정한다.
 */
export function navCanRetry(f: NavFailure): boolean {
  switch (f.kind) {
    case 'providerDown':
    case 'unreachable':
    case 'rateLimited':
    case 'other':
    case 'locationConsentRequired':
      return true
    default:
      return false
  }
}

/** 길찾기 한도인가 — 안내 띠는 이것을 실패 한 줄이 아니라 한도 안내(지도 앱으로 넘기기)로 그린다. */
export const navIsLimit = (f: NavFailure): boolean => f.kind === 'limitReached'
