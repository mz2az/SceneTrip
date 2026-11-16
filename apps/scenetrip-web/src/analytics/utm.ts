/*
 * 광고 꼬리표 지키기 — 정본: docs/project/plans/web-ad-landing.md §10-3-8.
 *
 * 앱이 뜰 때 주소의 `utm_*` 를 세션 저장소에 적는다. 첫 `screen_view` 가 나가기 전에 주소가 바뀌어도(`/go/{id}` → `/?show=`,
 * 덮개를 걷은 뒤 `/add` 등) 잃지 않는다. 같은 탭에서 꼬리표 없이 다시 들어오면 적어 둔 것을 쓴다. 새 꼬리표가 오면 바꾼다.
 * PostHog 는 super property 로, GA4 는 이후 모든 이벤트의 매개변수로 싣는다(index.ts).
 */

export const UTM_SESSION_KEY = 'scenetrip.utm'
/** 값 하나의 최대 길이 — 주소에 실린 긴 문자열이 이벤트를 부풀리지 않게. */
const MAX_VALUE = 100
const MAX_KEYS = 8

export type Utm = Record<string, string>

/** `?utm_source=meta&utm_content=a&x=1` → { utm_source: 'meta', utm_content: 'a' }. 빈 값은 뺀다. */
export function utmFrom(search: string): Utm {
  const out: Utm = {}
  for (const [k, v] of new URLSearchParams(search)) {
    const key = k.toLowerCase()
    if (!/^utm_[a-z0-9_]{1,30}$/.test(key) || key in out) continue
    const value = v.trim().slice(0, MAX_VALUE)
    if (value === '') continue
    out[key] = value
    if (Object.keys(out).length >= MAX_KEYS) break
  }
  return out
}

type SessionLike = Pick<Storage, 'getItem' | 'setItem'>

function session(): SessionLike | null {
  try {
    return typeof sessionStorage === 'undefined' ? null : sessionStorage
  } catch {
    return null
  }
}

/** 적어 둔 꼬리표. 없거나 읽지 못하면 빈 것. */
export function readUtm(store: SessionLike | null = session()): Utm {
  try {
    const raw = store?.getItem(UTM_SESSION_KEY)
    if (!raw) return {}
    const parsed: unknown = JSON.parse(raw)
    if (typeof parsed !== 'object' || parsed === null) return {}
    return utmFrom(new URLSearchParams(Object.entries(parsed as Record<string, unknown>).filter(([, v]) => typeof v === 'string') as [string, string][]).toString())
  } catch {
    return {}
  }
}

/** 주소에 꼬리표가 있으면 적고 그것을, 없으면 적어 둔 것을 돌려준다. */
export function storeUtm(search: string, store: SessionLike | null = session()): Utm {
  const fresh = utmFrom(search)
  if (Object.keys(fresh).length === 0) return readUtm(store)
  try {
    store?.setItem(UTM_SESSION_KEY, JSON.stringify(fresh))
  } catch {
    /* 저장하지 못해도 이번 실행 동안은 들고 있다 */
  }
  return fresh
}
