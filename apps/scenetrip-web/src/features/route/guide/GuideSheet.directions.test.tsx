/*
 * 가이드 답의 장소 카드에 「길찾기」 단추(MZ2AZ-554). `onDirections` 를 주면 펼친 카드에 단추가 뜨고, 누르면 그 장소로 부른다.
 * 서버는 부르지 않는다 — 카드 상세는 `cardProps.fetchCard` 가짜가 준다.
 */
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { chooseLanguage } from '@/i18n'
import type { PlaceCardData } from '../poi/poiCard'
import type { RoutePlace } from '../poi/poiPlace'
import { GuideSheet } from './GuideSheet'
import { GuideSession } from './guideSession'

const place: RoutePlace = {
  id: 'place-42', name: '덕수궁돌담길', category: '산책로', address: '서울 중구 정동', distanceMeters: 300, distanceBasis: 'guideAnchor',
  latitude: 37.5658, longitude: 126.9751, group: null, source: 'place', linkedPlaceId: null,
  displayName: null, nameRoman: null, categoryLabel: null, displayAddress: null,
}

const card: PlaceCardData = { title: null, reading: null, category: '산책로', address: '서울 중구 정동', phone: null, photos: [], naverUrl: null, rating: null, detailed: true, info: null }

function sessionWithPicked(): GuideSession {
  const s = new GuideSession()
  s.store.set((st) => ({ ...st, turns: [{ id: 1, role: 'assistant', text: '여기예요' }], places: [place], picked: place }))
  return s
}

beforeEach(() => {
  chooseLanguage('ko')
  vi.stubGlobal('fetch', vi.fn(async () => new Response(null, { status: 202 })))
  // jsdom 에 없는 것 — 창의 스크롤·크기 관찰은 이 시험의 관심이 아니다.
  vi.stubGlobal('ResizeObserver', class { observe() {} unobserve() {} disconnect() {} })
  Element.prototype.scrollTo = () => {}
})
afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

const directionsButton = () => screen.queryByRole('button', { name: /길찾기/ }) ?? screen.queryByText(/길찾기/)

describe('GuideSheet — 장소 카드 「길찾기」', () => {
  it('onDirections 를 주면 펼친 카드에 길찾기 단추가 뜨고, 누르면 그 장소로 부른다', async () => {
    const onDirections = vi.fn()
    const props = { session: sessionWithPicked(), here: { latitude: 37.56, longitude: 126.97 }, onAdd: vi.fn(), onDirections, cardProps: { fetchCard: async () => card } }
    render(<GuideSheet {...props} />)
    await waitFor(() => expect(directionsButton()).not.toBeNull())
    await act(async () => {
      fireEvent.click(directionsButton()!)
    })
    expect(onDirections).toHaveBeenCalledTimes(1)
    expect(onDirections).toHaveBeenCalledWith(place)
  })

  it('코스에 이미 담긴 곳이어도 길찾기 단추가 뜬다', async () => {
    const props = { session: sessionWithPicked(), here: { latitude: 37.56, longitude: 126.97 }, onAdd: vi.fn(), isAdded: () => true, onDirections: vi.fn(), cardProps: { fetchCard: async () => card } }
    render(<GuideSheet {...props} />)
    await waitFor(() => expect(directionsButton()).not.toBeNull())
  })
})
