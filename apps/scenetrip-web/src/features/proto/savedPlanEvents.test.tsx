/*
 * 담은 곳으로 계획 짜기의 단계 이벤트 — 정본: docs/project/plans/analytics-events.md 「이벤트 보강 (2026-11-16, MZ2AZ-586)」.
 *
 *   ProtoSavedPlan 이 뜨면 plan_step { flow: 'saved', step: 'picker' } 한 번.
 *   「코스 만들기」 로 코스를 만들어 편집 화면이 열리면 plan_step { flow: 'saved', step: 'editor' } 한 번.
 *
 * 편집 화면(RouteEditor)은 지도를 그려 jsdom 에서 무겁다 — 빈 상자로 바꾼다.
 */
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/analytics', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/analytics')>()
  return { ...actual, track: vi.fn() }
})
vi.mock('@/features/route/editor/RouteEditor', () => ({ RouteEditor: () => <div data-testid="route-editor" /> }))

import { track } from '@/analytics'
import { chooseLanguage } from '@/i18n'
import { cartStore } from '@/state/cart'
import { ProtoSavedPlan } from './ProtoPlanFlows'

const steps = () =>
  vi
    .mocked(track)
    .mock.calls.map(([e]) => e as { name: string; params: Record<string, unknown> })
    .filter((e) => e.name === 'plan_step')
    .map((e) => e.params)

beforeEach(() => {
  chooseLanguage('ko')
  vi.mocked(track).mockClear()
  cartStore.set({ loadState: 'loaded', placeIds: new Set(), items: [], pois: [], poiIds: new Set(), toast: null })
})

afterEach(() => {
  cleanup()
})

function renderPlan() {
  return render(
    <MemoryRouter>
      <ProtoSavedPlan onClose={() => {}} />
    </MemoryRouter>,
  )
}

describe('ProtoSavedPlan — plan_step(saved)', () => {
  it('뜨면 picker 한 번 — 다시 그려도 더 보내지 않는다', () => {
    const view = renderPlan()
    view.rerender(
      <MemoryRouter>
        <ProtoSavedPlan onClose={() => {}} />
      </MemoryRouter>,
    )
    expect(steps()).toEqual([{ flow: 'saved', step: 'picker' }])
  })

  it('「코스 만들기」 로 편집 화면이 열리면 editor 한 번', async () => {
    renderPlan()
    expect(screen.queryByTestId('route-editor')).toBeNull()
    await act(async () => {
      fireEvent.click(screen.getByText('코스 만들기'))
    })
    expect(screen.getByTestId('route-editor')).toBeTruthy()
    expect(steps()).toEqual([
      { flow: 'saved', step: 'picker' },
      { flow: 'saved', step: 'editor' },
    ])
  })
})
