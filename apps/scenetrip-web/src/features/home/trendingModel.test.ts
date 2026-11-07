import { beforeEach, describe, expect, it, vi } from 'vitest'
import { TRENDING_LIMIT, loadTrending } from './trendingModel'

const GET = vi.fn()
vi.mock('@/api/client', () => ({
  api: { GET: (...args: unknown[]) => GET(...args) },
  ok: async (p: Promise<{ data: unknown }>) => (await p).data,
}))

const items = (from: number, n: number) => Array.from({ length: n }, (_, i) => ({ id: from + i, category: 'drama', title: `w${from + i}`, placeCount: 1 }))

beforeEach(() => GET.mockReset())

describe('loadTrending', () => {
  it('선반과 같은 GET /contents 를 한도 100 으로 받고, total 까지 offset 으로 이어 받는다', async () => {
    GET.mockResolvedValueOnce({ data: { items: items(1, 100), total: 128, limit: 100, offset: 0 } })
    GET.mockResolvedValueOnce({ data: { items: items(101, 28), total: 128, limit: 100, offset: 100 } })

    const works = await loadTrending()

    expect(GET).toHaveBeenCalledTimes(2)
    expect(GET.mock.calls[0]).toEqual(['/contents', { params: { query: { limit: TRENDING_LIMIT, offset: 0 } } }])
    expect(GET.mock.calls[1]).toEqual(['/contents', { params: { query: { limit: TRENDING_LIMIT, offset: 100 } } }])
    expect(works.map((w) => w.id)).toEqual(items(1, 128).map((w) => w.id))
  })

  it('한 쪽으로 끝나면 한 번만 부른다', async () => {
    GET.mockResolvedValueOnce({ data: { items: items(1, 20), total: 20, limit: 100, offset: 0 } })
    expect((await loadTrending()).length).toBe(20)
    expect(GET).toHaveBeenCalledTimes(1)
  })
})
