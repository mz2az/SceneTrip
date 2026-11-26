import { act, cleanup, renderHook, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.hoisted(() => {
  const env = import.meta.env as Record<string, string>
  env.VITE_API_BASE = 'http://test.local'
})

import { pendingStore } from '@/app/tabRouter'
import type { MapCameraHandle } from './map/types'
import { type ContentList, type PlaceList, type PlaceSummary, SceneData, type SceneFetchers } from './sceneData'
import { useSearchTab } from './useSearchTab'

/*
 * MZ2AZ-600 — 「현 지도 내 성지 검색」 을 누른 직후 핀이 번쩍이지 않는다. 명세만 보고 쓴 시험.
 *
 * - `SceneData` 의 `placesFrom` 은 깔린 `places` 가 무엇의 답인가다. 지도 범위 결과가 깔리면 'viewport', 검색어 결과가 깔리면 'keyword'.
 *   응답이 오기 전에는 바뀌지 않는다.
 * - `useSearchTab` 의 `mapPlaces`·`numbered` 는 범위 검색을 켠 뒤 응답이 오기 전까지 누르기 직전 그대로다.
 *   응답이 오면 `mapPlaces` 는 그 응답의 장소들이다.
 */

const place = (id: number): PlaceSummary => ({ id, name: `장소 ${id}`, latitude: 37.5 + id * 0.001, longitude: 127 }) as PlaceSummary
const placeList = (items: PlaceSummary[]): PlaceList => ({ items, total: items.length, limit: 200, offset: 0 }) as PlaceList
const contentList = (): ContentList => ({ items: [], total: 0, limit: 50, offset: 0 }) as ContentList

/** 직접 풀어 주는 약속. */
function deferred<T>() {
  let resolve!: (v: T) => void
  const promise = new Promise<T>((r) => {
    resolve = r
  })
  return { promise, resolve }
}

// ---------------------------------------------------------------- SceneData.placesFrom

describe('SceneData — placesFrom', () => {
  /** 검색어·범위 요청을 하나씩 늦춰 두고 테스트가 직접 풀어 준다. */
  function setupData() {
    const keyword: Array<ReturnType<typeof deferred<PlaceList>>> = []
    const viewport: Array<ReturnType<typeof deferred<PlaceList>>> = []
    const fetchers: SceneFetchers = {
      contents: () => Promise.resolve(contentList()),
      places: (scope) => {
        const d = deferred<PlaceList>()
        ;(scope.kind === 'viewport' ? viewport : keyword).push(d)
        return d.promise
      },
    }
    return { data: new SceneData(fetchers, vi.fn()), keyword, viewport }
  }

  it('처음과 검색어 결과가 깔린 뒤에는 keyword', async () => {
    const { data, keyword } = setupData()
    expect(data.store.get().placesFrom).toBe('keyword')
    data.search('')
    await waitFor(() => expect(keyword).toHaveLength(1))
    keyword[0].resolve(placeList([place(1), place(2)]))
    await waitFor(() => expect(data.store.get().phase.kind).toBe('loaded'))
    expect(data.store.get().placesFrom).toBe('keyword')
  })

  it('지도 범위 결과가 깔리면 viewport — 응답이 오기 전에는 바뀌지 않는다', async () => {
    const { data, keyword, viewport } = setupData()
    data.search('')
    await waitFor(() => expect(keyword).toHaveLength(1))
    keyword[0].resolve(placeList([place(1), place(2)]))
    await waitFor(() => expect(data.store.get().phase.kind).toBe('loaded'))

    data.searchInViewport('126.9,37.4,127.1,37.6', 'userSelected')
    await waitFor(() => expect(viewport).toHaveLength(1))
    expect(data.store.get().placesFrom).toBe('keyword') // 아직 응답 전

    viewport[0].resolve(placeList([place(50)]))
    await waitFor(() => expect(data.store.get().places.map((p) => p.id)).toEqual([50]))
    expect(data.store.get().placesFrom).toBe('viewport')
  })

  it('범위 결과 뒤에 검색어 결과가 깔리면 keyword 로 돌아온다 — 응답 전에는 viewport 그대로', async () => {
    const { data, keyword, viewport } = setupData()
    data.searchInViewport('126.9,37.4,127.1,37.6', 'userSelected')
    await waitFor(() => expect(viewport).toHaveLength(1))
    viewport[0].resolve(placeList([place(50)]))
    await waitFor(() => expect(data.store.get().places.map((p) => p.id)).toEqual([50]))
    expect(data.store.get().placesFrom).toBe('viewport')

    data.search('')
    await waitFor(() => expect(keyword).toHaveLength(1))
    expect(data.store.get().placesFrom).toBe('viewport') // 아직 응답 전

    keyword[0].resolve(placeList([place(1), place(2)]))
    await waitFor(() => expect(data.store.get().places.map((p) => p.id)).toEqual([1, 2]))
    expect(data.store.get().placesFrom).toBe('keyword')
  })
})

// ---------------------------------------------------------------- useSearchTab 의 지도 핀

const BBOX = '126.9,37.4,127.1,37.6'
/** 전국 목록 — 첫 화면 10곳과 「받아 둔 전부」 가 구별되도록 15곳. */
const NATIONWIDE = Array.from({ length: 15 }, (_, i) => place(i + 1))
const IN_VIEW = [place(101), place(102), place(103)]

const wrapper = ({ children }: { children: ReactNode }) => <MemoryRouter initialEntries={['/search']}>{children}</MemoryRouter>
const json = (status: number, body: unknown) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

let viewportResponses: Array<(r: Response) => void>

beforeEach(() => {
  viewportResponses = []
  pendingStore.set((s) => ({ ...s, region: null }))
  vi.stubGlobal(
    'fetch',
    vi.fn(async (req: Request) => {
      const url = new URL(req.url)
      if (url.pathname.endsWith('/places')) {
        // 지도 범위 응답은 테스트가 직접 풀어 줄 때까지 늦춘다.
        if (url.searchParams.has('bbox')) return new Promise<Response>((resolve) => viewportResponses.push(resolve))
        return json(200, { items: NATIONWIDE, total: NATIONWIDE.length, limit: 200, offset: 0 })
      }
      if (/\/(places|contents)\/\d+$/.test(url.pathname)) return json(404, { code: 'NOT_FOUND', message: 'x' })
      return json(200, { items: [], total: 0, limit: 50, offset: 0 })
    }),
  )
})
afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

const fakeCamera: MapCameraHandle = { boundingBox: () => BBOX, isOutsideKorea: () => false, source: () => 'userSelected' }

/** 첫 화면(전국 목록 도착)까지 띄우고 카메라를 꽂는다. */
async function setupTab() {
  const view = renderHook(() => useSearchTab(true), { wrapper })
  await waitFor(() => expect(view.result.current.scene.places).toHaveLength(NATIONWIDE.length))
  view.result.current.camera.current = fakeCamera
  return view
}

const ids = (places: readonly PlaceSummary[]) => places.map((p) => p.id)

describe('useSearchTab — 「현 지도 내 성지 검색」 을 누른 직후의 핀 (MZ2AZ-600)', () => {
  it('누른 뒤 범위 응답이 오기 전까지 mapPlaces 는 누르기 직전과 같다', async () => {
    const { result } = await setupTab()
    const before = ids(result.current.mapPlaces)
    expect(before).toHaveLength(10) // 첫 화면은 인기 10곳

    act(() => result.current.toggleNearby())
    await waitFor(() => expect(viewportResponses).toHaveLength(1))
    expect(result.current.nearby).toBe(true)
    expect(ids(result.current.mapPlaces)).toEqual(before)
  })

  it('누른 뒤 범위 응답이 오기 전까지 numbered 는 누르기 직전과 같다', async () => {
    const { result } = await setupTab()
    const before = result.current.numbered

    act(() => result.current.toggleNearby())
    await waitFor(() => expect(viewportResponses).toHaveLength(1))
    expect(result.current.numbered).toBe(before)
  })

  it('범위 응답이 오면 mapPlaces 는 그 응답의 장소들이다', async () => {
    const { result } = await setupTab()

    act(() => result.current.toggleNearby())
    await waitFor(() => expect(viewportResponses).toHaveLength(1))
    await act(async () => {
      viewportResponses[0](json(200, { items: IN_VIEW, total: IN_VIEW.length, limit: 200, offset: 0 }))
    })
    await waitFor(() => expect(ids(result.current.mapPlaces)).toEqual(ids(IN_VIEW)))
  })
})
