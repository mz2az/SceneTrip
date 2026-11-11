import { describe, expect, it } from 'vitest'
import { placesForNewCourse } from './savedPlan'

/*
 * MZ2AZ-551 — 「담은 곳으로 새 코스」 는 담아 둔 곳 전부를 넣지 않는다. 사용자가 넣을 곳을 고르고, 처음에는 아무것도 골라져 있지 않다.
 * 고른 곳만, 담은 순서대로 새 코스에 들어간다. 목록에서 빠진(delisted) 곳은 골라 있어도 넣지 않는다.
 */
type Item = { placeId: number; name: string; delisted?: boolean }
const cart: Item[] = [
  { placeId: 1, name: '가' },
  { placeId: 2, name: '나' },
  { placeId: 3, name: '다' },
  { placeId: 4, name: '라', delisted: true },
]

describe('placesForNewCourse — 새 코스에 넣을 곳', () => {
  it('아무것도 고르지 않았으면(처음 상태) 아무것도 넣지 않는다', () => {
    expect(placesForNewCourse(cart, new Set())).toEqual([])
  })

  it('고른 곳만 넣는다', () => {
    expect(placesForNewCourse(cart, new Set([3, 1])).map((i) => i.placeId)).toEqual([1, 3])
  })

  it('순서는 고른 순서가 아니라 담은 순서다', () => {
    expect(placesForNewCourse(cart, new Set([3, 2, 1])).map((i) => i.placeId)).toEqual([1, 2, 3])
  })

  it('목록에서 빠진 곳은 골라 있어도 넣지 않는다', () => {
    expect(placesForNewCourse(cart, new Set([4, 2])).map((i) => i.placeId)).toEqual([2])
  })

  it('담은 목록에 없는 id 는 무시한다', () => {
    expect(placesForNewCourse(cart, new Set([99]))).toEqual([])
  })
})
