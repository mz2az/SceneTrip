import { cleanup, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.hoisted(() => {
  const env = import.meta.env as Record<string, string>
  env.VITE_API_BASE = 'http://test.local'
})

import { chooseLanguage } from '@/i18n'
import { clearSession } from '@/state/auth'
import { ProfileCover } from './ProfileCover'

/*
 * MZ2AZ-551 — 마이페이지에서 「준비 중」 줄을 걷어 낸다. 눌러도 아무 일이 없는 줄(알림)과 멘토가 헷갈려 한 「이 기기의 기록 지우기」 를 보이지 않는다.
 * 비회원 화면 기준(기기 기록 지우기는 비회원에게만 보이던 줄이다).
 */
beforeEach(() => {
  chooseLanguage('ko')
  clearSession()
  vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ items: [], total: 0 }), { status: 200, headers: { 'Content-Type': 'application/json' } })))
})
afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

function renderProfile() {
  return render(
    <MemoryRouter initialEntries={['/profile']}>
      <ProfileCover onClose={() => {}} />
    </MemoryRouter>,
  )
}

describe('마이페이지 — 준비 중 줄을 숨긴다', () => {
  it('「준비 중」 절과 알림 줄이 없다', () => {
    renderProfile()
    expect(screen.queryAllByText('준비 중')).toHaveLength(0)
    expect(screen.queryAllByText('알림')).toHaveLength(0)
  })

  it('「이 기기의 기록 지우기」 줄이 없다', () => {
    renderProfile()
    expect(screen.queryAllByText('이 기기의 기록 지우기')).toHaveLength(0)
  })

  it('다른 절(약관 및 정책)은 그대로 있다 — 화면이 통째로 비어서 통과하는 것이 아니다', () => {
    renderProfile()
    expect(screen.getByText('약관 및 정책')).toBeTruthy()
  })
})
