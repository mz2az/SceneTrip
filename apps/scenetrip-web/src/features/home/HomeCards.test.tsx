import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { chooseLanguage } from '@/i18n'
import { HomeHeader } from './HomeCards'

beforeEach(() => chooseLanguage('ko'))
afterEach(cleanup)

describe('HomeHeader', () => {
  it('마이페이지 단추에 톱니바퀴를 표시하고 누르면 onProfile을 부른다', () => {
    const onProfile = vi.fn()
    render(<HomeHeader onProfile={onProfile} />)

    const button = screen.getByRole('button', { name: '마이페이지' })
    expect(button.querySelector('svg.lucide-settings')).not.toBeNull()

    fireEvent.click(button)
    expect(onProfile).toHaveBeenCalledTimes(1)
  })
})
