/*
 * 편의시설 「켤 때만 · 다시 찾기를 누를 때만」 시안 — 코스 편집 지도 위의 갈래 칩(2026-11-08 사용자 요청, UI/UX 점검).
 *
 * 원래: 줌 13 이상이면 저절로 30 개가 깔리고, 지도를 옮길 때마다 바뀐다(route/poi/useAmbientPois).
 *  - 있는지 모른다(확대 전에는 아무것도 없다), 보던 점이 사라진다, 코스 번호 핀과 섞인다.
 * 시안: 처음엔 다 꺼 두고, 지도 위 칩을 켜면 그 갈래만 지금 범위에서 받는다. 지도를 옮겨도 점은 그대로이고
 *  「이 지역에서 다시 찾기」 가 뜬다(누를 때만 바뀐다). 너무 넓으면 「조금 더 확대해 주세요」.
 * 받은 것은 지도에 고정된 칸마다 1등만 큰 점으로, 나머지는 작은 점으로 찍는다(아래 「골고루 퍼뜨리기」). 「전체」 칩은 분류를 고르지 않고 다 찾는다.
 *
 * 검색(칩 줄 맨 앞 🔍): 누르면 칩 줄이 검색칸으로 바뀐다. 촬영지와 편의시설을 한 칸에서 찾고 결과만 둘로 나눈다 — 사람은 「경복궁」 이 촬영지인지
 * 편의시설인지 따지지 않는다. 먼저 지금 지도 범위에서 찾고, 없거나 더 보고 싶으면 「이 지역 밖에서도 찾기」. 0.35초 멈추면 묻고 2글자부터(요청 한도 1분 60회).
 * 편의시설 이름 검색은 서버에 검색어 칸(`GET /pois?q=`)이 없어 아직 자리만 둔다 — 계약을 바꾸는 일이 따로 필요하다.
 * 로컬 개발 서버 + 시안일 때만 쓰인다(docs/project/plans/web-trip-first-prototypes.md).
 */
import { api, ok } from '@/api/client'
import { Icon } from '@/design/Icon'
import { useEffect, useRef, useState } from 'react'
import { HStack, Spinner, Txt, VStack } from '@/design/primitives'
import type { MapHandle } from '@/map'
import type { PlaceSummary } from '@/features/route/models'
import { RemoteImage } from '@/features/route/ui/RemoteImage'
import type { PoiFetchResult, PoiViewport } from '@/features/route/poi/poiApi'
import { POI_GROUPS, type PoiGroup, poiGroupLabel, poiTone } from '@/features/route/poi/poiGroup'
import { type RoutePlace, fromPoi, makeSpot, placeGroup } from '@/features/route/poi/poiPlace'
import { viewportOf } from '@/features/route/poi/useAmbientPois'
import { Pressable } from '@/features/route/ui/Press'
import { DayPopup } from './protoCourseAdd'
import { say } from './protoState'
import { radius, shadow } from './protoTokens'

/** 엔터 뒤 결과 서랍이 처음 덮는 높이(지도의 비율). */
const PANEL_SHARE = 0.42


// ---------------------------------------------------------------- 골고루 퍼뜨리기 (구글 지도 방식 시안, 2026-11-08)
//
// 구글 지도처럼 「지도에 고정된 칸마다 순위 1등만 큰 점, 나머지는 작은 점」. 칸은 화면이 아니라 지도(웹 메르카토르 좌표)에 고정이라
// 지도를 옮겨도 같은 곳이 같은 칸에 든다. 확대하면 칸이 작아져 큰 점이 늘어나기만 한다 — 순위가 하나의 줄이라 넓을 때 칸 1등은 쪼개진 작은 칸에서도 1등이다.
// 순위: 갈래(역 → 명소 → 숙소 → 식당·카페) → id 를 섞은 값(같은 곳은 늘 같은 값 — 다시 찾아도 안 뒤바뀐다). 편의시설 인기도는 아직 거의 0 이라 뺐다.
//
// 시안 한계: 서버는 한 번에 200 곳까지라, 넓게 보면 범위 안 수십만 곳 중 가나다순(위치와 무관해 화면에 흩어진다) 200 곳에서 고른다.
// 그래서 칸의 진짜 1등이 빠질 수 있고 지도를 옮겨 다시 찾으면 바뀔 수 있다. 실제 앱은 서버가 칸마다 골라 줘야 한다(계약에 정렬 「골고루」 추가).

/** 한 번에 받는 수 — 계약 상한. */
const SPREAD_LIMIT = 200
/** 이만큼 확대하면 가나다순 표본 대신 가까운 순으로 200 곳 — 그 화면 안은 거의 다 받는다(명동도 화면 하나에 300 곳 안팎). */
export const SPREAD_DEEP_ZOOM = 18
/** 칸 한 변(px, 그 줌에서). 폰 화면이면 큰 점이 30 개 안팎. */
const CELL_PX = 72
const GROUP_RANK: Record<PoiGroup, number> = { transit: 0, sight: 1, stay: 2, food: 3 }

/** FNV-1a — id 를 섞어 늘 같은 「아무 순서」 를 만든다. */
function mix(id: string): number {
  let h = 2166136261
  for (let i = 0; i < id.length; i += 1) {
    h ^= id.charCodeAt(i)
    h = Math.imul(h, 16777619)
  }
  return h >>> 0
}

/** 갈래를 하나만 켰으면 서버가 거르고, 여럿이면 다 받아 여기서 거른다(요청은 한 번). */
export function fetchSpread(groups: readonly PoiGroup[], map: MapHandle | null) {
  const want = new Set(groups)
  return async (v: PoiViewport): Promise<PoiFetchResult> => {
    const bbox = `${v.west},${v.south},${v.east},${v.north}`
    const categoryGroup = groups.length === 1 ? groups[0] : undefined
    const deep = (map?.getZoom() ?? 0) >= SPREAD_DEEP_ZOOM
    const query = deep
      ? { bbox, lat: v.centerLat, lng: v.centerLng, sort: 'distance' as const, limit: SPREAD_LIMIT, categoryGroup }
      : { bbox, sort: 'alphabetical' as const, limit: SPREAD_LIMIT, categoryGroup }
    try {
      const list = await ok(api.GET('/pois', { params: { query } }))
      const places = list.items.map(fromPoi).filter((p) => want.has(placeGroup(p)))
      return { places, total: list.total }
    } catch {
      return { places: [], total: 0 }
    }
  }
}

/** 고정 칸마다 1등은 `major`(큰 점), 나머지는 `minor`(작은 점). 줌이 바뀌면 다시 부르기만 하면 된다 — 다시 받을 필요 없다. */
export function spreadPois(places: readonly RoutePlace[], zoom: number): { major: RoutePlace[]; minor: RoutePlace[] } {
  const world = (256 * 2 ** Math.floor(zoom)) / CELL_PX
  const ranked = [...places].sort((a, b) => GROUP_RANK[placeGroup(a)] - GROUP_RANK[placeGroup(b)] || mix(a.id) - mix(b.id))
  const seen = new Set<string>()
  const major: RoutePlace[] = []
  const minor: RoutePlace[] = []
  for (const p of ranked) {
    const x = Math.floor(((p.longitude + 180) / 360) * world)
    const sin = Math.sin((p.latitude * Math.PI) / 180)
    const y = Math.floor((0.5 - Math.log((1 + sin) / (1 - sin)) / (4 * Math.PI)) * world)
    const key = `${x}:${y}`
    if (seen.has(key)) minor.push(p)
    else {
      seen.add(key)
      major.push(p)
    }
  }
  return { major, minor }
}
/**
 * 지도 맨 위 — 갈래 칩 줄(가로로 넘김), 그 아래 가운데에 상태 알약 하나: 받는 중 / 「이 지역에서 다시 찾기」 / 「조금 더 확대해 주세요」.
 * 오른쪽 위 「내 위치」 단추(44)와 겹치지 않게 칩 줄 오른쪽을 비운다.
 */
export function MapPoiBar({ map, bottomInset, groupsOn, counts, loading, stale, tooWide, onToggle, onAll, onRefresh, onPick, dayCounts, currentDay, taken, onAddToDay }: {
  map: MapHandle | null
  bottomInset: number
  groupsOn: ReadonlySet<PoiGroup>
  counts: Partial<Record<PoiGroup, number>>
  loading: boolean
  stale: boolean
  tooWide: boolean
  onToggle: (g: PoiGroup) => void
  /** 「전체」 — 갈래를 고르지 않고 이 지역 편의시설을 다 찾는다(다 켜져 있으면 다 끈다). */
  onAll: () => void
  onRefresh: () => void
  /** 검색 결과를 골랐다 — 지도가 그곳으로 가고 정보 카드가 뜬다. */
  onPick: (place: RoutePlace) => void
  /** 일차마다 든 곳 수 · 보고 있는 일차 · 코스에 이미 든 촬영지 — 엔터 뒤 결과의 ＋(일차 고르기)가 쓴다. */
  dayCounts: readonly number[]
  currentDay: number
  taken: ReadonlySet<number>
  onAddToDay: (place: PlaceSummary, day: number) => void
}) {
  const [searching, setSearching] = useState(false)
  const [results, setResults] = useState<{ query: string; wide: boolean } | null>(null)
  const [closing, setClosing] = useState(false)
  const any = groupsOn.size > 0
  // 닫을 때는 줄어드는 움직임(0.2초)을 보인 뒤 칩 줄로 돌아간다.
  const close = () => {
    setClosing(true)
    window.setTimeout(() => {
      setSearching(false)
      setClosing(false)
    }, 200)
  }
  if (results) {
    return (
      <ResultsView
        map={map}
        bottomInset={bottomInset}
        query={results.query}
        wide={results.wide}
        dayCounts={dayCounts}
        currentDay={currentDay}
        taken={taken}
        onAddToDay={onAddToDay}
        onEdit={() => { setResults(null); setSearching(true) }}
        onExit={() => setResults(null)}
      />
    )
  }
  if (searching) {
    return (
      <SearchPanel
        map={map}
        bottomInset={bottomInset}
        closing={closing}
        onPick={(p) => { setSearching(false); onPick(p) }}
        onCommit={(query, wide) => { setSearching(false); setResults({ query, wide }) }}
        onClose={close}
      />
    )
  }
  return (
    <div style={{ position: 'absolute', top: 10, left: 0, right: 0, zIndex: 6, pointerEvents: 'none' }}>
      {/* 「내 위치」 단추는 시안에서 칩 줄 아래로 내려가 있다 — 줄이 폭을 다 쓴다. */}
      <div className="scroll-x" style={{ pointerEvents: 'auto' }}>
        <HStack spacing={6} style={{ padding: '0 10px', width: 'max-content' }}>
          {/* 검색 — 칩 줄 맨 앞. 평소엔 동그라미 하나라 지도를 가리지 않는다. */}
          <Pressable plain onClick={() => setSearching(true)} aria-label={say('장소 검색', 'Search places')} style={{ flex: 'none', minHeight: 44, display: 'grid', alignItems: 'center' }}>
            <span style={{ display: 'grid', placeItems: 'center', width: 34, height: 34, borderRadius: 999, background: 'var(--system-background)', boxShadow: shadow.card, border: '1px solid var(--system-gray5)' }}>
              <Icon name="magnifyingglass" size={15} />
            </span>
          </Pressable>
          {/* 전체 — 분류를 고르지 않고 찾기. 넷이 다 켜진 것과 같다. */}
          <Pressable plain aria-pressed={groupsOn.size === POI_GROUPS.length} onClick={onAll} style={{ flex: 'none', minHeight: 44, display: 'grid', alignItems: 'center' }}>
            <span
              style={{
                display: 'inline-flex', alignItems: 'center', height: 34, padding: '0 12px', borderRadius: radius.pill,
                background: groupsOn.size === POI_GROUPS.length ? 'var(--home-purple)' : 'var(--system-background)', boxShadow: shadow.card,
                border: groupsOn.size === POI_GROUPS.length ? 'none' : '1px solid var(--system-gray5)',
              }}
            >
              <Txt size={13} weight="semibold" style={{ color: groupsOn.size === POI_GROUPS.length ? '#fff' : 'var(--label)' }}>{say('전체', 'All')}</Txt>
            </span>
          </Pressable>
          {POI_GROUPS.map((g) => {
            const on = groupsOn.has(g)
            const n = counts[g]
            return (
              <Pressable
                key={g}
                plain
                aria-pressed={on}
                onClick={() => onToggle(g)}
                style={{ flex: 'none', minHeight: 44, display: 'grid', alignItems: 'center' }}
              >
                <span
                  style={{
                    display: 'inline-flex', alignItems: 'center', gap: 6, height: 34, padding: '0 12px', borderRadius: radius.pill,
                    background: on ? poiTone(g) : 'var(--system-background)', boxShadow: shadow.card, border: on ? 'none' : '1px solid var(--system-gray5)',
                  }}
                >
                  <span aria-hidden style={{ width: 8, height: 8, borderRadius: 999, background: on ? '#fff' : poiTone(g) }} />
                  <Txt size={13} weight="semibold" style={{ color: on ? '#fff' : 'var(--label)' }}>{poiGroupLabel(g)}</Txt>
                  {on && n !== undefined && <Txt size={12} style={{ color: 'rgba(255,255,255,0.85)' }}>{n}</Txt>}
                </span>
              </Pressable>
            )
          })}
        </HStack>
      </div>
      {any && (loading || stale || tooWide) && (
        <div style={{ display: 'flex', justifyContent: 'center', marginTop: 4 }}>
          {loading ? (
            <Status><Spinner size={14} /><Txt size={13} weight="semibold">{say('찾는 중', 'Searching')}</Txt></Status>
          ) : tooWide ? (
            <Status><Icon name="plus.magnifyingglass" size={14} /><Txt size={13} weight="semibold">{say('조금 더 확대하면 보여요', 'Zoom in to see places')}</Txt></Status>
          ) : (
            <Pressable plain onClick={onRefresh} style={{ pointerEvents: 'auto', minHeight: 44, display: 'grid', alignItems: 'center' }}>
              <Status><Icon name="arrow.clockwise" size={14} color="var(--home-purple)" /><Txt size={13} weight="semibold" style={{ color: 'var(--home-purple)' }}>{say('이 지역에서 다시 찾기', 'Search this area')}</Txt></Status>
            </Pressable>
          )}
        </div>
      )}
    </div>
  )
}

function Status({ children }: { children: React.ReactNode }) {
  return (
    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6, height: 34, padding: '0 14px', borderRadius: radius.pill, background: 'var(--system-background)', boxShadow: shadow.float, pointerEvents: 'auto' }}>
      {children}
    </span>
  )
}

// ---------------------------------------------------------------- 검색

const MIN_CHARS = 2
const QUIET_MS = 350
const LIMIT = 20

type Phase = { kind: 'idle' } | { kind: 'loading' } | { kind: 'done'; items: PlaceSummary[]; wide: boolean } | { kind: 'failed' }

/** 칩 줄 자리를 덮는 검색칸 + 그 아래 결과 칸. 검색 중에는 갈래 칩을 숨긴다(검색어와 칩 필터가 겹치면 「왜 안 나오지?」 가 생긴다). */
function SearchPanel({ map, bottomInset, closing, onPick, onCommit, onClose }: {
  map: MapHandle | null
  bottomInset: number
  closing: boolean
  onPick: (p: RoutePlace) => void
  /** 엔터 — 목록을 닫고 결과를 지도에 펼친다. */
  onCommit: (query: string, wide: boolean) => void
  onClose: () => void
}) {
  const [text, setText] = useState('')
  const [wide, setWide] = useState(false)
  const [phase, setPhase] = useState<Phase>({ kind: 'idle' })
  const ticket = useRef(0)
  const input = useRef<HTMLInputElement>(null)
  useEffect(() => input.current?.focus(), [])

  useEffect(() => {
    const q = text.trim()
    const mine = ++ticket.current
    if ([...q].length < MIN_CHARS) {
      setPhase({ kind: 'idle' })
      return
    }
    setPhase({ kind: 'loading' })
    const t = setTimeout(() => {
      // 먼저 지금 지도 범위(bbox), 「이 지역 밖에서도」 를 누르면 범위 없이(서버 기본 순서).
      const v = map && !wide ? viewportOf(map, bottomInset) : null
      const bbox = v ? `${v.west},${v.south},${v.east},${v.north}` : undefined
      ok(api.GET('/places', { params: { query: { q, limit: LIMIT, ...(bbox ? { bbox } : {}) } } }))
        .then((page) => mine === ticket.current && setPhase({ kind: 'done', items: page.items, wide }))
        .catch(() => mine === ticket.current && setPhase({ kind: 'failed' }))
    }, QUIET_MS)
    return () => clearTimeout(t)
  }, [text, wide, map, bottomInset])

  const pick = (p: PlaceSummary) => onPick(makeSpot({ id: `place-${p.id}`, name: p.name, category: p.type ?? null, address: p.address ?? null, latitude: p.latitude, longitude: p.longitude }))

  return (
    <div style={{ position: 'absolute', top: 10, left: 10, right: 10, zIndex: 7, display: 'flex', flexDirection: 'column', gap: 8, maxHeight: 'calc(100% - 20px)' }}>
      {/* 검색칸 — ← 닫기 · 입력 · ✕ 지우기 */}
      <HStack className={closing ? 'proto-search-close' : 'proto-search-open'} spacing={4} style={{ height: 46, padding: '0 6px', borderRadius: 999, background: 'var(--system-background)', boxShadow: shadow.float, flex: 'none' }}>
        <Pressable plain onClick={onClose} aria-label={say('검색 닫기', 'Close search')} style={{ width: 40, height: 40, display: 'grid', placeItems: 'center' }}>
          <Icon name="chevron.left" size={17} weight="semibold" />
        </Pressable>
        <input
          ref={input}
          className="bare-input"
          value={text}
          onChange={(e) => { setText(e.target.value); setWide(false) }}
          onKeyDown={(e) => {
            if (e.key === 'Escape') onClose()
            if (e.key === 'Enter' && !e.nativeEvent.isComposing && [...text.trim()].length >= MIN_CHARS) onCommit(text.trim(), wide)
          }}
          placeholder={say('촬영지·가게 이름', 'Spot or shop name')}
          aria-label={say('촬영지·가게 이름', 'Spot or shop name')}
          enterKeyHint="search"
          style={{ flex: 1, minWidth: 0, fontSize: 16 }}
        />
        {text && (
          <Pressable plain onClick={() => setText('')} aria-label={say('지우기', 'Clear')} style={{ width: 40, height: 40, display: 'grid', placeItems: 'center' }}>
            <Icon name="xmark.circle.fill" size={18} color="tertiary" />
          </Pressable>
        )}
      </HStack>

      {phase.kind !== 'idle' && !closing && (
        <div className="scroll-y proto-search-results" style={{ minHeight: 0, maxHeight: 420, borderRadius: radius.m, background: 'var(--system-background)', boxShadow: shadow.float, overscrollBehavior: 'contain' }}>
          {phase.kind === 'loading' ? (
            <VStack align="center" style={{ padding: 24 }}><Spinner size={18} /></VStack>
          ) : phase.kind === 'failed' ? (
            <Txt size={14} color="secondary" align="center" style={{ display: 'block', padding: 20 }}>{say('찾지 못했어요. 잠시 후 다시 해 주세요', 'Search failed. Try again shortly.')}</Txt>
          ) : (
            <VStack spacing={0} align="stretch">
              <Section title={phase.wide ? say('촬영지 · 전체', 'Filming spots · everywhere') : say('촬영지 · 이 지역', 'Filming spots · this area')} />
              {phase.items.length === 0 ? (
                <Txt size={13} color="secondary" style={{ display: 'block', padding: '4px 16px 12px' }}>{phase.wide ? say('찾는 촬영지가 없어요', 'No spots found') : say('이 지역엔 없어요', 'None in this area')}</Txt>
              ) : (
                phase.items.map((p, i) => (
                  <Pressable key={p.id} plain onClick={() => pick(p)} style={{ width: '100%', textAlign: 'left', paddingLeft: 14 }}>
                    <HStack spacing={12}>
                      <span style={{ flex: 'none', margin: '8px 0', display: 'grid' }}><RemoteImage url={p.imageUrl} symbol="mappin.and.ellipse" size={{ w: 40, h: 40 }} radius={8} /></span>
                      <VStack spacing={2} align="start" style={{ flex: 1, minWidth: 0, alignSelf: 'stretch', justifyContent: 'center', paddingRight: 14, borderTop: i > 0 ? '0.5px solid var(--separator)' : undefined }}>
                        <Txt size={15} weight="semibold" lines={1}>{p.name}</Txt>
                        <Txt size={12} color="secondary" lines={1}>{[p.contents?.[0]?.title, p.address].filter(Boolean).join(' · ')}</Txt>
                      </VStack>
                    </HStack>
                  </Pressable>
                ))
              )}
              {!phase.wide && (
                <Pressable plain onClick={() => setWide(true)} style={{ minHeight: 44, borderTop: '0.5px solid var(--separator)' }}>
                  <Txt size={14} weight="semibold" align="center" style={{ color: 'var(--home-purple)' }}>{say('이 지역 밖에서도 찾기', 'Search everywhere')}</Txt>
                </Pressable>
              )}
              {/* 편의시설 — 서버에 이름 검색이 생기면 여기 같은 모양으로 들어온다 */}
              <Section title={say('주변 가게·편의시설', 'Nearby shops & places')} />
              <Txt size={13} color="tertiary" style={{ display: 'block', padding: '4px 16px 14px' }}>{say('가게 이름 검색은 준비 중이에요 (서버 작업 후)', 'Shop name search is coming soon')}</Txt>
            </VStack>
          )}
        </div>
      )}
    </div>
  )
}

function Section({ title }: { title: string }) {
  return <Txt size={12} weight="semibold" color="secondary" style={{ display: 'block', padding: '12px 16px 6px', background: 'var(--grouped-background)' }}>{title}</Txt>
}

// ---------------------------------------------------------------- 엔터 뒤 — 결과를 지도에 펼친다

/**
 * 결과 핀 — 그 장소 사진이 든 동그라미(흰 테두리 · 그림자) 밑에 작은 꼬리. 사진이 없으면 보라 바탕에 흰 점.
 * 물방울 모양 단색 핀은 「못생겼다」(2026-11-08 사용자 지적) — 사진으로 장소를 기억하는 앱이라 지도에서도 사진을 보인다.
 * 고른 핀은 크게 + 아래 이름표. 사진 주소는 서버가 준 URL 이라 속성 값으로만 넣고 따옴표를 막는다.
 */
const SMALL = 34
const BIG = 46
const safeUrl = (u: string) => u.replace(/["'<>\\]/g, '')
const escapeText = (t: string) => t.replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]!)

function pinHtml(p: PlaceSummary, on: boolean): string {
  const d = on ? BIG : SMALL
  const face = p.imageUrl
    ? `<img src="${safeUrl(p.imageUrl)}" alt="" referrerpolicy="no-referrer" style="width:100%;height:100%;object-fit:cover;display:block">`
    : '<div style="width:100%;height:100%;display:grid;place-items:center;background:#5c4ad6"><div style="width:30%;height:30%;border-radius:50%;background:#fff"></div></div>'
  const label = on
    ? `<div style="position:absolute;top:${d + 8}px;left:50%;transform:translateX(-50%);white-space:nowrap;padding:3px 8px;border-radius:999px;background:#fff;box-shadow:0 1px 4px rgba(0,0,0,.25);font:600 12px/1.3 -apple-system,'Apple SD Gothic Neo',sans-serif;color:#111">${escapeText(p.name)}</div>`
    : ''
  return `<div style="position:relative;width:${d}px;height:${d + 6}px">
    <div style="position:absolute;left:50%;bottom:0;width:10px;height:10px;transform:translateX(-50%) rotate(45deg);background:${on ? '#5c4ad6' : '#fff'};box-shadow:1px 1px 3px rgba(0,0,0,.2)"></div>
    <div style="position:relative;width:${d}px;height:${d}px;border-radius:50%;overflow:hidden;border:${on ? 3 : 2.5}px solid ${on ? '#5c4ad6' : '#fff'};box-sizing:border-box;box-shadow:0 2px 8px rgba(0,0,0,.3);background:#eee">${face}</div>
    ${label}
  </div>`
}

function ResultsView({ map, bottomInset, query, wide, dayCounts, currentDay, taken, onAddToDay, onEdit, onExit }: {
  map: MapHandle | null
  bottomInset: number
  query: string
  wide: boolean
  dayCounts: readonly number[]
  currentDay: number
  taken: ReadonlySet<number>
  onAddToDay: (place: PlaceSummary, day: number) => void
  onEdit: () => void
  onExit: () => void
}) {
  const [items, setItems] = useState<PlaceSummary[] | null>(null)
  const [failed, setFailed] = useState(false)
  const [ask, setAsk] = useState(0)
  const [area, setArea] = useState(wide)
  const [stale, setStale] = useState(false)
  const [selected, setSelected] = useState<number | null>(null)
  const [asking, setAsking] = useState<PlaceSummary | null>(null)
  const [tall, setTall] = useState(false)
  /** 우리가 지도를 옮긴 직후의 멈춤은 사람이 옮긴 것이 아니다 — 그동안은 「다시 찾기」 를 띄우지 않는다. */
  const selfMove = useRef(0)

  // 받기 — 처음, 「다시 찾기」, 「이 지역 밖에서도」.
  useEffect(() => {
    let live = true
    setItems(null)
    setFailed(false)
    const v = map && !area ? viewportOf(map, bottomInset) : null
    const bbox = v ? `${v.west},${v.south},${v.east},${v.north}` : undefined
    ok(api.GET('/places', { params: { query: { q: query, limit: 40, ...(bbox ? { bbox } : {}) } } }))
      .then((page) => {
        if (!live) return
        setItems(page.items)
        setStale(false)
        // 결과가 다 보이게 맞춘다(서랍이 덮은 아래는 빼고).
        if (map && page.items.length > 0) {
          const lats = page.items.map((p) => p.latitude)
          const lngs = page.items.map((p) => p.longitude)
          selfMove.current = Date.now()
          map.fitBounds(
            { southWest: { lat: Math.min(...lats), lng: Math.min(...lngs) }, northEast: { lat: Math.max(...lats), lng: Math.max(...lngs) } },
            { paddingTopLeft: [40, 80], paddingBottomRight: [40, (map.getSize().y * PANEL_SHARE) + 40], maxZoom: 16 },
          )
        }
      })
      .catch(() => live && setFailed(true))
    return () => {
      live = false
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- 받는 계기는 검색어·범위·「다시 찾기」 뿐이다.
  }, [query, area, ask])

  // 지도를 옮기면 「이 지역에서 다시 찾기」.
  useEffect(() => {
    if (!map) return
    return map.on('moveend', () => {
      if (Date.now() - selfMove.current < 900) return
      setStale(true)
    })
  }, [map])

  // 결과 핀 — 따로 한 겹에 그린다. 끝내면 겹째 지운다.
  useEffect(() => {
    if (!map || !items) return
    const layer = map.createLayer()
    for (const p of items) {
      const on = p.id === selected
      layer.addMarker({
        position: { lat: p.latitude, lng: p.longitude }, html: pinHtml(p, on), className: 'proto-result-pin',
        size: on ? [BIG, BIG + 6] : [SMALL, SMALL + 6], anchor: on ? [BIG / 2, BIG + 6] : [SMALL / 2, SMALL + 6], zIndex: on ? 900 : 800, title: p.name,
        onClick: () => {
          setSelected(p.id)
          document.getElementById(`proto-result-${p.id}`)?.scrollIntoView({ block: 'nearest', behavior: 'smooth' })
        },
      })
    }
    return () => layer.remove()
  }, [map, items, selected])

  const focus = (p: PlaceSummary) => {
    setSelected(p.id)
    if (!map) return
    selfMove.current = Date.now()
    map.setView({ lat: p.latitude, lng: p.longitude }, Math.max(map.getZoom(), 15), { animate: true, shiftY: (map.getSize().y * PANEL_SHARE) / 2 })
  }

  return (
    <div style={{ position: 'absolute', inset: 0, zIndex: 16, pointerEvents: 'none' }}>
      {/* 위 — 검색어 알약(누르면 고쳐 쓰기) · ✕ 끝내기 */}
      <HStack spacing={4} style={{ position: 'absolute', top: 10, left: 10, right: 10, height: 46, padding: '0 6px 0 14px', borderRadius: 999, background: 'var(--system-background)', boxShadow: shadow.float, pointerEvents: 'auto' }}>
        <Icon name="magnifyingglass" size={15} color="secondary" />
        <Pressable plain onClick={onEdit} style={{ flex: 1, minWidth: 0, minHeight: 44, textAlign: 'left' }}>
          <Txt size={16} weight="semibold" lines={1}>{`“${query}”`} <Txt size={14} color="secondary">{items ? say(`${items.length}곳`, `${items.length}`) : ''}</Txt></Txt>
        </Pressable>
        <Pressable plain onClick={onExit} aria-label={say('검색 끝내기', 'End search')} style={{ width: 40, height: 40, display: 'grid', placeItems: 'center' }}>
          <Icon name="xmark.circle.fill" size={20} color="tertiary" />
        </Pressable>
      </HStack>

      {/* 지도를 옮긴 뒤에만 — 이 지역에서 다시 찾기 */}
      {stale && items && (
        <div style={{ position: 'absolute', top: 64, left: 0, right: 0, display: 'flex', justifyContent: 'center' }}>
          <Pressable plain onClick={() => { setArea(false); setAsk((n) => n + 1) }} style={{ pointerEvents: 'auto', minHeight: 44, display: 'grid', alignItems: 'center' }}>
            <Status><Icon name="arrow.clockwise" size={14} color="var(--home-purple)" /><Txt size={13} weight="semibold" style={{ color: 'var(--home-purple)' }}>{say('이 지역에서 다시 찾기', 'Search this area')}</Txt></Status>
          </Pressable>
        </div>
      )}

      {/* 아래 서랍 — 손잡이를 누르면 크게/작게 */}
      <div
        className="proto-search-results"
        style={{
          position: 'absolute', left: 0, right: 0, bottom: 0, height: tall ? '78%' : `${PANEL_SHARE * 100}%`, display: 'flex', flexDirection: 'column',
          background: 'var(--grouped-background)', borderRadius: '16px 16px 0 0', boxShadow: '0 -2px 16px rgba(0,0,0,0.15)', pointerEvents: 'auto', transition: 'height 0.28s cubic-bezier(.32,.72,0,1)',
        }}
      >
        <Pressable plain onClick={() => setTall((v) => !v)} aria-label={tall ? say('목록 줄이기', 'Shrink list') : say('목록 키우기', 'Expand list')} style={{ width: '100%', padding: '8px 0 4px', display: 'grid', placeItems: 'center' }}>
          <span style={{ width: 40, height: 5, borderRadius: 3, background: 'var(--system-gray3)' }} />
        </Pressable>
        {/* 탭 — 촬영지 / 가게(준비 중) */}
        <HStack spacing={6} style={{ padding: '2px 16px 10px', flex: 'none' }}>
          <span style={{ padding: '6px 12px', borderRadius: 999, background: 'var(--label)', color: 'var(--system-background)', fontSize: 13, fontWeight: 700 }}>{say(`촬영지 ${items?.length ?? ''}`, `Spots ${items?.length ?? ''}`)}</span>
          <span style={{ padding: '6px 12px', borderRadius: 999, background: 'var(--system-gray6)', color: 'var(--label-tertiary)', fontSize: 13, fontWeight: 600 }}>{say('가게 · 준비 중', 'Shops · soon')}</span>
        </HStack>
        <div className="scroll-y" style={{ flex: 1, minHeight: 0, padding: '0 16px 20px', overscrollBehavior: 'contain' }}>
          {failed ? (
            <Txt size={14} color="secondary" align="center" style={{ display: 'block', padding: 24 }}>{say('찾지 못했어요. 잠시 후 다시 해 주세요', 'Search failed. Try again shortly.')}</Txt>
          ) : items === null ? (
            <VStack align="center" style={{ padding: 24 }}><Spinner size={18} /></VStack>
          ) : items.length === 0 ? (
            <VStack spacing={10} align="center" style={{ padding: '24px 12px' }}>
              <Txt size={14} color="secondary" align="center">{area ? say('찾는 촬영지가 없어요', 'No spots found') : say('이 지역엔 없어요', 'None in this area')}</Txt>
              {!area && (
                <Pressable plain onClick={() => setArea(true)} style={{ minHeight: 40, padding: '0 16px', borderRadius: 999, background: 'var(--system-background)' }}>
                  <Txt size={14} weight="semibold" style={{ color: 'var(--home-purple)' }}>{say('이 지역 밖에서도 찾기', 'Search everywhere')}</Txt>
                </Pressable>
              )}
            </VStack>
          ) : (
            <ul style={{ listStyle: 'none', margin: 0, padding: 0, borderRadius: 14, background: 'var(--system-background)', overflow: 'hidden' }}>
              {items.map((p, i) => {
                const inCourse = taken.has(p.id)
                const on = p.id === selected
                return (
                  <li key={p.id} id={`proto-result-${p.id}`} style={{ background: on ? 'color-mix(in srgb, #1d4ed8 8%, var(--system-background))' : undefined }}>
                    <HStack spacing={12} style={{ paddingLeft: 12 }}>
                      <Pressable plain onClick={() => focus(p)} style={{ flex: 1, minWidth: 0, textAlign: 'left' }}>
                        <HStack spacing={12}>
                          <span style={{ flex: 'none', margin: '10px 0', display: 'grid' }}><RemoteImage url={p.imageUrl} symbol="mappin.and.ellipse" size={{ w: 52, h: 52 }} radius={10} /></span>
                          <VStack spacing={2} align="start" style={{ flex: 1, minWidth: 0, alignSelf: 'stretch', justifyContent: 'center', borderTop: i > 0 ? '0.5px solid var(--separator)' : undefined }}>
                            <Txt size={15} weight="semibold" lines={1}>{p.name}</Txt>
                            {p.contents?.[0]?.title && <Txt size={12} color="purple" lines={1}>{p.contents[0].title}</Txt>}
                            {p.address && <Txt size={12} color="secondary" lines={1}>{p.address}</Txt>}
                          </VStack>
                        </HStack>
                      </Pressable>
                      <span style={{ alignSelf: 'stretch', display: 'grid', alignItems: 'center', paddingRight: 8, borderTop: i > 0 ? '0.5px solid var(--separator)' : undefined }}>
                        <Pressable
                          plain
                          disabled={inCourse}
                          onClick={() => setAsking(p)}
                          aria-label={inCourse ? say('이미 코스에 있어요', 'Already in the trip') : say(`${p.name} 코스에 추가`, `Add ${p.name}`)}
                          style={{ width: 44, height: 44, display: 'grid', placeItems: 'center' }}
                        >
                          {inCourse
                            ? <Icon name="checkmark.circle.fill" size={24} color="var(--system-gray3)" />
                            : <span style={{ width: 30, height: 30, borderRadius: 999, display: 'grid', placeItems: 'center', background: 'var(--home-purple)' }}><Icon name="plus" size={15} weight="bold" color="white" /></span>}
                        </Pressable>
                      </span>
                    </HStack>
                  </li>
                )
              })}
            </ul>
          )}
        </div>
      </div>

      {asking && (
        <div style={{ pointerEvents: 'auto' }}>
          <DayPopup
            title={say(`「${asking.name}」 을 몇 일차에 넣을까요?`, `Add “${asking.name}” to which day?`)}
            dayCounts={dayCounts}
            current={currentDay}
            onPick={(day) => {
              onAddToDay(asking, day)
              setAsking(null)
            }}
            onClose={() => setAsking(null)}
          />
        </div>
      )}
    </div>
  )
}

// ---------------------------------------------------------------- 코스 번호 핀

/**
 * 코스 번호 핀 시안 — 시그니처 보라 단색 동그라미(흰 테두리 2)에 흰 굵은 번호, 아래 짧은 꼬리.
 * 원래 핀(route/map/pinSvg.numberedPinSvg)은 하늘→보라 그라데이션 물방울 속 흰 원에 보라 번호라 크고 겹이 많아 「못생겼다」(2026-11-08 사용자 지적).
 * 크기 30×38, 꼬리 끝 (15, 37).
 */
export const PROTO_NUMBERED = { w: 30, h: 38, tipX: 15, tipY: 37 } as const

/** `n` 이 없으면(검색 탭에서 덧붙인 핀) 번호 대신 흰 점. */
export function protoNumberedPinSvg(n: number | null): string {
  const { w, h } = PROTO_NUMBERED
  const font = "-apple-system,BlinkMacSystemFont,'SF Pro Text','Apple SD Gothic Neo',sans-serif"
  return (
    `<svg xmlns="http://www.w3.org/2000/svg" width="${w}" height="${h}" viewBox="0 0 ${w} ${h}" style="overflow:visible;filter:drop-shadow(0 2px 3px rgba(0,0,0,0.3))">` +
    `<path d="M10 25 L15 37 L20 25 Z" fill="#5c4ad6" stroke="#fff" stroke-width="2" stroke-linejoin="round"/>` +
    `<circle cx="15" cy="14" r="13" fill="#5c4ad6" stroke="#fff" stroke-width="2"/>` +
    `<path d="M11 25.5 L19 25.5 L15 34 Z" fill="#5c4ad6"/>` +
    (n === null
      ? `<circle cx="15" cy="14" r="4.5" fill="#fff"/>`
      : `<text x="15" y="14.5" text-anchor="middle" dominant-baseline="central" fill="#fff" font-weight="800" font-size="${n < 10 ? 13 : n < 100 ? 11.5 : 9}" font-family="${font}">${n}</text>`) +
    `</svg>`
  )
}
