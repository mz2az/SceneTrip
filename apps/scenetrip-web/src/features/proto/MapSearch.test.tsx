/*
 * 코스 편집 지도의 검색(protoPoi.MapPoiBar) — 명세만 보고 쓴 시험.
 *
 * - 🔍 를 누르면 칩 줄이 검색칸이 된다. 2글자부터, 0.35초 멈추면 지금 보이는 지도 범위(서랍 위)에서 가게(`GET /pois`)만 찾는다.
 *   촬영지(`GET /places`)는 여기서 더 찾지 않는다 — 타자 중에도, 엔터 뒤에도.
 * - 목록이 떠 있는 동안 결과를 지도에도 가운데가 맞는 작은 점으로 찍는다. 검색어를 지우거나 닫으면 점도 지운다.
 * - 줄을 누르면 그곳이 서랍 위 보이는 영역의 가운데에 오게 지도를 옮긴 뒤 onPick 을 부른다.
 */
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { components } from '@/api/generated/schema'
import type { MapHandle, MapLayer, MarkerHandle, MarkerSpec } from '@/map'
import type { RoutePlace } from '@/features/route/poi/poiPlace'
import { chooseLanguage } from '@/i18n'
import { writeJSON } from '@/state/storage'
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
type FakeLayer = { removed: boolean; live: Set<MarkerSpec>; addMarker: (spec: MarkerSpec) => MarkerHandle; clear: () => void; remove: () => void }
let layers: FakeLayer[]

function makeMap() {
  layers = []
  const createLayer = vi.fn((): MapLayer => {
    const layer: FakeLayer = {
      removed: false,
      live: new Set(),
      addMarker: vi.fn((spec: MarkerSpec): MarkerHandle => {
        layer.live.add(spec)
        return { setPosition: vi.fn(), remove: vi.fn(() => { layer.live.delete(spec) }) }
      }),
      clear: vi.fn(() => { layer.live.clear() }),
      remove: vi.fn(() => { layer.removed = true; layer.live.clear() }),
    }
    layers.push(layer)
    return { addMarker: layer.addMarker, clear: layer.clear, remove: layer.remove, addPolyline: vi.fn(() => ({ remove: vi.fn() })) }
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
const pinAt = (p: PoiSummary) => livePins().find((s) => s.position.lat === p.latitude && s.position.lng === p.longitude)

let map: ReturnType<typeof makeMap>

function renderBar(over: { bottomInset?: number; pickedId?: string | null } = {}) {
  const onPick = vi.fn<(place: RoutePlace) => void>()
  const ui = (pickedId: string | null | undefined) => (
    <MapPoiBar
      map={map}
      bottomInset={over.bottomInset ?? 400}
      groupsOn={new Set()}
      counts={{}}
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
  it('2글자 미만이면 묻지 않고, 2글자부터 0.35초 멈추면 지금 보이는 범위(서랍 위)로 /pois 만 묻는다', async () => {
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
    // 400×800 지도의 아래 400px 은 서랍이 덮는다 — 보이는 곳은 (0,0)~(400,400).
    const [west, south, east, north] = String(query.bbox).split(',').map(Number)
    expect(west).toBeCloseTo(126.95, 6)
    expect(south).toBeCloseTo(37.56, 6)
    expect(east).toBeCloseTo(126.99, 6)
    expect(north).toBeCloseTo(37.6, 6)

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

  it('「이 지역 밖에서도 찾기」 는 범위 없이 /pois 를 다시 묻는다', async () => {
    renderBar()
    openSearch()
    type('경복궁')
    await advance(400)
    expect(callsTo('/pois')).toHaveLength(1)

    fireEvent.click(screen.getByText('이 지역 밖에서도 찾기'))
    await advance(400)
    const last = callsTo('/pois').at(-1)![1]!.params!.query!
    expect(callsTo('/pois').length).toBeGreaterThanOrEqual(2)
    expect(last.q).toBe('경복궁')
    expect(last.bbox).toBeUndefined()
    expect(callsTo('/places')).toHaveLength(0)
  })
})

describe('MapPoiBar — 목록이 떠 있는 동안 지도 위 검색 점', () => {
  it('결과마다 가운데가 맞는 점을 하나씩 찍는다', async () => {
    renderBar()
    openSearch()
    type('경복궁')
    await advance(400)

    expect(map.createLayer).toHaveBeenCalled()
    const pins = livePins()
    expect(pins).toHaveLength(2)
    for (const p of [CAFE, BAKERY]) {
      const spec = pinAt(p)
      expect(spec, `${p.name} 점`).toBeTruthy()
      expect(spec!.anchor).toEqual([spec!.size[0] / 2, spec!.size[1] / 2])
    }
  })

  it('새 검색어의 결과가 다르면 점을 갈아 끼운다', async () => {
    renderBar()
    openSearch()
    type('경복궁')
    await advance(400)
    expect(livePins()).toHaveLength(2)

    type('광화문')
    await advance(400)
    const pins = livePins()
    expect(pins).toHaveLength(1)
    expect(pins[0].position).toEqual({ lat: NOODLE.latitude, lng: NOODLE.longitude })
  })

  it('검색어를 2글자 밑으로 지우면 점이 남지 않는다', async () => {
    renderBar()
    openSearch()
    type('경복궁')
    await advance(400)
    expect(livePins()).toHaveLength(2)

    type('경')
    await advance(400)
    expect(livePins()).toHaveLength(0)
  })

  it('← 로 검색을 닫으면 점이 남지 않는다', async () => {
    renderBar()
    openSearch()
    type('경복궁')
    await advance(400)
    expect(livePins()).toHaveLength(2)

    fireEvent.click(screen.getByLabelText('검색 닫기'))
    await advance(500)
    expect(livePins()).toHaveLength(0)
  })

  it('Esc 로 검색을 닫아도 점이 남지 않는다', async () => {
    renderBar()
    openSearch()
    type('경복궁')
    await advance(400)
    expect(livePins()).toHaveLength(2)

    fireEvent.keyDown(input(), { key: 'Escape' })
    await advance(500)
    expect(livePins()).toHaveLength(0)
  })

  it('정보 카드가 열린 곳(pickedId)에는 검색 점을 찍지 않는다', async () => {
    renderBar({ pickedId: `poi-${CAFE.id}` })
    openSearch()
    type('경복궁')
    await advance(400)

    expect(pinAt(CAFE)).toBeUndefined()
    expect(pinAt(BAKERY)).toBeTruthy()
    expect(livePins()).toHaveLength(1)
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

    expect(onPick).toHaveBeenCalledTimes(1)
    expect(onPick).toHaveBeenCalledWith(expect.objectContaining({ id: `poi-${CAFE.id}`, latitude: CAFE.latitude, longitude: CAFE.longitude }))
    // 지도를 먼저 옮기고 나서 알린다.
    expect(map.setView.mock.invocationCallOrder.at(-1)!).toBeLessThan(onPick.mock.invocationCallOrder[0])
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
