/*
 * 설치 창 붙잡기 — 정본: docs/project/plans/web-home-install.md §4 (MZ2AZ-589).
 *
 *   startInstallPromptCapture() 뒤 window 가 `appinstalled` 를 쏘면 track(webEvent.appInstalled()) 한 번(브라우저 메뉴로 설치해도 온다).
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/analytics', async (importOriginal) => ({ ...(await importOriginal<typeof import('@/analytics')>()), track: vi.fn() }))

beforeEach(() => {
  vi.resetModules()
})

const installedEvents = async () => {
  const { track } = await import('@/analytics')
  return vi
    .mocked(track)
    .mock.calls.map(([e]) => e as { name: string; params: Record<string, unknown> })
    .filter((e) => e.name === 'app_installed')
}

describe('appinstalled → app_installed', () => {
  it('설치가 끝나면 app_installed(매개변수 없음) 한 번', async () => {
    const { track } = await import('@/analytics')
    vi.mocked(track).mockClear()
    const { startInstallPromptCapture } = await import('./installPrompt')
    startInstallPromptCapture()
    window.dispatchEvent(new Event('appinstalled'))
    expect(await installedEvents()).toEqual([{ name: 'app_installed', params: {} }])
  })

})
