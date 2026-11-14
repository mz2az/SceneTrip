/*
 * 담은 뒤 시트 — 정본: docs/project/plans/web-keep-flow.md §5-2 · §6 · §8.
 *
 *   KeepSheet(): 상태는 keepStore. ① more → ② keep(환경마다) → ③ plan.
 *
 * 구현과 약속한 이음매(시험이 가짜로 바꾼다):
 *   - 환경 `currentKeepEnv()`, 설치 창 `promptInstall()` — @/features/keep/installPrompt
 *   - 밖으로 넘기기 `handoff.open(href)` — @/features/keep/handoff
 *   - 더 담을 작품 `homeStore.works`, 촬영지 `api.GET('/contents/{contentId}/places')`, 담기 `cartActions.addMany`
 *   - 로그인 여부 `account.store.signedIn`, 로그인 창 `accountActions.openSignInFor(null, 'keep')`
 *   - 이동 `useTabRouter()` — 마이맵 `paths.myMap`, 계획 `openPlanFromSaved()`(쪽지 pendingStore.plannerManual)
 */
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, useLocation } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const h = vi.hoisted(() => ({
  GET: vi.fn(),
  env: { value: 'other' as string },
  promptInstall: vi.fn(),
  handoffOpen: vi.fn(),
}))

vi.mock('@/api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/client')>()),
  api: { GET: (...args: unknown[]) => h.GET(...args), POST: vi.fn(), DELETE: vi.fn() },
}))

vi.mock('@/analytics', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/analytics')>()
  return { ...actual, track: vi.fn() }
})

vi.mock('@/features/keep/installPrompt', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/features/keep/installPrompt')>()),
  currentKeepEnv: () => h.env.value,
  promptInstall: () => h.promptInstall(),
}))

vi.mock('@/features/keep/handoff', () => ({
  handoff: { open: (href: string) => h.handoffOpen(href) },
}))

import { track, webEvent } from '@/analytics'
import type { components } from '@/api/generated/schema'
import { paths } from '@/app/routes'
import { OverlayRootContext } from '@/design/overlay'
import { pendingStore } from '@/app/tabRouter'
import { homeStore } from '@/features/home/homeModel'
import { account, accountActions } from '@/features/profile/auth/account'
import { chooseLanguage, type Lang } from '@/i18n'
import { cartActions, cartStore } from '@/state/cart'
import { KeepSheet } from './KeepSheet'
import { type KeepEnv, handoffHref, keepUrl } from './keepRules'
import { keepActions, keepStore } from './keepStore'

type PlaceSummary = components['schemas']['PlaceSummary']

const respond = (status: number, data?: unknown, error?: unknown) => ({
  data,
  error,
  response: { ok: status >= 200 && status < 300, status, headers: new Headers() },
})


/** 작품 id → 그 작품의 촬영지. 없으면 빈 목록. */
let placesOf: Map<number, PlaceSummary[]>
let placesFail: Set<number>

let location: ReturnType<typeof useLocation> | null = null
function Probe() {
  location = useLocation()
  return null
}

/** 앱(App.tsx)처럼 시트가 그려질 자리(OverlayRootContext)를 준 채로 띄운다. */
let overlayRoot: HTMLDivElement

function renderSheet() {
  return render(
    <OverlayRootContext.Provider value={overlayRoot}>
      <MemoryRouter initialEntries={['/search/content/32']}>
        <KeepSheet />
        <Probe />
      </MemoryRouter>
    </OverlayRootContext.Provider>,
  )
}

const events = () => vi.mocked(track).mock.calls.map(([e]) => e)
const hasEvent = (e: { name: string; params: Record<string, unknown> }) => events().some((x) => JSON.stringify(x) === JSON.stringify(e))

/** 문구가 든 가장 가까운 단추. */
function buttonWith(text: string): HTMLElement {
  const button = screen.getByText(text).closest('button')
  expect(button, `「${text}」 단추`).not.toBeNull()
  return button as HTMLElement
}

const bodyText = () => document.body.textContent ?? ''

function setEnv(env: KeepEnv) {
  h.env.value = env
}
function setSignedIn(signedIn: boolean) {
  account.store.set((s) => ({ ...s, signedIn, showingSignIn: false }))
}


beforeEach(() => {
  chooseLanguage('ko')
  overlayRoot = document.createElement('div')
  document.body.appendChild(overlayRoot)
  location = null
  setEnv('other')
  setSignedIn(false)
  placesOf = new Map()
  placesFail = new Set()
  h.GET.mockReset()
  h.GET.mockImplementation(async (path: string, init?: { params?: { path?: { contentId?: number } } }) => {
    if (path === '/contents/{contentId}/places') {
      const id = init?.params?.path?.contentId ?? -1
      if (placesFail.has(id)) return respond(500, undefined, { code: 'INTERNAL', message: 'x' })
      const items = placesOf.get(id) ?? []
      return respond(200, { items, total: items.length, limit: 100, offset: 0 })
    }
    if (path === '/cart') return respond(200, { items: [] })
    return respond(200, { items: [], total: 0, limit: 20, offset: 0 })
  })
  h.promptInstall.mockReset()
  h.handoffOpen.mockReset()
  vi.mocked(track).mockClear()
  cartStore.set({ loadState: 'loaded', placeIds: new Set(), items: [], pois: [], poiIds: new Set(), toast: null })
  homeStore.set((s) => ({ ...s, works: [] }))
  pendingStore.set((s) => ({ ...s, plannerManual: false }))
  keepStore.set({ open: false, step: 'more', contentIds: [], title: null, arriving: false, nearby: null })
  vi.spyOn(cartActions, 'addMany').mockResolvedValue({ saved: 3, already: 0, failed: 0 })
})

afterEach(() => {
  cleanup()
  overlayRoot.remove()
  vi.restoreAllMocks()
  vi.useRealTimers()
  account.store.set((s) => ({ ...s, showingSignIn: false }))
  chooseLanguage('ko')
})

describe('KeepSheet — 닫혀 있으면', () => {
  it('아무것도 그리지 않는다', () => {
    renderSheet()
    expect(bodyText()).toBe('')
  })
})

// web-ad-landing.md §2 — 시트 ① 은 없어졌다. 「모두 담기」 뒤에는 「더 담기」(/add)로 간다(다른 작품 포스터는 AddMoreCover.test.tsx 가 지킨다).
describe('① more — 「더 담기」 로 간다', () => {
  it('openAfterSave → 시트는 열리지 않고 /add 로 옮긴다', async () => {
    renderSheet()
    act(() => keepActions.openAfterSave(32, '케이팝 데몬 헌터스'))
    await vi.waitFor(() => expect(location?.pathname).toBe(paths.addMore))
    expect(keepStore.get().open).toBe(false)
    expect(bodyText()).not.toContain('다 담았어요')
  })

  it('그린 뒤 처음에는 옮기지 않는다', () => {
    renderSheet()
    expect(location?.pathname).toBe('/search/content/32')
  })
})

// ── ② keep
const KEEP_TEXT: Record<Exclude<KeepEnv, 'installed'>, { ko: [string, string]; en: [string, string] }> = {
  in_app_android: { ko: ['브라우저에서 열어 마이맵을 간직하세요', '브라우저에서 열기'], en: ['Open in your browser to keep My map', 'Open in browser'] },
  in_app_ios: { ko: ['사파리에서 열어 마이맵을 간직하세요', '사파리에서 열기'], en: ['Open in Safari to keep My map', 'Open in Safari'] },
  installable: { ko: ['홈 화면에 SceneTrip 을 추가하세요', '홈 화면에 추가'], en: ['Add SceneTrip to your Home Screen', 'Add to Home Screen'] },
  android_menu: { ko: ['홈 화면에 SceneTrip 을 추가하세요', '알겠어요'], en: ['Add SceneTrip to your Home Screen', 'Got it'] },
  ios_safari: { ko: ['홈 화면에 SceneTrip 을 추가하세요', '알겠어요'], en: ['Add SceneTrip to your Home Screen', 'Got it'] },
  other: { ko: ['이 페이지를 즐겨찾기에 추가하세요', '알겠어요'], en: ['Bookmark this page', 'Got it'] },
}
const ENVS = Object.keys(KEEP_TEXT) as Array<Exclude<KeepEnv, 'installed'>>
const IN_APP = ['in_app_android', 'in_app_ios'] as const
const NOT_IN_APP = ['installable', 'android_menu', 'ios_safari', 'other'] as const

const PLAN_KO = /이 \d+곳으로 여행 계획을 짜 볼까요\?/
// §9-7 — 1이면 단수(spot).
const PLAN_EN = /Plan a trip with these \d+ spots?\?/

function openKeep(env: KeepEnv) {
  setEnv(env)
  keepActions.openAt('keep', [32, 25])
  return renderSheet()
}

describe('② keep — 환경마다 제목과 주 단추', () => {
  for (const env of ENVS) {
    for (const lang of ['ko', 'en'] as const) {
      it(`${env} · ${lang}`, () => {
        chooseLanguage(lang)
        openKeep(env)
        const [title, primary] = KEEP_TEXT[env][lang]
        expect(bodyText()).toContain(title)
        expect(buttonWith(primary)).toBeTruthy()
      })
    }
  }

  it.each(ENVS)('%s — 뜨면 keep_prompt(env, view)', (env) => {
    openKeep(env)
    expect(hasEvent(webEvent.keepPrompt(env, 'view'))).toBe(true)
  })

  it.each(IN_APP)('%s — 본문에 「이 창을 닫으면 담은 곳이 사라져요」', (env) => {
    openKeep(env)
    expect(bodyText()).toContain('이 창을 닫으면 담은 곳이 사라져요')
  })

  it.each(NOT_IN_APP)('%s — 「이 창을 닫으면…」 은 없다', (env) => {
    openKeep(env)
    expect(bodyText()).not.toContain('이 창을 닫으면 담은 곳이 사라져요')
  })
})

describe('② keep — 앱 안 브라우저: 밖으로 넘기기', () => {
  it.each(IN_APP)('%s — handoff.open(handoffHref(env, keepUrl(origin, contentIds))) 와 open_browser', (env) => {
    openKeep(env)
    fireEvent.click(buttonWith(KEEP_TEXT[env].ko[1]))
    expect(h.handoffOpen).toHaveBeenCalledTimes(1)
    expect(h.handoffOpen).toHaveBeenCalledWith(handoffHref(env, keepUrl(window.location.origin, [32, 25])))
    expect(hasEvent(webEvent.keepPrompt(env, 'open_browser'))).toBe(true)
  })

  // 문구는 명세 §8 의 「안 열릴 때」 안내 문구.
  it.each<[Lang, string, string]>([
    ['ko', '브라우저에서 열기', '안 열리면 오른쪽 위 ⋯ 를 눌러 「외부 브라우저에서 열기」 를 고르세요'],
    ['en', 'Open in browser', "Didn't open? Tap ⋯ at the top right, then “Open in external browser”."],
  ])('%s — 1.5초 뒤에도 화면이 보이면 「안 열릴 때」 안내', (lang, primary, hint) => {
    vi.useFakeTimers()
    chooseLanguage(lang)
    openKeep('in_app_android')
    fireEvent.click(buttonWith(primary))
    expect(bodyText()).not.toContain(hint)
    act(() => {
      vi.advanceTimersByTime(1500)
    })
    expect(bodyText()).toContain(hint)
  })
})

describe('② keep — 설치 창(installable)', () => {
  it.each([
    ['accepted', 'install_accepted'],
    ['dismissed', 'install_dismissed'],
  ] as const)('설치 창 결과 %s → install + %s, 그다음 ③', async (outcome, action) => {
    h.promptInstall.mockResolvedValue(outcome)
    openKeep('installable')
    fireEvent.click(buttonWith('홈 화면에 추가'))
    await vi.waitFor(() => expect(keepStore.get().step).toBe('plan'))
    expect(h.promptInstall).toHaveBeenCalledTimes(1)
    expect(hasEvent(webEvent.keepPrompt('installable', 'install'))).toBe(true)
    expect(hasEvent(webEvent.keepPrompt('installable', action))).toBe(true)
  })
})

describe('② keep — 알겠어요 · 나중에', () => {
  it.each(['android_menu', 'ios_safari', 'other'] as const)('%s — 「알겠어요」 → got_it, ③', (env) => {
    openKeep(env)
    fireEvent.click(buttonWith('알겠어요'))
    expect(hasEvent(webEvent.keepPrompt(env, 'got_it'))).toBe(true)
    expect(keepStore.get().step).toBe('plan')
  })

  it.each(ENVS)('%s — 「나중에」 → skip, ③', (env) => {
    openKeep(env)
    fireEvent.click(buttonWith('나중에'))
    expect(hasEvent(webEvent.keepPrompt(env, 'skip'))).toBe(true)
    expect(keepStore.get().step).toBe('plan')
  })

  it('installed — ② 를 건너뛰고 바로 ③', async () => {
    openKeep('installed')
    await vi.waitFor(() => expect(bodyText()).toMatch(PLAN_KO))
    expect(bodyText()).not.toContain('홈 화면에 SceneTrip 을 추가하세요')
    expect(bodyText()).not.toContain('이 페이지를 즐겨찾기에 추가하세요')
  })
})

describe('② keep — 로그인 보조 단추', () => {
  const SIGN_IN = '로그인해서 어디서나 보기'

  it.each(NOT_IN_APP)('%s · 가입 안 함 → 보인다, 누르면 로그인 창(이유 keep) + sign_in', (env) => {
    const openSignInFor = vi.spyOn(accountActions, 'openSignInFor')
    openKeep(env)
    fireEvent.click(buttonWith(SIGN_IN))
    expect(openSignInFor).toHaveBeenCalledTimes(1)
    expect(openSignInFor.mock.calls[0]![1]).toBe('keep')
    expect(hasEvent(webEvent.keepPrompt(env, 'sign_in'))).toBe(true)
  })

  it('영어 — Sign in to see it anywhere', () => {
    chooseLanguage('en')
    openKeep('other')
    expect(buttonWith('Sign in to see it anywhere')).toBeTruthy()
  })

  it.each(IN_APP)('%s · 가입 안 함 → 앱 안 브라우저라 보이지 않는다', (env) => {
    openKeep(env)
    expect(screen.queryByText(SIGN_IN)).toBeNull()
  })

  it.each(NOT_IN_APP)('%s · 로그인함 → 보이지 않는다', (env) => {
    setSignedIn(true)
    openKeep(env)
    expect(screen.queryByText(SIGN_IN)).toBeNull()
  })
})

describe('③ plan', () => {
  function openPlan() {
    setEnv('other')
    keepActions.openAt('plan', [32, 25])
    return renderSheet()
  }

  it.each<[Lang, RegExp, string, string]>([
    ['ko', PLAN_KO, '계획 짜기', '마이맵 보기'],
    ['en', PLAN_EN, 'Plan my trip', 'See My map'],
  ])('%s — 문구와 두 단추', (lang, title, plan, myMap) => {
    chooseLanguage(lang)
    openPlan()
    expect(bodyText()).toMatch(title)
    expect(buttonWith(plan)).toBeTruthy()
    expect(buttonWith(myMap)).toBeTruthy()
  })

  it('영어 단수(§9-7) — 담은 곳이 1곳이면 「1 spots」 가 없다', () => {
    const one = { placeId: 101, name: '남산서울타워', addedAt: '2026-11-14T00:00:00Z', sourceContentId: 32, sourceContentTitle: '케이팝 데몬 헌터스', latitude: 37.55, longitude: 126.99 }
    // 언어를 먼저 — 언어가 바뀌면 cart.ts 가 장바구니를 다시 받아 덮어쓴다.
    chooseLanguage('en')
    cartStore.set({ loadState: 'loaded', placeIds: new Set([101]), items: [one], pois: [], poiIds: new Set(), toast: null })
    setEnv('other')
    keepActions.openAt('plan', [32])
    renderSheet()
    expect(bodyText()).toMatch(PLAN_EN)
    expect(bodyText()).not.toContain('1 spots')
  })

  it('「계획 짜기」 → 시트를 닫고 plan_start(keep_sheet), 담은 곳으로 직접 짜기(쪽지 plannerManual)로 경로여정', () => {
    openPlan()
    fireEvent.click(buttonWith('계획 짜기'))
    expect(keepStore.get().open).toBe(false)
    expect(hasEvent(webEvent.planStart('keep_sheet'))).toBe(true)
    expect(pendingStore.get().plannerManual).toBe(true)
    expect(location?.pathname).toBe(paths.courses)
  })

  it('「마이맵 보기」 → /my-map 과 my_map(open_keep)', () => {
    openPlan()
    fireEvent.click(buttonWith('마이맵 보기'))
    expect(hasEvent(webEvent.myMap('open_keep'))).toBe(true)
    expect(location?.pathname).toBe(paths.myMap)
  })
})
