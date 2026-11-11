/*
 * 시안(proto) 편집 화면은 지도를 펼친 채로 연다 (MZ2AZ-556 D).
 *
 * 지금까지는 목록만 보이고 「Map」 알약을 눌러야 지도가 펼쳐졌다. 이제 처음부터 지도가 펼쳐져 있고 알약은 「List only」 다.
 * 누르면 목록만 남고 알약이 「Map」 으로 바뀐다. 처음 안내(CoachTour)의 지도 단계도 「눌러서 지도를 펼친다」 가 아니라
 * 지도와 목록을 오가는 단추로 설명한다.
 */
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { CoachStep } from '@/features/proto/CoachTour'

const h = vi.hoisted(() => ({ steps: [] as CoachStep[] }))

// 지도는 jsdom 에서 그릴 수 없다 — 빈 상자로 바꾼다.
vi.mock('../map/RouteMap', () => ({ RouteMap: () => <div data-testid="route-map" /> }))
// 처음 안내는 단계만 받아 둔다.
vi.mock('@/features/proto/CoachTour', () => ({
  CoachTour: ({ steps }: { steps: CoachStep[] }) => {
    h.steps = steps
    return null
  },
}))
// 시안을 켠다 — 로컬 개발 서버 + proto 'b'.
vi.mock('@/features/proto/protoState', async (importOriginal) => {
  const orig = await importOriginal<typeof import('@/features/proto/protoState')>()
  orig.protoStore.set((s) => ({ ...s, proto: 'b' }))
  return { ...orig, protoAvailable: true, isFreshCourse: () => false }
})

import { chooseLanguage } from '@/i18n'
import { makeCourse, makeDay, makeStop } from '../models'
import { RouteEditor } from './RouteEditor'

function draftCourse() {
  const stop = makeStop({ id: 100, name: 'Seoul Central High School', type: null, address: 'Jongno-gu', latitude: 37.5, longitude: 127 }, { type: 'place' })
  return makeCourse({ title: 'Goblin day trip', days: [makeDay([stop])], madeByAI: true })
}

// jsdom 에는 ResizeObserver 가 없다(목록 줄·서랍이 쓴다).
class NoopResizeObserver { observe() {} unobserve() {} disconnect() {} }
vi.stubGlobal('ResizeObserver', NoopResizeObserver)

beforeEach(() => {
  chooseLanguage('en')
  vi.useFakeTimers({ shouldAdvanceTime: true })
  h.steps = []
})

afterEach(() => {
  cleanup()
  vi.useRealTimers()
})

describe('RouteEditor (시안) — 지도를 펼친 채로 연다 (MZ2AZ-556 D)', () => {
  it('(준비 확인) 시안 편집 화면이 그려지고 지도·목록 알약이 하나 있다', () => {
    const { container } = render(<RouteEditor course={draftCourse()} isNew onClose={() => {}} />)
    expect(container.querySelectorAll('[data-coach="editor-map"]')).toHaveLength(1)
  })

  it('새 코스는 지도가 펼쳐져 있고 알약은 「List only」 다', () => {
    render(<RouteEditor course={draftCourse()} isNew onClose={() => {}} />)
    const pill = screen.getByRole('button', { name: /list only/i })
    expect(pill.getAttribute('aria-pressed')).toBe('true')
    expect(screen.queryByRole('button', { name: /^map$/i })).toBeNull()
    // 지도 상자가 접혀(aria-hidden) 있지 않다
    expect(screen.getByTestId('route-map').closest('[aria-hidden="true"]')).toBeNull()
  })

  it('「List only」 를 누르면 목록만 남고 알약이 「Map」 으로 바뀐다', async () => {
    render(<RouteEditor course={draftCourse()} isNew onClose={() => {}} />)
    fireEvent.click(screen.getByRole('button', { name: /list only/i }))
    await act(async () => { await vi.advanceTimersByTimeAsync(1000) })
    const pill = screen.getByRole('button', { name: /^map$/i })
    expect(pill.getAttribute('aria-pressed')).toBe('false')
    expect(screen.queryByRole('button', { name: /list only/i })).toBeNull()
  })

  it('처음 안내의 지도 단계는 지도와 목록을 오가는 단추로 설명한다 — 「눌러서 지도를 연다」 가 아니다', () => {
    render(<RouteEditor course={draftCourse()} isNew onClose={() => {}} />)
    const mapStep = h.steps.find((s) => s.target === '[data-coach="editor-map"]')
    expect(mapStep).toBeDefined()
    const text = mapStep!.text
    // 지도가 이미 펼쳐져 있으니 「지도를 연다/펼친다」 로 시작하지 않는다.
    expect(text).not.toMatch(/^\s*(tap to )?open\b/i)
    expect(text).not.toMatch(/open the (route on a )?map/i)
    // 지도와 목록 둘 다 말한다.
    expect(text).toMatch(/map/i)
    expect(text).toMatch(/list/i)
  })
})
