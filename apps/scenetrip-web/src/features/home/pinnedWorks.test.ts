import { beforeEach, describe, expect, it, vi } from 'vitest'
import { PINNED_CONTENT_IDS, withPinned } from './pinnedWorks'

const GET = vi.fn()
vi.mock('@/api/client', () => ({
  api: { GET: (...args: unknown[]) => GET(...args) },
  ok: async (p: Promise<{ data: unknown }>) => (await p).data,
}))

const w = (id: number) => ({ id, category: 'drama' as const, title: `w${id}`, placeCount: 1 })
const ids = (list: ReadonlyArray<{ id: number }>) => list.map((x) => x.id)

beforeEach(() => GET.mockReset())

describe('withPinned — 고정 작품(케이팝 데몬 헌터스)을 맨 앞에', () => {
  it('고정 목록은 지금 [32] 이다', () => {
    expect(PINNED_CONTENT_IDS).toEqual([32])
  })

  it('이미 맨 앞이면 그대로, 따로 받지 않는다', async () => {
    expect(ids(await withPinned([w(32), w(1), w(2)]))).toEqual([32, 1, 2])
    expect(GET).not.toHaveBeenCalled()
  })

  it('목록 뒤쪽에 있으면 맨 앞으로 올리고 중복이 없다', async () => {
    const out = await withPinned([w(1), w(2), w(32), w(3)])
    expect(ids(out)).toEqual([32, 1, 2, 3])
    expect(GET).not.toHaveBeenCalled()
  })

  it('목록에 없으면 GET /contents/{id} 로 한 번 받아 앞에 넣는다', async () => {
    GET.mockResolvedValueOnce({ data: { ...w(32), title: '케이팝 데몬 헌터스', synopsis: 'x', aliases: [] } })
    const out = await withPinned([w(1), w(2)])
    expect(GET).toHaveBeenCalledTimes(1)
    expect(GET.mock.calls[0]).toEqual(['/contents/{contentId}', { params: { path: { contentId: 32 } } }])
    expect(ids(out)).toEqual([32, 1, 2])
    expect(out[0].title).toBe('케이팝 데몬 헌터스')
  })

  it('받기에 실패하면 조용히 기존 순서', async () => {
    GET.mockRejectedValueOnce(new Error('down'))
    expect(ids(await withPinned([w(1), w(2)]))).toEqual([1, 2])
  })

  it('고정이 여럿이면 적힌 순서대로 앞에 둔다', async () => {
    expect(ids(await withPinned([w(1), w(9), w(32)], [32, 9]))).toEqual([32, 9, 1])
  })
})
