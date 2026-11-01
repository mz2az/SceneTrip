import { describe, expect, it } from 'vitest'
import { chooseLanguage } from '@/i18n'
import { routeStore } from './store'

describe('언어가 바뀌면', () => {
  it('받아 둔 작품·촬영지 사본을 비운다 — 다음에 새 언어로 받게', () => {
    chooseLanguage('ko')
    routeStore.set((s) => ({ ...s, works: [{ id: 1, title: '이상한 변호사 우영우' } as never], places: [{ id: 2 } as never], placeWorks: { 2: ['우영우'] } }))
    chooseLanguage('en')
    expect(routeStore.get().works).toEqual([])
    expect(routeStore.get().places).toEqual([])
    expect(routeStore.get().placeWorks).toEqual({})
    chooseLanguage('ko')
  })

  it('같은 언어를 다시 고르면 건드리지 않는다', () => {
    chooseLanguage('en')
    routeStore.set((s) => ({ ...s, works: [{ id: 1, title: 'x' } as never] }))
    chooseLanguage('en')
    expect(routeStore.get().works).toHaveLength(1)
    chooseLanguage('ko')
  })
})
