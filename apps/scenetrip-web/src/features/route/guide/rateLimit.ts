/*
 * 서버가 응답 헤더로 알려 준 **남은 양**을 적어 두는 장부 — iOS Models/RateLimitLedger.swift (MZ2AZ-366, 계약 「요청 한도」).
 *
 * 챗봇·길찾기는 자기 한도의 `RateLimit-*` 헤더를 싣는다. 챗봇 창이 여기서 읽어 「N번 더 물어볼 수 있어요」 를 보인다.
 * 숫자를 앱에 박지 않는다 — 한도는 요금제 설정이라 바뀐다.
 */
import { createStore } from '@/state/storage'

export type LedgerBucket = 'guide' | 'navigation' | 'general'

export interface LedgerEntry {
  /** 그 요청에 걸린 한도 중 가장 빠듯한 것의 크기. */
  limit: number
  /** 그 한도의 남은 횟수. */
  remaining: number
  /** 다시 차는 때(벽시계, ms). */
  resetsAtMs: number
}

interface LedgerState {
  entries: Partial<Record<LedgerBucket, LedgerEntry>>
  /** 이번 실행에서 `RateLimit-*` 를 한 번이라도 봤나 — 이 서버가 요청 한도를 안다는 표시(챗봇 멱등 키를 켜도 되는 근거, 계획 §4-5). */
  seen: boolean
}

export const ledgerStore = createStore<LedgerState>({ entries: {}, seen: false })

export function bucketFor(path: string): LedgerBucket {
  if (path.endsWith('/guide/chat')) return 'guide'
  if (path.endsWith('/navigation/next-leg')) return 'navigation'
  return 'general'
}

/** `Retry-After` — 초. 숫자가 아니면(HTTP 날짜 꼴 등) 모르는 것으로 본다. */
export function intHeader(headers: Pick<Headers, 'get'>, name: string): number | null {
  const text = headers.get(name)?.trim()
  if (!text || !/^\d+$/.test(text)) return null
  return Number(text)
}

export const retryAfterOf = (headers: Pick<Headers, 'get'>): number | null => intHeader(headers, 'Retry-After')

/** 헤더 셋이 다 있고 숫자일 때만. 이름의 대소문자는 가리지 않는다(`Headers.get` 이 그렇다). */
export function entryFromHeaders(headers: Pick<Headers, 'get'>, nowMs: number): LedgerEntry | null {
  const limit = intHeader(headers, 'RateLimit-Limit')
  const remaining = intHeader(headers, 'RateLimit-Remaining')
  const reset = intHeader(headers, 'RateLimit-Reset')
  if (limit === null || remaining === null || reset === null) return null
  return { limit, remaining, resetsAtMs: nowMs + reset * 1000 }
}

/** 응답 하나를 읽는다. 헤더가 없으면(옛 서버, 멱등 키로 되돌려 준 답) 적어 둔 것을 지우지 않는다. */
export function recordResponse(path: string, headers: Pick<Headers, 'get'>, nowMs = Date.now()): void {
  const entry = entryFromHeaders(headers, nowMs)
  if (!entry) return
  ledgerStore.set((s) => ({ entries: { ...s.entries, [bucketFor(path)]: entry }, seen: true }))
}

export const ledgerEntry = (bucket: LedgerBucket): LedgerEntry | null => ledgerStore.get().entries[bucket] ?? null
export const serverKnowsLimits = (): boolean => ledgerStore.get().seen

/** 그 묶음의 값을 잊는다 — 계정이 바뀌면 앞 계정의 남은 양은 남의 것이다. 「서버가 한도를 안다」 는 그대로다. */
export function forgetBucket(bucket: LedgerBucket): void {
  ledgerStore.set((s) => ({ ...s, entries: { ...s.entries, [bucket]: undefined } }))
}
