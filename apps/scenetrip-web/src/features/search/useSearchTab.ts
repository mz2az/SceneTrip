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
import { patterns, paths } from '@/app/routes'
import { useLocationConsent } from '@/features/profile/consent'
import { cartActions, useCart } from '@/state/cart'
import { likeActions, useLikes } from '@/state/likes'
import { useOnAccountChange } from '@/state/shared'
import { useStore } from '@/state/storage'
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

export function useSearchTab() {
  const dataRef = useRef<SceneData | null>(null)
  if (!dataRef.current) dataRef.current = new SceneData()
  const data = dataRef.current
  const scene = useSceneState(data)
  const cart = useCart()
  const likes = useLikes()
  const location = useLocationConsent()
  const navigate = useNavigate()
  const { pathname } = useLocation()

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
  const inputRef = useRef<HTMLInputElement>(null)

  const initial = isInitialScreen(committed, nearby, selectedContent !== null)
  const visiblePlaces = useMemo(() => visiblePlacesOf(scene.places, chip, initial), [scene.places, chip, initial])
  /** 지도에 꽂는 핀 — 작품을 골랐으면 그 작품의 촬영지, 아니면 검색 결과. 시트 목록과 같은 배열이라 행 번호가 곧 핀 번호다. */
  const mapPlaces = selectedContent ? contentPlaces : visiblePlaces
  const numbered = numbersOnPins(initial, tab)

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
  const openContent = useCallback(
    (content: ContentSummary) => {
      selectedContentId.current = content.id
      setSelectedContent(content)
      setContentPlaces([])
      setContentLoading(true)
      setSelectedPlace(null)
      blurSearch()
      setDetent('medium')
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
    [data],
  )

  const openPlace = useCallback((place: PlaceSummary) => {
    setSelectedPlace(place)
    setFocusToken((n) => n + 1)
  }, [])

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

  const closePlace = () => {
    setSelectedPlace(null)
    setFitToken((n) => n + 1)
    leaveDetailUrl()
  }
  const closeContent = () => {
    selectedContentId.current = null
    setSelectedContent(null)
    setContentPlaces([])
    setFitToken((n) => n + 1)
    leaveDetailUrl()
  }
  /** URL 로 직접 연 상세에서 나오면 주소를 탭 주소로 되돌린다. */
  function leaveDetailUrl() {
    if (pathname.startsWith(paths.search + '/')) navigate(paths.search, { replace: true })
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
    },
    [],
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
    const source = camera.current!.source()
    // 기기 위치에서 나온 범위면 위치 동의를 먼저 거친다.
    if (source === 'device') location.request(() => applyViewportSearch(box, source))
    else applyViewportSearch(box, source)
  }, [applyViewportSearch, location])

  const toggleNearby = () => {
    if (nearby) {
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
    if (tab === 'place' && visiblePlaces.length > 0) setFitToken((n) => n + 1)
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
        return
      }
      // 받아 둔 첫 쪽에 없을 수 있다(흔한 이름이면 결과가 한 쪽을 넘는다) — 그 곳을 직접 받는다(MZ2AZ-372).
      void data.detailOfPlace(wanted).then((found) => {
        if (selectedContentId.current !== null) return
        setSelectedPlace((cur) => cur ?? placeSummaryOf(found))
        setFocusToken((n) => n + 1)
      }).catch(() => {})
    }
    setFitToken((n) => n + 1)
  }, [scene.phase, scene.places, data])

  // 홈이 남긴 「이 작품/이 촬영지 열어 줘」 쪽지 — 요약을 그대로 받아 상세를 연다. 자기 목록에서 찾지 않는다(검색한 뒤면 목록이 걸러져 있다). 쪽지는 한 번 읽고 버린다.
  const pendingContent = useStore(pendingStore, (s) => s.content)
  const pendingPlace = useStore(pendingStore, (s) => s.place)
  useEffect(() => {
    if (!pendingContent) return
    const wanted = consumePending('content')
    if (wanted) openContent(wanted)
  }, [pendingContent, openContent])
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
    setFitToken((n) => n + 1)
  }, [pendingPlace])

  // URL(`/search/content/:id`, `/search/place/:id`)로 직접 열었을 때도 같은 상세가 열려야 한다.
  useEffect(() => {
    const c = matchPath(patterns.searchContent, pathname)
    const p = matchPath(patterns.searchPlace, pathname)
    if (c) {
      const id = Number(c.params.contentId)
      const pending = pendingStore.get().content
      if (!Number.isFinite(id) || selectedContentId.current === id || pending?.id === id) return
      void ok(api.GET('/contents/{contentId}', { params: { path: { contentId: id } } })).then((detail) => openContent(summaryOf(detail))).catch(() => {})
    } else if (p) {
      const id = Number(p.params.placeId)
      const pending = pendingStore.get().place
      if (!Number.isFinite(id) || pending?.id === id) return
      void data.detailOfPlace(id).then((found) => {
        selectedContentId.current = null
        setSelectedContent(null)
        setContentPlaces([])
        setSelectedPlace(placeSummaryOf(found))
        setDetent('medium')
        setFitToken((n) => n + 1)
      }).catch(() => {})
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- 주소가 바뀔 때만.
  }, [pathname])

  // 담기 알림은 2초 뒤 사라진다.
  useEffect(() => {
    if (!cart.toast) return
    const t = setTimeout(() => cartActions.clearToast(), 2000)
    return () => clearTimeout(t)
  }, [cart.toast])

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
