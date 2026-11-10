/*
 * 편의시설 「켤 때만 · 다시 찾기를 누를 때만」 시안 — 코스 편집 지도 위의 갈래 칩(2026-11-08 사용자 요청, UI/UX 점검).
 *
 * 원래: 줌 13 이상이면 저절로 30 개가 깔리고, 지도를 옮길 때마다 바뀐다(route/poi/useAmbientPois).
 *  - 있는지 모른다(확대 전에는 아무것도 없다), 보던 점이 사라진다, 코스 번호 핀과 섞인다.
 * 시안: 처음엔 다 꺼 두고, 지도 위 칩을 켜면 그 갈래만 지금 범위에서 받는다. 지도를 옮겨도 점은 그대로이고
 *  「이 지역에서 다시 찾기」 가 뜬다(누를 때만 바뀐다). 너무 넓으면 「조금 더 확대해 주세요」.
 * 받은 것은 지도에 고정된 칸마다 1등만 아이콘으로 찍고 나머지는 그리지 않는다(아래 「골고루 퍼뜨리기」, 작은 점은 2026-11-10 뺐다). 「전체」 칩은 분류를 고르지 않고 다 찾는다.
 *
 * 검색(칩 줄 맨 앞 🔍): 누르면 칩 줄이 검색칸으로 바뀐다. 가게·장소(편의시설)만 이름으로 찾는다 — 촬영지는 찾지 않는다(2026-11-10 사용자 요청).
 * 먼저 지금 지도 범위에서 찾고, 없거나 더 보고 싶으면 「이 지역 밖에서도 찾기」. 0.35초 멈추면 묻고 2글자부터(요청 한도 1분 60회).
 * 글자를 치는 동안에는 목록만 — 지도에는 찍지 않는다. 목록 맨 위 「‘q’ 검색」 이나 키보드 검색을 누르면 결과 서랍과 지도 아이콘 핀(구글처럼,
 * 2026-11-10 사용자 결정). 이름 검색은 `GET /pois?q=`(1.23.0, MZ2AZ-283).
 * 로컬 개발 서버 + 시안일 때만 쓰인다(docs/project/plans/web-trip-first-prototypes.md).
 */
import { api, ok } from '@/api/client'
import { Icon } from '@/design/Icon'
import { useEffect, useRef, useState } from 'react'
import { HStack, Spinner, Txt, VStack } from '@/design/primitives'
import type { MapHandle } from '@/map'
import type { PoiFetchResult, PoiViewport } from '@/features/route/poi/poiApi'
import { POI_GROUPS, type PoiGroup, poiGroupLabel, poiTone } from '@/features/route/poi/poiGroup'
import { type RoutePlace, fromPoi, placeGroup, placeSymbol } from '@/features/route/poi/poiPlace'
import { DOT_IMAGE, poiDotHtml } from '@/features/route/poi/PoiDot'
import { viewportOf } from '@/features/route/poi/useAmbientPois'
import { Pressable } from '@/features/route/ui/Press'
import { say } from './protoState'
import { CoachTour } from './CoachTour'
import { radius, shadow } from './protoTokens'
import { DRAWER_HIGH, DRAWER_LOW, dragShare, settleShare, toggleShare } from './searchDrawer'
import { RESULTS_PAGE_SIZE, fitsMapForPage, mergeResultsPage, nextPageOffset, reachedLoadMore } from './searchPaging'

/** 엔터 뒤 결과 서랍이 처음 덮는 높이(지도의 비율). */
const PANEL_SHARE = DRAWER_LOW
/** 손가락이 이만큼(px) 안 움직이고 떼면 끈 것이 아니라 누른 것(손잡이 누름 → 42% ↔ 78%). */
const TAP_SLOP = 6


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
export function MapPoiBar({ map, bottomInset, groupsOn, counts, loading, stale, tooWide, onToggle, onAll, onRefresh, onPick, pickedId = null }: {
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
  /** 검색 결과를 골랐다 — 지도는 여기서 이미 그곳으로 옮겼다. 받는 쪽은 정보 카드를 띄운다. */
  onPick: (place: RoutePlace) => void
  /** 정보 카드가 떠 있는 곳 — 그 자리는 고른 핀(mapOverlay)이 그리므로 검색 점을 찍지 않는다. */
  pickedId?: string | null
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
  // 고른 곳으로 — 시트·결과 서랍이 덮지 않은 지도 가운데에 오게 한다. 예전에는 지도 칸 전체의 가운데로 옮겨 핀이 시트 밑에 숨고,
  // 보이는 지도 가운데(움직임이 향한 곳)에는 아무것도 없었다(2026-11-10 사용자 지적: 「핀과 효과 위치가 일치하지 않는다」).
  const pick = (p: RoutePlace) => {
    if (map) focusPlace(map, p, Math.max(bottomInset, results ? map.getSize().y * PANEL_SHARE : 0))
    onPick(p)
  }
  if (results) {
    return (
      <ResultsView
        map={map}
        bottomInset={bottomInset}
        query={results.query}
        wide={results.wide}
        pickedId={pickedId}
        onPick={pick}
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
        onPick={(p) => { setSearching(false); pick(p) }}
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
          { target: '[data-coach="map-search"]', title: say('지도에서 검색', 'Search on the map'), text: say('촬영지가 아닌 가게·장소를 찾아요 — 맛집·카페·숙소·명소를 이름으로 검색해 「코스에 담기」 로 바로 넣어요.', 'Find places beyond filming spots — search restaurants, cafés, stays and sights by name, then add them to your course.') },
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
/** 고른 곳의 정보 카드(아래 단추 줄 위)가 지도 아래를 덮는 높이 — 시트를 낮게 내려 두었어도 핀이 카드 밑에 숨지 않게 이만큼은 비킨다. */
const CARD_COVER = 300

type Phase = { kind: 'idle' } | { kind: 'loading' } | { kind: 'done'; shops: RoutePlace[]; wide: boolean } | { kind: 'failed' }

/** 가게·장소 이름 검색(GET /pois?q=). `bbox` 가 있으면 그 범위 안에서만. */
function searchShops(q: string, bbox: string | undefined, limit: number): Promise<RoutePlace[]> {
  return searchShopsPage(q, bbox, limit, 0).then((page) => page.shops)
}

/** 한 쪽 — 가게와 조건에 맞는 전체 개수(`total`). 결과 서랍의 「더 받기」 가 쓴다. */
function searchShopsPage(q: string, bbox: string | undefined, limit: number, offset: number): Promise<{ shops: RoutePlace[]; total: number }> {
  const query = { q, limit, ...(offset > 0 ? { offset } : {}), ...(bbox ? { bbox } : {}) }
  return ok(api.GET('/pois', { params: { query } })).then((page) => ({ shops: page.items.map(fromPoi), total: page.total }))
}

/**
 * 고른 곳으로 지도를 옮긴다 — 아래 `covered`(px)가 덮은 나머지 지도의 가운데에 오게. 덮은 높이의 반만큼 위로 올리면 된다
 * (RouteMap 의 「목록에서 고른 곳」 과 같은 규칙: shiftY = 시트 높이 / 2).
 */
export function focusPlace(map: MapHandle, p: RoutePlace, covered: number): void {
  map.setView({ lat: p.latitude, lng: p.longitude }, Math.max(map.getZoom(), 16), { animate: true, shiftY: Math.max(covered, CARD_COVER) / 2 })
}

/** 찾은 가게를 지도에 갈래 아이콘으로 찍는다(기본 지도의 1등 아이콘과 같은 모양 — 점이 아니다, 2026-11-10 사용자 결정). `shops` 가 바뀌면 다시 찍고, 화면이 사라지면 지운다. 정보 카드가 떠 있는 곳(`pickedId`)은 고른 핀이 그리므로 뺀다. */
function useShopPins(map: MapHandle | null, shops: readonly RoutePlace[], pickedId: string | null, onPick: (p: RoutePlace) => void) {
  const pickRef = useRef(onPick)
  useEffect(() => {
    pickRef.current = onPick
  })
  useEffect(() => {
    if (!map || shops.length === 0) return
    const layer = map.createLayer()
    for (const p of shops) {
      if (p.id === pickedId) continue
      layer.addMarker({
        position: { lat: p.latitude, lng: p.longitude },
        html: poiDotHtml(placeGroup(p), placeSymbol(p)),
        className: 'route-pin tm-dot proto-result-pin', size: [DOT_IMAGE, DOT_IMAGE], anchor: [DOT_IMAGE / 2, DOT_IMAGE / 2], zIndex: 20, title: p.displayName ?? p.name,
        onClick: () => pickRef.current(p),
      })
    }
    return () => layer.remove()
  }, [map, shops, pickedId])
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
      searchShops(q, bbox, LIMIT)
        .then((shops) => {
          if (mine !== ticket.current) return
          setPhase({ kind: 'done', shops, wide })
        })
        .catch(() => {
          if (mine !== ticket.current) return
          setPhase({ kind: 'failed' })
        })
    }, QUIET_MS)
    return () => clearTimeout(t)
  }, [text, wide, map, bottomInset])

  const q = text.trim()
  const canCommit = [...q].length >= MIN_CHARS

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
            if (e.key === 'Enter' && !e.nativeEvent.isComposing && canCommit) onCommit(q, wide)
          }}
          placeholder={say('가게·장소 이름', 'Shop or place name')}
          aria-label={say('가게·장소 이름', 'Shop or place name')}
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
          {/* 맨 위 줄 — 이 검색어로 결과 서랍을 연다(키보드 검색과 같다). */}
          {canCommit && (
            <Pressable plain onClick={() => onCommit(q, wide)} style={{ width: '100%', textAlign: 'left', paddingLeft: 16 }}>
              <HStack spacing={10} style={{ minHeight: 48, paddingRight: 16 }}>
                <Icon name="magnifyingglass" size={15} color="var(--home-purple)" />
                <Txt size={15} weight="semibold" lines={1} style={{ color: 'var(--home-purple)' }}>{say(`‘${q}’ 검색`, `Search “${q}”`)}</Txt>
              </HStack>
            </Pressable>
          )}
          {phase.kind === 'loading' ? (
            <VStack align="center" style={{ padding: 24 }}><Spinner size={18} /></VStack>
          ) : phase.kind === 'failed' ? (
            <Txt size={14} color="secondary" align="center" style={{ display: 'block', padding: 20 }}>{say('찾지 못했어요. 잠시 후 다시 해 주세요', 'Search failed. Try again shortly.')}</Txt>
          ) : (
            <VStack spacing={0} align="stretch">
              {/* 가게·장소만 찾는다 — 촬영지는 여기서 찾지 않는다(2026-11-10 사용자 요청). 누르면 그곳으로 가서 정보 카드(코스에 담기). */}
              <Section title={phase.wide ? say('가게·장소 · 전체', 'Shops & places · everywhere') : say('가게·장소 · 이 지역', 'Shops & places · this area')} />
              {phase.shops.length === 0 ? (
                <Txt size={13} color="secondary" style={{ display: 'block', padding: '4px 16px 12px' }}>{phase.wide ? say('찾는 곳이 없어요', 'No places found') : say('이 지역엔 없어요', 'None in this area')}</Txt>
              ) : (
                phase.shops.map((p, i) => <ShopRow key={p.id} place={p} first={i === 0} onPick={() => onPick(p)} />)
              )}
              {!phase.wide && (
                <Pressable plain onClick={() => setWide(true)} style={{ minHeight: 44, borderTop: '0.5px solid var(--separator)' }}>
                  <Txt size={14} weight="semibold" align="center" style={{ color: 'var(--home-purple)' }}>{say('이 지역 밖에서도 찾기', 'Search everywhere')}</Txt>
                </Pressable>
              )}
            </VStack>
          )}
        </div>
      )}
    </div>
  )
}

/** 가게 한 줄 — 이름 · 분류·주소. 글은 줄 왼쪽 여백(16)에서 시작하고 구분선도 같은 들여쓰기(왼쪽 점·회색 칸은 뺐다, MZ2AZ-547). */
function ShopRow({ place, first, onPick }: { place: RoutePlace; first: boolean; onPick: () => void }) {
  return (
    <Pressable plain onClick={onPick} style={{ width: '100%', textAlign: 'left', paddingLeft: 16 }}>
      <VStack spacing={2} align="start" style={{ minWidth: 0, minHeight: 56, justifyContent: 'center', padding: '8px 16px 8px 0', borderTop: first ? undefined : '0.5px solid var(--separator)' }}>
        <Txt size={15} weight="semibold" lines={1}>{place.displayName ?? place.name}</Txt>
        <Txt size={12} color="secondary" lines={1}>{[place.categoryLabel ?? place.category, place.displayAddress ?? place.address].filter(Boolean).join(' · ')}</Txt>
      </VStack>
    </Pressable>
  )
}

function Section({ title }: { title: string }) {
  return <Txt size={12} weight="semibold" color="secondary" style={{ display: 'block', padding: '12px 16px 6px', background: 'var(--grouped-background)' }}>{title}</Txt>
}

// ---------------------------------------------------------------- 엔터 뒤 — 결과를 지도에 펼친다

function ResultsView({ map, bottomInset, query, wide, pickedId, onPick, onEdit, onExit }: {
  map: MapHandle | null
  bottomInset: number
  query: string
  wide: boolean
  pickedId: string | null
  /** 결과를 눌렀다 — 그곳으로 가서 정보 카드(코스에 담기). */
  onPick: (place: RoutePlace) => void
  onEdit: () => void
  onExit: () => void
}) {
  const [shops, setShops] = useState<RoutePlace[] | null>(null)
  const [failed, setFailed] = useState(false)
  /** 더 받기 — 전체 개수, 바로 앞 쪽이 비었나, 받는 중, 더 받다 실패. 처음 쪽과 같은 범위(bbox)로 이어 받는다. */
  const [total, setTotal] = useState<number | null>(null)
  const [lastEmpty, setLastEmpty] = useState(false)
  const [moreBusy, setMoreBusy] = useState(false)
  const [moreFailed, setMoreFailed] = useState(false)
  const pageBox = useRef<string | undefined>(undefined)
  const round = useRef(0)
  const list = useRef<HTMLDivElement>(null)
  const [ask, setAsk] = useState(0)
  const [area, setArea] = useState(wide)
  const [stale, setStale] = useState(false)
  /** 서랍 높이(지도의 비율). 손을 떼면 42% 또는 78% 에 붙는다. */
  const [share, setShare] = useState(PANEL_SHARE)
  const [dragging, setDragging] = useState(false)
  const panel = useRef<HTMLDivElement>(null)
  /** 끈 뒤에 따라오는 click 을 누름으로 세지 않게. */
  const dragged = useRef(false)
  const drag = useRef<{ id: number; startY: number; startShare: number; mapHeight: number; lastY: number; lastT: number; v: number; moved: boolean } | null>(null)
  /** 우리가 지도를 옮긴 직후의 멈춤은 사람이 옮긴 것이 아니다 — 그동안은 「다시 찾기」 를 띄우지 않는다. */
  const selfMove = useRef(0)

  // 받기 — 처음, 「다시 찾기」, 「이 지역 밖에서도」.
  useEffect(() => {
    let live = true
    const mine = ++round.current
    setShops(null)
    setFailed(false)
    setTotal(null)
    setLastEmpty(false)
    setMoreBusy(false)
    setMoreFailed(false)
    const v = map && !area ? viewportOf(map, bottomInset) : null
    const bbox = v ? `${v.west},${v.south},${v.east},${v.north}` : undefined
    pageBox.current = bbox
    searchShopsPage(query, bbox, RESULTS_PAGE_SIZE, 0)
      .then((page) => {
        if (!live || mine !== round.current) return
        const found = page.shops
        setShops(found)
        setTotal(page.total)
        setLastEmpty(found.length === 0)
        setStale(false)
        // 결과가 다 보이게 맞춘다(시트·서랍이 덮은 아래는 빼고). 처음 쪽에서만 — 더 받을 때는 지도를 움직이지 않는다.
        if (map && found.length > 0 && fitsMapForPage(0)) {
          const lats = found.map((p) => p.latitude)
          const lngs = found.map((p) => p.longitude)
          selfMove.current = Date.now()
          map.fitBounds(
            { southWest: { lat: Math.min(...lats), lng: Math.min(...lngs) }, northEast: { lat: Math.max(...lats), lng: Math.max(...lngs) } },
            { paddingTopLeft: [40, 80], paddingBottomRight: [40, Math.max(bottomInset, map.getSize().y * PANEL_SHARE) + 40], maxZoom: 16 },
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

  const pick = (p: RoutePlace) => {
    selfMove.current = Date.now()
    onPick(p)
  }
  useShopPins(map, shops ?? [], pickedId, pick)

  // 더 받기 — 목록 끝 한 줄 안이 보이면 다음 20개. 받은 만큼 지도 핀도 는다(useShopPins 가 다시 찍는다).
  const loadMore = () => {
    if (!shops || moreBusy || moreFailed) return
    const offset = nextPageOffset(shops.length, total, lastEmpty)
    if (offset === null) return
    const mine = round.current
    setMoreBusy(true)
    searchShopsPage(query, pageBox.current, RESULTS_PAGE_SIZE, offset)
      .then((page) => {
        if (mine !== round.current) return
        setShops((now) => mergeResultsPage(now ?? [], page.shops))
        setTotal(page.total)
        setLastEmpty(page.shops.length === 0)
      })
      .catch(() => mine === round.current && setMoreFailed(true))
      .finally(() => mine === round.current && setMoreBusy(false))
  }
  /** 서랍 목록에서 지금 보이는 마지막 줄(받은 목록의 자리). 줄은 `data-row` 로 센다. */
  const checkNearEnd = () => {
    const box = list.current
    if (!box || !shops) return
    const bottom = box.getBoundingClientRect().bottom
    let last = -1
    box.querySelectorAll<HTMLElement>('[data-row]').forEach((row, i) => {
      if (row.getBoundingClientRect().top < bottom) last = i
    })
    if (reachedLoadMore(last, shops.length)) loadMore()
  }
  // 받은 뒤·서랍 높이가 바뀐 뒤에도 본다 — 목록이 짧아 스크롤이 없을 때도 끝이 보이면 이어 받는다.
  useEffect(checkNearEnd)

  // 서랍 끌기 — 손잡이·머리에서 누른 채 위아래로. 끄는 동안 손가락을 따라가고, 떼면 속도까지 보고 붙을 높이를 정한다.
  const mapHeight = () => panel.current?.parentElement?.clientHeight ?? 1
  const onDown = (e: React.PointerEvent) => {
    if (e.button !== 0) return
    e.currentTarget.setPointerCapture?.(e.pointerId)
    drag.current = { id: e.pointerId, startY: e.clientY, startShare: share, mapHeight: mapHeight(), lastY: e.clientY, lastT: e.timeStamp, v: 0, moved: false }
  }
  const onMove = (e: React.PointerEvent) => {
    const d = drag.current
    if (!d || d.id !== e.pointerId) return
    const dy = e.clientY - d.startY
    if (!d.moved && Math.abs(dy) < TAP_SLOP) return
    if (!d.moved) {
      d.moved = true
      setDragging(true)
    }
    const dt = e.timeStamp - d.lastT
    if (dt > 0) d.v = ((e.clientY - d.lastY) / dt) * 1000
    d.lastY = e.clientY
    d.lastT = e.timeStamp
    setShare(dragShare(d.startShare, dy, d.mapHeight))
  }
  const onUp = (e: React.PointerEvent) => {
    const d = drag.current
    if (!d || d.id !== e.pointerId) return
    drag.current = null
    setDragging(false)
    // 안 움직였으면 누름이다 — 이어 오는 click 이 42% ↔ 78% 를 바꾼다(키보드 Enter·Space 도 같은 길).
    if (!d.moved) return
    dragged.current = true
    // 마지막 움직임에서 오래 멈췄다 뗐으면 튕긴 것이 아니다.
    const v = e.timeStamp - d.lastT > 120 ? 0 : d.v
    setShare(settleShare(dragShare(d.startShare, e.clientY - d.startY, d.mapHeight), v))
  }
  const onCancel = (e: React.PointerEvent) => {
    const d = drag.current
    if (!d || d.id !== e.pointerId) return
    drag.current = null
    setDragging(false)
    setShare((s) => settleShare(s, 0))
  }
  const onHandleClick = () => {
    if (dragged.current) {
      dragged.current = false
      return
    }
    setShare((s) => toggleShare(s))
  }
  const tall = share > (DRAWER_LOW + DRAWER_HIGH) / 2

  return (
    <div style={{ position: 'absolute', inset: 0, zIndex: 16, pointerEvents: 'none' }}>
      {/* 위 — 검색어 알약(누르면 고쳐 쓰기) · ✕ 끝내기 */}
      <HStack spacing={4} style={{ position: 'absolute', top: 10, left: 10, right: 10, height: 46, padding: '0 6px 0 14px', borderRadius: 999, background: 'var(--system-background)', boxShadow: shadow.float, pointerEvents: 'auto' }}>
        <Icon name="magnifyingglass" size={15} color="secondary" />
        <Pressable plain onClick={onEdit} style={{ flex: 1, minWidth: 0, minHeight: 44, textAlign: 'left' }}>
          <Txt size={16} weight="semibold" lines={1}>{`“${query}”`} <Txt size={14} color="secondary">{shops ? say(`${total ?? shops.length}곳`, `${total ?? shops.length}`) : ''}</Txt></Txt>
        </Pressable>
        <Pressable plain onClick={onExit} aria-label={say('검색 끝내기', 'End search')} style={{ width: 40, height: 40, display: 'grid', placeItems: 'center' }}>
          <Icon name="xmark.circle.fill" size={20} color="tertiary" />
        </Pressable>
      </HStack>

      {/* 지도를 옮긴 뒤에만 — 이 지역에서 다시 찾기 */}
      {stale && shops && (
        <div style={{ position: 'absolute', top: 64, left: 0, right: 0, display: 'flex', justifyContent: 'center' }}>
          <Pressable plain onClick={() => { setArea(false); setAsk((n) => n + 1) }} style={{ pointerEvents: 'auto', minHeight: 44, display: 'grid', alignItems: 'center' }}>
            <Status><Icon name="arrow.clockwise" size={14} color="var(--home-purple)" /><Txt size={13} weight="semibold" style={{ color: 'var(--home-purple)' }}>{say('이 지역에서 다시 찾기', 'Search this area')}</Txt></Status>
          </Pressable>
        </div>
      )}

      {/* 아래 서랍 — 손잡이·머리를 끌어 올리고 내린다(떼면 42%·78% 에 붙는다). 누르면 크게/작게. */}
      <div
        ref={panel}
        className="proto-search-results"
        style={{
          position: 'absolute', left: 0, right: 0, bottom: 0, height: `${share * 100}%`, display: 'flex', flexDirection: 'column',
          background: 'var(--grouped-background)', borderRadius: '16px 16px 0 0', boxShadow: '0 -2px 16px rgba(0,0,0,0.15)', pointerEvents: 'auto',
          transition: dragging ? 'none' : 'height 0.28s cubic-bezier(.32,.72,0,1)',
        }}
      >
        <Pressable
          plain
          onPointerDown={onDown}
          onPointerMove={onMove}
          onPointerUp={onUp}
          onPointerCancel={onCancel}
          onClick={onHandleClick}
          aria-label={tall ? say('목록 줄이기', 'Shrink list') : say('목록 키우기', 'Expand list')}
          aria-expanded={tall}
          style={{ width: '100%', padding: '8px 0 10px', display: 'grid', placeItems: 'center', touchAction: 'none', cursor: dragging ? 'grabbing' : 'grab' }}
        >
          <span style={{ width: 40, height: 5, borderRadius: 3, background: 'var(--system-gray3)' }} />
        </Pressable>
        <div ref={list} onScroll={checkNearEnd} className="scroll-y" style={{ flex: 1, minHeight: 0, padding: '4px 16px 20px', overscrollBehavior: 'contain' }}>
          {failed ? (
            <Txt size={14} color="secondary" align="center" style={{ display: 'block', padding: 24 }}>{say('찾지 못했어요. 잠시 후 다시 해 주세요', 'Search failed. Try again shortly.')}</Txt>
          ) : shops === null ? (
            <VStack align="center" style={{ padding: 24 }}><Spinner size={18} /></VStack>
          ) : shops.length === 0 ? (
            <VStack spacing={10} align="center" style={{ padding: '24px 12px' }}>
              <Txt size={14} color="secondary" align="center">{area ? say('찾는 곳이 없어요', 'No places found') : say('이 지역엔 없어요', 'None in this area')}</Txt>
              {!area && (
                <Pressable plain onClick={() => setArea(true)} style={{ minHeight: 40, padding: '0 16px', borderRadius: 999, background: 'var(--system-background)' }}>
                  <Txt size={14} weight="semibold" style={{ color: 'var(--home-purple)' }}>{say('이 지역 밖에서도 찾기', 'Search everywhere')}</Txt>
                </Pressable>
              )}
            </VStack>
          ) : (
            <VStack spacing={0} align="stretch">
              <div style={{ borderRadius: 14, background: 'var(--system-background)', overflow: 'hidden' }}>
                {shops.map((p, i) => <div key={p.id} data-row><ShopRow place={p} first={i === 0} onPick={() => pick(p)} /></div>)}
              </div>
              {moreBusy && <VStack align="center" style={{ padding: 14 }}><Spinner size={16} /></VStack>}
              {moreFailed && (
                <Pressable plain onClick={() => { setMoreFailed(false); window.setTimeout(checkNearEnd, 0) }} style={{ minHeight: 44, marginTop: 8 }}>
                  <Txt size={14} weight="semibold" align="center" style={{ color: 'var(--home-purple)' }}>{say('더 불러오지 못했어요 · 다시 시도', "Couldn't load more · Try again")}</Txt>
                </Pressable>
              )}
            </VStack>
          )}
        </div>
      </div>
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
