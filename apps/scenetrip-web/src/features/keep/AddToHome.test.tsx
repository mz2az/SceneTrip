/*
 * 홈 화면 추가 단추와 권유 띠 — 정본: docs/project/plans/web-home-install.md §1·§2·§4 (MZ2AZ-589), 환경은 web-keep-flow.md §2-1.
 *
 *   AddToHomeButton()  머리줄 동그라미 단추, 이름 「홈 화면에 추가」. installed 면 null.
 *   AddToHomeNudge()   아래 띠 「다음엔 홈 화면에서 바로 열어요」 + 「추가」 + ✕(「닫기」). useInstallNudge() 가 null 이면 null.
 *   누르면(환경은 누를 때의 currentKeepEnv())
 *     installable                         → promptInstall() → install_accepted / install_dismissed, unavailable 이면 android_menu 안내 + guide
 *     samsung_android                     → 크롬 intent:// 로 handoff.open, open_browser (설치 창은 부르지 않는다)
 *     in_app_android · in_app_ios         → handoffHref 로 handoff.open, open_browser
 *       (이 셋은 1.5초 뒤에도 화면이 보이면 그 env 의 안내 + guide)
 *     android_menu · ios_safari · other   → 안내 + guide
 *   안내 시트 「홈 화면에 SceneTrip 을 추가하세요」 + 두 줄 + 「알겠어요」(got_it, 닫힘).
 *   이벤트 add_to_home(env, action, where, trigger?) — 단추는 where=header(trigger 없음), 띠는 where=nudge + trigger.
 *
 * 이음매(가짜로 바꾼다): currentKeepEnv·promptInstall(@/features/keep/installPrompt), handoff.open(@/features/keep/handoff),
 *   installNudge·useInstallNudge(@/features/keep/installNudge), track·handoffParams(@/analytics).
 */
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import type { ReactNode } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const h = vi.hoisted(() => ({
  env: 'other' as string,
  promptInstall: vi.fn(),
  handoffOpen: vi.fn(),
  nudge: null as null | 'course_created' | 'return_visit',
  nudgeListeners: new Set<() => void>(),
  setNudge(next: null | 'course_created' | 'return_visit') {
    this.nudge = next
    this.nudgeListeners.forEach((l) => l())
  },
  dismiss: vi.fn(),
  accept: vi.fn(),
  request: vi.fn(),
  // 설치 창이 붙잡혀 있나(installPrompt 의 canPromptInstall / useCanPromptInstall). 기본은 없다.
  canPrompt: false,
  canPromptListeners: new Set<() => void>(),
  setCanPrompt(next: boolean) {
    this.canPrompt = next
    this.canPromptListeners.forEach((l) => l())
  },
}))

vi.mock('@/analytics', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/analytics')>()),
  track: vi.fn(),
  handoffParams: () => ({ utm_source: 'meta', aid: 'anon-1' }),
}))
vi.mock('@/features/keep/installPrompt', async (importOriginal) => {
  const { useSyncExternalStore } = await import('react')
  const subscribe = (l: () => void) => {
    h.canPromptListeners.add(l)
    return () => h.canPromptListeners.delete(l)
  }
  return {
    ...(await importOriginal<typeof import('@/features/keep/installPrompt')>()),
    currentKeepEnv: () => h.env,
    promptInstall: () => h.promptInstall(),
    canPromptInstall: () => h.canPrompt,
    useCanPromptInstall: () => useSyncExternalStore(subscribe, () => h.canPrompt, () => false),
  }
})
vi.mock('@/features/keep/handoff', () => ({ handoff: { open: (href: string) => h.handoffOpen(href) } }))
vi.mock('@/features/keep/installNudge', async () => {
  const { useSyncExternalStore } = await import('react')
  const subscribe = (l: () => void) => {
    h.nudgeListeners.add(l)
    return () => h.nudgeListeners.delete(l)
  }
  return {
    installNudge: {
      request: (t: unknown) => h.request(t),
      dismiss: () => {
        h.dismiss()
        h.setNudge(null)
      },
      accept: () => {
        h.accept()
        h.setNudge(null)
      },
      current: () => h.nudge,
    },
    useInstallNudge: () => useSyncExternalStore(subscribe, () => h.nudge),
  }
})

import { track } from '@/analytics'
import { OverlayRootContext } from '@/design/overlay'
import { chooseLanguage } from '@/i18n'
import { cartStore } from '@/state/cart'
import { AddToHomeButton, AddToHomeNudge } from './AddToHome'
import { type KeepEnv, handoffHref, handoffKeepUrl } from './keepRules'

const UA_SAMPLE_CART_ITEM = { placeId: 101, sourceContentId: 32, sourceContentTitle: '케이팝 데몬 헌터스' }

type Ev = { name: string; params: Record<string, unknown> }
const addToHome = () =>
  vi
    .mocked(track)
    .mock.calls.map(([e]) => e as Ev)
    .filter((e) => e.name === 'add_to_home')
    .map((e) => e.params)
const actions = () => addToHome().map((p) => p.action)

let overlayRoot: HTMLDivElement

function renderIn(node: ReactNode) {
  return render(<OverlayRootContext.Provider value={overlayRoot}>{node}</OverlayRootContext.Provider>)
}

async function advance(ms: number) {
  let left = ms
  do {
    const step = Math.min(left, 100)
    await act(async () => {
      await vi.advanceTimersByTimeAsync(step)
    })
    left -= step
  } while (left > 0)
}

function setVisibility(state: 'visible' | 'hidden') {
  Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => state })
}

const headerButton = (name = '홈 화면에 추가') => screen.getByRole('button', { name })
function buttonWith(text: string): HTMLElement {
  const button = screen.getByText(text).closest('button')
  expect(button, `「${text}」 단추`).not.toBeNull()
  return button as HTMLElement
}
const bodyText = () => document.body.textContent ?? ''

const GUIDE_TITLE = '홈 화면에 SceneTrip 을 추가하세요'
const LINES: Record<Exclude<KeepEnv, 'installable' | 'installed'>, [string, string]> = {
  android_menu: ['1. 오른쪽 위 ⋮ 를 누르세요', '2. 「홈 화면에 추가」 를 고르세요'],
  ios_safari: ['1. 아래쪽 공유 단추를 누르세요', '2. 「홈 화면에 추가」 를 고르세요'],
  other: ['1. 브라우저 메뉴를 여세요', '2. 「즐겨찾기 추가」 를 고르세요'],
  samsung_android: ['1. 아래 ≡ 를 누르세요', '2. 「현재 페이지 추가」 에서 「북마크」 를 고르세요'],
  in_app_android: ['1. 오른쪽 위 ⋮ 를 누르세요', '2. 「다른 브라우저로 열기」 를 고르세요'],
  in_app_ios: ['1. 오른쪽 위 ⋯ 를 누르세요', '2. 「외부 브라우저에서 열기」 를 고르세요'],
}

beforeEach(() => {
  vi.useFakeTimers()
  chooseLanguage('ko')
  overlayRoot = document.createElement('div')
  document.body.appendChild(overlayRoot)
  h.env = 'other'
  h.nudge = null
  h.nudgeListeners.clear()
  h.promptInstall.mockReset()
  h.handoffOpen.mockReset()
  h.dismiss.mockReset()
  h.accept.mockReset()
  h.request.mockReset()
  h.canPrompt = false
  h.canPromptListeners.clear()
  setVisibility('visible')
  cartStore.set({ loadState: 'loaded', placeIds: new Set([101]), items: [UA_SAMPLE_CART_ITEM] as never, pois: [], poiIds: new Set(), toast: null })
  vi.mocked(track).mockClear()
})

afterEach(() => {
  cleanup()
  overlayRoot.remove()
  vi.useRealTimers()
  chooseLanguage('ko')
})

// ---------------------------------------------------------------- 머리줄 단추

describe('AddToHomeButton — 보이는 것', () => {
  it.each(['in_app_android', 'in_app_ios', 'samsung_android', 'installable', 'android_menu', 'ios_safari', 'other'])('env=%s 이면 「홈 화면에 추가」 단추', (env) => {
    h.env = env
    renderIn(<AddToHomeButton />)
    expect(headerButton()).toBeTruthy()
  })

  it('installed(홈 화면 앱) 면 그리지 않는다', () => {
    // 다른 env 에서는 보이는 것을 먼저 확인한다(늘 null 인 구현이 이 시험을 통과하지 않게).
    const first = renderIn(<AddToHomeButton />)
    expect(headerButton()).toBeTruthy()
    first.unmount()
    h.env = 'installed'
    const { container } = renderIn(<AddToHomeButton />)
    expect(screen.queryByRole('button', { name: '홈 화면에 추가' })).toBeNull()
    expect(container.innerHTML).toBe('')
  })

  it("영어 — 이름 'Add to Home Screen'", () => {
    chooseLanguage('en')
    renderIn(<AddToHomeButton />)
    expect(headerButton('Add to Home Screen')).toBeTruthy()
  })
})

describe('AddToHomeButton — installable', () => {
  it('설치 창을 띄우고 수락하면 tap → install_accepted', async () => {
    h.env = 'installable'
    h.promptInstall.mockResolvedValue('accepted')
    renderIn(<AddToHomeButton />)
    fireEvent.click(headerButton())
    await advance(0)
    expect(h.promptInstall).toHaveBeenCalledTimes(1)
    expect(addToHome()).toEqual([
      { env: 'installable', action: 'tap', where: 'header' },
      { env: 'installable', action: 'install_accepted', where: 'header' },
    ])
    expect(bodyText()).not.toContain(GUIDE_TITLE)
  })

  it('설치 창을 닫으면 install_dismissed', async () => {
    h.env = 'installable'
    h.promptInstall.mockResolvedValue('dismissed')
    renderIn(<AddToHomeButton />)
    fireEvent.click(headerButton())
    await advance(0)
    expect(actions()).toEqual(['tap', 'install_dismissed'])
  })

  it('설치 창이 사라졌으면(unavailable) 안드로이드 메뉴 안내 + guide', async () => {
    h.env = 'installable'
    h.promptInstall.mockResolvedValue('unavailable')
    renderIn(<AddToHomeButton />)
    fireEvent.click(headerButton())
    await advance(0)
    expect(bodyText()).toContain(GUIDE_TITLE)
    for (const line of LINES.android_menu) expect(bodyText()).toContain(line)
    expect(addToHome()).toEqual([
      { env: 'installable', action: 'tap', where: 'header' },
      { env: 'installable', action: 'guide', where: 'header' },
    ])
  })
})

describe('AddToHomeButton — 밖으로 넘기기', () => {
  it('samsung_android — 크롬을 지정한 intent:// 로 지금 장바구니·꼬리표를 싣고 넘긴다, 설치 창은 부르지 않는다', async () => {
    h.env = 'samsung_android'
    h.promptInstall.mockResolvedValue('accepted')
    renderIn(<AddToHomeButton />)
    fireEvent.click(headerButton())
    await advance(0)
    const expected = handoffHref('samsung_android', handoffKeepUrl(window.location.origin, [], cartStore.get(), { utm_source: 'meta', aid: 'anon-1' }))
    expect(h.handoffOpen).toHaveBeenCalledTimes(1)
    const href = h.handoffOpen.mock.calls[0]![0] as string
    expect(href).toBe(expected)
    expect(href.startsWith('intent://')).toBe(true)
    expect(href).toContain('package=com.android.chrome')
    expect(href).toContain('keep=32')
    expect(href).toContain('aid=anon-1')
    expect(h.promptInstall).not.toHaveBeenCalled()
    expect(addToHome()).toEqual([
      { env: 'samsung_android', action: 'tap', where: 'header' },
      { env: 'samsung_android', action: 'open_browser', where: 'header' },
    ])
  })

  it.each(['in_app_android', 'in_app_ios'] as const)('%s — handoffHref 로 넘긴다', async (env) => {
    h.env = env
    renderIn(<AddToHomeButton />)
    fireEvent.click(headerButton())
    await advance(0)
    const expected = handoffHref(env, handoffKeepUrl(window.location.origin, [], cartStore.get(), { utm_source: 'meta', aid: 'anon-1' }))
    expect(h.handoffOpen).toHaveBeenCalledTimes(1)
    expect(h.handoffOpen.mock.calls[0]![0]).toBe(expected)
    expect(actions()).toEqual(['tap', 'open_browser'])
  })

  it.each(['samsung_android', 'in_app_android', 'in_app_ios'] as const)('%s — 1.5초 뒤에도 보이면 그 env 의 안내 + guide', async (env) => {
    h.env = env
    renderIn(<AddToHomeButton />)
    fireEvent.click(headerButton())
    await advance(1400)
    expect(bodyText()).not.toContain(GUIDE_TITLE)
    expect(actions()).not.toContain('guide')
    await advance(100)
    expect(bodyText()).toContain(GUIDE_TITLE)
    for (const line of LINES[env]) expect(bodyText()).toContain(line)
    expect(addToHome()).toContainEqual({ env, action: 'guide', where: 'header' })
  })

  it.each(['samsung_android', 'in_app_android', 'in_app_ios'] as const)('%s — 넘어가서 화면이 가려졌으면 안내도 guide 도 없다', async (env) => {
    h.env = env
    renderIn(<AddToHomeButton />)
    fireEvent.click(headerButton())
    // 단추를 눌렀다는 증거가 먼저 있어야 한다(비어 있는 구현이 이 시험을 통과하지 않게).
    expect(h.handoffOpen).toHaveBeenCalledTimes(1)
    setVisibility('hidden')
    await advance(1500)
    await advance(1000)
    expect(bodyText()).not.toContain(GUIDE_TITLE)
    expect(actions()).not.toContain('guide')
  })

  it('누를 때 env 로 고정한다 — 넘긴 뒤 env 가 바뀌어도 안내는 누를 때의 것', async () => {
    h.env = 'in_app_ios'
    renderIn(<AddToHomeButton />)
    fireEvent.click(headerButton())
    h.env = 'other'
    await advance(1500)
    for (const line of LINES.in_app_ios) expect(bodyText()).toContain(line)
    expect(addToHome()).toContainEqual({ env: 'in_app_ios', action: 'guide', where: 'header' })
  })
})

describe('AddToHomeButton — 안내 시트', () => {
  it.each(['android_menu', 'ios_safari', 'other'] as const)('%s — 곧바로 안내 두 줄 + guide, 넘기지도 설치 창도 없다', async (env) => {
    h.env = env
    renderIn(<AddToHomeButton />)
    fireEvent.click(headerButton())
    await advance(0)
    expect(bodyText()).toContain(GUIDE_TITLE)
    for (const line of LINES[env]) expect(bodyText()).toContain(line)
    expect(h.handoffOpen).not.toHaveBeenCalled()
    expect(h.promptInstall).not.toHaveBeenCalled()
    expect(addToHome()).toEqual([
      { env, action: 'tap', where: 'header' },
      { env, action: 'guide', where: 'header' },
    ])
  })

  it('「알겠어요」 → got_it, 시트가 닫힌다', async () => {
    h.env = 'ios_safari'
    renderIn(<AddToHomeButton />)
    fireEvent.click(headerButton())
    await advance(0)
    fireEvent.click(buttonWith('알겠어요'))
    await advance(1000)
    expect(addToHome()).toContainEqual({ env: 'ios_safari', action: 'got_it', where: 'header' })
    expect(bodyText()).not.toContain(GUIDE_TITLE)
  })

  it("영어 — 'Add SceneTrip to your Home Screen' + 'Got it'", async () => {
    chooseLanguage('en')
    h.env = 'other'
    renderIn(<AddToHomeButton />)
    fireEvent.click(headerButton('Add to Home Screen'))
    await advance(0)
    expect(bodyText()).toContain('Add SceneTrip to your Home Screen')
    expect(buttonWith('Got it')).toBeTruthy()
  })
})

// ---------------------------------------------------------------- 권유 띠

describe('AddToHomeNudge — 보이는 것', () => {
  it('useInstallNudge() 가 null 이면 그리지 않는다 — 보였다가 null 이 되면 사라진다', async () => {
    const { container } = renderIn(<AddToHomeNudge />)
    expect(container.innerHTML).toBe('')
    act(() => h.setNudge('return_visit'))
    await advance(0)
    expect(bodyText()).toContain('다음엔 홈 화면에서 바로 열어요')
    act(() => h.setNudge(null))
    await advance(1000)
    expect(bodyText()).not.toContain('다음엔 홈 화면에서 바로 열어요')
  })

  it('보이면 문구 + 「추가」 + 「닫기」, view 한 번(where=nudge, trigger)', async () => {
    h.env = 'installable'
    h.nudge = 'course_created'
    const { rerender } = renderIn(<AddToHomeNudge />)
    await advance(0)
    expect(bodyText()).toContain('다음엔 홈 화면에서 바로 열어요')
    expect(buttonWith('추가')).toBeTruthy()
    expect(screen.getByRole('button', { name: '닫기' })).toBeTruthy()
    rerender(
      <OverlayRootContext.Provider value={overlayRoot}>
        <AddToHomeNudge />
      </OverlayRootContext.Provider>,
    )
    await advance(0)
    expect(addToHome()).toEqual([{ env: 'installable', action: 'view', where: 'nudge', trigger: 'course_created' }])
  })

  it('나중에 보이게 되면 그때 view', async () => {
    renderIn(<AddToHomeNudge />)
    await advance(0)
    expect(addToHome()).toEqual([])
    act(() => h.setNudge('return_visit'))
    await advance(0)
    expect(bodyText()).toContain('다음엔 홈 화면에서 바로 열어요')
    expect(addToHome()).toEqual([{ env: 'other', action: 'view', where: 'nudge', trigger: 'return_visit' }])
  })

  it("영어 — 'Open SceneTrip from your Home Screen next time' + 'Add' + 'Close'", async () => {
    chooseLanguage('en')
    h.nudge = 'return_visit'
    renderIn(<AddToHomeNudge />)
    await advance(0)
    expect(bodyText()).toContain('Open SceneTrip from your Home Screen next time')
    expect(buttonWith('Add')).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Close' })).toBeTruthy()
  })
})

describe('AddToHomeNudge — 누르기', () => {
  it('✕ → dismiss 이벤트 + installNudge.dismiss(), 띠가 사라진다', async () => {
    h.env = 'ios_safari'
    h.nudge = 'return_visit'
    renderIn(<AddToHomeNudge />)
    await advance(0)
    fireEvent.click(screen.getByRole('button', { name: '닫기' }))
    await advance(0)
    expect(h.dismiss).toHaveBeenCalledTimes(1)
    expect(h.accept).not.toHaveBeenCalled()
    expect(addToHome()).toContainEqual({ env: 'ios_safari', action: 'dismiss', where: 'nudge', trigger: 'return_visit' })
    expect(bodyText()).not.toContain('다음엔 홈 화면에서 바로 열어요')
    expect(bodyText()).not.toContain(GUIDE_TITLE)
  })

  it('「추가」(installable) → accept() 후 설치 창, tap · install_accepted 에 where=nudge + trigger', async () => {
    h.env = 'installable'
    h.nudge = 'course_created'
    h.promptInstall.mockResolvedValue('accepted')
    renderIn(<AddToHomeNudge />)
    await advance(0)
    fireEvent.click(buttonWith('추가'))
    await advance(0)
    expect(h.accept).toHaveBeenCalledTimes(1)
    expect(h.dismiss).not.toHaveBeenCalled()
    expect(h.promptInstall).toHaveBeenCalledTimes(1)
    expect(addToHome()).toEqual([
      { env: 'installable', action: 'view', where: 'nudge', trigger: 'course_created' },
      { env: 'installable', action: 'tap', where: 'nudge', trigger: 'course_created' },
      { env: 'installable', action: 'install_accepted', where: 'nudge', trigger: 'course_created' },
    ])
  })

  it('「추가」(ios_safari) → 띠는 사라져도 안내 시트는 뜬다, guide · got_it 에 trigger', async () => {
    h.env = 'ios_safari'
    h.nudge = 'return_visit'
    renderIn(<AddToHomeNudge />)
    await advance(0)
    fireEvent.click(buttonWith('추가'))
    await advance(0)
    expect(h.accept).toHaveBeenCalledTimes(1)
    expect(bodyText()).not.toContain('다음엔 홈 화면에서 바로 열어요')
    expect(bodyText()).toContain(GUIDE_TITLE)
    for (const line of LINES.ios_safari) expect(bodyText()).toContain(line)
    fireEvent.click(buttonWith('알겠어요'))
    await advance(1000)
    expect(actions()).toEqual(['view', 'tap', 'guide', 'got_it'])
    for (const p of addToHome()) expect(p).toMatchObject({ env: 'ios_safari', where: 'nudge', trigger: 'return_visit' })
  })

  it('「추가」(samsung_android) → 크롬 intent:// 로 넘기고 open_browser(nudge), 설치 창은 없다', async () => {
    h.env = 'samsung_android'
    h.nudge = 'course_created'
    renderIn(<AddToHomeNudge />)
    await advance(0)
    fireEvent.click(buttonWith('추가'))
    await advance(0)
    expect(h.promptInstall).not.toHaveBeenCalled()
    const href = h.handoffOpen.mock.calls[0]?.[0] as string | undefined
    expect(href).toContain('package=com.android.chrome')
    expect(addToHome()).toContainEqual({ env: 'samsung_android', action: 'open_browser', where: 'nudge', trigger: 'course_created' })
  })
})

// ---------------------------------------------------------------- 안내 시트가 떠 있는 동안 설치 창이 오면(MZ2AZ-590)
//
//   크롬은 30초쯤 머문 뒤에야 beforeinstallprompt 를 준다. 그 전에 누르면 안내 시트가 뜬다.
//   시트가 떠 있는 동안 useCanPromptInstall() 이 true 가 되면 주 단추가 「알겠어요」 → 「지금 설치」.
//   「지금 설치」 → promptInstall() → install_accepted / install_dismissed('unavailable' 이면 설치 이벤트 없음), 시트가 닫힌다, got_it 없음.
//   이벤트의 env 는 시트를 열 때의 env.

/** 설치 창을 띄우면 붙잡은 것을 버린다(진짜 promptInstall 과 같게). */
function promptResolves(outcome: 'accepted' | 'dismissed' | 'unavailable') {
  h.promptInstall.mockImplementation(async () => {
    h.setCanPrompt(false)
    return outcome
  })
}

async function openGuideFromHeader(env: string) {
  h.env = env
  renderIn(<AddToHomeButton />)
  fireEvent.click(headerButton())
  await advance(0)
  expect(bodyText()).toContain(GUIDE_TITLE)
}

/** 설치 창이 붙잡힌다. 진짜 브라우저에서는 이때 currentKeepEnv() 도 installable 로 바뀐다. */
async function promptArrives() {
  act(() => {
    h.env = 'installable'
    h.setCanPrompt(true)
  })
  await advance(0)
}

describe('안내 시트 + 늦게 온 설치 창 — 머리줄 단추(MZ2AZ-590)', () => {
  it('설치 창이 없으면 그대로 「알겠어요」, 「지금 설치」 는 없다', async () => {
    await openGuideFromHeader('android_menu')
    expect(buttonWith('알겠어요')).toBeTruthy()
    expect(screen.queryByText('지금 설치')).toBeNull()
  })

  it('시트가 떠 있는 동안 설치 창이 오면 주 단추가 「알겠어요」 → 「지금 설치」', async () => {
    await openGuideFromHeader('android_menu')
    expect(buttonWith('알겠어요')).toBeTruthy()
    await promptArrives()
    expect(buttonWith('지금 설치')).toBeTruthy()
    expect(screen.queryByText('알겠어요')).toBeNull()
    expect(bodyText()).toContain(GUIDE_TITLE)
  })

  it('「지금 설치」 → 설치 창, 수락하면 install_accepted(env 는 시트를 열 때의 android_menu), 시트가 닫히고 got_it 은 없다', async () => {
    promptResolves('accepted')
    await openGuideFromHeader('android_menu')
    await promptArrives()
    fireEvent.click(buttonWith('지금 설치'))
    await advance(1000)
    expect(h.promptInstall).toHaveBeenCalledTimes(1)
    expect(addToHome()).toEqual([
      { env: 'android_menu', action: 'tap', where: 'header' },
      { env: 'android_menu', action: 'guide', where: 'header' },
      { env: 'android_menu', action: 'install_accepted', where: 'header' },
    ])
    expect(bodyText()).not.toContain(GUIDE_TITLE)
  })

  it('「지금 설치」 → 설치 창을 닫으면 install_dismissed, 시트가 닫힌다', async () => {
    promptResolves('dismissed')
    await openGuideFromHeader('android_menu')
    await promptArrives()
    fireEvent.click(buttonWith('지금 설치'))
    await advance(1000)
    expect(h.promptInstall).toHaveBeenCalledTimes(1)
    expect(actions()).toEqual(['tap', 'guide', 'install_dismissed'])
    expect(addToHome()[2]).toEqual({ env: 'android_menu', action: 'install_dismissed', where: 'header' })
    expect(bodyText()).not.toContain(GUIDE_TITLE)
  })

  it("「지금 설치」 → 'unavailable' 이면 설치 이벤트도 got_it 도 없이 시트가 닫힌다", async () => {
    promptResolves('unavailable')
    await openGuideFromHeader('android_menu')
    await promptArrives()
    fireEvent.click(buttonWith('지금 설치'))
    await advance(1000)
    expect(h.promptInstall).toHaveBeenCalledTimes(1)
    expect(actions()).toEqual(['tap', 'guide'])
    expect(bodyText()).not.toContain(GUIDE_TITLE)
  })

  it('installable 인데 설치 창이 사라져 안내가 떴다가(unavailable) 다시 오면 「지금 설치」 → install_accepted', async () => {
    h.env = 'installable'
    h.promptInstall.mockResolvedValueOnce('unavailable')
    renderIn(<AddToHomeButton />)
    fireEvent.click(headerButton())
    await advance(0)
    expect(bodyText()).toContain(GUIDE_TITLE)
    promptResolves('accepted')
    await promptArrives()
    fireEvent.click(buttonWith('지금 설치'))
    await advance(1000)
    expect(h.promptInstall).toHaveBeenCalledTimes(2)
    expect(actions()).toEqual(['tap', 'guide', 'install_accepted'])
    expect(addToHome()[2]).toEqual({ env: 'installable', action: 'install_accepted', where: 'header' })
    expect(bodyText()).not.toContain(GUIDE_TITLE)
  })

  it("영어 — 'Got it' → 'Install now'", async () => {
    chooseLanguage('en')
    h.env = 'android_menu'
    renderIn(<AddToHomeButton />)
    fireEvent.click(headerButton('Add to Home Screen'))
    await advance(0)
    expect(buttonWith('Got it')).toBeTruthy()
    await promptArrives()
    expect(buttonWith('Install now')).toBeTruthy()
    expect(screen.queryByText('Got it')).toBeNull()
  })
})

describe('안내 시트 + 늦게 온 설치 창 — 권유 띠(MZ2AZ-590)', () => {
  async function openGuideFromNudge(env: string, trigger: 'course_created' | 'return_visit') {
    h.env = env
    h.nudge = trigger
    renderIn(<AddToHomeNudge />)
    await advance(0)
    fireEvent.click(buttonWith('추가'))
    await advance(0)
    expect(bodyText()).toContain(GUIDE_TITLE)
  }

  it('띠가 내려간 뒤 떠 있는 시트에서도 설치 창이 오면 「지금 설치」', async () => {
    await openGuideFromNudge('android_menu', 'return_visit')
    expect(buttonWith('알겠어요')).toBeTruthy()
    await promptArrives()
    expect(buttonWith('지금 설치')).toBeTruthy()
    expect(screen.queryByText('알겠어요')).toBeNull()
  })

  it('「지금 설치」 → 수락하면 install_accepted 에 where=nudge + trigger + 시트를 열 때의 env, got_it 없이 닫힌다', async () => {
    promptResolves('accepted')
    await openGuideFromNudge('android_menu', 'course_created')
    await promptArrives()
    fireEvent.click(buttonWith('지금 설치'))
    await advance(1000)
    expect(h.promptInstall).toHaveBeenCalledTimes(1)
    expect(addToHome()).toEqual([
      { env: 'android_menu', action: 'view', where: 'nudge', trigger: 'course_created' },
      { env: 'android_menu', action: 'tap', where: 'nudge', trigger: 'course_created' },
      { env: 'android_menu', action: 'guide', where: 'nudge', trigger: 'course_created' },
      { env: 'android_menu', action: 'install_accepted', where: 'nudge', trigger: 'course_created' },
    ])
    expect(bodyText()).not.toContain(GUIDE_TITLE)
  })

  it('「지금 설치」 → 닫으면 install_dismissed(nudge + trigger), 시트가 닫힌다', async () => {
    promptResolves('dismissed')
    await openGuideFromNudge('android_menu', 'return_visit')
    await promptArrives()
    fireEvent.click(buttonWith('지금 설치'))
    await advance(1000)
    expect(actions()).toEqual(['view', 'tap', 'guide', 'install_dismissed'])
    expect(addToHome()[3]).toEqual({ env: 'android_menu', action: 'install_dismissed', where: 'nudge', trigger: 'return_visit' })
    expect(bodyText()).not.toContain(GUIDE_TITLE)
  })
})
