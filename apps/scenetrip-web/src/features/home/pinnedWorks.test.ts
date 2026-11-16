import { beforeEach, describe, expect, it, vi } from 'vitest'
import { DEMOTED_CONTENT_IDS, PINNED_CONTENT_IDS, withPinned } from './pinnedWorks'

const GET = vi.fn()
vi.mock('@/api/client', () => ({
  api: { GET: (...args: unknown[]) => GET(...args) },
  ok: async (p: Promise<{ data: unknown }>) => (await p).data,
}))

const w = (id: number) => ({ id, category: 'drama' as const, title: `w${id}`, placeCount: 1 })
const ids = (list: ReadonlyArray<{ id: number }>) => list.map((x) => x.id)

beforeEach(() => GET.mockReset())

// 한 편만 고정했을 때의 동작은 고정 목록을 [32] 로 넘겨 본다(기본 목록은 11/17 부터 열 편).
describe('withPinned — 고정 작품(케이팝 데몬 헌터스)을 맨 앞에', () => {
  it('고정 목록 — 케데헌, 우영우 다음 폭싹 속았수다(68, 11/17), 그 뒤 사용자 11/15 목록(눈물의 여왕, 사내맞선, 참교육, 응답하라 1988, 약한영웅, 이태원 클라쓰, 무빙)', () => {
    expect(PINNED_CONTENT_IDS).toEqual([32, 58, 68, 64, 55, 84, 103, 60, 123, 47])
  })

  it('기본 목록 — 받은 목록에 폭싹 속았수다(68)가 있으면 세 번째로 올리고 따로 받지 않는다', async () => {
    const rest = [32, 58, 64, 55, 84, 103, 60, 123, 47].map(w)
    const out = await withPinned([w(1), w(68), w(2), ...rest])
    expect(ids(out).slice(0, 3)).toEqual([32, 58, 68])
    expect(ids(out)).toEqual([32, 58, 68, 64, 55, 84, 103, 60, 123, 47, 1, 2])
    expect(ids(out).filter((id) => id === 68)).toHaveLength(1)
    expect(GET).not.toHaveBeenCalled()
  })

  it('기본 목록 — 폭싹 속았수다(68)가 받은 목록에 없으면 GET /contents/68 로 받아 세 번째에 넣는다', async () => {
    GET.mockResolvedValueOnce({ data: { ...w(68), title: '폭싹 속았수다', synopsis: 'x', aliases: [] } })
    const rest = [32, 58, 64, 55, 84, 103, 60, 123, 47].map(w)
    const out = await withPinned([w(1), ...rest])
    expect(GET).toHaveBeenCalledTimes(1)
    expect(GET.mock.calls[0]).toEqual(['/contents/{contentId}', { params: { path: { contentId: 68 } } }])
    expect(ids(out)).toEqual([32, 58, 68, 64, 55, 84, 103, 60, 123, 47, 1])
    expect(out[2].title).toBe('폭싹 속았수다')
  })

  it('내리는 목록 — 올드보이(29)·괴물(3)', () => {
    expect(DEMOTED_CONTENT_IDS).toEqual([29, 3])
  })

  it('이미 맨 앞이면 그대로, 따로 받지 않는다', async () => {
    expect(ids(await withPinned([w(32), w(1), w(2)], [32]))).toEqual([32, 1, 2])
    expect(GET).not.toHaveBeenCalled()
  })

  it('목록 뒤쪽에 있으면 맨 앞으로 올리고 중복이 없다', async () => {
    const out = await withPinned([w(1), w(2), w(32), w(3)], [32])
    expect(ids(out)).toEqual([32, 1, 2, 3])
    expect(GET).not.toHaveBeenCalled()
  })

  it('목록에 없으면 GET /contents/{id} 로 한 번 받아 앞에 넣는다', async () => {
    GET.mockResolvedValueOnce({ data: { ...w(32), title: '케이팝 데몬 헌터스', synopsis: 'x', aliases: [] } })
    const out = await withPinned([w(1), w(2)], [32])
    expect(GET).toHaveBeenCalledTimes(1)
    expect(GET.mock.calls[0]).toEqual(['/contents/{contentId}', { params: { path: { contentId: 32 } } }])
    expect(ids(out)).toEqual([32, 1, 2])
    expect(out[0].title).toBe('케이팝 데몬 헌터스')
  })

  it('받기에 실패하면 조용히 기존 순서', async () => {
    GET.mockRejectedValueOnce(new Error('down'))
    expect(ids(await withPinned([w(1), w(2)], [32]))).toEqual([1, 2])
  })

  it('고정이 여럿이면 적힌 순서대로 앞에 둔다', async () => {
    expect(ids(await withPinned([w(1), w(9), w(32)], [32, 9]))).toEqual([32, 9, 1])
  })

  it('내리는 작품은 받은 목록 맨 뒤로, 서로의 순서는 받은 순서대로', async () => {
    expect(ids(await withPinned([w(25), w(29), w(119), w(3), w(121)], [], [29, 3]))).toEqual([25, 119, 121, 29, 3])
  })

  it('고정과 내리기를 같이 — 기본 목록', async () => {
    const pinned = PINNED_CONTENT_IDS.map(w)
    const out = await withPinned([w(25), w(119), w(29), w(3), w(121), ...pinned])
    expect(ids(out)).toEqual([...PINNED_CONTENT_IDS, 25, 119, 121, 29, 3])
    expect(GET).not.toHaveBeenCalled()
  })
})
