// iOS ScenePhotoRules (MZ2AZ-420) — 100~400 %, 두 번 누르면 200 %
import { beforeEach, describe, expect, it } from 'vitest'
import { chooseLanguage } from '@/i18n'
import { fullDate, relativeTime } from './format'
import { createPhotoRenewal, shouldRenew, unsigned } from './renewal'
import {
  DOUBLE_TAP_SCALE, IDENTITY, MAX_SCALE, MIN_SCALE, clampOffset, clampScale, doubleTap, doubled, fittedSize, halve, percent, zoomAt,
} from './zoom'

const viewport = { width: 400, height: 800 }
const fitted = fittedSize({ width: 1000, height: 500 }, viewport) // 400 x 200

describe('zoom', () => {
  it('배율은 100~400 % 로 자르고 이상한 값은 100 % 로', () => {
    expect(clampScale(0.2)).toBe(MIN_SCALE)
    expect(clampScale(9)).toBe(MAX_SCALE)
    expect(clampScale(Number.NaN)).toBe(MIN_SCALE)
    expect(clampScale(2.5)).toBe(2.5)
  })

  it('그림은 화면에 비율을 지켜 맞춘다', () => {
    expect(fitted).toEqual({ width: 400, height: 200 })
    expect(fittedSize({ width: 0, height: 5 }, viewport)).toEqual({ width: 0, height: 0 })
    expect(fittedSize({ width: 100, height: 100 }, { width: Number.POSITIVE_INFINITY, height: 5 })).toEqual({ width: 0, height: 0 })
  })

  it('단추는 두 배·반으로 바꾸고 100 % 아래·400 % 위로 가지 않는다', () => {
    let v = IDENTITY
    v = doubled(v, fitted, viewport)
    expect(percent(v)).toBe(200)
    v = doubled(v, fitted, viewport)
    expect(percent(v)).toBe(400)
    v = doubled(v, fitted, viewport)
    expect(percent(v)).toBe(400)
    v = halve(v, fitted, viewport)
    expect(percent(v)).toBe(200)
    v = halve(halve(v, fitted, viewport), fitted, viewport)
    expect(v).toEqual(IDENTITY)
  })

  it('두 번 누르면 그 점을 200 % 로, 확대돼 있으면 되돌린다', () => {
    const zoomed = doubleTap(IDENTITY, 100, 0, fitted, viewport)
    expect(zoomed.scale).toBe(DOUBLE_TAP_SCALE)
    expect(zoomed.x).toBe(-100) // 누른 점이 제자리에 남는다
    expect(doubleTap(zoomed, 0, 0, fitted, viewport)).toEqual(IDENTITY)
  })

  it('확대한 그림은 화면 밖으로 나가지 않고, 작으면 가운데에 둔다', () => {
    const v = clampOffset({ scale: 2, x: 9999, y: 9999 }, fitted, viewport)
    expect(v.x).toBe(200) // (400*2 - 400) / 2
    expect(v.y).toBe(0) // 200*2 = 400 < 800 — 세로는 화면보다 작다
    expect(zoomAt({ scale: 2, x: 50, y: 0 }, 1, 10, 10, fitted, viewport)).toEqual(IDENTITY)
  })
})

describe('날짜 표기', () => {
  beforeEach(() => chooseLanguage('ko'))
  const now = new Date('2026-10-29T12:00:00Z')

  it('상대 시간은 가장 큰 단위로, 이름이 있으면 이름으로', () => {
    expect(relativeTime(new Date('2026-10-20T12:00:00Z'), now)).toBe('지난주')
    expect(relativeTime(new Date('2026-10-28T12:00:00Z'), now)).toBe('어제')
    expect(relativeTime(new Date('2026-10-29T09:00:00Z'), now)).toBe('3시간 전')
    expect(relativeTime(new Date('2026-10-29T11:30:00Z'), now)).toBe('30분 전')
    chooseLanguage('en')
    expect(relativeTime(new Date('2026-10-20T12:00:00Z'), now)).toBe('last week')
    expect(relativeTime(new Date('2026-10-26T12:00:00Z'), now)).toBe('3 days ago')
  })

  it('전체 날짜는 앱 언어의 순서로', () => {
    const d = new Date(2026, 9, 20, 14, 58)
    expect(fullDate(d)).toBe('2026년 10월 20일 오후 2:58')
    chooseLanguage('en')
    expect(fullDate(d)).toMatch(/^October 20, 2026.*2:58\sPM$/)
  })
})

describe('사진 주소 갱신', () => {
  it('저절로 하는 갱신은 30 초에 한 번, 사람이 누른 것은 언제나', () => {
    expect(shouldRenew(false, null, 1000)).toBe(true)
    expect(shouldRenew(false, 1000, 20_000)).toBe(false)
    expect(shouldRenew(false, 1000, 31_000)).toBe(true)
    expect(shouldRenew(true, 1000, 2000)).toBe(true)
  })

  it('여러 칸이 한꺼번에 실패해도 한 번만 나가고 결과를 같이 받는다', async () => {
    let t = 0
    const r = createPhotoRenewal(() => t)
    let calls = 0
    let release: () => void = () => {}
    const work = () => {
      calls += 1
      return new Promise<void>((res) => (release = res))
    }
    const a = r.run(false, work)
    const b = r.run(false, work)
    release()
    expect(await a).toBe(true)
    expect(await b).toBe(true)
    expect(calls).toBe(1)
    t = 5000
    expect(await r.run(false, work)).toBe(false) // 30 초 안
    expect(calls).toBe(1)
  })

  it('서명 쿼리를 뗀 주소가 사진의 이름이다', () => {
    expect(unsigned('https://s.test/posts/a.png?X-Amz-Signature=1#x')).toBe('https://s.test/posts/a.png')
    expect(unsigned('not a url')).toBe('not a url')
  })
})
