/*
 * 마이페이지 「내 코스」 시트의 「경로여정에서 열기」 (MZ2AZ-599).
 *
 *   시트(`/profile/courses`)에서 코스 한 줄을 펼쳐 「경로여정에서 열기」 를 누르면 주소가 `/courses/{id}` 가 된다.
 *   그 상태에서 한 칸 뒤로 가면 마이페이지(`/profile`)다 — 시트가 열린 주소 `/profile/courses` 로 돌아가지 않는다.
 */
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, type NavigateFunction, useLocation, useNavigate } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.hoisted(() => {
  const env = import.meta.env as Record<string, string>
  env.VITE_API_BASE = 'http://test.local'
})

import type { components } from '@/api/generated/schema'
import { paths } from '@/app/routes'
import { OverlayRootContext } from '@/design/overlay'
import { chooseLanguage } from '@/i18n'
import { clearSession } from '@/state/auth'
import { cartStore } from '@/state/cart'
import { ProfileCover } from './ProfileCover'

type CourseSummary = components['schemas']['CourseSummary']
type CourseDetail = components['schemas']['CourseDetail']

const COURSE: CourseSummary = {
  id: 7,
  title: '서울 촬영지 하루',
  dayCount: 1,
  startDate: null,
  status: 'upcoming',
  origin: 'self',
  placeCount: 0,
  createdAt: '2026-11-20T00:00:00Z',
  updatedAt: '2026-11-20T00:00:00Z',
}

const DETAIL: CourseDetail = {
  ...COURSE,
  days: [{ dayNumber: 1, items: [], dwellMinutes: 0, travelMinutes: 0, totalMinutes: 0, travelBasis: 'straight-line' }],
}

const json = (status: number, body: unknown) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

let location: ReturnType<typeof useLocation> | null = null
function Probe() {
  location = useLocation()
  return null
}

let navigate: NavigateFunction | null = null
function Navigator() {
  navigate = useNavigate()
  return null
}

/** 앱(App.tsx)처럼 시트가 그려질 자리(OverlayRootContext)를 준다 — 없으면 GlassSheet 가 아무것도 그리지 않는다. */
let overlayRoot: HTMLDivElement

function renderProfile() {
  return render(
    <OverlayRootContext.Provider value={overlayRoot}>
      <MemoryRouter initialEntries={[paths.profile]}>
        <ProfileCover onClose={() => {}} />
        <Probe />
        <Navigator />
      </MemoryRouter>
    </OverlayRootContext.Provider>,
  )
}

/** 마이페이지 → 「내 코스」 시트 → 코스 줄 펼치기 → 「경로여정에서 열기」. */
async function openCourseFromSheet() {
  renderProfile()
  fireEvent.click(screen.getByText('내 코스').closest('button')!)
  expect(location?.pathname).toBe(paths.profileSection('courses'))

  const row = (await screen.findByText(COURSE.title)).closest('button')!
  fireEvent.click(row)
  const open = await screen.findByRole('button', { name: `경로여정에서 열기 — ${COURSE.title}` })
  fireEvent.click(open)
}

beforeEach(() => {
  chooseLanguage('ko')
  clearSession()
  location = null
  navigate = null
  overlayRoot = document.createElement('div')
  document.body.appendChild(overlayRoot)
  vi.stubGlobal(
    'fetch',
    vi.fn(async (req: Request) => {
      const path = new URL(req.url).pathname
      if (/\/courses$/.test(path)) return json(200, { items: [COURSE], totalCount: 1 })
      if (path.endsWith(`/courses/${COURSE.id}`)) return json(200, DETAIL)
      return json(200, { items: [], total: 0 })
    }),
  )
  cartStore.set({ loadState: 'loaded', placeIds: new Set(), items: [], pois: [], poiIds: new Set(), toast: null })
})
afterEach(() => {
  cleanup()
  overlayRoot.remove()
  vi.unstubAllGlobals()
  cartStore.set({ loadState: 'notLoaded', placeIds: new Set(), items: [], pois: [], poiIds: new Set(), toast: null })
  chooseLanguage('ko')
})

describe('내 코스 시트 — 「경로여정에서 열기」(MZ2AZ-599)', () => {
  it('누르면 주소가 /courses/{코스 id} 가 된다', async () => {
    await openCourseFromSheet()
    expect(location?.pathname).toBe(paths.course(COURSE.id))
  })

  it('연 뒤 한 칸 뒤로 가면 /profile 이다(시트 주소 /profile/courses 가 아니다)', async () => {
    await openCourseFromSheet()
    act(() => {
      void navigate!(-1)
    })
    expect(location?.pathname).toBe(paths.profile)
  })
})
