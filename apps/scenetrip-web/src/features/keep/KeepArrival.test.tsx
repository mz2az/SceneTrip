/*
 * 넘어온 사람 — `?keep=`. 정본: docs/project/plans/web-keep-flow.md §5-4.
 *
 *   KeepArrival(): 앱이 뜰 때 주소에 keep 이 있으면 작품마다 촬영지를 받아 addMany 로 담고,
 *   주소에서 keep 을 지우고 /my-map 을 연다. keep_arrive(작품 수).
 *   앱 안 브라우저가 아니면 시트를 ② keep 에서 연다. 앱 안 브라우저면 열지 않는다.
 *
 * §9-4: 담는 동안 keepStore.arriving 이 true, 끝나면(실패해도) false. 한 편도 못 담으면 시트를 열지 않고, 시트의 contentIds 는 담긴 작품만.
 *
 * 앱 안 브라우저 여부는 navigator.userAgent 로 정한다(시험이 바꾼다). 주소는 라우터(MemoryRouter)와
 * window.location 양쪽에 같은 값을 둔다 — 어느 쪽을 읽어도 같은 결과가 나와야 한다.
 */
import { cleanup, render } from '@testing-library/react'
import { MemoryRouter, useLocation } from 'react-router'
import { type MockInstance, afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const h = vi.hoisted(() => ({ GET: vi.fn() }))

vi.mock('@/api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/client')>()),
  api: { GET: (...args: unknown[]) => h.GET(...args), POST: vi.fn(), DELETE: vi.fn() },
}))

vi.mock('@/analytics', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/analytics')>()
  return { ...actual, track: vi.fn() }
})

import { track, webEvent } from '@/analytics'
import type { components } from '@/api/generated/schema'
import { paths } from '@/app/routes'
import { chooseLanguage } from '@/i18n'
import { cartActions, cartStore } from '@/state/cart'
import { KeepArrival } from './KeepArrival'
import { keepStore } from './keepStore'

type PlaceSummary = components['schemas']['PlaceSummary']

const CHROME_ANDROID = 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36'
const FB_ANDROID =
  'Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/157.0.0.0 Mobile Safari/537.36 [FB_IAB/FB4A;FBAV/586.0.0.52.53;IABMV/1;]'
const IG_IPHONE =
  'Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148 Instagram 350.0.0.0 (iPhone15,2; iOS 18_1; en_US)'

const respond = (status: number, data?: unknown) => ({
  data,
  error: status >= 400 ? { code: 'INTERNAL', message: 'x' } : undefined,
  response: { ok: status >= 200 && status < 300, status, headers: new Headers() },
})
const place = (id: number): PlaceSummary => ({ id, name: `장소${id}`, latitude: 37.5, longitude: 127 }) as PlaceSummary

const PLACES: Record<number, PlaceSummary[]> = {
  32: [place(101), place(102)],
  25: [place(201), place(202), place(203)],
}

let location: ReturnType<typeof useLocation> | null = null
function Probe() {
  location = useLocation()
  return null
}

function arrive(entry: string) {
  window.history.replaceState(null, '', entry)
  return render(
    <MemoryRouter initialEntries={[entry]}>
      <KeepArrival />
      <Probe />
    </MemoryRouter>,
  )
}

const placeCalls = () => h.GET.mock.calls.filter((c) => c[0] === '/contents/{contentId}/places')
const events = () => vi.mocked(track).mock.calls.map(([e]) => e)

let addMany: MockInstance<typeof cartActions.addMany>

function setUserAgent(ua: string) {
  vi.spyOn(navigator, 'userAgent', 'get').mockReturnValue(ua)
}

beforeEach(() => {
  chooseLanguage('ko')
  location = null
  setUserAgent(CHROME_ANDROID)
  h.GET.mockReset()
  h.GET.mockImplementation(async (path: string, init?: { params?: { path?: { contentId?: number } } }) => {
    if (path === '/contents/{contentId}/places') {
      const items = PLACES[init?.params?.path?.contentId ?? -1] ?? []
      return respond(200, { items, total: items.length, limit: 100, offset: 0 })
    }
    if (path === '/cart') return respond(200, { items: [] })
    return respond(200, { items: [], total: 0, limit: 20, offset: 0 })
  })
  vi.mocked(track).mockClear()
  cartStore.set({ loadState: 'loaded', placeIds: new Set(), items: [], toast: null })
  keepStore.set({ open: false, step: 'more', contentIds: [], title: null, arriving: false })
  addMany = vi.spyOn(cartActions, 'addMany').mockImplementation(async (ids) => ({ saved: ids.length, already: 0, failed: 0 }))
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  window.history.replaceState(null, '', '/')
})

describe('KeepArrival — ?keep=32,25 로 뜨면', () => {
  it('작품마다 촬영지를 받아 addMany 로 담는다', async () => {
    arrive('/?keep=32,25')
    await vi.waitFor(() => expect(addMany).toHaveBeenCalledTimes(2))
    expect(placeCalls().map((c) => (c[1] as { params: { path: { contentId: number } } }).params.path.contentId).sort()).toEqual([25, 32])
    // 네 번째 인자는 출처 — 넘어온 사람은 'arrive'(§9-3).
    const calls = addMany.mock.calls.map((c) => [c[0], c[1], c[3]])
    expect(calls).toContainEqual([[101, 102], 32, 'arrive'])
    expect(calls).toContainEqual([[201, 202, 203], 25, 'arrive'])
  })

  it('keep_arrive(작품 수) 를 보낸다', async () => {
    arrive('/?keep=32,25')
    await vi.waitFor(() => expect(events()).toContainEqual(webEvent.keepArrive(2)))
  })

  it('주소에서 keep 을 지우고 /my-map 을 연다', async () => {
    arrive('/?keep=32,25')
    await vi.waitFor(() => expect(location?.pathname).toBe(paths.myMap))
    expect(new URLSearchParams(location?.search ?? '').has('keep')).toBe(false)
  })

  it('다른 쿼리(utm_*)가 섞여 있어도 keep 을 읽는다', async () => {
    arrive('/?utm_source=facebook&keep=32,25&utm_campaign=kpop')
    await vi.waitFor(() => expect(addMany).toHaveBeenCalledTimes(2))
    await vi.waitFor(() => expect(location?.pathname).toBe(paths.myMap))
    expect(new URLSearchParams(location?.search ?? '').has('keep')).toBe(false)
  })

  it('앱 안 브라우저가 아니면 담은 뒤 시트를 ② keep 에서 그 작품들로 연다', async () => {
    arrive('/?keep=32,25')
    await vi.waitFor(() => expect(keepStore.get().open).toBe(true))
    expect(keepStore.get().step).toBe('keep')
    expect(keepStore.get().contentIds).toEqual([32, 25])
  })

  it.each([
    ['페이스북 안드로이드', FB_ANDROID],
    ['인스타 아이폰', IG_IPHONE],
  ])('앱 안 브라우저(%s)면 시트를 열지 않는다 — 담기와 이동은 한다', async (_, ua) => {
    setUserAgent(ua)
    arrive('/?keep=32,25')
    await vi.waitFor(() => expect(addMany).toHaveBeenCalledTimes(2))
    await vi.waitFor(() => expect(location?.pathname).toBe(paths.myMap))
    await vi.waitFor(() => expect(events()).toContainEqual(webEvent.keepArrive(2)))
    expect(keepStore.get().open).toBe(false)
  })
})

describe('KeepArrival — keep 이 없으면', () => {
  it.each(['/', '/?utm_source=facebook', '/?keep='])('%s — 아무것도 하지 않는다', async (entry) => {
    arrive(entry)
    // 혹시 늦게 도는 일이 있으면 드러나게 조금 기다린다.
    await new Promise((r) => setTimeout(r, 20))
    expect(placeCalls()).toEqual([])
    expect(addMany).not.toHaveBeenCalled()
    expect(events().filter((e) => e.name === 'keep_arrive')).toEqual([])
    expect(location?.pathname).toBe('/')
    expect(keepStore.get().open).toBe(false)
  })
})

// ── §9-4 넘어오는 중
/** keepStore.arriving 이 바뀐 차례를 적는다. */
function recordArriving(): { values: boolean[]; stop: () => void } {
  const values: boolean[] = []
  let last = keepStore.get().arriving
  const stop = keepStore.subscribe(() => {
    const next = keepStore.get().arriving
    if (next !== last) values.push(next)
    last = next
  })
  return { values, stop }
}

describe('KeepArrival — 넘어오는 중(arriving)', () => {
  it('담는 동안 true, 끝나면 false', async () => {
    const release: Array<() => void> = []
    addMany.mockImplementation(
      (ids) => new Promise((resolve) => release.push(() => resolve({ saved: ids.length, already: 0, failed: 0 }))),
    )
    const rec = recordArriving()
    arrive('/?keep=32,25')
    await vi.waitFor(() => expect(addMany).toHaveBeenCalled())
    expect(keepStore.get().arriving).toBe(true)
    // 남은 담기를 모두 끝낸다(하나씩 부르든 함께 부르든).
    await vi.waitFor(() => {
      release.splice(0).forEach((r) => r())
      expect(addMany).toHaveBeenCalledTimes(2)
    })
    release.splice(0).forEach((r) => r())
    await vi.waitFor(() => expect(keepStore.get().arriving).toBe(false))
    expect(rec.values[0]).toBe(true)
    expect(rec.values.at(-1)).toBe(false)
    rec.stop()
  })

  it('촬영지를 못 받아도 끝나면 false — 그리고 한 편도 못 담았으니 시트를 열지 않는다', async () => {
    h.GET.mockImplementation(async () => respond(500))
    const rec = recordArriving()
    arrive('/?keep=32,25')
    await vi.waitFor(() => expect(rec.values).toContain(true))
    await vi.waitFor(() => expect(keepStore.get().arriving).toBe(false))
    expect(keepStore.get().open).toBe(false)
    rec.stop()
  })

  it('addMany 가 던져도 끝나면 false', async () => {
    addMany.mockRejectedValue(new Error('boom'))
    const rec = recordArriving()
    arrive('/?keep=32,25')
    await vi.waitFor(() => expect(rec.values).toContain(true))
    await vi.waitFor(() => expect(keepStore.get().arriving).toBe(false))
    expect(keepStore.get().open).toBe(false)
    rec.stop()
  })

  it('담기가 모두 실패해 한 곳도 담지 못했으면 시트를 열지 않는다', async () => {
    addMany.mockImplementation(async (ids) => ({ saved: 0, already: 0, failed: ids.length }))
    const rec = recordArriving()
    arrive('/?keep=32,25')
    await vi.waitFor(() => expect(addMany).toHaveBeenCalledTimes(2))
    await vi.waitFor(() => expect(keepStore.get().arriving).toBe(false))
    expect(rec.values).toContain(true)
    expect(keepStore.get().open).toBe(false)
    rec.stop()
  })

  it('한 작품의 촬영지를 못 받으면 시트의 contentIds 는 담긴 작품만', async () => {
    const base = h.GET.getMockImplementation()!
    h.GET.mockImplementation(async (path: string, init?: { params?: { path?: { contentId?: number } } }) =>
      path === '/contents/{contentId}/places' && init?.params?.path?.contentId === 25 ? respond(500) : base(path, init),
    )
    arrive('/?keep=32,25')
    await vi.waitFor(() => expect(keepStore.get().open).toBe(true))
    expect(keepStore.get().contentIds).toEqual([32])
  })

  it('한 작품의 담기가 모두 실패하면 시트의 contentIds 는 담긴 작품만', async () => {
    addMany.mockImplementation(async (ids, contentId) =>
      contentId === 32 ? { saved: 0, already: 0, failed: ids.length } : { saved: ids.length, already: 0, failed: 0 },
    )
    arrive('/?keep=32,25')
    await vi.waitFor(() => expect(keepStore.get().open).toBe(true))
    expect(keepStore.get().contentIds).toEqual([25])
  })

  it('이미 다 담겨 있던 작품(already)도 담긴 작품으로 친다', async () => {
    addMany.mockImplementation(async (ids) => ({ saved: 0, already: ids.length, failed: 0 }))
    arrive('/?keep=32,25')
    await vi.waitFor(() => expect(keepStore.get().open).toBe(true))
    expect(keepStore.get().contentIds).toEqual([32, 25])
  })
})
