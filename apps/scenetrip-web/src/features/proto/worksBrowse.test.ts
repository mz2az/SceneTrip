import { describe, expect, it } from 'vitest'
import {
  appendPage,
  favoritesFirst,
  MAX_PICKED_WORKS,
  nextOffset,
  requestTitles,
  togglePick,
  WORKS_PAGE_SIZE,
  type WorkLike,
  worksQuery,
} from './worksBrowse'

const w = (id: number, title = `작품${id}`): WorkLike => ({ id, title })
const ids = (xs: readonly WorkLike[]) => xs.map((x) => x.id)

describe('worksQuery — GET /contents 질의', () => {
  it('처음 쪽: 검색어 없이 limit=30, offset=0', () => {
    expect(worksQuery('', 0)).toEqual({ limit: WORKS_PAGE_SIZE, offset: 0 })
    expect(WORKS_PAGE_SIZE).toBe(30)
  })

  it('다음 쪽: 받은 개수를 offset 으로', () => {
    expect(worksQuery('', 30)).toEqual({ limit: 30, offset: 30 })
    expect(worksQuery('', 60)).toEqual({ limit: 30, offset: 60 })
  })

  it('검색어는 앞뒤 공백을 떼고 q 로 넣는다', () => {
    expect(worksQuery('  도깨비 ', 0)).toEqual({ q: '도깨비', limit: 30, offset: 0 })
  })

  it('검색 결과의 다음 쪽도 같은 q 에 offset 만 늘린다', () => {
    expect(worksQuery('도깨비', 30)).toEqual({ q: '도깨비', limit: 30, offset: 30 })
  })

  it.each(['', '   ', '\t\n'])('빈 검색어(%j) → q 를 넣지 않는다', (q) => {
    const query = worksQuery(q, 0)
    expect(query).toEqual({ limit: 30, offset: 0 })
    expect('q' in query).toBe(false)
  })
})

describe('nextOffset — 쪽 넘김', () => {
  it('받은 개수가 total 보다 적으면 받은 개수가 다음 offset', () => {
    expect(nextOffset(30, 128)).toBe(30)
    expect(nextOffset(90, 128)).toBe(90)
  })

  it('마지막 쪽(받은 개수 = total) → null', () => {
    expect(nextOffset(128, 128)).toBeNull()
  })

  it('total 을 넘겨 받았어도 → null', () => {
    expect(nextOffset(130, 128)).toBeNull()
  })

  it('딱 한 쪽(total=30)을 다 받았으면 → null', () => {
    expect(nextOffset(30, 30)).toBeNull()
  })

  it('아직 아무것도 안 받았고 total 이 있으면 → 0', () => {
    expect(nextOffset(0, 128)).toBe(0)
  })

  it('결과가 0개(total=0) → null', () => {
    expect(nextOffset(0, 0)).toBeNull()
  })

  it('128개를 30개씩: 0,30,60,90,120 다음은 null', () => {
    const offsets: number[] = []
    let loaded = 0
    for (let next = nextOffset(loaded, 128); next !== null; next = nextOffset(loaded, 128)) {
      offsets.push(next)
      loaded = Math.min(next + 30, 128)
      if (offsets.length > 10) break
    }
    expect(offsets).toEqual([0, 30, 60, 90, 120])
  })
})

describe('appendPage — 받은 쪽 잇기', () => {
  it('뒤에 순서대로 잇는다', () => {
    expect(ids(appendPage([w(1), w(2)], [w(3), w(4)]))).toEqual([1, 2, 3, 4])
  })

  it('첫 쪽은 그대로', () => {
    expect(ids(appendPage([], [w(5), w(6)]))).toEqual([5, 6])
  })

  it('이미 있는 id 는 다시 넣지 않는다(서버 순서가 바뀌어 겹친 경우)', () => {
    expect(ids(appendPage([w(1), w(2), w(3)], [w(3), w(4)]))).toEqual([1, 2, 3, 4])
  })

  it('새 배열을 돌려주고 입력 배열은 바꾸지 않는다', () => {
    const prev = [w(1)]
    const next = appendPage(prev, [w(2)])
    expect(ids(next)).toEqual([1, 2])
    expect(ids(prev)).toEqual([1])
  })
})

describe('favoritesFirst — 찜 먼저, 그다음 서버 순서', () => {
  it('찜한 작품을 앞으로, 나머지는 서버 순서', () => {
    expect(ids(favoritesFirst([w(1), w(2), w(3), w(4)], new Set([3])))).toEqual([3, 1, 2, 4])
  })

  it('찜 여러 개는 받은 순서대로 앞에', () => {
    expect(ids(favoritesFirst([w(1), w(2), w(3), w(4), w(5)], new Set([5, 2])))).toEqual([2, 5, 1, 3, 4])
  })

  it('찜이 없으면 서버 순서 그대로', () => {
    expect(ids(favoritesFirst([w(4), w(1), w(9)], new Set()))).toEqual([4, 1, 9])
  })

  it('받지 않은 작품의 찜은 무시한다(받은 것 안에서만)', () => {
    expect(ids(favoritesFirst([w(1), w(2)], new Set([99, 2])))).toEqual([2, 1])
  })
})

describe('togglePick — 고른 것 유지', () => {
  it('안 고른 작품을 누르면 더한다', () => {
    const r = togglePick([w(1)], w(2))
    expect(ids(r.picked)).toEqual([1, 2])
    expect(r.tooMany).toBe(false)
  })

  it('고른 작품을 다시 누르면 뺀다', () => {
    const r = togglePick([w(1), w(2)], w(1))
    expect(ids(r.picked)).toEqual([2])
    expect(r.tooMany).toBe(false)
  })

  it('상한(5개)에서 더 고르면 그대로 두고 tooMany', () => {
    expect(MAX_PICKED_WORKS).toBe(5)
    const five = [w(1), w(2), w(3), w(4), w(5)]
    const r = togglePick(five, w(6))
    expect(ids(r.picked)).toEqual([1, 2, 3, 4, 5])
    expect(r.tooMany).toBe(true)
  })

  it('상한에서도 고른 것을 빼는 건 된다', () => {
    const r = togglePick([w(1), w(2), w(3), w(4), w(5)], w(3))
    expect(ids(r.picked)).toEqual([1, 2, 4, 5])
    expect(r.tooMany).toBe(false)
  })

  it('검색 결과에 없는 작품도 고른 채로 남고, 새로 고른 것이 뒤에 붙는다', () => {
    // 1·2 를 처음 목록에서 고른 뒤 검색해 결과에는 7 만 있다.
    const before = [w(1, '도깨비'), w(2, '미생')]
    const r = togglePick(before, w(7, '시그널'))
    expect(r.picked.map((x) => x.title)).toEqual(['도깨비', '미생', '시그널'])
  })

  it('새 배열을 돌려주고 입력 배열은 바꾸지 않는다', () => {
    const before = [w(1)]
    const r = togglePick(before, w(2))
    expect(ids(r.picked)).toEqual([1, 2])
    expect(ids(before)).toEqual([1])
  })
})

describe('requestTitles — AI 일정 요청의 작품 제목', () => {
  const popular = [w(10, '인기1'), w(11, '인기2'), w(12, '인기3'), w(13, '인기4')]

  it('고른 것이 있으면 고른 작품의 제목(고른 순서)', () => {
    expect(requestTitles([w(2, '미생'), w(1, '도깨비')], popular)).toEqual(['미생', '도깨비'])
  })

  it('고른 작품이 지금 목록(검색 결과)에 없어도 그 제목을 쓴다', () => {
    const searchResults = [w(7, '시그널')]
    expect(requestTitles([w(1, '도깨비')], searchResults)).toEqual(['도깨비'])
  })

  it('아무것도 안 고르면 인기 작품 앞 3개의 제목', () => {
    expect(requestTitles([], popular)).toEqual(['인기1', '인기2', '인기3'])
  })

  it('인기 작품이 3개보다 적으면 있는 만큼', () => {
    expect(requestTitles([], [w(10, '인기1'), w(11, '인기2')])).toEqual(['인기1', '인기2'])
  })
})
