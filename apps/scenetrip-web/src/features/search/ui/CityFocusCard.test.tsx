/*
 * 지도 위 도시 카드 — 명세만 보고 쓴 시험. 정본: docs/project/plans/web-city-map-first.md §1·§4.
 *
 * - 「서울 촬영지 124곳」 + 「이 도시로 코스 만들기」 + ✕.
 * - 「코스 만들기」 → protoActions.plan(city.key), plan_start 의 entry = 'city_map', 색칠·카드를 지운다(cityFocusStore 비움).
 * - ✕(닫기/Close) → cityFocusStore 를 비운다. plan_start 는 보내지 않는다.
 */
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/analytics', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/analytics')>()
  return { ...actual, track: vi.fn() }
})
// 칩을 고를 때 촬영지를 받아 오는 길은 이 시험의 관심 밖 — 네트워크를 타지 않게 막는다.
vi.mock('@/features/home/homeCityModel', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/features/home/homeCityModel')>()),
  ensureCityPlaces: vi.fn(async () => {}),
}))

import { track } from '@/analytics'
import { chooseLanguage } from '@/i18n'
import { cityFocusStore, focusCity } from '@/features/home/cityFocus'
import type { HomeCity } from '@/features/home/homeCityModel'
import { protoActions } from '@/features/proto/protoState'
import { CityFocusCard } from './CityFocusCard'

const seoul: HomeCity = { key: '서울', ko: '서울', name: '서울', places: [], placesLoaded: false, count: 124 }

const planStarts = () => vi.mocked(track).mock.calls.map(([e]) => e).filter((e) => e.name === 'plan_start')
const planButton = () => screen.getByRole('button', { name: /코스 만들기|Plan a trip/ })
const closeButton = () => screen.getByRole('button', { name: /^(닫기|Close)$/ })

let plan: ReturnType<typeof vi.spyOn>

beforeEach(() => {
  chooseLanguage('ko')
  vi.mocked(track).mockClear()
  plan = vi.spyOn(protoActions, 'plan').mockImplementation(() => {})
  focusCity('서울')
})
afterEach(() => {
  cleanup()
  plan.mockRestore()
  cityFocusStore.set({ key: null })
})

describe('CityFocusCard', () => {
  it('도시 이름과 촬영지 수를 보인다', () => {
    render(<CityFocusCard city={seoul} />)
    expect(screen.getByText(/서울 촬영지 124곳/)).toBeTruthy()
  })

  it('서버 수가 없으면 받은 촬영지 수를 보인다', () => {
    const busan: HomeCity = {
      key: '부산', ko: '부산', name: '부산', placesLoaded: true,
      places: [1, 2, 3].map((id) => ({ id, latitude: 35.1 + id * 0.01, longitude: 129.0 })) as unknown as HomeCity['places'],
    }
    render(<CityFocusCard city={busan} />)
    expect(screen.getByText(/부산 촬영지 3곳/)).toBeTruthy()
  })

  it('영어에서도 수를 보인다', () => {
    chooseLanguage('en')
    render(<CityFocusCard city={{ ...seoul, name: 'Seoul' }} />)
    expect(screen.getByText(/124/)).toBeTruthy()
    expect(closeButton()).toBeTruthy()
  })

  it('「코스 만들기」 는 그 도시로 코스 만들기를 연다', () => {
    render(<CityFocusCard city={seoul} />)
    fireEvent.click(planButton())
    expect(plan).toHaveBeenCalledTimes(1)
    expect(plan).toHaveBeenCalledWith('서울')
  })

  it('「코스 만들기」 는 plan_start(entry=city_map) 를 한 번 보낸다', () => {
    render(<CityFocusCard city={seoul} />)
    fireEvent.click(planButton())
    expect(planStarts()).toEqual([{ name: 'plan_start', params: { entry: 'city_map' } }])
  })

  it('「코스 만들기」 는 색칠·카드를 지운다(고른 도시를 비운다)', () => {
    render(<CityFocusCard city={seoul} />)
    fireEvent.click(planButton())
    expect(cityFocusStore.get().key).toBeNull()
  })

  it('✕ 는 고른 도시를 비우고 코스 만들기를 열지 않는다', () => {
    render(<CityFocusCard city={seoul} />)
    fireEvent.click(closeButton())
    expect(cityFocusStore.get().key).toBeNull()
    expect(plan).not.toHaveBeenCalled()
    expect(planStarts()).toEqual([])
  })
})
