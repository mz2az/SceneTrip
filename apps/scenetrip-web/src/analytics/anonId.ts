/*
 * PostHog 익명 방문자 id — 첫 화면 동안의 직접 전송(beacon.ts)과 나중에 뜨는 PostHog 라이브러리가 **같은 id** 를 쓰게 한다.
 * 정본: docs/project/plans/web-ad-landing.md §10-3-2.
 *
 * - 라이브러리가 이미 이 브라우저에 id 를 적어 두었으면(`persistence: 'localStorage'` → `ph_{키}_posthog` 의 `distinct_id`) 그것을 쓴다.
 * - 없으면 우리가 만들어 `scenetrip.analytics.anonId` 에 적고, 라이브러리를 켤 때 `bootstrap: { distinctID }` 로 넘긴다
 *   (https://posthog.com/docs/libraries/bootstrapping — 익명 id 는 `isIdentifiedID` 를 빼고 넘긴다).
 *   라이브러리에 이미 id 가 있을 때는 넘기지 않는다 — 넘기면 라이브러리가 그 id 로 갈아 끼운다(posthog-core `_init`).
 * - 쿠키는 쓰지 않는다. 계정·메일과 잇지 않는다(identify 를 부르지 않는다).
 */
import { readStorage, writeStorage } from '@/state/storage'

export const ANON_ID_KEY = 'scenetrip.analytics.anonId'

type Read = (key: string) => string | null
type Write = (key: string, value: string) => void

/** posthog-js 가 쓰는 저장소 열쇠(posthog-persistence `parseName` 과 같은 규칙). */
export function postHogPersistenceKey(token: string): string {
  return `ph_${token.replace(/\+/g, 'PL').replace(/\//g, 'SL').replace(/=/g, 'EQ')}_posthog`
}

/** 라이브러리가 적어 둔 익명 id. 없거나 읽지 못하면 null. */
export function storedPostHogId(token: string, read: Read = readStorage): string | null {
  try {
    const raw = read(postHogPersistenceKey(token))
    if (!raw) return null
    const id = (JSON.parse(raw) as { distinct_id?: unknown }).distinct_id
    return typeof id === 'string' && id !== '' ? id : null
  } catch {
    return null
  }
}

function newId(): string {
  try {
    if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') return crypto.randomUUID()
  } catch {
    /* 아래로 */
  }
  return `${Date.now().toString(16)}-${Math.random().toString(16).slice(2)}-${Math.random().toString(16).slice(2)}`
}

/** 이 방문자의 익명 id — 라이브러리 것, 없으면 우리가 적어 둔 것, 그것도 없으면 새로 만들어 적는다. */
export function anonId(token: string, read: Read = readStorage, write: Write = writeStorage, make: () => string = newId): string {
  const fromLib = storedPostHogId(token, read)
  if (fromLib !== null) return fromLib
  const ours = read(ANON_ID_KEY)
  if (ours) return ours
  const fresh = make()
  write(ANON_ID_KEY, fresh)
  return fresh
}

/** 라이브러리를 켤 때 넘길 id — 라이브러리에 이미 id 가 있으면 undefined(그대로 둔다). */
export function bootstrapDistinctId(token: string, read: Read = readStorage, write: Write = writeStorage): string | undefined {
  return storedPostHogId(token, read) === null ? anonId(token, read, write) : undefined
}

/** 외부 브라우저로 넘어올 때 주소에 실려 온 익명 id(`aid`)의 모양 — 우리가 만든 무작위 값만 받는다. */
const ADOPTABLE = /^[A-Za-z0-9-]{8,64}$/

/**
 * 앱 안 브라우저에서 넘겨받은 익명 id 를 이 브라우저의 id 로 삼는다(analytics-events.md 「이벤트 보강」, MZ2AZ-586).
 * 이 브라우저에 PostHog id 도 우리 id 도 없을 때만 — 이미 있는 사람을 바꿔 끼우지 않는다. 받았으면 true.
 */
export function adoptAnonId(candidate: string | null, token: string, read: Read = readStorage, write: Write = writeStorage): boolean {
  if (candidate === null || !ADOPTABLE.test(candidate)) return false
  if (storedPostHogId(token, read) !== null) return false
  if (read(ANON_ID_KEY)) return false
  write(ANON_ID_KEY, candidate)
  return true
}
