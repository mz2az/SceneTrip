import { describe, expect, it } from 'vitest'
import { fitsMapForPage, mergeResultsPage, nextPageOffset, reachedLoadMore, RESULTS_PAGE_SIZE } from './searchPaging'

const shop = (id: string, name = `가게 ${id}`) => ({ id, name })
const ids = (list: readonly { id: string }[]) => list.map((s) => s.id)

describe('RESULTS_PAGE_SIZE', () => {
  it('한 쪽은 20개', () => {
    expect(RESULTS_PAGE_SIZE).toBe(20)
  })
})

describe('nextPageOffset — 다음 쪽 위치와 멈춤', () => {
  it('20개 받았고 전체 45개 → 다음 offset 20', () => {
    expect(nextPageOffset(20, 45, false)).toBe(20)
  })

  it('40개 받았고 전체 45개 → 다음 offset 40', () => {
    expect(nextPageOffset(40, 45, false)).toBe(40)
  })

  it('45개 받아 전체에 닿음 → 멈춤(null)', () => {
    expect(nextPageOffset(45, 45, false)).toBeNull()
  })

  it('전체가 20의 배수(40)이고 40개 받음 → 빈 쪽을 더 묻지 않고 멈춤', () => {
    expect(nextPageOffset(40, 40, false)).toBeNull()
  })

  it('전체 0개 → 멈춤', () => {
    expect(nextPageOffset(0, 0, false)).toBeNull()
  })

  it('받은 수가 전체보다 많아도(서버 사이 변동) 멈춤', () => {
    expect(nextPageOffset(21, 20, false)).toBeNull()
  })

  it('앞 쪽이 비어 왔으면 전체에 못 닿아도 멈춤', () => {
    expect(nextPageOffset(20, 45, true)).toBeNull()
  })

  it('전체를 모르면(null) 받은 수가 다음 offset', () => {
    expect(nextPageOffset(20, null, false)).toBe(20)
  })

  it('전체를 모르고 앞 쪽이 비었으면 멈춤', () => {
    expect(nextPageOffset(20, null, true)).toBeNull()
  })
})

describe('reachedLoadMore — 끝 한 줄 안이 보이면 더 받는다', () => {
  it('20줄 중 끝에서 두 번째(인덱스 18)가 보이면 true', () => {
    expect(reachedLoadMore(18, 20)).toBe(true)
  })

  it('맨 끝(인덱스 19)이 보여도 true', () => {
    expect(reachedLoadMore(19, 20)).toBe(true)
  })

  it('끝에서 세 번째(인덱스 17)까지만 보이면 false', () => {
    expect(reachedLoadMore(17, 20)).toBe(false)
  })

  it('목록 앞쪽만 보이면 false', () => {
    expect(reachedLoadMore(5, 20)).toBe(false)
  })

  it('빈 목록 → false', () => {
    expect(reachedLoadMore(-1, 0)).toBe(false)
  })

  it('보이는 줄이 없으면(-1) false', () => {
    expect(reachedLoadMore(-1, 20)).toBe(false)
  })

  it('한 줄짜리 목록에서 그 줄이 보이면 true', () => {
    expect(reachedLoadMore(0, 1)).toBe(true)
  })
})

describe('mergeResultsPage — 받은 쪽 합치기', () => {
  it('겹침 없으면 원래 목록 뒤에 서버 순서대로 붙는다', () => {
    const merged = mergeResultsPage([shop('a'), shop('b')], [shop('c'), shop('d')])
    expect(ids(merged)).toEqual(['a', 'b', 'c', 'd'])
  })

  it('이미 받은 가게(같은 id)는 다시 넣지 않는다', () => {
    const merged = mergeResultsPage([shop('a'), shop('b')], [shop('b'), shop('c'), shop('a'), shop('d')])
    expect(ids(merged)).toEqual(['a', 'b', 'c', 'd'])
  })

  it('겹친 가게는 원래 자리·원래 값을 지킨다', () => {
    const merged = mergeResultsPage([shop('a', '옛 이름')], [shop('a', '새 이름'), shop('b')])
    expect(merged).toEqual([shop('a', '옛 이름'), shop('b')])
  })

  it('쪽이 모두 이미 받은 가게면 목록 그대로', () => {
    const merged = mergeResultsPage([shop('a'), shop('b')], [shop('b'), shop('a')])
    expect(ids(merged)).toEqual(['a', 'b'])
  })

  it('빈 목록에 처음 쪽 → 그 쪽 그대로', () => {
    expect(ids(mergeResultsPage([], [shop('x'), shop('y')]))).toEqual(['x', 'y'])
  })

  it('빈 쪽 → 목록 그대로', () => {
    expect(ids(mergeResultsPage([shop('a')], []))).toEqual(['a'])
  })

  it('원래 목록을 바꾸지 않고 새 배열을 돌려준다', () => {
    const loaded = [shop('a')]
    const merged = mergeResultsPage(loaded, [shop('b')])
    expect(ids(merged)).toEqual(['a', 'b'])
    expect(ids(loaded)).toEqual(['a'])
  })
})

describe('fitsMapForPage — 지도는 처음 쪽에서만 맞춘다', () => {
  it('offset 0 → true', () => {
    expect(fitsMapForPage(0)).toBe(true)
  })

  it('offset 20 → false', () => {
    expect(fitsMapForPage(20)).toBe(false)
  })

  it('offset 40 → false', () => {
    expect(fitsMapForPage(40)).toBe(false)
  })
})
