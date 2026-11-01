import { describe, expect, it } from 'vitest'
import { orderedChoices } from './LanguagePickView'

describe('언어 고르기 순서', () => {
  it('기기 언어가 맨 위에 온다 — 한국어가 아니면 영어가 먼저', () => {
    expect(orderedChoices('en')).toEqual(['en', 'ko'])
    expect(orderedChoices('ko')).toEqual(['ko', 'en'])
  })
})
