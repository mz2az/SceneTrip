/*
 * 새 코스 저장 실패 이벤트 — 정본: docs/project/plans/analytics-events.md 「이벤트 보강 (2026-11-16, MZ2AZ-586)」.
 *
 *   routeActions.save(course)
 *     새 코스(serverId null)를 저장하다 실패하면(만들기 POST 든 채우기 PUT 이든) plan_failed { flow: 'editor', reason: 'save' } 한 번.
 *     있던 코스를 고쳐 저장하다 실패한 것은 세지 않는다. 성공하면 보내지 않는다.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'

const h = vi.hoisted(() => ({ GET: vi.fn(), POST: vi.fn(), PUT: vi.fn(), DELETE: vi.fn() }))

vi.mock('@/api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/client')>()),
  api: {
    GET: (...a: unknown[]) => h.GET(...a),
    POST: (...a: unknown[]) => h.POST(...a),
    PUT: (...a: unknown[]) => h.PUT(...a),
    DELETE: (...a: unknown[]) => h.DELETE(...a),
  },
}))
vi.mock('@/analytics', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/analytics')>()
  return { ...actual, track: vi.fn() }
})

import { track } from '@/analytics'
import { makeCourse, makeDay } from './models'
import { routeActions } from './store'
import { savedCourse, stop } from './testkit'

const respond = (status: number, data?: unknown) => ({
  data,
  error: status >= 400 ? { code: 'INTERNAL', message: 'x' } : undefined,
  response: { ok: status >= 200 && status < 300, status, headers: new Headers() },
})

const detail = (id: number) => ({
  id,
  title: '서울 하루',
  dayCount: 1,
  origin: 'self',
  pace: 'normal',
  startDate: null,
  days: [{ dayNumber: 1, items: [] }],
  createdAt: '2026-11-16T00:00:00Z',
  updatedAt: '2026-11-16T00:00:00Z',
})

const newCourse = () => makeCourse({ title: '새 코스', days: [makeDay([stop('남산', 10)])] })

const planFailed = () =>
  vi
    .mocked(track)
    .mock.calls.map(([e]) => e as { name: string; params: Record<string, unknown> })
    .filter((e) => e.name === 'plan_failed')

beforeEach(() => {
  vi.mocked(track).mockClear()
  h.GET.mockReset().mockResolvedValue(respond(200, { items: [], total: 0, limit: 20, offset: 0 }))
  h.POST.mockReset().mockResolvedValue(respond(201, { id: 77 }))
  h.PUT.mockReset().mockResolvedValue(respond(200, detail(77)))
  h.DELETE.mockReset().mockResolvedValue(respond(204))
})

describe('routeActions.save — plan_failed(editor, save)', () => {
  it('새 코스 만들기(POST)가 실패하면 한 번', async () => {
    h.POST.mockResolvedValue(respond(500))
    expect(await routeActions.save(newCourse())).toBeNull()
    expect(planFailed()).toEqual([{ name: 'plan_failed', params: { flow: 'editor', reason: 'save' } }])
  })

  it('새 코스 채우기(PUT)가 실패해도 한 번', async () => {
    h.PUT.mockResolvedValue(respond(500))
    expect(await routeActions.save(newCourse())).toBeNull()
    expect(planFailed()).toEqual([{ name: 'plan_failed', params: { flow: 'editor', reason: 'save' } }])
  })

  it('네트워크로 던져도 한 번', async () => {
    h.POST.mockRejectedValue(new TypeError('Failed to fetch'))
    expect(await routeActions.save(newCourse())).toBeNull()
    expect(planFailed()).toEqual([{ name: 'plan_failed', params: { flow: 'editor', reason: 'save' } }])
  })

  it('있던 코스를 고쳐 저장하다 실패한 것은 세지 않는다', async () => {
    h.PUT.mockResolvedValue(respond(500))
    expect(await routeActions.save(savedCourse())).toBeNull()
    expect(planFailed()).toEqual([])
  })

  it('새 코스 저장이 되면 보내지 않는다', async () => {
    expect(await routeActions.save(newCourse())).not.toBeNull()
    expect(planFailed()).toEqual([])
  })
})
