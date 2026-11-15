/*
 * 앱의 첫 화면 덮개 — 정본: docs/project/plans/web-ad-landing.md §12(연출) · §1-3 · §6 · §10 · §11(뒤 절이 이긴다).
 *
 *   LandingCover({ contentId, onRest, onClose }): GET /contents/{id} · /contents/{id}/places · /cart · /courses 만 받는다
 *   (§12 — 장면 사진·/places/{id} 는 받지 않는다).
 *   처음 온 사람이 아니면(담은 촬영지·편의시설·코스) onClose('notNew'). 받기 실패·404·0곳·목록에서 빠진 작품 → onClose('failed').
 *   네 박자 drop → poster → spill → rest(시간은 landingScript — §12-1 느리고 크게). 받으면 곧바로 ①.
 *   ② 「이 장소들, 진짜 {도시}에 있어요!」 + 포스터 아래 「{작품}의 장면들」, 포스터가 안 왔으면 제목 카드, 오면 바꿔 끼운다.
 *   ③ 카드(최대 10장)가 포스터 둘레로 펼쳐졌다가 한 장씩 크게 → 주머니로, 수가 오른다. 작품 32 는 앞면이 일러스트(/intro/kdh/{id}.webp),
 *      뒷면이 실제 사진(뒤집힘). 일러스트가 없으면 사진만, 사진도 없으면 이름 카드. 영어 화면이면 이름 아래 한국어 이름(한 번 더 받는다).
 *   ④ 「{N}곳, 전부 {도시}에 있어요!」 + 담기 + 「먼저 둘러볼게요」.
 *   화면을 누르면 rest + landing(id,'skip'). 「건너뛰기」 → onClose('skip') + landing(id,'skip')(§11).
 *   rest: onRest(). 「이 N곳 마이맵에 담기」 → addMany(전부, id, onProgress, 'landing'), 「담는 중… d/t」,
 *         끝나면 ⑤ 간직하기(§13-1 — 자세한 시험은 KeepBeat.test.tsx), 거기서 「나중에」 → onClose('kept').
 *         실패가 있으면 남고 「남은 N곳도 담기」. 「먼저 둘러볼게요」 → onClose('browse') + browse. 담는 중에 걷히면 kept 를 부르지 않는다(§10-3-7).
 *   prefers-reduced-motion 이면 처음부터 rest.
 *
 * 시험이 의지하는 이음매:
 *   - 덮개를 든 쪽(IntroShell)은 onClose 를 받으면 덮개를 뗀다 — 여기서는 Host 가 같은 일을 한다.
 *   - 받기 `api.GET`(@/api/client), 담기 `cartActions.addMany`(@/state/cart), 이벤트 `track`(@/analytics).
 *   - 「건너뛰기」 단추 이름 `say('건너뛰기','Skip')`. 움직임 줄이기는 `window.matchMedia('(prefers-reduced-motion: reduce)')`.
 *   - 화면 아무 곳 누르기 = 덮개 무대(.st-landing)를 누르는 것.
 *   - 주머니의 수 = .st-film__bag-count, 카드 = .st-card(뒤집는 카드는 data-flip, 뒷면 .st-card__face--back, 이름 .st-card__name·.st-card__ko,
 *     이름 카드 .st-card__namecard), 포스터 = .st-film__poster, 포스터 아래 글 = .st-film__caption.
 *   - 사진 미리 받기는 jsdom 에서 하지 않는다(주소를 그대로 그린다). 받기 순서 시험만 가짜 Image 와 사용자 에이전트를 건다.
 *
 * 받으면 곧바로 ① — 그린 뒤 0.6초에는 ① drop(1.5초) 안에 있다.
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
  address: string
}
const place = (id: number, i: number): Place => ({
  id,
  name: `장소${id}`,
  latitude: 37.55 + i * 0.01,
  longitude: 126.98 + i * 0.01,
  imageUrl: `https://x/place-${id}.jpg`,
  imageThumbnailUrl: `https://x/place-${id}-s.jpg`,
  address: `서울특별시 종로구 ${id}`,
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

/** 그린 뒤 받기가 끝나고 ① drop 한가운데에 있는 때. */
async function toDrop() {
  await advance(50)
  await advance(550)
}
/** ② poster 한가운데(받고 1.5초 + 0.4초 — ② 는 1.7초). */
async function toPoster() {
  await toDrop()
  await advance(1300)
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
  h.GET.mockImplementation(async (path: string, init?: { params?: { path?: { contentId?: number; placeId?: number } }; headers?: Record<string, string> }) => {
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
      // 한국어로 달라면 한국어 이름(영어 화면의 카드 아래 작은 줄).
      const items = init?.headers?.['Accept-Language'] === 'ko' ? placesReply.map((p) => ({ ...p, name: `한글${p.id}` })) : placesReply
      return respond(200, { items, total: placesReply.length, limit: 100, offset: 0 })
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

// ---------------------------------------------------------------- 받는 것 · §11 누구에게

describe('받기', () => {
  it('작품·촬영지·장바구니·코스를 받고, /places/{id}(장면 사진)는 부르지 않는다(§12)', async () => {
    placesReply = placesN(5)
    renderLanding()
    await toRest()
    const calls = h.GET.mock.calls
    expect(calls.some((c) => c[0] === '/contents/{contentId}' && (c[1] as { params: { path: { contentId: number } } }).params.path.contentId === 32)).toBe(true)
    expect(calls.some((c) => c[0] === '/contents/{contentId}/places' && (c[1] as { params: { path: { contentId: number } } }).params.path.contentId === 32)).toBe(true)
    expect(calls.some((c) => c[0] === '/courses')).toBe(true)
    expect(calls.some((c) => c[0] === '/places/{placeId}')).toBe(false)
  })

  it('랜딩이 뜨면 landing(id, view)', async () => {
    renderLanding()
    await toDrop()
    expect(hasLanding('view')).toBe(true)
  })

  it('박자가 바뀔 때마다 onBeat — drop → poster → spill → rest(§6 landing(beat))', async () => {
    renderLanding()
    await toDrop()
    expect(beats).toEqual(['drop'])
    await toRest()
    expect(beats.filter((b, i) => beats.indexOf(b) === i)).toEqual(['drop', 'poster', 'spill', 'rest'])
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
    expect(document.querySelector('.st-film')).toBeNull()
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
    await toDrop()
    expect(closes).toEqual([])
    expect(document.querySelector('.st-film')).not.toBeNull()
  })
})

// ---------------------------------------------------------------- §12 네 박자

const pouchCount = () => Number(document.querySelector('.st-film__bag-count')?.textContent ?? 'NaN')
const beatNow = () => document.querySelector('.st-film')?.getAttribute('data-beat') ?? null
/** 그 박자가 될 때까지 10ms 씩(최대 20초). */
async function untilBeat(beat: string) {
  for (let i = 0; i < 2000 && beatNow() !== beat; i++) await advance(10)
}

describe('네 박자 (§12)', () => {
  it('① drop — 해태와 SceneTrip, 포스터·단추는 아직 없다', async () => {
    renderLanding()
    await toDrop()
    expect(document.querySelector('.st-film')?.getAttribute('data-beat')).toBe('drop')
    expect(document.querySelector('.st-film__hero')).not.toBeNull()
    expect(bodyText()).toContain('SceneTrip')
    expect(document.querySelector('.st-film__poster')).toBeNull()
    expect(queryButtonWith('이 3곳 마이맵에 담기')).toBeNull()
  })

  it.each<[Lang, string, string]>([
    ['ko', '이 장소들, 진짜 서울에 있어요!', `${TITLE}의 장면들`],
    ['en', 'These places are real — in Seoul!', `Scenes from ${TITLE}`],
  ])('%s — ② poster: 포스터가 서고 해태가 빼꼼 + 「%s」, 포스터 아래 「%s」', async (lang, ask, caption) => {
    chooseLanguage(lang)
    renderLanding()
    await toPoster()
    expect(document.querySelector('.st-film')?.getAttribute('data-beat')).toBe('poster')
    expect(document.querySelector('.st-film__poster img')?.getAttribute('src')).toBe('https://x/poster-32.jpg')
    expect(document.querySelector('.st-film__peek .st-film__head')).not.toBeNull()
    expect(document.querySelectorAll('.st-film__paw')).toHaveLength(2)
    expect(bodyText()).toContain(ask)
    expect(bodyText()).not.toMatch(/Where is this|저기 어디야/)
    expect(document.querySelector('.st-film__caption')?.textContent).toBe(caption)
    expect(queryButtonWith(lang === 'ko' ? '이 3곳 마이맵에 담기' : 'Keep these 3 on My map')).toBeNull()
  })

  it('③ spill — 카드가 펼쳐지고(앞면 일러스트, 뒷면 사진과 이름) 한 장씩 주머니로, 수가 0 에서 N 까지 오른다', async () => {
    placesReply = placesN(4)
    renderLanding()
    await untilBeat('spill') // ③ 시작 직후 — 펼침
    expect(beatNow()).toBe('spill')
    expect(document.querySelector('.st-film')?.getAttribute('data-phase')).toBe('spread')
    expect(pouchCount()).toBe(0)
    const cards = [...document.querySelectorAll('.st-card')]
    expect(cards).toHaveLength(4)
    const first = cards[0]!
    expect(first.hasAttribute('data-flip')).toBe(true)
    expect(first.querySelector('.st-card__face:not(.st-card__face--back) img.st-card__photo')?.getAttribute('src')).toBe('/intro/kdh/101.webp')
    expect(first.querySelector('.st-card__face--back img.st-card__photo')?.getAttribute('src')).toBe('https://x/place-101-s.jpg')
    expect(first.querySelector('.st-card__face--back .st-card__name')?.textContent).toBe('장소101')
    expect(first.querySelector('.st-card__face:not(.st-card__face--back) .st-card__name')).toBeNull()
    const seen: number[] = []
    for (let i = 0; i < 80; i++) {
      await advance(100)
      seen.push(pouchCount())
      if (beatNow() === 'rest') break
    }
    expect(seen.some((c) => c > 0 && c < 4)).toBe(true)
    expect(beatNow()).toBe('rest')
    expect(pouchCount()).toBe(4)
    expect(document.querySelector('.st-card')).toBeNull()
  })

  it('③ — 주머니에 들어간 카드는 사라지고, 들어갈 때마다 하나씩 준다', async () => {
    placesReply = placesN(3)
    renderLanding()
    await untilBeat('spill')
    await advance(1300 + 650 + 340 + 50) // 첫 장이 들어간 뒤
    expect(pouchCount()).toBe(1)
    expect(document.querySelectorAll('.st-card')).toHaveLength(2)
    expect(document.querySelector('.st-film')?.getAttribute('data-phase')).toBe('one')
  })

  it('③ — 10곳이 넘으면 카드는 10장, 마지막 장이 들어갈 때 수가 N 까지', async () => {
    placesReply = placesN(14)
    renderLanding()
    await untilBeat('spill')
    expect(document.querySelectorAll('.st-card')).toHaveLength(10)
    await untilBeat('rest')
    expect(pouchCount()).toBe(14)
  })

  it('en — 카드 이름 아래 작게 한국어 이름(한국어로 한 번 더 받는다)', async () => {
    chooseLanguage('en')
    renderLanding()
    await untilBeat('spill')
    const back = document.querySelector('.st-card .st-card__face--back')!
    expect(back.querySelector('.st-card__name')?.textContent).toBe('장소101')
    expect(back.querySelector('.st-card__ko')?.textContent).toBe('한글101')
    const koCalls = h.GET.mock.calls.filter((c) => c[0] === '/contents/{contentId}/places' && (c[1] as { headers?: Record<string, string> }).headers?.['Accept-Language'] === 'ko')
    expect(koCalls).toHaveLength(1)
  })

  it('ko — 한국어 화면이면 한국어 이름 줄도, 두 번째 받기도 없다', async () => {
    renderLanding()
    await untilBeat('spill')
    expect(document.querySelector('.st-card__ko')).toBeNull()
    expect(h.GET.mock.calls.filter((c) => c[0] === '/contents/{contentId}/places')).toHaveLength(1)
  })

  it('작품 32 가 아니면 일러스트 없이 실제 사진만 — 뒤집지 않는다', async () => {
    renderLanding(7)
    await untilBeat('spill')
    const first = document.querySelector('.st-card')!
    expect(first.hasAttribute('data-flip')).toBe(false)
    expect(first.querySelector('img.st-card__photo')?.getAttribute('src')).toBe('https://x/place-101-s.jpg')
    expect(first.querySelector('.st-card__name')?.textContent).toBe('장소101')
    expect(document.querySelector('.st-card__face--back')).toBeNull()
  })

  it.each<[Lang, string, string, string]>([
    ['ko', '3곳, 전부 서울에 있어요!', '이 3곳 마이맵에 담기', '먼저 둘러볼게요'],
    ['en', '3 places, all in Seoul!', 'Keep these 3 on My map', 'Just looking'],
  ])('%s — ④ rest: 핀 든 해태 + 「%s」 + 주 단추·둘러보기, onRest', async (lang, line, keep, browse) => {
    chooseLanguage(lang)
    renderLanding()
    await toRest()
    expect(document.querySelector('.st-film__pin')).not.toBeNull()
    expect(screen.getByRole('heading').textContent).toBe(line)
    expect(buttonWith(keep)).toBeTruthy()
    expect(buttonWith(browse)).toBeTruthy()
    expect(hasLanding('rest')).toBe(true)
    expect(rests).toBe(1)
  })

  it('④ — 주소를 모르면 한국, 섞여 있으면 「대부분」', async () => {
    placesReply = placesN(3).map((p, i) => ({ ...p, address: i === 0 ? '부산광역시 중구' : p.address }))
    renderLanding()
    await toRest()
    expect(screen.getByRole('heading').textContent).toBe('3곳, 대부분 서울에 있어요!')
    cleanup()
    placesReply = placesN(2).map((p) => ({ ...p, address: '' }))
    renderLanding()
    await toRest()
    expect(screen.getByRole('heading').textContent).toBe('2곳, 전부 한국에 있어요!')
  })

  it.each<[Lang, string, string]>([
    ['ko', '이 1곳 마이맵에 담기', '1곳, 서울에 있어요!'],
    ['en', 'Keep this 1 on My map', '1 place, in Seoul!'],
  ])('%s — 1곳이면 단수', async (lang, keep, line) => {
    chooseLanguage(lang)
    placesReply = placesN(1)
    renderLanding()
    await toRest()
    expect(buttonWith(keep)).toBeTruthy()
    expect(screen.getByRole('heading').textContent).toBe(line)
  })
})

describe('화면 누르기·건너뛰기', () => {
  it('화면을 누르면 바로 rest 로, landing(id, skip) — 덮개는 그대로', async () => {
    renderLanding()
    await toDrop()
    expect(queryButtonWith('이 3곳 마이맵에 담기')).toBeNull()
    fireEvent.click(document.querySelector('.st-landing')!)
    await advance(0)
    expect(buttonWith('이 3곳 마이맵에 담기')).toBeTruthy()
    expect(hasLanding('skip')).toBe(true)
    expect(closes).toEqual([])
  })

  // §11 — 「건너뛰기」 는 ④ 로 가는 것이 아니라 덮개를 걷는다.
  it.each<[Lang, string]>([
    ['ko', '건너뛰기'],
    ['en', 'Skip'],
  ])('%s — 「%s」 단추는 덮개를 걷는다: onClose(skip) + landing(id, skip)', async (lang, skip) => {
    chooseLanguage(lang)
    renderLanding()
    await toDrop()
    fireEvent.click(screen.getByRole('button', { name: skip }))
    await advance(0)
    expect(closes).toEqual(['skip'])
    expect(hasLanding('skip')).toBe(true)
  })

  it('건너뛰기는 담지 않는다 — 저장은 사용자가 누를 때만', async () => {
    renderLanding()
    await toDrop()
    fireEvent.click(screen.getByRole('button', { name: '건너뛰기' }))
    await advance(30_000)
    expect(addMany).not.toHaveBeenCalled()
  })
})

describe('자동으로 담지 않는다', () => {
  it('네 박자가 끝나도 addMany 를 부르지 않는다', async () => {
    renderLanding()
    await toRest()
    expect(addMany).not.toHaveBeenCalled()
  })
})

describe('prefers-reduced-motion', () => {
  it('처음부터 rest — 해태는 서 있다(움직임을 끈다)', async () => {
    reducedMotion = true
    renderLanding()
    await advance(50)
    await advance(100)
    expect(buttonWith('이 3곳 마이맵에 담기')).toBeTruthy()
    expect(document.querySelector('.st-film')?.hasAttribute('data-instant')).toBe(true)
    expect(document.querySelector('.st-card')).toBeNull()
  })
})

// ---------------------------------------------------------------- §1-3 누른 뒤

describe('담기', () => {
  it('addMany(전부, id, onProgress, landing) 를 부르고 landing(id, save)', async () => {
    renderLanding()
    await toRest()
    fireEvent.click(buttonWith('이 3곳 마이맵에 담기'))
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
    ['ko', '이 3곳 마이맵에 담기', '담는 중… 1/3'],
    ['en', 'Keep these 3 on My map', 'Saving… 1/3'],
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

  it('끝나고 실패가 없으면 덮개 안에서 ⑤ 간직하기, 「나중에」 → onClose(kept) — 「더 담기」 로(§13-1)', async () => {
    renderLanding()
    await toRest()
    fireEvent.click(buttonWith('이 3곳 마이맵에 담기'))
    await advance(0)
    await advance(0)
    expect(closes).toEqual([])
    expect(document.querySelector('.st-film')?.getAttribute('data-beat')).toBe('keep')
    fireEvent.click(buttonWith('나중에'))
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
    fireEvent.click(buttonWith('이 3곳 마이맵에 담기'))
    await advance(0)
    await advance(0)
    expect(closes).toEqual([])
    const again = buttonWith('남은 1곳도 담기')
    fireEvent.click(again)
    await advance(0)
    expect(addMany).toHaveBeenCalledTimes(2)
  })

  it('담긴 것부터 하나씩 「마이맵」 로 날아간다 — 실패한 곳은 날지 않는다(§10-3-9)', async () => {
    let progress: ((done: number, total: number) => void) | undefined
    let finish: (v: { saved: number; already: number; failed: number }) => void = () => {}
    addMany.mockImplementation((_ids, _cid, onProgress) => {
      progress = onProgress
      return new Promise((resolve) => (finish = resolve))
    })
    renderLanding()
    await toRest()
    const before = myMapLandStore.get()
    fireEvent.click(buttonWith('이 3곳 마이맵에 담기'))
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
    fireEvent.click(buttonWith('이 3곳 마이맵에 담기'))
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

  it('③ 의 「마이맵」 단추 → onClose(myMap)', async () => {
    renderLanding()
    await toRest()
    fireEvent.click(screen.getByRole('button', { name: '마이맵' }))
    await advance(0)
    expect(closes).toEqual(['myMap'])
  })
})

// ---------------------------------------------------------------- 사진 받기 — 포스터 → 썸네일, 늦으면 제목 카드

describe('사진 — 받는 순서와 늦은 포스터', () => {
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
  const fail = async (url: string) => {
    await act(async () => {
      for (const img of FakeImage.made.filter((i) => i.src === url)) img.onerror?.()
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

  it('포스터를 먼저, 그다음 카드 앞면(일러스트), 그다음 뒷면 사진 — 10장까지만, 장면 사진은 받지 않는다', async () => {
    placesReply = placesN(12)
    renderLanding()
    await advance(50)
    expect(asked()).toEqual(['https://x/poster-32.jpg'])
    await load('https://x/poster-32.jpg')
    await advance(0)
    const ids = Array.from({ length: 10 }, (_, i) => 101 + i)
    expect(asked().slice(1)).toEqual(ids.map((id) => `/intro/kdh/${id}.webp`))
    for (const id of ids) await load(`/intro/kdh/${id}.webp`)
    await advance(0)
    expect(asked().slice(11)).toEqual(ids.map((id) => `https://x/place-${id}-s.jpg`))
    expect(asked().some((u) => u.includes('scene') || u.includes('place-111') || u.includes('place-112'))).toBe(false)
  })

  it('작품 32 가 아니면 카드 앞면이 곧 사진 — 일러스트를 받지 않는다', async () => {
    placesReply = placesN(3)
    renderLanding(7)
    await advance(50)
    await load('https://x/poster-32.jpg')
    await advance(0)
    expect(asked().slice(1)).toEqual([101, 102, 103].map((id) => `https://x/place-${id}-s.jpg`))
    expect(asked().some((u) => u.includes('/intro/'))).toBe(false)
  })

  it('② 에 포스터가 아직이면 제목을 쓴 보라 카드, 오면 바꿔 끼운다', async () => {
    renderLanding()
    await toPoster()
    expect(document.querySelector('.st-film__poster-card')?.textContent).toContain(TITLE)
    expect(document.querySelector('.st-film__poster-in > img')).toBeNull()
    await load('https://x/poster-32.jpg')
    await advance(0)
    expect(document.querySelector('.st-film__poster-in > img')?.getAttribute('src')).toBe('https://x/poster-32.jpg')
    expect(document.querySelector('.st-film__poster-card')).toBeNull()
  })

  it('③ — 일러스트가 없으면 사진만(뒤집지 않는다), 사진도 아직이면 이름 카드', async () => {
    placesReply = placesN(3)
    renderLanding()
    await advance(50)
    await load('https://x/poster-32.jpg')
    await advance(0)
    // 101 은 그림 파일이 없다(받기 실패), 102 는 그림이 왔다, 103 도 그림이 없다.
    await fail('/intro/kdh/101.webp')
    await load('/intro/kdh/102.webp')
    await fail('/intro/kdh/103.webp')
    await advance(0)
    await load('https://x/place-101-s.jpg')
    await load('https://x/place-102-s.jpg')
    await untilBeat('spill')
    const [c101, c102, c103] = [...document.querySelectorAll('.st-card')]
    expect(c101!.hasAttribute('data-flip')).toBe(false)
    expect(c101!.querySelector('img.st-card__photo')?.getAttribute('src')).toBe('https://x/place-101-s.jpg')
    expect(c102!.hasAttribute('data-flip')).toBe(true)
    expect(c102!.querySelector('.st-card__face--back img.st-card__photo')?.getAttribute('src')).toBe('https://x/place-102-s.jpg')
    expect(c103!.hasAttribute('data-flip')).toBe(false)
    expect(c103!.querySelector('.st-card__namecard')?.textContent).toContain('장소103')
    await untilBeat('rest')
    expect(pouchCount()).toBe(3)
  })
})
