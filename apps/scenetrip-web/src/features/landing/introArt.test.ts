/*
 * 첫 화면 카드 그림과 주인공 — introArt.ts.
 *   introArtUrl: 작품 32 만 /intro/kdh/<그림 id>.webp, 운영 id(345·351·384·434)는 DEV 그림 id 로 옮긴다.
 *   introHeroId: 작품 32 의 주인공은 N서울타워(그림 id 81) — DEV·운영 모두 81. 목록에 없으면 null, 다른 작품은 null.
 */
import { describe, expect, it } from 'vitest'
import { introArtUrl, introHeroId } from './introArt'

describe('introArtUrl', () => {
  it('작품 32 만 그림이 있다', () => {
    expect(introArtUrl(32, 148)).toBe('/intro/kdh/148.webp')
    expect(introArtUrl(7, 148)).toBeNull()
  })
  it.each([
    [345, 342],
    [351, 348],
    [384, 381],
    [434, 431],
  ])('운영 id %i → 그림 %i', (prod, file) => {
    expect(introArtUrl(32, prod)).toBe(`/intro/kdh/${file}.webp`)
  })
})

describe('introHeroId', () => {
  it('DEV·운영 목록 모두에서 N서울타워(81)', () => {
    expect(introHeroId(32, [148, 173, 81, 348, 342, 44, 40, 381, 431, 16])).toBe(81)
    expect(introHeroId(32, [148, 173, 81, 345, 351, 44, 40, 384, 434, 16])).toBe(81)
  })
  it('목록에 없으면 null, 다른 작품은 null', () => {
    expect(introHeroId(32, [148, 173])).toBeNull()
    expect(introHeroId(7, [81])).toBeNull()
  })
})
