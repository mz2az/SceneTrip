/*
 * 앱의 첫 화면(인트로) 덮개 — 정본: docs/project/plans/web-ad-landing.md §12(연출) · §10(느린 폰) · §11(누구에게) · §1-3(누른 뒤).
 *
 * 앱 화면 전체를 쓰는 한 편의 짧은 캐릭터 애니메이션이다. 그림은 해태(작은 WebP, 덮개 덩어리에 실려 온다)와 작품 포스터,
 * 촬영지 썸네일뿐 — 장면 사진·지도·`/places/{id}` 는 받지 않는다. 움직임은 CSS transform·opacity 와 Web Animations 만.
 *
 *   받는 중   보라 바탕 + 해태 얼굴 + SceneTrip(IntroSplash — index.html 이 JS 보다 먼저 같은 것을 그려 둔다)
 *   ① drop    그 얼굴이 위로 폴짝 뛰어 사라지고, 온몸 해태가 위에서 떨어져 통통 튄 뒤 방긋(웃는 얼굴로 바뀌며 1 → 1.08 → 1)
 *   ② poster  포스터가 아래에서 올라와 선다. 해태가 그 윗변 뒤로 들어가 앞발 둘을 걸고 머리를 빼꼼 + 「저기 어디야?」
 *             포스터가 1.2초 안에 안 왔으면 제목을 쓴 보라 카드로 대신하고, 오면 바꿔 끼운다.
 *   ③ spill   포스터에서 촬영지 썸네일이 하나씩 포물선으로 튀어나와 아래 「내 지도」 주머니로 쏙. 들어갈 때마다 수가 오르고 주머니가 통 튄다.
 *             썸네일은 사진이 온 것부터 난다 — 안 온 것은 수만 오른다.
 *   ④ rest    핀을 든 해태 + 「{N}곳, 전부 {도시}에 있어요!」 + 담기 + 「먼저 둘러볼게요」.
 *
 * 화면 아무 곳이나 누르면(단추 제외) → ④. 「건너뛰기」·「먼저 둘러볼게요」 → 덮개가 걷힌다. 움직임 줄이기면 처음부터 ④(해태 정지).
 * 자동으로 담지 않는다. 담기를 끝내면 「더 담기」(/add). 담는 중에 덮개를 걷으면 담기는 끝까지 하되 /add 로 보내지 않는다(§10-3-7).
 * 처음 온 사람이 아니면(담은 것·코스가 있다) 아무것도 그리지 않고 곧바로 걷힌다(§11).
 */
import { type CSSProperties, type JSX, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { track, webEvent } from '@/analytics'
import { api, installHeader, ok } from '@/api/client'
import type { components } from '@/api/generated/schema'
import { Icon } from '@/design/Icon'
import { Spinner, Txt } from '@/design/primitives'
import { MY_MAP_TARGET, MyMapButton, flyToMyMap, myMapLandStore } from '@/features/keep/flyToMyMap'
import { Pressable } from '@/features/route/ui/Press'
import { say, useLang } from '@/i18n'
import { smallImage } from '@/shared/smallImage'
import { spots } from '@/shared/spots'
import { cartActions, cartStore } from '@/state/cart'
import { useStore } from '@/state/storage'
import pawLeft from './art/paw-left.webp?inline'
import pawRight from './art/paw-right.webp?inline'
import haetaeJoy from './art/haetae-joy.webp?inline'
import haetaePeek from './art/haetae-peek.webp?inline'
import haetaePin from './art/haetae-pinhold.webp?inline'
import haetaeSit from './art/haetae-sit.webp?inline'
import { INTRO_HAETAE_SRC, IntroSplash } from './IntroSplash'
import type { IntroClose } from './introClose'
import { type Beat, FLIGHT_MS, type LandingTimeline, beatAt, landingTimeline, launchAt, pocketedCount, spillStart, topRegion } from './landingScript'
import './landing.css'

type ContentDetail = components['schemas']['ContentDetail']
type PlaceSummary = components['schemas']['PlaceSummary']

/** 촬영지 받기 한 번의 끝. */
const PLACES_LIMIT = 100
/** 포스터 다음에 먼저 받는 썸네일 수. */
const FIRST_THUMBS = 6
/** ③ 에서 나는 썸네일의 최대 수 — 나머지는 수만 오른다. */
const FLY_MAX = 12
/** 담는 동안 주머니로 날려 보내는 조각 수. */
const FLY_PIECES = 6

interface Ready {
  content: ContentDetail
  places: PlaceSummary[]
}

type SaveState = { kind: 'idle' } | { kind: 'saving'; done: number; total: number } | { kind: 'failed'; failed: number }

/** ③ 의 한 자리 — 어느 촬영지가 나는가(사진이 안 왔으면 날지 않고 수만 오른다). */
interface Slot {
  place: number
  fly: boolean
}

const reducedMotion = (): boolean =>
  typeof window !== 'undefined' && window.matchMedia?.('(prefers-reduced-motion: reduce)').matches === true

const thumbOf = (p: PlaceSummary) => smallImage(p.imageThumbnailUrl, p.imageUrl)
/** 사진을 미리 받을 수 있는 환경인가(jsdom 은 사진을 받지 않는다 — 그때는 주소를 그대로 그린다). */
const canPreload = (): boolean => typeof Image !== 'undefined' && typeof navigator !== 'undefined' && !/jsdom/i.test(navigator.userAgent)

export function LandingCover({ contentId, onRest, onClose, onBeat, onKeep }: {
  contentId: number
  onRest?: () => void
  onClose: (how: IntroClose) => void
  /** 박자가 바뀌었다(IntroShell 이 `landing(beat)` 을 보내고 떠날 때의 박자로 적는다). */
  onBeat?: (beat: Beat) => void
  /** 담기를 눌렀다 — 이 뒤로는 떠나도 `landing(leave)` 를 보내지 않는다. */
  onKeep?: () => void
}): JSX.Element {
  useLang()
  const [ready, setReady] = useState<Ready | null>(null)
  /** 네 박자의 시계 — null 이면 아직 시작하지 않았다. */
  const [elapsed, setElapsed] = useState<number | null>(null)
  const [save, setSave] = useState<SaveState>({ kind: 'idle' })
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

  // ---- 사진 받기 순서: 포스터 → 첫 썸네일 6장 → 나머지.
  const preloadGroups = useMemo(() => {
    if (!ready) return []
    const thumbs = ready.places.map(thumbOf).filter((u): u is string => !!u)
    return [poster ? [poster] : [], thumbs.slice(0, FIRST_THUMBS), thumbs.slice(FIRST_THUMBS)].filter((g) => g.length > 0)
  }, [ready, poster])
  const [preloading] = useState(canPreload)
  const loaded = useOrderedPreload(preloadGroups)
  const has = useCallback((url: string | null) => url !== null && (!preloading || loaded.has(url)), [loaded, preloading])

  // ---- 시계 — 그림이 바뀌는 때만 넘긴다: ② · ③ 의 시작, 썸네일이 튀어나오는 때·주머니에 들어가는 때, ④.
  const startClock = useCallback(
    (from: number) => {
      timers.current.forEach(clearTimeout)
      timers.current = []
      setElapsed(from)
      if (from >= timeline.total) return
      const marks = new Set<number>([timeline.dropMs, spillStart(timeline), timeline.total])
      for (let i = 0; i < n; i++) {
        marks.add(launchAt(i, timeline))
        marks.add(launchAt(i, timeline) + FLIGHT_MS)
      }
      for (const at of [...marks].sort((a, b) => a - b)) {
        if (at <= from) continue
        timers.current.push(setTimeout(() => setElapsed(at), at - from))
      }
    },
    [n, timeline],
  )
  useEffect(() => () => timers.current.forEach(clearTimeout), [])

  // 받으면 곧바로 ① — 해태 그림은 덮개 덩어리에 실려 와 기다릴 것이 없다.
  useEffect(() => {
    if (!ready || elapsed !== null) return
    once('view')
    startClock(reduced.current ? timeline.total : 0)
  }, [ready, elapsed, once, startClock, timeline.total])

  const beat = elapsed === null ? null : beatAt(elapsed, timeline)
  const rest = beat === 'rest'
  const pocketed = elapsed === null ? 0 : pocketedCount(elapsed, timeline, n)
  const launched = elapsed === null || beat === 'drop' || beat === 'poster' ? 0 : rest ? n : countLaunched(elapsed, timeline, n)
  useEffect(() => {
    if (beat !== null) onBeat?.(beat)
  }, [beat, onBeat])
  useEffect(() => {
    if (!rest) return
    once('rest')
    onRest?.()
  }, [rest, once, onRest])

  // ---- ③ 의 자리 — 사진이 온 촬영지부터 난다. 안 온 것은 뒤로 미루고, 남은 것이 다 안 왔으면 수만 오른다.
  const [slots, setSlots] = useState<Slot[]>([])
  useEffect(() => {
    if (!ready || rest || slots.length >= launched) return
    const next = [...slots]
    const flying = () => next.filter((s) => s.fly).length
    while (next.length < launched) {
      const used = new Set(next.map((s) => s.place))
      const left = ready.places.map((_, i) => i).filter((i) => !used.has(i))
      const photoReady = left.find((i) => has(thumbOf(ready.places[i]!)))
      if (photoReady !== undefined && flying() < FLY_MAX) next.push({ place: photoReady, fly: true })
      else next.push({ place: left[left.length - 1]!, fly: false })
    }
    setSlots(next)
  }, [ready, launched, rest, slots, has])

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
  // ③ 에서 해태가 고개를 번갈아 돌려 튀어나오는 썸네일을 따라 본다.
  const look = beat === 'spill' && launched > 0 ? (launched % 2 === 0 ? -1 : 1) : 0
  const vars = {
    '--stage-h': `${g.h}px`,
    '--hero-to-poster': `${g.posterTop - 30 - g.heroCy}px`,
    '--poster-from': `${g.h - g.posterTop + 40}px`,
  } as CSSProperties

  return (
    <div
      ref={stage}
      className="st-landing st-film"
      data-beat={beat}
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
            <img className="st-film__sit" src={haetaeSit} alt="" draggable={false} />
            <img className="st-film__joy" src={haetaeJoy} alt="" draggable={false} />
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

      {/* ②·③ — 포스터 윗변 뒤에서 빼꼼(iOS 시작 화면과 같은 동작). 머리는 윗변에서 잘리고, 앞발은 윗변에 걸친다. */}
      {(beat === 'poster' || beat === 'spill' || beat === 'rest') && (
        <>
          <div className="st-film__peek" aria-hidden style={{ left: g.w / 2 - 100, top: g.posterTop - 122 }}>
            <div className="st-film__look" style={{ transform: `rotate(${look * 9}deg)` }}>
              <img className="st-film__head" src={haetaePeek} alt="" draggable={false} />
            </div>
          </div>
          <img className="st-film__paw st-film__paw--l" src={pawLeft} alt="" aria-hidden draggable={false} style={{ left: g.w / 2 - 34 - 21, top: g.posterTop - 16 }} />
          <img className="st-film__paw st-film__paw--r" src={pawRight} alt="" aria-hidden draggable={false} style={{ left: g.w / 2 + 34 - 21, top: g.posterTop - 16 }} />
        </>
      )}

      {/* ④ — 핀을 든 해태가 포스터 윗변에서 올라온다. */}
      {rest && (
        <img className="st-film__pin" src={haetaePin} alt="" aria-hidden draggable={false} style={{ left: g.pinLeft, top: g.posterTop + 26 - g.pinH, width: g.pinW, height: g.pinH }} />
      )}

      {/* 말풍선 — ② 「저기 어디야?」, ④ 「{N}곳, 전부 {도시}에 있어요!」 */}
      {beat !== 'drop' && (
        <div
          key={rest ? 'rest' : 'ask'}
          className={rest ? 'st-film__bubble st-film__bubble--rest' : 'st-film__bubble'}
          style={rest ? { right: g.w - g.pinLeft - 12, bottom: g.h - (g.posterTop - 30), width: 'max-content', maxWidth: g.pinLeft + 12 - 16 } : { right: g.w / 2 + 52, bottom: g.h - (g.posterTop - 66) }}
        >
          {rest ? (
            <Txt as="h1" size={17} weight="heavy" style={{ margin: 0, color: '#2a1856', lineHeight: 1.25 }}>{restLine}</Txt>
          ) : (
            <Txt size={16} weight="heavy" style={{ color: '#2a1856' }}>{say('저기 어디야?', 'Where is this?')}</Txt>
          )}
        </div>
      )}

      {/* ③ — 포스터에서 튀어나와 주머니로 나는 썸네일 */}
      {beat === 'spill' &&
        slots.slice(0, launched).map((s, k) => {
          if (!s.fly || elapsed === null) return null
          const at = launchAt(k, timeline)
          if (elapsed >= at + FLIGHT_MS) return null
          const p = places[s.place]!
          return <Flight key={p.id} k={k} url={thumbOf(p)!} name={p.name} g={g} delayMs={Math.max(0, at - elapsed)} />
        })}

      {/* 「내 지도」 주머니 — ③ 에서 나타나 수가 오른다. 담을 때 조각이 여기로 내려앉는다. */}
      {(beat === 'spill' || rest) && (
        <Pouch count={rest ? n : pocketed} top={g.pouchTop} restTop={g.pouchRestTop} rest={rest} />
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
  return n === 1 ? say('이 1곳 내 지도에 담기', 'Keep this 1 on my map') : say(`이 ${n}곳 내 지도에 담기`, `Keep these ${n} on my map`)
}

/** 튀어나온 썸네일 수 — ③ 에서 launchAt(i) 가 지난 것. */
function countLaunched(elapsed: number, t: LandingTimeline, n: number): number {
  let c = 0
  while (c < n && launchAt(c, t) <= elapsed) c++
  return c
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
  pinW: number
  pinH: number
  /** ④ 의 핀 해태 왼쪽 — 줄어든 포스터의 오른쪽 윗모서리에 걸친다(말풍선이 왼쪽에 넉넉히 서게). */
  pinLeft: number
  /** 주머니 윗변 — ③ 과 ④(단추 위로 올라선다). */
  pouchTop: number
  pouchRestTop: number
}

/** 무대 크기 → 자리. 포스터는 5:7, 폭은 화면의 56%(최대 220, 낮은 화면에서는 더 작게). */
function geometry(w: number, h: number): Geometry {
  const sitW = Math.round(Math.min(150, w * 0.37))
  const sitH = Math.round((sitW * 582) / 525)
  const posterW = Math.round(Math.max(150, Math.min(220, w * 0.56, ((h - 380) * 5) / 7)))
  const posterH = Math.round((posterW * 7) / 5)
  const posterTop = Math.round(Math.max(150, h * 0.25))
  const pinW = 112
  const pinH = Math.round((pinW * 397) / 355)
  return {
    w,
    h,
    sitW,
    sitH,
    heroCy: Math.round(h / 2 + 14 - sitH / 2),
    posterW,
    posterH,
    posterTop,
    pinW,
    pinH,
    pinLeft: Math.round(w / 2 + (posterW * 0.82) / 2 - pinW * 0.6),
    pouchTop: h - 160,
    pouchRestTop: h - 250,
  }
}

// ---------------------------------------------------------------- 부품

/** 포스터에서 주머니로 나는 썸네일 한 장 — 포물선(2차 베지어를 9점으로 나눈 Web Animations). 그리는 수단이 없으면 제자리에 둔다. */
function Flight({ k, url, name, g, delayMs }: { k: number; url: string; name: string; g: Geometry; delayMs: number }) {
  const ref = useRef<HTMLDivElement>(null)
  const side = (k % 2 === 0 ? 1 : -1) * (0.6 + ((k * 7) % 5) / 6)
  const start = { x: g.w / 2 + side * 26, y: g.posterTop + g.posterH * 0.42 }
  const end = { x: g.w / 2, y: g.pouchTop + 30 }
  useEffect(() => {
    const el = ref.current
    if (!el || typeof el.animate !== 'function') return
    const ctrl = { x: g.w / 2 + side * Math.min(150, g.w * 0.38), y: Math.min(start.y, end.y) - 150 }
    const frames: Keyframe[] = []
    for (let i = 0; i <= 8; i++) {
      const t = i / 8
      const x = (1 - t) * (1 - t) * start.x + 2 * (1 - t) * t * ctrl.x + t * t * end.x
      const y = (1 - t) * (1 - t) * start.y + 2 * (1 - t) * t * ctrl.y + t * t * end.y
      const scale = t < 0.2 ? 0.35 + (t / 0.2) * 0.75 : 1.1 - ((t - 0.2) / 0.8) * 0.8
      frames.push({ transform: `translate(${x - start.x}px, ${y - start.y}px) scale(${scale.toFixed(3)}) rotate(${(side * 14 * t).toFixed(1)}deg)`, opacity: t > 0.92 ? 0 : 1, offset: t })
    }
    const a = el.animate(frames, { duration: FLIGHT_MS, delay: delayMs, easing: 'linear', fill: 'both' })
    return () => a.cancel()
    // 한 장은 한 번만 난다 — 자리·시간이 그 뒤에 바뀌어도 다시 시작하지 않는다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])
  return (
    <div ref={ref} className="st-film__thumb" aria-hidden style={{ left: start.x - 31, top: start.y - 31 }}>
      <img src={url} alt="" referrerPolicy="no-referrer" draggable={false} />
      <span>{name}</span>
    </div>
  )
}

/** 「내 지도」 주머니 — 가방 모양 + 수 배지. 수가 오르거나 무엇이 내려앉으면 통 튄다. 담을 때 날아오는 조각의 과녁. */
function Pouch({ count, top, restTop, rest }: { count: number; top: number; restTop: number; rest: boolean }) {
  const landed = useStore(myMapLandStore, (x) => x)
  return (
    <div className="st-film__pouch" style={{ top, transform: rest ? `translateY(${restTop - top}px)` : undefined }} {...{ [MY_MAP_TARGET]: '' }} aria-hidden>
      <div key={`${count}-${landed}`} className={count > 0 || landed > 0 ? 'st-film__bag st-film__bag--bump' : 'st-film__bag'}>
        <span className="st-film__bag-flap" />
        <Icon name="map" size={22} weight="bold" color="#5c4ad6" />
        <span className="st-film__bag-count">{count}</span>
      </div>
      <span className="st-film__bag-label">{say('내 지도', 'My map')}</span>
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
