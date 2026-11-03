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

import { chooseLanguage } from '@/i18n'
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
