/*
 * 홈 화면 추가 단추 — iOS 웹뷰 껍데기 안에서는 없다(MZ2AZ-591, docs/project/plans/ios-webview-shell.md).
 * AddToHome.test.tsx 와 달리 currentKeepEnv 를 가짜로 바꾸지 않는다 — 진짜 navigator.userAgent → keepEnv 로 installed 가 되는지 본다.
 */
import { cleanup, render } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/analytics', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/analytics')>()),
  track: vi.fn(),
}))

import { OverlayRootContext } from '@/design/overlay'
import { chooseLanguage } from '@/i18n'
import { AddToHomeButton } from './AddToHome'
import { currentKeepEnv } from './installPrompt'

const SHELL_IPHONE =
  'Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148 SceneTripShell/1'
const SAFARI_IPHONE =
  'Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.1 Mobile/15E148 Safari/604.1'

let overlayRoot: HTMLDivElement

function setUserAgent(ua: string) {
  vi.spyOn(navigator, 'userAgent', 'get').mockReturnValue(ua)
}

beforeEach(() => {
  chooseLanguage('ko')
  overlayRoot = document.createElement('div')
  document.body.appendChild(overlayRoot)
})

afterEach(() => {
  cleanup()
  overlayRoot.remove()
  vi.restoreAllMocks()
})

describe('AddToHomeButton — iOS 웹뷰 껍데기', () => {
  it('껍데기 UA 면 currentKeepEnv 는 installed', () => {
    setUserAgent(SHELL_IPHONE)
    expect(currentKeepEnv()).toBe('installed')
  })

  it('껍데기 UA 면 단추를 그리지 않는다', () => {
    setUserAgent(SHELL_IPHONE)
    const { container } = render(
      <OverlayRootContext.Provider value={overlayRoot}>
        <AddToHomeButton />
      </OverlayRootContext.Provider>,
    )
    expect(container.innerHTML).toBe('')
  })

  it('보통 아이폰 사파리면 단추가 있다(대조)', () => {
    setUserAgent(SAFARI_IPHONE)
    const { container } = render(
      <OverlayRootContext.Provider value={overlayRoot}>
        <AddToHomeButton />
      </OverlayRootContext.Provider>,
    )
    expect(container.querySelector('button')).not.toBeNull()
  })
})
