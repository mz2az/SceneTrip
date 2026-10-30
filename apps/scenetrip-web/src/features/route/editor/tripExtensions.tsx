/*
 * 코스 편집 화면에 **여행 안내·가이드 챗봇·주변 편의시설**을 잇는다 — iOS RouteEditorTrip · RouteEditorGuide · RouteEditorAmbient (+ RouteGuideFloat).
 *
 * 편집 화면(`RouteEditor`)은 계획 모드만 안다. 안내·챗봇·편의시설은 `editorExtensions` 로 끼어들고, 이 파일이 그 등록의 전부다
 * (모듈을 읽으면 `registerEditorExtensions` 가 불린다 — `RouteCover` 가 이 파일을 가져온다).
 *
 *   Overlay     해태 동그라미 · 가이드 서랍 · 도착 알림 카드 · 스탬프 · 카카오 위치 띠 · 지도 위 층(주변 점·AI 장소·경로선) · 고른 곳의 정보 카드
 *   PanelTop    안내 띠(가는 중) — 일정 목록 맨 위
 *   PanelChips  갈래 칩(전체 · 음식점 · … · AI 장소)
 *   BottomBar   안내 중에는 이 줄이 안내의 것(도착함 / 다음으로 / 안내 끝)
 *   rowExtras   줄마다 「길찾기」·「안내 중」
 *
 * 유료 호출(`/navigation/next-leg`·`/guide/chat`)은 사람이 누를 때만 나간다 — 화면이 저절로 부르지 않는다.
 */
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { api, installHeader, ok } from '@/api/client'
import { consumePending, pendingStore } from '@/app/tabRouter'
import { tr } from '@/i18n'
import { PhotoGallery } from '@/features/reviews/photoGallery'
import { bookOf } from '@/features/reviews/photoGalleryRules'
import { GalleryViewer } from '@/features/reviews/PhotoViewers'
import { ReviewsSheet } from '@/features/reviews/ReviewsSheet'
import { cartActions, useCart } from '@/state/cart'
import { footprintStore, kilometersOf } from '@/state/footprints'
import { createStore, useStore } from '@/state/storage'
import { nextUnvisited } from '../availability'
import { type GuidePlan } from '../draft'
import { GuidePanel } from '../guide/GuidePanel'
import { GuideFloatingChip } from '../guide/GuideChip'
import { GuideSheet } from '../guide/GuideSheet'
import { planOf, revising } from '../guide/guidePlan'
import { type GuideContext, type ContextSpot, type ContextTrip, buildContext } from '../guide/guideRequest'
import { type GuideSession, sharedGuide, useGuideState } from '../guide/guideSession'
import { guideEntryPlacement, parseDirective, resolveAnchor } from '../guide/guideRules'
import type { GuideAnswer } from '../guide/guideSender'
import { distanceMeters } from '../nav/geo'
import { KakaoNotice } from '../nav/KakaoNotice'
import { useMapOverlay } from '../nav/useMapOverlay'
import { StampOverlay } from '../nav/StampOverlay'
import { ArrivalNotice, TripBanner } from '../nav/TripBanner'
import { TripControls } from '../nav/TripControls'
import { type TripState, type TripTarget, TripSession, useTripState } from '../nav/tripSession'
import { PlaceCard } from '../poi/PlaceCard'
import type { CardPhoto } from '../poi/poiCard'
import { PoiChips } from '../poi/PoiChips'
import { type PlaceTarget, courseEntry, drawnPlaceIds, isSameSpot, rowOf, targetOf, withoutDotsUnderPins } from '../poi/placePoiLink'
import { POI_GROUPS, type PoiGroup } from '../poi/poiGroup'
import { type RoutePlace, dedupeKey, placeGroup, placeKey, serverIdOf } from '../poi/poiPlace'
import { useAmbientPois } from '../poi/useAmbientPois'
import { type RouteCourse, type RouteStop, canNavigate, makePoiStop, makeStop, placeIdOf, poiIdOf } from '../models'
import { addStops, replaceDay } from './courseEdit'
import { type EditorHost, registerEditorExtensions } from './editorExtensions'

// ---------------------------------------------------------------- 공유 상태

/** 편집 화면 하나가 쓰는 안내 한 줄 — 화면 밖에 둔다(여행 안내는 편집 화면의 지도가 곧 여행 지도라 화면 밖 객체가 든다). */
export const tripSession = new TripSession()

interface UiState {
  /** 가이드 서랍이 열려 있는가. */
  guideOpen: boolean
  /** 켜 둔 편의시설 갈래. 처음엔 다 켠다. */
  groupsOn: ReadonlySet<PoiGroup>
  /** 「AI 장소」 칩. */
  aiOn: boolean
  /** 지도가 멈춘 뒤 받은 주변 편의시설. 갈래 칩이 센다. */
  ambient: readonly RoutePlace[]
  /** 도착 알림 카드의 높이(px). */
  arrivalHeight: number
  /** 지도 중심 — 챗봇의 「지도 중심 주변」 기준. */
  mapCenter: { latitude: number; longitude: number } | null
  /** 챗봇 기준이 지도 중심인가(내 위치가 있어도). */
  preferMap: boolean
  /** 정보 카드를 다시 읽으라는 신호(리뷰·사진을 닫은 뒤). */
  reloadToken: number
  /** 서버에 방문 기록을 남기는 중인 항목 id. */
  visitSaving: ReadonlySet<number>
  /** 남기지 못한 항목 id. */
  visitFailed: ReadonlySet<number>
}

const ui = createStore<UiState>({
  guideOpen: false, groupsOn: new Set(POI_GROUPS), aiOn: true, ambient: [], arrivalHeight: 0, mapCenter: null, preferMap: false, reloadToken: 0,
  visitSaving: new Set(), visitFailed: new Set(),
})
const patchUi = (p: Partial<UiState>) => ui.set((s) => ({ ...s, ...p }))

// ---------------------------------------------------------------- 방문 기록 (iOS VisitSync)

/** 스탬프가 찍혔다 — 코스 상태와 서버 둘 다에 「다녀옴」. 저장 전 코스(서버 id 없음)는 화면에만 남는다. */
export async function markVisited(host: Pick<EditorHost, 'course' | 'setCourse'>, stop: RouteStop, put = putVisit): Promise<void> {
  const markLocally = () => host.setCourse((c) => ({ ...c, days: c.days.map((d) => ({ ...d, stops: d.stops.map((s) => (s.uid === stop.uid ? { ...s, visited: true } : s)) })) }))
  const courseId = host.course.serverId
  const itemId = stop.serverItemId
  if (courseId === null || itemId === null) return markLocally()
  const was = ui.get()
  patchUi({ visitSaving: new Set([...was.visitSaving, itemId]), visitFailed: without(was.visitFailed, itemId) })
  try {
    await put(courseId, itemId)
    markLocally()
    patchUi({ visitSaving: without(ui.get().visitSaving, itemId) })
  } catch {
    patchUi({ visitSaving: without(ui.get().visitSaving, itemId), visitFailed: new Set([...ui.get().visitFailed, itemId]) })
  }
}

async function putVisit(courseId: number, itemId: number): Promise<void> {
  await ok(api.PUT('/courses/{courseId}/items/{itemId}/visit', { params: { header: installHeader(), path: { courseId, itemId } }, body: { visited: true } }))
}

const without = (set: ReadonlySet<number>, id: number): Set<number> => new Set([...set].filter((x) => x !== id))

// ---------------------------------------------------------------- 길찾기 시작 (iOS RouteEditorView.startTrip)

/**
 * 이 성지로 안내를 켠다. 번호는 그 일차 안의 순서다. **방금 담은 곳은 먼저 저장한다** — 길찾기 계약은 목적지를 저장된 코스 항목으로만 받는다
 * (챗봇이 찾아 준 가게를 담고 곧바로 「다음 · 2번으로 길찾기」를 누르면 막혔다, 2026-09-17). 저장이 실패하면 시작하지 않는다.
 */
export async function startTripTo(host: Pick<EditorHost, 'course' | 'dayIndex' | 'stops' | 'saveNow' | 'setFocusedUid' | 'openInfo'>, stop: RouteStop, session: TripSession = tripSession): Promise<void> {
  // iOS `guard stop.canNavigate else { pickedStop = stop; return }` — 갈 수 없는 곳이면 그 곳의 정보 카드를 연다.
  if (!canNavigate(stop)) {
    host.openInfo?.(stop)
    return
  }
  let target = stop
  let stops = host.stops
  let courseId = host.course.serverId
  if (stop.serverItemId === null && courseId !== null && !stop.placeMissing) {
    const saved = await host.saveNow()
    if (!saved) return
    courseId = saved.serverId
    stops = saved.days[host.dayIndex]?.stops ?? []
    target = rowOf(refOf(stop), stops.map((s) => ({ ...refOf(s), row: s })))?.row ?? stop
  }
  const number = Math.max(0, stops.findIndex((s) => s.uid === target.uid)) + 1
  host.setFocusedUid(null)
  sharedGuide.setPicked(null)
  session.start(target as TripTarget, number, courseId)
}

const refOf = (s: RouteStop) => ({ placeId: placeIdOf(s), poiId: poiIdOf(s), name: s.place.name, latitude: s.place.latitude, longitude: s.place.longitude })

// ---------------------------------------------------------------- 가이드에게 주는 화면 상태 (iOS guideContext · guideTrip)

export function guideContextOf(p: { course: RouteCourse; stops: readonly RouteStop[]; dayIndex: number; focused: RouteStop | null; trip: TripState; tripNumber: number }): GuideContext {
  const spot = (s: RouteStop, number: number, visited?: boolean): ContextSpot => ({ number, name: s.place.name, kind: s.place.type ?? null, latitude: s.place.latitude, longitude: s.place.longitude, ...(visited === undefined ? {} : { visited }) })
  let tripState: ContextTrip | null = null
  if (p.course.isRunning) {
    const meters = p.trip.target && p.trip.here ? Math.round(distanceMeters(p.trip.here, p.trip.target.place)) : null
    // 지도의 발자국과 같은 창(최근 하루)으로 잰다. 소수 첫째 자리 — 계약이 그렇게 적었다.
    const since = Date.now() - 24 * 3600 * 1000
    const recent = footprintStore.get().points.filter((pt) => pt.at >= since)
    tripState = {
      phase: p.trip.phase === 'idle' ? 'plan' : p.trip.phase,
      course: p.course.title,
      day: p.dayIndex + 1,
      days: p.course.days.length,
      targetNumber: p.trip.target ? p.tripNumber : null,
      targetMeters: meters,
      walkedKilometers: Math.round(kilometersOf(recent) * 10) / 10,
    }
  }
  return buildContext({
    stops: p.stops.map((s, i) => spot(s, i + 1, s.visited)),
    picked: p.focused ? spot(p.focused, 0) : null,
    trip: tripState,
    plan: planOf(p.course),
  })
}

// ---------------------------------------------------------------- 챗봇 답을 화면에 적용 (iOS applyGuideAnswer)

export interface AnswerHost {
  course: RouteCourse
  setCourse(next: RouteCourse): void
  setDayIndex(i: number): void
  setFocusedUid(uid: string | null): void
  fit(): void
  closeGuide(): void
  refreshCart(): void
  setPicked(place: RoutePlace | null): void
  showOnlyAiPlaces(): void
}

/**
 * 답에 실린 `effects` 와 `ui` 를 화면에 적용한다. 모르는 명령은 무시한다 — 에이전트가 명령을 늘려도 옛 앱이 깨지지 않게(계약 `GuideEffect`·`GuideUiDirective`).
 * `plan.*` 은 편집 사본을 갈아 끼운다. **저장하지 않는다** — 저장은 「완료」의 PUT 뿐이라 「취소」가 공짜로 남는다. `cart.*` 는 서버가 이미 저장했으니 장바구니만 다시 읽는다.
 */
export function applyGuideAnswer(answer: GuideAnswer, host: AnswerHost): void {
  let working = host.course
  for (const effect of answer.effects) {
    if (effect.op === 'plan.draft' || effect.op === 'plan.revise' || effect.op === 'plan.move') {
      if (!effect.plan) continue
      working = revising(working, effect.plan as GuidePlan)
      host.setCourse(working)
      host.setDayIndex(0)
      host.setFocusedUid(null)
      host.fit()
    } else if (effect.op === 'cart.add' || effect.op === 'cart.remove') {
      host.refreshCart()
    }
  }
  const openDay = (day: number) => {
    if (working.days.length === 0) return
    host.setDayIndex(Math.min(Math.max(day - 1, 0), working.days.length - 1))
    host.fit()
  }
  for (const raw of answer.ui) {
    const d = parseDirective(raw)
    if (!d) continue
    switch (d.kind) {
      case 'mapFocus':
      case 'routeDraw':
        // 그 핀들이 다 보이게. 빨간 미리보기 핀을 따로 찍지 않는다 — 같은 곳이 이미 「AI 장소」 해태 핀으로 그려진다(2026-09-17).
        host.setPicked(null)
        host.fit()
        break
      case 'placeCard':
        host.setPicked(answer.places.find((p) => serverIdOf(p) === d.placeId) ?? null)
        break
      case 'courseOpen':
        openDay(d.day)
        break
      case 'courseFocus': {
        openDay(d.day)
        const names = new Set(d.changed)
        const day = working.days[Math.min(Math.max(d.day - 1, 0), working.days.length - 1)]
        host.setFocusedUid(day?.stops.find((s) => names.has(s.place.name))?.uid ?? null)
        break
      }
      case 'sheetCollapse':
        host.closeGuide()
        break
    }
  }
  // AI 가 장소를 찾아 왔으면 지도에는 그것만 남긴다(2026-09-17 사용자 결정) — 음식점·명소 갈래는 끄고 「AI 장소」만 켠다. 갈래는 칩으로 다시 켤 수 있다.
  if (answer.places.length > 0) host.showOnlyAiPlaces()
}

// ---------------------------------------------------------------- 담기 (iOS addGuidePlace)

/** 가이드가 찾아 준 곳·주변 편의시설을 코스에 담을 줄로. 촬영지는 촬영지로, 촬영지와 같은 곳인 편의시설도 촬영지로, 그 밖의 편의시설은 편의시설로. */
export function stopForPlace(place: RoutePlace): RouteStop {
  const entry = courseEntry(place)
  if (entry.kind === 'place' && entry.id !== null) {
    return makeStop({ id: entry.id, name: entry.name, type: entry.category, address: entry.address, latitude: entry.latitude, longitude: entry.longitude }, { type: 'place' })
  }
  if (entry.kind === 'poi' && entry.id !== null) {
    return { ...makePoiStop(entry.id, { name: entry.name, category: entry.category, address: entry.address, latitude: entry.latitude, longitude: entry.longitude }), poiText: entry.poiText }
  }
  return makeStop({ id: -(Date.now() % 1_000_000_000), name: entry.name, type: entry.category, address: entry.address, latitude: entry.latitude, longitude: entry.longitude }, { type: 'pin' })
}

// ---------------------------------------------------------------- 화면 부품

function useGuideAnchor(host: EditorHost) {
  const trip = useTripState(tripSession)
  const { mapCenter, preferMap } = useStore(ui, (s) => s)
  const current = host.here ?? trip.here
  return useMemo(() => resolveAnchor({ current, mapCenter, preferMap, mapSource: 'userSelected' }), [current, mapCenter, preferMap])
}

/** 편집 화면이 열려 있는 동안만 선 한 벌 — 지도 층·챗봇·안내·정보 카드. 화면을 그리지 않고 상태를 잇는 일도 한다. */
function EditorTripLayer({ host }: { host: EditorHost }) {
  const trip = useTripState(tripSession)
  const guideState = useGuideState(sharedGuide)
  const state = useStore(ui, (s) => s)
  const cart = useCart()
  const hostRef = useRef(host)
  hostRef.current = host
  const anchor = useGuideAnchor(host)
  // 정보 카드의 별점 줄·사진 줄 — 리뷰 시트와 사진첩이 이 위에 올라온다.
  const [reviewing, setReviewing] = useState<{ target: PlaceTarget; title: string } | null>(null)
  const [viewing, setViewing] = useState<{ gallery: PhotoGallery; title: string; index: number } | null>(null)
  const openReviews = useCallback((target: PlaceTarget, title: string) => setReviewing({ target, title }), [])
  const openPhotos = useCallback((photos: CardPhoto[], index: number, title: string) => {
    const picked = sharedGuide.state.picked
    const target = picked ? targetOf(picked) : null
    if (!target) return
    // 카드가 이미 받은 사진으로 사진첩을 먼저 채운다 — 넘기다 끝이 가까우면 사진첩이 다음 쪽을 받는다.
    const gallery = new PhotoGallery(target)
    gallery.show(bookOf(photos, photos.length, []), target, gallery.generation)
    setViewing({ gallery, title, index })
  }, [])

  // 대화는 코스의 것이다 — 다른 코스를 열면 지운다. 저장으로 서버 id 를 얻은 같은 코스는 열쇠만 바꾼다.
  const courseKey = host.course.serverId !== null ? `course:${host.course.serverId}` : `draft:${host.course.uid}`
  const lastUid = useRef<{ uid: string; key: string } | null>(null)
  useEffect(() => {
    const prev = lastUid.current
    if (prev && prev.uid === host.course.uid && prev.key !== courseKey) sharedGuide.rekey(courseKey)
    else sharedGuide.bind(courseKey)
    lastUid.current = { uid: host.course.uid, key: courseKey }
  }, [courseKey, host.course.uid])

  // 홈 「이어서 길찾기」 — 코스가 열리면 첫 미방문 성지로 안내를 켠다. 표시는 한 번 읽고 끈다(iOS runPendingTripStart).
  const tripStartWanted = useStore(pendingStore, (p) => p.tripStart)
  useEffect(() => {
    if (!tripStartWanted || host.stops.length === 0) return
    consumePending('tripStart')
    const first = nextUnvisited(host.stops)?.stop ?? host.stops.find(canNavigate)
    if (first) void startTripTo(hostRef.current, first)
  }, [tripStartWanted, host.stops])

  // 스탬프가 찍히면 코스 상태와 서버에 「다녀옴」.
  useEffect(() => {
    tripSession.onArrived = (target) => {
      const stop = hostRef.current.stops.find((s) => s.uid === target.uid)
      if (stop) void markVisited(hostRef.current, stop)
    }
    return () => {
      tripSession.onArrived = null
    }
  }, [])

  // 지도 중심 — 챗봇의 「지도 중심 주변」 기준.
  useEffect(() => {
    const map = host.map
    if (!map) return
    const update = () => {
      const c = map.getCenter()
      patchUi({ mapCenter: { latitude: c.lat, longitude: c.lng } })
    }
    update()
    return map.on('moveend', update)
  }, [host.map])

  // 주변 편의시설 — 안내 중에는 점을 다 끈다(경로선이 주인공). 기기 위치에서 비롯한 지도는 위치 동의가 있어야 부른다.
  const ambient = useAmbientPois({ map: host.map, enabled: !trip.target, source: 'userSelected', locationAllowed: () => true, bottomInset: host.panelHeight })
  useEffect(() => {
    patchUi({ ambient: ambient.places })
  }, [ambient.places])

  // 챗봇의 답 — 같은 답을 두 번 적용하지 않는다.
  const applied = useRef(guideState.answerTick)
  useEffect(() => {
    if (guideState.answerTick === applied.current) return
    applied.current = guideState.answerTick
    const answer = guideState.lastAnswer
    if (!answer) return
    const h = hostRef.current
    applyGuideAnswer(answer, {
      course: h.course,
      setCourse: (c) => h.setCourse(c),
      setDayIndex: h.setDayIndex,
      setFocusedUid: h.setFocusedUid,
      fit: h.fit,
      closeGuide: () => patchUi({ guideOpen: false }),
      refreshCart: () => void cartActions.refresh(),
      setPicked: (p) => sharedGuide.setPicked(p),
      showOnlyAiPlaces: () => patchUi({ groupsOn: new Set(), aiOn: true }),
    })
  }, [guideState.answerTick, guideState.lastAnswer])

  // ---- 지도 위에 얹는 것
  const dayStops = host.stops
  const taken = useMemo(() => new Set(host.course.days.flatMap((d) => d.stops).map((s) => dedupeKey(s.place.name, s.place.latitude, s.place.longitude))), [host.course])
  const takenPoi = useMemo(() => new Set(host.course.days.flatMap((d) => d.stops).map(poiIdOf).filter((v): v is number => v !== null)), [host.course])
  const guidePlaces = useMemo(() => (state.aiOn ? guideState.places.filter((p) => !host.course.days.flatMap((d) => d.stops).some((s) => isSameSpot(p, refOf(s)))) : []), [state.aiOn, guideState.places, host.course])
  const shown = useMemo(() => new Set(guideState.places.map(placeKey)), [guideState.places])
  const drawn = useMemo(
    () => drawnPlaceIds({ stops: dayStops.map((s) => s.place), guidePlaceIds: guidePlaces.map((p) => placeIdOfGuide(p)).filter((v): v is number => v !== null) }),
    [dayStops, guidePlaces],
  )
  const visibleAmbient = useMemo(
    () => withoutDotsUnderPins(state.ambient.filter((p) => state.groupsOn.has(placeGroup(p)) && !shown.has(placeKey(p)) && !taken.has(placeKey(p))), drawn, takenPoi),
    [state.ambient, state.groupsOn, shown, taken, drawn, takenPoi],
  )
  const overlayState = useMemo(
    () => ({
      ambient: visibleAmbient,
      guidePlaces,
      picked: guideState.picked,
      legs: trip.result?.legs ?? [],
      here: trip.here,
      target: trip.target ? { latitude: trip.target.place.latitude, longitude: trip.target.place.longitude } : null,
      preview: previewOn(trip, host.stops),
    }),
    [visibleAmbient, guidePlaces, guideState.picked, trip, host.stops],
  )
  useMapOverlay(host.map, overlayState, { onSelectPlace: (p) => sharedGuide.setPicked(p) })

  // 카메라 — 안내 경로가 오거나 「현재위치로」를 눌렀을 때 「나와 목적지」에 맞춘다.
  useEffect(() => {
    const map = host.map
    if (!map || trip.recenterTick === 0 || !trip.target) return
    const pts = [{ lat: trip.target.place.latitude, lng: trip.target.place.longitude }]
    if (trip.here) pts.push({ lat: trip.here.latitude, lng: trip.here.longitude })
    if (pts.length > 1) {
      const lats = pts.map((c) => c.lat)
      const lngs = pts.map((c) => c.lng)
      const bounds = { southWest: { lat: Math.min(...lats), lng: Math.min(...lngs) }, northEast: { lat: Math.max(...lats), lng: Math.max(...lngs) } }
      map.fitBounds(bounds, { paddingTopLeft: [40, 40], paddingBottomRight: [40, host.panelHeight + 40], maxZoom: 17 })
    } else map.setView(pts[0], Math.max(map.getZoom(), 15))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [trip.recenterTick, host.map])

  // ---- 화면
  const session: GuideSession = sharedGuide
  const entry = guideEntryPlacement({ guiding: trip.phase === 'guiding', hasTarget: trip.target !== null, hasCard: host.cardOpen || guideState.picked !== null, panelOpen: state.guideOpen, pinning: host.pinning })
  const cardTop = !state.guideOpen && (guideState.picked !== null || host.cardOpen) ? CARD_RESERVE : null
  const context = useMemo(
    () =>
      guideContextOf({
        course: host.course, stops: host.stops, dayIndex: host.dayIndex, focused: host.stops.find((s) => s.uid === host.focusedUid) ?? null, trip,
        tripNumber: tripSession.targetNumber,
      }),
    [host.course, host.stops, host.dayIndex, host.focusedUid, trip],
  )
  const nextNumber = nextUnvisited(host.stops)
  const arrivedHeaderProps = { session: tripSession, state: trip, next: nextNumber ? { number: nextNumber.number, name: nextNumber.stop.place.name } : null }
  const picked = guideState.picked
  const pickedStop = picked ? host.stops.find((s) => isSameSpot(picked, refOf(s))) ?? null : null
  const addPlace = useCallback(
    (place: RoutePlace) => {
      const h = hostRef.current
      const res = addStops(h.course, h.dayIndex, [stopForPlace(place)], { asNext: true, target: tripSession.state.target as RouteStop | null, arrived: tripSession.state.phase === 'arrived' })
      if (res.added === 0) return
      h.setCourse(res.course)
      h.fit()
    },
    [],
  )
  const removePlace = useCallback((place: RoutePlace) => {
    const h = hostRef.current
    const target = h.stops.find((s) => isSameSpot(place, refOf(s)))
    if (!target) return
    h.setCourse(replaceDay(h.course, h.dayIndex, (d) => ({ ...d, stops: d.stops.filter((s) => s.uid !== target.uid) })))
  }, [])
  const isAdded = useCallback((place: RoutePlace) => hostRef.current.course.days.flatMap((d) => d.stops).some((s) => isSameSpot(place, refOf(s))), [])
  const reroute = host.course.isRunning
    ? (place: RoutePlace) => {
        const stop = hostRef.current.stops.find((s) => isSameSpot(place, refOf(s)))
        if (stop) void startTripTo(hostRef.current, stop)
      }
    : undefined

  return (
    <>
      {/* 도착 알림 — 지도 위 맨 위. 경로는 아래에, 알림만 위에(2026-09-03 사용자 결정). */}
      <div style={{ position: 'absolute', top: TOP_BAR_HEIGHT, left: 0, right: 0, zIndex: 25 }}>
        <ArrivalNotice {...arrivedHeaderProps} onHeight={(px) => px !== state.arrivalHeight && patchUi({ arrivalHeight: px })} visitNotice={visitNoticeFor(host.stops.find((s) => s.uid === trip.target?.uid) ?? null, state, host)} />
      </div>
      <KakaoNotice session={tripSession} state={trip} top={TOP_BAR_HEIGHT + 8} />

      {picked && !state.guideOpen && !host.cardOpen && (
        <div style={{ position: 'absolute', left: 12, right: 12, bottom: CARD_BOTTOM, zIndex: 20 }}>
          <PlaceCard
            place={picked}
            added={pickedStop !== null}
            onAdd={() => addPlace(picked)}
            onRemove={() => removePlace(picked)}
            onReroute={reroute && pickedStop && trip.target?.uid !== pickedStop.uid ? () => reroute(picked) : undefined}
            onClose={() => sharedGuide.setPicked(null)}
            reloadToken={state.reloadToken}
            onOpenReviews={openReviews}
            onOpenPhotos={openPhotos}
          />
        </div>
      )}

      <GuideFloatingChip hidden={entry !== 'floating'} cardTop={cardTop} onTap={() => patchUi({ guideOpen: true })} />
      <GuidePanel open={state.guideOpen}>
        <GuideSheet
          session={session}
          here={anchor?.spot ?? null}
          coordinateSource={anchor?.source ?? 'userSelected'}
          anchorDescription={anchor?.isMapCenter ? tr('현재 지도 중심 주변을 찾아 드립니다.') : tr('지도에 표시된 내 위치 주변을 찾아 드립니다.')}
          onChangeAnchor={state.mapCenter && (host.here ?? trip.here) ? () => patchUi({ preferMap: !state.preferMap }) : undefined}
          anchorActionTitle={anchor?.isMapCenter ? tr('내 위치 주변') : tr('지도 중심 주변')}
          cartPlaces={cart.items.map((i) => i.name)}
          onOpenCart={() => {
            patchUi({ guideOpen: false })
            host.openCart()
          }}
          context={context}
          onAdd={addPlace}
          isAdded={isAdded}
          onRemove={removePlace}
          onReroute={reroute}
          onOpenPlanner={() => {
            host.requestPlanner()
            patchUi({ guideOpen: false })
            host.requestClose()
          }}
          onClose={() => patchUi({ guideOpen: false })}
          cardProps={{ reloadToken: state.reloadToken, onOpenReviews: openReviews, onOpenPhotos: openPhotos }}
        />
      </GuidePanel>

      <ReviewsSheet
        open={reviewing !== null}
        onClose={() => setReviewing(null)}
        subject={reviewing?.target ?? { kind: 'place', id: 0 }}
        title={reviewing?.title ?? ''}
        onChanged={() => patchUi({ reloadToken: ui.get().reloadToken + 1 })}
      />
      {viewing && (
        <GalleryViewer
          gallery={viewing.gallery}
          open
          onClose={() => {
            setViewing(null)
            patchUi({ reloadToken: ui.get().reloadToken + 1 })
          }}
          index={viewing.index}
          onIndex={(index) => setViewing((v) => (v ? { ...v, index } : v))}
          reviews={{ kind: 'sheet', title: viewing.title, onChanged: () => patchUi({ reloadToken: ui.get().reloadToken + 1 }) }}
          entry="card"
        />
      )}

      {trip.stamped && trip.target && (
        <StampOverlay
          title={tripSession.targetNumber > 0 ? tr('%d번 성지 도착!', tripSession.targetNumber) : tr('도착!')}
          subtitle={trip.target.place.name}
          onDone={() => tripSession.stampDone()}
        />
      )}
    </>
  )
}

/** 지도 머리줄(취소 · 제목 · 저장)의 높이 — 도착 알림은 그 아래에 뜬다. */
const TOP_BAR_HEIGHT = 52
/** 해태 동그라미가 비켜야 할 정보 카드의 윗변(바닥에서). 카드는 대략 아래 단추 줄 위로 이만큼 올라온다. */
const CARD_RESERVE = 250
const CARD_BOTTOM = 70

/** 촬영지 id — 가이드가 찾아 준 곳 중 촬영지에서 온 것. */
function placeIdOfGuide(p: RoutePlace): number | null {
  return courseEntry(p).kind === 'place' ? courseEntry(p).id : null
}

/** 가는 중인데 경로가 아직 안 왔으면 「내 자리 → 목적지」, 도착해 다음을 고르기 전이면 다음 곳 — 실제 경로가 오면 끈다. */
function previewOn(trip: TripState, stops: readonly RouteStop[]): boolean {
  if (trip.phase === 'guiding') return trip.result === null && trip.target !== null
  if (trip.phase === 'arrived') return nextUnvisited(stops) !== null
  return false
}

/** 도착 직후 서버에 방문을 남기는 중이거나 못 남겼을 때의 한 줄. 아니면 undefined — 알림 카드가 기본 문구를 쓴다. */
function visitNoticeFor(stop: RouteStop | null, state: Pick<UiState, 'visitSaving' | 'visitFailed'>, host: EditorHost): React.ReactNode {
  const id = stop?.serverItemId ?? null
  if (!stop || id === null) return undefined
  if (state.visitSaving.has(id)) return <span style={{ fontSize: 12, color: 'var(--label-secondary)' }}>{tr('방문 기록을 저장하는 중이에요')}</span>
  if (state.visitFailed.has(id)) {
    return (
      <div style={{ display: 'flex', flexDirection: 'column', gap: 6, alignItems: 'flex-start' }}>
        <span style={{ fontSize: 12, color: 'var(--label-secondary)' }}>{tr('방문 기록을 저장하지 못했어요')}</span>
        <button type="button" onClick={() => void markVisited(host, stop)} style={{ fontSize: 12, fontWeight: 600, color: 'var(--accent)', minHeight: 44, background: 'none', border: 'none', cursor: 'pointer' }}>
          {tr('다시 저장')}
        </button>
      </div>
    )
  }
  return undefined
}

function EditorTripPanelTop({ host }: { host: EditorHost }) {
  const trip = useTripState(tripSession)
  const nextNumber = nextUnvisited(host.stops)
  const guide = guideEntryPlacement({ guiding: trip.phase === 'guiding', hasTarget: trip.target !== null, hasCard: host.cardOpen || sharedGuide.state.picked !== null, panelOpen: ui.get().guideOpen, pinning: host.pinning })
  return (
    <TripBanner
      session={tripSession}
      state={trip}
      next={nextNumber ? { number: nextNumber.number, name: nextNumber.stop.place.name } : null}
      guideInHeader={guide === 'tripHeader'}
      onOpenGuide={() => patchUi({ guideOpen: true })}
    />
  )
}

function EditorChips({ host }: { host: EditorHost }) {
  const trip = useTripState(tripSession)
  const guideState = useGuideState(sharedGuide)
  const state = useStore(ui, (s) => s)
  const shown = useMemo(() => new Set(guideState.places.map(placeKey)), [guideState.places])
  const drawn = useMemo(() => drawnPlaceIds({ stops: host.stops.map((s) => s.place) }), [host.stops])
  // 갈래 칩이 세는 대상 — 주변 편의시설만. 챗봇 결과는 「AI 장소」 칩이 따로 센다.
  const counted = useMemo(() => withoutDotsUnderPins(state.ambient.filter((p) => !shown.has(placeKey(p))), drawn), [state.ambient, shown, drawn])
  const aiCount = guideState.places.length
  if (trip.target) return null
  if (counted.length === 0 && aiCount === 0) return null
  const extras =
    aiCount > 0
      ? [
          {
            id: 'ai', label: tr('AI 장소 %d', aiCount), tone: 'var(--accent)', isOn: state.aiOn, image: 'haetae-face.png',
            onTap: () => {
              patchUi({ aiOn: !state.aiOn })
              // 감춘 핀을 계속 골라 두면 카드만 남는다.
              const picked = sharedGuide.state.picked
              if (state.aiOn && picked && guideState.places.some((p) => p.id === picked.id)) sharedGuide.setPicked(null)
            },
          },
        ]
      : []
  return (
    <div style={{ padding: '8px 0 0', background: 'var(--system-background)' }}>
      <PoiChips
        places={counted}
        groupsOn={state.groupsOn}
        onChange={(next) => patchUi({ groupsOn: next })}
        onGroupOff={(g) => {
          // 갈래를 껐다 — 그 갈래에서 골라 둔 핀을 놓는다.
          const picked = sharedGuide.state.picked
          if (picked && placeGroup(picked) === g && !guideState.places.some((p) => p.id === picked.id)) sharedGuide.setPicked(null)
        }}
        extras={extras}
      />
    </div>
  )
}

function EditorBottomBar({ host, fallback }: { host: EditorHost; fallback: React.ReactNode }) {
  const trip = useTripState(tripSession)
  const { visitSaving, visitFailed } = useStore(ui, (s) => s)
  if (tripSession.state.target === null) return <>{fallback}</>
  const next = nextUnvisited(host.stops)
  const target = host.stops.find((s) => s.uid === trip.target?.uid)
  const pending = target?.serverItemId != null && (visitSaving.has(target.serverItemId) || visitFailed.has(target.serverItemId))
  return (
    <TripControls
      session={tripSession}
      state={trip}
      next={next ? { number: next.number, name: next.stop.place.name, onGo: () => void startTripTo(host, next.stop) } : null}
      visitPending={pending}
      onlyUnavailable={!next && host.stops.some((s) => !canNavigate(s))}
    />
  )
}

// ---------------------------------------------------------------- 등록

export function registerTripExtensions(): () => void {
  return registerEditorExtensions({
    Overlay: EditorTripLayer,
    PanelTop: EditorTripPanelTop,
    PanelChips: EditorChips,
    BottomBar: EditorBottomBar,
    rowExtras: (stop, host) => {
      const t = tripSession.state
      return {
        isTarget: t.phase === 'guiding' && t.target?.uid === stop.uid,
        onNavigate: host.course.isRunning && canNavigate(stop) ? () => void startTripTo(host, stop) : null,
      }
    },
    startTrip: (host, stop) => void startTripTo(host, stop),
    onDispose: () => {
      tripSession.end()
      patchUi({ guideOpen: false, ambient: [], groupsOn: new Set(POI_GROUPS), aiOn: true, preferMap: false })
    },
  })
}

registerTripExtensions()

// 시험·화면 확인이 상태를 읽고 쓴다.
export const tripUi = ui
