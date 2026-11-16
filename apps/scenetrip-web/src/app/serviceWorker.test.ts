/*
 * 서비스 워커 등록 — 정본: docs/project/plans/web-home-install.md §3 (MZ2AZ-589).
 *
 *   registerServiceWorker({ enabled, nav? })
 *   enabled 이고 nav 에 serviceWorker 가 있으면 nav.serviceWorker.register('/sw.js', { scope: '/' }). 그 밖엔 아무것도 하지 않는다.
 *   register 가 거부되거나 바로 던져도 던지지 않는다.
 */
import { describe, expect, it, vi } from 'vitest'
import { registerServiceWorker } from './serviceWorker'

function fakeNav(register: (...a: unknown[]) => unknown): Navigator {
  return { serviceWorker: { register } } as unknown as Navigator
}

describe('registerServiceWorker', () => {
  it("enabled 이면 '/sw.js' 를 scope '/' 로 등록한다", () => {
    const register = vi.fn().mockResolvedValue({})
    registerServiceWorker({ enabled: true, nav: fakeNav(register) })
    expect(register).toHaveBeenCalledTimes(1)
    expect(register).toHaveBeenCalledWith('/sw.js', { scope: '/' })
  })

  it('enabled 가 아니면 등록하지 않는다', () => {
    const register = vi.fn().mockResolvedValue({})
    registerServiceWorker({ enabled: false, nav: fakeNav(register) })
    expect(register).not.toHaveBeenCalled()
  })

  it('serviceWorker 가 없는 브라우저면 아무 일 없이 지나간다', () => {
    expect(() => registerServiceWorker({ enabled: true, nav: {} as Navigator })).not.toThrow()
  })

  it('register 가 거부돼도 던지지 않고, 처리되지 않은 거부도 남기지 않는다', async () => {
    const unhandled = vi.fn()
    process.on('unhandledRejection', unhandled)
    try {
      const register = vi.fn().mockRejectedValue(new Error('SecurityError'))
      expect(() => registerServiceWorker({ enabled: true, nav: fakeNav(register) })).not.toThrow()
      expect(register).toHaveBeenCalledTimes(1)
      await new Promise((r) => setTimeout(r, 0))
      expect(unhandled).not.toHaveBeenCalled()
    } finally {
      process.off('unhandledRejection', unhandled)
    }
  })

  it('register 가 바로 던져도 던지지 않는다', () => {
    const register = vi.fn(() => {
      throw new Error('boom')
    })
    expect(() => registerServiceWorker({ enabled: true, nav: fakeNav(register) })).not.toThrow()
    expect(register).toHaveBeenCalledTimes(1)
  })
})
