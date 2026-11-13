/*
 * 길찾기 구간의 색·배지·종류 — MZ2AZ-566, docs/project/plans/web-directions-steps.md §1.
 *
 * 지도 선과 안내 띠의 단계 목록이 같은 색을 쓴다(11/13 사용자 지적 — 지도는 전부 보라색인데 글자 바탕은 다른 색이었다).
 * 버스 종류는 제공자가 한국어로만 준다(`vehicleType`) — 외국인이 읽게 앱이 풀어 쓴다(11/11 멘토 지적).
 */
import { tr } from '@/i18n'
import type { LegMode } from './navModels'

export const WALK_COLOR = '#8A94A6'
export const SUBWAY_FALLBACK = '#3A6FD8'
const TRANSIT_COLOR = '#6B7280'

/** 안내문의 마지막 괄호(없으면 전체)에서 「탈 곳 > 내릴 곳」. */
export function boardAlight(guidance: string): { from: string; to: string } | null {
  const inner = lastGroup(guidance) ?? guidance
  const at = inner.indexOf('>')
  if (at < 0) return null
  const from = inner.slice(0, at).trim()
  const to = inner.slice(at + 1).trim()
  return from !== '' && to !== '' ? { from, to } : null
}

/** 맨 바깥 괄호 묶음 중 마지막의 안쪽 — 정류장 이름 안의 괄호(「서울역버스환승센터(소월로)」)는 그 묶음의 일부다. 없으면 null. */
function lastGroup(text: string): string | null {
  let depth = 0
  let start = -1
  let last: string | null = null
  for (let i = 0; i < text.length; i += 1) {
    const c = text[i]
    if (c === '(') {
      if (depth === 0) start = i + 1
      depth += 1
    } else if (c === ')' && depth > 0) {
      depth -= 1
      if (depth === 0) last = text.slice(start, i)
    }
  }
  return last
}

/** 배지 글자색 — 밝은 노선 색(수인분당 노랑 등)에는 흰 글자가 안 읽힌다. */
export function badgeInk(color: string): string {
  const m = /^#([0-9a-f]{6})$/i.exec(color)
  if (!m?.[1]) return '#fff'
  const n = Number.parseInt(m[1], 16)
  const lum = (0.299 * ((n >> 16) & 255) + 0.587 * ((n >> 8) & 255) + 0.114 * (n & 255)) / 255
  return lum > 0.66 ? '#1c1c1e' : '#fff'
}

/** 이름 있는 노선 — 짧은 이름과 알아보는 글자. 순서가 판정 순서다(`신분당` 이 `분당` 보다 먼저). */
const NAMED_LINES: readonly { short: string; words: readonly string[] }[] = [
  { short: 'Gyeongui', words: ['경의', 'Gyeongui'] },
  { short: 'AREX', words: ['공항', 'AREX', 'Airport'] },
  { short: 'Shinbundang', words: ['신분당', 'Shinbundang'] },
  { short: 'Suin-Bundang', words: ['수인', '분당', 'Bundang'] },
  { short: 'Donghae', words: ['동해', 'Donghae'] },
  { short: 'BGL', words: ['김해', 'Gimhae'] },
]

export function subwayLine(name: string | null | undefined): string | null {
  const s = (name ?? '').trim()
  if (s === '') return null
  const numbered = /(\d+)\s*호선/.exec(s) ?? /\bline\s*(\d+)\b/i.exec(s)
  if (numbered?.[1]) return numbered[1]
  const named = NAMED_LINES.find((l) => l.words.some((w) => s.includes(w)))
  return named ? named.short : s
}

const SEOUL: Readonly<Record<string, string>> = {
  '1': '#0052A4', '2': '#00A84D', '3': '#EF7C1C', '4': '#00A5DE', '5': '#996CAC', '6': '#CD7C2F', '7': '#747F00', '8': '#E6186C', '9': '#BDB092',
  Gyeongui: '#77C4A3', AREX: '#0090D2', Shinbundang: '#D4003B', 'Suin-Bundang': '#FABE00',
}
const BUSAN: Readonly<Record<string, string>> = {
  '1': '#F06A00', '2': '#81BF48', '3': '#BB8C00', '4': '#217DCB', Donghae: '#0054A6', BGL: '#8652A1',
}

const inBusan = (at: { latitude: number; longitude: number } | null | undefined): boolean =>
  !!at && at.latitude >= 34.8 && at.latitude <= 35.6 && at.longitude >= 128.7 && at.longitude <= 129.4

/** 버스 종류 — 위에서부터 먼저 맞는 줄. 색은 서울시 버스 체계를 따른다. */
const BUS_KINDS: readonly { words: readonly string[]; color: string; kind: string }[] = [
  { words: ['간선'], color: '#3D5BAB', kind: '간선버스' },
  { words: ['지선'], color: '#5BB025', kind: '지선버스' },
  { words: ['마을'], color: '#5BB025', kind: '마을버스' },
  { words: ['광역'], color: '#E6312E', kind: '광역버스' },
  { words: ['직행', '급행'], color: '#E6312E', kind: '급행버스' },
  { words: ['순환'], color: '#F2B70A', kind: '순환버스' },
  { words: ['공항'], color: '#00A0E9', kind: '공항버스' },
]

export interface LegStyle {
  color: string
  badge: string | null
  kind: string | null
}

export function legStyle(p: {
  mode: LegMode
  vehicleType?: string | null
  vehicleName?: string | null
  at?: { latitude: number; longitude: number } | null
}): LegStyle {
  const name = (p.vehicleName ?? '').trim()
  switch (p.mode) {
    case 'walk':
      return { color: WALK_COLOR, badge: null, kind: null }
    case 'subway': {
      const badge = subwayLine(p.vehicleName)
      const table = inBusan(p.at) ? BUSAN : SEOUL
      return { color: (badge !== null ? table[badge] : undefined) ?? SUBWAY_FALLBACK, badge, kind: tr('지하철') }
    }
    case 'bus': {
      const type = p.vehicleType ?? ''
      const row = BUS_KINDS.find((k) => k.words.some((w) => type.includes(w)))
      return { color: row?.color ?? '#3D7DCA', badge: name === '' ? null : name, kind: tr(row?.kind ?? '버스') }
    }
    case 'transit':
      return { color: TRANSIT_COLOR, badge: name === '' ? null : name, kind: null }
  }
}
