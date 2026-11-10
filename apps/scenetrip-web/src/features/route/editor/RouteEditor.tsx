/*
 * 코스 편집 화면 — iOS RouteTab/RouteEditorView.swift (+ Controls·Header·List·Parts·Ambient·Trip 의 계획 모드).
 * AI 초안도 직접 짜기도 여기로 모인다. 8/11 회의 확정: 「AI로 초안을 만들고 바로 저장시키는 게 아니고, 초안이 만들어지고 거기서 수정할 수 있게 또 해 줘야 되겠네요」.
 * 그래서 이 화면은 작업 사본을 들고 있다가 「만들기/저장」을 눌러야 서버에 넣는다 — 취소하면 아무것도 남지 않는다(바꾼 것이 있으면 버릴지 묻는다).
 *
 * 지도가 주인공이다 — 지도를 화면에 다 깔고 일정은 끌어 올렸다 내렸다 하는 시트로 얹는다(2026-08-27 사용자 지적, 검색 탭과 같은 부품).
 * 여행 중 안내(길찾기·도착 스탬프)·가이드 챗봇·편의시설 카드는 editorExtensions 로 다른 담당 모듈이 잇는다.
 */
import type { MapHandle } from '@/map'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Icon } from '@/design/Icon'
import { HStack, Txt, VStack } from '@/design/primitives'
import { Pressable } from '../ui/Press'
import { consumePending, pendingStore } from '@/app/tabRouter'
import { tr, useLang } from '@/i18n'
import { useStore } from '@/state/storage'
import { startCourse } from '../availability'
import { changedSince, isBlank, outgoingKey, savedTitle } from '../bridge'
import { notesSummary } from '../draft'
import { optimized, startingNearest, usableAnchor } from '../geometry'
import { type LatLng, RouteMap } from '../map/RouteMap'
import type { LocateStatus } from '../map/locateStatus'
import { type PlaceSummary, type RouteCourse, type RouteStop, canNavigate, courseCanSave, placeIdOf } from '../models'
import type { PinKind } from '../placeType'
import { courseSaveMessage, routeActions, routeStore, useRouteState, workTitlesOfPlace } from '../store'
import { AIBanner } from '../ui/AIBanner'
import { GlassDialog } from '../ui/GlassDialog'
import { BottomBar } from './BottomBar'
import { BottomSheet, type Detent, SLIDE_MS } from './BottomSheet'
import { useCartHint } from './cartHint'
import { CartSheet } from './CartSheet'
import {
  addDay, addPlaces, moveStop, removeLastDay, removeStop, setDayStops, setStay, shouldNudgeOptimize, takenPlaceIds,
} from './courseEdit'
import { EditorPanel } from './EditorPanel'
import { type EditorHost, editorExtensions } from './editorExtensions'
import { PinSheet, pinnedPlace } from './PinSheet'
import { SearchSheet } from './SearchSheet'
import { StaySheet } from './StaySheet'
import { StopCard } from './StopCard'
import { TopBar } from './TopBar'
import { clearFreshCourse, isFreshCourse, protoAvailable, protoStore, say } from '@/features/proto/protoState'
import { useBackGuard } from '@/features/proto/useBackGuard'
import { CoachTour } from '@/features/proto/CoachTour'
import { PlaceDetail } from '@/features/search/ui/PlaceDetail'
import { AddFab, MapAddScreen, SavedAddSheet } from '@/features/proto/ProtoAddMenu'
import { api, ok } from '@/api/client'

/** 일정 시트 중간 단 — 지도 40 : 일정 60. */
const PANEL_MEDIUM = 0.6
const WORK_TITLE_LIMIT = 2

export function RouteEditor({ course: initial, isNew, onClose }: { course: RouteCourse; isNew: boolean; onClose: () => void }) {
  useLang()
  const [course, setCourse] = useState(initial)
  const [dayIndex, setDayIndex] = useState(0)
  const [fitToken, setFitToken] = useState(0)
  const [courseFitToken, setCourseFitToken] = useState(0)
  // 켜짐이 기본이다(2026-08-28) — 코스를 보는 사람은 대개 자기 위치와 견주고 싶어서 본다.
  const [showingMe, setShowingMe] = useState(true)
  const [here, setHere] = useState<LatLng | null>(null)
  const [locateStatus, setLocateStatus] = useState<LocateStatus>('idle')
  const [focusedUid, setFocusedUid] = useState<string | null>(null)
  const [pickedStop, setPickedStop] = useState<RouteStop | null>(null)
  const [previews, setPreviews] = useState<PlaceSummary[]>([])
  const [pinning, setPinning] = useState(false)
  const [pendingPin, setPendingPin] = useState<LatLng | null>(null)
  const [showCart, setShowCart] = useState(false)
  const [showSearch, setShowSearch] = useState(false)
  const [showStay, setShowStay] = useState(false)
  const [detent, setDetent] = useState<Detent>('medium')
  const [panelHeight, setPanelHeight] = useState(0)
  const [optimizeNudge, setOptimizeNudge] = useState(false)
  // 둘 다 선택이고 서로 무관하다. 둘 다 꺼진 채로 시작한다(2026-09-04) — 여행 전 계획은 통째로 자유롭게 최적화한다. 저장되지 않는다.
  const [pinStart, setPinStart] = useState(false)
  const [pinEnd, setPinEnd] = useState(false)
  const [showNotes, setShowNotes] = useState(false)
  const [opened, setOpened] = useState(() => outgoingKey(initial))
  const [confirmingDiscard, setConfirmingDiscard] = useState(false)
  const [blockedDay, setBlockedDay] = useState<number | null>(null)
  const [saving, setSaving] = useState(false)
  const [map, setMap] = useState<MapHandle | null>(null)
  const state = useRouteState()
  const ext = useStore(editorExtensions, (s) => s)
  const cartHint = useCartHint(course)
  // 「지도 켜고 끄기」 시안(로컬 개발 서버 전용) — 일정 목록이 화면 전체, 「지도」 를 누르면 위에 지도가 펼쳐진다(Wanderlog 식).
  // 여행 중이면 길찾기를 보느라 지도가 먼저라 펼친 채로 연다.
  const proto = useStore(protoStore, (p) => protoAvailable && p.proto !== null)
  const [mapOpen, setMapOpen] = useState(initial.isRunning)
  // 「보기 / 편집」 시안 — 저장된 코스는 보기로 열리고(‹ 뒤로 · 편집), 「편집」 을 눌러야 고친다(취소 · 저장). 새 코스는 처음부터 편집(취소 · 만들기).
  // 방금 「도시로 시작」 으로 만든 코스는 편집 상태로 열고, 저장 없이 나가면 묻고 지운다(2026-11-09 사용자 결정 — 세 가지 만들기 모두 「저장」 해야 끝).
  const [fresh, setFresh] = useState(() => proto && isFreshCourse(initial.serverId))
  const [confirmingLeave, setConfirmingLeave] = useState(false)
  const [editing, setEditing] = useState(isNew || fresh)
  const [beforeEdit, setBeforeEdit] = useState(initial)
  const [confirmingRevert, setConfirmingRevert] = useState(false)
  const viewing = proto && !editing
  // 시안 — 「지도」: 화면 전체 목록이 맨 위에 선 서랍으로 바뀐 뒤 중간 단으로 내려앉는다. 「목록만」: 서랍이 맨 위까지 올라간 뒤 화면 전체 목록으로 바뀐다.
  const [switching, setSwitching] = useState(false)
  // 지도를 처음 펼칠 때만 코스 전체에 맞춘다 — 접혀 있는 동안(높이 0) 맞춘 화면은 엉뚱하다. 그 뒤로는 사람이 맞춰 둔 확대·위치를 지킨다.
  const mapFitted = useRef(initial.isRunning)
  const openMap = () => {
    setDetent('expanded')
    setMapOpen(true)
    window.setTimeout(() => setDetent('medium'), 60)
    if (!mapFitted.current) {
      mapFitted.current = true
      window.setTimeout(() => setFitToken((n) => n + 1), SLIDE_MS + 80)
    }
  }
  const closeMap = () => {
    setSwitching(true)
    setDetent('expanded')
    window.setTimeout(() => {
      setMapOpen(false)
      setSwitching(false)
    }, SLIDE_MS)
  }
  /** 시안 — 줄을 눌러 연 장소 상세(검색 탭과 같은 화면). */
  const [detailOf, setDetailOf] = useState<PlaceSummary | null>(null)

  const day = course.days[Math.min(dayIndex, course.days.length - 1)]
  const stops = useMemo(() => day?.stops ?? [], [day])
  const focused = stops.find((s) => s.uid === focusedUid) ?? null
  /** 저장하면 서버 값이 달라지는가 — 「취소」가 묻는 기준(MZ2AZ-369). 새 코스는 빈 틀이 아니면 묻는다(AI 초안은 손대지 않았어도 묻는다). */
  const dirty = isNew ? !isBlank(course) : changedSince(opened, course)

  // 작품 줄 — 받아 둔 촬영지 목록(인기 200곳)에 없는 곳은 여기서 물어 채운다(MZ2AZ-372).
  const stopIds = stops.map((s) => s.uid).join(',')
  useEffect(() => {
    void routeActions.loadWorkTitles(stops)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [stopIds])
  useEffect(() => () => ext.onDispose?.(), [ext])

  const refit = useCallback(() => {
    setFocusedUid(null)
    setFitToken((n) => n + 1)
  }, [])

  // ---- 저장·닫기
  const saveAndClose = useCallback(async (): Promise<RouteCourse | null> => {
    if (saving) return null
    setSaving(true)
    try {
      // 제목을 비운 채 저장하면 목록에 이름 없는 코스가 생긴다 — 「직접 짜기」의 기본 이름으로 둔다.
      const titled = { ...course, title: savedTitle(course.title) }
      setCourse(titled)
      const saved = await routeActions.save(titled)
      if (saved) {
        setOpened(outgoingKey(saved))
        onClose()
      }
      return saved
    } finally {
      setSaving(false)
    }
  }, [course, onClose, saving])

  /** 길찾기는 목적지를 저장된 코스 항목으로만 받는다 — 방금 담은 곳은 먼저 저장한다. 저장 뒤에도 화면은 닫지 않는다. */
  const saveNow = useCallback(async (): Promise<RouteCourse | null> => {
    const saved = await routeActions.save({ ...course, title: savedTitle(course.title) })
    if (saved) {
      setCourse(saved)
      setOpened(outgoingKey(saved))
    }
    return saved
  }, [course])

  const cancel = () => (dirty ? setConfirmingDiscard(true) : onClose())

  // ---- 담기
  const taken = useMemo(() => takenPlaceIds(course), [course])
  const addAndFit = (res: { course: RouteCourse; added: number }) => {
    if (res.added === 0) return
    setCourse(res.course)
    setFitToken((n) => n + 1)
    // 둘부터 순서라는 것이 생긴다 — 그때부터 최적화를 권한다.
    if (shouldNudgeOptimize(res.course.days[dayIndex].stops)) setOptimizeNudge(true)
  }
  const onAddPlaces = (places: PlaceSummary[]) => addAndFit(addPlaces(course, dayIndex, places))
  // 시안 — 고른 일차에 넣는다(＋ 메뉴의 두 갈래). 다른 일차에 넣었으면 그 일차로 옮겨 보여 준다.
  const addToDay = (places: PlaceSummary[], day: number) => {
    setCourse((c) => addPlaces(c, day, places).course)
    setDayIndex(day)
    setFitToken((n) => n + 1)
  }
  const [addMenu, setAddMenu] = useState<'map' | 'saved' | null>(null)
  const onPinDone = (name: string, category: PinKind) => pendingPin && addAndFit(addPlaces(course, dayIndex, [pinnedPlace(name, category, pendingPin)], true))

  // ---- 목록 편집
  const editStops = (next: RouteStop[]) => {
    setCourse((c) => setDayStops(c, dayIndex, next))
    // 손으로 순서를 바꿨다 — 동선이 낡았을 수 있다. 다시 권한다.
    setOptimizeNudge(shouldNudgeOptimize(next))
  }
  const onOptimize = () => {
    // 현재 위치를 알고 출발이 아직 안 정해졌으면 가장 가까운 곳이 출발이다(2026-09-04). 출발 고정 토글은 건드리지 않는다(2026-09-16) — 고정은 이번 계산에만 준다.
    let ordered: readonly RouteStop[] = stops
    let head = pinStart
    const hereSpot = here ? ({ id: 0, name: '여기', latitude: here.latitude, longitude: here.longitude } as PlaceSummary) : null
    const anchor = !pinStart ? usableAnchor(hereSpot, ordered) : null
    if (anchor) {
      ordered = startingNearest(ordered, anchor)
      head = true
    }
    setCourse((c) => setDayStops(c, dayIndex, optimized(ordered, head, pinEnd)))
    // 시안(시험용 웹) — 지도는 보던 그대로 둔다. 번호만 바뀐다(2026-11-09 사용자 지적: 누르면 확대가 다 풀렸다).
    if (protoAvailable && protoStore.get().proto !== null) return
    // 코스 전체가 보이게 — 고른 줄을 놓지 않으면 지도가 그 한 곳으로 확대해 들어간다(2026-09-17 사용자 지적).
    setFocusedUid(null)
    setCourseFitToken((n) => n + 1)
    setOptimizeNudge(false)
  }
  const onFocusRow = (stop: RouteStop) => {
    // 시안 — 줄을 누르면 지도가 그곳을 가리킨다. 접혀 있으면 펼친다. 상세는 사진의 ⓘ 로 따로 연다.
    if (proto && !mapOpen) {
      openMap()
      setFocusedUid(stop.uid)
      return
    }
    // 한 번 더 누르면 놓는다 — 놓을 방법이 없으면 한 곳을 고른 뒤 경로 전체를 다시 볼 수가 없다(2026-08-25 사용자 지적).
    if (focusedUid === stop.uid) refit()
    else setFocusedUid(stop.uid)
  }
  const worksOf = (stop: RouteStop): string => {
    const id = placeIdOf(stop)
    if (id === null) return ''
    // 방금 검색·장바구니에서 담은 곳은 작품을 이미 들고 있다. 둘까지만 적는다 — 셋을 넘기면 줄이 넘쳐 주소가 밀린다.
    const carried = (stop.place.contents ?? []).map((c) => c.title)
    return (carried.length > 0 ? carried : [...workTitlesOfPlace(routeStore.get(), id)]).slice(0, WORK_TITLE_LIMIT).join(' · ')
  }

  // ---- 여행 시작/종료 (진행 상태만 바꾼다 — 코스 내용을 함께 덮어쓰면 편집 중이던 것이 저장돼 「시작」이 「저장」을 겸하게 된다)
  const host: EditorHost = {
    course, setCourse, dayIndex, setDayIndex, stops, isNew, map, refit, saveNow, panelHeight,
    requestClose: cancel,
    requestPlanner: () => pendingStore.set((s) => ({ ...s, planner: true })),
    here, pinning, cardOpen: pickedStop !== null, focusedUid, setFocusedUid, fit: refit, openCart: () => setShowCart(true),
    openInfo: (stop) => {
      setPickedStop(stop)
      setFocusedUid(stop.uid)
    },
  }
  const startTrip = (stop: RouteStop) => ext.startTrip?.(host, stop)
  const toggleRunning = async () => {
    if (course.isRunning) {
      setCourse({ ...course, isRunning: false })
      await routeActions.setRunning(course, false)
      return
    }
    const started = startCourse(course, dayIndex)
    if (!started) return
    setCourse(started.course)
    // 서버가 「여행 중」을 안 뒤에 길을 묻는다(2026-09-17) — 상태 요청을 던져 놓기만 하고 곧바로 길찾기를 부르면 서버가 시작 전 코스로 보고 거절했다.
    await routeActions.setRunning(course, true, dayIndex + 1)
    startTrip(started.target)
  }

  // 시안의 일정 목록 — 지도를 접으면 화면 전체, 펼치면 서랍 안에 같은 것이 들어간다.
  const protoPanel = (
      <EditorPanel
        course={course}
        dayIndex={dayIndex}
        stops={stops}
        focusedUid={focusedUid}
        pinStart={pinStart}
        pinEnd={pinEnd}
        optimizeNudge={optimizeNudge}
        pinning={pinning}
        cartHint={cartHint}
        worksOf={worksOf}
        rowExtras={ext.rowExtras ? (s) => ext.rowExtras!(s, host) : undefined}
        top={ext.PanelTop ? <ext.PanelTop host={host} /> : undefined}
        chips={ext.PanelChips ? <ext.PanelChips host={host} /> : undefined}
        onDayIndex={(i) => {
          setDayIndex(i)
          // 일차가 바뀌면 고른 장소를 놓는다 — 지도는 그 일차 전체로 맞춘다.
          refit()
        }}
        onAddDay={() => {
          setCourse(addDay(course))
          setDayIndex(course.days.length)
          refit()
        }}
        onRemoveDay={() => {
          const res = removeLastDay(course)
          if (res.blocked !== null) setBlockedDay(res.blocked)
          else {
            setCourse(res.course)
            setDayIndex(Math.min(dayIndex, res.course.days.length - 1))
          }
        }}
        onFocus={onFocusRow}
        onTogglePin={(w) => (w === 'start' ? setPinStart((v) => !v) : setPinEnd((v) => !v))}
        onOptimize={onOptimize}
        onSearch={() => setShowSearch(true)}
        onCart={() => setShowCart(true)}
        onPin={() => {
          // 핀은 지도를 눌러 찍는다 — 접혀 있으면 펼친다.
          if (!mapOpen) openMap()
          setPinning((v) => !v)
        }}
        onShowStay={() => setShowStay(true)}
        viewing={viewing}
        photos={{
          onOpen: (stop) => {
            const id = placeIdOf(stop)
            return id === null ? null : () => setDetailOf({ ...stop.place, id })
          },
        }}
        onMoveStop={(from, to) => editStops(moveStop(stops, from, to))}
        onRemoveStop={(uid) => editStops(removeStop(stops, uid))}
      />
  )

  const bottomBar = (
    <BottomBar course={course} stops={stops} isNew={isNew} saving={saving} onCreate={() => void saveAndClose()} onToggleRunning={() => void toggleRunning()} onNavigate={startTrip} />
  )

  // 시안 — 저장된 코스의 편집: 「취소」 는 화면을 닫지 않고 편집 전으로 되돌려 보기로, 「저장」 은 저장하고 보기로.
  const startEdit = () => {
    setBeforeEdit(course)
    setEditing(true)
  }
  const revert = () => {
    setCourse(beforeEdit)
    setEditing(false)
    setPinning(false)
  }
  const protoCancel = () => (fresh ? setConfirmingLeave(true) : changedSince(outgoingKey(beforeEdit), course) ? setConfirmingRevert(true) : revert())
  const protoSave = async () => {
    const saved = await saveNow()
    if (saved) {
      setEditing(false)
      setFresh(false)
      clearFreshCourse()
    }
  }
  // 브라우저 「뒤로」 — 저장 안 한 것이 있으면 바로 닫지 않고 「취소」 와 같은 것을 묻는다.
  const unsaved = isNew ? dirty : fresh || changedSince(outgoingKey(beforeEdit), course)
  const guard = useBackGuard(proto && editing && unsaved, () => {
    if (isNew) setConfirmingDiscard(true)
    else protoCancel()
  })
  /** 방금 만든 코스를 저장 없이 나간다 — 서버에 만들어 둔 것을 지운다. */
  const leaveFresh = () =>
    guard.release(() => {
      clearFreshCourse()
      void routeActions.remove(course)
      onClose()
    })

  return (
    <VStack style={{ height: '100%', background: 'var(--grouped-background)', position: 'relative' }}>
      {viewing ? (
        <ViewBar title={course.title} onBack={onClose} onEdit={startEdit} />
      ) : (
        <TopBar
          title={course.title}
          isNew={isNew}
          canSave={courseCanSave(course)}
          saving={saving}
          onTitle={(title) => setCourse((c) => ({ ...c, title }))}
          onCancel={proto && !isNew ? protoCancel : cancel}
          onSave={() => void (proto && !isNew ? protoSave() : saveAndClose())}
          compact={proto}
        />
      )}
      {course.madeByAI && isNew && (
        <div style={{ padding: '0 16px 8px' }}>
          {/* 토스트는 몇 초 뒤 사라져 그 사이에 화면을 안 본 사람은 놓친다. 저장 전이라는 사실은 저장할 때까지 화면에 남아 있어야 한다. */}
          <AIBanner text={course.filledFromPopular ? tr('고른 작품의 촬영지가 아직 없어 인기 장소로 채웠습니다') : tr('AI 가 짠 일정입니다 · 아직 저장 전')} />
        </div>
      )}
      {course.madeByAI && isNew && course.draftNotes.length > 0 && <DraftNotes notes={course.draftNotes} open={showNotes} onToggle={() => setShowNotes((v) => !v)} />}

      <div
        style={{
          position: 'relative', minHeight: 0, background: 'var(--system-background)',
          ...(proto && !mapOpen ? { flex: 'none', height: 0, overflow: 'hidden' } : { flex: 1 }),
        }}
        aria-hidden={proto && !mapOpen ? true : undefined}
      >
        <RouteMap
          stops={stops}
          fitToken={fitToken}
          courseFitToken={courseFitToken}
          pinning={pinning}
          pending={pendingPin}
          showingMe={showingMe}
          focused={focused}
          targetUid={stops.find((s) => ext.rowExtras?.(s, host).isTarget)?.uid ?? null}
          previews={previews}
          bottomInset={proto && !mapOpen ? 0 : panelHeight}
          here={here}
          onHere={setHere}
          onLocateStatus={setLocateStatus}
          onTapMap={(pin) => {
            // 한 번 찍으면 모드를 끈다. 켜 두면 시트를 닫는 손짓이 다음 핀이 된다.
            setPinning(false)
            setPendingPin(pin)
          }}
          onTapStop={(stop) => {
            setPickedStop(stop)
            // 누른 곳으로 확대해 들어간다 — 일차 전체를 맞춘 화면 그대로면 무엇을 눌렀는지 지도에서 알 수 없다(2026-10-16).
            setFocusedUid(stop.uid)
          }}
          onTapPreview={(place) => {
            const stop = stops.find((s) => s.place.name === place.name && s.place.latitude === place.latitude)
            if (stop) {
              setPickedStop(stop)
              setFocusedUid(stop.uid)
            }
          }}
          onReady={(m) => {
            setMap(m)
            ext.onMapReady?.(host, m)
          }}
        />
        {(pinning || (showingMe && locateStatus !== 'idle')) && (
          <div style={{ position: 'absolute', top: 10, left: 0, right: 0, display: 'flex', justifyContent: 'center', pointerEvents: 'none', zIndex: 5 }}>
            <span style={{ padding: '7px 12px', borderRadius: 999, background: 'rgba(250,250,250,0.78)', backdropFilter: 'blur(20px)', fontSize: 13, fontWeight: 500 }}>
              {pinning ? tr('지도를 눌러 장소를 찍으세요') : locateStatus === 'denied' ? tr('위치 권한이 없어 현재 위치를 표시할 수 없습니다') : tr('현재 위치를 찾는 중입니다')}
            </span>
          </div>
        )}
        {/* 「내 위치」 토글 — 핀 찍는 동안에는 숨긴다(지도를 눌러야 하는데 버튼이 손에 걸린다). 검색 탭은 날아가는 버튼이지만 여기서는 토글이다 —
            그냥 날아가면 촬영지가 화면 밖으로 나가 무엇을 보던 화면인지 알 수 없다(2026-08-24 사용자 지적). */}
        {!pinning && <LocateButton on={showingMe} onPress={() => setShowingMe((v) => !v)} top={proto && editing ? 62 : 10} />}
        {/* 편의시설 칩·검색은 고칠 때만 — 볼 때는 내 코스만 보인다 */}
        {proto && editing && mapOpen && !pinning && ext.MapTop && <ext.MapTop host={host} />}
        {!proto && (
          <BottomSheet detent={detent} onDetentChange={setDetent} mediumFraction={PANEL_MEDIUM} onHeightChange={setPanelHeight}>
          <EditorPanel
            course={course}
            dayIndex={dayIndex}
            stops={stops}
            focusedUid={focusedUid}
            pinStart={pinStart}
            pinEnd={pinEnd}
            optimizeNudge={optimizeNudge}
            pinning={pinning}
            cartHint={cartHint}
            worksOf={worksOf}
            rowExtras={ext.rowExtras ? (s) => ext.rowExtras!(s, host) : undefined}
            top={ext.PanelTop ? <ext.PanelTop host={host} /> : undefined}
            chips={ext.PanelChips ? <ext.PanelChips host={host} /> : undefined}
            onDayIndex={(i) => {
              setDayIndex(i)
              // 일차가 바뀌면 고른 장소를 놓는다 — 지도는 그 일차 전체로 맞춘다.
              refit()
            }}
            onAddDay={() => {
              setCourse(addDay(course))
              setDayIndex(course.days.length)
              refit()
            }}
            onRemoveDay={() => {
              const res = removeLastDay(course)
              if (res.blocked !== null) setBlockedDay(res.blocked)
              else {
                setCourse(res.course)
                setDayIndex(Math.min(dayIndex, res.course.days.length - 1))
              }
            }}
            onFocus={onFocusRow}
            onTogglePin={(w) => (w === 'start' ? setPinStart((v) => !v) : setPinEnd((v) => !v))}
            onOptimize={onOptimize}
            onSearch={() => setShowSearch(true)}
            onCart={() => setShowCart(true)}
            onPin={() => setPinning((v) => !v)}
            onShowStay={() => setShowStay(true)}
            onMoveStop={(from, to) => editStops(moveStop(stops, from, to))}
            onRemoveStop={(uid) => editStops(removeStop(stops, uid))}
          />
          </BottomSheet>
        )}
        {/* 시안 — 지도를 펼치면 원래처럼 지도 위에 끌어 올리고 내리는 서랍. 「목록만」 을 누르면 서랍이 화면 전체 목록으로 돌아간다. */}
        {proto && mapOpen && (
          <>
            <BottomSheet detent={detent} onDetentChange={setDetent} mediumFraction={PANEL_MEDIUM} onHeightChange={setPanelHeight} topInset={0} settleFirst>
              {protoPanel}
            </BottomSheet>
            {!switching && <MapPill open onToggle={closeMap} />}
            {!switching && editing && <AddFab onMap={() => setAddMenu('map')} onSaved={() => setAddMenu('saved')} />}
          </>
        )}
        {pickedStop && (
          <div style={{ position: 'absolute', left: 12, right: 12, bottom: 12, zIndex: 20 }}>
            {(() => {
              const onReroute = course.isRunning && canNavigate(pickedStop) && ext.startTrip ? () => startTrip(pickedStop) : null
              const close = () => {
                setPickedStop(null)
                if (focusedUid !== pickedStop.uid) return
                // 시안 — 카드를 닫아도 지도는 보던 그대로 둔다(고른 표시만 놓는다). 다시 맞추면 확대해 둔 화면이 풀렸다(2026-11-10 사용자 지적).
                if (proto) setFocusedUid(null)
                // 핀을 눌러 확대해 들어왔다면 카드를 닫을 때 일차 전체로 돌아간다.
                else refit()
              }
              return ext.StopCard ? <ext.StopCard host={host} stop={pickedStop} onClose={close} onReroute={onReroute} /> : <StopCard stop={pickedStop} onReroute={onReroute} onClose={close} />
            })()}
          </div>
        )}
      </div>
      {proto && !mapOpen && (
        <div style={{ position: 'relative', flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column', background: 'var(--system-background)' }}>
          <MapPill open={false} onToggle={openMap} />
          {editing && <AddFab onMap={() => setAddMenu('map')} onSaved={() => setAddMenu('saved')} />}
          {protoPanel}
        </div>
      )}
      {/* 시안 — 저장된 코스를 고치는 동안에는 「코스 시작」 줄을 숨긴다. 저장은 위 「저장」 하나. */}
      {proto && editing && !isNew ? null : ext.BottomBar ? <ext.BottomBar host={host} fallback={bottomBar} /> : bottomBar}
      {ext.Overlay && <ext.Overlay host={host} />}
      {proto && addMenu === 'map' && (
        <MapAddScreen dayCounts={course.days.map((d) => d.stops.length)} current={dayIndex} taken={taken} onAdd={(place, day) => addToDay([place], day)} onClose={() => setAddMenu(null)} />
      )}
      {proto && (
        <SavedAddSheet open={addMenu === 'saved'} dayCounts={course.days.map((d) => d.stops.length)} current={dayIndex} taken={taken} onAdd={addToDay} onClose={() => setAddMenu(null)} />
      )}
      {proto && detailOf && (
        <div style={{ position: 'absolute', inset: 0, zIndex: 50, display: 'flex', flexDirection: 'column', background: 'var(--system-background)' }}>
          <PlaceDetail
            key={detailOf.id}
            summary={detailOf}
            load={(id) => ok(api.GET('/places/{placeId}', { params: { path: { placeId: id } } }))}
            onBack={() => setDetailOf(null)}
          />
        </div>
      )}

      <SearchSheet open={showSearch} onClose={() => setShowSearch(false)} taken={taken} onPreview={setPreviews} onAdd={onAddPlaces} />
      <CartSheet open={showCart} onClose={() => setShowCart(false)} taken={taken} onPreview={setPreviews} onAdd={onAddPlaces} />
      <PinSheet pin={pendingPin} onClose={() => setPendingPin(null)} onDone={onPinDone} />
      <StaySheet open={showStay} onClose={() => setShowStay(false)} stops={stops} estimate={day?.estimate ?? null} onPick={(uid, m) => setCourse((c) => setStay(c, dayIndex, uid, m))} />

      <GlassDialog
        open={state.saveFailure !== null}
        variant="alert"
        title={tr('저장하지 못했습니다')}
        message={state.saveFailure ? courseSaveMessage(state.saveFailure) : undefined}
        buttons={[{ label: tr('확인'), role: 'cancel' }]}
        onClose={routeActions.clearFailure}
      />
      <GlassDialog
        open={blockedDay !== null}
        variant="alert"
        title={tr('일차를 뺄 수 없습니다')}
        message={tr('%lld일차에 담은 장소를 먼저 빼 주세요', (blockedDay ?? 0) + 1)}
        buttons={[{ label: tr('확인'), role: 'cancel' }]}
        onClose={() => setBlockedDay(null)}
      />
      <GlassDialog
        open={confirmingRevert}
        variant="alert"
        title={tr('바꾼 내용을 버릴까요?')}
        buttons={[
          { label: tr('버리기'), role: 'destructive', onPress: revert },
          { label: tr('계속 편집'), role: 'cancel' },
        ]}
        onClose={() => setConfirmingRevert(false)}
      />
      <GlassDialog
        open={confirmingDiscard}
        variant="alert"
        title={tr('바꾼 내용을 버릴까요?')}
        buttons={[
          { label: tr('버리기'), role: 'destructive', onPress: () => guard.release(onClose) },
          // 마법사로 가려다 남기로 했다 — 쪽지를 거둔다. 안 그러면 나중에 「취소」로 닫을 때 마법사가 뜬다.
          { label: tr('계속 편집'), role: 'cancel', onPress: () => void consumePending('planner') },
        ]}
        onClose={() => setConfirmingDiscard(false)}
      />
      {/* 처음 만든 계획 화면 — 지도·AI·장소 추가(검색)·저장을 한 번 짚어 준다(2026-11-09 사용자 요청). */}
      <CoachTour
        id="editor-first"
        active={proto && editing && (isNew || fresh) && !confirmingLeave && !confirmingDiscard}
        delay={1200}
        steps={[
          { target: '[data-coach="editor-map"]', title: say('지도', 'Map'), text: say('눌러서 코스를 지도로 펼쳐 동선을 확인해요. 다시 누르면 목록만 봐요.', 'Open the route on a map to check the path. Tap again for the list only.') },
          { target: '.gd-chip:not(.gd-chip-hidden)', title: say('해태 AI', 'Haetae AI'), text: say('「하루 줄여 줘」 「카페 하나 넣어 줘」처럼 말로 부탁하면 코스를 고쳐 줘요.', 'Ask in plain words — "make it one day shorter", "add a café" — and it edits the route.') },
          { target: '[data-coach="editor-add"]', title: say('장소 검색해서 담기', 'Search and add places'), text: say('지금은 편집 중이라 장소를 검색해서 더 넣을 수 있어요. 보관함에 담아 둔 곳도 여기서 넣어요.', "You're editing, so you can search for places and add them. Saved places are here too.") },
          { target: '[data-coach="editor-save"]', title: say('다 고쳤으면 저장', 'Save when done'), text: say('저장해야 코스가 남아요. 저장하지 않고 나가면 한 번 더 물어봐요.', "Save to keep the course. If you leave without saving, we'll ask first.") },
        ]}
      />
      <GlassDialog
        open={confirmingLeave}
        variant="alert"
        title={tr('저장하지 않고 나갈까요?')}
        message={tr('방금 만든 코스가 지워져요.')}
        buttons={[
          { label: tr('나가기'), role: 'destructive', onPress: leaveFresh },
          { label: tr('계속 편집'), role: 'cancel' },
        ]}
        onClose={() => setConfirmingLeave(false)}
      />
    </VStack>
  )
}

/** 시안 — 저장된 코스를 볼 때의 머리줄: ‹ 뒤로 · 코스 이름(고칠 수 없음) · 편집. */
function ViewBar({ title, onBack, onEdit }: { title: string; onBack: () => void; onEdit: () => void }) {
  return (
    <div style={{ background: 'var(--system-background)', padding: '4px 8px', flex: 'none' }}>
      <HStack justify="between">
        <Pressable plain onClick={onBack} aria-label={say('뒤로', 'Back')} style={{ minHeight: 44, minWidth: 44, display: 'grid', placeItems: 'center' }}>
          <Icon name="chevron.left" size={20} weight="semibold" color="accent" />
        </Pressable>
        <Txt size={17} weight="semibold" lines={1} style={{ maxWidth: 220 }}>{title}</Txt>
        <Pressable plain onClick={onEdit} style={{ minHeight: 44, minWidth: 44, padding: '0 8px' }}>
          <Txt size={17} color="accent">{say('편집', 'Edit')}</Txt>
        </Pressable>
      </HStack>
    </div>
  )
}

/**
 * 시안 — 목록 위에 떠 있는 「지도」 알약(왼쪽 아래). 지도가 펼쳐져 있으면 「목록만」 으로 바뀐다.
 * 처음엔 아래 가운데였는데 오른쪽 아래 해태 가이드의 말풍선(「도와줄게!」)이 가운데까지 뻗어 겹쳤다(2026-11-08 사용자 지적).
 * 오른쪽은 해태와 ＋ 가 세로로 쓰므로 왼쪽으로 비켰다 — 375 폭에서도 말풍선 왼끝(약 176)과 알약 오른끝(약 156) 사이가 남는다(어림).
 */
function MapPill({ open, onToggle }: { open: boolean; onToggle: () => void }) {
  return (
    <Pressable
      data-coach="editor-map"
      plain
      onClick={onToggle}
      aria-pressed={open}
      style={{
        position: 'absolute', left: 16, bottom: 16, zIndex: 10, height: 44, padding: '0 18px', borderRadius: 999,
        background: 'var(--label)', color: 'var(--system-background)', boxShadow: '0 6px 20px rgba(0,0,0,0.22)',
      }}
    >
      <HStack spacing={6}>
        <Icon name={open ? 'list.bullet' : 'map'} size={15} color="var(--system-background)" />
        <Txt size={15} weight="semibold" style={{ color: 'var(--system-background)' }}>{open ? say('목록만', 'List only') : say('지도', 'Map')}</Txt>
      </HStack>
    </Pressable>
  )
}

/** 지도 오른쪽 위 「내 위치」 토글. 켜짐은 모양이 아니라 배경색으로 구별한다. 흰 판 + 테두리 + 그림자로 볼록하게 세워 어느 상태든 단추로 보이게 한다(2026-08-28 사용자 지적). */
/** `top` — 시안에서는 지도 맨 위에 편의시설 칩 줄이 있어 그 아래(62)로 내린다. */
function LocateButton({ on, onPress, top = 10 }: { on: boolean; onPress: () => void; top?: number }) {
  return (
    <Pressable
      plain
      onClick={onPress}
      aria-label={tr('내 위치')}
      aria-pressed={on}
      style={{
        position: 'absolute', top, right: 10, zIndex: 5, width: 44, height: 44, borderRadius: '50%', display: 'grid', placeItems: 'center',
        background: on ? 'var(--accent)' : 'var(--system-background)', border: on ? 'none' : '1px solid var(--system-gray4)', boxShadow: '0 2px 8px rgba(0,0,0,0.18)',
      }}
    >
      <Icon name="dot.scope" size={17} weight="medium" color={on ? '#fff' : 'accent'} />
    </Pressable>
  )
}

/** 초안의 알림줄 — 접힌 한 줄(「뺀 곳 7 · 주의 3」)이고 누르면 펼쳐진다. 열 줄을 다 펼쳐 두면 지도가 밀려 내려간다(2026-09-11). */
function DraftNotes({ notes, open, onToggle }: { notes: readonly string[]; open: boolean; onToggle: () => void }) {
  return (
    <VStack spacing={4} align="start" style={{ padding: '0 20px 8px' }}>
      <Pressable plain onClick={onToggle}>
        <HStack spacing={6}>
          <Icon name="info.circle" size={11} color="secondary" />
          <Txt size={11} weight="medium" color="secondary">{notesSummary(notes)}</Txt>
          <Icon name={open ? 'chevron.up' : 'chevron.down'} size={9} color="secondary" />
        </HStack>
      </Pressable>
      {open && notes.map((n) => <Txt key={n} size={11} color="secondary" lines={2} style={{ paddingLeft: 18 }}>{n}</Txt>)}
    </VStack>
  )
}
