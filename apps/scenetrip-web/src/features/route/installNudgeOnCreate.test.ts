/*
 * 새 코스를 처음 저장하면 홈 화면 추가 권유 — 정본: docs/project/plans/web-home-install.md §1 (MZ2AZ-589).
 *
 *   routeActions.save(course) 가 새 코스(serverId null)를 저장하고 create_course 를 보내는 자리에서 installNudge.request('course_created').
 *   고쳐 저장했거나 저장이 실패하면 부르지 않는다. (띄울지 말지는 installNudge 가 정한다.)
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'

const h = vi.hoisted(() => ({ GET: vi.fn(), POST: vi.fn(), PUT: vi.fn(), DELETE: vi.fn(), request: vi.fn() }))

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
vi.mock('@/features/keep/installNudge', () => ({
  installNudge: { request: (t: unknown) => h.request(t), dismiss: vi.fn(), accept: vi.fn(), current: () => null },
  useInstallNudge: () => null,
}))

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
const created = () =>
  vi
    .mocked(track)
    .mock.calls.map(([e]) => e as { name: string })
    .filter((e) => e.name === 'create_course')

beforeEach(() => {
  vi.mocked(track).mockClear()
  h.request.mockReset()
  h.GET.mockReset().mockResolvedValue(respond(200, { items: [], total: 0, limit: 20, offset: 0 }))
  h.POST.mockReset().mockResolvedValue(respond(201, { id: 77 }))
  h.PUT.mockReset().mockResolvedValue(respond(200, detail(77)))
  h.DELETE.mockReset().mockResolvedValue(respond(204))
})

describe("routeActions.save — installNudge.request('course_created')", () => {
  it('새 코스 저장이 되면 create_course 와 함께 한 번', async () => {
    expect(await routeActions.save(newCourse())).not.toBeNull()
    expect(created()).toHaveLength(1)
    expect(h.request).toHaveBeenCalledTimes(1)
    expect(h.request).toHaveBeenCalledWith('course_created')
  })

  it('있던 코스를 고쳐 저장하면 부르지 않는다 — 그 뒤 새 코스를 저장하면 그때 한 번', async () => {
    expect(await routeActions.save(savedCourse())).not.toBeNull()
    expect(h.request).not.toHaveBeenCalled()
    expect(await routeActions.save(newCourse())).not.toBeNull()
    expect(h.request.mock.calls).toEqual([['course_created']])
  })

  it('새 코스 저장이 실패하면 부르지 않는다 — 다시 저장해 되면 그때 한 번', async () => {
    h.POST.mockResolvedValueOnce(respond(500))
    expect(await routeActions.save(newCourse())).toBeNull()
    expect(h.request).not.toHaveBeenCalled()
    expect(await routeActions.save(newCourse())).not.toBeNull()
    expect(h.request.mock.calls).toEqual([['course_created']])
  })
})
