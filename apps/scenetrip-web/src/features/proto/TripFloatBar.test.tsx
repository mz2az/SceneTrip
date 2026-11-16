import { act, cleanup, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { chooseLanguage } from '@/i18n'
import { TripFloatBar } from './ProtoScreens'

beforeEach(() => {
  chooseLanguage('ko')
  vi.useFakeTimers()
  try { window.localStorage.clear() } catch { /* 저장소가 없으면 그대로 */ }
  // jsdom 의 크기는 0 이라 안내가 단추를 못 찾고 그리지 않는다 — 크기를 흉내 내서 안내가 있다면 실제로 뜨게 한다.
  vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue(
    { x: 10, y: 20, top: 20, left: 10, width: 100, height: 44, right: 110, bottom: 64, toJSON: () => ({}) } as DOMRect,
  )
})

afterEach(() => {
  vi.restoreAllMocks()
  cleanup()
  vi.useRealTimers()
  document.body.innerHTML = ''
})

describe('TripFloatBar — 홈의 떠 있는 「여행 코스 짜기」 줄', () => {
  // 2026-11-14 사용자 결정: 홈의 「첫 여행 계획을 짜 보세요」 코치 안내(home-plan-trip)는 없앴다(web-keep-flow.md §9-6).
  // 담은 곳이 없고 여행 중인 코스도 없는, 처음 온 사람의 홈에서도 안내는 뜨지 않는다.
  it('처음 온 사람(담은 곳·코스 없음)에게도 home-plan-trip 안내가 뜨지 않는다', async () => {
    render(
      <MemoryRouter>
        <TripFloatBar />
      </MemoryRouter>,
    )
    expect(screen.getByText('여행 코스 짜기')).toBeTruthy()

    await act(async () => { await vi.advanceTimersByTimeAsync(6000) })

    expect(screen.queryByRole('dialog')).toBeNull()
    expect(screen.queryByText('첫 여행 계획을 짜 보세요')).toBeNull()
    expect(document.querySelector('[data-coach="plan-trip"]')).toBeNull()
  })
})
