/*
 * 다시 온 사람의 홈 — 정본: docs/project/plans/web-ad-landing.md §4 · §6 · §9-8.
 *
 *   담은 것이 없을 때: 첫 칸 = 지금 뜨는 작품 1편의 장면 카드 + 단추 「이 작품의 장소 {N}곳 보기」(→ /go/{id}),
 *                      그 아래 「좋아하는 작품에서 시작하세요」 포스터 격자 6편(→ /go/{id}, home_tap(pick_title)) · 전체 보기(/trending),
 *                      도시 칩 줄은 아래에(home_tap(city_chip)).
 *   담은 것이 있을 때: 첫 칸 = 마이맵 카드, 그 아래 「더 담아 볼까요?」 줄 — 아직 안 담은 작품 포스터(→ 작품 상세, home_tap(add_title))
 *                      + 끝에 「주변 식당·쇼핑」 칸(→ /add, home_tap(add_nearby)).
 *   「어디로 여행 가세요?」 사진 카드는 맨 위에서 내린다. 「지금 뜨는 작품」 줄은 위 두 칸이 대신한다.
 *
 * 시험이 의지하는 이음매:
 *   - 작품 고르기 격자는 이름이 「좋아하는 작품에서 시작하세요」 인 region(<section aria-label> 또는 aria-labelledby 제목),
 *     더 담기 줄은 이름이 「더 담아 볼까요?」 인 region(시험이 정한 것). 마이맵 카드는 지금처럼 이름이 「마이맵」 인 region.
 *   - 칸 안의 포스터는 단추다. 격자의 「전체 보기」 도 단추.
 *   - 지금 뜨는 작품은 homeStore.works, 담은 것은 cartStore(GET /cart 도 같은 값), 도시는 homeCityStore.
 *   - 이벤트는 track(webEvent.homeTap(section)).
 * 홈의 다른 서버 호출(커뮤니티·코스)은 기존 홈 시험(HomeSeeAll.test.tsx)처럼 가짜로 막는다.
 * 지금 운영·dev 의 기본인 새 홈(시안 A, protoStore.proto = 'a')으로 그린다.
 */
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter, useLocation } from 'react-router'
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

vi.mock('./homeModel', async (importOriginal) => {
  const actual = await importOriginal<typeof import('./homeModel')>()
  const { createStore } = await import('@/state/storage')
  return {
    ...actual,
    homeStore: createStore({ works: [], today: null, trips: [], stamps: [], tripsLoading: false, failed: false }),
    reloadHome: async () => {},
  }
})
vi.mock('../community/store', () => ({
  useCommunityStore: () => ({ posts: [], refresh: async () => {}, blocked: async () => {} }),
}))
vi.mock('../route/store', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../route/store')>()),
  useRouteState: () => ({ routeCourses: [], courseList: 'loaded' }),
}))
vi.mock('@/features/search/map/MapView', () => ({ MapView: () => null }))

import { track } from '@/analytics'
import type { components } from '@/api/generated/schema'
import { chooseLanguage, type Lang } from '@/i18n'
import { type CartItem, cartStore } from '@/state/cart'
import { protoStore } from '../proto/protoState'
import { type HomeCity, homeCityStore } from './homeCityModel'
import { homeStore } from './homeModel'
import { HomeTab } from './HomeTab'

type ContentSummary = components['schemas']['ContentSummary']

const respond = (status: number, data?: unknown) => ({
  data,
  error: status >= 400 ? { code: 'INTERNAL', message: 'x' } : undefined,
  response: { ok: status >= 200 && status < 300, status, headers: new Headers() },
})

const TITLES: Record<number, string> = {
  32: '케이팝 데몬 헌터스',
  7: '도깨비',
  3: '기생충',
  5: '오징어 게임',
  6: '사랑의 불시착',
  8: '이상한 변호사 우영우',
  9: '더 글로리',
  10: '눈물의 여왕',
}
const WORK_IDS = [32, 7, 3, 5, 6, 8, 9, 10]
const works: ContentSummary[] = WORK_IDS.map((id, i) => ({
  id,
  category: 'drama',
  title: TITLES[id]!,
  placeCount: 10 - i,
  posterUrl: `https://x/poster-${id}.jpg`,
}))

const city = (key: string, count: number, lng: number): HomeCity =>
  ({
    key,
    ko: key,
    name: key,
    places: [],
    count,
    placesLoaded: false,
    area: { center: { lat: 37.5, lng }, bbox: [lng - 0.1, 37.4, lng + 0.1, 37.6] },
  }) as HomeCity

let cartItems: CartItem[]

let location: ReturnType<typeof useLocation> | null = null
function Probe() {
  location = useLocation()
  return null
}

function renderHome() {
  return render(
    <MemoryRouter initialEntries={['/']}>
      <HomeTab />
      <Probe />
    </MemoryRouter>,
  )
}

const homeTaps = () =>
  vi
    .mocked(track)
    .mock.calls.map(([e]) => e as { name: string; params: Record<string, unknown> })
    .filter((e) => e.name === 'home_tap')
    .map((e) => e.params.section)

/** a 가 b 보다 문서에서 앞에 있다. */
const before = (a: Node, b: Node) => (a.compareDocumentPosition(b) & Node.DOCUMENT_POSITION_FOLLOWING) !== 0

/** 그 region 안의 칸(단추) 중 이름에 `exclude` 가 들어가지 않은 것들. */
const tilesIn = (region: HTMLElement, exclude: string) =>
  within(region)
    .getAllByRole('button')
    .filter((b) => !(b.textContent ?? '').includes(exclude) && !(b.getAttribute('aria-label') ?? '').includes(exclude))

/** 그 region 이 이 작품을 보이나 — 제목 글자나 포스터의 alt 로. */
const showsWork = (region: HTMLElement, id: number) =>
  (region.textContent ?? '').includes(TITLES[id]!) || within(region).queryAllByAltText(TITLES[id]!).length > 0

function withCart(items: CartItem[]) {
  cartItems = items
  cartStore.set({ loadState: 'loaded', placeIds: new Set(items.map((i) => i.placeId)), items, pois: [], poiIds: new Set(), toast: null })
}

beforeEach(() => {
  cartStore.set({ loadState: 'notLoaded', placeIds: new Set(), items: [], pois: [], poiIds: new Set(), toast: null })
  chooseLanguage('ko')
  Object.defineProperty(window, 'matchMedia', {
    configurable: true,
    writable: true,
    value: (query: string) => ({
      matches: false, media: query, onchange: null,
      addListener: () => {}, removeListener: () => {}, addEventListener: () => {}, removeEventListener: () => {}, dispatchEvent: () => false,
    }),
  })
  location = null
  cartItems = []
  h.GET.mockReset()
  h.GET.mockImplementation(async (path: string, init?: { params?: { path?: { contentId?: number; placeId?: number } } }) => {
    if (path === '/cart') return respond(200, { items: cartItems, totalCount: cartItems.length, pois: [], poiCount: 0 })
    if (path === '/contents') return respond(200, { items: works, total: works.length, limit: 20, offset: 0 })
    if (path === '/contents/{contentId}/places') {
      const id = init?.params?.path?.contentId ?? 0
      const items = [1, 2, 3].map((n) => ({ id: id * 100 + n, name: `장소${id * 100 + n}`, latitude: 37.5, longitude: 127, imageUrl: `https://x/p-${n}.jpg` }))
      return respond(200, { items, total: items.length, limit: 100, offset: 0 })
    }
    if (path === '/places/{placeId}') {
      const id = init?.params?.path?.placeId ?? 0
      return respond(200, {
        id, name: `장소${id}`, latitude: 37.5, longitude: 127,
        scenes: [{ contentId: 32, contentTitle: TITLES[32], sceneDescription: '장면', sceneImageUrl: `https://x/scene-${id}.jpg`, sceneImageUrls: [`https://x/scene-${id}.jpg`] }],
      })
    }
    return respond(200, { items: [], total: 0, limit: 20, offset: 0 })
  })
  vi.mocked(track).mockClear()
  homeStore.set((s) => ({ ...s, works, today: null, failed: false }))
  homeCityStore.set({ lang: 'ko', status: 'loaded', selected: null, cities: [city('서울', 120, 126.98), city('부산', 40, 129.07)] })
  protoStore.set((s) => ({ ...s, proto: 'a', asking: false, forced: false }))
})

afterEach(() => {
  cleanup()
  cartStore.set({ loadState: 'notLoaded', placeIds: new Set(), items: [], pois: [], poiIds: new Set(), toast: null })
  chooseLanguage('ko')
})

// ---------------------------------------------------------------- 담은 것이 없을 때

describe('담은 것이 없을 때 — 첫 칸(§9-8)', () => {
  it.each<[Lang, RegExp]>([
    ['ko', /^이 작품의 장소 \d+곳 보기$/],
    ['en', /^See \d+ places? from this show$/],
  ])('%s — 장면 카드의 단추를 누르면 그 작품의 랜딩 /go/{id}', async (lang, label) => {
    chooseLanguage(lang)
    homeCityStore.set((s) => ({ ...s, lang }))
    withCart([])
    renderHome()
    const button = await vi.waitFor(() => {
      const found = screen.getAllByRole('button').find((b) => label.test((b.textContent ?? '').trim()))
      expect(found, '「이 작품의 장소 N곳 보기」 단추').toBeDefined()
      return found!
    })
    fireEvent.click(button)
    await vi.waitFor(() => expect(location?.pathname).toMatch(/^\/go\/\d+$/))
    const id = Number(location!.pathname.split('/')[2])
    expect(WORK_IDS).toContain(id)
  })
})

describe('담은 것이 없을 때 — 작품 고르기', () => {
  it.each<[Lang, string]>([
    ['ko', '좋아하는 작품에서 시작하세요'],
    ['en', 'Start with a show you love'],
  ])('%s — 격자 제목', async (lang, title) => {
    chooseLanguage(lang)
    homeCityStore.set((s) => ({ ...s, lang }))
    withCart([])
    renderHome()
    await vi.waitFor(() => expect(screen.getByRole('region', { name: title })).toBeTruthy())
  })

  it('포스터 6편 + 전체 보기', async () => {
    withCart([])
    renderHome()
    const grid = await vi.waitFor(() => screen.getByRole('region', { name: '좋아하는 작품에서 시작하세요' }))
    expect(tilesIn(grid, '전체 보기')).toHaveLength(6)
    expect(within(grid).getByRole('button', { name: '전체 보기' })).toBeTruthy()
  })

  it('포스터를 누르면 그 작품의 랜딩 /go/{id} + home_tap(pick_title)', async () => {
    withCart([])
    renderHome()
    const grid = await vi.waitFor(() => screen.getByRole('region', { name: '좋아하는 작품에서 시작하세요' }))
    fireEvent.click(tilesIn(grid, '전체 보기')[1]!)
    await vi.waitFor(() => expect(location?.pathname).toMatch(/^\/go\/\d+$/))
    expect(WORK_IDS).toContain(Number(location!.pathname.split('/')[2]))
    expect(homeTaps()).toContain('pick_title')
  })

  it('전체 보기 → /trending', async () => {
    withCart([])
    renderHome()
    const grid = await vi.waitFor(() => screen.getByRole('region', { name: '좋아하는 작품에서 시작하세요' }))
    fireEvent.click(within(grid).getByRole('button', { name: '전체 보기' }))
    await vi.waitFor(() => expect(location?.pathname).toBe('/trending'))
  })

  it('「어디로 여행 가세요?」 사진 카드가 맨 위에 없다 — 첫 칸과 격자가 앞선다', async () => {
    withCart([])
    renderHome()
    const grid = await vi.waitFor(() => screen.getByRole('region', { name: '좋아하는 작품에서 시작하세요' }))
    const ask = screen.queryByText('어디로 여행 가세요?')
    if (ask) expect(before(grid, ask), '「어디로 여행 가세요?」 가 격자보다 앞에 있다').toBe(true)
  })

  it('「지금 뜨는 작품」 줄은 없다 — 위 칸들이 대신한다', async () => {
    withCart([])
    renderHome()
    await vi.waitFor(() => screen.getByRole('region', { name: '좋아하는 작품에서 시작하세요' }))
    expect(screen.queryByText('지금 뜨는 작품')).toBeNull()
  })

  it('도시 칩 줄은 격자 아래에 있고, 칩을 누르면 home_tap(city_chip)', async () => {
    withCart([])
    renderHome()
    const grid = await vi.waitFor(() => screen.getByRole('region', { name: '좋아하는 작품에서 시작하세요' }))
    const chip = await vi.waitFor(() => {
      const found = screen.getAllByRole('button').find((b) => (b.textContent ?? '').includes('서울') && !grid.contains(b))
      expect(found, '「서울」 칩').toBeDefined()
      return found!
    })
    expect(before(grid, chip), '도시 칩이 격자 아래').toBe(true)
    fireEvent.click(chip)
    await vi.waitFor(() => expect(homeTaps()).toContain('city_chip'))
  })

  it('마이맵 카드와 더 담기 줄은 없다', async () => {
    withCart([])
    renderHome()
    await vi.waitFor(() => screen.getByRole('region', { name: '좋아하는 작품에서 시작하세요' }))
    expect(screen.queryByRole('region', { name: '마이맵' })).toBeNull()
    expect(screen.queryByRole('region', { name: '더 담아 볼까요?' })).toBeNull()
  })
})

// ---------------------------------------------------------------- 담은 것이 있을 때

const SAVED: CartItem[] = [
  { placeId: 701, name: '덕수궁 돌담길', addedAt: '2026-11-14T00:00:00Z', sourceContentId: 7, sourceContentTitle: TITLES[7], latitude: 37.56, longitude: 126.97 },
  { placeId: 702, name: '주문진 방사제', addedAt: '2026-11-14T00:00:01Z', sourceContentId: 7, sourceContentTitle: TITLES[7], latitude: 37.9, longitude: 128.8 },
]

describe('담은 것이 있을 때', () => {
  it('마이맵 카드가 첫 칸이고 그 아래 「더 담아 볼까요?」 줄', async () => {
    withCart(SAVED)
    renderHome()
    const myMap = await vi.waitFor(() => screen.getByRole('region', { name: '마이맵' }))
    const more = await vi.waitFor(() => screen.getByRole('region', { name: '더 담아 볼까요?' }))
    expect(before(myMap, more)).toBe(true)
    const ask = screen.queryByText('어디로 여행 가세요?')
    if (ask) expect(before(myMap, ask), '「어디로 여행 가세요?」 가 마이맵 카드보다 앞에 있다').toBe(true)
    // 처음 온 사람의 칸은 없다.
    expect(screen.queryByRole('region', { name: '좋아하는 작품에서 시작하세요' })).toBeNull()
    expect(screen.getAllByRole('button').some((b) => /^이 작품의 장소 \d+곳 보기$/.test((b.textContent ?? '').trim()))).toBe(false)
  })

  it('영어 — 줄 이름 「Add more」', async () => {
    chooseLanguage('en')
    homeCityStore.set((s) => ({ ...s, lang: 'en' }))
    withCart(SAVED)
    renderHome()
    await vi.waitFor(() => expect(screen.getByRole('region', { name: 'Add more' })).toBeTruthy())
  })

  it('더 담기 줄은 아직 안 담은 작품만', async () => {
    withCart(SAVED)
    renderHome()
    const more = await vi.waitFor(() => screen.getByRole('region', { name: '더 담아 볼까요?' }))
    await vi.waitFor(() => expect(showsWork(more, 3) || showsWork(more, 32)).toBe(true))
    expect(showsWork(more, 7), '이미 담은 도깨비').toBe(false)
  })

  it('포스터를 누르면 작품 상세(/search/content/{id}) + home_tap(add_title)', async () => {
    withCart(SAVED)
    renderHome()
    const more = await vi.waitFor(() => screen.getByRole('region', { name: '더 담아 볼까요?' }))
    const posters = tilesIn(more, '주변 식당·쇼핑')
    expect(posters.length).toBeGreaterThan(0)
    fireEvent.click(posters[0]!)
    await vi.waitFor(() => expect(location?.pathname).toMatch(/^\/search\/content\/\d+$/))
    const id = Number(location!.pathname.split('/')[3])
    expect(WORK_IDS).toContain(id)
    expect(id).not.toBe(7)
    expect(homeTaps()).toContain('add_title')
  })

  it.each<[Lang, string, string]>([
    ['ko', '더 담아 볼까요?', '주변 식당·쇼핑'],
    ['en', 'Add more', 'Food & shopping nearby'],
  ])('%s — 끝의 「%s」 칸 → /add + home_tap(add_nearby)', async (lang, rowName, nearby) => {
    chooseLanguage(lang)
    homeCityStore.set((s) => ({ ...s, lang }))
    withCart(SAVED)
    renderHome()
    const more = await vi.waitFor(() => screen.getByRole('region', { name: rowName }))
    const tile = within(more)
      .getAllByRole('button')
      .find((b) => (b.textContent ?? '').includes(nearby) || (b.getAttribute('aria-label') ?? '').includes(nearby))
    expect(tile, `「${nearby}」 칸`).toBeDefined()
    // 줄 끝 — 포스터들 뒤
    const posters = tilesIn(more, nearby)
    if (posters.length > 0) expect(before(posters[posters.length - 1]!, tile!)).toBe(true)
    fireEvent.click(tile!)
    await vi.waitFor(() => expect(location?.pathname).toBe('/add'))
    expect(homeTaps()).toContain('add_nearby')
  })
})
