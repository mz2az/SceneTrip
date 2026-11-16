import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const h = vi.hoisted(() => ({ env: 'other' as string }))
vi.mock('@/features/keep/installPrompt', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/features/keep/installPrompt')>()),
  currentKeepEnv: () => h.env,
}))

import { chooseLanguage } from '@/i18n'
import { HomeHeader } from './HomeCards'

beforeEach(() => {
  chooseLanguage('ko')
  h.env = 'other'
})
afterEach(cleanup)

describe('HomeHeader', () => {
  it('마이페이지 단추에 톱니바퀴를 표시하고 누르면 onProfile을 부른다', () => {
    const onProfile = vi.fn()
    // 마이맵 단추는 늘 있다(web-keep-flow.md §12) — onMyMap 이 필수가 됐다.
    render(<HomeHeader onProfile={onProfile} onMyMap={vi.fn()} />)

    const button = screen.getByRole('button', { name: '마이페이지' })
    expect(button.querySelector('svg.lucide-settings')).not.toBeNull()

    fireEvent.click(button)
    expect(onProfile).toHaveBeenCalledTimes(1)
  })

  // web-home-install.md §1 (MZ2AZ-589) — 「마이맵」 알약과 톱니바퀴 사이, 홈 화면 앱으로 열었으면 없다.
  it('「홈 화면에 추가」 단추가 「마이맵」 알약과 톱니바퀴 사이에 있다', () => {
    render(<HomeHeader onProfile={vi.fn()} onMyMap={vi.fn()} />)
    const myMap = screen.getByRole('button', { name: '마이맵' })
    const add = screen.getByRole('button', { name: '홈 화면에 추가' })
    const gear = screen.getByRole('button', { name: '마이페이지' })
    expect(myMap.compareDocumentPosition(add) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(add.compareDocumentPosition(gear) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  it('홈 화면 앱(installed)으로 열었으면 「홈 화면에 추가」 단추가 없다', () => {
    const first = render(<HomeHeader onProfile={vi.fn()} onMyMap={vi.fn()} />)
    expect(screen.getByRole('button', { name: '홈 화면에 추가' })).toBeTruthy()
    first.unmount()
    h.env = 'installed'
    render(<HomeHeader onProfile={vi.fn()} onMyMap={vi.fn()} />)
    expect(screen.queryByRole('button', { name: '홈 화면에 추가' })).toBeNull()
    expect(screen.getByRole('button', { name: '마이페이지' })).toBeTruthy()
  })
})
