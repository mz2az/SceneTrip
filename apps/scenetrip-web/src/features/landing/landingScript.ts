/*
 * 첫 화면 애니메이션의 네 박자 — 순수 함수. 정본: docs/project/plans/web-ad-landing.md §12.
 *
 *   ① drop    0~1.2초   해태가 위에서 떨어져 통통 튀고 방긋, 그 아래 SceneTrip
 *   ② poster  1.2~2.6초 포스터가 아래에서 올라와 서고, 해태가 윗변에 앞발을 걸고 빼꼼 + 「저기 어디야?」
 *   ③ spill   2.6초~(최대 2.2초) 포스터에서 촬영지 썸네일이 하나씩 튀어나와 「내 지도」 주머니로 쏙
 *   ④ rest    핀을 든 해태 + 「{N}곳, 전부 {도시}에 있어요!」 + 담기 단추
 */

export type Beat = 'drop' | 'poster' | 'spill' | 'rest'

export interface LandingTimeline {
  dropMs: number
  posterMs: number
  /** ③ 의 길이 — 첫 썸네일이 튀어나와 마지막 썸네일이 주머니에 들어가고 주머니가 가라앉기까지. 0곳이면 0. */
  spillMs: number
  /** 썸네일이 하나씩 튀어나오는 간격(ms). 1곳 이하면 0. */
  perItemMs: number
  total: number
}

/** ① 의 길이(ms). */
export const DROP_MS = 1200
/** ② 의 길이(ms). */
export const POSTER_MS = 1400
/** ③ 의 최대 길이(ms). */
export const SPILL_MAX_MS = 2200
/** 썸네일 하나가 포스터에서 주머니까지 나는 시간(ms). */
export const FLIGHT_MS = 640
/** 마지막 썸네일이 들어간 뒤 주머니가 가라앉는 시간(ms). */
export const SETTLE_MS = 160
/** 썸네일 간격의 최대(ms) — 곳이 적어도 이보다 느리게 쏟아지지 않는다. */
const PER_ITEM_MAX = 300

export function landingTimeline(placeCount: number): LandingTimeline {
  const n = Math.max(0, Math.floor(placeCount))
  const perItemMs = n <= 1 ? 0 : Math.min(PER_ITEM_MAX, Math.floor((SPILL_MAX_MS - FLIGHT_MS - SETTLE_MS) / (n - 1)))
  const spillMs = n === 0 ? 0 : FLIGHT_MS + perItemMs * (n - 1) + SETTLE_MS
  return { dropMs: DROP_MS, posterMs: POSTER_MS, spillMs, perItemMs, total: DROP_MS + POSTER_MS + spillMs }
}

export function beatAt(elapsedMs: number, t: LandingTimeline): Beat {
  if (elapsedMs < t.dropMs) return 'drop'
  if (elapsedMs < t.dropMs + t.posterMs) return 'poster'
  if (elapsedMs < t.total) return 'spill'
  return 'rest'
}

/** ③ 이 시작하는 때(ms). */
export const spillStart = (t: LandingTimeline): number => t.dropMs + t.posterMs

/** i 번째(0부터) 썸네일이 포스터에서 튀어나오는 때(ms). */
export const launchAt = (i: number, t: LandingTimeline): number => spillStart(t) + i * t.perItemMs

/**
 * 주머니에 들어간 수 — ③ 전에는 0, i 번째는 `launchAt(i) + FLIGHT_MS` 에 들어간다. 최대 placeCount, rest 는 언제나 placeCount.
 */
export function pocketedCount(elapsedMs: number, t: LandingTimeline, placeCount: number): number {
  if (placeCount <= 0) return 0
  if (elapsedMs >= t.total) return placeCount
  const since = elapsedMs - spillStart(t) - FLIGHT_MS
  if (since < 0) return 0
  if (t.perItemMs === 0) return placeCount
  return Math.min(placeCount, Math.floor(since / t.perItemMs) + 1)
}

/** 시·도 — 주소에서 찾는 말(한국어 앞머리·영어 이름)과 화면에 쓰는 이름. */
const REGIONS: { ko: string; en: string; match: RegExp }[] = [
  { ko: '서울', en: 'Seoul', match: /^서울|\bSeoul\b/i },
  { ko: '부산', en: 'Busan', match: /^부산|\bBusan\b/i },
  { ko: '대구', en: 'Daegu', match: /^대구|\bDaegu\b/i },
  { ko: '인천', en: 'Incheon', match: /^인천|\bIncheon\b/i },
  { ko: '광주', en: 'Gwangju', match: /^광주광역시|^광주 |\bGwangju\b/i },
  { ko: '대전', en: 'Daejeon', match: /^대전|\bDaejeon\b/i },
  { ko: '울산', en: 'Ulsan', match: /^울산|\bUlsan\b/i },
  { ko: '세종', en: 'Sejong', match: /^세종|\bSejong\b/i },
  { ko: '경기', en: 'Gyeonggi', match: /^경기|\bGyeonggi/i },
  { ko: '강원', en: 'Gangwon', match: /^강원|\bGangwon/i },
  { ko: '충북', en: 'Chungbuk', match: /^충청북도|^충북|\bChungcheongbuk|\bChungbuk/i },
  { ko: '충남', en: 'Chungnam', match: /^충청남도|^충남|\bChungcheongnam|\bChungnam/i },
  { ko: '전북', en: 'Jeonbuk', match: /^전라북도|^전북|\bJeollabuk|\bJeonbuk/i },
  { ko: '전남', en: 'Jeonnam', match: /^전라남도|^전남|\bJeollanam|\bJeonnam/i },
  { ko: '경북', en: 'Gyeongbuk', match: /^경상북도|^경북|\bGyeongsangbuk|\bGyeongbuk/i },
  { ko: '경남', en: 'Gyeongnam', match: /^경상남도|^경남|\bGyeongsangnam|\bGyeongnam/i },
  { ko: '제주', en: 'Jeju', match: /^제주|\bJeju\b/i },
]

/** 한 주소의 시·도. 모르면 null. */
export function regionOf(address: string | null | undefined): { ko: string; en: string } | null {
  const a = address?.trim()
  if (!a) return null
  // 영어 주소는 시·도가 맨 끝에 온다(「…, Gwangju-si, Gyeonggi-do」) — 가장 뒤에서 찾은 것을 쓴다.
  let hit: (typeof REGIONS)[number] | null = null
  let at = -1
  for (const r of REGIONS) {
    for (const m of a.matchAll(new RegExp(r.match.source, 'gi'))) {
      if (m.index > at) {
        at = m.index
        hit = r
      }
    }
  }
  return hit ? { ko: hit.ko, en: hit.en } : null
}

/**
 * ④ 의 말풍선에 쓰는 곳 — 촬영지 주소에서 가장 많은 시·도(같으면 먼저 나온 것). 모르면 한국.
 * `all` 은 주소를 아는 촬영지가 모두 그 시·도이고 모르는 주소가 없는가(「전부」 라고 말해도 되는가).
 */
export function topRegion(addresses: readonly (string | null | undefined)[]): { ko: string; en: string; all: boolean } {
  const counts = new Map<string, { region: { ko: string; en: string }; n: number; first: number }>()
  let unknown = 0
  addresses.forEach((a, i) => {
    const r = regionOf(a)
    if (!r) {
      unknown += 1
      return
    }
    const c = counts.get(r.ko)
    if (c) c.n += 1
    else counts.set(r.ko, { region: r, n: 1, first: i })
  })
  const best = [...counts.values()].sort((a, b) => b.n - a.n || a.first - b.first)[0]
  if (!best) return { ko: '한국', en: 'Korea', all: true }
  return { ...best.region, all: counts.size === 1 && unknown === 0 }
}
