/*
 * 홈 스크롤 깊이 — 정본: docs/project/plans/web-proto-events.md §2-1.
 *
 *   homeScrollDepths(scrollTop, clientHeight, scrollHeight, sent): number[]
 *   - seen = (scrollTop + clientHeight) / scrollHeight × 100. scrollHeight <= clientHeight 면 100.
 *   - 25·50·75·100 중 seen 이상(>=)이고 sent 에 없는 값을 작은 것부터. 반올림하지 않는다(24.9 는 25 가 아니다).
 *   - sent 는 이미 보낸 깊이들(세션 동안 호출부가 쌓는다).
 */
import { describe, expect, it } from 'vitest'
import { homeScrollDepths } from './homeScroll'

const none = () => new Set<number>()

describe('homeScrollDepths', () => {
  it('맨 위에서 화면 한 장이 전체의 20% 면 아무것도 없다', () => {
    expect(homeScrollDepths(0, 200, 1000, none())).toEqual([])
  })

  it('정확히 25% 면 25', () => {
    expect(homeScrollDepths(50, 200, 1000, none())).toEqual([25])
  })

  it('24.9% 는 25 가 아니다 — 올림하지 않는다', () => {
    expect(homeScrollDepths(49, 200, 1000, none())).toEqual([])
  })

  it('소수점 스크롤 위치도 그대로 비교한다 (74.99% → 25·50 까지)', () => {
    expect(homeScrollDepths(549.9, 200, 1000, none())).toEqual([25, 50])
  })

  it.each([
    [200, 300, 1000, [25, 50]], // 50%
    [550, 200, 1000, [25, 50, 75]], // 75%
    [800, 200, 1000, [25, 50, 75, 100]], // 100%
  ] as const)('경계값 그대로: top=%d client=%d height=%d → %j', (top, client, height, want) => {
    expect(homeScrollDepths(top, client, height, none())).toEqual(want)
  })

  it('한 번에 여러 단계를 건너뛰면 작은 것부터 모두 돌려준다', () => {
    expect(homeScrollDepths(700, 300, 1000, none())).toEqual([25, 50, 75, 100])
  })

  it('이미 보낸 깊이는 건너뛴다', () => {
    expect(homeScrollDepths(550, 200, 1000, new Set([25, 50]))).toEqual([75])
    expect(homeScrollDepths(800, 200, 1000, new Set([25, 75]))).toEqual([50, 100])
  })

  it('다 보냈으면 빈 배열', () => {
    expect(homeScrollDepths(800, 200, 1000, new Set([25, 50, 75, 100]))).toEqual([])
  })

  it('위로 되돌아가도 이미 본 깊이를 다시 내지 않는다 (sent 가 막는다)', () => {
    const sent = new Set<number>()
    for (const d of homeScrollDepths(550, 200, 1000, sent)) sent.add(d)
    expect(homeScrollDepths(0, 200, 1000, sent)).toEqual([])
    expect(homeScrollDepths(800, 200, 1000, sent)).toEqual([100])
  })

  it('짧은 화면(scrollHeight <= clientHeight)은 100% 로 본다', () => {
    expect(homeScrollDepths(0, 800, 600, none())).toEqual([25, 50, 75, 100])
    expect(homeScrollDepths(0, 800, 800, none())).toEqual([25, 50, 75, 100])
    expect(homeScrollDepths(0, 800, 800, new Set([25, 50]))).toEqual([75, 100])
  })

  it('scrollHeight 가 0 이어도 NaN·예외 없이 100 으로 본다', () => {
    expect(homeScrollDepths(0, 0, 0, none())).toEqual([25, 50, 75, 100])
  })

  it('돌려주는 값은 정수다', () => {
    for (const d of homeScrollDepths(800, 200, 1000, none())) expect(Number.isInteger(d)).toBe(true)
  })
})
