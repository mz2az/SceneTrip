/*
 * 편의시설 담기 — 검증 뒤 바꾼 것(화면 쪽). 정본: docs/project/plans/keep-poi.md §9.
 *
 *   §9-1  알림은 앱 맨 위 CartToast 한 곳 — role="status", 2.4초, 새 알림은 바꿔 끼우고 시간을 다시 잰다(같은 글자여도).
 *   §9-2  이름 — 제목 displayName ?? name, 그 아래 nameRoman 은 displayName 이 없고 한국어 화면이 아닐 때만(savedPoiName, SavedPoiRow).
 *   §9-4  빼는 중이면 SavedPoiRow 의 빼기 단추가 눌리지 않는다.
 *   §9-5  N 은 폐업한 편의시설·목록에서 빠진 촬영지를 뺀 수(usableCount, 홈 카드·내 지도).
 *   §9-6  칩은 눌리는 높이 44px 이상.
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

import { paths } from '@/app/routes'
import { Chip } from '@/features/route/poi/PoiChips'
import { chooseLanguage } from '@/i18n'
import { type CartItem, type CartPoi, cartActions, cartPoiBusyStore, cartStore } from '@/state/cart'
import { CART_TOAST_MS, CartToast } from './CartToast'
import { savedPoiName, usableCount } from './keepPoiRules'
import { keepActions } from './keepStore'
import { MyMapCard } from './MyMapCard'
import { MyMapCover } from './MyMapCover'
import { NearbyChips } from './NearbyChips'
import { SavedPoiRow } from './SavedPoiRow'

const respond = (status: number, data?: unknown) => ({
  data,
  error: status >= 400 ? { code: 'INTERNAL', message: 'x' } : undefined,
  response: { ok: status >= 200 && status < 300, status, headers: new Headers() },
})

const item = (placeId: number, p: Partial<CartItem> = {}): CartItem => ({
  placeId,
  name: `장소${placeId}`,
  addedAt: '2026-11-14T00:00:00Z',
  latitude: 37.55,
  longitude: 126.99,
  sourceContentId: 32,
  sourceContentTitle: '케이팝 데몬 헌터스',
  ...p,
})

const poi = (id: number, p: Partial<CartPoi> = {}): CartPoi => ({
  id,
  name: `편의시설${id}`,
  category: '카페',
  categoryGroup: 'food',
  latitude: 37.551,
  longitude: 126.991,
  addedAt: '2026-11-14T00:00:00Z',
  closed: false,
  ...p,
})

const empty = () => ({ loadState: 'notLoaded' as const, placeIds: new Set<number>(), items: [], pois: [], poiIds: new Set<number>(), toast: null })

function withCart(items: CartItem[], pois: CartPoi[]) {
  cartStore.set({ loadState: 'loaded', placeIds: new Set(items.map((i) => i.placeId)), items, pois, poiIds: new Set(pois.map((p) => p.id)), toast: null })
}

beforeEach(() => {
  cartStore.set(empty())
  chooseLanguage('ko')
  cartPoiBusyStore.set(new Set())
  h.GET.mockReset()
  h.GET.mockImplementation(async (path: string) => {
    if (path === '/cart') {
      const { items, pois } = cartStore.get()
      return respond(200, { items, totalCount: items.length, pois, poiCount: pois.length })
    }
    return respond(200, { items: [], total: 0, limit: 20, offset: 0 })
  })
  keepActions.setNearby(null)
})

afterEach(() => {
  cleanup()
  vi.useRealTimers()
  vi.restoreAllMocks()
  cartStore.set(empty())
  chooseLanguage('ko')
})

describe('§9-1 CartToast', () => {
  it('알림이 오면 role="status" 로 그 글자를 보인다', () => {
    render(<CartToast />)
    expect(screen.queryByRole('status')).toBeNull()
    act(() => cartActions.notify('내 지도에 담았어요'))
    expect(screen.getByRole('status').textContent).toBe('내 지도에 담았어요')
  })

  it('2.4초 뒤 사라진다(2초에는 아직 있다)', () => {
    vi.useFakeTimers()
    render(<CartToast />)
    act(() => cartActions.notify('내 지도에 담았어요'))
    act(() => vi.advanceTimersByTime(2000))
    expect(screen.queryByRole('status')).not.toBeNull()
    act(() => vi.advanceTimersByTime(CART_TOAST_MS - 2000))
    expect(screen.queryByRole('status')).toBeNull()
    expect(CART_TOAST_MS).toBe(2400)
  })

  it('새 알림이 오면 바꿔 끼우고 시간을 다시 잰다 — 같은 글자여도', () => {
    vi.useFakeTimers()
    render(<CartToast />)
    act(() => cartActions.notify('내 지도에 담았어요'))
    act(() => vi.advanceTimersByTime(2000))
    act(() => cartActions.notify('내 지도에 담았어요'))
    act(() => vi.advanceTimersByTime(2000))
    expect(screen.queryByRole('status')).not.toBeNull()
    act(() => cartActions.notify('내 지도에서 뺐어요'))
    expect(screen.getByRole('status').textContent).toBe('내 지도에서 뺐어요')
    act(() => vi.advanceTimersByTime(CART_TOAST_MS))
    expect(screen.queryByRole('status')).toBeNull()
  })

  it('내 지도 덮개가 떠 있어도 보인다 — 덮개와 같은 앱 맨 위 층에 있다', async () => {
    withCart([item(101)], [])
    render(
      <MemoryRouter initialEntries={[paths.myMap]}>
        <MyMapCover onClose={vi.fn()} />
        <CartToast />
      </MemoryRouter>,
    )
    act(() => cartActions.notify('내 지도에 담았어요'))
    expect(screen.getAllByRole('status').some((e) => e.textContent === '내 지도에 담았어요')).toBe(true)
  })
})

describe('§9-2 이름', () => {
  it('displayName 이 있으면 제목이 그것, 로마자 없음', () => {
    expect(savedPoiName({ name: '을지면옥', displayName: 'Euljimyeonok', nameRoman: 'Euljimyeonok' }, false)).toEqual({ title: 'Euljimyeonok', roman: null })
  })

  it('displayName 이 없고 영어 화면이면 name 아래 nameRoman', () => {
    expect(savedPoiName({ name: '을지면옥', displayName: null, nameRoman: 'Euljimyeonok' }, false)).toEqual({ title: '을지면옥', roman: 'Euljimyeonok' })
  })

  it('한국어 화면이면 로마자 없음', () => {
    expect(savedPoiName({ name: '을지면옥', nameRoman: 'Euljimyeonok' }, true)).toEqual({ title: '을지면옥', roman: null })
  })

  it('이름이 원래 로마자(같은 글자)면 두 번 적지 않는다', () => {
    expect(savedPoiName({ name: 'GS25', nameRoman: 'gs25' }, false).roman).toBeNull()
  })

  it('SavedPoiRow — 영어 화면, displayName 없음 → 로마자 줄이 보인다', () => {
    chooseLanguage('en')
    const { container } = render(<SavedPoiRow poi={poi(7, { name: '을지면옥', nameRoman: 'Euljimyeonok' })} onOpen={vi.fn()} onRemove={vi.fn()} />)
    expect(container.textContent).toContain('을지면옥')
    expect(container.textContent).toContain('Euljimyeonok')
  })

  it('SavedPoiRow — 한국어 화면이면 로마자 줄이 없다', () => {
    const { container } = render(<SavedPoiRow poi={poi(7, { name: '을지면옥', nameRoman: 'Euljimyeonok' })} onOpen={vi.fn()} onRemove={vi.fn()} />)
    expect(container.textContent).not.toContain('Euljimyeonok')
  })
})

describe('§9-4 빼는 중', () => {
  it('busy 면 빼기 단추를 눌러도 onRemove 를 부르지 않는다', () => {
    const onRemove = vi.fn()
    render(<SavedPoiRow poi={poi(7)} busy onOpen={vi.fn()} onRemove={onRemove} />)
    const button = screen.getByRole('button', { name: '빼기' })
    expect((button as HTMLButtonElement).disabled).toBe(true)
    fireEvent.click(button)
    expect(onRemove).not.toHaveBeenCalled()
  })

  it('내 지도 — 그 편의시설을 빼는 중이면 줄의 빼기 단추가 눌리지 않는다', async () => {
    withCart([item(101)], [poi(7, { name: '남산 돈까스' })])
    render(
      <MemoryRouter initialEntries={[paths.myMap]}>
        <MyMapCover onClose={vi.fn()} />
      </MemoryRouter>,
    )
    await vi.waitFor(() => expect(document.body.textContent).toContain('남산 돈까스'))
    act(() => cartPoiBusyStore.set(new Set([7])))
    expect((screen.getByRole('button', { name: '빼기' }) as HTMLButtonElement).disabled).toBe(true)
  })
})

describe('§9-5 N 은 폐업·목록에서 빠진 것을 뺀 수', () => {
  it('usableCount', () => {
    expect(usableCount([item(1), item(2, { delisted: true })], [poi(7), poi(8, { closed: true })])).toBe(2)
    expect(usableCount([], [])).toBe(0)
  })

  it('홈 카드 — 「3곳」(촬영지 2 + 편의시설 1), 주변 칩도 열린 것만', () => {
    const { container } = render(
      <MyMapCard items={[item(1), item(2), item(3, { delisted: true })]} pois={[poi(7), poi(8, { closed: true })]} onOpenMap={vi.fn()} onPlan={vi.fn()} />,
    )
    expect(container.textContent).toContain('3곳')
    expect(container.textContent).toContain('주변 1')
  })

  it('홈 카드 — 폐업한 편의시설뿐이어도 카드는 뜬다', () => {
    const { container } = render(<MyMapCard items={[]} pois={[poi(8, { closed: true })]} onOpenMap={vi.fn()} onPlan={vi.fn()} />)
    expect(container.textContent).toContain('내 지도')
  })

  it('내 지도 — 머리줄과 「이 N곳으로」 의 N', async () => {
    withCart([item(101), item(102, { delisted: true })], [poi(7), poi(8, { closed: true }), poi(9)])
    render(
      <MemoryRouter initialEntries={[paths.myMap]}>
        <MyMapCover onClose={vi.fn()} />
      </MemoryRouter>,
    )
    await vi.waitFor(() => expect(document.body.textContent).toContain('내 지도 · 3곳'))
    expect(document.body.textContent).toContain('이 3곳으로 계획 짜기')
  })
})

describe('§9-6 누르는 크기', () => {
  it('칩의 눌리는 자리는 높이 44px 이상', () => {
    render(<Chip label="식당·카페" isOn={false} onTap={vi.fn()} />)
    const button = screen.getByRole('button', { name: '식당·카페' })
    expect(parseFloat(button.style.minHeight)).toBeGreaterThanOrEqual(44)
  })

  it('주변 칩 줄의 칩도 44px', () => {
    render(<NearbyChips value={null} onChange={vi.fn()} onMap />)
    for (const button of screen.getAllByRole('button')) expect(parseFloat(button.style.minHeight)).toBeGreaterThanOrEqual(44)
  })
})
