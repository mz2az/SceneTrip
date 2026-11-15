/*
 * 다시 온 사람의 홈 — 정본: docs/project/plans/web-ad-landing.md §4 · §9-8, web-keep-flow.md §5-6.
 *
 * 2026-11-15 사용자 — 「너무 많이 바꾼 거 아냐? 마이맵 카드는 좋다. 기존의 「어디로 여행 가세요?」 카드와 「지금 뜨는 작품」 줄도 살리자.
 * 「지금 뜨는 작품」 의 전체 보기로 작품 전체를 볼 수 있어야 한다.」
 *
 *   담은 것이 있든 없든 같은 뼈대: 머리줄 → (마이맵 카드 — 담은 것이 있을 때만) → 「어디로 여행 가세요?」 사진 카드
 *   → 「지금 뜨는 작품 · 인기순 · 전체 보기」(전체 작품, 담은 작품도 빼지 않는다, 포스터 → 작품 상세 + home_tap(trending_title),
 *   전체 보기 → /trending + home_tap(trending_all)) → 오늘의 성지.
 *   장면 카드(「이 작품의 장소 N곳 보기」)·「좋아하는 작품에서 시작하세요」 격자·「더 담아 볼까요?」 줄은 홈에 없다.
 *
 * 시험이 의지하는 이음매:
 *   - 마이맵 카드는 이름이 「마이맵」 인 region. 「지금 뜨는 작품」 의 포스터는 작품 제목을 글자로 품은 단추, 「전체 보기」 도 단추.
 *   - 오늘의 성지는 homeStore.today.
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
import { chooseLanguage } from '@/i18n'
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

// ---------------------------------------------------------------- 뼈대

const TODAY = { id: 901, name: '오늘의장소', latitude: 37.5, longitude: 127, address: '서울 중구' } as components['schemas']['PlaceSummary']

/** 화면에서 그 글자를 가진 가장 안쪽 요소. */
const textNode = (text: string) => screen.getByText(text)

const SAVED: CartItem[] = [
  { placeId: 701, name: '덕수궁 돌담길', addedAt: '2026-11-14T00:00:00Z', sourceContentId: 7, sourceContentTitle: TITLES[7], latitude: 37.56, longitude: 126.97 },
  { placeId: 702, name: '주문진 방사제', addedAt: '2026-11-14T00:00:01Z', sourceContentId: 7, sourceContentTitle: TITLES[7], latitude: 37.9, longitude: 128.8 },
]

/** 「지금 뜨는 작품」 포스터 — 제목을 품은 단추. 마이맵 카드 안의 칩은 뺀다. */
const posterButton = (id: number) => {
  const myMap = screen.queryByRole('region', { name: '마이맵' })
  return screen.getAllByRole('button').find((b) => (b.textContent ?? '').includes(TITLES[id]!) && !(myMap?.contains(b) ?? false))
}

describe.each<[string, CartItem[]]>([
  ['담은 것이 없을 때', []],
  ['담은 것이 있을 때', SAVED],
])('%s — 같은 뼈대', (_name, items) => {
  beforeEach(() => {
    homeStore.set((s) => ({ ...s, today: TODAY }))
    withCart(items)
  })

  it('머리줄 → (마이맵) → 「어디로 여행 가세요?」 → 「지금 뜨는 작품」·「전체 보기」 → 오늘의 성지', async () => {
    renderHome()
    const ask = await vi.waitFor(() => textNode('어디로 여행 가세요?'))
    const head = textNode('오늘은 어느 장면으로 갈까요?')
    const shelf = textNode('지금 뜨는 작품')
    const all = screen.getByRole('button', { name: '전체 보기' })
    const today = textNode('오늘의 성지')
    expect(before(head, ask), '머리줄이 「어디로 여행 가세요?」 앞').toBe(true)
    expect(before(ask, shelf), '「어디로 여행 가세요?」 가 「지금 뜨는 작품」 앞').toBe(true)
    expect(before(shelf, all), '「전체 보기」 는 「지금 뜨는 작품」 머리줄에').toBe(true)
    expect(before(all, today), '「지금 뜨는 작품」 이 오늘의 성지 앞').toBe(true)
    if (items.length > 0) {
      const myMap = await vi.waitFor(() => screen.getByRole('region', { name: '마이맵' }))
      expect(before(head, myMap), '머리줄이 마이맵 앞').toBe(true)
      expect(before(myMap, ask), '마이맵이 「어디로 여행 가세요?」 앞').toBe(true)
    } else {
      expect(screen.queryByRole('region', { name: '마이맵' })).toBeNull()
    }
  })

  it('장면 카드·작품 고르기 격자·「더 담아 볼까요?」 줄은 없다', async () => {
    renderHome()
    await vi.waitFor(() => textNode('지금 뜨는 작품'))
    expect(screen.queryByText('더 담아 볼까요?')).toBeNull()
    expect(screen.queryByRole('region', { name: '더 담아 볼까요?' })).toBeNull()
    expect(screen.queryByRole('region', { name: '좋아하는 작품에서 시작하세요' })).toBeNull()
    expect(screen.queryByText('주변 식당·쇼핑')).toBeNull()
    expect(screen.getAllByRole('button').some((b) => /^이 작품의 장소 \d+곳 보기$/.test((b.textContent ?? '').trim()))).toBe(false)
  })

  it('「지금 뜨는 작품」 은 전체 작품 — 고정 작품이 앞, 담은 작품도 빼지 않는다', async () => {
    renderHome()
    await vi.waitFor(() => textNode('지금 뜨는 작품'))
    for (const id of WORK_IDS) expect(posterButton(id), `${TITLES[id]} 포스터`).toBeDefined()
    expect(before(posterButton(32)!, posterButton(7)!), '케이팝 데몬 헌터스가 맨 앞').toBe(true)
  })

  it('「전체 보기」 → /trending + home_tap(trending_all)', async () => {
    renderHome()
    const all = await vi.waitFor(() => screen.getByRole('button', { name: '전체 보기' }))
    fireEvent.click(all)
    await vi.waitFor(() => expect(location?.pathname).toBe('/trending'))
    expect(homeTaps()).toContain('trending_all')
  })

  it('포스터 → 작품 상세(/search/content/{id}) + home_tap(trending_title)', async () => {
    renderHome()
    await vi.waitFor(() => textNode('지금 뜨는 작품'))
    fireEvent.click(posterButton(3)!)
    await vi.waitFor(() => expect(location?.pathname).toBe('/search/content/3'))
    expect(homeTaps()).toContain('trending_title')
  })

  it('「어디로 여행 가세요?」 의 도시 칩 → select_home_city(proto_chip)', async () => {
    renderHome()
    const ask = await vi.waitFor(() => textNode('어디로 여행 가세요?'))
    const card = ask.closest('section')!
    const chip = within(card).getAllByRole('button').find((b) => (b.textContent ?? '').includes('서울'))
    expect(chip, '「서울」 칩').toBeDefined()
    fireEvent.click(chip!)
    const sent = vi.mocked(track).mock.calls.map(([e]) => e as { name: string; params: Record<string, unknown> })
    expect(sent.some((e) => e.name === 'select_home_city' && e.params.source === 'proto_chip' && e.params.city === '서울')).toBe(true)
  })
})

describe('영어', () => {
  it('「Where are you traveling?」 · 「Trending titles」 · 「See all」, 「Add more」 없음', async () => {
    chooseLanguage('en')
    homeCityStore.set((s) => ({ ...s, lang: 'en' }))
    withCart(SAVED)
    renderHome()
    const ask = await vi.waitFor(() => screen.getByText('Where are you traveling?'))
    const shelf = screen.getByText('Trending titles')
    expect(before(ask, shelf)).toBe(true)
    expect(screen.getByRole('button', { name: 'See all' })).toBeTruthy()
    expect(screen.queryByRole('region', { name: 'Add more' })).toBeNull()
  })
})
