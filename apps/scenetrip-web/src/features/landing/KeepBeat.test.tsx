/*
 * 첫 화면의 ⑤ 「간직하기」 — 정본: docs/project/plans/web-ad-landing.md §13-1.
 *
 *   ④ 에서 담기가 끝나면(실패 0) 덮개 안에서 ⑤ (data-beat=keep, onBeat('keep')). 실패가 있으면 ④ 에 남는다.
 *   이미 로그인했거나(hasSession) 홈 화면에서 연 앱(installed)이면 ⑤ 없이 onClose('kept').
 *   ⑤ 를 보이면 keepStore 에 표식 — 「더 담기 → 일정 짜기」 는 간직하기 단계를 건너뛰고 계획부터.
 *   말풍선: 브라우저와 상관없이 하나, 두 줄 「이 지도로 코스를 짜 봐요 / 길은 내가 안내해 줄게요!」
 *        (en 「Plan a route with this map. / I'll guide you there!」). 2026-11-16 전 문구(「이 창을 닫으면 마이맵이 사라져요」,
 *        「마이맵을 간직하세요」)는 더 이상 없다(MZ2AZ-584).
 *   주 「로그인하고 코스 짜기」(en 「Sign in and plan my route」) → 앱을 붙이고(onNeedApp) promptSignIn(null, 'keep') — RootSheets 가 openSignInFor(null, 'keep').
 *        로그인 창이 떴다가(signInSheetOpenStore true) 닫히면(false) onClose('kept'). 계정(account)은 제 상태를 그 비춤에 적는다.
 *   보조(앱 안) 「브라우저에서 열기」 → handoff.open(intent:// 또는 x-safari-…/my-map?keep=32). 1.5초 뒤에도 보이면
 *        안드로이드는 한 줄 안내, 아이폰은 해태가 오른쪽 위를 가리키는 그림 + 두 줄.
 *   보조(그 밖) installable → 설치 창, 아니면 안내 두 줄 + 「알겠어요」. 「나중에」 → onClose('kept').
 *   이벤트: keep_prompt(env, action, where=intro).
 */
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { useState } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const h = vi.hoisted(() => ({
  GET: vi.fn(),
  session: false,
  standalone: false,
}))

vi.mock('@/api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/client')>()),
  api: { GET: (...args: unknown[]) => h.GET(...args), POST: vi.fn(), DELETE: vi.fn() },
}))
vi.mock('@/analytics', async (importOriginal) => ({ ...(await importOriginal<typeof import('@/analytics')>()), track: vi.fn() }))
vi.mock('@/state/auth', async (importOriginal) => ({ ...(await importOriginal<typeof import('@/state/auth')>()), hasSession: () => h.session }))
import { track, webEvent } from '@/analytics'
import { OverlayRootContext } from '@/design/overlay'
import { handoff } from '@/features/keep/handoff'
import { introKeepSeen, keepActions, keepStore, resetIntroKeepSeenForTest } from '@/features/keep/keepStore'
import { chooseLanguage } from '@/i18n'
import { dismissSignInPrompt, signInPromptStore, signInSheetOpenStore } from '@/state/auth'
import { cartActions, cartStore } from '@/state/cart'
import type { IntroClose } from './introClose'
import { LandingCover } from './LandingCover'

const UA = {
  fbAndroid:
    'Mozilla/5.0 (Linux; Android 14; SM-S918N Build/UP1A; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/130.0 Mobile Safari/537.36 [FB_IAB/FB4A;FBAV/480.0.0.0;]',
  igIphone:
    'Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148 Instagram 350.0.0.0 (iPhone15,3; iOS 18_1; ko_KR)',
  chromeDesktop: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36',
}

const respond = (status: number, data?: unknown) => ({
  data,
  error: status >= 400 ? { code: 'INTERNAL', message: 'x' } : undefined,
  response: { ok: status >= 200 && status < 300, status, headers: new Headers() },
})
const places = [101, 102, 103].map((id, i) => ({
  id,
  name: `장소${id}`,
  latitude: 37.55 + i * 0.01,
  longitude: 126.98,
  imageUrl: `https://x/p-${id}.jpg`,
  imageThumbnailUrl: `https://x/p-${id}-s.jpg`,
  address: `서울특별시 종로구 ${id}`,
}))

let closes: IntroClose[] = []
let beats: string[] = []
let needApp = 0
let overlayRoot: HTMLDivElement

function Host() {
  const [open, setOpen] = useState(true)
  if (!open) return null
  return (
    <LandingCover
      contentId={32}
      onBeat={(b) => beats.push(b)}
      onNeedApp={() => (needApp += 1)}
      onClose={(how) => {
        closes.push(how)
        setOpen(false)
      }}
    />
  )
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

function setUA(ua: string) {
  Object.defineProperty(window.navigator, 'userAgent', { configurable: true, get: () => ua })
}

function buttonWith(text: string): HTMLElement {
  const button = screen.getByText(text).closest('button')
  expect(button, `「${text}」 단추`).not.toBeNull()
  return button as HTMLElement
}
const prompts = () =>
  vi
    .mocked(track)
    .mock.calls.map(([e]) => e as { name: string; params: Record<string, unknown> })
    .filter((e) => e.name === 'keep_prompt')
    .map((e) => e.params)
const beatAttr = () => document.querySelector('.st-film')?.getAttribute('data-beat')

/** 첫 화면을 ④ 까지 보고 담기를 누른다 — 담기가 끝난 뒤. */
async function keepAll(label = '이 3곳 마이맵에 담기') {
  render(
    <OverlayRootContext.Provider value={overlayRoot}>
      <Host />
    </OverlayRootContext.Provider>,
  )
  await advance(50)
  await advance(30_000)
  fireEvent.click(buttonWith(label))
  await advance(0)
  await advance(0)
}

let openSpy: ReturnType<typeof vi.spyOn>

beforeEach(() => {
  vi.useFakeTimers()
  chooseLanguage('ko')
  overlayRoot = document.createElement('div')
  document.body.appendChild(overlayRoot)
  closes = []
  beats = []
  needApp = 0
  h.session = false
  h.standalone = false
  dismissSignInPrompt()
  signInSheetOpenStore.set(false)
  sessionStorage.clear()
  resetIntroKeepSeenForTest()
  setUA(UA.chromeDesktop)
  cartStore.set({ loadState: 'loaded', placeIds: new Set(), items: [], pois: [], poiIds: new Set(), toast: null })
  Object.defineProperty(window, 'matchMedia', {
    configurable: true,
    writable: true,
    value: (query: string) => ({
      matches: h.standalone && query.includes('display-mode: standalone'),
      media: query,
      onchange: null,
      addListener: () => {},
      removeListener: () => {},
      addEventListener: () => {},
      removeEventListener: () => {},
      dispatchEvent: () => false,
    }),
  })
  h.GET.mockReset()
  h.GET.mockImplementation(async (path: string) => {
    if (path === '/contents/{contentId}') return respond(200, { id: 32, category: 'movie', title: '케이팝 데몬 헌터스', posterUrl: 'https://x/poster.jpg', placeCount: 3 })
    if (path === '/contents/{contentId}/places') return respond(200, { items: places, total: 3, limit: 100, offset: 0 })
    if (path === '/courses') return respond(200, { items: [] })
    return respond(200, { items: [], totalCount: 0, pois: [], poiCount: 0 })
  })
  vi.mocked(track).mockClear()
  vi.spyOn(cartActions, 'addMany').mockImplementation(async () => {
    cartStore.set((s) => ({ ...s, placeIds: new Set([101, 102, 103]) }))
    return { saved: 3, already: 0, failed: 0 }
  })
  openSpy = vi.spyOn(handoff, 'open').mockImplementation(() => {})
})

afterEach(() => {
  cleanup()
  overlayRoot.remove()
  vi.restoreAllMocks()
  vi.useRealTimers()
  chooseLanguage('ko')
})

// ---------------------------------------------------------------- 뜨는 조건

/** 2026-11-16 전 말풍선과 주 단추 — 더는 보이지 않는다(MZ2AZ-584). */
const OLD_KO = ['이 창을 닫으면 마이맵이 사라져요', '마이맵을 간직하세요', '로그인해서 간직하기']
const OLD_EN = ['Close this window and your map is gone', 'Keep your map for your trip', 'Sign in to keep it']

describe('⑤ 가 뜨는 조건', () => {
  it.each<[string, string]>([
    ['페이스북 안드로이드 인앱', UA.fbAndroid],
    ['인스타 아이폰 인앱', UA.igIphone],
    ['크롬 데스크톱', UA.chromeDesktop],
  ])('%s — 담기가 끝나면 덮개 안에서 ⑤, 말풍선은 어느 브라우저든 같은 두 줄', async (_name, ua) => {
    setUA(ua)
    await keepAll()
    expect(closes).toEqual([])
    expect(beatAttr()).toBe('keep')
    expect(beats).toContain('keep')
    const text = document.body.textContent ?? ''
    expect(text).toContain('이 지도로 코스를 짜 봐요')
    expect(text).toContain('길은 내가 안내해 줄게요!')
    for (const old of OLD_KO) expect(text).not.toContain(old)
    expect(buttonWith('로그인하고 코스 짜기')).toBeTruthy()
    expect(buttonWith('나중에')).toBeTruthy()
  })

  it.each<[string, string]>([
    ['페이스북 안드로이드 인앱', UA.fbAndroid],
    ['인스타 아이폰 인앱', UA.igIphone],
    ['크롬 데스크톱', UA.chromeDesktop],
  ])('영어 %s — 「Plan a route with this map.」 + 「I\'ll guide you there!」 + 「Sign in and plan my route」 · 「Later」', async (_name, ua) => {
    chooseLanguage('en')
    setUA(ua)
    await keepAll('Keep these 3 on My map')
    const text = document.body.textContent ?? ''
    expect(text).toContain('Plan a route with this map.')
    expect(text).toContain("I'll guide you there!")
    for (const old of OLD_EN) expect(text).not.toContain(old)
    expect(buttonWith('Sign in and plan my route')).toBeTruthy()
    expect(buttonWith('Later')).toBeTruthy()
  })

  it('영어 앱 안 브라우저 — 보조 「Open in browser」', async () => {
    chooseLanguage('en')
    setUA(UA.fbAndroid)
    await keepAll('Keep these 3 on My map')
    expect(buttonWith('Open in browser')).toBeTruthy()
  })

  it('이미 로그인했으면 ⑤ 없이 곧바로 onClose(kept)', async () => {
    h.session = true
    setUA(UA.fbAndroid)
    await keepAll()
    expect(closes).toEqual(['kept'])
    expect(beats).not.toContain('keep')
    expect(prompts()).toEqual([])
    expect(introKeepSeen()).toBe(false)
  })

  it('홈 화면에서 연 앱(installed)이면 ⑤ 없이 곧바로 onClose(kept)', async () => {
    h.standalone = true
    setUA('Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.1 Mobile/15E148 Safari/604.1')
    await keepAll()
    expect(closes).toEqual(['kept'])
    expect(beats).not.toContain('keep')
  })

  it('실패가 있으면 ⑤ 없이 ④ 에 남는다', async () => {
    vi.mocked(cartActions.addMany).mockResolvedValue({ saved: 2, already: 0, failed: 1 })
    await keepAll()
    expect(beatAttr()).toBe('rest')
    expect(screen.queryByText('로그인하고 코스 짜기')).toBeNull()
  })

  it('⑤ 를 보면 keep_prompt(env, view, where=intro) 한 번 + 이 탭에 표식', async () => {
    setUA(UA.igIphone)
    await keepAll()
    expect(prompts()).toEqual([{ env: 'in_app_ios', action: 'view', where: 'intro' }])
    expect(introKeepSeen()).toBe(true)
    expect(sessionStorage.getItem('scenetrip.keep.introSeen')).toBe('1')
  })
})

// ---------------------------------------------------------------- 단추

describe('로그인하고 코스 짜기', () => {
  it('앱을 붙이고 로그인 창(이유 keep)을 부탁한다 — 덮개는 그대로, 창이 떴다가 닫히면 onClose(kept)', async () => {
    setUA(UA.fbAndroid)
    await keepAll()
    fireEvent.click(buttonWith('로그인하고 코스 짜기'))
    await advance(0)
    expect(needApp).toBe(1)
    expect(signInPromptStore.get()).toEqual({ requested: true, reason: null, key: 'keep' })
    expect(prompts()).toContainEqual({ env: 'in_app_android', action: 'sign_in', where: 'intro' })
    expect(closes).toEqual([])
    // 로그인 창이 떴다 — 아직 그대로.
    act(() => signInSheetOpenStore.set(true))
    await advance(0)
    expect(closes).toEqual([])
    // 로그인 창이 닫혔다(성공·취소 모두).
    act(() => signInSheetOpenStore.set(false))
    await advance(0)
    expect(closes).toEqual(['kept'])
  })

  it('앱 안 브라우저가 아니어도 같은 단추', async () => {
    await keepAll()
    fireEvent.click(buttonWith('로그인하고 코스 짜기'))
    await advance(0)
    expect(signInPromptStore.get().key).toBe('keep')
    expect(prompts()).toContainEqual({ env: 'other', action: 'sign_in', where: 'intro' })
  })

  it('계정은 로그인 창이 떠 있는지를 signInSheetOpenStore 에 비춘다(RootSheets 가 부르는 openSignInFor 그대로)', async () => {
    const { accountActions } = await import('@/features/profile/auth/account')
    accountActions.openSignInFor(null, 'keep')
    expect(signInSheetOpenStore.get()).toBe(true)
    accountActions.closeSignIn()
    expect(signInSheetOpenStore.get()).toBe(false)
  })
})

describe('브라우저에서 열기(앱 안 브라우저)', () => {
  it('안드로이드 — intent:// 로 넘기고, 1.5초 뒤에도 보이면 한 줄 안내', async () => {
    setUA(UA.fbAndroid)
    await keepAll()
    fireEvent.click(buttonWith('브라우저에서 열기'))
    expect(openSpy).toHaveBeenCalledTimes(1)
    const href = openSpy.mock.calls[0]![0] as string
    expect(href.startsWith('intent://')).toBe(true)
    expect(href).toContain('/my-map?keep=32')
    expect(prompts()).toContainEqual({ env: 'in_app_android', action: 'open_browser', where: 'intro' })
    expect(document.body.textContent).not.toContain('다른 브라우저로 열기')
    await advance(1500)
    expect(document.body.textContent).toContain('다른 브라우저로 열기')
    expect(closes).toEqual([])
  })

  it('아이폰 — x-safari- 로 넘기고, 1.5초 뒤에도 보이면 해태가 오른쪽 위를 가리키는 그림 + 두 줄', async () => {
    setUA(UA.igIphone)
    await keepAll()
    fireEvent.click(buttonWith('브라우저에서 열기'))
    const href = openSpy.mock.calls[0]![0] as string
    expect(href.startsWith('x-safari-')).toBe(true)
    expect(href).toContain('/my-map?keep=32')
    expect(document.querySelector('.st-keep__point')).toBeNull()
    await advance(1500)
    expect(document.querySelector('.st-keep__point')).not.toBeNull()
    expect(document.body.textContent).toContain('1. 오른쪽 위 ⋯ 를 누르세요')
    expect(document.body.textContent).toContain('2. 「외부 브라우저에서 열기」')
  })

  it('넘어가서 화면이 가려졌으면 안내를 보이지 않는다', async () => {
    setUA(UA.igIphone)
    await keepAll()
    fireEvent.click(buttonWith('브라우저에서 열기'))
    Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => 'hidden' })
    await advance(1500)
    expect(document.querySelector('.st-keep__point')).toBeNull()
    Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => 'visible' })
  })

  // analytics-events.md 「이벤트 보강 (MZ2AZ-586)」 — 1.5초 뒤에도 그대로라 안내를 띄우면 keep_prompt(open_browser_stuck, intro) 한 번.
  it.each([
    ['안드로이드', UA.fbAndroid, 'in_app_android'],
    ['아이폰', UA.igIphone, 'in_app_ios'],
  ] as const)('%s — 1.5초 뒤에도 보이면 keep_prompt(open_browser_stuck, intro) 한 번', async (_l, ua, env) => {
    setUA(ua)
    await keepAll()
    fireEvent.click(buttonWith('브라우저에서 열기'))
    await advance(1400)
    expect(prompts().filter((p) => p.action === 'open_browser_stuck')).toEqual([])
    await advance(100)
    await advance(3000)
    expect(prompts().filter((p) => p.action === 'open_browser_stuck')).toEqual([{ env, action: 'open_browser_stuck', where: 'intro' }])
  })

  it('넘어가서 화면이 가려졌으면 open_browser_stuck 을 보내지 않는다', async () => {
    setUA(UA.igIphone)
    await keepAll()
    fireEvent.click(buttonWith('브라우저에서 열기'))
    Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => 'hidden' })
    await advance(1500)
    Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => 'visible' })
    expect(prompts().filter((p) => p.action === 'open_browser_stuck')).toEqual([])
  })
})

describe('앱 안 브라우저가 아닐 때의 보조 단추', () => {
  it('크롬 데스크톱 — 「즐겨찾기에 추가하기」 → 안내 두 줄 + 「알겠어요」 → got_it, onClose(kept)', async () => {
    await keepAll()
    expect(screen.queryByText('브라우저에서 열기')).toBeNull()
    fireEvent.click(buttonWith('즐겨찾기에 추가하기'))
    expect(document.body.textContent).toContain('2. 「즐겨찾기 추가」 를 고르세요')
    fireEvent.click(buttonWith('알겠어요'))
    await advance(0)
    expect(prompts()).toContainEqual({ env: 'other', action: 'got_it', where: 'intro' })
    expect(closes).toEqual(['kept'])
  })
})

// MZ2AZ-585 — 안내를 펼치는 보조 단추를 누른 것 자체를 센다(즐겨찾기는 브라우저가 알려 주지 않는다).
const UA_IPHONE_SAFARI =
  'Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.1 Mobile/15E148 Safari/604.1'
const UA_ANDROID_CHROME = 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36'
const actions = () => prompts().map((p) => p.action)

describe('보조 안내 단추를 누르면 keep_prompt(bookmark 또는 home_screen_guide, intro)', () => {
  it('크롬 데스크톱(other) — 「즐겨찾기에 추가하기」 → bookmark 한 번, 그다음 「알겠어요」 → got_it', async () => {
    await keepAll()
    fireEvent.click(buttonWith('즐겨찾기에 추가하기'))
    expect(prompts()).toContainEqual({ env: 'other', action: 'bookmark', where: 'intro' })
    expect(actions().filter((a) => a === 'bookmark')).toHaveLength(1)
    expect(actions()).not.toContain('home_screen_guide')
    fireEvent.click(buttonWith('알겠어요'))
    await advance(0)
    expect(prompts()).toContainEqual({ env: 'other', action: 'got_it', where: 'intro' })
    expect(actions()).toEqual(['view', 'bookmark', 'got_it'])
  })

  it("영어 other — 'Bookmark this page' → bookmark", async () => {
    chooseLanguage('en')
    await keepAll('Keep these 3 on My map')
    fireEvent.click(buttonWith('Bookmark this page'))
    expect(prompts()).toContainEqual({ env: 'other', action: 'bookmark', where: 'intro' })
  })

  it.each<[string, string, 'ios_safari' | 'android_menu']>([
    ['아이폰 사파리', UA_IPHONE_SAFARI, 'ios_safari'],
    ['안드로이드 크롬(설치 창 없음)', UA_ANDROID_CHROME, 'android_menu'],
  ])('%s — 「홈 화면에 추가하기」 → home_screen_guide 한 번, 그다음 「알겠어요」 → got_it', async (_name, ua, env) => {
    setUA(ua)
    await keepAll()
    fireEvent.click(buttonWith('홈 화면에 추가하기'))
    expect(prompts()).toContainEqual({ env, action: 'home_screen_guide', where: 'intro' })
    expect(actions().filter((a) => a === 'home_screen_guide')).toHaveLength(1)
    expect(actions()).not.toContain('bookmark')
    fireEvent.click(buttonWith('알겠어요'))
    await advance(0)
    expect(actions()).toEqual(['view', 'home_screen_guide', 'got_it'])
    expect(closes).toEqual(['kept'])
  })

  it("영어 아이폰 사파리 — 'Add to Home Screen' → home_screen_guide", async () => {
    chooseLanguage('en')
    setUA(UA_IPHONE_SAFARI)
    await keepAll('Keep these 3 on My map')
    fireEvent.click(buttonWith('Add to Home Screen'))
    expect(prompts()).toContainEqual({ env: 'ios_safari', action: 'home_screen_guide', where: 'intro' })
  })

  it('앱 안 브라우저의 「브라우저에서 열기」 는 bookmark, home_screen_guide 를 남기지 않는다', async () => {
    setUA(UA.fbAndroid)
    await keepAll()
    fireEvent.click(buttonWith('브라우저에서 열기'))
    expect(actions()).not.toContain('bookmark')
    expect(actions()).not.toContain('home_screen_guide')
  })
})

// MZ2AZ-588 — 삼성 인터넷은 제 설치 창(WebAPK)을 Play 프로텍트가 막는다. 설치 창 대신 크롬으로 넘긴다.
const UA_SAMSUNG =
  'Mozilla/5.0 (Linux; Android 14; SM-S918N) AppleWebKit/537.36 (KHTML, like Gecko) SamsungBrowser/27.0 Chrome/125.0.0.0 Mobile Safari/537.36'
const SAMSUNG_GUIDE = ['1. 아래 ≡ 를 누르세요', '2. 「현재 페이지 추가」 에서 「북마크」 를 고르세요']

describe('삼성 인터넷(samsung_android) — 크롬에서 열기', () => {
  /** 브라우저가 설치 창을 주었다(beforeinstallprompt) — 붙잡혀 있어도 쓰지 않아야 한다. */
  let installPrompt: ReturnType<typeof vi.fn>
  beforeEach(async () => {
    const { startInstallPromptCapture } = await import('@/features/keep/installPrompt')
    startInstallPromptCapture()
    installPrompt = vi.fn(async () => {})
    const event = Object.assign(new Event('beforeinstallprompt', { cancelable: true }), {
      prompt: installPrompt,
      userChoice: Promise.resolve({ outcome: 'accepted' as const }),
    })
    window.dispatchEvent(event)
  })
  afterEach(() => {
    // 붙잡은 설치 창을 버린다 — 다른 시험에 새지 않게.
    window.dispatchEvent(new Event('appinstalled'))
  })

  it('보조 단추는 「크롬에서 열기」 — 「홈 화면에 추가」 는 없고, 설치 창을 부르지 않는다', async () => {
    const { canPromptInstall } = await import('@/features/keep/installPrompt')
    expect(canPromptInstall()).toBe(true)
    setUA(UA_SAMSUNG)
    await keepAll()
    expect(buttonWith('크롬에서 열기')).toBeTruthy()
    expect(screen.queryByText('홈 화면에 추가')).toBeNull()
    expect(screen.queryByText('홈 화면에 추가하기')).toBeNull()
    expect(prompts()).toEqual([{ env: 'samsung_android', action: 'view', where: 'intro' }])
    expect(installPrompt).not.toHaveBeenCalled()
  })

  it("영어 — 'Open in Chrome'", async () => {
    chooseLanguage('en')
    setUA(UA_SAMSUNG)
    await keepAll('Keep these 3 on My map')
    expect(buttonWith('Open in Chrome')).toBeTruthy()
  })

  it('누르면 open_browser(intro) + 크롬 intent 로 handoff.open, 설치 창은 부르지 않는다', async () => {
    setUA(UA_SAMSUNG)
    await keepAll()
    fireEvent.click(buttonWith('크롬에서 열기'))
    expect(prompts()).toContainEqual({ env: 'samsung_android', action: 'open_browser', where: 'intro' })
    expect(openSpy).toHaveBeenCalledTimes(1)
    const href = openSpy.mock.calls[0]![0] as string
    expect(href.startsWith('intent://')).toBe(true)
    expect(href).toContain('package=com.android.chrome')
    expect(href).toContain('/my-map?keep=32')
    expect(installPrompt).not.toHaveBeenCalled()
    expect(actions()).not.toContain('install')
  })

  it('1.5초 뒤에도 보이면 open_browser_stuck(intro) 한 번 + 두 줄 안내', async () => {
    setUA(UA_SAMSUNG)
    await keepAll()
    fireEvent.click(buttonWith('크롬에서 열기'))
    for (const line of SAMSUNG_GUIDE) expect(document.body.textContent).not.toContain(line)
    await advance(1500)
    expect(prompts().filter((p) => p.action === 'open_browser_stuck')).toEqual([{ env: 'samsung_android', action: 'open_browser_stuck', where: 'intro' }])
    for (const line of SAMSUNG_GUIDE) expect(document.body.textContent).toContain(line)
    expect(closes).toEqual([])
  })

  it('넘어가서 화면이 가려졌으면 안내도 이벤트도 없다', async () => {
    setUA(UA_SAMSUNG)
    await keepAll()
    fireEvent.click(buttonWith('크롬에서 열기'))
    Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => 'hidden' })
    await advance(1500)
    Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => 'visible' })
    expect(prompts().filter((p) => p.action === 'open_browser_stuck')).toEqual([])
    for (const line of SAMSUNG_GUIDE) expect(document.body.textContent).not.toContain(line)
  })
})

describe('나중에', () => {
  it('keep_prompt(skip, intro) + onClose(kept) — 「더 담기」 로', async () => {
    setUA(UA.fbAndroid)
    await keepAll()
    fireEvent.click(buttonWith('나중에'))
    await advance(0)
    expect(prompts()).toContainEqual({ env: 'in_app_android', action: 'skip', where: 'intro' })
    expect(closes).toEqual(['kept'])
    expect(signInPromptStore.get().requested).toBe(false)
  })
})

// ---------------------------------------------------------------- 더 담기의 간직하기 건너뛰기

describe('⑤ 를 본 뒤 「더 담기 → 일정 짜기」', () => {
  it('간직하기(keep) 단계를 건너뛰고 계획(plan)부터', async () => {
    setUA(UA.fbAndroid)
    await keepAll()
    keepActions.startPlan('in_app_android', [32])
    expect(keepStore.get().step).toBe('plan')
  })

  it('⑤ 를 보지 않았으면 지금처럼 간직하기부터', () => {
    keepActions.startPlan('in_app_android', [32])
    expect(keepStore.get().step).toBe('keep')
  })
})

describe('이벤트 모양', () => {
  it('keepPrompt(env, action, intro) 는 where=intro 를 싣고, where 없으면 지금 그대로', () => {
    expect(webEvent.keepPrompt('in_app_ios', 'view', 'intro')).toEqual({ name: 'keep_prompt', params: { env: 'in_app_ios', action: 'view', where: 'intro' } })
    expect(webEvent.keepPrompt('in_app_ios', 'view')).toEqual({ name: 'keep_prompt', params: { env: 'in_app_ios', action: 'view' } })
    expect(webEvent.landingBeat(32, 'keep')).toEqual({ name: 'landing', params: { content_id: 32, action: 'beat', beat: 'keep' } })
  })
})
