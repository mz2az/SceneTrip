import { describe, expect, it } from 'vitest'
import { returnToOf } from '@/app/tabRouter'
import { contentBack, placeBack } from './detailBack'

// 격자(/trending)에서 작품 5 를 열면 이동 state 에 이렇게 실린다.
const FROM_GRID = { returnTo: '/trending', contentId: 5 }

describe('returnToOf — 실어 연 그 작품에만 유효하다', () => {
  it('작품 id 가 같으면 돌아갈 곳을 준다', () => {
    expect(returnToOf(FROM_GRID, 5)).toBe('/trending')
  })
  it('작품이 바뀌면(검색에서 다른 작품을 열었으면) 무효다', () => {
    expect(returnToOf(FROM_GRID, 7)).toBeNull()
  })
})

describe('contentBack — 작품 상세의 뒤로', () => {
  it('격자에서 연 그 작품이면 한 칸 물린다(브라우저 뒤로와 같다)', () => {
    expect(contentBack({ pathname: '/search/content/5', state: FROM_GRID, contentId: 5, canGoBack: true })).toEqual({ kind: 'pop' })
  })
  it('물릴 기록이 없으면(새로 고침·주소로 바로 옴) 격자로 바꿔 간다', () => {
    expect(contentBack({ pathname: '/search/content/5', state: FROM_GRID, contentId: 5, canGoBack: false })).toEqual({ kind: 'replace', to: '/trending' })
  })
  it('S2-2: 격자에서 연 5 위에서 검색으로 7 을 열었으면 7 의 뒤로는 격자가 아니라 검색 탭이다', () => {
    expect(contentBack({ pathname: '/search/content/5', state: FROM_GRID, contentId: 7, canGoBack: true })).toEqual({ kind: 'replace', to: '/search' })
  })
  it('표시 없이 연 상세는 전처럼 검색 탭 주소로 돌린다', () => {
    expect(contentBack({ pathname: '/search/content/5', state: null, contentId: 5, canGoBack: true })).toEqual({ kind: 'replace', to: '/search' })
  })
  it('이미 검색 탭 주소면 주소를 건드리지 않는다', () => {
    expect(contentBack({ pathname: '/search', state: null, contentId: 5, canGoBack: true })).toEqual({ kind: 'stay' })
  })
})

describe('placeBack — 촬영지 상세의 뒤로', () => {
  it('S2-1: 작품 상세 위에서 연 촬영지를 닫으면 작품 상세 주소와 돌아갈 곳을 그대로 둔다', () => {
    const step = placeBack({ pathname: '/search/content/5', state: FROM_GRID, contentId: 5 })
    expect(step).toEqual({ kind: 'stay' })
    // 이어서 작품 상세의 뒤로는 격자로 간다.
    expect(contentBack({ pathname: '/search/content/5', state: FROM_GRID, contentId: 5, canGoBack: true })).toEqual({ kind: 'pop' })
  })
  it('작품 상세가 남아 있는데 주소가 촬영지면 작품 상세 주소로 되돌리며 state 를 넘긴다', () => {
    expect(placeBack({ pathname: '/search/place/9', state: FROM_GRID, contentId: 5 })).toEqual({ kind: 'replace', to: '/search/content/5', state: FROM_GRID })
  })
  it('작품 상세 없이 연 촬영지는 전처럼 검색 탭 주소로 돌린다', () => {
    expect(placeBack({ pathname: '/search/place/9', state: null, contentId: null })).toEqual({ kind: 'replace', to: '/search' })
  })
})
