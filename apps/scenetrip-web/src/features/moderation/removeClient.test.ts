/*
 * MZ2AZ-569 — 운영자의 「내리기」 창구(docs/project/plans/moderation-operator.md §4 `client.ts`).
 * `remove(target)` 은 `POST /moderation/removals` 에 `{ targetType, targetId }` 를 싣는다. 실패는 `ok` 가 ApiFailure 로 바꾼다.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'

const h = vi.hoisted(() => ({ POST: vi.fn() }))

vi.mock('@/api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/client')>()),
  api: { POST: (...args: unknown[]) => h.POST(...args) },
}))

import { ApiFailure } from '@/api/failure'
import { serverModerationClient } from './client'
import { commentTarget, postTarget, reviewTarget } from './rules'

const respond = (status: number, error?: unknown) => ({
  data: undefined,
  error,
  response: { ok: status >= 200 && status < 300, status, headers: new Headers() },
})

beforeEach(() => {
  h.POST.mockReset()
})

describe('serverModerationClient.remove', () => {
  it('POST /moderation/removals 에 글 종류와 번호를 싣는다', async () => {
    h.POST.mockResolvedValue(respond(204))
    await serverModerationClient.remove(postTarget(42))
    expect(h.POST).toHaveBeenCalledTimes(1)
    const [path, init] = h.POST.mock.calls[0]!
    expect(path).toBe('/moderation/removals')
    expect((init as { body: unknown }).body).toEqual({ targetType: 'post', targetId: 42 })
  })

  it('댓글·리뷰도 계약 종류 그대로 보낸다', async () => {
    h.POST.mockResolvedValue(respond(204))
    await serverModerationClient.remove(commentTarget(3))
    await serverModerationClient.remove(reviewTarget(8))
    expect(h.POST.mock.calls.map((c) => (c[1] as { body: unknown }).body)).toEqual([
      { targetType: 'comment', targetId: 3 },
      { targetType: 'review', targetId: 8 },
    ])
  })

  it('403 OPERATOR_ONLY 는 ApiFailure 로 던진다', async () => {
    h.POST.mockResolvedValue(respond(403, { code: 'OPERATOR_ONLY', message: 'x' }))
    const error = await serverModerationClient.remove(postTarget(1)).then(() => null, (e: unknown) => e)
    expect(error).toBeInstanceOf(ApiFailure)
    expect((error as ApiFailure).statusCode).toBe(403)
    expect((error as ApiFailure).apiCode).toBe('OPERATOR_ONLY')
  })
})
