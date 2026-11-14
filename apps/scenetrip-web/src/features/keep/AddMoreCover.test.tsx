/*
 * 「더 담기」 덮개 `/add` — 정본: docs/project/plans/web-ad-landing.md §2 · §3 · §7 · §9-4, keep-poi.md §9-2.
 *
 *   AddMoreCover({ onClose }): 머리줄 「내 지도 · N곳」(N = 촬영지 + 편의시설), 「더 담아 볼까요?」.
 *   다른 작품: moreWorks(지금 뜨는 작품, 이미 담은 작품들, 9). 칸의 ＋ → 촬영지 받아 addMany(…, 'more') + keep_more, 칸에 「✓ +N」.
 *   주변 세 줄(쇼핑·명소·식당·카페): 담은 곳 둘레(nearbyBbox)에서 GET /pois(sort=spread).
 *     쇼핑 categories=쇼핑 + hasImage=true, 명소 categories=관광지,문화시설 + hasImage=true, 식당 categoryGroup=food(사진 조건 없음).
 *     칸 ＋ → addPoi. 다시 눌러도 빼지 않는다(§10-3-12 — 빼기는 내 지도에서). 줄이 비면 줄을 그리지 않는다. 실패는 「불러오지 못했어요」 + 다시 시도.
 *   아래: 「이 N곳으로 여행 일정 짜기」 → 간직하기(②, 설치된 환경이면 ③) + add_more(plan). 「내 지도 보기」 → /my-map.
 *   이벤트: 뜨면 add_more(view), 주변 칸으로 담으면 add_more(poi).
 *
 * 시험이 의지하는 이음매(명세에 이름이 없어 시험이 정한 것 — 구현이 따른다):
 *   - 칸의 원형 ＋ 단추: aria-label = say(`${이름} 담기`, `Add ${이름}`) — 이름은 작품 제목, 편의시설은 displayName ?? name.
 *     담겨 있으면 같은 단추가 aria-pressed="true"(편의시설 칸). 눌린 단추를 다시 눌러도 빼지 않는다.
 *   - 줄 끝 「지도에서 더 보기」 는 줄 순서(쇼핑 → 명소 → 식당·카페)대로 놓인다.
 *   - 지금 뜨는 작품 = homeStore.works(@/features/home/homeModel). GET /contents 도 같은 목록을 준다.
 *   - 환경 currentKeepEnv()(@/features/keep/installPrompt), 밖으로 넘기기 handoff.open(href)(@/features/keep/handoff).
 *   - 담기 cartActions.addMany · addPoi · removePoi, 시트 상태 keepStore, 주변 칩 keepStore.nearby.
 */
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, useLocation } from 'react-router'
import { type MockInstance, afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const h = vi.hoisted(() => ({
  GET: vi.fn(),
  env: { value: 'other' as string },
  handoffOpen: vi.fn(),
}))

vi.mock('@/api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/client')>()),
  api: { GET: (...args: unknown[]) => h.GET(...args), POST: vi.fn(), DELETE: vi.fn() },
}))

vi.mock('@/analytics', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/analytics')>()
  return { ...actual, track: vi.fn() }
})

vi.mock('@/features/keep/installPrompt', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/features/keep/installPrompt')>()),
  currentKeepEnv: () => h.env.value,
}))

vi.mock('@/features/keep/handoff', () => ({
  handoff: { open: (href: string) => h.handoffOpen(href) },
}))

vi.mock('@/features/search/map/MapView', () => ({ MapView: () => null }))

import { track } from '@/analytics'
import type { components } from '@/api/generated/schema'
import { OverlayRootContext } from '@/design/overlay'
import { homeStore } from '@/features/home/homeModel'
import { chooseLanguage, type Lang } from '@/i18n'
import { type CartItem, type CartPoi, cartActions, cartStore } from '@/state/cart'
import { AddMoreCover } from './AddMoreCover'
import { nearbyBbox } from './keepPoiRules'
import { keepStore } from './keepStore'

type ContentSummary = components['schemas']['ContentSummary']

const respond = (status: number, data?: unknown) => ({
  data,
  error: status >= 400 ? { code: 'INTERNAL', message: 'x' } : undefined,
  response: { ok: status >= 200 && status < 300, status, headers: new Headers() },
})

const work = (id: number, title: string, placeCount = 3, delisted?: boolean): ContentSummary => ({
  id,
  category: 'drama',
  title,
  placeCount,
  posterUrl: `https://x/poster-${id}.jpg`,
  ...(delisted === undefined ? {} : { delisted }),
})

const ITEMS: CartItem[] = [
  { placeId: 201, name: '낙산공원', addedAt: '2026-11-14T00:00:00Z', sourceContentId: 32, sourceContentTitle: '케이팝 데몬 헌터스', latitude: 37.58, longitude: 127.0 },
  { placeId: 202, name: '남산서울타워', addedAt: '2026-11-14T00:00:01Z', sourceContentId: 32, sourceContentTitle: '케이팝 데몬 헌터스', latitude: 37.55, longitude: 126.99 },
  { placeId: 203, name: '북촌', addedAt: '2026-11-14T00:00:02Z', sourceContentId: 32, sourceContentTitle: '케이팝 데몬 헌터스', latitude: 37.582, longitude: 126.983 },
]

type Kind = 'shopping' | 'sight' | 'food'
interface Poi {
  id: number
  placeId: number | null
  name: string
  displayName: string | null
  nameRoman: string | null
  category: string
  categoryLabel: string
  categoryGroup: 'food' | 'sight'
  latitude: number
  longitude: number
  imageUrl: string | null
}
const poi = (id: number, kind: Kind, name: string, extra: Partial<Poi> = {}): Poi => ({
  id,
  placeId: null,
  name,
  displayName: name,
  nameRoman: null,
  category: kind === 'shopping' ? '쇼핑' : kind === 'sight' ? '관광지' : '음식점',
  categoryLabel: kind === 'shopping' ? '쇼핑' : kind === 'sight' ? '관광지' : '한식',
  categoryGroup: kind === 'food' ? 'food' : 'sight',
  latitude: 37.57,
  longitude: 126.99,
  imageUrl: kind === 'food' ? null : `https://x/poi-${id}.jpg`,
  ...extra,
})

/** 줄마다 GET /pois 의 답 — 목록 또는 'fail'. */
let nearby: Record<Kind, Poi[] | 'fail'>
/** 한 번만 실패하는 줄. */
let failOnce: Set<Kind>
/** 작품 id → 촬영지 id 들. 'fail' 이면 500. */
let placesOf: Map<number, number[] | 'fail'>

interface PoisQuery {
  bbox?: string
  categoryGroup?: string
  categories?: string
  hasImage?: boolean
  sort?: string
  limit?: number
}
const kindOf = (q: PoisQuery): Kind | null =>
  q.categories === '쇼핑' ? 'shopping' : q.categories === '관광지,문화시설' ? 'sight' : q.categoryGroup === 'food' && q.categories === undefined ? 'food' : null
const poisQueries = () => h.GET.mock.calls.filter((c) => c[0] === '/pois').map((c) => ((c[1] as { params?: { query?: PoisQuery } })?.params?.query ?? {}) as PoisQuery)

let location: ReturnType<typeof useLocation> | null = null
function Probe() {
  location = useLocation()
  return null
}

let overlayRoot: HTMLDivElement

function renderCover(onClose = vi.fn()) {
  const view = render(
    <OverlayRootContext.Provider value={overlayRoot}>
      <MemoryRouter initialEntries={['/add']}>
        <AddMoreCover onClose={onClose} />
        <Probe />
      </MemoryRouter>
    </OverlayRootContext.Provider>,
  )
  return { ...view, onClose }
}

const events = () => vi.mocked(track).mock.calls.map(([e]) => e as { name: string; params: Record<string, unknown> })
const has = (name: string, params: Record<string, unknown>) =>
  events().some((e) => e.name === name && JSON.stringify(e.params) === JSON.stringify(params))
const bodyText = () => document.body.textContent ?? ''

function buttonWith(text: string): HTMLElement {
  const button = screen.getByText(text).closest('button')
  expect(button, `「${text}」 단추`).not.toBeNull()
  return button as HTMLElement
}
const addButton = (name: string, lang: Lang = 'ko') => screen.getByRole('button', { name: lang === 'ko' ? `${name} 담기` : `Add ${name}` })
const queryAddButton = (name: string) => screen.queryByRole('button', { name: `${name} 담기` })

function withCart(items: CartItem[], pois: CartPoi[] = []) {
  cartStore.set({
    loadState: 'loaded',
    placeIds: new Set(items.map((i) => i.placeId)),
    items,
    pois,
    poiIds: new Set(pois.map((p) => p.id)),
    toast: null,
  })
}

let addMany: MockInstance<typeof cartActions.addMany>
let addPoi: MockInstance<typeof cartActions.addPoi>
let removePoi: MockInstance<typeof cartActions.removePoi>

beforeEach(() => {
  cartStore.set({ loadState: 'notLoaded', placeIds: new Set(), items: [], pois: [], poiIds: new Set(), toast: null })
  chooseLanguage('ko')
  overlayRoot = document.createElement('div')
  document.body.appendChild(overlayRoot)
  location = null
  h.env.value = 'other'
  h.handoffOpen.mockReset()
  nearby = {
    shopping: [poi(1, 'shopping', '롯데영플라자'), poi(2, 'shopping', '명동 거리')],
    sight: [poi(11, 'sight', '경복궁'), poi(12, 'sight', '창덕궁')],
    food: [poi(21, 'food', '명동교자'), poi(22, 'food', '이층 카페')],
  }
  failOnce = new Set()
  placesOf = new Map()
  const works = [work(32, '케이팝 데몬 헌터스'), work(7, '도깨비'), work(3, '기생충')]
  homeStore.set((s) => ({ ...s, works }))
  h.GET.mockReset()
  h.GET.mockImplementation(async (path: string, init?: { params?: { path?: { contentId?: number }; query?: PoisQuery } }) => {
    if (path === '/pois') {
      const q = init?.params?.query ?? {}
      const kind = kindOf(q)
      if (kind === null) return respond(200, { items: [], total: 0, limit: 60, offset: 0 })
      if (failOnce.has(kind)) {
        failOnce.delete(kind)
        return respond(500)
      }
      const rows = nearby[kind]
      if (rows === 'fail') return respond(500)
      const items = q.limit !== undefined ? rows.slice(0, q.limit) : rows
      return respond(200, { items, total: rows.length, limit: q.limit ?? 60, offset: 0 })
    }
    if (path === '/contents/{contentId}/places') {
      const id = init?.params?.path?.contentId ?? -1
      const ids = placesOf.get(id) ?? []
      if (ids === 'fail') return respond(500)
      const items = ids.map((pid) => ({ id: pid, name: `장소${pid}`, latitude: 37.5, longitude: 127 }))
      return respond(200, { items, total: items.length, limit: 100, offset: 0 })
    }
    if (path === '/contents') {
      const works = homeStore.get().works
      return respond(200, { items: works, total: works.length, limit: 20, offset: 0 })
    }
    if (path === '/cart') {
      const s = cartStore.get()
      return respond(200, { items: s.items, totalCount: s.items.length, pois: s.pois, poiCount: s.pois.length })
    }
    return respond(200, { items: [], total: 0, limit: 20, offset: 0 })
  })
  vi.mocked(track).mockClear()
  keepStore.set({ open: false, step: 'more', contentIds: [], title: null, arriving: false, nearby: null })
  withCart(ITEMS)
  addMany = vi.spyOn(cartActions, 'addMany').mockResolvedValue({ saved: 3, already: 0, failed: 0 })
  addPoi = vi.spyOn(cartActions, 'addPoi').mockImplementation(async (p) => {
    const found = Object.values(nearby).flatMap((rows) => (rows === 'fail' ? [] : rows)).find((x) => x.id === p.id)
    const s = cartStore.get()
    const added = { ...(found ?? poi(p.id, 'food', `편의시설${p.id}`)), addedAt: '2026-11-14T01:00:00Z', closed: false } as unknown as CartPoi
    cartStore.set({ ...s, pois: [...s.pois, added], poiIds: new Set([...s.poiIds, p.id]) })
    return 'saved'
  })
  removePoi = vi.spyOn(cartActions, 'removePoi').mockImplementation(async (id) => {
    const s = cartStore.get()
    const next = new Set(s.poiIds)
    next.delete(id)
    cartStore.set({ ...s, pois: s.pois.filter((x) => x.id !== id), poiIds: next })
  })
})

afterEach(() => {
  cleanup()
  overlayRoot.remove()
  vi.restoreAllMocks()
  cartStore.set({ loadState: 'notLoaded', placeIds: new Set(), items: [], pois: [], poiIds: new Set(), toast: null })
  chooseLanguage('ko')
})

// ---------------------------------------------------------------- 머리줄

describe('머리줄', () => {
  it.each<[Lang, string, string]>([
    ['ko', '내 지도 · 3곳', '더 담아 볼까요?'],
    ['en', 'My map · 3 spots', 'Add more to your map'],
  ])('%s — 「내 지도 · N곳」 과 「더 담아 볼까요?」', async (lang, head, sub) => {
    chooseLanguage(lang)
    withCart(ITEMS)
    renderCover()
    await vi.waitFor(() => expect(bodyText()).toContain(head))
    expect(bodyText()).toContain(sub)
  })

  it('N 은 촬영지 + 편의시설', async () => {
    const saved = { ...poi(99, 'food', '담아 둔 식당'), addedAt: '2026-11-14T00:00:00Z', closed: false } as unknown as CartPoi
    withCart(ITEMS, [saved])
    renderCover()
    await vi.waitFor(() => expect(bodyText()).toContain('내 지도 · 4곳'))
  })

  it('뜨면 add_more(view)', async () => {
    renderCover()
    await vi.waitFor(() => expect(has('add_more', { action: 'view' })).toBe(true))
  })
})

// ---------------------------------------------------------------- 다른 작품

describe('다른 작품의 촬영지', () => {
  it.each<[Lang, string]>([
    ['ko', '다른 작품의 촬영지'],
    ['en', 'Spots from other shows'],
  ])('%s — 구획 제목', async (lang, title) => {
    chooseLanguage(lang)
    withCart(ITEMS)
    renderCover()
    await vi.waitFor(() => expect(bodyText()).toContain(title))
  })

  it('최대 9편 — 이미 담은 작품·촬영지 없는 작품·빠진 작품은 빼고 받은 순서대로', async () => {
    const works = [
      work(32, '케이팝 데몬 헌터스'),
      work(2, '촬영지 없는 작품', 0),
      work(4, '빠진 작품', 5, true),
      ...Array.from({ length: 10 }, (_, i) => work(100 + i, `작품${100 + i}`)),
    ]
    homeStore.set((s) => ({ ...s, works }))
    renderCover()
    await vi.waitFor(() => expect(queryAddButton('작품100')).not.toBeNull())
    for (let i = 0; i < 9; i++) expect(queryAddButton(`작품${100 + i}`), `작품${100 + i}`).not.toBeNull()
    expect(queryAddButton('작품109')).toBeNull()
    expect(queryAddButton('케이팝 데몬 헌터스')).toBeNull()
    expect(queryAddButton('촬영지 없는 작품')).toBeNull()
    expect(queryAddButton('빠진 작품')).toBeNull()
  })

  it('다른 작품이 없으면 그 구획을 뺀다', async () => {
    homeStore.set((s) => ({ ...s, works: [work(32, '케이팝 데몬 헌터스')] }))
    renderCover()
    await vi.waitFor(() => expect(bodyText()).toContain('내 지도 · 3곳'))
    await new Promise((r) => setTimeout(r, 20))
    expect(bodyText()).not.toContain('다른 작품의 촬영지')
  })

  it('＋ 를 누르면 그 작품 촬영지를 받아 addMany(…, more) 로 담고 keep_more, 칸에 「✓ +N」', async () => {
    placesOf.set(7, [701, 702, 703])
    renderCover()
    await vi.waitFor(() => expect(queryAddButton('도깨비')).not.toBeNull())
    fireEvent.click(addButton('도깨비'))
    await vi.waitFor(() => expect(addMany).toHaveBeenCalledTimes(1))
    const call = addMany.mock.calls[0]!
    expect([call[0], call[1], call[3]]).toEqual([[701, 702, 703], 7, 'more'])
    expect(has('keep_more', { content_id: 7 })).toBe(true)
    await vi.waitFor(() => expect(bodyText()).toContain('✓ +3'))
  })

  it.each<[Lang, string]>([
    ['ko', '담지 못했어요'],
    ['en', "Couldn't save"],
  ])('%s — 받기에 실패하면 「%s」, 다시 누를 수 있다', async (lang, failed) => {
    chooseLanguage(lang)
    withCart(ITEMS)
    placesOf.set(7, 'fail')
    renderCover()
    await vi.waitFor(() => expect(screen.queryByRole('button', { name: lang === 'ko' ? '도깨비 담기' : 'Add 도깨비' })).not.toBeNull())
    fireEvent.click(addButton('도깨비', lang))
    await vi.waitFor(() => expect(bodyText()).toContain(failed))
    placesOf.set(7, [701, 702, 703])
    fireEvent.click(addButton('도깨비', lang))
    await vi.waitFor(() => expect(addMany).toHaveBeenCalledTimes(1))
    await vi.waitFor(() => expect(bodyText()).toContain('✓ +3'))
  })
})

// ---------------------------------------------------------------- 주변 세 줄

describe('주변 — 요청', () => {
  it('담은 곳 둘레(nearbyBbox)에서 sort=spread 로 세 줄을 받는다 — 쇼핑·명소는 사진 있는 곳만, 식당은 사진 조건 없음', async () => {
    renderCover()
    await vi.waitFor(() => expect(poisQueries().length).toBeGreaterThanOrEqual(3))
    const bbox = nearbyBbox(ITEMS.map((i) => ({ latitude: i.latitude!, longitude: i.longitude! })))
    const qs = poisQueries()
    const shopping = qs.find((q) => q.categories === '쇼핑')
    const sight = qs.find((q) => q.categories === '관광지,문화시설')
    const food = qs.find((q) => q.categoryGroup === 'food')
    expect(shopping, '쇼핑 요청').toBeDefined()
    expect(sight, '명소 요청').toBeDefined()
    expect(food, '식당 요청').toBeDefined()
    for (const q of [shopping!, sight!, food!]) {
      expect(q.bbox).toBe(bbox)
      expect(q.sort).toBe('spread')
    }
    expect(shopping!.hasImage).toBe(true)
    expect(sight!.hasImage).toBe(true)
    expect(food!.hasImage).not.toBe(true)
    expect(food!.categories).toBeUndefined()
  })
})

describe('주변 — 줄', () => {
  it.each<[Lang, string, string, string]>([
    ['ko', '주변 쇼핑', '주변 명소', '주변 식당·카페'],
    ['en', 'Shopping nearby', 'Sights nearby', 'Food & cafés nearby'],
  ])('%s — 줄 제목과 칸(이름·분류 이름)', async (lang, shop, sight, food) => {
    chooseLanguage(lang)
    withCart(ITEMS)
    renderCover()
    await vi.waitFor(() => expect(bodyText()).toContain(food))
    expect(bodyText()).toContain(shop)
    expect(bodyText()).toContain(sight)
    for (const name of ['롯데영플라자', '경복궁', '명동교자']) expect(bodyText(), name).toContain(name)
    expect(bodyText(), '분류 이름').toContain('한식')
  })

  it('줄마다 최대 12곳', async () => {
    nearby.shopping = Array.from({ length: 15 }, (_, i) => poi(300 + i, 'shopping', `가게${300 + i}`))
    renderCover()
    await vi.waitFor(() => expect(queryAddButton('가게300')).not.toBeNull())
    const shown = Array.from({ length: 15 }, (_, i) => queryAddButton(`가게${300 + i}`)).filter((b) => b !== null)
    expect(shown).toHaveLength(12)
  })

  it('그 둘레에 없으면 그 줄을 그리지 않는다', async () => {
    nearby.food = []
    renderCover()
    await vi.waitFor(() => expect(bodyText()).toContain('주변 쇼핑'))
    await vi.waitFor(() => expect(bodyText()).toContain('주변 명소'))
    await new Promise((r) => setTimeout(r, 20))
    expect(bodyText()).not.toContain('주변 식당·카페')
  })

  it('편의시설 이름은 displayName ?? name, 영어 화면이고 displayName 이 없으면 nameRoman 을 작게', async () => {
    chooseLanguage('en')
    withCart(ITEMS)
    nearby.food = [poi(21, 'food', '명동교자', { displayName: null, nameRoman: 'Myeongdong Gyoja' })]
    renderCover()
    await vi.waitFor(() => expect(bodyText()).toContain('명동교자'))
    expect(bodyText()).toContain('Myeongdong Gyoja')
  })

  it('한국어 화면에서는 nameRoman 을 보이지 않는다', async () => {
    nearby.food = [poi(21, 'food', '명동교자', { displayName: null, nameRoman: 'Myeongdong Gyoja' })]
    renderCover()
    await vi.waitFor(() => expect(bodyText()).toContain('명동교자'))
    expect(bodyText()).not.toContain('Myeongdong Gyoja')
  })
})

describe('주변 — 담기·빼기', () => {
  it('칸의 ＋ → addPoi + add_more(poi), 칸이 담김(aria-pressed)', async () => {
    renderCover()
    await vi.waitFor(() => expect(queryAddButton('경복궁')).not.toBeNull())
    fireEvent.click(addButton('경복궁'))
    await vi.waitFor(() => expect(addPoi).toHaveBeenCalledTimes(1))
    expect(addPoi.mock.calls[0]![0]).toMatchObject({ id: 11 })
    await vi.waitFor(() => expect(has('add_more', { action: 'poi' })).toBe(true))
    await vi.waitFor(() => expect(addButton('경복궁').getAttribute('aria-pressed')).toBe('true'))
  })

  // §10-3-12 — 편의시설 칸은 담기만. (옛 시험 「한 번 더 누르면 removePoi」 는 명세가 바뀌었다.)
  it('한 번 더 눌러도 빼지 않는다 — removePoi·remove 를 부르지 않고 담김 그대로', async () => {
    const remove = vi.spyOn(cartActions, 'remove').mockResolvedValue()
    renderCover()
    await vi.waitFor(() => expect(queryAddButton('명동교자')).not.toBeNull())
    fireEvent.click(addButton('명동교자'))
    await vi.waitFor(() => expect(addButton('명동교자').getAttribute('aria-pressed')).toBe('true'))
    fireEvent.click(addButton('명동교자'))
    await new Promise((r) => setTimeout(r, 20))
    expect(removePoi).not.toHaveBeenCalled()
    expect(remove).not.toHaveBeenCalled()
    expect(addButton('명동교자').getAttribute('aria-pressed')).toBe('true')
  })

  it('촬영지와 연결된 편의시설 칸을 다시 눌러도 그 촬영지를 빼지 않는다', async () => {
    const remove = vi.spyOn(cartActions, 'remove').mockResolvedValue()
    // 경복궁(편의시설 11)이 담긴 촬영지 201(낙산공원 자리)과 같은 곳 — 칸이 처음부터 담김으로 보인다.
    nearby.sight = [poi(11, 'sight', '경복궁', { placeId: 201 })]
    renderCover()
    await vi.waitFor(() => expect(queryAddButton('경복궁')).not.toBeNull())
    await vi.waitFor(() => expect(addButton('경복궁').getAttribute('aria-pressed')).toBe('true'))
    fireEvent.click(addButton('경복궁'))
    await new Promise((r) => setTimeout(r, 20))
    expect(remove).not.toHaveBeenCalled()
    expect(removePoi).not.toHaveBeenCalled()
    expect(addButton('경복궁').getAttribute('aria-pressed')).toBe('true')
  })

  it('이미 담은 편의시설은 처음부터 담김으로 보인다', async () => {
    const saved = { ...poi(12, 'sight', '창덕궁'), addedAt: '2026-11-14T00:00:00Z', closed: false } as unknown as CartPoi
    withCart(ITEMS, [saved])
    renderCover()
    await vi.waitFor(() => expect(queryAddButton('창덕궁')).not.toBeNull())
    expect(addButton('창덕궁').getAttribute('aria-pressed')).toBe('true')
  })

  it('줄 끝 「지도에서 더 보기」 → 그 칩이 켜진 내 지도', async () => {
    renderCover()
    await vi.waitFor(() => expect(screen.queryAllByText('지도에서 더 보기').length).toBeGreaterThanOrEqual(1))
    // 줄 순서: 쇼핑 → 명소 → 식당·카페
    fireEvent.click(screen.getAllByText('지도에서 더 보기')[0]!.closest('button')!)
    await vi.waitFor(() => expect(location?.pathname).toBe('/my-map'))
    expect(keepStore.get().nearby).toBe('shopping')
  })
})

describe('주변 — 실패', () => {
  it.each<[Lang, string, string]>([
    ['ko', '불러오지 못했어요', '다시 시도'],
    ['en', "Couldn't load", 'Try again'],
  ])('%s — 줄 받기에 실패하면 「%s」 + 「%s」, 누르면 다시 받는다', async (lang, failed, retry) => {
    chooseLanguage(lang)
    withCart(ITEMS)
    failOnce.add('shopping')
    renderCover()
    await vi.waitFor(() => expect(bodyText()).toContain(failed))
    const before = poisQueries().filter((q) => q.categories === '쇼핑').length
    fireEvent.click(screen.getByRole('button', { name: retry }))
    await vi.waitFor(() => expect(poisQueries().filter((q) => q.categories === '쇼핑').length).toBeGreaterThan(before))
    await vi.waitFor(() => expect(bodyText()).toContain('롯데영플라자'))
  })
})

// ---------------------------------------------------------------- 아래 단추

describe('아래 단추', () => {
  it.each<[Lang, string, string]>([
    ['ko', '이 3곳으로 여행 일정 짜기', '내 지도 보기'],
    ['en', 'Plan my trip with 3 spots', 'See my map'],
  ])('%s — 주 단추·보조 단추 문구', async (lang, plan, see) => {
    chooseLanguage(lang)
    withCart(ITEMS)
    renderCover()
    await vi.waitFor(() => expect(buttonWith(plan)).toBeTruthy())
    expect(buttonWith(see)).toBeTruthy()
  })

  it.each<[Lang, string]>([
    ['ko', '이 1곳으로 여행 일정 짜기'],
    ['en', 'Plan my trip with 1 spot'],
  ])('%s — 1곳이면 단수', async (lang, plan) => {
    chooseLanguage(lang)
    withCart([ITEMS[0]!])
    renderCover()
    await vi.waitFor(() => expect(buttonWith(plan)).toBeTruthy())
  })

  it('일정 짜기 → add_more(plan), 간직하기(②) 부터 연다', async () => {
    renderCover()
    await vi.waitFor(() => expect(buttonWith('이 3곳으로 여행 일정 짜기')).toBeTruthy())
    fireEvent.click(buttonWith('이 3곳으로 여행 일정 짜기'))
    await vi.waitFor(() => expect(has('add_more', { action: 'plan' })).toBe(true))
    expect(keepStore.get().open).toBe(true)
    expect(keepStore.get().step).toBe('keep')
  })

  it('홈 화면에 설치된 환경이면 ③ 계획부터', async () => {
    h.env.value = 'installed'
    renderCover()
    await vi.waitFor(() => expect(buttonWith('이 3곳으로 여행 일정 짜기')).toBeTruthy())
    fireEvent.click(buttonWith('이 3곳으로 여행 일정 짜기'))
    await vi.waitFor(() => expect(keepStore.get().open).toBe(true))
    expect(keepStore.get().step).toBe('plan')
  })

  it('「내 지도 보기」 → /my-map', async () => {
    renderCover()
    await vi.waitFor(() => expect(buttonWith('내 지도 보기')).toBeTruthy())
    fireEvent.click(buttonWith('내 지도 보기'))
    await vi.waitFor(() => expect(location?.pathname).toBe('/my-map'))
  })
})

// ---------------------------------------------------------------- §3 앱 안 브라우저 띠

describe('앱 안 브라우저 띠', () => {
  it.each(['in_app_ios', 'in_app_android'])('%s — 「이 창을 닫으면 사라져요」 + 「브라우저에서 열기」 → handoff + keep_prompt(env, open_browser)', async (env) => {
    h.env.value = env
    renderCover()
    await vi.waitFor(() => expect(bodyText()).toContain('이 창을 닫으면 사라져요'))
    fireEvent.click(buttonWith('브라우저에서 열기'))
    await vi.waitFor(() => expect(h.handoffOpen).toHaveBeenCalledTimes(1))
    expect(has('keep_prompt', { env, action: 'open_browser' })).toBe(true)
  })

  it('앱 안 브라우저가 아니면 띠가 없다', async () => {
    h.env.value = 'other'
    renderCover()
    await vi.waitFor(() => expect(bodyText()).toContain('내 지도 · 3곳'))
    expect(bodyText()).not.toContain('이 창을 닫으면 사라져요')
  })
})
