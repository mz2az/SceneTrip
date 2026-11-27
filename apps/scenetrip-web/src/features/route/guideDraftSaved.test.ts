/*
 * 담은 촬영지로 AI 일정 — guideDraft 가 placeIds 를 보낸다. 정본: docs/project/plans/saved-ai-course.md §4,
 * 계약 contracts/openapi/scene-api-v1.yaml 1.33.0 `GuidePlanRequest.placeIds`(titles·city·placeIds 중 정확히 하나).
 *
 *   guideDraft({ …, placeIds: [..] }) → POST /guide/plan 몸체에 placeIds, titles·city 는 싣지 않는다.
 *   작품 목록이 비어 있어도(담은 곳 모드에는 작품 단계가 없다) 요청은 나간다.
 *   placeIds 를 주지 않으면 지금처럼 titles 를 보낸다.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'

const h = vi.hoisted(() => ({ POST: vi.fn(), GET: vi.fn() }))

vi.mock('@/api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/client')>()),
  api: { POST: (...args: unknown[]) => h.POST(...args), GET: (...args: unknown[]) => h.GET(...args) },
  ok: async (p: Promise<{ data?: unknown; response: { ok: boolean; status: number } }>) => {
    const r = await p
    if (r.response.ok) return r.data
    throw new Error(`status ${r.response.status}`)
  },
}))

import { type DraftRequest, guideDraft } from './store'

const okRes = (data: unknown) => ({ data, response: { ok: true, status: 200, headers: new Headers() } })

const plan = {
  days: [
    {
      day: 1,
      stops: [
        { order: 1, placeId: 11, name: '담은곳1', address: '서울 종로구', latitude: 37.57, longitude: 126.98, arriveMinute: 540 },
        { order: 2, placeId: 22, name: '담은곳2', address: '서울 종로구', latitude: 37.58, longitude: 126.98, arriveMinute: 600 },
      ],
    },
  ],
}

function request(extra: Partial<DraftRequest> = {}): DraftRequest {
  return {
    nights: 0, startDate: null, workIds: new Set<number>(), pace: 'tight', near: null, locationAllowed: false,
    sortedWorks: [], places: [], ...extra,
  }
}

/** 마지막 POST /guide/plan 의 몸체. */
function sentBody(): Record<string, unknown> {
  const calls = h.POST.mock.calls.filter((c) => c[0] === '/guide/plan')
  expect(calls.length, 'POST /guide/plan 이 나갔다').toBeGreaterThan(0)
  return (calls.at(-1)![1] as { body: Record<string, unknown> }).body
}

beforeEach(() => {
  h.POST.mockReset()
  h.GET.mockReset()
  h.POST.mockResolvedValue(okRes({ plan }))
  h.GET.mockResolvedValue({ data: undefined, response: { ok: false, status: 404, headers: new Headers() } })
})

describe('guideDraft — placeIds (담은 촬영지로, 1.33.0)', () => {
  it('placeIds 를 주면 몸체에 placeIds, titles·city 는 없다', async () => {
    const res = await guideDraft(request({ placeIds: [11, 22, 33] }))
    const body = sentBody()
    expect(body.placeIds).toEqual([11, 22, 33])
    expect(body.titles ?? undefined).toBeUndefined()
    expect(body.city ?? undefined).toBeUndefined()
    expect(res.ok).toBe(true)
  })

  it('작품 목록이 비어 있어도 placeIds 로 요청이 나간다(작품 단계가 없다)', async () => {
    const res = await guideDraft(request({ placeIds: [11], sortedWorks: [] }))
    expect(h.POST).toHaveBeenCalled()
    expect(res.ok).toBe(true)
  })

  it('기간·속도는 그대로 실린다', async () => {
    await guideDraft(request({ placeIds: [11, 22], nights: 1, pace: 'tight' }))
    const body = sentBody()
    expect(body.days).toBe(2)
    expect(body.pace).toBe('packed')
  })

  it('placeIds 를 주지 않으면 지금처럼 titles 를 보내고 placeIds 는 없다', async () => {
    await guideDraft(request({ titles: ['도깨비'] }))
    const body = sentBody()
    expect(body.titles).toEqual(['도깨비'])
    expect(body.placeIds ?? undefined).toBeUndefined()
  })
})
