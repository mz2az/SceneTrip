/*
 * 화면이 오류를 다루는 데 필요한 만큼만 추린 것 — iOS `ApiFailure` (Models/SceneData.swift:306) 와 같다.
 * 계약이 `ApiError.message` 를 「사용자에게 그대로 보여 줄 문구가 아니다」 라고 못 박았으므로 문구는 여기서 만든다.
 */
import { tr } from '@/i18n'

export class ApiFailure extends Error {
  readonly statusCode: number | null
  readonly traceId: string | null
  /** 기기가 오프라인이라 실패했다 — 서버 탓이 아니라 「인터넷 연결」 을 말해야 한다. */
  readonly isOffline: boolean
  /** 오류 본문의 `code`. */
  readonly apiCode: string | null
  readonly retryAfterSeconds: number | null

  constructor(init: { statusCode?: number | null; traceId?: string | null; isOffline?: boolean; apiCode?: string | null; retryAfterSeconds?: number | null; message?: string }) {
    super(init.message ?? `ApiFailure(${init.statusCode ?? 'network'}${init.apiCode ? ` ${init.apiCode}` : ''})`)
    this.name = 'ApiFailure'
    this.statusCode = init.statusCode ?? null
    this.traceId = init.traceId ?? null
    this.isOffline = init.isOffline ?? false
    this.apiCode = init.apiCode ?? null
    this.retryAfterSeconds = init.retryAfterSeconds ?? null
  }

  /** 「다시 시도」 가 의미 있는 경우 — 서버에 닿지 못했거나, 서버 사정(5xx)이거나, 분당 한도(429)다. */
  get isRetryable(): boolean {
    if (this.statusCode === null) return true
    return [429, 500, 502, 503, 504].includes(this.statusCode)
  }

  /** 사용자에게 보일 한 줄. Android 의 `ApiFailure.message` 와 같은 문구여야 한다. */
  get userMessage(): string {
    switch (this.statusCode) {
      case null:
        return this.isOffline ? tr('인터넷 연결을 확인해 주세요.') : tr('서버에 연결하지 못했습니다.')
      case 500:
      case 502:
      case 503:
      case 504:
        return tr('잠시 문제가 생겼습니다.')
      case 429:
        return tr('요청이 많아요. 잠시 뒤 다시 시도해 주세요.')
      default:
        return tr('요청을 처리하지 못했습니다.')
    }
  }
}

/** 어떤 오류든 ApiFailure 로 접는다. */
export function asFailure(error: unknown): ApiFailure {
  if (error instanceof ApiFailure) return error
  const offline = typeof navigator !== 'undefined' && navigator.onLine === false
  return new ApiFailure({ statusCode: null, isOffline: offline })
}
