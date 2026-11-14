/*
 * 작품검색 탭의 상태와 동작 — iOS `SearchTabView` 의 @State 와 함수들. 출처: apps/scenetrip-ios/src/SearchTab/{SearchTabView,SearchTabOverlays}.swift
 *
 * 지도는 항상 보이고 그 위에 바텀시트가 얹혀 드릴다운한다. 목록은 작품/장소 두 탭이며 검색어 하나로 둘이 동시에 채워진다.
 * 드릴다운은 검색 상태와 별개다: 작품을 골라도 검색어와 검색 결과는 그대로 남아, 뒤로 가면 고르기 전 화면으로 돌아간다.
 */
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { matchPath, useLocation, useNavigate } from 'react-router'
import { appEvent, track } from '@/analytics'
import { api, ok } from '@/api/client'
import { consumePending, pendingStore } from '@/app/tabRouter'
import { paths, patterns } from '@/app/routes'
import { useLocationConsent } from '@/features/profile/consent'
import { cartActions, useCart } from '@/state/cart'
import { likeActions, useLikes } from '@/state/likes'
import { useOnAccountChange } from '@/state/shared'
import { useStore } from '@/state/storage'
import { protoAvailable, protoStore } from '@/features/proto/protoState'
import { type BackStep, contentBack, placeBack } from './detailBack'
import { type ListTab, isInitialScreen, numbersOnPins, visiblePlacesOf } from './listRules'
import type { LocateOutcome, MapCameraHandle } from './map/MapView'
import { CHIP_ALL } from './rules/placeType'
import { type EntityType, type Suggestion, SUGGEST_LIMIT } from './rules/suggestions'
import { placeSummaryOf } from './rules/placeWorks'
import { type ContentDetail, type ContentSummary, type PlaceSummary, SceneData, useSceneState } from './sceneData'
import { searchUsageIntent } from './usage'
import type { Detent } from './ui/sheetRules'

/** 자동완성 맨 위에 포스터와 함께 띄우는 가장 연관된 작품 — 서버가 랭킹을 정하므로 suggest 응답의 첫 작품을 `GET /contents/{id}` 로 채운다(§3-3). */
type TopWork = ContentDetail | null

const summaryOf = (w: ContentDetail): ContentSummary => ({
  id: w.id, category: w.category, title: w.title, posterUrl: w.posterUrl, broadcaster: w.broadcaster, releaseYear: w.releaseYear, genres: w.genres, placeCount: w.placeCount,
})

const inSearchDetail = (path: string): boolean => path.startsWith(paths.search + '/')
const onPlacePath = (path: string): boolean => matchPath(patterns.searchPlace, path) !== null

/** 이 앱 안에서 한 칸 뒤로 갈 수 있는가 — react-router 가 history.state 에 남기는 위치 번호(idx)가 0 보다 크면. 새로 고침·직접 연 주소면 0 이다. */
function canGoBack(): boolean {
  const idx = (window.history.state as { idx?: unknown } | null)?.idx
  return typeof idx === 'number' && idx > 0
}

/** `active` — 탭이 지금 보이는가. 홈에서 건 지역 필터는 탭을 떠날 때 지운다(일회성, MZ2AZ-531). */
/**
 * `embedded` — 다른 화면 위에 띄운 검색(코스 편집의 「지도에서 추가」 시안)이다. 주소를 읽지도 바꾸지도 않는다 —
 * 상세를 닫을 때 주소를 검색 탭으로 옮기면 띄운 화면 밑의 코스 편집이 사라진다.
 */
export function useSearchTab(active = true, embedded = false) {
  const dataRef = useRef<SceneData | null>(null)
  if (!dataRef.current) dataRef.current = new SceneData()
  const data = dataRef.current
  const scene = useSceneState(data)
  const cart = useCart()
  const likes = useLikes()
  const location = useLocationConsent()
  const navigate = useNavigate()
  const { pathname, state: navState } = useLocation()

  const [draft, setDraft] = useState('')
  /** 확정된 검색어. `draft` 는 타이핑하는 동안에도 바뀌므로 「지금 이 목록이 무엇을 검색한 결과인가」 를 그것으로 판단하면 헤더가 글자마다 깜빡인다. */
  const [committed, setCommitted] = useState('')
  const [tab, setTab] = useState<ListTab>('work')
  const [chip, setChip] = useState(CHIP_ALL)
  const [detent, setDetent] = useState<Detent>('medium')
  const [selectedPlace, setSelectedPlace] = useState<PlaceSummary | null>(null)
  const [selectedContent, setSelectedContent] = useState<ContentSummary | null>(null)
  const [contentPlaces, setContentPlaces] = useState<readonly PlaceSummary[]>([])
  const [contentLoading, setContentLoading] = useState(false)
  const [suggestions, setSuggestions] = useState<readonly Suggestion[]>([])
  const [topWork, setTopWork] = useState<TopWork>(null)
  const [fitToken, setFitToken] = useState(0)
  const [focusToken, setFocusToken] = useState(0)
  const [panToken, setPanToken] = useState(0)
  const [panTarget, setPanTarget] = useState<PlaceSummary | null>(null)
  const [showCart, setShowCart] = useState(false)
  const [nearby, setNearby] = useState(false)
  const [koreaToken, setKoreaToken] = useState(0)
  const [locateToken, setLocateToken] = useState(0)
  const [locateFailure, setLocateFailure] = useState<LocateOutcome | null>(null)
  const [outsideKorea, setOutsideKorea] = useState(false)
  const [sheetHeight, setSheetHeight] = useState(0)
  const [searchFocused, setSearchFocused] = useState(false)
  const [chipFillRetry, setChipFillRetry] = useState(0)

  const camera = useRef<MapCameraHandle | null>(null)
  /** 검색을 확정했고 결과 도착을 기다리는 중 — 도착하면 그때 카메라를 맞춘다(확정 즉시 올리면 지도가 직전 검색의 핀 범위로 fit 된다 — 실측). */
  const pendingFit = useRef(false)
  /** 자동완성에서 장소를 골랐다 — 결과가 오면 이 장소를 선택하고 확대한다. */
  const pendingFocusPlaceId = useRef<number | null>(null)
  const selectedContentId = useRef<number | null>(null)
  const topWorkRef = useRef<TopWork>(null)
  /** 지금의 지도 범위 검색이 홈 「지도에서 보기」 쪽지로 걸린 것인가 — 탭을 떠나면 지운다. 사용자가 직접 건 범위 검색·검색어는 해당 없다. */
  const regionFromHome = useRef(false)
  const inputRef = useRef<HTMLInputElement>(null)
  /** 지금 열린 촬영지 상세의 id — 주소 효과가 이미 연 곳을 다시 받아 덮지 않게(MZ2AZ-551). */
  const selectedPlaceId = useRef<number | null>(null)
  selectedPlaceId.current = selectedPlace?.id ?? null
  /** 최신 주소 — useCallback 으로 묶인 여는 함수들이 읽는다. */
  const pathRef = useRef(pathname)
  pathRef.current = pathname
  /** 이 훅이 쌓은(push) 상세 주소들 — 닫을 때 그 칸이면 바꾸지 않고 한 칸 뒤로 간다(목록 주소가 두 번 쌓이지 않게). */
  const pushedDetails = useRef<string[]>([])

  /**
   * 상세를 열면 주소를 싣는다(MZ2AZ-551) — /search 에서 열면 쌓고(브라우저 뒤로가 /search 로), 다른 상세 위에서 같은 갈래로 바꾸면 그 자리를 바꾼다.
   * 촬영지는 작품 상세 위에 쌓인다(뒤로가 작품 상세로). 이미 그 주소면(주소창·홈이 연 경우) 그대로.
   */
  const showDetailUrl = useCallback(
    (to: string, replaceWhen: (path: string) => boolean) => {
      if (embedded) return
      const from = pathRef.current
      if (from === to) return
      const stack = pushedDetails.current
      if (replaceWhen(from)) {
        if (stack[stack.length - 1] === from) stack[stack.length - 1] = to
        navigate(to, { replace: true })
      } else {
        stack.push(to)
        navigate(to)
      }
      pathRef.current = to
    },
    [embedded, navigate],
  )

  const initial = isInitialScreen(committed, nearby, selectedContent !== null)
  const visiblePlaces = useMemo(() => visiblePlacesOf(scene.places, chip, initial), [scene.places, chip, initial])
  /** 지도에 꽂는 핀 — 작품을 골랐으면 그 작품의 촬영지, 아니면 검색 결과. 시트 목록과 같은 배열이라 행 번호가 곧 핀 번호다. */
  const proto = useStore(protoStore, (s) => protoAvailable && s.proto !== null)
  /** 시안(로컬 개발 서버 전용): 첫 화면 지도에는 보관함에 담은 곳만 꽂는다 — 번호는 보관함 목록 순서. 좌표 없는 곳·목록에서 빠진 곳은 뺀다. */
  const savedPins = useMemo<PlaceSummary[]>(
    () =>
      cart.items
        .filter((i) => i.latitude != null && i.longitude != null && !i.delisted)
        .map((i) => ({ id: i.placeId, name: i.name, address: i.address, latitude: i.latitude!, longitude: i.longitude!, imageUrl: i.imageUrl, imageThumbnailUrl: i.imageThumbnailUrl })),
    [cart.items],
  )
  const mapPlaces = selectedContent ? contentPlaces : proto && initial ? savedPins : visiblePlaces
  const numbered = (proto && initial && !selectedContent) || numbersOnPins(initial, tab)

  const blurSearch = () => {
    setSearchFocused(false)
    inputRef.current?.blur()
  }

  // ---------------------------------------------------------------- 동작

  /**
   * 검색 확정 — 추천어 선택이나 엔터. 이때만 서버를 부른다(§3-5) — 글자마다 부르지 않는다. 카메라는 여기서 움직이지 않고 결과 도착 때 맞춘다.
   * `kind` 는 사용자가 고른 것의 갈래다: 장소면 장소 탭, 작품·배우면 작품 탭 — 배우로 찾는 것은 「그 배우가 나온 작품」 이다. 갈래를 모르면 탭을 그대로 둔다.
   */
  const commit = useCallback(
    (term: string, kind: EntityType | null = null) => {
      const nextTab: ListTab = kind === 'place' ? 'place' : kind === 'content' || kind === 'person' ? 'work' : tab
      setTab(nextTab)
      setDraft(term)
      setCommitted(term.trim())
      regionFromHome.current = false
      // 단어로 검색하는 순간 반경 모드는 끝난다 — 두 조건이 함께 걸려 있으면 결과가 왜 그렇게 나왔는지 화면만 보고는 설명할 수 없다.
      setNearby(false)
      setChip(CHIP_ALL)
      setSelectedPlace(null)
      selectedContentId.current = null
      setSelectedContent(null)
      setContentPlaces([])
      blurSearch()
      setDetent('medium')
      if (term.trim() !== '') track(appEvent.search(Array.from(term.trim()).length, kind ?? 'typed'))
      data.commitSearch(term, searchUsageIntent(term, nextTab === 'work' ? 'content' : 'place'))
      pendingFit.current = true
    },
    [data, tab],
  )

  /** 작품을 고르면 작품 상세로 들어간다. 검색어·검색 결과·탭은 건드리지 않는다 — 뒤로 가면 고르기 전 화면 그대로다. */
  /** `syncUrl` — 주소에 싣는가. 홈 쪽지·주소창이 연 것은 이미 그 주소다(먼저 실으면 홈 주소 위에 쌓인다). */
  const showContent = useCallback(
    (content: ContentSummary, syncUrl: boolean) => {
      selectedContentId.current = content.id
      setSelectedContent(content)
      setContentPlaces([])
      setContentLoading(true)
      setSelectedPlace(null)
      blurSearch()
      setDetent('medium')
      if (syncUrl) showDetailUrl(paths.searchContent(content.id), inSearchDetail)
      void data
        .placesOfContent(content.id)
        .catch(() => [] as PlaceSummary[])
        .then((places) => {
          // 로드 중에 뒤로 갔거나 다른 작품을 골랐으면 버린다.
          if (selectedContentId.current !== content.id) return
          setContentPlaces(places)
          setContentLoading(false)
          setFitToken((n) => n + 1)
        })
    },
    [data, showDetailUrl],
  )
  const openContent = useCallback((content: ContentSummary) => showContent(content, true), [showContent])

  const openPlace = useCallback((place: PlaceSummary) => {
    setSelectedPlace(place)
    setFocusToken((n) => n + 1)
    showDetailUrl(paths.searchPlace(place.id), onPlacePath)
  }, [showDetailUrl])

  /** 담기 — 계약이 적어 둔 세 경로 중 목록 행의 `+`. 이미 담긴 장소면 뺀다(목록 행의 버튼은 담기 전용이 아니라 토글이다). 담는 순간 그 핀이 가운데 오게 지도를 이동만 한다. */
  const save = useCallback(
    (place: PlaceSummary, sourceContentId?: number | null) => {
      if (cart.placeIds.has(place.id)) {
        void cartActions.remove(place.id)
        return
      }
      void cartActions.add(place.id, sourceContentId)
      // 지도를 옮기는 것은 담을 때만이다. 뺄 때 옮기면 사라진 것을 보여 주는 꼴이다.
      setPanTarget(place)
      setPanToken((n) => n + 1)
    },
    [cart.placeIds],
  )

  const toggleLike = useCallback((contentId: number) => void likeActions.toggle(contentId), [])

  /** 자동완성에서 장소를 골랐다. 검색을 확정하되 장소 탭으로 넘어가고, 결과가 도착하면 그 장소를 바로 선택·확대한다. */
  const selectPlaceSuggestion = useCallback(
    (item: Suggestion) => {
      commit(item.name)
      setTab('place')
      pendingFocusPlaceId.current = item.id
    },
    [commit],
  )

  // 상세에서 나올 때 주소 — 직접 연 상세면 탭 주소로, 격자(지금 뜨는 작품)가 연 작품이면 격자로(MZ2AZ-534). 정하는 규칙은 detailBack.ts.
  const applyBack = (step: BackStep) => {
    if (embedded) return
    // 이 훅이 쌓은 상세 칸이면 한 칸 뒤로 — 바꾸기(replace)로 가면 같은 주소가 두 번 쌓여 뒤로를 한 번 더 눌러야 한다.
    const stack = pushedDetails.current
    if (step.kind !== 'stay' && stack.length > 0 && stack[stack.length - 1] === pathname) {
      stack.pop()
      navigate(-1)
      return
    }
    if (step.kind === 'pop') navigate(-1)
    else if (step.kind === 'replace') navigate(step.to, { replace: true, state: step.state })
  }
  const closePlace = () => {
    const closing = selectedPlace?.id ?? null
    setSelectedPlace(null)
    setFitToken((n) => n + 1)
    applyBack(placeBack({ pathname, state: navState, contentId: selectedContentId.current, placeId: closing, canGoBack: canGoBack() }))
  }
  const closeContent = () => {
    const closing = selectedContentId.current
    selectedContentId.current = null
    setSelectedContent(null)
    setContentPlaces([])
    setFitToken((n) => n + 1)
    applyBack(contentBack({ pathname, state: navState, contentId: closing, canGoBack: canGoBack() }))
  }

  const clearSearch = () => {
    setDraft('')
    commit('')
  }

  const onTapPin = useCallback(
    (place: PlaceSummary) => {
      setSelectedPlace(place)
      if (selectedContentId.current === null) setTab('place')
      setDetent('medium')
      setFocusToken((n) => n + 1)
      showDetailUrl(paths.searchPlace(place.id), onPlacePath)
    },
    [showDetailUrl],
  )

  // ---- 현 지도 내 성지 검색
  const applyViewportSearch = useCallback(
    (bbox: string, source: 'device' | 'userSelected') => {
      setNearby(true)
      setDraft('')
      setCommitted('')
      setSelectedPlace(null)
      selectedContentId.current = null
      setSelectedContent(null)
      setContentPlaces([])
      setTab('place')
      setChip(CHIP_ALL)
      setDetent('medium')
      // 카메라를 건드리지 않는다 — 「지금 보고 있는 이 화면 안」 을 묻는 것이라, 결과가 왔다고 지도를 옮기면 물어본 그 화면이 사라진다.
      data.searchInViewport(bbox, source)
    },
    [data],
  )

  /** 지금 보이는 지도 범위로 촬영지를 (다시) 찾는다. 켜져 있을 때 또 부르면 옮긴·확대한 화면으로 다시 찾는다 — 받는 양에 상한(200)이 있어 좁히면 그 안의 것이 다 들어온다. */
  const searchViewport = useCallback(() => {
    const box = camera.current?.boundingBox()
    if (!box) return
    regionFromHome.current = false
    const source = camera.current!.source()
    // 기기 위치에서 나온 범위면 위치 동의를 먼저 거친다.
    if (source === 'device') location.request(() => applyViewportSearch(box, source))
    else applyViewportSearch(box, source)
  }, [applyViewportSearch, location])

  const toggleNearby = () => {
    if (nearby) {
      regionFromHome.current = false
      // 해제도 카메라를 건드리지 않는다 — 켤 때 안 움직인 화면이 끌 때 움직이면 무엇 때문에 움직였는지 알 수 없다.
      setNearby(false)
      setDraft('')
      setCommitted('')
      setChip(CHIP_ALL)
      data.search('')
    } else searchViewport()
  }

  // ---------------------------------------------------------------- 효과

  // 첫 진입: 전체 목록과 장바구니.
  useEffect(() => {
    data.search('')
    void cartActions.refresh()
    void likeActions.refresh()
  }, [data])
  useOnAccountChange(useCallback(() => void cartActions.refresh(), []))

  // 지도가 남한 밖으로 나갔는지 지켜본다 — 매 프레임 갱신하면 화면이 다시 그려지므로 1 초에 한 번만 확인한다.
  useEffect(() => {
    const id = setInterval(() => setOutsideKorea(camera.current?.isOutsideKorea() ?? false), 1000)
    return () => clearInterval(id)
  }, [])

  // 첫 화면에서 탭을 옮기면: 장소 탭은 인기 10곳이 한 화면에 다 들어오게, 작품 탭은 남한 전체로.
  const prevTab = useRef(tab)
  useEffect(() => {
    if (prevTab.current === tab) return
    prevTab.current = tab
    if (!initial) return
    // 핀을 눌러 장소를 연 채 탭이 바뀐 것이면 인기 핀 맞춤이 그 장소로의 확대를 덮지 않게 한다.
    if (tab === 'place' && visiblePlaces.length > 0 && selectedPlace === null) setFitToken((n) => n + 1)
    else if (tab === 'work') setKoreaToken((n) => n + 1)
    // eslint-disable-next-line react-hooks/exhaustive-deps -- 탭이 바뀔 때만.
  }, [tab])

  // 카메라 fit 은 결과가 실제로 도착한 순간에 건다. 첫 진입 로드는 pendingFit 이 false 라 남한 전체를 유지한다.
  useEffect(() => {
    if (scene.phase.kind !== 'loaded' || !pendingFit.current) return
    pendingFit.current = false
    // 자동완성에서 장소를 골라 들어왔으면 결과 전체가 아니라 그 장소로 바로 확대하고 상세를 연다.
    const wanted = pendingFocusPlaceId.current
    if (wanted !== null) {
      pendingFocusPlaceId.current = null
      const place = scene.places.find((p) => p.id === wanted)
      if (place) {
        setSelectedPlace(place)
        setFocusToken((n) => n + 1)
        // 목록·핀에서 연 것과 같이 주소를 쌓는다(MZ2AZ-551).
        showDetailUrl(paths.searchPlace(place.id), onPlacePath)
        return
      }
      // 받아 둔 첫 쪽에 없을 수 있다(흔한 이름이면 결과가 한 쪽을 넘는다) — 그 곳을 직접 받는다(MZ2AZ-372).
      void data.detailOfPlace(wanted).then((found) => {
        if (selectedContentId.current !== null) return
        const opening = selectedPlaceId.current === null
        setSelectedPlace((cur) => cur ?? placeSummaryOf(found))
        setFocusToken((n) => n + 1)
        if (opening) showDetailUrl(paths.searchPlace(found.id), onPlacePath)
      }).catch(() => {})
    }
    setFitToken((n) => n + 1)
  }, [scene.phase, scene.places, data, showDetailUrl])

  // 홈이 남긴 「이 작품/이 촬영지 열어 줘」 쪽지 — 요약을 그대로 받아 상세를 연다. 자기 목록에서 찾지 않는다(검색한 뒤면 목록이 걸러져 있다). 쪽지는 한 번 읽고 버린다.
  const pendingContent = useStore(pendingStore, (s) => s.content)
  const pendingPlace = useStore(pendingStore, (s) => s.place)
  useEffect(() => {
    if (!pendingContent) return
    const wanted = consumePending('content')
    if (wanted) showContent(wanted, false)
  }, [pendingContent, showContent])
  useEffect(() => {
    if (!pendingPlace) return
    const wanted = consumePending('place')
    if (!wanted) return
    // 작품 상세를 거치지 않고 바로 장소 상세.
    selectedContentId.current = null
    setSelectedContent(null)
    setContentPlaces([])
    setSelectedPlace(wanted)
    blurSearch()
    setDetent('medium')
    setFocusToken((n) => n + 1)
  }, [pendingPlace])

  // 홈 「도시의 성지」의 「지도에서 보기」(MZ2AZ-531) — 그 도시 촬영지를 감싼 범위로 「현 지도 내 성지 검색」을 걸고, 결과가 오면 핀에 카메라를 맞춘다.
  const pendingRegion = useStore(pendingStore, (s) => s.region)
  useEffect(() => {
    if (!pendingRegion) return
    const bbox = consumePending('region')
    if (!bbox) return
    blurSearch()
    applyViewportSearch(bbox, 'userSelected')
    regionFromHome.current = true
    pendingFit.current = true
    // eslint-disable-next-line react-hooks/exhaustive-deps -- 쪽지가 올 때만.
  }, [pendingRegion])

  // 홈에서 건 지역 필터는 일회성이다 — 탭을 떠나면 지워, 탭바·「전체 보기」·오늘의 성지 등 다른 길로 다시 오면 필터 없이 연다(사용자 피드백).
  useEffect(() => {
    if (active || !regionFromHome.current) return
    regionFromHome.current = false
    setNearby(false)
    setDraft('')
    setCommitted('')
    setChip(CHIP_ALL)
    data.search('')
    // eslint-disable-next-line react-hooks/exhaustive-deps -- 숨겨질 때만.
  }, [active])

  // URL(`/search/content/:id`, `/search/place/:id`)로 직접 열었을 때도 같은 상세가 열려야 한다.
  // 브라우저 뒤로로 상세 주소에서 /search 에 오면 열린 상세를 닫는다(MZ2AZ-551). 다른 탭에서 돌아온 것은 닫지 않는다(전처럼 남는다).
  const prevPath = useRef(pathname)
  useEffect(() => {
    if (embedded) return
    const before = prevPath.current
    prevPath.current = pathname
    const stack = pushedDetails.current
    const at = stack.lastIndexOf(pathname)
    // 작품검색 탭을 떠나면(홈·커뮤니티·덮개) 쌓은 기록을 버린다 — 홈에서 같은 상세 주소로 다시 와도 그 칸은 우리가 쌓은 것이 아니다.
    if (pathname === paths.search || !(pathname === paths.search || inSearchDetail(pathname))) stack.length = 0
    else if (at >= 0) stack.length = at + 1
    if (pathname === paths.search) {
      if (!inSearchDetail(before)) return
      selectedContentId.current = null
      setSelectedContent(null)
      setContentPlaces([])
      setContentLoading(false)
      setSelectedPlace(null)
      setFitToken((n) => n + 1)
      return
    }
    const c = matchPath(patterns.searchContent, pathname)
    const p = matchPath(patterns.searchPlace, pathname)
    if (c) {
      const id = Number(c.params.contentId)
      const pending = pendingStore.get().content
      // 촬영지 상세에서 뒤로 와 작품 상세 주소가 됐다 — 위에 떠 있던 촬영지를 닫는다.
      if (selectedContentId.current === id && selectedPlaceId.current !== null && onPlacePath(before)) {
        setSelectedPlace(null)
        setFitToken((n) => n + 1)
      }
      if (!Number.isFinite(id) || selectedContentId.current === id || pending?.id === id) return
      void ok(api.GET('/contents/{contentId}', { params: { path: { contentId: id } } })).then((detail) => showContent(summaryOf(detail), false)).catch(() => {})
    } else if (p) {
      const id = Number(p.params.placeId)
      const pending = pendingStore.get().place
      // 이미 연 곳이면 다시 받지 않는다 — 받아 오면 작품 상세 위에 연 촬영지의 작품이 지워진다.
      if (!Number.isFinite(id) || pending?.id === id || selectedPlaceId.current === id) return
      void data.detailOfPlace(id).then((found) => {
        selectedContentId.current = null
        setSelectedContent(null)
        setContentPlaces([])
        setSelectedPlace(placeSummaryOf(found))
        setDetent('medium')
        setFocusToken((n) => n + 1)
      }).catch(() => {})
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- 주소가 바뀔 때만.
  }, [pathname])

  // 자동완성 — 글자마다 부르되 응답이 오는 사이 검색어가 바뀌었으면 버린다(늦게 온 응답이 최신을 덮는다).
  useEffect(() => {
    const query = draft.trim()
    if (query === '') {
      setSuggestions([])
      topWorkRef.current = null
      setTopWork(null)
      return
    }
    let stale = false
    void (async () => {
      const items = await ok(api.GET('/search/suggestions', { params: { query: { q: query, limit: SUGGEST_LIMIT } } })).then((r) => r.items).catch(() => [] as Suggestion[])
      if (stale) return
      setSuggestions(items)
      // 첫 작품 후보만 포스터·메타까지 채운다. 없으면 카드도 없다.
      const first = items.find((s) => s.type === 'content')
      if (!first) {
        topWorkRef.current = null
        setTopWork(null)
        return
      }
      if (topWorkRef.current?.id === first.id) return
      const detail = await ok(api.GET('/contents/{contentId}', { params: { path: { contentId: first.id } } })).catch(() => null)
      if (stale) return
      topWorkRef.current = detail
      setTopWork(detail)
    })()
    return () => {
      stale = true
    }
  }, [draft])

  return {
    data, scene, cart, likes,
    draft, setDraft, committed, tab, setTab, chip, setChip, detent, setDetent,
    selectedPlace, selectedContent, contentPlaces, contentLoading, suggestions, topWork,
    fitToken, focusToken, panToken, panTarget, koreaToken, locateToken, setLocateToken,
    showCart, setShowCart, nearby, locateFailure, setLocateFailure, outsideKorea, sheetHeight, setSheetHeight,
    searchFocused, setSearchFocused, chipFillRetry, bumpChipFill: () => setChipFillRetry((n) => n + 1),
    camera, inputRef, initial, visiblePlaces, mapPlaces, numbered, location,
    commit, openContent, openPlace, save, toggleLike, selectPlaceSuggestion, closePlace, closeContent, clearSearch,
    onTapPin, searchViewport, toggleNearby, blurSearch,
    setKoreaToken,
  }
}
