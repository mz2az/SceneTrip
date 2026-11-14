/*
 * 마이맵 덮개 `/my-map` — 정본: docs/project/plans/web-keep-flow.md §5-3 · §6.
 *
 *   MyMapCover({ onClose }): 담은 곳은 useCart()/cartStore. 머리줄 「마이맵 · N곳」, 위는 지도, 아래는 작품별 묶음 목록.
 *   줄을 누르면 my_map(open_place). 주 단추 「이 N곳으로 계획 짜기」 → my_map(plan) + plan_start(my_map) + 담은 곳으로 계획.
 *   「더 담기」 → /add + my_map(add_more)(web-ad-landing.md §2 가 /trending 에서 바꿨다). 비었음·실패 안내.
 *
 * 지도 부품(@/features/search/map/MapView)은 jsdom 에서 뜨지 않아 빈 부품으로 바꾼다.
 * 장바구니는 스토어에 미리 넣고, GET /cart 도 같은 값을 돌려준다 — 덮개가 열릴 때 다시 받아도 같은 결과다.
 */
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, useLocation } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const h = vi.hoisted(() => ({ GET: vi.fn(), DELETE: vi.fn() }))

vi.mock('@/api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/client')>()),
  api: { GET: (...args: unknown[]) => h.GET(...args), POST: vi.fn(), DELETE: (...args: unknown[]) => h.DELETE(...args) },
}))

vi.mock('@/analytics', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/analytics')>()
  return { ...actual, track: vi.fn() }
})

vi.mock('@/features/search/map/MapView', () => ({ MapView: () => null }))

import { track, webEvent } from '@/analytics'
import { paths } from '@/app/routes'
import { pendingStore } from '@/app/tabRouter'
import { chooseLanguage } from '@/i18n'
import { type CartItem, cartStore } from '@/state/cart'
import { keepActions } from './keepStore'
import { MyMapCover } from './MyMapCover'

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

const ITEMS: CartItem[] = [
  item({ name: '남산서울타워', sourceContentId: 32, sourceContentTitle: '케이팝 데몬 헌터스', latitude: 37.55, longitude: 126.99, address: '서울 용산구' }),
  item({ name: '덕수궁 돌담길', sourceContentId: 7, sourceContentTitle: '도깨비', latitude: 37.56, longitude: 126.97 }),
  item({ name: '낙산공원', sourceContentId: 32, sourceContentTitle: '케이팝 데몬 헌터스', latitude: 37.58, longitude: 127.0 }),
  item({ name: '출처 없는 카페', sourceContentId: null, latitude: 37.5, longitude: 127.02 }),
  item({ name: '좌표 없는 골목', sourceContentId: 7, sourceContentTitle: '도깨비' }),
]

/** GET /cart 가 돌려줄 것 — 'fail' 이면 500. */
let cartReply: CartItem[] | 'fail'

let location: ReturnType<typeof useLocation> | null = null
function Probe() {
  location = useLocation()
  return null
}

function renderCover(onClose = vi.fn()) {
  const view = render(
    <MemoryRouter initialEntries={[paths.myMap]}>
      <MyMapCover onClose={onClose} />
      <Probe />
    </MemoryRouter>,
  )
  return { ...view, onClose }
}

function withCart(items: CartItem[]) {
  cartReply = items
  cartStore.set({ loadState: 'loaded', placeIds: new Set(items.map((i) => i.placeId)), items, pois: [], poiIds: new Set(), toast: null })
}

const events = () => vi.mocked(track).mock.calls.map(([e]) => e)
const bodyText = () => document.body.textContent ?? ''
const cartGets = () => h.GET.mock.calls.filter((c) => c[0] === '/cart').length

function buttonWith(text: string): HTMLElement {
  const button = screen.getByText(text).closest('button')
  expect(button, `「${text}」 단추`).not.toBeNull()
  return button as HTMLElement
}

beforeEach(() => {
  // 언어를 먼저 정하고 나서 장바구니를 채운다 — 언어가 바뀌면 cart.ts 가 다시 받기 때문이다.
  cartStore.set({ loadState: 'notLoaded', placeIds: new Set(), items: [], pois: [], poiIds: new Set(), toast: null })
  chooseLanguage('ko')
  location = null
  h.GET.mockReset()
  h.DELETE.mockReset()
  h.DELETE.mockImplementation(async () => respond(204))
  h.GET.mockImplementation(async (path: string) => {
    if (path === '/cart') return cartReply === 'fail' ? respond(500) : respond(200, { items: cartReply })
    return respond(200, { items: [], total: 0, limit: 20, offset: 0 })
  })
  vi.mocked(track).mockClear()
  pendingStore.set((s) => ({ ...s, plannerManual: false }))
  keepActions.setArriving(false)
  withCart(ITEMS)
})

afterEach(() => {
  cleanup()
  cartStore.set({ loadState: 'notLoaded', placeIds: new Set(), items: [], pois: [], poiIds: new Set(), toast: null })
  chooseLanguage('ko')
})

describe('MyMapCover — 머리줄', () => {
  it('「마이맵 · N곳」', async () => {
    renderCover()
    await vi.waitFor(() => expect(bodyText()).toContain('마이맵 · 5곳'))
  })

  it('영어 — My map · N spots', async () => {
    cartStore.set({ loadState: 'notLoaded', placeIds: new Set(), items: [], pois: [], poiIds: new Set(), toast: null })
    chooseLanguage('en')
    withCart(ITEMS)
    renderCover()
    await vi.waitFor(() => expect(bodyText()).toContain('My map · 5 spots'))
  })
})

describe('MyMapCover — 작품별 묶음 목록', () => {
  it('묶음은 작품이 처음 나온 순서, 출처 없는 것은 맨 뒤 — 줄마다 이름', async () => {
    renderCover()
    await vi.waitFor(() => expect(bodyText()).toContain('남산서울타워'))
    const text = bodyText()
    const at = (s: string) => {
      const i = text.indexOf(s)
      expect(i, s).toBeGreaterThanOrEqual(0)
      return i
    }
    // 케이팝 데몬 헌터스 묶음(남산서울타워·낙산공원) → 도깨비 묶음(덕수궁 돌담길·좌표 없는 골목) → 출처 없음(출처 없는 카페)
    expect(at('남산서울타워')).toBeLessThan(at('덕수궁 돌담길'))
    expect(at('낙산공원')).toBeLessThan(at('덕수궁 돌담길'))
    expect(at('좌표 없는 골목')).toBeLessThan(at('출처 없는 카페'))
    expect(at('덕수궁 돌담길')).toBeLessThan(at('출처 없는 카페'))
    // 작품 제목이 묶음 머리에 보인다
    expect(at('케이팝 데몬 헌터스')).toBeLessThan(at('남산서울타워'))
    expect(at('도깨비')).toBeLessThan(at('덕수궁 돌담길'))
  })

  it('좌표 없는 항목도 목록에는 보인다', async () => {
    renderCover()
    await vi.waitFor(() => expect(bodyText()).toContain('좌표 없는 골목'))
  })

  it('줄을 누르면 my_map(open_place)', async () => {
    renderCover()
    await vi.waitFor(() => expect(bodyText()).toContain('덕수궁 돌담길'))
    const row = screen.getByText('덕수궁 돌담길').closest('button, a, [role="button"]')
    expect(row, '줄은 누를 수 있다').not.toBeNull()
    fireEvent.click(row as HTMLElement)
    expect(events()).toContainEqual(webEvent.myMap('open_place'))
  })
})

describe('MyMapCover — 아래 단추', () => {
  it('「이 N곳으로 계획 짜기」 → my_map(plan) + plan_start(my_map), 담은 곳으로 계획(쪽지 plannerManual)', async () => {
    renderCover()
    await vi.waitFor(() => expect(screen.getByText('이 5곳으로 계획 짜기')).toBeTruthy())
    fireEvent.click(buttonWith('이 5곳으로 계획 짜기'))
    expect(events()).toContainEqual(webEvent.myMap('plan'))
    expect(events()).toContainEqual(webEvent.planStart('my_map'))
    expect(pendingStore.get().plannerManual).toBe(true)
    expect(location?.pathname).toBe(paths.courses)
  })

  it('영어 — Plan a trip with these N spots', async () => {
    cartStore.set({ loadState: 'notLoaded', placeIds: new Set(), items: [], pois: [], poiIds: new Set(), toast: null })
    chooseLanguage('en')
    withCart(ITEMS)
    renderCover()
    await vi.waitFor(() => expect(buttonWith('Plan a trip with these 5 spots')).toBeTruthy())
  })

  // web-ad-landing.md §2 — 마이맵의 「더 담기」 도 「더 담기」 덮개(/add)로 온다.
  it('「더 담기」 → /add + my_map(add_more)', async () => {
    renderCover()
    await vi.waitFor(() => expect(screen.getByText('더 담기')).toBeTruthy())
    fireEvent.click(buttonWith('더 담기'))
    expect(events()).toContainEqual(webEvent.myMap('add_more'))
    expect(location?.pathname).toBe(paths.addMore)
  })
})

describe('MyMapCover — 비었음 · 실패', () => {
  it('담은 곳이 없으면 「아직 담은 곳이 없어요」 + 「작품 둘러보기」 → /trending', async () => {
    withCart([])
    renderCover()
    await vi.waitFor(() => expect(bodyText()).toContain('아직 담은 곳이 없어요'))
    fireEvent.click(buttonWith('작품 둘러보기'))
    expect(location?.pathname).toBe(paths.trending)
  })

  it('못 받으면 「못 불러왔어요」 + 다시 시도 → 다시 받는다', async () => {
    cartReply = 'fail'
    cartStore.set({ loadState: 'failed', placeIds: new Set(), items: [], pois: [], poiIds: new Set(), toast: null })
    renderCover()
    await vi.waitFor(() => expect(bodyText()).toContain('못 불러왔어요'))
    const before = cartGets()
    fireEvent.click(buttonWith('다시 시도'))
    await vi.waitFor(() => expect(cartGets()).toBeGreaterThan(before))
  })
})

// §9-4 — 넘어오는 중(keepStore.arriving)에는 빈 화면 대신 받는 중.
describe('MyMapCover — 넘어오는 중', () => {
  it('arriving 이면 담은 곳이 아직 없어도 「아직 담은 곳이 없어요」 를 보이지 않는다 — 끝나면 보인다', async () => {
    withCart([])
    keepActions.setArriving(true)
    renderCover()
    await new Promise((r) => setTimeout(r, 30))
    expect(bodyText()).not.toContain('아직 담은 곳이 없어요')
    act(() => keepActions.setArriving(false))
    await vi.waitFor(() => expect(bodyText()).toContain('아직 담은 곳이 없어요'))
  })
})

// §9-7 — 영어는 1이면 단수.
describe('MyMapCover — 영어 단수', () => {
  it('1곳이면 「My map · 1 spot」, 어디에도 「1 spots」 가 없다', async () => {
    cartStore.set({ loadState: 'notLoaded', placeIds: new Set(), items: [], pois: [], poiIds: new Set(), toast: null })
    chooseLanguage('en')
    withCart([ITEMS[0]!])
    renderCover()
    await vi.waitFor(() => expect(bodyText()).toContain('My map · 1 spot'))
    expect(bodyText()).not.toContain('1 spots')
  })
})

/*
 * 옛 장바구니 시트(작품검색 탭·마이페이지)에만 있던 「빼기」 를 마이맵 촬영지 줄로 옮겼다(web-keep-flow.md §11-1).
 * 목록에서 빠진 촬영지(누를 수 없는 흐린 줄)도 뺄 수 있다 — 옛 시트가 그랬다.
 */
describe('MyMapCover — 촬영지 줄 빼기(§11-1)', () => {
  it('줄마다 「마이맵에서 빼기」 가 있고, 누르면 DELETE /cart/items/{placeId}', async () => {
    renderCover()
    await vi.waitFor(() => expect(screen.getAllByRole('button', { name: '마이맵에서 빼기' })).toHaveLength(ITEMS.length))
    const row = screen.getByText('덕수궁 돌담길').closest('[data-place-row]') as HTMLElement
    const remove = Array.from(row.querySelectorAll('button')).find((b) => b.getAttribute('aria-label') === '마이맵에서 빼기')!
    await act(async () => {
      fireEvent.click(remove)
    })
    const placeId = ITEMS.find((i) => i.name === '덕수궁 돌담길')!.placeId
    expect(h.DELETE).toHaveBeenCalledWith('/cart/items/{placeId}', expect.objectContaining({ params: expect.objectContaining({ path: { placeId } }) }))
  })

  it('빼기를 눌러도 장소 상세로 가지 않는다(줄 누르기와 따로)', async () => {
    renderCover()
    await vi.waitFor(() => expect(screen.getAllByRole('button', { name: '마이맵에서 빼기' }).length).toBeGreaterThan(0))
    await act(async () => {
      fireEvent.click(screen.getAllByRole('button', { name: '마이맵에서 빼기' })[0]!)
    })
    expect(location?.pathname).toBe(paths.myMap)
    expect(events()).not.toContainEqual(webEvent.myMap('open_place'))
  })

  it('목록에서 빠진 촬영지도 뺄 수 있다', async () => {
    const gone = item({ name: '빠진 촬영지', sourceContentId: 7, sourceContentTitle: '도깨비', latitude: 37.57, longitude: 126.98, delisted: true })
    withCart([...ITEMS, gone])
    renderCover()
    await vi.waitFor(() => expect(screen.getByText('빠진 촬영지')).toBeTruthy())
    expect(screen.getAllByRole('button', { name: '마이맵에서 빼기' })).toHaveLength(ITEMS.length + 1)
    // 흐린 줄은 누를 수 없다 — 그 줄을 감싼 칸에서 빼기 단추만 찾는다.
    let line: HTMLElement | null = screen.getByText('빠진 촬영지')
    while (line && !line.querySelector('button[aria-label="마이맵에서 빼기"]')) line = line.parentElement
    expect(line?.closest('button'), '흐린 줄 자체는 단추가 아니다').toBeNull()
    await act(async () => {
      fireEvent.click(line!.querySelector('button[aria-label="마이맵에서 빼기"]')!)
    })
    expect(h.DELETE).toHaveBeenCalledWith('/cart/items/{placeId}', expect.objectContaining({ params: expect.objectContaining({ path: { placeId: gone.placeId } }) }))
  })

  it('영어 — Remove from My map', async () => {
    chooseLanguage('en')
    withCart(ITEMS)
    renderCover()
    await vi.waitFor(() => expect(screen.getAllByRole('button', { name: 'Remove from My map' })).toHaveLength(ITEMS.length))
  })
})
