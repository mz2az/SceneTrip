/*
 * 앱의 첫 화면(인트로) 덮개 — 정본: docs/project/plans/web-ad-landing.md §1 · §6 · §7 · §9 · §10 · §11(앞 절보다 뒤 절이 이긴다).
 *
 * 처음 온 사람이 첫 8초에 「장면 → 실제 장소 → 내 지도에 담김」 을 몸으로 느끼게 한다. 영상 없이 사진과 CSS transform·opacity 만.
 * 어느 주소로 들어오든 앱 위에 덮이고(IntroShell), 걷히면 그 자리의 앱이 보인다.
 *
 *   받는 중  보라 바탕 + 해태 + SceneTrip(IntroSplash — index.html 이 JS 보다 먼저 같은 것을 그려 둔다)
 *   ① scene  장면 사진이 화면을 꽉 채운다. 장면 사진이 오면 시작하고, 1.2초 안에 안 오면 포스터로 시작해 오면 바꿔 끼운다(§10-3-4).
 *   ② spread 장면 사진이 카드로 줄며 같은 자리에서 실제 사진으로 뒤집힌다(캡션 「장면」 + 장소 이름). 촬영지 카드가 부채처럼 펼쳐진 뒤
 *            사진이 온 카드부터 한 장씩 지도의 제자리로 날아가 핀이 된다(안 온 카드는 뒤로). 아래 「내 지도」 접시의 수가 오른다.
 *   ③ rest   핀이 다 꽂힌 지도 + 「{N} real places from {작품}」 + 담기 + 「먼저 둘러볼게요」.
 *
 * 화면 아무 곳이나 누르면(단추 제외) → ③. 「건너뛰기」·「먼저 둘러볼게요」 → 덮개가 걷힌다. 움직임 줄이기면 처음부터 ③.
 * 자동으로 담지 않는다. 담기를 끝내면 「더 담기」(/add). 담는 중에 덮개를 걷으면 담기는 끝까지 하되 /add 로 보내지 않는다(§10-3-7).
 * 처음 온 사람이 아니면(담은 것·코스가 있다) 아무것도 그리지 않고 곧바로 걷힌다(§11).
 *
 * 느린 폰(§10): 받는 것은 작품·촬영지·장바구니·코스 넷을 함께. 장면 사진은 촬영지 목록의 `sceneImageThumbnailUrl`(계약 1.32.0)을
 * 쓰고, 없으면(옛 서버) 앞 세 곳만 `GET /places/{id}` 로 받되 기다리지 않는다. 사진은 ① 장면 → ② 첫 카드 3장 → 나머지 순으로 받는다.
 * 지도 SDK 는 ③ 이 그려지거나 사진을 다 받고 2초 뒤 한가할 때(`releaseDeferred`) 받는다 — 그 전에는 pinLayout 자리에 핀을 꽂는다.
 */
import { type CSSProperties, type JSX, Suspense, lazy, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { track, webEvent } from '@/analytics'
import { api, installHeader, ok } from '@/api/client'
import type { components } from '@/api/generated/schema'
import { afterDeferred, releaseDeferred } from '@/app/deferred'
import { Icon } from '@/design/Icon'
import { Spinner, Txt } from '@/design/primitives'
import { MyMapButton, flyToMyMap } from '@/features/keep/flyToMyMap'
import { color } from '@/features/proto/protoTokens'
import { Pressable } from '@/features/route/ui/Press'
import { CAMERA_TOP_INSET } from '@/features/search/map/mapRules'
import { say, useLang } from '@/i18n'
import type { MapHandle } from '@/map'
import { project } from '@/map/mercator'
import { smallImage } from '@/shared/smallImage'
import { spots } from '@/shared/spots'
import { cartActions, cartStore, useCart } from '@/state/cart'
import { INTRO_HAETAE_SRC as INTRO_MARK_SRC, IntroSplash } from './IntroSplash'
import type { IntroClose } from './introClose'
import { FAN_MS, type LandingTimeline, beatAt, landedCount, landingTimeline, pinLayout, placeLine, sceneCaption } from './landingScript'
import './landing.css'

const MapView = lazy(() => import('@/features/search/map/MapView').then((m) => ({ default: m.MapView })))

type ContentDetail = components['schemas']['ContentDetail']
type PlaceDetail = components['schemas']['PlaceDetail']
/** 계약 1.32.0 의 새 칸(§10-2) — 옛 서버에는 없다. 생성 타입에 기대지 않고 좁게 읽는다. */
type PlaceSummary = components['schemas']['PlaceSummary'] & { sceneImageThumbnailUrl?: string | null; nameKo?: string | null }
type SceneLike = { contentId?: number; sceneImageUrl?: string | null; sceneImageUrls?: string[] | null; sceneImageThumbnailUrl?: string | null; sceneImageThumbnailUrls?: string[] | null; sceneDescription?: string | null }

/** 장면 사진이 이만큼(ms) 안에 안 오면 포스터로 ① 을 시작한다(§10-3-4). */
export const SCENE_WAIT_MS = 1200
/** 장면 사진을 따로 받는 앞 몇 곳(옛 서버). */
const SCENE_FETCH = 3
/** ② 에서 먼저 받는 카드 수(§10-3-3). */
const FIRST_CARDS = 3
/** 부채로 펼쳐 보이는 카드 수(나머지는 영웅 카드 밑에 겹쳐 있다 날아간다). */
const FAN_MAX = 6
/** 촬영지 받기 한 번의 끝. */
const PLACES_LIMIT = 100
/** 담는 동안 「내 지도」 로 날려 보내는 조각 수. */
const FLY_PIECES = 6
/** 첫 사진들을 다 받은 뒤 미뤄 둔 일(지도·분석·앱 덩어리)을 풀기까지(ms) — §10-3-2 의 「2초 뒤 한가할 때」. */
const RELEASE_AFTER_PHOTOS_MS = 2000

const HERO = { w: 176, h: 224 }
const CARD = { w: 112, h: 140 }
const PIN = { w: 42, h: 52 }

interface Ready {
  content: ContentDetail
  places: PlaceSummary[]
}

interface Scene {
  url: string | null
  /** 작은 판(긴 변 720px)인가 — 아니면 원본(옛 서버, 장당 2~3MB). */
  small: boolean
  description: string | null
}

type SaveState = { kind: 'idle' } | { kind: 'saving'; done: number; total: number } | { kind: 'failed'; failed: number }

const reducedMotion = (): boolean =>
  typeof window !== 'undefined' && window.matchMedia?.('(prefers-reduced-motion: reduce)').matches === true

/** 장면 하나 — 이 작품의 장면을 먼저. 사진은 작은 판(§10-3-3)이 있으면 그것, 없으면 원본. */
export function sceneOf(detail: Pick<PlaceDetail, 'sceneDescription'> & { scenes?: SceneLike[] | null }, contentId: number): Scene {
  const scenes = detail.scenes ?? []
  const own = scenes.find((s) => s.contentId === contentId) ?? scenes[0]
  const small = own?.sceneImageThumbnailUrl ?? own?.sceneImageThumbnailUrls?.[0] ?? null
  const url = small ?? own?.sceneImageUrl ?? own?.sceneImageUrls?.[0] ?? null
  return { url, small: small !== null, description: own?.sceneDescription ?? detail.sceneDescription ?? null }
}

const cardPhoto = (p: PlaceSummary) => smallImage(p.imageThumbnailUrl, p.imageUrl)
/** 사진을 미리 받을 수 있는 환경인가(jsdom 은 사진을 받지 않는다 — 그때는 주소를 그대로 그린다). */
const canPreload = (): boolean => typeof Image !== 'undefined' && typeof navigator !== 'undefined' && !/jsdom/i.test(navigator.userAgent)

export function LandingCover({ contentId, startedAt, onRest, onClose }: {
  contentId: number
  /** 첫 화면을 시작한 때(`performance.now()`) — 앱이 뜬 때. 장면 사진을 기다리는 1.2초는 여기서 잰다. 없으면 덮개가 뜬 때. */
  startedAt?: number
  onRest?: () => void
  onClose: (how: IntroClose) => void
}): JSX.Element {
  const lang = useLang()
  const cart = useCart()
  const [ready, setReady] = useState<Ready | null>(null)
  /** 장면 — 촬영지 id → 장면. 목록에 실려 오거나(새 서버) 따로 받아 채운다(옛 서버). */
  const [scenes, setScenes] = useState<Record<number, Scene>>({})
  /** 따로 받는 장면이 다 왔다(또는 받을 것이 없다). */
  const [scenesSettled, setScenesSettled] = useState(false)
  /** 세 박자의 시계 — null 이면 아직 시작하지 않았다. */
  const [elapsed, setElapsed] = useState<number | null>(null)
  const [posterTime, setPosterTime] = useState(false)
  const [save, setSave] = useState<SaveState>({ kind: 'idle' })
  const [mapWanted, setMapWanted] = useState(false)
  const reduced = useRef(reducedMotion())
  const timers = useRef<ReturnType<typeof setTimeout>[]>([])
  const sent = useRef<Set<string>>(new Set())
  const alive = useRef(true)
  const closeRef = useRef(onClose)
  useEffect(() => {
    closeRef.current = onClose
  }, [onClose])

  useEffect(() => {
    alive.current = true
    return () => {
      alive.current = false
    }
  }, [])

  const once = useCallback(
    (action: 'view' | 'rest') => {
      if (sent.current.has(action)) return
      sent.current.add(action)
      track(webEvent.landing(contentId, action))
    },
    [contentId],
  )

  // 지도는 미뤄 둔 일이 풀릴 때(§10-3-2).
  useEffect(() => {
    afterDeferred(() => alive.current && setMapWanted(true))
  }, [])

  // ---- 받기 — 작품·촬영지·장바구니·코스를 함께. 장면은 목록에 없으면 앞 세 곳만 따로, 기다리지 않는다.
  useEffect(() => {
    let live = true
    const cartReady = cartStore.get().loadState === 'loaded' ? Promise.resolve() : cartActions.refresh()
    const courses = ok(api.GET('/courses', { params: { header: installHeader() } }))
      .then((c) => c.items.length)
      .catch(() => 0)
    const content = ok(api.GET('/contents/{contentId}', { params: { path: { contentId } } }))
    const list = ok(api.GET('/contents/{contentId}/places', { params: { path: { contentId }, query: { limit: PLACES_LIMIT } } }))
    void (async () => {
      // 처음 온 사람인가 — 담은 것(촬영지·편의시설)이나 코스가 하나라도 있으면 곧바로 걷힌다(§11).
      const [, courseCount] = await Promise.all([cartReady.catch(() => {}), courses])
      if (!live) return
      const c0 = cartStore.get()
      if (c0.placeIds.size > 0 || c0.poiIds.size > 0 || courseCount > 0) {
        closeRef.current('notNew')
        return
      }
      let detail: ContentDetail
      let places: PlaceSummary[]
      try {
        const [c, l] = await Promise.all([content, list])
        detail = c
        places = (l.items as PlaceSummary[]).filter((p) => Number.isFinite(p.latitude) && Number.isFinite(p.longitude))
      } catch {
        if (live) closeRef.current('failed')
        return
      }
      if (!live) return
      if (detail.delisted === true || places.length === 0) {
        closeRef.current('failed')
        return
      }
      const listed: Record<number, Scene> = {}
      for (const p of places) {
        if (p.sceneImageThumbnailUrl) listed[p.id] = { url: p.sceneImageThumbnailUrl, small: true, description: p.sceneDescription ?? null }
      }
      setScenes(listed)
      setReady({ content: detail, places })
      if (Object.keys(listed).length > 0) {
        setScenesSettled(true)
        return
      }
      // 옛 서버 — 앞 세 곳의 장면을 따로. 오는 대로 채운다.
      const front = places.slice(0, SCENE_FETCH)
      await Promise.allSettled(
        front.map((p) =>
          ok(api.GET('/places/{placeId}', { params: { path: { placeId: p.id } } })).then((d) => {
            if (live) setScenes((s) => ({ ...s, [p.id]: sceneOf(d, contentId) }))
          }),
        ),
      )
      if (live) setScenesSettled(true)
    })()
    void content.catch(() => {})
    void list.catch(() => {})
    return () => {
      live = false
    }
  }, [contentId])

  const n = ready?.places.length ?? 0
  const timeline: LandingTimeline = useMemo(() => landingTimeline(n), [n])

  // 영웅 카드 — 앞 세 곳 가운데 장면이 있는 첫 곳(없으면 첫 곳). 시계가 돌기 시작하면 고정한다.
  const [frozenHero, setFrozenHero] = useState<number | null>(null)
  const liveHero = useMemo(() => {
    if (!ready) return 0
    const i = ready.places.slice(0, SCENE_FETCH).findIndex((p) => scenes[p.id]?.url)
    return i < 0 ? 0 : i
  }, [ready, scenes])
  const heroIndex = frozenHero ?? liveHero
  const heroPlace = ready?.places[heroIndex] ?? null
  const heroScene = heroPlace ? (scenes[heroPlace.id] ?? null) : null
  const heroSceneUrl = heroScene?.url ?? null
  const poster = ready?.content.posterUrl ?? null

  // ---- 사진 받기 순서(§10-3-3): ① 장면(작은 판이 없으면 포스터) → ② 첫 카드 3장 → 나머지.
  // 옛 서버의 원본 장면(장당 2~3MB)은 맨 끝 — 느린 망에서 카드 사진의 대역폭을 먹지 않게(그동안 ① 은 포스터).
  const heroSceneSmall = heroScene?.small === true
  const preloadGroups = useMemo(() => {
    if (!ready) return []
    const first = heroSceneUrl !== null && heroSceneSmall ? heroSceneUrl : poster
    const cards = ready.places.filter((_, i) => i !== heroIndex).map(cardPhoto).filter((u): u is string => !!u)
    const heroPhoto = heroPlace ? cardPhoto(heroPlace) : null
    const late = heroSceneUrl !== null && !heroSceneSmall ? [heroSceneUrl] : []
    return [first ? [first] : [], [...cards.slice(0, FIRST_CARDS), ...(heroPhoto ? [heroPhoto] : [])], cards.slice(FIRST_CARDS), late].filter((g) => g.length > 0)
  }, [ready, heroSceneUrl, heroSceneSmall, poster, heroIndex, heroPlace])
  const [preloading] = useState(canPreload)
  /** 미리 받은 사진만 그린다 — 화면에 놓인 <img> 가 받기 순서를 흐트러뜨리지 않게(받은 것은 캐시에서 곧바로 뜬다). */
  const shown = (url: string | null): string | null => (url !== null && (loaded.has(url) || !preloading) ? url : null)
  const loaded = useOrderedPreload(preloadGroups)

  // ---- 시계
  const startClock = useCallback(
    (from: number) => {
      timers.current.forEach(clearTimeout)
      timers.current = []
      setElapsed(from)
      if (from >= timeline.total) return
      // 그림이 바뀌는 때만 시계를 넘긴다 — ② 의 시작, 카드가 하나씩 날아가는 때, ③.
      const marks = new Set<number>([timeline.sceneMs, timeline.total])
      for (let j = 0; j <= n; j++) marks.add(timeline.sceneMs + FAN_MS + j * timeline.perCardMs)
      for (const at of [...marks].sort((a, b) => a - b)) {
        if (at <= from) continue
        timers.current.push(setTimeout(() => setElapsed(at), at - from))
      }
    },
    [n, timeline],
  )
  useEffect(() => () => timers.current.forEach(clearTimeout), [])

  // 포스터로 시작하는 때 — 첫 화면을 시작하고 1.2초(받기가 그보다 늦으면 받는 대로).
  const startedRef = useRef(startedAt)
  useEffect(() => {
    const since = startedRef.current === undefined ? 0 : Math.max(0, performance.now() - startedRef.current)
    const timer = setTimeout(() => setPosterTime(true), Math.max(0, SCENE_WAIT_MS - since))
    return () => clearTimeout(timer)
  }, [])

  const sceneLoaded = heroSceneUrl !== null && loaded.has(heroSceneUrl)
  useEffect(() => {
    if (!ready || elapsed !== null) return
    if (reduced.current) {
      once('view')
      setFrozenHero(liveHero)
      startClock(timeline.total)
      return
    }
    // 장면 사진이 왔거나, 1.2초가 지났거나, 작은 장면 사진이 없다고 판명났고 포스터가 왔다(또는 포스터도 없다).
    const noSmallScene = scenesSettled && !heroSceneSmall
    if (sceneLoaded || posterTime || (noSmallScene && (poster === null || loaded.has(poster) || !preloading))) {
      once('view')
      setFrozenHero(liveHero)
      startClock(0)
    }
  }, [ready, elapsed, sceneLoaded, posterTime, scenesSettled, heroSceneSmall, poster, loaded, preloading, liveHero, once, startClock, timeline.total])

  const beat = elapsed === null ? null : beatAt(elapsed, timeline)
  const landed = elapsed === null ? 0 : landedCount(elapsed, timeline, n)
  const rest = beat === 'rest'
  useEffect(() => {
    if (!rest) return
    once('rest')
    onRest?.()
  }, [rest, once, onRest])

  // 첫 사진들(장면 + 첫 카드 3장)이 다 오면 2초 뒤 한가할 때 미뤄 둔 일을 푼다 — 빠른 환경에서는 ③ 전에 지도가 선다.
  const firstPhotos = preloadGroups.slice(0, 2).flat().filter((u) => u !== heroSceneUrl || heroSceneSmall)
  const firstPhotosLoaded = firstPhotos.length > 0 && firstPhotos.every((u) => loaded.has(u))
  useEffect(() => {
    if (!firstPhotosLoaded) return
    const timer = setTimeout(() => {
      const idle = (window as { requestIdleCallback?: (cb: () => void, o?: { timeout: number }) => number }).requestIdleCallback
      if (idle) idle(() => releaseDeferred(), { timeout: 1000 })
      else releaseDeferred()
    }, RELEASE_AFTER_PHOTOS_MS)
    return () => clearTimeout(timer)
  }, [firstPhotosLoaded])

  // ---- 날아가는 차례(§10-3-4) — 사진이 온 카드부터. 영웅 카드는 뒤집기를 다 보인 뒤 맨 끝.
  const [order, setOrder] = useState<number[]>([])
  useEffect(() => {
    if (!ready) return
    const want = rest ? n : landed
    if (order.length >= want) return
    const next = [...order]
    const others = ready.places.map((_, i) => i).filter((i) => i !== heroIndex)
    while (next.length < want) {
      const left = others.filter((i) => !next.includes(i))
      if (left.length === 0) {
        if (!next.includes(heroIndex)) next.push(heroIndex)
        break
      }
      const photoReady = left.find((i) => {
        const u = cardPhoto(ready.places[i]!)
        return u === null || loaded.has(u)
      })
      next.push(photoReady ?? left[0]!)
    }
    setOrder(next)
  }, [ready, landed, rest, n, order, heroIndex, loaded])
  const flightOf = useMemo(() => new Map(order.map((placeIndex, slot) => [placeIndex, slot])), [order])

  /** 「건너뛰기」 — 덮개를 걷는다(§11). */
  const skipAll = () => {
    track(webEvent.landing(contentId, 'skip'))
    closeRef.current('skip')
  }
  /** 화면을 누르면 ③ 으로. */
  const jumpToRest = () => {
    if (!ready || beat === null || beat === 'rest') return
    track(webEvent.landing(contentId, 'skip'))
    startClock(timeline.total)
  }

  // ---- 무대 크기와 핀 자리
  const stage = useRef<HTMLDivElement>(null)
  const [size, setSize] = useState(() => ({ w: typeof window === 'undefined' ? 390 : window.innerWidth, h: typeof window === 'undefined' ? 800 : window.innerHeight }))
  const hasStage = ready !== null && beat !== null
  useEffect(() => {
    const el = stage.current
    if (!el) return
    const read = () => {
      const r = el.getBoundingClientRect()
      if (r.width > 0 && r.height > 0) setSize({ w: r.width, h: r.height })
    }
    read()
    if (typeof ResizeObserver === 'undefined') return
    const observer = new ResizeObserver(read)
    observer.observe(el)
    return () => observer.disconnect()
  }, [hasStage])
  const region = useMemo(() => pinRegion(size.w, size.h), [size])
  const fallbackPins = useMemo(
    () =>
      ready
        ? pinLayout(ready.places, { width: region.right - region.left, height: region.bottom - region.top, pad: 6 }).map((p) => ({ x: p.x + region.left, y: p.y + region.top }))
        : [],
    [ready, region],
  )
  const [map, setMap] = useState<MapHandle | null>(null)
  const mapPins = useMapPins(map, ready?.places ?? null, region, size)
  const pins = mapPins ?? fallbackPins

  // ---- 담기
  const keepAll = async (from: Element | null, onlyRemaining: boolean) => {
    if (!ready || save.kind === 'saving') return
    const all = ready.places.map((p) => p.id)
    const remaining = all.filter((id) => !cartStore.get().placeIds.has(id))
    const ids = onlyRemaining && remaining.length > 0 ? remaining : all
    track(webEvent.landing(contentId, 'save'))
    setSave({ kind: 'saving', done: 0, total: ids.length })
    // 담긴 것부터 하나씩 「내 지도」 로 날린다(§10-3-9) — 실패한 것은 날리지 않는다.
    const before = new Set(cartStore.get().placeIds)
    const flown = new Set<number>()
    const flights: Promise<void>[] = []
    const flyNew = () => {
      const now = cartStore.get().placeIds
      ready.places.forEach((p, i) => {
        if (flown.size >= FLY_PIECES || flown.has(p.id) || before.has(p.id) || !now.has(p.id)) return
        flown.add(p.id)
        flights.push(flyPin(p, pins[i], from))
      })
    }
    const result = await cartActions.addMany(
      ids,
      contentId,
      (done, total) => {
        if (alive.current) setSave({ kind: 'saving', done, total })
        flyNew()
      },
      'landing',
    )
    // 담는 중에 덮개가 걷혔으면(둘러보기·건너뛰기) 끌고 가지 않는다 — 알림(CartToast)만 남는다(§10-3-7).
    if (!alive.current) return
    if (result.failed === 0) {
      // 날던 것이 다 내려앉은 뒤 「더 담기」 로(그리는 수단이 없으면 곧바로).
      await Promise.all(flights)
      if (alive.current) closeRef.current('kept')
      return
    }
    setSave({ kind: 'failed', failed: result.failed })
  }

  const browse = () => {
    track(webEvent.landing(contentId, 'browse'))
    closeRef.current('browse')
  }

  // ---------------------------------------------------------------- 그리기
  if (!ready || beat === null) return <IntroSplash />

  const { content, places } = ready
  const title = content.title
  const heroPhoto = heroPlace ? shown(cardPhoto(heroPlace)) : null
  const captionText = sceneCaption(heroScene?.description ?? heroPlace?.sceneDescription ?? null, lang) ?? say(`${title}의 장면`, `A scene from ${title}`)
  const captionPlace = heroPlace ? placeLine(heroPlace.name, heroPlace.nameKo, lang) : null
  const scene = beat === 'scene'
  const instant = reduced.current
  // ① 의 그림 — 장면 사진이 왔으면 그것, 아니면 포스터(장면 사진이 오면 바꿔 끼운다).
  const sceneShown = sceneLoaded ? heroSceneUrl : (shown(poster) ?? null)

  const fanSlots = Math.min(FAN_MAX, places.length - 1)
  const others = places.map((_, i) => i).filter((i) => i !== heroIndex)
  const fanIndexOf = new Map(others.map((placeIndex, k) => [placeIndex, k]))
  const heroCenter = { x: size.w / 2, y: Math.min(size.h - 150, region.bottom + 40) }
  const flightMs = Math.max(260, timeline.perCardMs + 140)

  return (
    <div
      ref={stage}
      className="st-landing"
      onClick={(e) => {
        if ((e.target as HTMLElement).closest('button')) return
        jumpToRest()
      }}
      style={{ position: 'absolute', inset: 0, overflow: 'hidden', background: '#1c1530', color: '#fff' }}
    >
      {/* 지도 — 미뤄 둔 일이 풀리면 밑에서 띄운다. 그 전에는 민무늬 바탕. 누름은 받지 않는다(무대가 받는다). */}
      <div aria-hidden style={{ position: 'absolute', left: 0, right: 0, bottom: 0, top: -CAMERA_TOP_INSET, pointerEvents: 'none', background: color.brandFill }}>
        {mapWanted && (
          <Suspense fallback={null}>
            <MapView
              pins={[]}
              numbered={false}
              koreaToken={0}
              locateToken={0}
              fitToken={0}
              focusToken={0}
              focus={null}
              panToken={0}
              pan={null}
              sheetHeight={0}
              onTapPin={() => {}}
              onLocateFailure={() => {}}
              onReady={() => {}}
              onMap={setMap}
            />
          </Suspense>
        )}
      </div>
      {/* 지도 위 옅은 막 — 머리줄 글자가 읽히게, ① 에서는 장면 사진이 덮는다. */}
      <div aria-hidden className="st-landing__veil" />

      {/* 핀 — 카드가 꽂힐 때 하나씩 떨어진다. */}
      {places.map((p, i) => {
        const at = pins[i]
        const slot = flightOf.get(i)
        if (!at || (!rest && slot === undefined)) return null
        const photo = rest ? cardPhoto(p) : shown(cardPhoto(p))
        return (
          <div
            key={p.id}
            className={instant ? 'st-landing__pin' : 'st-landing__pin st-landing__pin--drop'}
            style={{ transform: `translate(${at.x - PIN.w / 2}px, ${at.y - PIN.h}px)`, animationDelay: rest && !instant && slot === undefined ? `${Math.min(i, 12) * 30}ms` : undefined }}
            aria-hidden
          >
            <span className="st-landing__pin-head">{photo && <img src={photo} alt="" referrerPolicy="no-referrer" draggable={false} />}</span>
          </div>
        )
      })}

      {/* 촬영지 카드 — ② 에서 부채로 펼쳐졌다가 제자리로 날아간다. */}
      {!rest &&
        places.map((p, i) => {
          if (i === heroIndex) return null
          const k = fanIndexOf.get(i) ?? 0
          const visibleInFan = k < fanSlots
          // 부채는 바깥부터 번갈아 자리를 잡는다. 부채에 못 든 카드는 영웅 카드 뒤에 숨어 있다 날아 나온다.
          const fanIndex = visibleInFan ? fanOrder(fanSlots)[k]! : Math.floor((fanSlots - 1) / 2)
          const flying = flightOf.has(i)
          const home = { x: heroCenter.x, y: heroCenter.y + 8 }
          const spreadPos = fan(fanIndex, fanSlots, size.w)
          const target = pins[i] ?? home
          const photo = shown(cardPhoto(p))
          let transform: string
          let opacity: number
          if (scene) {
            transform = `translate(0px, 40px) rotate(0deg) scale(0.6)`
            opacity = 0
          } else if (flying) {
            transform = `translate(${target.x - home.x}px, ${target.y - PIN.h / 2 - home.y}px) rotate(0deg) scale(0.3)`
            opacity = 0
          } else if (visibleInFan) {
            transform = `translate(${spreadPos.dx}px, ${spreadPos.dy}px) rotate(${spreadPos.rot}deg) scale(1)`
            opacity = 1
          } else {
            transform = 'translate(0px, 0px) rotate(0deg) scale(0.9)'
            opacity = 1
          }
          return (
            <div
              key={p.id}
              aria-hidden
              className="st-landing__card"
              style={{
                left: home.x - CARD.w / 2,
                top: home.y - CARD.h / 2,
                width: CARD.w,
                height: CARD.h,
                transform,
                opacity,
                zIndex: visibleInFan ? 12 + Math.round((fanSlots - Math.abs(fanIndex - (fanSlots - 1) / 2)) * 2) : 10,
                transition: flying
                  ? `transform ${flightMs}ms cubic-bezier(0.5, 0, 0.3, 1), opacity ${flightMs}ms ease-in`
                  : `transform 520ms cubic-bezier(0.22, 1.2, 0.36, 1) ${300 + fanIndex * 40}ms, opacity 300ms ease ${300 + fanIndex * 40}ms`,
              }}
            >
              {photo && <img src={photo} alt="" referrerPolicy="no-referrer" draggable={false} />}
              <span className="st-landing__card-name">{p.name}</span>
            </div>
          )
        })}

      {/* 영웅 카드 — ① 에서는 화면을 꽉 채운 장면 사진, ② 에서 카드로 줄어 같은 자리에서 실제 사진으로 뒤집힌다. */}
      {!rest && heroPlace && (
        <Hero
          scene={scene}
          flying={flightOf.has(heroIndex)}
          center={heroCenter}
          target={pins[heroIndex] ?? heroCenter}
          stage={size}
          sceneUrl={sceneShown}
          dim={!sceneLoaded}
          photoUrl={heroPhoto}
          flightMs={flightMs}
        />
      )}

      {/* ① 의 글 — 아래 큰 흰 글씨. ② 로 넘어가면 사라진다. */}
      {!rest && (
        <div className="st-landing__scene-text" style={{ opacity: scene ? 1 : 0, transform: scene ? 'translateY(0)' : 'translateY(16px)' }}>
          <Txt as="h1" size={34} weight="heavy" style={{ margin: 0, color: '#fff', lineHeight: 1.08, letterSpacing: -0.4, textShadow: '0 2px 14px rgba(0,0,0,0.45)' }}>
            {title}
          </Txt>
          <Txt size={18} weight="semibold" style={{ color: '#fff', opacity: 0.95, textShadow: '0 1px 8px rgba(0,0,0,0.5)' }}>
            {say('이 장면, 진짜 있는 곳이에요', 'This scene is a real place')}
          </Txt>
        </div>
      )}

      {/* ② 의 캡션 — 장면(상투구를 뗀 앞 60자) + 그 아래 장소 이름(영어 화면이면 한글 병기) */}
      {!rest && !scene && (
        <div className="st-landing__caption" style={{ top: heroCenter.y + HERO.h / 2 + 14 }}>
          <span>
            <Txt size={14} weight="bold" lines={2} align="center" style={{ color: '#1c1530' }}>{captionText}</Txt>
            {captionPlace && (
              <Txt size={12} weight="semibold" lines={1} align="center" style={{ color: color.primaryText, marginTop: 2 }}>{`→ ${captionPlace}`}</Txt>
            )}
          </span>
        </div>
      )}

      {/* ② 의 「내 지도」 접시 — 꽂힌 수가 오른다. */}
      {!rest && !scene && (
        <div className="st-landing__plate-row">
          <div className="st-landing__plate" aria-live="polite">
            <Icon name="map" size={18} weight="bold" color="#fff" />
            <Txt size={15} weight="bold" style={{ color: '#fff' }}>{say('내 지도', 'My map')}</Txt>
            <span key={landed} className="st-landing__plate-count">{landed}</span>
          </div>
        </div>
      )}

      {/* 머리줄 — 해태 + SceneTrip, 오른쪽은 ①·② 에서 건너뛰기, ③ 에서 「내 지도」 단추 */}
      <div className="st-landing__head">
        <div style={{ display: 'flex', alignItems: 'center', gap: 6, minWidth: 0 }}>
          <IntroMark />
          <Txt size={15} weight="heavy" style={{ color: scene ? '#fff' : color.primaryText, textShadow: scene ? '0 1px 6px rgba(0,0,0,0.4)' : undefined }}>SceneTrip</Txt>
        </div>
        {rest ? (
          <MyMapButton onPress={() => closeRef.current('myMap')} />
        ) : (
          <Pressable plain onClick={skipAll} className="st-landing__skip" style={{ background: scene ? 'rgba(0,0,0,0.45)' : 'rgba(255,255,255,0.96)' }}>
            <Txt size={14} weight="bold" style={{ color: scene ? '#fff' : '#1c1530' }}>{say('건너뛰기', 'Skip')}</Txt>
          </Pressable>
        )}
      </div>

      {/* ③ — 핀이 다 꽂힌 지도 위 아래 판 */}
      {rest && (
        <RestPanel
          title={title}
          places={places}
          scenes={scenes}
          save={save}
          instant={instant}
          lang={lang}
          onKeep={(el) => void keepAll(el, save.kind === 'failed')}
          onBrowse={browse}
          cartSaved={cart.placeIds}
          sceneShown={(sc) => (sc?.url && (sc.small || shown(sc.url)) ? sc.url : null)}
        />
      )}
    </div>
  )
}

// ---------------------------------------------------------------- 부품

/** 머리줄의 작은 해태 — 첫 화면과 같은 그림(작은 WebP, 이미 받아 둔 것). */
function IntroMark() {
  return <img src={INTRO_MARK_SRC} alt="" width={26} height={22} style={{ objectFit: 'contain' }} draggable={false} />
}

/**
 * 영웅 카드 — 장면(앞) ↔ 실제 사진(뒤). ① 에서는 화면을 덮는 크기로 키워 둔다(transform 만).
 * 비싼 효과는 쓰지 않는다(§10-3-10): 흐림(filter)·뒤 흐림(backdrop-filter)·모서리 둥글기 전환 대신, 모서리는 늘 둥글고
 * ① 의 확대가 화면 밖으로 밀어내 보이지 않는다. 포스터로 시작하면 어둡게 덮어 둔다(dim).
 */
function Hero({ scene, flying, center, target, stage, sceneUrl, dim, photoUrl, flightMs }: {
  scene: boolean
  flying: boolean
  center: { x: number; y: number }
  target: { x: number; y: number }
  stage: { w: number; h: number }
  sceneUrl: string | null
  dim: boolean
  photoUrl: string | null
  flightMs: number
}) {
  // 화면을 덮는 배율 — 카드의 가운데를 화면 가운데로 옮기고 키운다(모서리 18px 이 화면 밖으로 나가게 조금 더).
  const cover = Math.max(stage.w / HERO.w, stage.h / HERO.h) * 1.12
  const toScreen = `translate(${stage.w / 2 - center.x}px, ${stage.h / 2 - center.y}px) scale(${cover})`
  const transform = scene ? toScreen : flying ? `translate(${target.x - center.x}px, ${target.y - PIN.h / 2 - center.y}px) scale(0.24)` : 'translate(0px, 0px) scale(1)'
  const style: CSSProperties = {
    left: center.x - HERO.w / 2,
    top: center.y - HERO.h / 2,
    width: HERO.w,
    height: HERO.h,
    transform,
    opacity: flying ? 0 : 1,
    transition: flying
      ? `transform ${flightMs}ms cubic-bezier(0.5, 0, 0.3, 1), opacity ${flightMs}ms ease-in`
      : 'transform 640ms cubic-bezier(0.32, 0.72, 0, 1)',
  }
  return (
    <div className="st-landing__hero" style={style} aria-hidden>
      <div className={scene ? 'st-landing__flip' : 'st-landing__flip st-landing__flip--turned'}>
        <div className="st-landing__face">
          {sceneUrl && <img key={sceneUrl} src={sceneUrl} alt="" referrerPolicy="no-referrer" draggable={false} fetchPriority="high" className={dim ? 'st-landing__dim' : 'st-landing__kenburns'} />}
          <span className="st-landing__shade" style={{ opacity: scene ? 1 : 0 }} />
        </div>
        <div className="st-landing__face st-landing__face--back">
          {photoUrl ? <img src={photoUrl} alt="" referrerPolicy="no-referrer" draggable={false} /> : sceneUrl && <img src={sceneUrl} alt="" referrerPolicy="no-referrer" draggable={false} />}
          <span className="st-landing__real">{say('실제 장소', 'Real place')}</span>
        </div>
      </div>
    </div>
  )
}

function RestPanel({ title, places, scenes, save, instant, lang, cartSaved, sceneShown, onKeep, onBrowse }: {
  title: string
  places: readonly PlaceSummary[]
  scenes: Record<number, Scene>
  save: SaveState
  instant: boolean
  lang: 'ko' | 'en'
  cartSaved: ReadonlySet<number>
  /** 모서리에 그릴 장면 주소 — 작은 판이거나 이미 받은 원본만(옛 서버의 원본 장면은 장당 수 MB). */
  sceneShown: (scene: Scene | undefined) => string | null
  onKeep: (from: Element | null) => void
  onBrowse: () => void
}) {
  const n = places.length
  const keepLabel =
    save.kind === 'saving'
      ? say(`담는 중… ${save.done}/${save.total}`, `Saving… ${save.done}/${save.total}`)
      : save.kind === 'failed'
        ? say(`남은 ${save.failed}곳도 담기`, `Save the other ${spots(save.failed)}`)
        : n === 1
          ? say('이 1곳 내 지도에 담기', 'Keep this 1 on my map')
          : say(`이 ${n}곳 내 지도에 담기`, `Keep these ${n} on my map`)
  return (
    <div className={instant ? 'st-landing__panel' : 'st-landing__panel st-landing__panel--in'} onClick={(e) => e.stopPropagation()}>
      <Txt as="h1" size={23} weight="heavy" lines={2} style={{ margin: 0, color: '#1c1530', letterSpacing: -0.3 }}>
        {say(`${title}의 실제 장소 ${n}곳`, `${n} real places from ${title}`)}
      </Txt>
      <div className="scroll-x st-landing__strip">
        {places.slice(0, 12).map((p) => {
          const photo = cardPhoto(p)
          // 72px 모서리도 작은 판(§10-3-3). 옛 서버의 원본은 이미 받은 것만 — ③ 에서 수 MB 를 새로 받지 않는다.
          const sceneUrl = sceneShown(scenes[p.id])
          const kept = cartSaved.has(p.id)
          const ko = lang === 'en' && p.nameKo && p.nameKo.trim() !== p.name.trim() ? p.nameKo.trim() : null
          return (
            <div key={p.id} className="st-landing__chip">
              <span className="st-landing__chip-photo">
                {photo && <img src={photo} alt="" loading="lazy" referrerPolicy="no-referrer" draggable={false} />}
                {sceneUrl && <img className="st-landing__chip-scene" src={sceneUrl} alt="" loading="lazy" referrerPolicy="no-referrer" draggable={false} />}
                {kept && <span className="st-landing__chip-kept"><Icon name="checkmark" size={12} weight="bold" color="#fff" /></span>}
              </span>
              <span className="st-landing__chip-name">{p.name}</span>
              {ko && <span className="st-landing__chip-ko">{ko}</span>}
            </div>
          )
        })}
      </div>
      {save.kind === 'failed' && (
        <Txt size={13} weight="semibold" color="red">{say(`${save.failed}곳은 담지 못했어요`, `Couldn't save ${spots(save.failed)}`)}</Txt>
      )}
      <Pressable
        plain
        className="proto-press st-landing__keep"
        disabled={save.kind === 'saving'}
        onClick={(e) => onKeep(e.currentTarget)}
      >
        <span style={{ display: 'inline-flex', alignItems: 'center', gap: 8, justifyContent: 'center' }}>
          {save.kind === 'saving' ? <Spinner size={16} /> : <Icon name="map" size={18} weight="bold" color="#fff" />}
          <Txt size={17} weight="bold" style={{ color: '#fff' }}>{keepLabel}</Txt>
        </span>
      </Pressable>
      <Pressable plain onClick={onBrowse} className="st-landing__browse">
        <Txt size={15} weight="semibold" color="secondary">{say('먼저 둘러볼게요', 'Just looking')}</Txt>
      </Pressable>
    </div>
  )
}

// ---------------------------------------------------------------- 사진 받기

/**
 * 묶음 차례대로 사진을 미리 받는다(§10-3-3) — 앞 묶음이 다 오거나 실패해야 다음 묶음을 시작한다. 받은 주소의 집합을 돌려준다.
 * 묶음이 바뀌면(장면이 늦게 왔다) 처음부터 다시 — 이미 받은 것은 브라우저 캐시에서 곧바로 온다.
 */
function useOrderedPreload(groups: readonly (readonly string[])[]): ReadonlySet<string> {
  const [loaded, setLoaded] = useState<ReadonlySet<string>>(() => new Set())
  const key = JSON.stringify(groups)
  useEffect(() => {
    const plan = JSON.parse(key) as string[][]
    if (plan.length === 0 || !canPreload()) return
    let live = true
    void (async () => {
      for (const [g, group] of plan.entries()) {
        await Promise.all(
          group.map(
            (url) =>
              new Promise<void>((resolve) => {
                const img = new Image()
                img.referrerPolicy = 'no-referrer'
                ;(img as HTMLImageElement & { fetchPriority?: string }).fetchPriority = g === 0 ? 'high' : g === 1 ? 'auto' : 'low'
                img.onload = () => {
                  if (live) setLoaded((s) => (s.has(url) ? s : new Set(s).add(url)))
                  resolve()
                }
                img.onerror = () => resolve()
                img.src = url
              }),
          ),
        )
        if (!live) return
      }
    })()
    return () => {
      live = false
    }
  }, [key])
  return loaded
}

// ---------------------------------------------------------------- 자리 계산

/** 핀이 놓이는 구역 — 머리줄 아래, ③ 의 아래 판 위. ②·③ 이 같은 자리를 쓴다(핀이 옮겨 다니지 않게). */
function pinRegion(w: number, h: number): { left: number; right: number; top: number; bottom: number } {
  const top = 96 + PIN.h
  const bottom = Math.max(top + 120, h - 330)
  return { left: 36, right: Math.max(72, w - 36), top, bottom }
}

/** 부채의 차례(0부터) → 부채 자리. 바깥부터 번갈아 — [0, 끝, 1, 끝-1, …]. */
function fanOrder(count: number): number[] {
  const out: number[] = []
  let l = 0
  let r = count - 1
  while (l <= r) {
    out.push(l++)
    if (l <= r) out.push(r--)
  }
  return out
}

/** 부채의 한 자리 — 영웅 카드 뒤에서 좌우로 활짝 벌어지고 바깥으로 갈수록 기울며 내려간다. 화면 폭(`stageW`) 안에 머문다. */
function fan(i: number, count: number, stageW: number): { dx: number; dy: number; rot: number } {
  if (count <= 0) return { dx: 0, dy: 0, rot: 0 }
  const mid = (count - 1) / 2
  const k = i - mid
  const reach = Math.max(1, mid)
  const step = Math.min(78, (stageW / 2 - CARD.w / 2 - 12) / reach)
  return { dx: k * step, dy: Math.abs(k) * 16 + 6, rot: k * 11 }
}

/** 진짜 지도의 핀 자리(무대 좌표). 지도가 서면 촬영지 전체에 맞추고, 지도가 움직이면 다시 잰다. 지도가 없으면 null. */
function useMapPins(
  map: MapHandle | null,
  places: readonly PlaceSummary[] | null,
  region: { left: number; right: number; top: number; bottom: number },
  size: { w: number; h: number },
): { x: number; y: number }[] | null {
  const [pins, setPins] = useState<{ x: number; y: number }[] | null>(null)
  useEffect(() => {
    if (!map || !places || places.length === 0) return
    const measure = () => {
      const s = map.getSize()
      if (s.x <= 0 || s.y <= 0) return
      const z = map.getZoom()
      const c = project(map.getCenter(), z)
      setPins(places.map((p) => {
        const pt = project({ lat: p.latitude, lng: p.longitude }, z)
        return { x: pt.x - c.x + s.x / 2, y: pt.y - c.y + s.y / 2 - CAMERA_TOP_INSET }
      }))
    }
    const lats = places.map((p) => p.latitude)
    const lngs = places.map((p) => p.longitude)
    map.resize()
    if (places.length === 1) {
      map.setView({ lat: lats[0]!, lng: lngs[0]! }, 15, { animate: false, shiftY: -((region.top + region.bottom) / 2 - size.h / 2) })
    } else {
      map.fitBounds(
        { southWest: { lat: Math.min(...lats), lng: Math.min(...lngs) }, northEast: { lat: Math.max(...lats), lng: Math.max(...lngs) } },
        {
          paddingTopLeft: [region.left, region.top + CAMERA_TOP_INSET],
          paddingBottomRight: [size.w - region.right, size.h - region.bottom],
          animate: false,
          maxZoom: 16,
        },
      )
    }
    const timer = setTimeout(measure, 0)
    const offMove = map.on('moveend', measure)
    const offZoom = map.on('zoomend', measure)
    return () => {
      clearTimeout(timer)
      offMove()
      offZoom()
    }
  }, [map, places, region, size])
  return map ? pins : null
}

/** 담긴 촬영지 하나를 핀 자리에서 「내 지도」 단추로 날린다. 핀 자리를 모르면 누른 단추에서. */
function flyPin(p: PlaceSummary, at: { x: number; y: number } | undefined, from: Element | null): Promise<void> {
  const imageUrl = cardPhoto(p)
  if (!at) return flyToMyMap(from, { imageUrl })
  // 무대 좌표 = 덮개 좌표.
  const stageEl = from?.closest('.st-landing')
  const origin = stageEl ? stageEl.getBoundingClientRect() : { left: 0, top: 0 }
  return flyToMyMap({ left: origin.left + at.x - PIN.w / 2, top: origin.top + at.y - PIN.h, width: PIN.w, height: PIN.w }, { imageUrl })
}
