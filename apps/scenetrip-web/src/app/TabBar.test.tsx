// 탭바 — 누르는 동안 흐려진다(iOS `.buttonStyle(.plain)`, 시뮬레이터 실측 ≈ 0.7)
import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { chooseLanguage } from '@/i18n'
import { TabBar } from './TabBar'

beforeEach(() => chooseLanguage('ko'))
afterEach(cleanup)

describe('TabBar', () => {
  it('세 탭 모두 눌림(흐림) 효과 클래스를 가진다 — plain 으로 꺼 두지 않는다', () => {
    render(<TabBar selected="home" onSelect={() => {}} />)
    for (const name of ['작품검색', '홈', '커뮤니티']) {
      const button = screen.getByLabelText(name)
      expect(button.classList.contains('pressable')).toBe(true)
      expect(button.classList.contains('tab-press')).toBe(true)
    }
  })
})
