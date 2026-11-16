/*
 * 코스 편집 지도의 검색(protoPoi.MapPoiBar) — 명세만 보고 쓴 시험.
 *
 * - 🔍 를 누르면 칩 줄이 검색칸이 된다. 2글자부터, 0.35초 멈추면 가게(`GET /pois`)만 찾는다 — 범위(bbox) 없이, 보이는 지도(서랍 위)
 *   가운데에서 가까운 순(`lat`·`lng`·`sort=distance`, §8·§10-2). 촬영지(`GET /places`)는 찾지 않는다 — 타자 중에도, 엔터 뒤에도.
 * - 목록 제목은 「가게·장소 · 가까운 순」, 하나도 없으면 「찾는 곳이 없어요」. 「이 지역엔 없어요」·「이 지역 밖에서도 찾기」 는 없다(§10-2).
 * - 지도 핀은 결과 앞 100곳까지(§8-2). 고른 곳으로 옮길 때 화면 너비 3배 안이면 0.6초 부드럽게, 밖이면 한 번에(§10-3).
 * - 갈래 칩에는 개수를 붙이지 않는다.
 * - 글자 치는 동안에는 추천 목록만 — 지도에는 아무것도 찍지 않는다. 목록 맨 위 「‘q’ 검색」 이나 키보드 엔터가 결과 서랍을 연다.
 * - 결과 서랍: 결과마다 갈래 아이콘 핀. 처음 쪽은 `limit=20`, 목록 끝 한 줄 안이 보이면 `offset=20` 부터 다음 쪽, `total`·빈 쪽에서 멈춤,
 *   같은 id 는 다시 넣지 않고, 지도는 처음 쪽에서만 맞춘다(docs/project/plans/motion-works-drawer-parity.md §7).
 * - 줄을 누르면 그곳이 서랍 위 보이는 영역의 가운데에 오게 지도를 옮긴 뒤 onPick 을 부른다.
 */
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { components } from '@/api/generated/schema'
import type { MapHandle, MapLayer, MarkerHandle, MarkerSpec } from '@/map'
import type { RoutePlace } from '@/features/route/poi/poiPlace'
import { chooseLanguage } from '@/i18n'
import { writeJSON } from '@/state/storage'
import { fromPoi, placeGroup, placeSymbol } from '@/features/route/poi/poiPlace'
import { poiDotHtml } from '@/features/route/poi/PoiDot'
import { POI_GROUPS, type PoiGroup } from '@/features/route/poi/poiGroup'
import { MapPoiBar } from './protoPoi'

type PoiSummary = components['schemas']['PoiSummary']

const h = vi.hoisted(() => ({ GET: vi.fn() }))

// 진짜 `ok` 처럼: 응답이 2xx 면 data, 아니면 던진다. 나머지 내보내기는 진짜를 쓴다.
vi.mock('@/api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/client')>()),
  api: { GET: (...args: unknown[]) => h.GET(...args) },
  ok: async (p: Promise<{ data?: unknown; response: { ok: boolean; status: number } }>) => {
    const r = await p
    if (r.response.ok) return r.data
    throw new Error(`status ${r.response.status}`)
  },
}))

const okRes = (data: unknown) => ({ data, response: { ok: true, status: 200, headers: new Headers() } })

const poi = (id: number, name: string, latitude: number, longitude: number): PoiSummary => ({
  id, name, category: '카페', categoryLabel: '카페', categoryGroup: 'food', address: '서울 종로구', latitude, longitude,
})

/** 검색어마다 돌려줄 가게. 촬영지(`/places`)는 부르면 안 되지만, 불렸을 때 화면이 깨지지 않게 촬영지 하나를 돌려준다. */
let shopsFor: (q: string) => PoiSummary[]
let placesItems: unknown[]
type Init = { params?: { query?: Record<string, unknown> } } | undefined
const callsTo = (path: string) => h.GET.mock.calls.filter((c) => c[0] === path) as Array<[string, Init]>

// ---- 지도 흉내 — 겹(layer)마다 살아 있는 점을 센다. 겹째 지우기·비우기·점 하나 지우기를 모두 따른다.
type FakeLayer = { removed: boolean; added: number; live: Set<MarkerSpec>; addMarker: (spec: MarkerSpec) => MarkerHandle; clear: () => void; remove: () => void }
let layers: FakeLayer[]

function makeMap() {
  layers = []
  const createLayer = vi.fn((): MapLayer => {
    const layer: FakeLayer = {
      removed: false,
      added: 0,
      live: new Set(),
      addMarker: vi.fn((spec: MarkerSpec): MarkerHandle => {
        layer.added += 1
        layer.live.add(spec)
        return { setPosition: vi.fn(), remove: vi.fn(() => { layer.live.delete(spec) }) }
      }),
      clear: vi.fn(() => { layer.live.clear() }),
      remove: vi.fn(() => { layer.removed = true; layer.live.clear() }),
    }
    layers.push(layer)
    return { addMarker: layer.addMarker, clear: layer.clear, remove: layer.remove, addPolyline: vi.fn(() => ({ remove: vi.fn() })), addPolygon: vi.fn(() => ({ remove: vi.fn() })) }
  })
  return {
    provider: 'naver', container: document.createElement('div'),
    getZoom: () => 12, getSize: () => ({ x: 400, y: 800 }), getCenter: () => ({ lat: 37.56, lng: 126.97 }),
    // 화면 왼쪽 위 (0,0) = (37.6, 126.95). 아래로 1px 에 위도 0.0001 씩 줄고, 오른쪽으로 1px 에 경도 0.0001 씩 는다.
    containerPointToLatLng: ([x, y]: [number, number]) => ({ lat: 37.6 - y * 0.0001, lng: 126.95 + x * 0.0001 }),
    setView: vi.fn(), fitBounds: vi.fn(), stop: vi.fn(), on: vi.fn(() => () => {}),
    createLayer, setBottomInset: vi.fn(), resize: vi.fn(), destroy: vi.fn(),
  } satisfies MapHandle
}

/** 지금 지도에 남아 있는 검색 점들. */
const livePins = (): MarkerSpec[] => layers.filter((l) => !l.removed).flatMap((l) => [...l.live])
/** 지금까지 찍은 적 있는 점 수(지운 것 포함). */
const addedPins = () => layers.reduce((n, l) => n + l.added, 0)
const pinAt = (p: PoiSummary) => livePins().find((s) => s.position.lat === p.latitude && s.position.lng === p.longitude)

let map: ReturnType<typeof makeMap>

function renderBar(over: { bottomInset?: number; pickedId?: string | null; groupsOn?: ReadonlySet<PoiGroup>; counts?: Partial<Record<PoiGroup, number>> } = {}) {
  const onPick = vi.fn<(place: RoutePlace) => void>()
  const ui = (pickedId: string | null | undefined) => (
    <MapPoiBar
      map={map}
      bottomInset={over.bottomInset ?? 400}
      groupsOn={over.groupsOn ?? new Set()}
      counts={over.counts ?? {}}
      loading={false}
      stale={false}
      tooWide={false}
      onToggle={vi.fn()}
      onAll={vi.fn()}
      onRefresh={vi.fn()}
      onPick={onPick}
      pickedId={pickedId}
    />
  )
  const view = render(ui(over.pickedId))
  return { ...view, onPick, rerenderPicked: (id: string | null) => view.rerender(ui(id)) }
}

async function advance(ms: number) {
  await act(async () => { await vi.advanceTimersByTimeAsync(ms) })
}

const openSearch = () => fireEvent.click(screen.getByLabelText(/^(장소 검색|Search places)$/))
const input = () => screen.getByRole('textbox') as HTMLInputElement
const type = (text: string) => fireEvent.change(input(), { target: { value: text } })

/** 검색 패널(칩 줄 자리) 안의 글자 전부 — 입력칸의 안내 글(placeholder)도 같이. */
function panelText(container: HTMLElement): string {
  const placeholders = [...container.querySelectorAll('input')].map((i) => i.placeholder).join(' ')
  return `${container.textContent ?? ''} ${placeholders}`
}

const CAFE = poi(7, '경복궁 카페', 37.579, 126.977)
const BAKERY = poi(8, '경복궁 빵집', 37.575, 126.975)
const NOODLE = poi(9, '광화문 국수', 37.571, 126.976)

beforeEach(() => {
  chooseLanguage('ko')
  vi.useFakeTimers()
  // 처음 보는 사람 안내(지도 검색)는 이미 본 것으로 — 시험 화면을 덮지 않게.
  writeJSON('proto.coach.seen', ['map-search'])
  map = makeMap()
  shopsFor = (q) => (q.includes('광화문') ? [NOODLE] : [CAFE, BAKERY])
  placesItems = [{ id: 45, name: '경복궁 촬영 장소', latitude: 37.58, longitude: 126.97, contents: [{ contentId: 1, title: '드라마' }] }]
  h.GET.mockImplementation((path: string, init: Init) => {
    const q = String(init?.params?.query?.q ?? '')
    if (path === '/pois') return Promise.resolve(okRes({ items: shopsFor(q), total: shopsFor(q).length }))
    if (path === '/places') {
      return Promise.resolve(okRes({ items: placesItems, total: placesItems.length }))
    }
    return Promise.resolve(okRes({ items: [] }))
  })
})

afterEach(() => {
  cleanup()
  vi.useRealTimers()
  vi.clearAllMocks()
  localStorage.clear()
})

describe('MapPoiBar — 타자 중 검색(가게만)', () => {
  it('2글자 미만이면 묻지 않고, 2글자부터 0.35초 멈추면 범위 없이 보이는 지도(서랍 위) 가운데에서 가까운 순으로 /pois 만 묻는다', async () => {
    renderBar({ bottomInset: 400 })
    openSearch()
    type('경')
    await advance(1_000)
    // (다른 모듈이 불러올 때 묻는 것 — 예: 즐겨찾기 — 은 상관하지 않는다. 검색 창구만 본다.)
    expect(callsTo('/pois')).toHaveLength(0)
    expect(callsTo('/places')).toHaveLength(0)

    type('경복')
    await advance(349)
    expect(callsTo('/pois')).toHaveLength(0)
    await advance(1)
    expect(callsTo('/pois')).toHaveLength(1)

    const query = callsTo('/pois')[0][1]!.params!.query!
    expect(query.q).toBe('경복')
    expect(typeof query.limit).toBe('number')
    // 400×800 지도의 아래 400px 은 서랍이 덮는다 — 보이는 곳은 (0,0)~(400,400), 그 가운데 (200,200) = (37.58, 126.97).
    expect(query.bbox).toBeUndefined()
    expect(query.sort).toBe('distance')
    expect(Number(query.lat)).toBeCloseTo(37.58, 6)
    expect(Number(query.lng)).toBeCloseTo(126.97, 6)

    // 촬영지는 여기서 찾지 않는다.
    expect(callsTo('/places')).toHaveLength(0)
  })

  it('목록은 가게 줄만 보이고, 어디에도 촬영지 이야기가 없다', async () => {
    const { container } = renderBar()
    openSearch()
    type('경복궁')
    await advance(400)

    expect(screen.getByText('경복궁 카페')).toBeTruthy()
    expect(screen.getByText('경복궁 빵집')).toBeTruthy()
    expect(screen.queryByText('경복궁 촬영 장소')).toBeNull()
    expect(panelText(container)).not.toMatch(/촬영지|filming|spot/i)
    expect(callsTo('/places')).toHaveLength(0)
  })

  it('영어 화면에서도 입력칸 안내 글과 목록에 촬영지(Filming·Spot) 이야기가 없다', async () => {
    chooseLanguage('en')
    const { container } = renderBar()
    openSearch()
    expect(input().placeholder).not.toMatch(/촬영지|spot/i)
    type('Gyeongbok')
    await advance(400)
    expect(screen.getByText('경복궁 카페')).toBeTruthy()
    expect(panelText(container)).not.toMatch(/촬영지|filming|spot/i)
    expect(callsTo('/places')).toHaveLength(0)
  })

  it('한국어 입력칸 안내 글에도 촬영지가 없다', () => {
    renderBar()
    openSearch()
    expect(input().placeholder).not.toMatch(/촬영지|spot/i)
  })

  it('목록 제목은 「가게·장소 · 가까운 순」 이고, 「이 지역」 문구·「이 지역 밖에서도 찾기」 단추가 없다', async () => {
    const { container } = renderBar()
    openSearch()
    type('경복궁')
    await advance(400)

    expect(screen.getByText('경복궁 카페')).toBeTruthy()
    expect(screen.getByText('가게·장소 · 가까운 순')).toBeTruthy()
    expect(screen.queryByText('이 지역 밖에서도 찾기')).toBeNull()
    expect(panelText(container)).not.toMatch(/이 지역/)
    // 한 번만 묻는다 — 범위를 넓혀 다시 묻는 길이 없다.
    expect(callsTo('/pois')).toHaveLength(1)
  })

  it('하나도 없으면 「찾는 곳이 없어요」 — 「이 지역엔 없어요」 가 아니다', async () => {
    shopsFor = () => []
    const { container } = renderBar()
    openSearch()
    type('없는가게')
    await advance(400)

    expect(screen.getByText('가게·장소 · 가까운 순')).toBeTruthy()
    expect(screen.getByText('찾는 곳이 없어요')).toBeTruthy()
    expect(screen.queryByText('이 지역엔 없어요')).toBeNull()
    expect(screen.queryByText('이 지역 밖에서도 찾기')).toBeNull()
    expect(panelText(container)).not.toMatch(/이 지역/)
  })

  it('영어 화면 제목은 「Shops & places · nearest first」 쪽 — 「this area」·「everywhere」 가 없다', async () => {
    chooseLanguage('en')
    const { container } = renderBar()
    openSearch()
    type('Gyeongbok')
    await advance(400)
    expect(screen.getByText('경복궁 카페')).toBeTruthy()
    expect(panelText(container)).not.toMatch(/this area|everywhere/i)
  })
})

describe('MapPoiBar — 글자 치는 동안(추천 목록만)', () => {
  it('목록은 뜨지만 지도에는 아무것도 찍지 않는다', async () => {
    renderBar()
    openSearch()
    type('경복궁')
    await advance(400)

    expect(screen.getByText('경복궁 카페')).toBeTruthy()
    expect(addedPins()).toBe(0)
    expect(livePins()).toHaveLength(0)

    // 검색어를 바꿔 결과가 달라져도 그대로.
    type('광화문')
    await advance(400)
    expect(screen.getByText('광화문 국수')).toBeTruthy()
    expect(addedPins()).toBe(0)
    expect(map.fitBounds).not.toHaveBeenCalled()
  })

  it('목록 맨 위 줄은 「‘q’ 검색」 이고, 누르면 결과 서랍이 열린다(목록이 닫히고 지도에 결과 핀)', async () => {
    renderBar()
    openSearch()
    type('경복궁')
    await advance(400)
    expect(addedPins()).toBe(0)

    fireEvent.click(screen.getByText('‘경복궁’ 검색'))
    await advance(1_000)

    expect(screen.queryByRole('textbox')).toBeNull()
    expect(screen.getByText('경복궁 카페')).toBeTruthy()
    expect(pinAt(CAFE)).toBeTruthy()
    expect(pinAt(BAKERY)).toBeTruthy()
  })

  it('영어 화면에서는 맨 위 줄이 「Search “q”」 이고 누르면 결과 서랍이 열린다', async () => {
    chooseLanguage('en')
    renderBar()
    openSearch()
    type('Gyeongbok')
    await advance(400)

    fireEvent.click(screen.getByText('Search “Gyeongbok”'))
    await advance(1_000)

    expect(screen.queryByRole('textbox')).toBeNull()
    expect(livePins()).toHaveLength(2)
  })

  it('키보드 엔터도 결과 서랍을 연다', async () => {
    renderBar()
    openSearch()
    type('경복궁')
    await advance(400)
    fireEvent.keyDown(input(), { key: 'Enter' })
    await advance(1_000)

    expect(screen.queryByRole('textbox')).toBeNull()
    expect(livePins()).toHaveLength(2)
  })
})

describe('MapPoiBar — 결과 서랍의 지도 핀', () => {
  it('결과마다 갈래 아이콘 핀(기본 지도 편의시설 아이콘과 같은 모양)을 하나씩 찍는다', async () => {
    await openDrawer('경복궁')

    expect(livePins()).toHaveLength(2)
    for (const p of [CAFE, BAKERY]) {
      const spec = pinAt(p)
      expect(spec, `${p.name} 핀`).toBeTruthy()
      const place = fromPoi(p)
      expect(spec!.html).toBe(poiDotHtml(placeGroup(place), placeSymbol(place)))
    }
  })

  it('정보 카드가 열린 곳(pickedId)에는 결과 핀을 찍지 않는다', async () => {
    renderBar({ pickedId: `poi-${CAFE.id}` })
    openSearch()
    type('경복궁')
    await advance(400)
    fireEvent.keyDown(input(), { key: 'Enter' })
    await advance(1_000)

    expect(pinAt(CAFE)).toBeUndefined()
    expect(pinAt(BAKERY)).toBeTruthy()
    expect(livePins()).toHaveLength(1)
  })

  it('검색을 끝내면 결과 핀이 남지 않는다', async () => {
    await openDrawer('경복궁')
    expect(livePins()).toHaveLength(2)

    fireEvent.click(screen.getByLabelText('검색 끝내기'))
    await advance(500)
    expect(livePins()).toHaveLength(0)
  })
})

// ---- 「더 받기」 — 서버 흉내는 offset·limit 대로 잘라 주고, 서랍 목록의 줄 위치는 글자로 찾아 흉내 낸다.

const ROW_H = 56
const label = (i: number) => `가게 ${String(i).padStart(3, '0')}`
const shop = (i: number) => poi(1_000 + i, label(i), 37.57 + i * 0.0001, 126.97 + i * 0.0001)
/** 지금 서랍 목록 칸에 보이는 높이(px)와 스크롤한 거리. */
let listHeight = 0
let listScroll = 0

/** 글자로 줄을 찾는다 — 가게 이름 하나만 든 요소는 그 가게의 줄(위에서 i 번째), 여럿 든 요소는 목록 칸(0~listHeight). */
function stubListGeometry() {
  const rect = (top: number, bottom: number) => ({ top, bottom, left: 0, right: 400, width: 400, height: bottom - top, x: 0, y: top, toJSON: () => ({}) }) as DOMRect
  vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function (this: HTMLElement) {
    const names = (this.textContent ?? '').match(/가게 \d{3}/g) ?? []
    const unique = new Set(names)
    if (unique.size === 1) {
      // 목록의 몇 번째 줄인가 — 화면에 그려진 가게 줄 순서.
      const all = [...new Set((document.body.textContent ?? '').match(/가게 \d{3}/g) ?? [])]
      const i = all.indexOf(names[0] ?? "")
      const top = i * ROW_H - listScroll
      return rect(top, top + ROW_H)
    }
    if (unique.size > 1) return rect(0, listHeight)
    return rect(0, 0)
  })
}

/** 목록을 굴렸다 — 줄의 조상 모두에 scroll 을 보낸다(어느 칸이 듣든). */
async function scrollListTo(rows: number) {
  listScroll = rows * ROW_H
  let el: HTMLElement | null = screen.getByText(label(0))
  while (el) {
    fireEvent.scroll(el)
    el = el.parentElement
  }
  await advance(500)
}

const drawerCalls = (from: number) => callsTo('/pois').slice(from).map((c) => c[1]!.params!.query!)

async function openDrawer(q: string) {
  renderBar()
  openSearch()
  type(q)
  await advance(400)
  const before = callsTo('/pois').length
  fireEvent.keyDown(input(), { key: 'Enter' })
  await advance(1_000)
  return before
}

/** /pois 를 `items(offset, limit)` 와 `total` 로 흉내 낸다. */
function servePages(total: number, items: (offset: number, limit: number) => PoiSummary[]) {
  h.GET.mockImplementation((path: string, init: Init) => {
    if (path !== '/pois') return Promise.resolve(okRes({ items: [] }))
    const query = init?.params?.query ?? {}
    const offset = Number(query.offset ?? 0)
    const limit = Number(query.limit ?? 20)
    return Promise.resolve(okRes({ items: items(offset, limit), total }))
  })
}

describe('MapPoiBar — 결과 서랍 더 받기', () => {
  beforeEach(() => {
    listHeight = 3 * ROW_H
    listScroll = 0
    stubListGeometry()
  })
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('처음 쪽은 limit 20·offset 없이 묻고, 받은 20곳을 지도에 찍고 지도를 한 번 맞춘다', async () => {
    const all = Array.from({ length: 45 }, (_, i) => shop(i))
    servePages(45, (o, l) => all.slice(o, o + l))
    const from = await openDrawer('가게')

    const calls = drawerCalls(from)
    expect(calls).toHaveLength(1)
    expect(calls[0].q).toBe('가게')
    expect(calls[0].limit).toBe(20)
    expect(calls[0].offset).toBeUndefined()
    // 범위 없이 화면 가운데에서 가까운 순.
    expect(calls[0].bbox).toBeUndefined()
    expect(calls[0].sort).toBe('distance')
    expect(typeof calls[0].lat).toBe('number')
    expect(typeof calls[0].lng).toBe('number')
    expect(livePins()).toHaveLength(20)
    expect(map.fitBounds).toHaveBeenCalledTimes(1)
  })

  it('끝 한 줄 안이 보이기 전에는 더 받지 않고, 보이면 offset 20 으로 다음 20곳 — 핀도 늘고 지도는 다시 맞추지 않는다', async () => {
    const all = Array.from({ length: 45 }, (_, i) => shop(i))
    servePages(45, (o, l) => all.slice(o, o + l))
    const from = await openDrawer('가게')
    expect(drawerCalls(from)).toHaveLength(1)

    // 17번째 줄(인덱스 16)까지만 보인다 — 아직 끝 한 줄 안이 아니다.
    await scrollListTo(14)
    expect(drawerCalls(from)).toHaveLength(1)

    // 끝 줄까지 보인다.
    await scrollListTo(17)
    const calls = drawerCalls(from)
    expect(calls).toHaveLength(2)
    expect(calls[1].offset).toBe(20)
    expect(calls[1].limit).toBe(20)
    expect(calls[1].q).toBe('가게')
    // 다음 쪽도 처음 쪽과 같은 기준점·가까운 순, 범위 없이.
    expect(calls[1].bbox).toBeUndefined()
    expect(calls[1].sort).toBe('distance')
    expect(calls[1].lat).toBe(calls[0].lat)
    expect(calls[1].lng).toBe(calls[0].lng)

    expect(screen.getByText(label(39))).toBeTruthy()
    expect(livePins()).toHaveLength(40)
    expect(map.fitBounds).toHaveBeenCalledTimes(1)
  })

  it('total 에 닿으면 더 묻지 않는다', async () => {
    const all = Array.from({ length: 45 }, (_, i) => shop(i))
    servePages(45, (o, l) => all.slice(o, o + l))
    // 목록 칸이 아주 커서 받은 줄이 늘 다 보인다 — 끝이 보일 때마다 이어 받는다.
    listHeight = 1_000_000
    const from = await openDrawer('가게')
    await scrollListTo(0)
    await advance(1_000)

    const offsets = drawerCalls(from).map((q) => q.offset)
    expect(offsets).toEqual([undefined, 20, 40])
    expect(livePins()).toHaveLength(45)

    await scrollListTo(44)
    await advance(1_000)
    expect(drawerCalls(from)).toHaveLength(3)
    expect(map.fitBounds).toHaveBeenCalledTimes(1)
  })

  it('빈 쪽이 오면 total 이 남아 있어도 멈춘다', async () => {
    const all = Array.from({ length: 20 }, (_, i) => shop(i))
    servePages(100, (o, l) => all.slice(o, o + l))
    listHeight = 1_000_000
    const from = await openDrawer('가게')
    await scrollListTo(0)
    await advance(1_000)
    await scrollListTo(19)
    await advance(1_000)

    expect(drawerCalls(from).map((q) => q.offset)).toEqual([undefined, 20])
    expect(livePins()).toHaveLength(20)
  })

  it('이미 받은 가게(같은 id)는 다시 넣지 않는다 — 목록에도 지도에도 한 번씩', async () => {
    const all = Array.from({ length: 40 }, (_, i) => shop(i))
    // 두 번째 쪽이 앞 쪽 끝 두 곳(18·19)과 겹쳐 온다.
    servePages(40, (o, l) => (o === 0 ? all.slice(0, l) : all.slice(o - 2, o - 2 + l)))
    const from = await openDrawer('가게')
    await scrollListTo(17)
    expect(drawerCalls(from)).toHaveLength(2)

    expect(screen.getAllByText(label(18))).toHaveLength(1)
    expect(screen.getAllByText(label(19))).toHaveLength(1)
    expect(screen.getByText(label(37))).toBeTruthy()
    expect(livePins()).toHaveLength(38)
    expect(new Set(livePins().map((s) => `${s.position.lat},${s.position.lng}`)).size).toBe(38)
  })
})

describe('MapPoiBar — 결과 서랍의 핀 상한(앞 100곳)', () => {
  beforeEach(() => {
    listScroll = 0
    stubListGeometry()
  })
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('목록은 120곳까지 늘어도 지도 핀은 앞 100곳만', async () => {
    const all = Array.from({ length: 120 }, (_, i) => shop(i))
    servePages(120, (o, l) => all.slice(o, o + l))
    // 목록 칸이 아주 커서 받은 줄이 늘 다 보인다 — 끝이 보일 때마다 이어 받는다.
    listHeight = 1_000_000
    const from = await openDrawer('가게')
    await scrollListTo(0)
    await advance(2_000)
    await scrollListTo(0)
    await advance(2_000)

    expect(drawerCalls(from).map((q) => q.offset)).toEqual([undefined, 20, 40, 60, 80, 100])
    // 목록에는 120곳 모두.
    expect(screen.getByText(label(119))).toBeTruthy()
    // 지도에는 앞 100곳 — 101번째(인덱스 100) 이후는 핀이 없다.
    expect(livePins()).toHaveLength(100)
    expect(pinAt(shop(99))).toBeTruthy()
    expect(pinAt(shop(100))).toBeUndefined()
    expect(pinAt(shop(119))).toBeUndefined()
  })
})

describe('MapPoiBar — 결과 서랍이 비었을 때', () => {
  it('「찾는 곳이 없어요」 만 — 「이 지역엔 없어요」·「이 지역 밖에서도 찾기」 가 없다', async () => {
    shopsFor = () => []
    const from = await openDrawer('없는가게')

    expect(screen.getByText('찾는 곳이 없어요')).toBeTruthy()
    expect(screen.queryByText('이 지역엔 없어요')).toBeNull()
    expect(screen.queryByText('이 지역 밖에서도 찾기')).toBeNull()
    // 범위를 넓혀 다시 묻지 않는다.
    expect(callsTo('/pois').slice(from)).toHaveLength(1)
  })
})

describe('MapPoiBar — 갈래 칩', () => {
  it('켠 칩에도 개수를 붙이지 않는다', () => {
    const counts = Object.fromEntries(POI_GROUPS.map((g, i) => [g, 900 + i])) as Partial<Record<PoiGroup, number>>
    const { container } = renderBar({ groupsOn: new Set(POI_GROUPS), counts })
    for (const g of POI_GROUPS) expect(container.textContent ?? '').not.toContain(String(counts[g]))
  })
})

describe('MapPoiBar — 줄을 누르면', () => {
  it('서랍 위 보이는 영역의 가운데로 확대해 옮긴 뒤 onPick 을 부른다', async () => {
    const { onPick } = renderBar({ bottomInset: 400 })
    openSearch()
    type('경복궁')
    await advance(400)

    fireEvent.click(screen.getByText('경복궁 카페'))
    await advance(0)

    expect(map.setView).toHaveBeenCalled()
    const [center, zoom, options] = map.setView.mock.calls.at(-1)!
    expect(center).toEqual({ lat: CAFE.latitude, lng: CAFE.longitude })
    expect(zoom).toBeGreaterThanOrEqual(16)
    expect(options?.shiftY).toBe(200)
    // 화면 가운데(37.56, 126.97)에서 약 2km — 화면 너비(약 3.5km)의 3배 안이므로 0.6초 동안 부드럽게.
    expect(options?.animate).toBe(true)
    expect(options?.seconds).toBeCloseTo(0.6, 6)

    expect(onPick).toHaveBeenCalledTimes(1)
    expect(onPick).toHaveBeenCalledWith(expect.objectContaining({ id: `poi-${CAFE.id}`, latitude: CAFE.latitude, longitude: CAFE.longitude }))
    // 지도를 먼저 옮기고 나서 알린다.
    expect(map.setView.mock.invocationCallOrder.at(-1)!).toBeLessThan(onPick.mock.invocationCallOrder[0])
  })
})

describe('MapPoiBar — 고른 곳이 아주 멀면', () => {
  it('화면 너비 3배를 넘으면(서울 → 부산) 움직임 없이 한 번에 옮긴다', async () => {
    const FAR = poi(70, '부산 국밥', 35.1, 129.04)
    shopsFor = () => [FAR]
    const { onPick } = renderBar({ bottomInset: 400 })
    openSearch()
    type('부산국밥')
    await advance(400)

    fireEvent.click(screen.getByText('부산 국밥'))
    await advance(0)

    const [center, , options] = map.setView.mock.calls.at(-1)!
    expect(center).toEqual({ lat: FAR.latitude, lng: FAR.longitude })
    expect(options?.animate).toBe(false)
    expect(onPick).toHaveBeenCalledTimes(1)
  })
})

describe('MapPoiBar — 엔터 뒤 결과', () => {
  it('엔터 뒤에도 가게만 보이고 촬영지 탭·이름표가 없으며 /places 를 묻지 않는다', async () => {
    // 부르면 안 되는 /places 는 빈 목록 — 불렸을 때 화면이 깨지는 대신 아래 단언이 이유를 말하게.
    placesItems = []
    const { container } = renderBar()
    openSearch()
    type('경복궁')
    await advance(400)
    fireEvent.keyDown(input(), { key: 'Enter' })
    await advance(1_000)

    expect(screen.getByText('경복궁 카페')).toBeTruthy()
    expect(screen.queryByText('경복궁 촬영 장소')).toBeNull()
    expect(panelText(container)).not.toMatch(/촬영지|filming|spot/i)
    expect(callsTo('/places')).toHaveLength(0)
  })
})
