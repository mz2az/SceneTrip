/*
 * 첫 화면 애니메이션의 박자 — 순수 함수. 정본: docs/project/plans/web-ad-landing.md §12-1(느리고 크게, intro-lab).
 *
 *   ① drop    0~1.5초    해태가 위에서 떨어져 통통 튀고 방긋, 그 아래 SceneTrip
 *   ② poster  1.5~3.2초  포스터가 올라와 서고 해태가 빼꼼 + 「이 장소들, 진짜 {도시}에 있어요!」, 포스터 아래 「{작품}의 장면들」
 *   ③ spill   3.2초~     (펼침) 촬영지 카드가 포스터 둘레로 쫙 펼쳐진다(1.3초 — 4.5초에 하나씩이 시작)
 *                        (하나씩) 한 장씩 가운데로 크게 → 그림이 실제 사진으로 뒤집힘 → 이름 → 「My map」 주머니로 쏙
 *   ④ rest    핀을 든 해태 + 「{N}곳, 전부 {도시}에 있어요!」 + 담기 단추
 *
 * 모든 시간은 아래 INTRO_PACE 한 곳에 있다 — 늦추거나 당길 때는 이 값만 고친다.
 * 분석 박자(`beat`)는 그대로 drop·poster·spill·rest — 펼침과 하나씩은 둘 다 spill 이다.
 */

export type Beat = 'drop' | 'poster' | 'spill' | 'rest'

/** 첫 화면의 모든 시간(ms) — 이름은 INTRO_PACE 의 칸. */
export type IntroPace = Readonly<Record<'dropMs' | 'posterMs' | 'spreadMs' | 'spreadFlyMs' | 'oneMs' | 'zoomMs' | 'flipMs' | 'flyMs' | 'settleMs', number>>

/** 첫 화면의 모든 시간(ms). */
export const INTRO_PACE: IntroPace = {
  /** ① 등장. */
  dropMs: 1500,
  /** ② 포스터와 빼꼼. */
  posterMs: 1700,
  /** ③ 의 펼침 — 카드가 포스터 둘레로 펼쳐지는 시간(마지막 카드가 자리를 잡을 때까지 포함). */
  spreadMs: 1300,
  /** 펼침에서 카드 하나가 포스터에서 제자리까지 나는 시간. */
  spreadFlyMs: 620,
  /** 하나씩 — 한 장의 차례(다음 장이 커지기 시작할 때까지). */
  oneMs: 900,
  /** 한 장이 제자리에서 가운데로 크게 커지는 시간. */
  zoomMs: 180,
  /** 커진 뒤 그림이 사진으로 뒤집히는 시간. */
  flipMs: 250,
  /** 한 장이 가운데에서 주머니까지 나는 시간 — 다음 장이 커지기 시작할 때 떠난다. */
  flyMs: 340,
  /** 마지막 장이 주머니에 들어간 뒤 ④ 까지. */
  settleMs: 220,
}

/** 하나씩 보이는 카드의 최대 수 — 나머지는 마지막 장이 들어갈 때 수만 함께 오른다. */
export const FEATURED_MAX = 10

export interface LandingTimeline {
  dropMs: number
  posterMs: number
  spreadMs: number
  oneMs: number
  /** 하나씩 보이는 카드 수 = min(곳 수, FEATURED_MAX). */
  featured: number
  /** ③ 의 길이 — 펼침 + 하나씩 + 마지막 장이 날아 들어가 주머니가 가라앉기까지. 0곳이면 0. */
  spillMs: number
  total: number
}

export function landingTimeline(placeCount: number, pace: IntroPace = INTRO_PACE): LandingTimeline {
  const n = Math.max(0, Math.floor(placeCount))
  const featured = Math.min(n, FEATURED_MAX)
  const spillMs = featured === 0 ? 0 : pace.spreadMs + featured * pace.oneMs + pace.flyMs + pace.settleMs
  return { dropMs: pace.dropMs, posterMs: pace.posterMs, spreadMs: pace.spreadMs, oneMs: pace.oneMs, featured, spillMs, total: pace.dropMs + pace.posterMs + spillMs }
}

export function beatAt(elapsedMs: number, t: LandingTimeline): Beat {
  if (elapsedMs < t.dropMs) return 'drop'
  if (elapsedMs < t.dropMs + t.posterMs) return 'poster'
  if (elapsedMs < t.total) return 'spill'
  return 'rest'
}

/** ③ 이 시작하는 때(ms) — 펼침의 시작. */
export const spillStart = (t: LandingTimeline): number => t.dropMs + t.posterMs

/** 펼침에서 i 번째(0부터) 카드가 포스터를 떠나는 때(ms). 마지막 카드도 펼침 안에 자리를 잡는다. */
export function spreadAt(i: number, t: LandingTimeline, pace: IntroPace = INTRO_PACE): number {
  const gap = t.featured <= 1 ? 0 : Math.floor((t.spreadMs - pace.spreadFlyMs - 120) / (t.featured - 1))
  return spillStart(t) + 60 + i * Math.min(90, gap)
}

/** i 번째 카드가 가운데로 커지기 시작하는 때(ms). */
export const zoomAt = (i: number, t: LandingTimeline): number => spillStart(t) + t.spreadMs + i * t.oneMs

/** i 번째 카드가 가운데를 떠나 주머니로 나는 때(ms) = 다음 카드가 커지기 시작하는 때. */
export const leaveAt = (i: number, t: LandingTimeline): number => zoomAt(i + 1, t)

/** i 번째 카드가 주머니에 들어가는 때(ms). */
export const pocketAt = (i: number, t: LandingTimeline, pace: IntroPace = INTRO_PACE): number => leaveAt(i, t) + pace.flyMs

/** 지금 가운데에 있는 카드(0부터) — 하나씩 박자가 아니면 null. */
export function centerCard(elapsedMs: number, t: LandingTimeline): number | null {
  if (t.featured === 0) return null
  const since = elapsedMs - zoomAt(0, t)
  if (since < 0 || elapsedMs >= t.total) return null
  const i = Math.floor(since / t.oneMs)
  return i < t.featured ? i : null
}

/**
 * 주머니에 들어간 수 — i 번째 카드는 pocketAt(i) 에 들어간다. 마지막 카드가 들어갈 때 하나씩 보이지 않은 나머지도 함께 들어간다.
 * 최대 placeCount, rest 는 언제나 placeCount.
 */
export function pocketedCount(elapsedMs: number, t: LandingTimeline, placeCount: number): number {
  if (placeCount <= 0) return 0
  if (elapsedMs >= t.total) return placeCount
  let c = 0
  while (c < t.featured && pocketAt(c, t) <= elapsedMs) c++
  return c === t.featured ? placeCount : c
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
