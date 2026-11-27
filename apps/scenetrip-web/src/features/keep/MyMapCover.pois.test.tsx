/*
 * 마이맵 `/my-map` — 담은 편의시설과 주변 칩. 정본: docs/project/plans/keep-poi.md §3-3 · §3-7 · §7.
 *
 *   머리줄·아래 단추의 N = 촬영지 + 편의시설(myMapCount). 편의시설뿐이어도 빈 화면이 아니다.
 *   목록 맨 아래 묶음 「주변에서 담은 곳」/Saved nearby — 줄은 SavedPoiRow(이름·분류·주소, 폐업 「문을 닫았어요」).
 *   주변 칩(NearbyChips)을 켜면 GET /pois { bbox: nearbyBbox(담은 곳), categoryGroup, categories?, sort: 'spread', spreadZoom, limit: 60 }
 *   과 my_map_nearby(kind). keepStore.nearby 가 미리 켜져 있으면 그 칩이 켜진 채로 뜬다.
 *   11/15 — 담은 곳이 있고 nearby 가 null 이면 열 때 「식당·카페」 를 저절로 켠다(이벤트 없음). 사람이 끄면 덮개가 열려 있는 동안 다시 켜지 않는다.
 *   0건 「이 둘레에는 없어요. 지도를 옮겨 보세요」, 실패 「불러오지 못했어요」.
 *
 * 지도 부품은 MyMapCover.test.tsx 처럼 빈 부품으로 바꾼다 — 그래서 지도 위 점을 눌러 카드를 띄우는 것은 여기서 보지 않는다.
 */
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const h = vi.hoisted(() => ({ GET: vi.fn() }))

vi.mock('@/api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/client')>()),
  api: { GET: (...args: unknown[]) => h.GET(...args), POST: vi.fn(), DELETE: vi.fn() },
}))

vi.mock('@/analytics', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/analytics')>()
  return { ...actual, track: vi.fn() }
})

vi.mock('@/features/search/map/MapView', () => ({ MapView: () => null }))

import { track, webEvent } from '@/analytics'
import type { components } from '@/api/generated/schema'
import { paths } from '@/app/routes'
import { pendingStore } from '@/app/tabRouter'
import { chooseLanguage } from '@/i18n'
import { type CartItem, type CartPoi, cartActions, cartStore } from '@/state/cart'
import { nearbyBbox } from './keepPoiRules'
import { keepActions, keepStore } from './keepStore'
import { MyMapCover } from './MyMapCover'

type PoiSummary = components['schemas']['PoiSummary']

const respond = (status: number, data?: unknown) => ({
  data,
  error: status >= 400 ? { code: 'INTERNAL', message: 'x' } : undefined,
  response: { ok: status >= 200 && status < 300, status, headers: new Headers() },
})

let seq = 0
const item = (p: Partial<CartItem>): CartItem => ({
  placeId: ++seq + 1000,
  name: `장소${seq}`,
  addedAt: '2026-11-14T00:00:00Z',
  ...p,
})

const savedPoi = (id: number, p: Partial<CartPoi> = {}): CartPoi => ({
  id,
  name: `편의시설${id}`,
  category: '카페',
  categoryLabel: '카페',
  categoryGroup: 'food',
  latitude: 37.551,
  longitude: 126.991,
  addedAt: '2026-11-14T00:00:00Z',
  closed: false,
  ...p,
})

const ITEMS: CartItem[] = [
  item({ name: '남산서울타워', sourceContentId: 32, sourceContentTitle: '케이팝 데몬 헌터스', latitude: 37.55, longitude: 126.99 }),
  item({ name: '덕수궁 돌담길', sourceContentId: 7, sourceContentTitle: '도깨비', latitude: 37.56, longitude: 126.97 }),
  item({ name: '출처 없는 카페', sourceContentId: null, latitude: 37.5, longitude: 127.02 }),
]

const POIS: CartPoi[] = [
  savedPoi(501, { name: '남산 돈까스', category: '양식', categoryLabel: '양식', address: '서울 중구 소파로' }),
  savedPoi(502, { name: '명동 게스트하우스', category: '게스트하우스', categoryLabel: '게스트하우스', categoryGroup: 'stay', address: '서울 중구 명동' }),
]

/** GET /cart 가 돌려줄 것. */
let cartReply: { items: CartItem[]; pois: CartPoi[] }
/** GET /pois 가 돌려줄 것 — 'fail' 이면 500. */
let poisReply: PoiSummary[] | 'fail'

function renderCover() {
  return render(
    <MemoryRouter initialEntries={[paths.myMap]}>
      <MyMapCover onClose={vi.fn()} />
    </MemoryRouter>,
  )
}

function withCart(items: CartItem[], pois: CartPoi[]) {
  cartReply = { items, pois }
  cartStore.set({
    loadState: 'loaded',
    placeIds: new Set(items.map((i) => i.placeId)),
    items,
    pois,
    poiIds: new Set(pois.map((p) => p.id)),
    toast: null,
  })
}

const events = () => vi.mocked(track).mock.calls.map(([e]) => e)
const bodyText = () => document.body.textContent ?? ''
const poiCalls = () => h.GET.mock.calls.filter((c) => c[0] === '/pois')
const lastPoiQuery = () => (poiCalls().at(-1)![1] as { params: { query: Record<string, unknown> } }).params.query

function chip(text: string): HTMLElement {
  const button = screen.getByText(text).closest('button')
  expect(button, `「${text}」 칩`).not.toBeNull()
  return button as HTMLElement
}

const ambient = (id: number, name: string): PoiSummary => ({
  id,
  name,
  category: '한식',
  categoryLabel: '한식',
  categoryGroup: 'food',
  latitude: 37.552,
  longitude: 126.992,
})

beforeEach(() => {
  cartStore.set({ loadState: 'notLoaded', placeIds: new Set(), items: [], pois: [], poiIds: new Set(), toast: null })
  chooseLanguage('ko')
  h.GET.mockReset()
  poisReply = [ambient(901, '을지로 국밥')]
  h.GET.mockImplementation(async (path: string) => {
    if (path === '/cart') {
      return respond(200, { items: cartReply.items, totalCount: cartReply.items.length, pois: cartReply.pois, poiCount: cartReply.pois.length })
    }
    if (path === '/pois') {
      if (poisReply === 'fail') return respond(500)
      return respond(200, { items: poisReply, total: poisReply.length, limit: 60, offset: 0 })
    }
    return respond(200, { items: [], total: 0, limit: 20, offset: 0 })
  })
  vi.mocked(track).mockClear()
  pendingStore.set((s) => ({ ...s, plannerManual: false, plannerAiSaved: false }))
  keepActions.setArriving(false)
  keepActions.setNearby(null)
  withCart(ITEMS, POIS)
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  keepStore.set((s) => ({ ...s, nearby: null }))
  cartStore.set({ loadState: 'notLoaded', placeIds: new Set(), items: [], pois: [], poiIds: new Set(), toast: null })
  chooseLanguage('ko')
})

// 담은 촬영지로 AI 일정(docs/project/plans/saved-ai-course.md §4) — AI 일정에는 촬영지만 간다.
// 촬영지가 하나라도 있으면 AI 마법사(담은 곳 모드), 편의시설만 담았으면 지금처럼 직접 짜기.
describe('MyMapCover — 주 단추가 여는 마법사', () => {
  function clickPrimary() {
    const primary = screen.getByRole('button', { name: /이 \d+곳으로/ })
    fireEvent.click(primary)
  }

  it('촬영지 3 + 편의시설 2 → AI 마법사 담은 곳 모드(쪽지 plannerAiSaved, plannerManual 아님)', async () => {
    renderCover()
    await vi.waitFor(() => expect(screen.getByRole('button', { name: /이 5곳으로/ })).toBeTruthy())
    clickPrimary()
    expect(pendingStore.get().plannerAiSaved).toBe(true)
    expect(pendingStore.get().plannerManual).toBe(false)
  })

  it('편의시설만 담았으면(촬영지 0곳) 지금처럼 직접 짜기(쪽지 plannerManual, plannerAiSaved 아님)', async () => {
    withCart([], POIS)
    renderCover()
    await vi.waitFor(() => expect(screen.getByRole('button', { name: /이 2곳으로/ })).toBeTruthy())
    clickPrimary()
    expect(pendingStore.get().plannerManual).toBe(true)
    expect(pendingStore.get().plannerAiSaved).toBe(false)
  })
})

describe('MyMapCover — N 은 촬영지 + 편의시설', () => {
  it('머리줄 「마이맵 · 5곳」(촬영지 3 + 편의시설 2)', async () => {
    renderCover()
    await vi.waitFor(() => expect(bodyText()).toContain('마이맵 · 5곳'))
  })

  it('아래 주 단추 「이 5곳으로 계획 짜기」', async () => {
    renderCover()
    await vi.waitFor(() => expect(bodyText()).toContain('이 5곳으로 계획 짜기'))
  })

  it('영어 — My map · 5 spots', async () => {
    cartStore.set({ loadState: 'notLoaded', placeIds: new Set(), items: [], pois: [], poiIds: new Set(), toast: null })
    chooseLanguage('en')
    withCart(ITEMS, POIS)
    renderCover()
    await vi.waitFor(() => expect(bodyText()).toContain('My map · 5 spots'))
  })
})

describe('MyMapCover — 편의시설만 담았을 때', () => {
  it('빈 화면이 아니다 — 「아직 담은 곳이 없어요」 없이 머리줄 1곳과 그 줄', async () => {
    withCart([], [POIS[0]!])
    renderCover()
    await vi.waitFor(() => expect(bodyText()).toContain('남산 돈까스'))
    expect(bodyText()).not.toContain('아직 담은 곳이 없어요')
    expect(bodyText()).toContain('마이맵 · 1곳')
    expect(bodyText()).toContain('주변에서 담은 곳')
  })

  it('영어 — Nothing saved yet 이 없다, My map · 1 spot', async () => {
    cartStore.set({ loadState: 'notLoaded', placeIds: new Set(), items: [], pois: [], poiIds: new Set(), toast: null })
    chooseLanguage('en')
    withCart([], [POIS[0]!])
    renderCover()
    await vi.waitFor(() => expect(bodyText()).toContain('My map · 1 spot'))
    expect(bodyText()).not.toContain('Nothing saved yet')
    expect(bodyText()).not.toContain('1 spots')
  })
})

describe('MyMapCover — 「주변에서 담은 곳」 묶음', () => {
  it('목록 맨 아래 — 촬영지 묶음들 뒤에, 담은 편의시설 줄들', async () => {
    renderCover()
    await vi.waitFor(() => expect(bodyText()).toContain('주변에서 담은 곳'))
    const text = bodyText()
    const at = (s: string) => {
      const i = text.indexOf(s)
      expect(i, s).toBeGreaterThanOrEqual(0)
      return i
    }
    // 촬영지 줄(출처 없는 묶음이 촬영지 목록의 맨 끝) 뒤에 묶음 제목, 그 뒤에 편의시설 줄.
    expect(at('출처 없는 카페')).toBeLessThan(at('주변에서 담은 곳'))
    expect(at('남산서울타워')).toBeLessThan(at('주변에서 담은 곳'))
    expect(at('주변에서 담은 곳')).toBeLessThan(at('남산 돈까스'))
    expect(at('주변에서 담은 곳')).toBeLessThan(at('명동 게스트하우스'))
  })

  it('줄마다 이름(displayName ?? name)·분류 이름·주소', async () => {
    withCart(ITEMS, [savedPoi(503, { name: '스타벅스 남산점', displayName: 'Starbucks Namsan', categoryLabel: 'Cafe', category: '카페', address: '서울 중구 회현동' })])
    renderCover()
    await vi.waitFor(() => expect(bodyText()).toContain('Starbucks Namsan'))
    expect(bodyText()).toContain('Cafe')
    expect(bodyText()).toContain('서울 중구 회현동')
  })

  it('영어 — Saved nearby', async () => {
    cartStore.set({ loadState: 'notLoaded', placeIds: new Set(), items: [], pois: [], poiIds: new Set(), toast: null })
    chooseLanguage('en')
    withCart(ITEMS, POIS)
    renderCover()
    await vi.waitFor(() => expect(bodyText()).toContain('Saved nearby'))
  })

  it('폐업한 것은 「문을 닫았어요」', async () => {
    withCart(ITEMS, [savedPoi(504, { name: '문 닫은 빵집', closed: true })])
    renderCover()
    await vi.waitFor(() => expect(bodyText()).toContain('문 닫은 빵집'))
    expect(bodyText()).toContain('문을 닫았어요')
  })

  it('편의시설을 담지 않았으면 묶음이 없다', async () => {
    withCart(ITEMS, [])
    renderCover()
    await vi.waitFor(() => expect(bodyText()).toContain('남산서울타워'))
    expect(bodyText()).not.toContain('주변에서 담은 곳')
  })

  it('줄의 빼기 → cartActions.removePoi(그 id)', async () => {
    const removePoi = vi.spyOn(cartActions, 'removePoi').mockResolvedValue(undefined)
    withCart(ITEMS, [POIS[0]!])
    renderCover()
    await vi.waitFor(() => expect(bodyText()).toContain('남산 돈까스'))
    fireEvent.click(screen.getByRole('button', { name: '빼기' }))
    expect(removePoi).toHaveBeenCalledWith(501)
  })
})

describe('MyMapCover — 주변 칩', () => {
  it('열면 「식당·카페」 가 켜져 있고(§7, 11/15) GET /pois(food) 가 나간다 — 나머지 셋은 꺼져 있다', async () => {
    renderCover()
    await vi.waitFor(() => expect(bodyText()).toContain('남산서울타워'))
    await vi.waitFor(() => expect(chip('식당·카페').getAttribute('aria-pressed')).toBe('true'))
    for (const name of ['쇼핑', '명소', '숙소']) expect(chip(name).getAttribute('aria-pressed')).not.toBe('true')
    await vi.waitFor(() => expect(poiCalls().length).toBeGreaterThanOrEqual(1))
    expect(lastPoiQuery()).toMatchObject({ categoryGroup: 'food', sort: 'spread', limit: 60 })
    expect(keepStore.get().nearby).toBe('food')
  })

  it('저절로 켠 것은 my_map_nearby 를 보내지 않는다 — 사람이 누른 것이 아니다', async () => {
    renderCover()
    await vi.waitFor(() => expect(chip('식당·카페').getAttribute('aria-pressed')).toBe('true'))
    await vi.waitFor(() => expect(poiCalls().length).toBeGreaterThanOrEqual(1))
    expect(events().filter((e) => e.name === 'my_map_nearby')).toEqual([])
  })

  it('편의시설만 담았어도 열면 「식당·카페」 가 켜진다', async () => {
    withCart([], [POIS[0]!])
    renderCover()
    await vi.waitFor(() => expect(chip('식당·카페').getAttribute('aria-pressed')).toBe('true'))
  })

  it('담은 것이 늦게 들어와도(받는 중 → 받음) 그때 한 번 켠다', async () => {
    cartStore.set({ loadState: 'notLoaded', placeIds: new Set(), items: [], pois: [], poiIds: new Set(), toast: null })
    cartReply = { items: ITEMS, pois: POIS }
    renderCover()
    await vi.waitFor(() => expect(bodyText()).toContain('남산서울타워'))
    await vi.waitFor(() => expect(chip('식당·카페').getAttribute('aria-pressed')).toBe('true'))
    expect(events().filter((e) => e.name === 'my_map_nearby')).toEqual([])
  })

  it('담은 것이 없으면 켜지 않는다 — GET /pois 도 없다', async () => {
    withCart([], [])
    renderCover()
    await vi.waitFor(() => expect(bodyText()).toContain('아직 담은 곳이 없어요'))
    await new Promise((r) => setTimeout(r, 30))
    expect(keepStore.get().nearby).toBeNull()
    expect(poiCalls()).toEqual([])
  })

  it('사람이 끄면 덮개가 열려 있는 동안 다시 켜지 않는다 — 담은 것이 바뀌어도', async () => {
    renderCover()
    await vi.waitFor(() => expect(chip('식당·카페').getAttribute('aria-pressed')).toBe('true'))
    fireEvent.click(chip('식당·카페'))
    await vi.waitFor(() => expect(chip('식당·카페').getAttribute('aria-pressed')).not.toBe('true'))
    // 담은 것이 바뀐다(편의시설 하나 더) — 그래도 다시 켜지 않는다.
    act(() => withCart(ITEMS, [...POIS, savedPoi(503, { name: '새로 담은 곳' })]))
    await vi.waitFor(() => expect(bodyText()).toContain('새로 담은 곳'))
    await new Promise((r) => setTimeout(r, 30))
    for (const name of ['식당·카페', '쇼핑', '명소', '숙소']) expect(chip(name).getAttribute('aria-pressed')).not.toBe('true')
    expect(keepStore.get().nearby).toBeNull()
  })

  it('「식당·카페」 를 껐다 다시 켜면 GET /pois(categoryGroup food, sort spread, limit 60, bbox 는 담은 곳 둘레)와 my_map_nearby(food)', async () => {
    renderCover()
    await vi.waitFor(() => expect(chip('식당·카페').getAttribute('aria-pressed')).toBe('true'))
    fireEvent.click(chip('식당·카페'))
    await vi.waitFor(() => expect(chip('식당·카페').getAttribute('aria-pressed')).not.toBe('true'))
    const before = poiCalls().length
    fireEvent.click(chip('식당·카페'))
    await vi.waitFor(() => expect(poiCalls().length).toBeGreaterThan(before))
    const query = lastPoiQuery()
    expect(query).toMatchObject({ categoryGroup: 'food', sort: 'spread', limit: 60 })
    expect(query.categories).toBeUndefined()
    // 담은 곳들(촬영지 + 편의시설)의 둘레 — 촬영지만으로 잡은 둘레를 덮는다.
    const [minLng, minLat, maxLng, maxLat] = String(query.bbox).split(',').map(Number)
    const [eMinLng, eMinLat, eMaxLng, eMaxLat] = nearbyBbox(ITEMS.map((i) => ({ latitude: i.latitude!, longitude: i.longitude! })))!
      .split(',')
      .map(Number)
    expect(minLng).toBeLessThanOrEqual(eMinLng!)
    expect(minLat).toBeLessThanOrEqual(eMinLat!)
    expect(maxLng).toBeGreaterThanOrEqual(eMaxLng!)
    expect(maxLat).toBeGreaterThanOrEqual(eMaxLat!)
    expect(events()).toContainEqual(webEvent.myMapNearby('food'))
    expect(chip('식당·카페').getAttribute('aria-pressed')).toBe('true')
  })

  it('담은 것이 촬영지뿐이면 bbox 는 nearbyBbox(촬영지 좌표) 그대로', async () => {
    withCart(ITEMS, [])
    renderCover()
    await vi.waitFor(() => expect(bodyText()).toContain('남산서울타워'))
    fireEvent.click(chip('숙소'))
    await vi.waitFor(() => expect(poiCalls().length).toBeGreaterThanOrEqual(1))
    expect(lastPoiQuery().bbox).toBe(nearbyBbox(ITEMS.map((i) => ({ latitude: i.latitude!, longitude: i.longitude! }))))
    expect(lastPoiQuery()).toMatchObject({ categoryGroup: 'stay', sort: 'spread', limit: 60 })
    expect(events()).toContainEqual(webEvent.myMapNearby('stay'))
  })

  it('「쇼핑」 은 categoryGroup sight + categories 쇼핑', async () => {
    renderCover()
    await vi.waitFor(() => expect(bodyText()).toContain('남산서울타워'))
    fireEvent.click(chip('쇼핑'))
    await vi.waitFor(() => expect(poiCalls().length).toBeGreaterThanOrEqual(1))
    expect(lastPoiQuery()).toMatchObject({ categoryGroup: 'sight', categories: '쇼핑', sort: 'spread', limit: 60 })
    expect(events()).toContainEqual(webEvent.myMapNearby('shopping'))
  })

  it('「명소」 는 categoryGroup sight + categories 관광지,문화시설', async () => {
    renderCover()
    await vi.waitFor(() => expect(bodyText()).toContain('남산서울타워'))
    fireEvent.click(chip('명소'))
    await vi.waitFor(() => expect(poiCalls().length).toBeGreaterThanOrEqual(1))
    expect(lastPoiQuery()).toMatchObject({ categoryGroup: 'sight', categories: '관광지,문화시설' })
    expect(events()).toContainEqual(webEvent.myMapNearby('sight'))
  })

  it('켜진 칩을 다시 누르면 꺼진다', async () => {
    renderCover()
    await vi.waitFor(() => expect(chip('식당·카페').getAttribute('aria-pressed')).toBe('true'))
    fireEvent.click(chip('식당·카페'))
    await vi.waitFor(() => expect(chip('식당·카페').getAttribute('aria-pressed')).not.toBe('true'))
  })

  it('keepStore.nearby 가 미리 켜져 있으면(시트에서 넘어옴) 그 칩이 켜진 채로 뜨고 받는다', async () => {
    keepActions.setNearby('shopping')
    renderCover()
    await vi.waitFor(() => expect(chip('쇼핑').getAttribute('aria-pressed')).toBe('true'))
    await vi.waitFor(() => expect(poiCalls().length).toBeGreaterThanOrEqual(1))
    expect(lastPoiQuery()).toMatchObject({ categoryGroup: 'sight', categories: '쇼핑', sort: 'spread', limit: 60 })
    // 시트에서 켜 두고 연 칩은 그대로 — 식당·카페로 바꾸지 않는다.
    await new Promise((r) => setTimeout(r, 30))
    expect(chip('쇼핑').getAttribute('aria-pressed')).toBe('true')
    expect(chip('식당·카페').getAttribute('aria-pressed')).not.toBe('true')
    expect(poiCalls().every((c) => (c[1] as { params: { query: Record<string, unknown> } }).params.query.categoryGroup !== 'food')).toBe(true)
  })

  it('받은 결과가 0건이면 「이 둘레에는 없어요. 지도를 옮겨 보세요」', async () => {
    poisReply = []
    renderCover()
    await vi.waitFor(() => expect(bodyText()).toContain('남산서울타워'))
    fireEvent.click(chip('숙소'))
    await vi.waitFor(() => expect(bodyText()).toContain('이 둘레에는 없어요. 지도를 옮겨 보세요'))
  })

  it('영어 — None around here. Try moving the map.', async () => {
    cartStore.set({ loadState: 'notLoaded', placeIds: new Set(), items: [], pois: [], poiIds: new Set(), toast: null })
    chooseLanguage('en')
    withCart(ITEMS, POIS)
    poisReply = []
    renderCover()
    await vi.waitFor(() => expect(bodyText()).toContain('My map'))
    fireEvent.click(chip('Stays'))
    await vi.waitFor(() => expect(bodyText()).toContain('None around here. Try moving the map.'))
  })

  it('결과가 있으면 0건 안내가 없다', async () => {
    renderCover()
    await vi.waitFor(() => expect(chip('식당·카페').getAttribute('aria-pressed')).toBe('true'))
    await vi.waitFor(() => expect(poiCalls().length).toBeGreaterThanOrEqual(1))
    await new Promise((r) => setTimeout(r, 30))
    expect(bodyText()).not.toContain('이 둘레에는 없어요')
    expect(bodyText()).not.toContain('불러오지 못했어요')
  })

  it('실패면 「불러오지 못했어요」', async () => {
    poisReply = 'fail'
    renderCover()
    // 저절로 켜진 「식당·카페」 의 받기가 실패한다.
    await vi.waitFor(() => expect(bodyText()).toContain('남산서울타워'))
    await vi.waitFor(() => expect(bodyText()).toContain('불러오지 못했어요'))
  })

  it("영어 — Couldn't load", async () => {
    cartStore.set({ loadState: 'notLoaded', placeIds: new Set(), items: [], pois: [], poiIds: new Set(), toast: null })
    chooseLanguage('en')
    withCart(ITEMS, POIS)
    poisReply = 'fail'
    renderCover()
    await vi.waitFor(() => expect(bodyText()).toContain('My map'))
    await vi.waitFor(() => expect(bodyText()).toContain("Couldn't load"))
  })
})
