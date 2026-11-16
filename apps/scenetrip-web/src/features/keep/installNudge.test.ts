/*
 * 홈 화면 추가 권유 띠의 상태 — 정본: docs/project/plans/web-home-install.md §1 (MZ2AZ-589).
 *
 *   installNudge.request(trigger)  shouldNudge(currentKeepEnv(), …) 가 참이면 그 trigger 로 보이고, 세션 표식을 남기고,
 *                                  course_created 면 courseNudged=true
 *   installNudge.dismiss()/accept() 숨기고 dismissedAt=Date.now()
 *   installNudge.current()         보이는 trigger 또는 null
 *   useInstallNudge()              같은 값, 바뀌면 다시 그린다
 *   저장: localStorage 'st.installNudge' = JSON {dismissedAt, courseNudged}, sessionStorage 'st.installNudge.shown'. 저장소가 던져도 던지지 않는다.
 *
 * 새 페이지 열기(새 세션)는 vi.resetModules() 뒤 다시 import 로 흉내 낸다.
 */
import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const h = vi.hoisted(() => ({ env: 'other' as string }))

vi.mock('@/features/keep/installPrompt', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/features/keep/installPrompt')>()),
  currentKeepEnv: () => h.env,
}))

const DAY = 24 * 60 * 60 * 1000
const NOW = Date.UTC(2026, 10, 17, 12)
const LOCAL = 'st.installNudge'
const SESSION = 'st.installNudge.shown'

async function load() {
  vi.resetModules()
  return import('./installNudge')
}
const saved = () => JSON.parse(localStorage.getItem(LOCAL) ?? 'null') as { dismissedAt: number | null; courseNudged: boolean } | null

beforeEach(() => {
  vi.useFakeTimers()
  vi.setSystemTime(NOW)
  localStorage.clear()
  sessionStorage.clear()
  h.env = 'other'
})

afterEach(() => {
  vi.restoreAllMocks()
  vi.useRealTimers()
})

describe('request', () => {
  it('return_visit — 보이고 세션 표식을 남긴다', async () => {
    const { installNudge } = await load()
    installNudge.request('return_visit')
    expect(installNudge.current()).toBe('return_visit')
    expect(sessionStorage.getItem(SESSION)).not.toBeNull()
  })

  it('course_created — 보이고 courseNudged=true 로 저장한다', async () => {
    const { installNudge } = await load()
    installNudge.request('course_created')
    expect(installNudge.current()).toBe('course_created')
    expect(saved()?.courseNudged).toBe(true)
  })

  it('installed 면 보이지 않고 아무것도 적지 않는다', async () => {
    h.env = 'installed'
    const { installNudge } = await load()
    installNudge.request('return_visit')
    installNudge.request('course_created')
    expect(installNudge.current()).toBeNull()
    expect(sessionStorage.getItem(SESSION)).toBeNull()
  })

  it.each(['in_app_android', 'in_app_ios', 'samsung_android', 'installable', 'android_menu', 'ios_safari'])('env=%s 이면 보인다', async (env) => {
    h.env = env
    const { installNudge } = await load()
    installNudge.request('return_visit')
    expect(installNudge.current()).toBe('return_visit')
  })

  it('이번 세션에 이미 띄웠으면(닫은 뒤라도) 다시 띄우지 않는다', async () => {
    const { installNudge } = await load()
    installNudge.request('return_visit')
    installNudge.dismiss()
    vi.setSystemTime(NOW + 8 * DAY)
    installNudge.request('course_created')
    expect(installNudge.current()).toBeNull()
  })

  it('세션 표식은 새로고침(모듈 다시 읽기)에도 남는다', async () => {
    const first = await load()
    first.installNudge.request('return_visit')
    const again = await load()
    again.installNudge.request('return_visit')
    expect(again.installNudge.current()).toBeNull()
  })
})

describe('dismiss · accept', () => {
  it('dismiss — 숨기고 dismissedAt 에 지금 시각', async () => {
    const { installNudge } = await load()
    installNudge.request('return_visit')
    installNudge.dismiss()
    expect(installNudge.current()).toBeNull()
    expect(saved()?.dismissedAt).toBe(NOW)
  })

  it('accept(「추가」) — 숨기고 dismissedAt 에 지금 시각', async () => {
    const { installNudge } = await load()
    installNudge.request('course_created')
    installNudge.accept()
    expect(installNudge.current()).toBeNull()
    expect(saved()).toEqual({ dismissedAt: NOW, courseNudged: true })
  })
})

describe('다음 세션', () => {
  it('닫은 지 하루 — 새 세션이어도 띄우지 않는다', async () => {
    localStorage.setItem(LOCAL, JSON.stringify({ dismissedAt: NOW - DAY, courseNudged: false }))
    const { installNudge } = await load()
    installNudge.request('return_visit')
    expect(installNudge.current()).toBeNull()
  })

  it('닫은 지 8일 — 다시 띄운다', async () => {
    localStorage.setItem(LOCAL, JSON.stringify({ dismissedAt: NOW - 8 * DAY, courseNudged: false }))
    const { installNudge } = await load()
    installNudge.request('return_visit')
    expect(installNudge.current()).toBe('return_visit')
  })

  it('course_created 는 이 브라우저에서 한 번뿐, return_visit 은 그대로', async () => {
    const first = await load()
    first.installNudge.request('course_created')
    first.installNudge.dismiss()
    sessionStorage.clear()
    vi.setSystemTime(NOW + 8 * DAY)
    const next = await load()
    next.installNudge.request('course_created')
    expect(next.installNudge.current()).toBeNull()
    next.installNudge.request('return_visit')
    expect(next.installNudge.current()).toBe('return_visit')
  })
})

describe('useInstallNudge', () => {
  it('request·dismiss 에 따라 다시 그린다', async () => {
    const { installNudge, useInstallNudge } = await load()
    const { result } = renderHook(() => useInstallNudge())
    expect(result.current).toBeNull()
    act(() => installNudge.request('course_created'))
    expect(result.current).toBe('course_created')
    act(() => installNudge.dismiss())
    expect(result.current).toBeNull()
  })
})

describe('저장소가 망가져도', () => {
  it('저장소 읽기·쓰기가 던져도 request·dismiss·accept 는 던지지 않는다', async () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new DOMException('denied', 'SecurityError')
    })
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('denied', 'SecurityError')
    })
    const { installNudge } = await load()
    expect(() => installNudge.request('return_visit')).not.toThrow()
    expect(() => installNudge.dismiss()).not.toThrow()
    expect(() => installNudge.request('course_created')).not.toThrow()
    expect(() => installNudge.accept()).not.toThrow()
    expect(installNudge.current()).toBeNull()
  })

  it('저장된 값이 JSON 이 아니면 처음처럼 본다', async () => {
    localStorage.setItem(LOCAL, '{not json')
    const { installNudge } = await load()
    expect(() => installNudge.request('return_visit')).not.toThrow()
    expect(installNudge.current()).toBe('return_visit')
  })
})
