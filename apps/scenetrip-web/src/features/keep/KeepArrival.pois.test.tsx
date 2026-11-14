/*
 * 넘어온 사람 — `?pois=`. 정본: docs/project/plans/keep-poi.md §3-6 · §4 (옛 흐름은 web-keep-flow.md §5-4).
 *
 *   ?pois=7,9 로 뜨면 KeepArrival 이 하나씩 cartActions.addPoi 로 담는다(이미 담긴 것·없는 것은 건너뛴다).
 *   keep_arrive(count, poi_count) — count 는 그대로 작품 수, poi_count 는 담긴 편의시설 수.
 *   이 시험은 「담긴」 = addPoi 가 'saved' 를 돌려준 것으로 본다('already'·'failed' 는 세지 않는다).
 *
 * 띄우는 방법은 KeepArrival.test.tsx 와 같다.
 */
import { cleanup, render } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
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
import { chooseLanguage } from '@/i18n'
import { cartActions, cartStore } from '@/state/cart'
import { KeepArrival } from './KeepArrival'
import { keepStore } from './keepStore'

type PlaceSummary = components['schemas']['PlaceSummary']

const CHROME_ANDROID = 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36'

const respond = (status: number, data?: unknown) => ({
  data,
  error: status >= 400 ? { code: 'INTERNAL', message: 'x' } : undefined,
  response: { ok: status >= 200 && status < 300, status, headers: new Headers() },
})
const place = (id: number): PlaceSummary => ({ id, name: `장소${id}`, latitude: 37.5, longitude: 127 }) as PlaceSummary

const PLACES: Record<number, PlaceSummary[]> = { 32: [place(101), place(102)] }

function arrive(entry: string) {
  window.history.replaceState(null, '', entry)
  return render(
    <MemoryRouter initialEntries={[entry]}>
      <KeepArrival />
    </MemoryRouter>,
  )
}

const events = () => vi.mocked(track).mock.calls.map(([e]) => e)
const arriveEvents = () => events().filter((e) => e.name === 'keep_arrive')

type AddPoi = (poi: { id: number; placeId?: number | null }) => Promise<'saved' | 'already' | 'failed'>
let addMany: MockInstance<typeof cartActions.addMany>
let addPoi: MockInstance<AddPoi>
/** poiId → addPoi 결과. 없으면 'saved'. */
let poiResult: Map<number, 'saved' | 'already' | 'failed'>

const addedPoiIds = () => addPoi.mock.calls.map((c) => c[0].id)

beforeEach(() => {
  chooseLanguage('ko')
  vi.spyOn(navigator, 'userAgent', 'get').mockReturnValue(CHROME_ANDROID)
  h.GET.mockReset()
  h.GET.mockImplementation(async (path: string, init?: { params?: { path?: { contentId?: number } } }) => {
    if (path === '/contents/{contentId}/places') {
      const items = PLACES[init?.params?.path?.contentId ?? -1] ?? []
      return respond(200, { items, total: items.length, limit: 100, offset: 0 })
    }
    if (path === '/cart') return respond(200, { items: [], totalCount: 0, pois: [], poiCount: 0 })
    return respond(200, { items: [], total: 0, limit: 20, offset: 0 })
  })
  vi.mocked(track).mockClear()
  cartStore.set({ loadState: 'loaded', placeIds: new Set(), items: [], pois: [], poiIds: new Set(), toast: null })
  keepStore.set({ open: false, step: 'more', contentIds: [], title: null, arriving: false, nearby: null })
  addMany = vi.spyOn(cartActions, 'addMany').mockImplementation(async (ids) => ({ saved: ids.length, already: 0, failed: 0 }))
  poiResult = new Map()
  addPoi = vi
    .spyOn(cartActions as unknown as { addPoi: AddPoi }, 'addPoi')
    .mockImplementation(async (poi) => poiResult.get(poi.id) ?? 'saved')
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  window.history.replaceState(null, '', '/')
})

describe('KeepArrival — ?pois=7,9', () => {
  it('하나씩 cartActions.addPoi 로 담는다(받은 순서)', async () => {
    arrive('/?pois=7,9')
    await vi.waitFor(() => expect(addPoi).toHaveBeenCalledTimes(2))
    expect(addedPoiIds()).toEqual([7, 9])
  })

  it('하나씩 — 앞 담기가 끝나기 전에는 다음 담기를 부르지 않는다', async () => {
    const release: Array<() => void> = []
    addPoi.mockImplementation(() => new Promise((resolve) => release.push(() => resolve('saved'))))
    arrive('/?pois=7,9')
    await vi.waitFor(() => expect(addPoi).toHaveBeenCalledTimes(1))
    await new Promise((r) => setTimeout(r, 10))
    expect(addPoi).toHaveBeenCalledTimes(1)
    release.shift()!()
    await vi.waitFor(() => expect(addPoi).toHaveBeenCalledTimes(2))
    release.shift()!()
    await vi.waitFor(() => expect(arriveEvents()).toHaveLength(1))
  })

  it('keep_arrive(0, 2) — 작품은 없고 편의시설 둘', async () => {
    arrive('/?pois=7,9')
    await vi.waitFor(() => expect(arriveEvents()).toHaveLength(1))
    expect(arriveEvents()[0]).toEqual(webEvent.keepArrive(0, 2))
  })

  it('작품이 없으니 addMany 는 부르지 않는다', async () => {
    arrive('/?pois=7,9')
    await vi.waitFor(() => expect(arriveEvents()).toHaveLength(1))
    expect(addMany).not.toHaveBeenCalled()
  })
})

describe('KeepArrival — ?keep=32&pois=7 (둘 다)', () => {
  it('작품은 addMany, 편의시설은 addPoi 로 담고 keep_arrive(1, 1)', async () => {
    arrive('/?keep=32&pois=7')
    await vi.waitFor(() => expect(arriveEvents()).toHaveLength(1))
    expect(addMany).toHaveBeenCalledTimes(1)
    expect(addMany.mock.calls[0]![0]).toEqual([101, 102])
    expect(addedPoiIds()).toEqual([7])
    expect(arriveEvents()[0]).toEqual(webEvent.keepArrive(1, 1))
  })

  it('keep_arrive 는 한 번만', async () => {
    arrive('/?keep=32&pois=7,9')
    await vi.waitFor(() => expect(addPoi).toHaveBeenCalledTimes(2))
    await new Promise((r) => setTimeout(r, 20))
    expect(arriveEvents()).toHaveLength(1)
    expect(arriveEvents()[0]).toEqual(webEvent.keepArrive(1, 2))
  })
})

describe('KeepArrival — poi_count 는 담긴 것만', () => {
  it("이미 담긴 것('already')과 실패('failed')는 세지 않는다", async () => {
    poiResult.set(8, 'already')
    poiResult.set(9, 'failed')
    arrive('/?pois=7,8,9')
    await vi.waitFor(() => expect(arriveEvents()).toHaveLength(1))
    expect(arriveEvents()[0]).toEqual(webEvent.keepArrive(0, 1))
  })

  it('실패해도 멈추지 않고 다음 것을 담는다', async () => {
    poiResult.set(7, 'failed')
    arrive('/?pois=7,9')
    await vi.waitFor(() => expect(arriveEvents()).toHaveLength(1))
    expect(addedPoiIds()).toEqual([7, 9])
    expect(arriveEvents()[0]).toEqual(webEvent.keepArrive(0, 1))
  })
})

describe('KeepArrival — pois 가 없으면', () => {
  it('?keep=32 만 — addPoi 는 부르지 않고 keep_arrive(1, 0)', async () => {
    arrive('/?keep=32')
    await vi.waitFor(() => expect(arriveEvents()).toHaveLength(1))
    expect(addPoi).not.toHaveBeenCalled()
    expect(arriveEvents()[0]).toEqual(webEvent.keepArrive(1, 0))
  })

  it.each(['/', '/?pois=', '/?pois=abc'])('%s — 아무것도 하지 않는다', async (entry) => {
    arrive(entry)
    await new Promise((r) => setTimeout(r, 20))
    expect(addPoi).not.toHaveBeenCalled()
    expect(arriveEvents()).toEqual([])
  })
})
