/*
 * 앱의 첫 화면 덮개 — 정본: docs/project/plans/web-ad-landing.md §1 · §6 · §7 · §9-1 · §10 · §11(뒤 절이 이긴다).
 *
 *   LandingCover({ contentId, onRest, onClose }): GET /contents/{id} · /contents/{id}/places · /cart · /courses 를 함께.
 *   장면은 목록의 sceneImageThumbnailUrl(1.32.0), 없으면 앞 세 곳만 /places/{id}.
 *   처음 온 사람이 아니면(담은 촬영지·편의시설·코스) onClose('notNew'). 받기 실패·404·0곳·목록에서 빠진 작품 → onClose('failed').
 *   세 박자 scene → spread → rest(시간은 landingScript). ① 은 장면 사진이 오면, 1.2초 안에 안 오면 포스터로 시작(§10-3-4).
 *   화면을 누르면 rest + landing(id,'skip'). 「건너뛰기」 → onClose('skip') + landing(id,'skip')(§11).
 *   rest: onRest(). 「이 N곳 내 지도에 담기」 → addMany(전부, id, onProgress, 'landing'), 「담는 중… d/t」, 끝나면 onClose('kept').
 *         실패가 있으면 남고 「남은 N곳도 담기」. 「먼저 둘러볼게요」 → onClose('browse') + browse. 담는 중에 걷히면 kept 를 부르지 않는다(§10-3-7).
 *   prefers-reduced-motion 이면 처음부터 rest.
 *
 * 시험이 의지하는 이음매:
 *   - 덮개를 든 쪽(IntroShell)은 onClose 를 받으면 덮개를 뗀다 — 여기서는 Host 가 같은 일을 한다.
 *   - 받기 `api.GET`(@/api/client), 담기 `cartActions.addMany`(@/state/cart), 이벤트 `track`(@/analytics).
 *   - 「건너뛰기」 단추 이름 `say('건너뛰기','Skip')`. 움직임 줄이기는 `window.matchMedia('(prefers-reduced-motion: reduce)')`.
 *   - 화면 아무 곳 누르기 = ① 의 문구 「이 장면, 진짜 있는 곳이에요」 를 누르는 것(누름이 덮개까지 올라간다).
 *   - 사진 미리 받기는 jsdom 에서 하지 않는다(주소를 그대로 그린다). 받기 순서 시험만 가짜 Image 와 사용자 에이전트를 건다.
 *
 * ① 은 덮개가 뜨고 1.2초에 포스터로 시작한다 — 그래서 그린 뒤 1.3초에는 ① scene(1.6초) 안에 있다.
 * 지도 부품(@/features/search/map/MapView)은 jsdom 에서 뜨지 않아 빈 부품으로 바꾼다.
 */
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { useState } from 'react'
import { type MockInstance, afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const h = vi.hoisted(() => ({ GET: vi.fn() }))

vi.mock('@/api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/client')>()),
  api: { GET: (...args: unknown[]) => h.GET(...args), POST: vi.fn(), DELETE: vi.fn() },
}))

vi.mock('@/analytics', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/analytics')>()
  return { ...actual, track: vi.fn() }
})

vi.mock('@/features/search/map/MapView', () => ({ MapView: () => null }))

import { track } from '@/analytics'
import { OverlayRootContext } from '@/design/overlay'
import { chooseLanguage, type Lang } from '@/i18n'
import { myMapLandStore } from '@/features/keep/flyToMyMap'
import { cartActions, cartStore } from '@/state/cart'
import type { IntroClose } from './introClose'
import { LandingCover } from './LandingCover'

const TITLE = '케이팝 데몬 헌터스'

const respond = (status: number, data?: unknown, error?: unknown) => ({
  data,
  error: error ?? (status >= 400 ? { code: status === 404 ? 'CONTENT_NOT_FOUND' : 'INTERNAL', message: 'x' } : undefined),
  response: { ok: status >= 200 && status < 300, status, headers: new Headers() },
})

interface Place {
  id: number
  name: string
  latitude: number
  longitude: number
  imageUrl: string
  imageThumbnailUrl: string
  sceneDescription: string
}
const place = (id: number, i: number): Place => ({
  id,
  name: `장소${id}`,
  latitude: 37.55 + i * 0.01,
  longitude: 126.98 + i * 0.01,
  imageUrl: `https://x/place-${id}.jpg`,
  imageThumbnailUrl: `https://x/place-${id}-s.jpg`,
  sceneDescription: `장면 ${id}`,
})
const placesN = (n: number) => Array.from({ length: n }, (_, i) => place(101 + i, i))

/** GET /contents/{id} 의 답 — 'ok' · 404 · 500 · 'delisted'. */
let contentReply: 'ok' | 404 | 500 | 'delisted'
/** GET /contents/{id}/places 의 답 — 목록 또는 'fail'. */
let placesReply: Place[] | 'fail'
/** 서버 장바구니 — GET /cart 가 돌려준다. */
let serverCart: number[]

/** 덮개가 걷힌 까닭(차례대로). */
let closes: IntroClose[] = []
let rests = 0
/** onBeat 로 알린 박자(차례대로)·onKeep 을 부른 수 — IntroShell 이 landing(beat)·떠남에 쓴다. */
let beats: string[] = []
let keeps = 0
/** 코스 수 — GET /courses 가 돌려준다. */
let serverCourses = 0
/** 촬영지 목록에 새 서버의 칸(sceneImageThumbnailUrl·nameKo)을 싣는가. */
let newServer = false

/** IntroShell 처럼 — onClose 를 받으면 덮개를 뗀다. */
function Host({ contentId }: { contentId: number }) {
  const [open, setOpen] = useState(true)
  if (!open) return null
  return (
    <LandingCover
      contentId={contentId}
      onRest={() => (rests += 1)}
      onBeat={(b) => beats.push(b)}
      onKeep={() => (keeps += 1)}
      onClose={(how) => {
        closes.push(how)
        setOpen(false)
      }}
    />
  )
}

let overlayRoot: HTMLDivElement

function renderLanding(contentId = 32) {
  return render(
    <OverlayRootContext.Provider value={overlayRoot}>
      <Host contentId={contentId} />
    </OverlayRootContext.Provider>,
  )
}

/**
 * 가짜 시간을 흘린다 — 100ms 씩 나눠 act 를 따로 감싼다.
 * 한 act 안에서는 React 가 그리기·effect 를 끝까지 미뤄, 그 사이에 걸어 둔 타이머(effect 안의 setTimeout·rAF)가 돌지 못한다.
 */
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

/** 그린 뒤 받기가 끝나고 ① scene 한가운데에 있는 때. */
async function toScene() {
  await advance(50)
  await advance(1250)
}
/** 세 박자가 다 끝난 때. */
async function toRest() {
  await advance(50)
  await advance(30_000)
}

const events = () => vi.mocked(track).mock.calls.map(([e]) => e as { name: string; params: Record<string, unknown> })
const landingActions = () => events().filter((e) => e.name === 'landing').map((e) => e.params)
const hasLanding = (action: string, contentId = 32) => landingActions().some((p) => p.content_id === contentId && p.action === action)
const bodyText = () => document.body.textContent ?? ''

function buttonWith(text: string): HTMLElement {
  const button = screen.getByText(text).closest('button')
  expect(button, `「${text}」 단추`).not.toBeNull()
  return button as HTMLElement
}
const queryButtonWith = (text: string) => screen.queryByText(text)?.closest('button') ?? null

let reducedMotion = false
let addMany: MockInstance<typeof cartActions.addMany>

beforeEach(() => {
  cartStore.set({ loadState: 'notLoaded', placeIds: new Set(), items: [], pois: [], poiIds: new Set(), toast: null })
  chooseLanguage('ko')
  vi.useFakeTimers()
  overlayRoot = document.createElement('div')
  document.body.appendChild(overlayRoot)
  closes = []
  rests = 0
  beats = []
  keeps = 0
  serverCourses = 0
  newServer = false
  reducedMotion = false
  Object.defineProperty(window, 'matchMedia', {
    configurable: true,
    writable: true,
    value: (query: string) => ({
      matches: reducedMotion && query.includes('prefers-reduced-motion') && query.includes('reduce'),
      media: query,
      onchange: null,
      addListener: () => {},
      removeListener: () => {},
      addEventListener: () => {},
      removeEventListener: () => {},
      dispatchEvent: () => false,
    }),
  })
  contentReply = 'ok'
  placesReply = placesN(3)
  serverCart = []
  h.GET.mockReset()
  h.GET.mockImplementation(async (path: string, init?: { params?: { path?: { contentId?: number; placeId?: number } } }) => {
    if (path === '/contents/{contentId}') {
      if (contentReply === 404) return respond(404)
      if (contentReply === 500) return respond(500)
      const count = Array.isArray(placesReply) ? placesReply.length : 0
      return respond(200, {
        id: init?.params?.path?.contentId ?? 32,
        category: 'movie',
        title: TITLE,
        posterUrl: 'https://x/poster-32.jpg',
        placeCount: count,
        ...(contentReply === 'delisted' ? { delisted: true } : {}),
      })
    }
    if (path === '/contents/{contentId}/places') {
      if (placesReply === 'fail') return respond(500)
      const items = newServer
        ? placesReply.map((p) => ({ ...p, sceneImageThumbnailUrl: `https://x/scene-${p.id}-s.jpg`, nameKo: `장소${p.id}` }))
        : placesReply
      return respond(200, { items, total: placesReply.length, limit: 100, offset: 0 })
    }
    if (path === '/places/{placeId}') {
      const id = init?.params?.path?.placeId ?? -1
      const p = (Array.isArray(placesReply) ? placesReply : []).find((x) => x.id === id) ?? place(id, 0)
      return respond(200, {
        ...p,
        scenes: [
          {
            contentId: 32,
            contentTitle: TITLE,
            posterUrl: 'https://x/poster-32.jpg',
            sceneDescription: `Rumi & Jinu sing "Free" at ${p.name}. More words here.`,
            sceneImageUrl: `https://x/scene-${id}.jpg`,
            sceneImageUrls: [`https://x/scene-${id}.jpg`],
          },
        ],
      })
    }
    if (path === '/courses') {
      return respond(200, { items: Array.from({ length: serverCourses }, (_, i) => ({ id: 900 + i, title: `코스${i}` })) })
    }
    if (path === '/cart') {
      return respond(200, {
        items: serverCart.map((placeId) => ({ placeId, name: `장소${placeId}`, addedAt: '2026-11-14T00:00:00Z', sourceContentId: 32 })),
        totalCount: serverCart.length,
        pois: [],
        poiCount: 0,
      })
    }
    return respond(200, { items: [], total: 0, limit: 20, offset: 0 })
  })
  vi.mocked(track).mockClear()
  addMany = vi.spyOn(cartActions, 'addMany').mockResolvedValue({ saved: 3, already: 0, failed: 0 })
})

afterEach(() => {
  cleanup()
  overlayRoot.remove()
  vi.restoreAllMocks()
  vi.useRealTimers()
  cartStore.set({ loadState: 'notLoaded', placeIds: new Set(), items: [], pois: [], poiIds: new Set(), toast: null })
  chooseLanguage('ko')
})

/** 장바구니를 스토어와 서버(GET /cart) 양쪽에 같은 값으로 둔다. */
function withCart(placeIds: number[]) {
  serverCart = placeIds
  cartStore.set({
    loadState: 'loaded',
    placeIds: new Set(placeIds),
    items: placeIds.map((placeId) => ({ placeId, name: `장소${placeId}`, addedAt: '2026-11-14T00:00:00Z', sourceContentId: 32 })),
    pois: [],
    poiIds: new Set(),
    toast: null,
  })
}

// ---------------------------------------------------------------- §1-1 받는 것 · §11 누구에게

describe('받기', () => {
  it('작품·촬영지·장바구니·코스를 받고, 장면 사진은 앞의 세 곳만 /places/{id} 로 받는다(옛 서버)', async () => {
    placesReply = placesN(5)
    renderLanding()
    await toScene()
    const calls = h.GET.mock.calls
    expect(calls.some((c) => c[0] === '/contents/{contentId}' && (c[1] as { params: { path: { contentId: number } } }).params.path.contentId === 32)).toBe(true)
    expect(calls.some((c) => c[0] === '/contents/{contentId}/places' && (c[1] as { params: { path: { contentId: number } } }).params.path.contentId === 32)).toBe(true)
    expect(calls.some((c) => c[0] === '/courses')).toBe(true)
    const detailIds = new Set(
      calls.filter((c) => c[0] === '/places/{placeId}').map((c) => (c[1] as { params: { path: { placeId: number } } }).params.path.placeId),
    )
    expect(detailIds).toEqual(new Set([101, 102, 103]))
  })

  it('목록에 장면 작은 판(sceneImageThumbnailUrl)이 오면 /places/{id} 를 부르지 않는다(§10-2)', async () => {
    newServer = true
    placesReply = placesN(5)
    renderLanding()
    await toScene()
    expect(h.GET.mock.calls.some((c) => c[0] === '/places/{placeId}')).toBe(false)
    expect(bodyText()).toContain('이 장면, 진짜 있는 곳이에요')
  })

  it('랜딩이 뜨면 landing(id, view)', async () => {
    renderLanding()
    await toScene()
    expect(hasLanding('view')).toBe(true)
  })

  it('박자가 바뀔 때마다 onBeat — scene → spread → rest(§6 landing(beat))', async () => {
    renderLanding()
    await toScene()
    expect(beats).toEqual(['scene'])
    await toRest()
    expect(beats.filter((b, i) => beats.indexOf(b) === i)).toEqual(['scene', 'spread', 'rest'])
    expect(keeps).toBe(0)
  })
})

describe('받지 못하면 덮개를 걷는다 — onClose(failed)', () => {
  it.each<[string, () => void]>([
    ['작품이 없다(404)', () => (contentReply = 404)],
    ['작품 받기 실패(500)', () => (contentReply = 500)],
    ['촬영지 받기 실패', () => (placesReply = 'fail')],
    ['촬영지가 0곳', () => (placesReply = [])],
    ['목록에서 빠진 작품', () => (contentReply = 'delisted')],
  ])('%s', async (_name, setup) => {
    setup()
    renderLanding()
    await toRest()
    expect(closes).toEqual(['failed'])
    expect(addMany).not.toHaveBeenCalled()
    expect(hasLanding('view')).toBe(false)
  })
})

describe('처음 온 사람이 아니면 덮개 없음 — onClose(notNew)(§11)', () => {
  it('담은 촬영지가 하나라도 있으면', async () => {
    withCart([999])
    renderLanding()
    await toRest()
    expect(closes).toEqual(['notNew'])
    expect(hasLanding('view')).toBe(false)
    expect(bodyText()).not.toContain('이 장면, 진짜 있는 곳이에요')
  })

  it('담은 편의시설이 있으면', async () => {
    cartStore.set({ loadState: 'loaded', placeIds: new Set(), items: [], pois: [], poiIds: new Set([5]), toast: null })
    renderLanding()
    await toRest()
    expect(closes).toEqual(['notNew'])
  })

  it('코스가 있으면', async () => {
    serverCourses = 1
    renderLanding()
    await toRest()
    expect(closes).toEqual(['notNew'])
  })

  it('아무것도 없으면 덮개가 뜬다', async () => {
    renderLanding()
    await toScene()
    expect(closes).toEqual([])
    expect(bodyText()).toContain('이 장면, 진짜 있는 곳이에요')
  })
})

// ---------------------------------------------------------------- §1-2 세 박자

describe('세 박자 문구', () => {
  it.each<[Lang, string, string, string]>([
    ['ko', '이 장면, 진짜 있는 곳이에요', `${TITLE}의 실제 장소 3곳`, '이 3곳 내 지도에 담기'],
    ['en', 'This scene is a real place', `3 real places from ${TITLE}`, 'Keep these 3 on my map'],
  ])('%s — ① 장면 문구와 제목, 단추 없음', async (lang, sceneLine, _restTitle, keep) => {
    chooseLanguage(lang)
    renderLanding()
    await toScene()
    expect(bodyText()).toContain(sceneLine)
    expect(bodyText()).toContain(TITLE)
    // ① 에는 단추가 없다(§9-1) — 담기 단추는 ③ 에서만.
    expect(queryButtonWith(keep)).toBeNull()
    expect(closes).toEqual([])
  })

  it.each<[Lang, string, string, string]>([
    ['ko', `${TITLE}의 실제 장소 3곳`, '이 3곳 내 지도에 담기', '먼저 둘러볼게요'],
    ['en', `3 real places from ${TITLE}`, 'Keep these 3 on my map', 'Just looking'],
  ])('%s — ③ 제목·주 단추·둘러보기, onRest', async (lang, title, keep, browse) => {
    chooseLanguage(lang)
    renderLanding()
    await toRest()
    expect(bodyText()).toContain(title)
    expect(buttonWith(keep)).toBeTruthy()
    expect(buttonWith(browse)).toBeTruthy()
    expect(hasLanding('rest')).toBe(true)
    expect(rests).toBe(1)
  })

  it.each<[Lang, string]>([
    ['ko', '이 1곳 내 지도에 담기'],
    ['en', 'Keep this 1 on my map'],
  ])('%s — 1곳이면 단수', async (lang, keep) => {
    chooseLanguage(lang)
    placesReply = placesN(1)
    renderLanding()
    await toRest()
    expect(buttonWith(keep)).toBeTruthy()
  })
})

describe('② 의 캡션(§10-3-5)', () => {
  it('en — 상투구를 뗀 장면과 그 아래 장소 이름(한글 병기)', async () => {
    chooseLanguage('en')
    newServer = true
    placesReply = placesN(3).map((p) => ({ ...p, name: `Place ${p.id}`, sceneDescription: 'This is the backdrop for the scene where Rumi and Jinu sing on a roof.' }))
    renderLanding()
    await toScene()
    await advance(1600) // ② 로
    expect(bodyText()).toContain('Rumi and Jinu sing on a roof')
    expect(bodyText()).not.toContain('This is the backdrop')
    expect(bodyText()).toContain('Place 101 (장소101)')
  })

  it('장면 설명이 없으면 「{작품}의 장면」', async () => {
    newServer = true
    placesReply = placesN(3).map((p) => ({ ...p, sceneDescription: '' }))
    renderLanding()
    await toScene()
    await advance(1600)
    expect(bodyText()).toContain(`${TITLE}의 장면`)
  })
})

describe('화면 누르기·건너뛰기', () => {
  it('화면을 누르면 바로 rest 로, landing(id, skip) — 덮개는 그대로', async () => {
    renderLanding()
    await toScene()
    expect(queryButtonWith('이 3곳 내 지도에 담기')).toBeNull()
    fireEvent.click(screen.getByText('이 장면, 진짜 있는 곳이에요'))
    await advance(0)
    expect(buttonWith('이 3곳 내 지도에 담기')).toBeTruthy()
    expect(hasLanding('skip')).toBe(true)
    expect(closes).toEqual([])
  })

  // §11 — 「건너뛰기」 는 ③ 으로 가는 것이 아니라 덮개를 걷는다(옛 시험은 ③ 으로 갔다).
  it.each<[Lang, string]>([
    ['ko', '건너뛰기'],
    ['en', 'Skip'],
  ])('%s — 「%s」 단추는 덮개를 걷는다: onClose(skip) + landing(id, skip)', async (lang, skip) => {
    chooseLanguage(lang)
    renderLanding()
    await toScene()
    fireEvent.click(screen.getByRole('button', { name: skip }))
    await advance(0)
    expect(closes).toEqual(['skip'])
    expect(hasLanding('skip')).toBe(true)
  })

  it('건너뛰기는 담지 않는다 — 저장은 사용자가 누를 때만', async () => {
    renderLanding()
    await toScene()
    fireEvent.click(screen.getByRole('button', { name: '건너뛰기' }))
    await advance(30_000)
    expect(addMany).not.toHaveBeenCalled()
  })
})

describe('자동으로 담지 않는다', () => {
  it('세 박자가 끝나도 addMany 를 부르지 않는다', async () => {
    renderLanding()
    await toRest()
    expect(addMany).not.toHaveBeenCalled()
  })
})

describe('prefers-reduced-motion', () => {
  it('처음부터 rest — 장면을 기다리지 않는다', async () => {
    reducedMotion = true
    renderLanding()
    await advance(50)
    await advance(100)
    expect(buttonWith('이 3곳 내 지도에 담기')).toBeTruthy()
  })
})

// ---------------------------------------------------------------- §1-3 누른 뒤

describe('담기', () => {
  it('addMany(전부, id, onProgress, landing) 를 부르고 landing(id, save)', async () => {
    renderLanding()
    await toRest()
    fireEvent.click(buttonWith('이 3곳 내 지도에 담기'))
    await advance(0)
    expect(addMany).toHaveBeenCalledTimes(1)
    const [ids, contentId, onProgress, source] = addMany.mock.calls[0]!
    expect([...ids].sort((a, b) => a - b)).toEqual([101, 102, 103])
    expect(contentId).toBe(32)
    expect(typeof onProgress).toBe('function')
    expect(source).toBe('landing')
    expect(hasLanding('save')).toBe(true)
    expect(keeps).toBe(1)
  })

  it.each<[Lang, string, string]>([
    ['ko', '이 3곳 내 지도에 담기', '담는 중… 1/3'],
    ['en', 'Keep these 3 on my map', 'Saving… 1/3'],
  ])('%s — 담는 동안 단추가 진행을 보인다', async (lang, keep, saving) => {
    chooseLanguage(lang)
    let progress: ((done: number, total: number) => void) | undefined
    let finish: (v: { saved: number; already: number; failed: number }) => void = () => {}
    addMany.mockImplementation((_ids, _cid, onProgress) => {
      progress = onProgress
      return new Promise((resolve) => (finish = resolve))
    })
    renderLanding()
    await toRest()
    fireEvent.click(buttonWith(keep))
    await advance(0)
    act(() => progress?.(1, 3))
    expect(bodyText()).toContain(saving)
    await act(async () => finish({ saved: 3, already: 0, failed: 0 }))
  })

  it('끝나고 실패가 없으면 onClose(kept) — 「더 담기」 로', async () => {
    renderLanding()
    await toRest()
    fireEvent.click(buttonWith('이 3곳 내 지도에 담기'))
    await advance(0)
    await advance(0)
    expect(closes).toEqual(['kept'])
  })

  it('실패가 있으면 그 자리에 남고 「남은 N곳도 담기」, 누르면 다시 담는다', async () => {
    addMany.mockImplementation(async () => {
      cartStore.set((s) => ({ ...s, loadState: 'loaded', placeIds: new Set([101, 102]) }))
      serverCart = [101, 102]
      return { saved: 2, already: 0, failed: 1 }
    })
    renderLanding()
    await toRest()
    fireEvent.click(buttonWith('이 3곳 내 지도에 담기'))
    await advance(0)
    await advance(0)
    expect(closes).toEqual([])
    const again = buttonWith('남은 1곳도 담기')
    fireEvent.click(again)
    await advance(0)
    expect(addMany).toHaveBeenCalledTimes(2)
  })

  it('담긴 것부터 하나씩 「내 지도」 로 날아간다 — 실패한 곳은 날지 않는다(§10-3-9)', async () => {
    let progress: ((done: number, total: number) => void) | undefined
    let finish: (v: { saved: number; already: number; failed: number }) => void = () => {}
    addMany.mockImplementation((_ids, _cid, onProgress) => {
      progress = onProgress
      return new Promise((resolve) => (finish = resolve))
    })
    renderLanding()
    await toRest()
    const before = myMapLandStore.get()
    fireEvent.click(buttonWith('이 3곳 내 지도에 담기'))
    await advance(0)
    expect(myMapLandStore.get()).toBe(before)
    act(() => {
      cartStore.set((s) => ({ ...s, placeIds: new Set([101]) }))
      progress?.(1, 3)
    })
    await advance(0)
    expect(myMapLandStore.get()).toBe(before + 1)
    // 102 는 실패 — 담기지 않았다.
    act(() => progress?.(2, 3))
    act(() => {
      cartStore.set((s) => ({ ...s, placeIds: new Set([101, 103]) }))
      progress?.(3, 3)
    })
    await advance(0)
    expect(myMapLandStore.get()).toBe(before + 2)
    await act(async () => finish({ saved: 2, already: 0, failed: 1 }))
  })

  it('담는 중에 「먼저 둘러볼게요」 로 걷으면 담기는 끝까지 하되 「더 담기」 로 끌고 가지 않는다(§10-3-7)', async () => {
    let finish: (v: { saved: number; already: number; failed: number }) => void = () => {}
    addMany.mockImplementation(() => new Promise((resolve) => (finish = resolve)))
    renderLanding()
    await toRest()
    fireEvent.click(buttonWith('이 3곳 내 지도에 담기'))
    await advance(0)
    fireEvent.click(buttonWith('먼저 둘러볼게요'))
    await advance(0)
    expect(closes).toEqual(['browse'])
    await act(async () => finish({ saved: 3, already: 0, failed: 0 }))
    await advance(1000)
    expect(closes).toEqual(['browse'])
  })
})

describe('먼저 둘러볼게요', () => {
  it('덮개를 걷는다 — onClose(browse), landing(id, browse), 담지 않는다(§11)', async () => {
    renderLanding()
    await toRest()
    fireEvent.click(buttonWith('먼저 둘러볼게요'))
    await advance(0)
    expect(closes).toEqual(['browse'])
    expect(hasLanding('browse')).toBe(true)
    expect(addMany).not.toHaveBeenCalled()
  })

  it('③ 의 「내 지도」 단추 → onClose(myMap)', async () => {
    renderLanding()
    await toRest()
    fireEvent.click(screen.getByRole('button', { name: '내 지도' }))
    await advance(0)
    expect(closes).toEqual(['myMap'])
  })
})

// ---------------------------------------------------------------- §10-3-3 · §10-3-4 사진 받기 순서와 기다림 없는 시계

describe('사진 — 받는 순서와 포스터로 시작', () => {
  /** 가짜 Image — 만든 차례대로 주소를 적고, 시험이 `load(url)` 로 받았다고 알린다. */
  class FakeImage {
    static made: FakeImage[] = []
    onload: (() => void) | null = null
    onerror: (() => void) | null = null
    referrerPolicy = ''
    fetchPriority = ''
    src = ''
    constructor() {
      FakeImage.made.push(this)
    }
  }
  const load = async (url: string) => {
    await act(async () => {
      for (const img of FakeImage.made.filter((i) => i.src === url)) img.onload?.()
    })
  }
  const asked = () => FakeImage.made.map((i) => i.src)
  let uaSpy: MockInstance
  beforeEach(() => {
    FakeImage.made = []
    vi.stubGlobal('Image', FakeImage)
    uaSpy = vi.spyOn(navigator, 'userAgent', 'get').mockReturnValue('Mozilla/5.0 (Linux; Android 10) Chrome/157')
  })
  afterEach(() => {
    vi.unstubAllGlobals()
    uaSpy.mockRestore()
  })

  it('① 장면 작은 판을 먼저, 그다음 첫 카드 3장(+영웅 카드), 나머지는 그 뒤', async () => {
    newServer = true
    placesReply = placesN(6)
    renderLanding()
    await advance(50)
    expect(asked()).toEqual(['https://x/scene-101-s.jpg'])
    await load('https://x/scene-101-s.jpg')
    await advance(0)
    expect(asked().slice(1)).toEqual(['https://x/place-102-s.jpg', 'https://x/place-103-s.jpg', 'https://x/place-104-s.jpg', 'https://x/place-101-s.jpg'])
    for (const id of [102, 103, 104, 101]) await load(`https://x/place-${id}-s.jpg`)
    await advance(0)
    expect(asked().slice(5)).toEqual(['https://x/place-105-s.jpg', 'https://x/place-106-s.jpg'])
  })

  it('장면 사진이 오면 곧바로 ① — 1.2초를 기다리지 않는다', async () => {
    newServer = true
    renderLanding()
    await advance(50)
    await load('https://x/scene-101-s.jpg')
    await advance(100)
    expect(bodyText()).toContain('이 장면, 진짜 있는 곳이에요')
    expect(document.querySelector('.st-landing__hero img')?.getAttribute('src')).toBe('https://x/scene-101-s.jpg')
  })

  it('1.2초 안에 안 오면 포스터로 ① 을 시작하고, 장면 사진이 오면 바꿔 끼운다', async () => {
    newServer = true
    renderLanding()
    await advance(50)
    await advance(1000)
    expect(bodyText()).not.toContain('이 장면, 진짜 있는 곳이에요')
    await advance(250)
    expect(bodyText()).toContain('이 장면, 진짜 있는 곳이에요')
    // 포스터는 먼저 받지 않았으니 아직 그리지 않는다 — 장면 사진이 오면 그것.
    await load('https://x/scene-101-s.jpg')
    await advance(0)
    expect(document.querySelector('.st-landing__hero img')?.getAttribute('src')).toBe('https://x/scene-101-s.jpg')
  })

  it('옛 서버 — 원본 장면(장당 수 MB)은 카드 사진 뒤로 미루고 ① 은 포스터', async () => {
    placesReply = placesN(4)
    renderLanding()
    await advance(50)
    await advance(50)
    expect(asked()[0]).toBe('https://x/poster-32.jpg')
    await load('https://x/poster-32.jpg')
    await advance(1300)
    expect(document.querySelector('.st-landing__hero img')?.getAttribute('src')).toBe('https://x/poster-32.jpg')
    const sceneAt = asked().indexOf('https://x/scene-101.jpg')
    const lastCardAt = Math.max(...['102', '103', '104', '101'].map((id) => asked().indexOf(`https://x/place-${id}-s.jpg`)))
    if (sceneAt >= 0) expect(sceneAt).toBeGreaterThan(lastCardAt)
  })

  it('옛 서버 — 작은 장면이 없다고 판명나고 포스터가 오면 1.2초를 기다리지 않고 ①', async () => {
    renderLanding()
    await advance(50)
    await advance(50)
    await load('https://x/poster-32.jpg')
    await advance(100)
    expect(bodyText()).toContain('이 장면, 진짜 있는 곳이에요')
  })

  it('② 의 카드는 사진이 온 것부터 날아간다 — 안 온 카드는 뒤로', async () => {
    newServer = true
    placesReply = placesN(4)
    renderLanding()
    await advance(50)
    await load('https://x/scene-101-s.jpg')
    // 첫 카드 묶음 가운데 104 만 받았다.
    await load('https://x/place-104-s.jpg')
    const t = landingTimelineOf(4)
    await advance(t.sceneMs + 700 + t.perCardMs + 10)
    // 첫 번째로 꽂힌 핀은 104 의 사진이다.
    const pins = [...document.querySelectorAll('.st-landing__pin img')].map((i) => i.getAttribute('src'))
    expect(pins[0]).toBe('https://x/place-104-s.jpg')
  })
})

import { landingTimeline as landingTimelineOf } from './landingScript'
