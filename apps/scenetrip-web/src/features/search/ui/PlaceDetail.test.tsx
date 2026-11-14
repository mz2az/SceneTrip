// 촬영지 상세 — 부모가 다시 그려도 조회 기록을 또 남기지 않는다.
// 부모(SearchTab)는 그릴 때마다 `load={(id) => ...}` 를 새 화살표 함수로 넘긴다. 함수 모양이 바뀌었다고 「열었다」를 또 세면 안 된다.
import { act, cleanup, render, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

// Node 의 Request 는 상대 주소를 받지 못한다 — 시험에서는 절대 기준 주소를 준다(client.ts 가 읽기 전에).
vi.hoisted(() => {
  const env = import.meta.env as Record<string, string>
  env.VITE_API_BASE = 'http://test.local'
  // .env.local 의 개발 안전장치는 켜 두면 POST /events 를 막는다 — 가짜 fetch 라 서버에는 아무것도 가지 않는다.
  env.VITE_BLOCK_WRITES = ''
  env.VITE_BLOCK_PATHS = ''
})

import { account } from '@/features/profile/auth/account'
import { chooseLanguage } from '@/i18n'
import { clearSession, setSession } from '@/state/auth'
import type { PlaceDetail as PlaceDetailT, PlaceSummary } from '../sceneData'
import { PlaceDetail } from './PlaceDetail'

const json = (status: number, body: unknown) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

let events: unknown[]

beforeEach(() => {
  chooseLanguage('ko')
  events = []
  vi.stubGlobal(
    'fetch',
    vi.fn(async (req: Request) => {
      const path = new URL(req.url).pathname
      if (req.method === 'POST' && path.endsWith('/events')) {
        events.push(await req.clone().json())
        return new Response(null, { status: 202 })
      }
      // 사진첩·후기 등 다른 조회는 빈 목록으로 답한다.
      return json(200, { items: [], total: 0, totalCount: 0 })
    }),
  )
})
afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

const summary = (id: number): PlaceSummary => ({ id, name: `장소 ${id}`, latitude: 37.5, longitude: 127 })
const detailOf = (id: number) => ({ ...summary(id), scenes: [] }) as unknown as PlaceDetailT
const viewOf = (id: number) => ({ type: 'view', entityType: 'place', entityId: id })

/** 효과·비동기 호출이 다 돌 때까지 몇 차례 기다린다(「더 일어나지 않았다」를 잴 때). */
const settle = () => act(async () => {
  for (let i = 0; i < 5; i += 1) await new Promise((r) => setTimeout(r, 0))
})

describe('PlaceDetail — 조회 기록', () => {
  it('처음 열면 조회를 한 번 기록하고 상세를 한 번 읽는다', async () => {
    const load = vi.fn(async (id: number) => detailOf(id))
    render(<PlaceDetail summary={summary(7)} load={load} onBack={() => {}} />)
    await waitFor(() => expect(events).toHaveLength(1))
    await settle()
    expect(events).toEqual([viewOf(7)])
    expect(load).toHaveBeenCalledTimes(1)
    expect(load).toHaveBeenCalledWith(7)
  })

  it('같은 장소로 다시 그리면서 load 만 새 함수로 바뀌어도 조회를 또 기록하지 않고 다시 읽지 않는다', async () => {
    const first = vi.fn(async (id: number) => detailOf(id))
    const { rerender } = render(<PlaceDetail summary={summary(7)} load={first} onBack={() => {}} />)
    await waitFor(() => expect(events).toHaveLength(1))
    await settle()

    // 부모가 다시 그렸다 — summary 는 같은 장소, load 는 새 화살표 함수.
    const second = vi.fn(async (id: number) => detailOf(id))
    rerender(<PlaceDetail summary={summary(7)} load={second} onBack={() => {}} />)
    const third = vi.fn(async (id: number) => detailOf(id))
    rerender(<PlaceDetail summary={summary(7)} load={third} onBack={() => {}} />)
    await settle()

    expect(events).toEqual([viewOf(7)])
    expect(first.mock.calls.length + second.mock.calls.length + third.mock.calls.length).toBe(1)
  })

  it('다른 장소로 바뀌면 그 장소의 조회를 기록하고 그 장소를 읽는다', async () => {
    const load = vi.fn(async (id: number) => detailOf(id))
    const { rerender } = render(<PlaceDetail summary={summary(7)} load={load} onBack={() => {}} />)
    await waitFor(() => expect(events).toHaveLength(1))
    await settle()

    rerender(<PlaceDetail summary={summary(8)} load={load} onBack={() => {}} />)
    await waitFor(() => expect(events).toHaveLength(2))
    await settle()

    expect(events).toEqual([viewOf(7), viewOf(8)])
    expect(load.mock.calls.map((c) => c[0])).toEqual([7, 8])
  })
})

// ---------------------------------------------------------------- 길찾기 (MZ2AZ-532)
// 「길찾기」가 큰 주 버튼, 「마이맵에 담기」는 보조. 비회원이 누르면 로그인 시트(이유 문구)를 띄우고 코스를 만들지 않는다.

describe('PlaceDetail — 길찾기', () => {
  const writes = () =>
    (vi.mocked(fetch).mock.calls as unknown as Array<[Request]>)
      .map(([req]) => `${req.method} ${new URL(req.url).pathname}`)
      .filter((line) => !line.startsWith('GET') && !line.endsWith('/events'))

  afterEach(() => {
    clearSession()
    account.actions.closeSignIn()
  })

  it('「길찾기」가 주 버튼이고 「마이맵에 담기」도 남아 있다', async () => {
    const load = vi.fn(async (id: number) => detailOf(id))
    const { getByRole } = render(<PlaceDetail summary={summary(7)} load={load} onBack={() => {}} onOpenTrip={() => {}} />)
    await settle()
    const directions = getByRole('button', { name: /길찾기/ })
    const cart = getByRole('button', { name: /마이맵에 담기/ })
    expect(directions.getAttribute('data-variant')).toBe('prominent')
    expect(cart.getAttribute('data-variant')).toBe('bordered')
    // 문서 순서상 길찾기가 먼저다.
    expect(directions.compareDocumentPosition(cart) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  it('비회원이 누르면 로그인 시트를 이유 문구와 함께 띄우고 코스를 만들지 않는다', async () => {
    clearSession()
    account.actions.start()
    const opened: number[] = []
    const load = vi.fn(async (id: number) => detailOf(id))
    const { getByRole } = render(<PlaceDetail summary={summary(7)} load={load} onBack={() => {}} onOpenTrip={(id) => opened.push(id)} />)
    await settle()
    await act(async () => {
      getByRole('button', { name: /길찾기/ }).click()
    })
    await settle()
    expect(account.store.get().showingSignIn).toBe(true)
    expect(account.store.get().signInReason).toBe('길 안내는 유료 지도 API 를 써서 가입한 분께만 열려 있어요')
    expect(writes()).toEqual([])
    expect(opened).toEqual([])
  })

  it('로그인한 사람이 누르면 POST /courses → 채우기 → 시작 → 그 코스로 여행을 연다', async () => {
    setSession({ accessToken: 'a', refreshToken: 'r', accessTokenExpiresIn: 3600 })
    account.actions.start()
    const courseDetail = {
      id: 555, title: '장소 7 가는 길', status: 'active', origin: 'self', pace: 'tight', dayCount: 1, currentDayNo: 1,
      days: [{ dayNo: 1, totalMinutes: 30, items: [{ id: 1, source: 'place', placeId: 7, name: '장소 7', latitude: 37.5, longitude: 127, dwellMinutes: 30 }] }],
    }
    vi.mocked(fetch).mockImplementation(async (input: RequestInfo | URL) => {
      const req = input as Request
      const path = new URL(req.url).pathname
      if (req.method === 'POST' && path.endsWith('/events')) return new Response(null, { status: 202 })
      if (req.method === 'POST' && path.endsWith('/courses')) return json(201, { id: 555 })
      // 채우기(PUT)는 예정(upcoming) 코스를 돌려주고, 시작(progress)이 active 로 바꾼다 — 서버와 같은 순서.
      if (req.method === 'PUT' && path.endsWith('/courses/555')) return json(200, { ...courseDetail, status: 'upcoming', currentDayNo: null })
      if (req.method === 'PUT' && path.endsWith('/courses/555/progress')) return json(200, courseDetail)
      return json(200, { items: [], total: 0, totalCount: 0 })
    })
    const opened: number[] = []
    const load = vi.fn(async (id: number) => detailOf(id))
    const { getByRole } = render(<PlaceDetail summary={summary(7)} load={load} onBack={() => {}} onOpenTrip={(id) => opened.push(id)} />)
    await settle()
    await act(async () => {
      getByRole('button', { name: /길찾기/ }).click()
    })
    await waitFor(() => expect(opened).toEqual([555]))
    expect(writes()).toEqual(['POST /v1/courses', 'PUT /v1/courses/555', 'PUT /v1/courses/555/progress'])
    expect(account.store.get().showingSignIn).toBe(false)
  })
})
