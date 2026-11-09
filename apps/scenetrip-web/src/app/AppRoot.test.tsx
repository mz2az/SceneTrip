import { act, cleanup, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { chooseLanguage, currentLang } from '@/i18n'
import { AppRoot } from './AppRoot'

// 시작 화면만 검사하므로 지도·네트워크가 있는 실제 탭은 띄우지 않는다.
vi.mock('@/app/RootTabs', () => ({
  RootTabs: () => <div data-testid="root-tabs" />,
}))

// 새 버전 알림은 따로 시험한다(UpdatePrompt.test.tsx). 여기서는 붙어 있는지만 본다.
vi.mock('@/features/update/UpdatePrompt', () => ({
  UpdatePrompt: () => <div data-testid="update-prompt" />,
}))

beforeEach(() => {
  localStorage.clear()
  sessionStorage.clear()
  vi.useFakeTimers()
})

afterEach(() => {
  cleanup()
  vi.clearAllTimers()
  vi.useRealTimers()
})

describe('AppRoot — 첫 실행', () => {
  it('타이머를 기다리지 않고 앱을 바로 그린다', () => {
    render(<AppRoot />)

    expect(screen.getByTestId('root-tabs')).toBeTruthy()
  })

  it.each([0, 3000])('%dms 뒤에도 스플래시·언어 선택·사용법 문구가 없다', (elapsed) => {
    render(<AppRoot />)
    if (elapsed > 0) act(() => vi.advanceTimersByTime(elapsed))

    expect(screen.queryAllByText(/^SceneTrip$/i)).toHaveLength(0)
    expect(screen.queryAllByText(/Stand where the scene happened|장면이 있던 곳|장면이 펼쳐진 곳|장면이 촬영된 곳/i)).toHaveLength(0)
    expect(screen.queryAllByText(/Choose your language|언어를 선택하세요/i)).toHaveLength(0)
    expect(screen.queryAllByRole('button', { name: /^(건너뛰기|Skip|다음|Next|시작하기|Get started)$/i })).toHaveLength(0)
  })

  it.each([0, 3000])('%dms 뒤에도 앱을 덮는 zIndex 100 덮개가 없다', (elapsed) => {
    const { container } = render(<AppRoot />)
    if (elapsed > 0) act(() => vi.advanceTimersByTime(elapsed))

    const overlays = Array.from(container.querySelectorAll('div')).filter(({ style }) => (
      style.position === 'absolute'
      && (style.inset === '0' || style.inset === '0px')
      && style.zIndex === '100'
    ))
    expect(overlays).toHaveLength(0)
  })

  it('언어가 바뀌면 RootTabs를 다시 만든다', () => {
    render(<AppRoot />)
    const firstTabs = screen.getByTestId('root-tabs')

    act(() => chooseLanguage(currentLang() === 'en' ? 'ko' : 'en'))

    expect(screen.getAllByTestId('root-tabs')).toHaveLength(1)
    expect(screen.getByTestId('root-tabs')).not.toBe(firstTabs)
    expect(firstTabs.isConnected).toBe(false)
  })

  it('새 버전 알림(UpdatePrompt)을 함께 붙인다', () => {
    render(<AppRoot />)

    expect(screen.getByTestId('update-prompt')).toBeTruthy()
  })
})
