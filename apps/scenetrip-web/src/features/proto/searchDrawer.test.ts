import { describe, expect, it } from 'vitest'
import { DRAWER_HIGH, DRAWER_LOW, dragShare, FLICK_SPEED, settleShare, toggleShare } from './searchDrawer'

const MAP = 1000 // 지도 높이(px). 100px 끌면 비율 0.1.

describe('dragShare — 끄는 동안 손가락을 따라간다', () => {
  it('위로 100px(dy=-100) → 0.1 커진다', () => {
    expect(dragShare(0.42, -100, MAP)).toBeCloseTo(0.52, 6)
  })

  it('아래로 100px(dy=+100) → 0.1 작아진다', () => {
    expect(dragShare(0.78, 100, MAP)).toBeCloseTo(0.68, 6)
  })

  it('움직이지 않으면 시작 높이', () => {
    expect(dragShare(0.6, 0, MAP)).toBeCloseTo(0.6, 6)
  })

  it('지도 높이에 비례한다(500px 지도에서 100px = 0.2)', () => {
    expect(dragShare(0.42, -100, 500)).toBeCloseTo(0.62, 6)
  })

  it('경계에 딱 닿으면 경계 그대로', () => {
    expect(dragShare(0.42, -360, MAP)).toBeCloseTo(0.78, 6)
    expect(dragShare(0.78, 360, MAP)).toBeCloseTo(0.42, 6)
  })
})

describe('dragShare — 42% 아래·78% 위로는 조금만(고무줄)', () => {
  it('78% 위로 끌면 78% 보다는 크고, 손가락만큼은 아니다', () => {
    const raw = 0.78 + 0.1 // 손가락 그대로라면
    const s = dragShare(0.78, -100, MAP)
    expect(s).toBeGreaterThan(DRAWER_HIGH)
    expect(s).toBeLessThan(raw)
    // 「조금만」: 넘친 만큼의 절반도 안 된다
    expect(s - DRAWER_HIGH).toBeLessThan((raw - DRAWER_HIGH) / 2)
  })

  it('42% 아래로 끌면 42% 보다는 작고, 손가락만큼은 아니다', () => {
    const raw = 0.42 - 0.1
    const s = dragShare(0.42, 100, MAP)
    expect(s).toBeLessThan(DRAWER_LOW)
    expect(s).toBeGreaterThan(raw)
    expect(DRAWER_LOW - s).toBeLessThan((DRAWER_LOW - raw) / 2)
  })

  it('더 끌수록 더 늘어난다(멈추지 않는다)', () => {
    const a = dragShare(0.78, -50, MAP)
    const b = dragShare(0.78, -150, MAP)
    expect(b).toBeGreaterThan(a)
  })

  it('아주 멀리 끌어도 경계는 넘되 지도 밖(0~1)으로는 안 나간다', () => {
    const up = dragShare(0.78, -100_000, MAP)
    expect(up).toBeGreaterThan(DRAWER_HIGH)
    expect(up).toBeLessThanOrEqual(1)
    const down = dragShare(0.42, 100_000, MAP)
    expect(down).toBeLessThan(DRAWER_LOW)
    expect(down).toBeGreaterThanOrEqual(0)
  })

  it('중간에서 시작해 경계를 넘으면 넘은 부분만 줄어든다', () => {
    // 0.6 에서 위로 300px: 0.78 까지는 그대로(0.18), 넘친 0.12 만 고무줄
    const s = dragShare(0.6, -300, MAP)
    expect(s).toBeGreaterThan(DRAWER_HIGH)
    expect(s).toBeLessThan(0.9)
  })
})

describe('settleShare — 손을 뗀 뒤 붙을 높이', () => {
  it.each([
    [0.45, DRAWER_LOW],
    [0.55, DRAWER_LOW],
    [0.65, DRAWER_HIGH],
    [0.75, DRAWER_HIGH],
    [0.3, DRAWER_LOW], // 고무줄로 아래 넘침
    [0.85, DRAWER_HIGH], // 고무줄로 위 넘침
  ])('천천히 놓으면 가까운 쪽: %s → %s', (share, expected) => {
    expect(settleShare(share, 0)).toBe(expected)
  })

  it('기준보다 느린 튕김(599px/s)은 가까운 쪽', () => {
    expect(settleShare(0.45, -599)).toBe(DRAWER_LOW)
    expect(settleShare(0.75, 599)).toBe(DRAWER_HIGH)
  })

  it('빠르게 위로 튕기면(-600px/s 이상) 낮은 곳 가까이서도 78%', () => {
    expect(FLICK_SPEED).toBe(600)
    expect(settleShare(0.45, -600)).toBe(DRAWER_HIGH)
    expect(settleShare(0.45, -1500)).toBe(DRAWER_HIGH)
  })

  it('빠르게 아래로 튕기면(+600px/s 이상) 높은 곳 가까이서도 42%', () => {
    expect(settleShare(0.75, 600)).toBe(DRAWER_LOW)
    expect(settleShare(0.75, 2000)).toBe(DRAWER_LOW)
  })

  it('이미 끝에서 같은 방향으로 튕겨도 그 끝', () => {
    expect(settleShare(0.8, -1000)).toBe(DRAWER_HIGH)
    expect(settleShare(0.4, 1000)).toBe(DRAWER_LOW)
  })
})

describe('toggleShare — 손잡이 누름', () => {
  it('42% → 78%', () => {
    expect(DRAWER_LOW).toBe(0.42)
    expect(toggleShare(0.42)).toBe(0.78)
  })

  it('78% → 42%', () => {
    expect(DRAWER_HIGH).toBe(0.78)
    expect(toggleShare(0.78)).toBe(0.42)
  })

  it('두 번 누르면 제자리', () => {
    expect(toggleShare(toggleShare(DRAWER_LOW))).toBe(DRAWER_LOW)
  })
})
