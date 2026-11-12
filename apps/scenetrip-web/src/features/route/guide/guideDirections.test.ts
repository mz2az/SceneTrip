/*
 * 가이드 답의 장소 카드 「길찾기」(MZ2AZ-554) — 누르면 무엇을 어떤 순서로 하나.
 * iOS tests/RouteGuideDirectionsTests.swift(`RouteGuideDirections.steps`) 와 같은 값이다.
 *
 * - 로그인 안 했으면 길찾기 로그인 안내(이유 문구)만 띄운다 — 담지도, 안내를 켜지도 않는다.
 * - 코스에 없는 곳이면 먼저 담는다(가이드의 기존 담기 길).
 * - 여행 중이 아니면 그곳을 목적지로 여행을 시작하고, 이미 여행 중이면 목적지만 바꾼다.
 */
import { describe, expect, it } from 'vitest'
import { type GuideDirectionsStep, guideDirectionsSteps } from './guideRules'

const steps = (signedIn: boolean, inCourse: boolean, tripRunning: boolean): GuideDirectionsStep[] => guideDirectionsSteps({ signedIn, inCourse, tripRunning })

describe('guideDirectionsSteps — 가이드 카드 「길찾기」', () => {
  it('로그인 안 했으면 로그인 안내만', () => {
    for (const inCourse of [false, true]) for (const running of [false, true]) expect(steps(false, inCourse, running)).toEqual(['signIn'])
  })
  it('코스에 없고 여행 전이면 담고 여행을 시작한다', () => {
    expect(steps(true, false, false)).toEqual(['add', 'startTrip'])
  })
  it('코스에 있고 여행 전이면 담지 않고 여행을 시작한다', () => {
    expect(steps(true, true, false)).toEqual(['startTrip'])
  })
  it('코스에 없고 여행 중이면 담고 목적지만 바꾼다', () => {
    expect(steps(true, false, true)).toEqual(['add', 'switchTarget'])
  })
  it('코스에 있고 여행 중이면 목적지만 바꾼다', () => {
    expect(steps(true, true, true)).toEqual(['switchTarget'])
  })
  it('여행 중이면 여행을 다시 시작하지 않는다', () => {
    for (const inCourse of [false, true]) expect(steps(true, inCourse, true)).not.toContain('startTrip')
  })
})
