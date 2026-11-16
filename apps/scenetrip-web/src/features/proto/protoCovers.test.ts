/*
 * 도시 대표 사진(MZ2AZ-595) — 대구(서문시장, 140)는 사진에 www.encyber.com 워터마크가 있어 사진 머리에서 뺀다.
 * 워터마크 사진 촬영지 번호(MZ2AZ-596, 사진 파이프라인이 바꿀 때까지)는 도시 대표 사진으로 고르지 않는다.
 */
import { renderHook } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { HomeCity } from '@/features/home/homeCityModel'
import * as covers from './protoCovers'
import { CITY_COVERS, fixedCoverOf } from './protoCovers'
import { cityPhotoOf, coverOf, useCitySlides } from './protoParts'

const EXPECTED_WATERMARKED = [53, 67, 113, 116, 117, 127, 128, 140, 145, 153, 174, 180, 183, 190, 194, 200, 212, 213, 376, 378, 415, 14, 400, 131, 214]

const place = (id: number, imageUrl: string | null) => ({ id, name: `p${id}`, imageUrl })
const city = (key: string, places: ReturnType<typeof place>[]) =>
  ({ key, name: key, places, placesLoaded: true }) as unknown as HomeCity

/** 아직 내보내지 않았으면 undefined — 가져오기 오류가 아니라 단언 실패가 되게. */
const watermarked = (): ReadonlySet<number> | undefined =>
  (covers as Record<string, unknown>).WATERMARKED_PLACE_IDS as ReadonlySet<number> | undefined

describe('CITY_COVERS', () => {
  it('대구가 빠진 7곳, 순서는 서울, 부산, 경주, 제주, 인천, 수원, 포항', () => {
    expect(CITY_COVERS.map((c) => c.key)).toEqual(['서울', '부산', '경주', '제주', '인천', '수원', '포항'])
  })
  it('서문시장(140)은 대표 사진이 아니다', () => {
    expect(CITY_COVERS.some((c) => c.placeId === 140)).toBe(false)
    expect(fixedCoverOf('대구')).toBeUndefined()
  })
  it('사진 머리에 대구 장이 없다', () => {
    const { result } = renderHook(() => useCitySlides())
    const { slides } = result.current
    expect(slides).toHaveLength(7)
    expect(slides.some((s) => s.city === 'Daegu' || s.city === '대구')).toBe(false)
    expect(slides.some((s) => s.place.id === 140)).toBe(false)
  })
})

describe('WATERMARKED_PLACE_IDS', () => {
  it('워터마크 사진 촬영지 번호 25개 그대로', () => {
    const ids = watermarked()
    expect(ids).toBeInstanceOf(Set)
    expect([...(ids ?? [])].sort((a, b) => a - b)).toEqual([...EXPECTED_WATERMARKED].sort((a, b) => a - b))
  })
})

describe('coverOf / cityPhotoOf', () => {
  it('워터마크 촬영지는 건너뛰고 다음 사진 있는 촬영지', () => {
    const daegu = city('대구', [place(140, 'https://img.example/140.jpg'), place(141, 'https://img.example/141.jpg')])
    expect(coverOf(daegu)?.id).toBe(141)
    expect(cityPhotoOf(daegu)).toBe('https://img.example/141.jpg')
  })
  it('사진 없는 촬영지와 워터마크 촬영지를 모두 건너뛴다', () => {
    const c = city('광주', [place(1, null), place(53, 'https://img.example/53.jpg'), place(2, 'https://img.example/2.jpg')])
    expect(coverOf(c)?.id).toBe(2)
  })
  it('사진 있는 촬영지가 전부 워터마크면 없음', () => {
    const c = city('광주', [place(14, 'https://img.example/14.jpg'), place(400, 'https://img.example/400.jpg'), place(3, null)])
    expect(coverOf(c)).toBeUndefined()
    expect(cityPhotoOf(c)).toBeNull()
  })
  it('워터마크 아닌 첫 사진 촬영지는 그대로(기존 동작)', () => {
    const c = city('광주', [place(7, 'https://img.example/7.jpg'), place(8, 'https://img.example/8.jpg')])
    expect(coverOf(c)?.id).toBe(7)
  })
  it('고른 대표 사진이 있는 도시는 그 사진이 먼저', () => {
    const seoul = city('서울', [place(9, 'https://img.example/9.jpg')])
    expect(cityPhotoOf(seoul)).toBe(fixedCoverOf('서울')?.src)
  })
})
