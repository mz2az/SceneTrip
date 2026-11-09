import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import type { components } from '@/api/generated/schema'
import { chooseLanguage } from '@/i18n'
import { SpotRow } from './protoParts'

type PlaceSummary = components['schemas']['PlaceSummary']

function place(over: Partial<PlaceSummary> = {}): PlaceSummary {
  return {
    id: 1,
    name: '오죽헌',
    address: '강원 강릉시 율곡로 3139번길 24',
    latitude: 37.7794,
    longitude: 128.8787,
    imageUrl: null,
    contents: [
      { contentId: 10, title: '도깨비' },
      { contentId: 11, title: '두 번째 작품' },
    ],
    sceneDescription: '김신이 메밀꽃밭에서 은탁을 만나는 장면',
    ...over,
  }
}

beforeEach(() => chooseLanguage('ko'))
afterEach(cleanup)

describe('SpotRow', () => {
  it('장소 이름·첫 작품 제목·장면 설명을 보인다', () => {
    render(<SpotRow place={place()} />)

    expect(screen.getByText('오죽헌')).toBeTruthy()
    expect(screen.getByText('도깨비')).toBeTruthy()
    expect(screen.queryByText('두 번째 작품')).toBeNull()
    expect(screen.getByText('김신이 메밀꽃밭에서 은탁을 만나는 장면')).toBeTruthy()
  })

  it('장면 설명이 있으면 주소 대신 장면 설명을 보인다', () => {
    render(<SpotRow place={place()} />)

    expect(screen.queryByText('강원 강릉시 율곡로 3139번길 24')).toBeNull()
  })

  it.each([
    ['null', null],
    ['빈 글', ''],
    ['공백만', '   '],
  ])('장면 설명이 %s 이면 주소를 보인다', (_label, sceneDescription) => {
    render(<SpotRow place={place({ sceneDescription })} />)

    expect(screen.getByText('강원 강릉시 율곡로 3139번길 24')).toBeTruthy()
  })

  it('actions 를 행 안에 그린다', () => {
    const { container } = render(
      <SpotRow place={place()} actions={<button type="button">일정에 넣기</button>} />,
    )

    const action = screen.getByRole('button', { name: '일정에 넣기' })
    expect(container.contains(action)).toBe(true)
    // 행(이름이 든 같은 덩어리) 안에 있어야 한다.
    const row = container.firstElementChild!
    expect(row.contains(action)).toBe(true)
    expect(row.contains(screen.getByText('오죽헌'))).toBe(true)
  })
})
