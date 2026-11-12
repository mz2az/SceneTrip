import { act, cleanup, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { chooseLanguage } from '@/i18n'
import { LEGAL_LINKS } from './legalLinks'
import { HelpSection } from './ProfileSettings'

/*
 * MZ2AZ-551 — 마이페이지 「문의하기」 의 「메일 쓰기」(mailto)는 메일 앱이 없는 기기(데스크톱 웹·일부 안드로이드)에서 아무 일도 안 일어난다.
 * 주소를 클립보드에 복사하는 단추를 둔다.
 */
let writeText: ReturnType<typeof vi.fn>

beforeEach(() => {
  chooseLanguage('ko')
  writeText = vi.fn(async () => {})
  Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })
})
afterEach(cleanup)

describe('문의하기 — 주소 복사', () => {
  it('주소 복사 단추를 누르면 문의 메일 주소가 클립보드에 들어간다', async () => {
    render(<HelpSection onReplay={() => {}} onLanguageChosen={() => {}} />)
    const copy = screen.getByRole('button', { name: /복사/ })
    await act(async () => {
      copy.click()
    })
    expect(writeText).toHaveBeenCalledWith(LEGAL_LINKS.supportEmail)
  })
})
