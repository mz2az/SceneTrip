/*
 * 앱의 첫 화면(인트로) 덮개 — 정본: docs/project/plans/web-ad-landing.md §12-1(느리고 크게) · §12 · §10(느린 폰) · §11(누구에게) · §1-3(누른 뒤).
 *
 * 앱 화면 전체를 쓰는 한 편의 짧은 캐릭터 애니메이션이다(약 12초). 그림은 해태(작은 WebP, 덮개 덩어리에 실려 온다)와 작품 포스터,
 * 촬영지 사진, 그리고 작품 32 만 우리 사진을 바탕으로 새로 그린 일러스트(introArt.ts). 장면 사진·지도·`/places/{id}` 는 받지 않는다.
 * 움직임은 CSS transform·opacity 와 Web Animations 만. 시간은 모두 landingScript 의 INTRO_PACE.
 *
 *   받는 중   보라 바탕 + 해태 얼굴 + SceneTrip(IntroSplash — index.html 이 JS 보다 먼저 같은 것을 그려 둔다)
 *   ① drop    그 얼굴이 위로 폴짝 뛰어 사라지고, 온몸 해태가 위에서 떨어져 통통 튄 뒤 방긋
 *   ② poster  포스터가 아래에서 올라와 서고 해태가 윗변에 앞발을 걸고 빼꼼 + 머리 위 가로로 넓은 말풍선 「이 장소들, 진짜 {도시}에 있어요!」,
 *             포스터 아래 「{작품}의 장면들」. 포스터가 늦으면 제목을 쓴 보라 카드로 대신하고, 오면 바꿔 끼운다.
 *   ③ spill   (펼침) 포스터가 작아지며 촬영지 카드(앞면은 일러스트, 없으면 사진)가 포스터 둘레로 쫙 펼쳐진다.
 *             (주인공) 첫 장(작품 32 는 N서울타워)이 가운데로 크게 → 일러스트를 오래 → 천천히 실제 사진으로 뒤집힘 + 이름 → 주머니로.
 *             (나머지) 받은 순서로 빠르게 한 장씩 — 커지며 곧바로 사진으로 뒤집혀 → 오른쪽 아래 「My map」 주머니로 쏙, 수 +1.
 *             사진이 늦은 장은 이름 카드로.
 *   ④ rest    핀을 든 해태 + 「{N}곳, 전부 {도시}에 있어요!」 + 담기 + 「먼저 둘러볼게요」.
 *   ⑤ keep    담기가 끝나면(실패 0) 해태가 주머니를 안고 간직하기를 권한다(KeepBeat, §13-1). 이미 로그인했거나 홈 화면에서 연 앱이면 건너뛴다.
 *
 * 화면 아무 곳이나 누르면(단추 제외) → ④. 「건너뛰기」·「먼저 둘러볼게요」 → 덮개가 걷힌다. 움직임 줄이기면 처음부터 ④(해태 정지).
 * 자동으로 담지 않는다. 담기를 끝내면 ⑤ 간직하기를 거쳐 「더 담기」(/add). 담는 중에 덮개를 걷으면 담기는 끝까지 하되 /add 로 보내지 않는다(§10-3-7).
 * 처음 온 사람이 아니면(담은 것·코스가 있다) 아무것도 그리지 않고 곧바로 걷힌다(§11).
 */
import { type CSSProperties, type JSX, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { type LandingBeat, track, webEvent } from '@/analytics'
import { api, installHeader, ok } from '@/api/client'
import type { components } from '@/api/generated/schema'
import { Icon } from '@/design/Icon'
import { Spinner, Txt } from '@/design/primitives'
import { MY_MAP_TARGET, MyMapButton, flyToMyMap, myMapLandStore } from '@/features/keep/flyToMyMap'
import { currentKeepEnv } from '@/features/keep/installPrompt'
import { keepActions } from '@/features/keep/keepStore'
import { Pressable } from '@/features/route/ui/Press'
import { currentLang, say, useLang } from '@/i18n'
import { smallImage } from '@/shared/smallImage'
import { spots } from '@/shared/spots'
import { hasSession } from '@/state/auth'
import { cartActions, cartStore } from '@/state/cart'
import { useStore } from '@/state/storage'
import { landingArt } from './art'
import { INTRO_HAETAE_SRC, IntroSplash } from './IntroSplash'
import { KeepBeat } from './KeepBeat'
import { introArtUrl, introHeroId } from './introArt'
import type { IntroClose } from './introClose'
import {
  type LandingTimeline,
  beatAt,
  centerCard,
  flipOf,
  introOrder,
  landingTimeline,
  leaveAt,
  pocketAt,
  pocketedCount,
  spillStart,
  spreadAt,
  topRegion,
  zoomAt,
  zoomMsOf,
} from './landingScript'
import './landing.css'

type ContentDetail = components['schemas']['ContentDetail']
type PlaceSummary = components['schemas']['PlaceSummary']

/** 촬영지 받기 한 번의 끝. */
const PLACES_LIMIT = 100
/** 담는 동안 주머니로 날려 보내는 조각 수. */
const FLY_PIECES = 6

interface Ready {
  content: ContentDetail
  places: PlaceSummary[]
}

type SaveState = { kind: 'idle' } | { kind: 'saving'; done: number; total: number } | { kind: 'failed'; failed: number }

const reducedMotion = (): boolean =>
  typeof window !== 'undefined' && window.matchMedia?.('(prefers-reduced-motion: reduce)').matches === true

const thumbOf = (p: PlaceSummary) => smallImage(p.imageThumbnailUrl, p.imageUrl)
/** 사진을 미리 받을 수 있는 환경인가(jsdom 은 사진을 받지 않는다 — 그때는 주소를 그대로 그린다). */
const canPreload = (): boolean => typeof Image !== 'undefined' && typeof navigator !== 'undefined' && !/jsdom/i.test(navigator.userAgent)

export function LandingCover({ contentId, onRest, onClose, onBeat, onKeep, onNeedApp }: {
  contentId: number
  onRest?: () => void
  onClose: (how: IntroClose) => void
  /** 박자가 바뀌었다(IntroShell 이 `landing(beat)` 을 보내고 떠날 때의 박자로 적는다). */
  onBeat?: (beat: LandingBeat) => void
  /** 담기를 눌렀다 — 이 뒤로는 떠나도 `landing(leave)` 를 보내지 않는다. */
  onKeep?: () => void
  /** ⑤ 에서 로그인 창을 띄우기 전 — 로그인 창이 사는 밑의 앱을 붙여 달라. */
  onNeedApp?: () => void
}): JSX.Element {
  useLang()
  const [ready, setReady] = useState<Ready | null>(null)
  /** 영어 화면에서 카드 이름 아래 작게 쓰는 한국어 이름(촬영지 id → 이름). 한국어 화면이면 비어 있다. */
  const [koNames, setKoNames] = useState<ReadonlyMap<number, string>>(() => new Map())
  /** 네 박자의 시계 — null 이면 아직 시작하지 않았다. */
  const [elapsed, setElapsed] = useState<number | null>(null)
  const [save, setSave] = useState<SaveState>({ kind: 'idle' })
  /** ⑤ 간직하기 — 담기가 끝나고(실패 0) 보이는 박자(§13-1). */
  const [keepStep, setKeepStep] = useState(false)
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

  // ---- 받기 — 작품·촬영지·장바구니·코스를 함께(§12: 장면 사진·/places/{id}·지도는 받지 않는다).
  useEffect(() => {
    let live = true
    const cartReady = cartStore.get().loadState === 'loaded' ? Promise.resolve() : cartActions.refresh()
    const courses = ok(api.GET('/courses', { params: { header: installHeader() } }))
      .then((c) => c.items.length)
      .catch(() => 0)
    const content = ok(api.GET('/contents/{contentId}', { params: { path: { contentId } } }))
    const list = ok(api.GET('/contents/{contentId}/places', { params: { path: { contentId }, query: { limit: PLACES_LIMIT } } }))
    // 영어 화면이면 한국어 이름도 한 번 더 받는다(읽기만, 기다리지 않는다 — 늦거나 실패하면 한국어 줄 없이).
    if (currentLang() !== 'ko') {
      void ok(api.GET('/contents/{contentId}/places', { params: { path: { contentId }, query: { limit: PLACES_LIMIT } }, headers: { 'Accept-Language': 'ko' } }))
        .then((l) => {
          if (live) setKoNames(new Map(l.items.map((p) => [p.id, p.name])))
        })
        .catch(() => {})
    }
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
        places = l.items
      } catch {
        if (live) closeRef.current('failed')
        return
      }
      if (!live) return
      if (detail.delisted === true || places.length === 0) {
        closeRef.current('failed')
        return
      }
      setReady({ content: detail, places })
    })()
    void content.catch(() => {})
    void list.catch(() => {})
    return () => {
      live = false
    }
  }, [contentId])

  const n = ready?.places.length ?? 0
  const timeline: LandingTimeline = useMemo(() => landingTimeline(n), [n])
  const poster = ready?.content.posterUrl ?? null

  // ---- 그림 받기 순서: 포스터 → 카드 앞면(일러스트, 없으면 사진) → 일러스트 뒤의 사진. 하나씩 보이지 않는 곳의 사진은 받지 않는다.
  // 카드 순서 — 주인공 촬영지(작품 32 는 N서울타워)를 맨 앞으로, 나머지는 받은 순서.
  const featured = useMemo(() => {
    if (!ready) return []
    const ids = ready.places.map((p) => p.id)
    return introOrder(ids, introHeroId(contentId, ids))
      .map((i) => ready.places[i]!)
      .slice(0, timeline.featured)
  }, [ready, timeline.featured, contentId])
  const preloadGroups = useMemo(() => {
    const fronts = featured.map((p) => introArtUrl(contentId, p.id) ?? thumbOf(p)).filter((u): u is string => !!u)
    const backs = featured.filter((p) => introArtUrl(contentId, p.id) !== null).map(thumbOf).filter((u): u is string => !!u)
    return [poster ? [poster] : [], fronts, backs].filter((g) => g.length > 0)
  }, [featured, poster, contentId])
  const [preloading] = useState(canPreload)
  const loaded = useOrderedPreload(preloadGroups)
  const has = useCallback((url: string | null) => url !== null && (!preloading || loaded.has(url)), [loaded, preloading])

  // ---- 시계 — 그림이 바뀌는 때만 넘긴다: ② · ③ 의 시작, 카드가 커지는 때·주머니에 들어가는 때, ④. 카드의 움직임 자체는 Web Animations.
  const startClock = useCallback(
    (from: number) => {
      timers.current.forEach(clearTimeout)
      timers.current = []
      setElapsed(from)
      if (from >= timeline.total) return
      const marks = new Set<number>([timeline.dropMs, spillStart(timeline), timeline.total])
      for (let i = 0; i <= timeline.featured; i++) {
        marks.add(zoomAt(i, timeline))
        if (i < timeline.featured) marks.add(pocketAt(i, timeline))
      }
      for (const at of [...marks].sort((a, b) => a - b)) {
        if (at <= from) continue
        timers.current.push(setTimeout(() => setElapsed(at), at - from))
      }
    },
    [timeline],
  )
  useEffect(() => () => timers.current.forEach(clearTimeout), [])

  // 받으면 곧바로 ① — 해태 그림은 덮개 덩어리에 실려 와 기다릴 것이 없다.
  useEffect(() => {
    if (!ready || elapsed !== null) return
    once('view')
    startClock(reduced.current ? timeline.total : 0)
  }, [ready, elapsed, once, startClock, timeline.total])

  const filmBeat = elapsed === null ? null : beatAt(elapsed, timeline)
  const beat: LandingBeat | null = keepStep ? 'keep' : filmBeat
  const rest = filmBeat === 'rest'
  const pocketed = elapsed === null ? 0 : pocketedCount(elapsed, timeline, n)
  const center = elapsed === null || beat !== 'spill' ? null : centerCard(elapsed, timeline)
  useEffect(() => {
    if (beat !== null) onBeat?.(beat)
  }, [beat, onBeat])
  useEffect(() => {
    if (!rest) return
    once('rest')
    onRest?.()
  }, [rest, once, onRest])

  /** 「건너뛰기」 — 덮개를 걷는다(§11). */
  const skipAll = () => {
    track(webEvent.landing(contentId, 'skip'))
    closeRef.current('skip')
  }
  /** 화면을 누르면 ④ 로. */
  const jumpToRest = () => {
    if (!ready || beat === null || beat === 'rest') return
    track(webEvent.landing(contentId, 'skip'))
    startClock(timeline.total)
  }

  // ---- 무대 크기
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
  const g = useMemo(() => geometry(size.w, size.h), [size])

  // ---- 담기
  const keepAll = async (from: Element | null, onlyRemaining: boolean) => {
    if (!ready || save.kind === 'saving') return
    const all = ready.places.map((p) => p.id)
    const remaining = all.filter((id) => !cartStore.get().placeIds.has(id))
    const ids = onlyRemaining && remaining.length > 0 ? remaining : all
    track(webEvent.landing(contentId, 'save'))
    onKeep?.()
    setSave({ kind: 'saving', done: 0, total: ids.length })
    // 담긴 것부터 하나씩 주머니로 날린다(§10-3-9) — 실패한 것은 날리지 않는다.
    const before = new Set(cartStore.get().placeIds)
    const flown = new Set<number>()
    const flights: Promise<void>[] = []
    const flyNew = () => {
      const now = cartStore.get().placeIds
      for (const p of ready.places) {
        if (flown.size >= FLY_PIECES || flown.has(p.id) || before.has(p.id) || !now.has(p.id)) continue
        flown.add(p.id)
        flights.push(flyToMyMap(from, { imageUrl: thumbOf(p) }))
      }
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
      await Promise.all(flights)
      if (!alive.current) return
      // ⑤ 간직하기 — 이미 로그인했거나 홈 화면에서 연 앱이면 건너뛰고 곧바로 「더 담기」(§13-1).
      if (hasSession() || currentKeepEnv() === 'installed') {
        closeRef.current('kept')
        return
      }
      keepActions.markIntroKeepSeen()
      setKeepStep(true)
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

  if (keepStep) {
    return (
      <div ref={stage} className="st-landing st-film" data-beat="keep">
        <div className="st-landing__head">
          <div className="st-film__brand">
            <img src={INTRO_HAETAE_SRC} alt="" width={26} height={22} draggable={false} />
            <Txt size={15} weight="heavy" style={{ color: '#fff' }}>SceneTrip</Txt>
          </div>
        </div>
        <KeepBeat count={n} contentId={contentId} onDone={() => closeRef.current('kept')} onNeedApp={onNeedApp} />
      </div>
    )
  }

  // ② 는 받고 1.2초 뒤에 시작한다 — 그때까지 포스터가 안 왔으면 제목 카드, 오면 바꿔 끼운다.
  const posterImg = has(poster) ? poster : null
  const region = topRegion(places.map((p) => p.address))
  const city = say(region.ko, region.en)
  const restLine =
    n === 1
      ? say(`1곳, ${city}에 있어요!`, `1 place, in ${city}!`)
      : region.all
        ? say(`${n}곳, 전부 ${city}에 있어요!`, `${n} places, all in ${city}!`)
        : say(`${n}곳, 대부분 ${city}에 있어요!`, `${n} places, mostly in ${city}!`)
  const askLine = say(`이 장소들, 진짜 ${city}에 있어요!`, `These places are real — in ${city}!`)
  const vars = {
    '--stage-h': `${g.h}px`,
    '--hero-to-poster': `${g.posterTop + g.askDy - 30 - g.heroCy}px`,
    '--poster-dy': `${g.askDy}px`,
    '--poster-from': `${g.h - g.posterTop + 40}px`,
    '--poster-spread': `translateY(${Math.round((g.posterH * (1 - g.spreadScale)) / 2)}px) scale(${g.spreadScale.toFixed(3)})`,
  } as CSSProperties

  return (
    <div
      ref={stage}
      className="st-landing st-film"
      data-beat={beat}
      data-phase={beat === 'spill' ? (center !== null ? 'one' : pocketed === 0 ? 'spread' : 'end') : undefined}
      data-instant={reduced.current ? '' : undefined}
      onClick={(e) => {
        if ((e.target as HTMLElement).closest('button')) return
        jumpToRest()
      }}
      style={vars}
    >
      {/* ① — 첫 화면(HTML)의 해태 얼굴과 SceneTrip 이 같은 자리에서 시작한다. */}
      {beat === 'drop' && (
        <img className="st-film__face0" src={INTRO_HAETAE_SRC} alt="" width={72} height={62} draggable={false} style={{ left: g.w / 2 - 36, top: g.h / 2 - 49 }} />
      )}
      {(beat === 'drop' || beat === 'poster') && (
        <div className="st-film__word" style={{ top: g.h / 2 + 36 - 14 }}>
          SceneTrip
        </div>
      )}
      {(beat === 'drop' || beat === 'poster') && (
        <div className="st-film__hero" aria-hidden style={{ left: g.w / 2 - g.sitW / 2, top: g.heroCy - g.sitH / 2, width: g.sitW, height: g.sitH }}>
          <div className="st-film__hero-in">
            <img className="st-film__sit" src={landingArt.sit} alt="" draggable={false} />
            <img className="st-film__joy" src={landingArt.joy} alt="" draggable={false} />
          </div>
        </div>
      )}

      {/* ② — 포스터. 늦으면 제목을 쓴 보라 카드. */}
      {beat !== 'drop' && (
        <div className="st-film__poster" aria-hidden style={{ left: g.w / 2 - g.posterW / 2, top: g.posterTop, width: g.posterW, height: g.posterH }}>
          <div className="st-film__poster-in">
            {posterImg ? (
              <img src={posterImg} alt="" referrerPolicy="no-referrer" draggable={false} />
            ) : (
              <span className="st-film__poster-card">
                <img src={INTRO_HAETAE_SRC} alt="" width={44} height={38} draggable={false} />
                <span>{title}</span>
              </span>
            )}
          </div>
        </div>
      )}

      {/* ② — 포스터 아래 「{작품}의 장면들」 */}
      {(beat === 'poster' || beat === 'spill') && (
        <div className="st-film__caption" style={{ top: g.posterTop + g.askDy + g.posterH + 14 }}>
          {say(`${title}의 장면들`, `Scenes from ${title}`)}
        </div>
      )}

      {/* ②·③ — 포스터 윗변 뒤에서 빼꼼(iOS 시작 화면과 같은 동작). 머리는 윗변에서 잘리고, 앞발은 윗변에 걸친다. */}
      {(beat === 'poster' || beat === 'spill') && (
        <>
          <div className="st-film__peek" aria-hidden style={{ left: g.w / 2 - 100, top: g.posterTop + g.askDy - 122 }}>
            <div className="st-film__look">
              <img className="st-film__head" src={landingArt.peek} alt="" draggable={false} />
            </div>
          </div>
          <img className="st-film__paw st-film__paw--l" src={landingArt.pawLeft} alt="" aria-hidden draggable={false} style={{ left: g.w / 2 - 34 - 21, top: g.posterTop + g.askDy - 16 }} />
          <img className="st-film__paw st-film__paw--r" src={landingArt.pawRight} alt="" aria-hidden draggable={false} style={{ left: g.w / 2 + 34 - 21, top: g.posterTop + g.askDy - 16 }} />
        </>
      )}

      {/* ④ — 핀을 든 해태가 포스터 윗변에서 올라온다. */}
      {rest && (
        <img className="st-film__pin" src={landingArt.pin} alt="" aria-hidden draggable={false} style={{ left: g.pinLeft, top: g.posterTop + 26 - g.pinH, width: g.pinW, height: g.pinH }} />
      )}

      {/* ② 말풍선 — 가로로 넓게 한두 줄, 해태 머리 위(2026-11-15 사용자: 「가로로 보여주는 게 나을 것 같아」). */}
      {beat === 'poster' && (
        <div className="st-film__bubble-row" style={{ bottom: g.h - (g.posterTop + g.askDy - ASK_HEAD_UP) }}>
          <div className="st-film__bubble st-film__bubble--wide">
            <Txt size={16} weight="heavy" style={{ color: '#2a1856', lineHeight: 1.25 }}>{askLine}</Txt>
          </div>
        </div>
      )}
      {/* ④ 말풍선 — 「{N}곳, 전부 {도시}에 있어요!」 */}
      {rest && (
        <div
          key="rest"
          className="st-film__bubble st-film__bubble--rest"
          style={{ right: g.w - g.pinLeft - 12, bottom: g.h - (g.posterTop - 30), width: 'max-content', maxWidth: g.pinLeft + 12 - 16 }}
        >
          <Txt as="h1" size={17} weight="heavy" style={{ margin: 0, color: '#2a1856', lineHeight: 1.25 }}>{restLine}</Txt>
        </div>
      )}

      {/* ③ — 포스터 둘레로 펼쳐졌다가 한 장씩 크게, 뒤집혀 실제 사진, 주머니로 */}
      {beat === 'spill' &&
        elapsed !== null &&
        featured.map((p, i) => {
          if (elapsed >= pocketAt(i, timeline)) return null
          const art = introArtUrl(contentId, p.id)
          const photo = thumbOf(p)
          const ko = koNames.get(p.id)
          return (
            <Card
              key={p.id}
              i={i}
              t={timeline}
              g={g}
              mountElapsed={elapsed}
              art={art !== null && has(art) ? art : null}
              photo={photo !== null && has(photo) ? photo : null}
              name={p.name}
              nameKo={ko && ko !== p.name ? ko : null}
              // 주인공은 언제나 맨 앞 — 둘레의 카드가 가운데 주인공을 가리지 않는다(2026-11-16 사용자). 나머지는 뒤에 온 장이 위.
              z={i === 0 ? 50 : 30 + i}
            />
          )
        })}

      {/* 「내 지도」 주머니 — ③ 에서 나타나 수가 오른다. 담을 때 조각이 여기로 내려앉는다. */}
      {(beat === 'spill' || rest) && (
        <Pouch count={rest ? n : pocketed} g={g} rest={rest} />
      )}

      {/* 머리줄 — ① 에서는 건너뛰기만, ② 부터 왼쪽에 작은 해태 + SceneTrip. ④ 의 오른쪽은 「내 지도」 단추. */}
      <div className="st-landing__head">
        <div className="st-film__brand" style={{ opacity: beat === 'drop' ? 0 : 1 }}>
          <img src={INTRO_HAETAE_SRC} alt="" width={26} height={22} draggable={false} />
          <Txt size={15} weight="heavy" style={{ color: '#fff' }}>SceneTrip</Txt>
        </div>
        {rest ? (
          <MyMapButton tone="photo" onPress={() => closeRef.current('myMap')} />
        ) : (
          <Pressable plain onClick={skipAll} className="st-landing__skip">
            <Txt size={14} weight="bold" style={{ color: '#fff' }}>{say('건너뛰기', 'Skip')}</Txt>
          </Pressable>
        )}
      </div>

      {/* ④ — 아래 단추 */}
      {rest && (
        <div className="st-landing__panel" onClick={(e) => e.stopPropagation()}>
          {save.kind === 'failed' && (
            <Txt size={13} weight="bold" style={{ color: '#ffd1dc', textAlign: 'center' }}>{say(`${save.failed}곳은 담지 못했어요`, `Couldn't save ${spots(save.failed)}`)}</Txt>
          )}
          <Pressable plain className="proto-press st-landing__keep" disabled={save.kind === 'saving'} onClick={(e) => void keepAll(e.currentTarget, save.kind === 'failed')}>
            <span style={{ display: 'inline-flex', alignItems: 'center', gap: 8, justifyContent: 'center' }}>
              {save.kind === 'saving' ? <Spinner size={16} /> : <Icon name="map" size={18} weight="bold" color="#2a1856" />}
              <Txt size={17} weight="heavy" style={{ color: '#2a1856' }}>{keepLabel(save, n)}</Txt>
            </span>
          </Pressable>
          <Pressable plain onClick={browse} className="st-landing__browse">
            <Txt size={15} weight="semibold" style={{ color: 'rgba(255,255,255,0.86)' }}>{say('먼저 둘러볼게요', 'Just looking')}</Txt>
          </Pressable>
        </div>
      )}
    </div>
  )
}

function keepLabel(save: SaveState, n: number): string {
  if (save.kind === 'saving') return say(`담는 중… ${save.done}/${save.total}`, `Saving… ${save.done}/${save.total}`)
  if (save.kind === 'failed') return say(`남은 ${save.failed}곳도 담기`, `Save the other ${spots(save.failed)}`)
  return n === 1 ? say('이 1곳 마이맵에 담기', 'Keep this 1 on My map') : say(`이 ${n}곳 마이맵에 담기`, `Keep these ${n} on My map`)
}

// ---------------------------------------------------------------- 자리

interface Geometry {
  w: number
  h: number
  sitW: number
  sitH: number
  /** ① 의 해태가 내려앉는 가운데(y). */
  heroCy: number
  posterW: number
  posterH: number
  posterTop: number
  /** ② 에서 말풍선이 해태 머리 위에 들어가도록 포스터·해태를 내리는 거리 — 높은 화면은 0. */
  askDy: number
  /** 포스터의 가운데(y) — ③ 의 카드 고리의 가운데. */
  posterCy: number
  /** ③ 에서 포스터가 줄어드는 비율(가운데를 축으로). */
  spreadScale: number
  /** 카드 고리(타원)의 가로·세로 반지름. */
  ringRx: number
  ringRy: number
  /** 고리에 선 카드의 폭 — 카드는 큰 크기로 그리고 줄여 세운다(커질 때 흐려지지 않게). */
  ringW: number
  /** 가운데로 커진 카드의 크기와 가운데(y). */
  bigW: number
  bigH: number
  zoomCy: number
  pinW: number
  pinH: number
  /** ④ 의 핀 해태 왼쪽 — 줄어든 포스터의 오른쪽 윗모서리에 걸친다(말풍선이 왼쪽에 넉넉히 서게). */
  pinLeft: number
  /** 주머니 — ③ 은 오른쪽 아래, ④ 는 가운데에서 단추 위로. */
  pouchLeft: number
  pouchTop: number
  pouchRestLeft: number
  pouchRestTop: number
}

/** ② 말풍선 — 위 끝(머리줄 아래), 두 줄 높이, 말풍선 아래 끝과 포스터 윗변 사이(해태 머리가 서는 자리). */
const ASK_TOP = 60
const ASK_H = 58
const ASK_HEAD_UP = 102

/** 카드: 폭 W, 높이 1.5W(사진 칸 + 아래 이름 띠). */
const CARD_RATIO = 1.5
/** 주머니 상자의 폭과 그 안 가방의 가운데(상자 왼쪽 위에서). */
const POUCH_W = 110
const BAG_CX = 55
const BAG_CY = 38

/** 무대 크기 → 자리. 포스터는 5:7, 폭은 화면의 56%(최대 220, 낮은 화면에서는 더 작게). 높이 667 에서도 모두 화면 안에 든다. */
function geometry(w: number, h: number): Geometry {
  const sitW = Math.round(Math.min(150, w * 0.37))
  const sitH = Math.round((sitW * 582) / 525)
  const posterW = Math.round(Math.max(150, Math.min(220, w * 0.56, ((h - 380) * 5) / 7)))
  const posterH = Math.round((posterW * 7) / 5)
  const posterTop = Math.round(Math.max(150, h * 0.25))
  const posterCy = posterTop + posterH / 2
  const pinW = 112
  const pinH = Math.round((pinW * 397) / 355)
  const ringW = Math.round(Math.max(54, Math.min(70, w * 0.165)))
  const ringH = ringW * CARD_RATIO
  const ringRx = Math.round(Math.min(w / 2 - ringW / 2 - 8, 190))
  // 위로는 머리줄(56) 아래, 아래로는 주머니(오른쪽 아래 약 110) 위에 든다.
  const ringRy = Math.round(Math.max(120, Math.min(posterCy - 58 - ringH / 2, h - 112 - ringH / 2 - posterCy, 290)))
  const spreadScale = Math.min(0.62, ((ringRy - ringH / 2 - 8) * 2) / posterH, ((ringRx - ringW / 2 - 8) * 2) / posterW)
  const bigW = Math.round(Math.min(w * 0.7, (h - 170) / CARD_RATIO, 330))
  const bigH = Math.round(bigW * CARD_RATIO)
  const pouchTop = h - 114
  return {
    w,
    h,
    sitW,
    sitH,
    heroCy: Math.round(h / 2 + 14 - sitH / 2),
    posterW,
    posterH,
    posterTop,
    askDy: Math.max(0, ASK_TOP + ASK_H + ASK_HEAD_UP - posterTop),
    posterCy,
    spreadScale: Math.max(0.36, spreadScale),
    ringRx,
    ringRy,
    ringW,
    bigW,
    bigH,
    zoomCy: Math.round(56 + (pouchTop - 56) / 2),
    pinW,
    pinH,
    pinLeft: Math.round(w / 2 + (posterW * 0.82) / 2 - pinW * 0.6),
    pouchLeft: w - 12 - POUCH_W,
    pouchTop,
    pouchRestLeft: Math.round(w / 2 - POUCH_W / 2),
    pouchRestTop: h - 250,
  }
}

// ---------------------------------------------------------------- 부품

/**
 * ③ 의 카드 한 장 — 펼침(포스터 → 고리) · 하나씩(고리 → 가운데로 크게 → 뒤집힘 → 주머니)을 Web Animations 둘로 그린다.
 * 바깥(자리·크기)과 안(뒤집기)을 나눈다. 시간은 ③ 의 시작을 0 으로 잡고, 그린 때의 시계(mountElapsed)에서 이어 간다.
 * art 가 있으면 앞면은 일러스트, 뒷면은 실제 사진(없으면 이름 카드) — 커진 뒤 뒤집힌다. art 가 없으면 앞면이 곧 사진(뒤집지 않는다).
 */
function Card({ i, t, g, mountElapsed, art, photo, name, nameKo, z }: {
  i: number
  t: LandingTimeline
  g: Geometry
  mountElapsed: number
  art: string | null
  photo: string | null
  name: string
  nameKo: string | null
  z: number
}) {
  const outer = useRef<HTMLDivElement>(null)
  const inner = useRef<HTMLDivElement>(null)
  /** 그린 때 — 시계(ms)와 performance.now(). 뒤집기를 나중에 다시 걸 때 여기서 이어 간다. */
  const born = useRef<{ elapsed: number; perf: number } | null>(null)
  const flip = art !== null
  const S = spillStart(t)
  const end = pocketAt(i, t)
  const now = (): number => {
    born.current ??= { elapsed: mountElapsed, perf: performance.now() }
    return born.current.elapsed + (performance.now() - born.current.perf) - S
  }
  const total = end - S
  const at = (ms: number) => Math.min(1, Math.max(0, (ms - S) / total))

  useEffect(() => {
    const el = outer.current
    if (!el || typeof el.animate !== 'function') return
    const angle = (-90 + (i * 360) / Math.max(1, t.featured)) * (Math.PI / 180)
    const ring = { x: g.w / 2 + g.ringRx * Math.cos(angle), y: g.posterCy + g.ringRy * Math.sin(angle) }
    const small = g.ringW / g.bigW
    const tilt = ((i * 37) % 11) - 5
    const pouch = { x: g.pouchLeft + BAG_CX, y: g.pouchTop + BAG_CY }
    const pos = (x: number, y: number, scale: number, rot: number) => `translate(${(x - g.w / 2).toFixed(1)}px, ${(y - g.zoomCy).toFixed(1)}px) scale(${scale.toFixed(3)}) rotate(${rot}deg)`
    const out = spreadAt(i, t)
    const zoom = zoomAt(i, t)
    const leave = leaveAt(i, t)
    // 나머지는 가운데 더미 — 한 장씩 조금 비껴 기울어 쌓이고(뒤에 온 장이 위), 다 함께 주머니로 간다. 주인공은 정가운데.
    const pile = i === 0 ? { x: g.w / 2, y: g.zoomCy, s: 1, r: 0 } : { x: g.w / 2 + (((i * 29) % 7) - 3) * 7, y: g.zoomCy + (((i * 17) % 5) - 2) * 6, s: 0.92, r: ((i * 41) % 13) - 6 }
    const frames: Keyframe[] = [
      { offset: 0, transform: pos(g.w / 2, g.posterCy, small * 0.4, 0), opacity: 0 },
      { offset: at(out), transform: pos(g.w / 2, g.posterCy, small * 0.4, 0), opacity: 0, easing: 'cubic-bezier(0.2, 0.9, 0.3, 1.2)' },
      { offset: at(out + 120), opacity: 1 },
      { offset: at(out + t.pace.spreadFlyMs), transform: pos(ring.x, ring.y, small, tilt), opacity: 1 },
      { offset: at(zoom), transform: pos(ring.x, ring.y, small, tilt), opacity: 1, easing: 'cubic-bezier(0.3, 0.8, 0.3, 1.1)' },
      { offset: at(zoom + zoomMsOf(i, t)), transform: pos(pile.x, pile.y, pile.s, pile.r), opacity: 1 },
      { offset: at(leave), transform: pos(pile.x, pile.y, pile.s, pile.r), opacity: 1, easing: 'cubic-bezier(0.4, 0, 0.8, 0.5)' },
      { offset: at(leave + t.pace.flyMs * 0.8), opacity: 1 },
      { offset: 1, transform: pos(pouch.x, pouch.y, 0.12, 14), opacity: 0 },
    ]
    const a = el.animate(frames, { duration: total, easing: 'linear', fill: 'both' })
    a.currentTime = now()
    return () => a.cancel()
    // 한 장의 길은 한 번만 건다 — 자리·시간이 그 뒤에 바뀌어도 다시 시작하지 않는다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => {
    const el = inner.current
    if (!el || typeof el.animate !== 'function' || !flip) return
    const { at: from, ms: flipMs } = flipOf(i, t)
    const a = el.animate(
      [
        { offset: 0, transform: 'rotateY(0deg)' },
        // 주인공은 0.6초 동안 고르게(천천히 넘어가는 것이 보이게), 나머지는 빠르게.
        { offset: at(from), transform: 'rotateY(0deg)', easing: i === 0 ? 'cubic-bezier(0.25, 0.1, 0.75, 0.9)' : 'cubic-bezier(0.4, 0, 0.2, 1)' },
        { offset: at(from + flipMs), transform: 'rotateY(180deg)' },
        { offset: 1, transform: 'rotateY(180deg)' },
      ],
      { duration: total, easing: 'linear', fill: 'both' },
    )
    a.currentTime = now()
    return () => a.cancel()
    // 일러스트가 늦게 오면 그때 뒤집기를 건다(지금 시각에서 이어서).
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [flip])

  const label = (
    <span className="st-card__label">
      <span className="st-card__name">{name}</span>
      {nameKo && <span className="st-card__ko">{nameKo}</span>}
    </span>
  )
  const face = (url: string | null, withName: boolean) =>
    url ? (
      <>
        <img className="st-card__photo" src={url} alt="" referrerPolicy="no-referrer" draggable={false} />
        {withName ? label : <span className="st-card__label st-card__label--blank"><img src={INTRO_HAETAE_SRC} alt="" width={30} height={26} draggable={false} /></span>}
      </>
    ) : (
      <span className="st-card__namecard">
        <img src={INTRO_HAETAE_SRC} alt="" width={52} height={45} draggable={false} />
        <span className="st-card__name">{name}</span>
        {nameKo && <span className="st-card__ko">{nameKo}</span>}
      </span>
    )
  return (
    <div
      ref={outer}
      className="st-card"
      aria-hidden
      data-flip={flip ? '' : undefined}
      style={{ left: g.w / 2 - g.bigW / 2, top: g.zoomCy - g.bigH / 2, width: g.bigW, height: g.bigH, zIndex: z }}
    >
      <div ref={inner} className="st-card__in">
        <div className="st-card__face">{flip ? face(art, false) : face(photo, true)}</div>
        {flip && <div className="st-card__face st-card__face--back">{face(photo, true)}</div>}
      </div>
    </div>
  )
}

/** 「내 지도」 주머니 — 가방 모양 + 수 배지. 수가 오르거나 무엇이 내려앉으면 통 튄다. 담을 때 날아오는 조각의 과녁. */
function Pouch({ count, g, rest }: { count: number; g: Geometry; rest: boolean }) {
  const landed = useStore(myMapLandStore, (x) => x)
  const dx = rest ? g.pouchRestLeft - g.pouchLeft : 0
  const dy = rest ? g.pouchRestTop - g.pouchTop : 0
  return (
    <div className="st-film__pouch" style={{ left: g.pouchLeft, top: g.pouchTop, transform: rest ? `translate(${dx}px, ${dy}px)` : undefined }} {...{ [MY_MAP_TARGET]: '' }} aria-hidden>
      <div className="st-film__pouch-in">
        <div key={`${count}-${landed}`} className={count > 0 || landed > 0 ? 'st-film__bag st-film__bag--bump' : 'st-film__bag'}>
          <span className="st-film__bag-flap" />
          <Icon name="map" size={22} weight="bold" color="#5c4ad6" />
          <span className="st-film__bag-count">{count}</span>
        </div>
        <span className="st-film__bag-label">{say('마이맵', 'My map')}</span>
      </div>
    </div>
  )
}

// ---------------------------------------------------------------- 사진 받기

/**
 * 묶음 차례대로 사진을 미리 받는다 — 앞 묶음이 다 오거나 실패해야 다음 묶음을 시작한다. 받은 주소의 집합을 돌려준다.
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
