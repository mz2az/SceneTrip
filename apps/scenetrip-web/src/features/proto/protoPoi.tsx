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
 * 편의시설 이름 검색은 `GET /pois?q=`(1.23.0, MZ2AZ-283) — 촬영지와 같은 범위(지금 지도 / 이 지역 밖)로 같이 묻는다.
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
import { CoachTour } from './CoachTour'
import { radius, shadow } from './protoTokens'

/** 엔터 뒤 결과 서랍이 처음 덮는 높이(지도의 비율). */
const PANEL_SHARE = 0.42


// ---------------------------------------------------------------- 골고루 퍼뜨리기 (구글 지도 방식, MZ2AZ-540)
//
// 구글 지도처럼 「지도에 고정된 칸마다 순위 1등만 큰 점, 나머지는 작은 점」. 칸은 화면이 아니라 지도(웹 메르카토르 좌표)에 고정이라
// 지도를 옮겨도 같은 곳이 같은 칸에 든다. 순위는 서버가 매긴다(GET /pois?sort=spread — 인기도 → 갈래 → 늘 같은 섞은 순서) — 받은 순서가 곧 순위다.
// 확대하면 다시 받지 않고 여기서 더 작은 칸으로 다시 솎는다(큰 점이 늘어나기만 한다).

/** 한 번에 받는 수 — 계약 상한. */
const SPREAD_LIMIT = 200
/** 칸 한 변(px, 그 줌에서). 폰 화면이면 큰 점이 30 개 안팎. */
const CELL_PX = 72

/** 지금 화면을 서버의 「골고루」 순서로 받는다. 갈래를 하나만 켰으면 서버가 거르고, 여럿이면 다 받아 여기서 거른다(요청은 한 번). */
export function fetchSpread(groups: readonly PoiGroup[], map: MapHandle | null) {
  const want = new Set(groups)
  return async (v: PoiViewport): Promise<PoiFetchResult> => {
    const bbox = `${v.west},${v.south},${v.east},${v.north}`
    const categoryGroup = groups.length === 1 ? groups[0] : undefined
    const spreadZoom = Math.max(0, Math.min(22, Math.floor(map?.getZoom() ?? 0)))
    const query = { bbox, lat: v.centerLat, lng: v.centerLng, sort: 'spread' as const, spreadZoom, limit: SPREAD_LIMIT, categoryGroup }
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
  // 받은 순서가 곧 순위다(서버 GET /pois?sort=spread, MZ2AZ-540) — 칸마다 먼저 온 것이 큰 점. 서버의 칸 안 순서와 같은 줄이라 확대해 칸을 쪼개도
  // 큰 점은 늘기만 한다(넓을 때 한 칸에서 앞섰던 것은 쪼갠 작은 칸에서도 앞선다).
  const world = (256 * 2 ** Math.floor(zoom)) / CELL_PX
  const seen = new Set<string>()
  const major: RoutePlace[] = []
  const minor: RoutePlace[] = []
  for (const p of places) {
    const x = Math.floor(((p.longitude + 180) / 360) * world)
    const lat = (p.latitude * Math.PI) / 180
    const y = Math.floor(((1 - Math.log(Math.tan(lat) + 1 / Math.cos(lat)) / Math.PI) / 2) * world)
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
        onPickShop={onPick}
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
      {/* 지도를 처음 펼치면 검색·분류를 짚어 준다(2026-11-09 사용자 요청). */}
      <CoachTour
        id="map-search"
        active
        delay={700}
        steps={[
          { target: '[data-coach="map-search"]', title: say('지도에서 검색', 'Search on the map'), text: say('촬영지가 아닌 곳도 찾을 수 있어요 — 맛집·카페·숙소·명소를 이름으로 검색해 「코스에 담기」 로 바로 넣어요.', 'Not just filming spots — search restaurants, cafés, stays and sights by name, then add them to your course.') },
          { target: '[data-coach="map-chips"]', title: say('주변 둘러보기', 'Browse nearby'), text: say('전체·음식·관광지·숙소 칩을 누르면 지금 보이는 지도 안의 장소가 핀으로 떠요.', 'Tap All, Food, Sights or Stays to pin places in the area you are looking at.') },
        ]}
      />
      {/* 「내 위치」 단추는 시안에서 칩 줄 아래로 내려가 있다 — 줄이 폭을 다 쓴다. */}
      <div className="scroll-x" style={{ pointerEvents: 'auto' }}>
        <HStack spacing={6} style={{ padding: '0 10px', width: 'max-content' }}>
          {/* 검색 — 칩 줄 맨 앞. 평소엔 동그라미 하나라 지도를 가리지 않는다. */}
          <Pressable data-coach="map-search" plain onClick={() => setSearching(true)} aria-label={say('장소 검색', 'Search places')} style={{ flex: 'none', minHeight: 44, display: 'grid', alignItems: 'center' }}>
            <span style={{ display: 'grid', placeItems: 'center', width: 34, height: 34, borderRadius: 999, background: 'var(--system-background)', boxShadow: shadow.card, border: '1px solid var(--system-gray5)' }}>
              <Icon name="magnifyingglass" size={15} />
            </span>
          </Pressable>
          {/* 전체 — 분류를 고르지 않고 찾기. 넷이 다 켜진 것과 같다. */}
          <Pressable data-coach="map-chips" plain aria-pressed={groupsOn.size === POI_GROUPS.length} onClick={onAll} style={{ flex: 'none', minHeight: 44, display: 'grid', alignItems: 'center' }}>
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

type Phase = { kind: 'idle' } | { kind: 'loading' } | { kind: 'done'; items: PlaceSummary[]; shops: RoutePlace[]; wide: boolean } | { kind: 'failed' }

/** 편의시설 이름 검색 — 촬영지 검색과 같은 범위. 실패하면 빈 목록(촬영지 결과는 그대로 보인다). */
function searchShops(q: string, bbox: string | undefined, limit: number): Promise<RoutePlace[]> {
  return ok(api.GET('/pois', { params: { query: { q, limit, ...(bbox ? { bbox } : {}) } } }))
    .then((page) => page.items.map(fromPoi))
    .catch(() => [])
}

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
      Promise.all([ok(api.GET('/places', { params: { query: { q, limit: LIMIT, ...(bbox ? { bbox } : {}) } } })), searchShops(q, bbox, LIMIT)])
        .then(([page, shops]) => mine === ticket.current && setPhase({ kind: 'done', items: page.items, shops, wide }))
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
              {/* 편의시설 — 이름 검색(GET /pois?q=). 누르면 그곳으로 가서 정보 카드(코스에 담기). */}
              <Section title={phase.wide ? say('가게·편의시설 · 전체', 'Shops & places · everywhere') : say('가게·편의시설 · 이 지역', 'Shops & places · this area')} />
              {phase.shops.length === 0 ? (
                <Txt size={13} color="secondary" style={{ display: 'block', padding: '4px 16px 14px' }}>{say('찾는 가게가 없어요', 'No shops found')}</Txt>
              ) : (
                phase.shops.map((p, i) => <ShopRow key={p.id} place={p} first={i === 0} onPick={() => onPick(p)} />)
              )}
            </VStack>
          )}
        </div>
      )}
    </div>
  )
}

/** 가게 한 줄 — 갈래 색 점 · 이름 · 분류·주소. */
function ShopRow({ place, first, onPick }: { place: RoutePlace; first: boolean; onPick: () => void }) {
  return (
    <Pressable plain onClick={onPick} style={{ width: '100%', textAlign: 'left', paddingLeft: 14 }}>
      <HStack spacing={12}>
        <span aria-hidden style={{ flex: 'none', width: 40, height: 40, margin: '8px 0', borderRadius: 8, display: 'grid', placeItems: 'center', background: 'var(--system-gray6)' }}>
          <span style={{ width: 12, height: 12, borderRadius: 999, background: poiTone(placeGroup(place)), border: '2px solid #fff' }} />
        </span>
        <VStack spacing={2} align="start" style={{ flex: 1, minWidth: 0, alignSelf: 'stretch', justifyContent: 'center', paddingRight: 14, borderTop: first ? undefined : '0.5px solid var(--separator)' }}>
          <Txt size={15} weight="semibold" lines={1}>{place.displayName ?? place.name}</Txt>
          <Txt size={12} color="secondary" lines={1}>{[place.categoryLabel ?? place.category, place.displayAddress ?? place.address].filter(Boolean).join(' · ')}</Txt>
        </VStack>
      </HStack>
    </Pressable>
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

function ResultsView({ map, bottomInset, query, wide, dayCounts, currentDay, taken, onAddToDay, onPickShop, onEdit, onExit }: {
  map: MapHandle | null
  bottomInset: number
  query: string
  wide: boolean
  dayCounts: readonly number[]
  currentDay: number
  taken: ReadonlySet<number>
  onAddToDay: (place: PlaceSummary, day: number) => void
  /** 가게 결과를 눌렀다 — 그곳으로 가서 정보 카드(코스에 담기). */
  onPickShop: (place: RoutePlace) => void
  onEdit: () => void
  onExit: () => void
}) {
  const [items, setItems] = useState<PlaceSummary[] | null>(null)
  const [shops, setShops] = useState<RoutePlace[]>([])
  const [tab, setTab] = useState<'spots' | 'shops'>('spots')
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
    setShops([])
    void searchShops(query, bbox, 40).then((found) => live && setShops(found))
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

  // 가게 탭의 핀 — 갈래 색 점. 누르면 정보 카드.
  useEffect(() => {
    if (!map || tab !== 'shops' || shops.length === 0) return
    const layer = map.createLayer()
    for (const p of shops) {
      layer.addMarker({
        position: { lat: p.latitude, lng: p.longitude },
        html: `<span style="display:block;width:16px;height:16px;border-radius:50%;background:${poiTone(placeGroup(p))};border:2.5px solid #fff;box-sizing:border-box;box-shadow:0 1px 3px rgba(0,0,0,0.35)"></span>`,
        className: 'proto-result-pin', size: [16, 16], anchor: [8, 8], zIndex: 800, title: p.name,
        onClick: () => onPickShop(p),
      })
    }
    return () => layer.remove()
  }, [map, tab, shops, onPickShop])

  // 결과 핀 — 따로 한 겹에 그린다. 끝내면 겹째 지운다.
  useEffect(() => {
    if (!map || !items || tab !== 'spots') return
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
  }, [map, items, selected, tab])

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
        {/* 탭 — 촬영지 / 가게(편의시설 이름 검색) */}
        <HStack spacing={6} role="tablist" style={{ padding: '2px 16px 10px', flex: 'none' }}>
          {(['spots', 'shops'] as const).map((t) => {
            const on = tab === t
            const label = t === 'spots' ? say(`촬영지 ${items?.length ?? ''}`, `Spots ${items?.length ?? ''}`) : say(`가게 ${shops.length}`, `Shops ${shops.length}`)
            return (
              <Pressable key={t} plain role="tab" aria-selected={on} onClick={() => setTab(t)} style={{ minHeight: 36, display: 'grid', alignItems: 'center' }}>
                <span style={{ padding: '6px 12px', borderRadius: 999, background: on ? 'var(--label)' : 'var(--system-gray6)', color: on ? 'var(--system-background)' : 'var(--label-secondary)', fontSize: 13, fontWeight: on ? 700 : 600 }}>{label}</span>
              </Pressable>
            )
          })}
        </HStack>
        <div className="scroll-y" style={{ flex: 1, minHeight: 0, padding: '0 16px 20px', overscrollBehavior: 'contain' }}>
          {tab === 'shops' ? (
            shops.length === 0 ? (
              <Txt size={14} color="secondary" align="center" style={{ display: 'block', padding: 24 }}>{area ? say('찾는 가게가 없어요', 'No shops found') : say('이 지역엔 없어요', 'None in this area')}</Txt>
            ) : (
              <div style={{ borderRadius: 14, background: 'var(--system-background)', overflow: 'hidden' }}>
                {shops.map((p, i) => <ShopRow key={p.id} place={p} first={i === 0} onPick={() => onPickShop(p)} />)}
              </div>
            )
          ) : failed ? (
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
