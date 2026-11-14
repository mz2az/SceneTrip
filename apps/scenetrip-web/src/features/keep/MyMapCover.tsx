/*
 * 내 지도 — 덮개 `/my-map`. 정본: docs/project/plans/web-keep-flow.md §5-3·§6, 편의시설은 keep-poi.md §3-3·§7·§8.
 *
 *   머리줄 「내 지도 · N곳」(N = 촬영지 + 편의시설, myMapCount) + 뒤로
 *   위 약 42% 지도 — 담은 촬영지 핀(좌표가 있고 목록에서 빠지지 않은 것만) + 담은 편의시설 핀(갈래 색, savedPoiPin), 전부가 보이게 맞춘다
 *     지도 위 칩 줄(NearbyChips, keepStore.nearby) — 켜면 담은 곳 둘레(nearbyBbox)의 그 종류 편의시설을 받아 점으로(코스 편집 화면과 같은 점·층,
 *     route/nav/useMapOverlay), 사용자가 지도를 옮기면 보이는 영역으로 다시 받는다. 점·담은 핀·목록 줄을 누르면 정보 카드(route/poi/PlaceCard).
 *   아래 스크롤 목록 — 작품별 묶음(mapGroups)마다 소제목 「작품 · N곳」 과 줄(사진·이름·주소), 맨 아래 「주변에서 담은 곳」(SavedPoiRow)
 *   아래 고정 — 주 「이 N곳으로 계획 짜기」(openPlanFromSaved), 보조 「더 담기」(/add, web-ad-landing.md §2)
 *
 * web-ad-landing.md §9-5·§9-7(VisitKorea 일정 구조): 지도는 위에 고정, 목록이 스크롤되면 보고 있는 줄의 핀이 커지고 강조된다(지도도 그쪽으로).
 * 줄 사이에는 다음 장소까지의 직선 거리 칩(myMapRows). 촬영지 줄 썸네일 모서리에 장면 사진(화면에 보인 줄만 늦게 GET /places/{id}).
 * 머리줄 오른쪽 「내 지도」 단추(배지), 앱 안 브라우저면 머리줄 아래 띠(§3).
 *
 * 받는 중 · 비었음 · 실패(다시 시도) · 됨. 덮개는 닫히면 내려간 뒤 빠지므로(Cover 의 usePresence) 숨은 지도가 타일을 받지 않는다.
 * 덮개가 빠지면 주변 칩(keepStore.nearby)을 끈다.
 */
import { type JSX, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { track, webEvent } from '@/analytics'
import { api, ok } from '@/api/client'
import { paths } from '@/app/routes'
import { useTabRouter } from '@/app/tabRouter'
import { Icon } from '@/design/Icon'
import { HStack, Spinner, Txt, VStack } from '@/design/primitives'
import { PhotoGallery } from '@/features/reviews/photoGallery'
import { bookOf } from '@/features/reviews/photoGalleryRules'
import { GalleryViewer } from '@/features/reviews/PhotoViewers'
import { ReviewsSheet } from '@/features/reviews/ReviewsSheet'
import { spreadPois } from '@/features/proto/protoPoi'
import { PrimaryButton, SecondaryButton } from '@/features/proto/protoParts'
import { color, contained, radius } from '@/features/proto/protoTokens'
import { unavailableMessage } from '@/features/route/availability'
import { useMapOverlay } from '@/features/route/nav/useMapOverlay'
import { emptyOverlay } from '@/features/route/nav/mapOverlay'
import { PlaceCard } from '@/features/route/poi/PlaceCard'
import type { CardPhoto } from '@/features/route/poi/poiCard'
import { type PlaceTarget, targetOf, withoutDotsUnderPins } from '@/features/route/poi/placePoiLink'
import { poiGlyph, poiGroupOf } from '@/features/route/poi/poiGroup'
import { type RoutePlace, fromPoi, poiIdOfPlace } from '@/features/route/poi/poiPlace'
import { Pressable } from '@/features/route/ui/Press'
import { MapView } from '@/features/search/map/MapView'
import { CAMERA_TOP_INSET, PLACE_ZOOM } from '@/features/search/map/mapRules'
import { project } from '@/map/mercator'
import type { PlaceSummary } from '@/features/search/sceneData'
import { say, useLang } from '@/i18n'
import type { MapHandle } from '@/map'
import { smallImage } from '@/shared/smallImage'
import { type CartItem, type CartPoi, cartActions, cartPoiBusyStore, useCart, usePoiBusy } from '@/state/cart'
import { useStore } from '@/state/storage'
import { spots } from '@/shared/spots'
import { type NearbyKind, myMapCount, nearbyBbox, nearbyQuery, usableCount } from './keepPoiRules'
import { mapGroups } from './keepRules'
import { keepActions, useKeep } from './keepStore'
import { NearbyChips } from './NearbyChips'
import { SAVED_POI_PIN, savedPoiPinHtml } from './savedPoiPin'
import { SavedPoiRow } from './SavedPoiRow'
import { MyMapButton, flyToMyMap } from './flyToMyMap'
import { InAppBand } from './InAppBand'
import { distanceLabel, metersBetween } from './myMapRows'
import { pickSceneThumb } from '@/features/proto/sceneFlip'
import { createStore } from '@/state/storage'

/** 지도 칸의 높이 — 덮개 높이의 약 42%(위 절반쯤, 목록이 한두 줄 더 보이게). */
const MAP_SHARE = '42%'
/** 지도 위 칩 줄이 차지하는 높이(px) — 카메라가 핀을 이 아래에 맞춘다. 칩의 눌리는 자리가 44px 이다(§9-6). */
const CHIPS_H = 52
/** 정보 카드가 떠 있어도 칩 줄 아래로 남겨 두는 지도 띠의 높이(px) — 고른 곳이 여기에 온다. */
const CARD_PEEK = 76
/** 핀 전체에 맞출 때의 둘레 여백(px). */
const FIT_PAD = 36
/** 주변 편의시설 한 번에 받는 수(§3-3). */
const NEARBY_LIMIT = 60
/** 지도를 옮긴 뒤 이만큼 조용하면 보이는 영역으로 다시 받는다(코스 편집 화면의 주변 점과 같은 값). */
const NEARBY_QUIET_MS = 350
/** 지도가 아직 없을 때(첫 받기)의 spreadZoom. */
const DEFAULT_SPREAD_ZOOM = 14
/** 담은 편의시설 핀의 z — 촬영지 핀 위, 고른 점 아래. */
const Z_SAVED_POI = 20

const hasCoords = (i: CartItem): i is CartItem & { latitude: number; longitude: number } =>
  typeof i.latitude === 'number' && typeof i.longitude === 'number' && !Number.isNaN(i.latitude) && !Number.isNaN(i.longitude)

const poiHasCoords = (p: CartPoi): boolean => Number.isFinite(p.latitude) && Number.isFinite(p.longitude)

/** 담은 항목 → 촬영지 상세·지도 핀이 받는 모양. 좌표가 없으면 0 으로 둔다(상세는 id 로 다시 받는다). */
function placeOf(i: CartItem): PlaceSummary {
  return {
    id: i.placeId,
    name: i.name,
    address: i.address ?? null,
    latitude: i.latitude ?? 0,
    longitude: i.longitude ?? 0,
    imageUrl: i.imageUrl ?? null,
    imageThumbnailUrl: i.imageThumbnailUrl ?? null,
  }
}

/** 지도에서 핀이 서는 곳 — 촬영지 핀(목록에서 빠지지 않은 것)과 담은 편의시설. 주변 둘레(nearbyBbox)와 카메라가 이것을 쓴다. */
function savedPoints(items: readonly CartItem[], pois: readonly CartPoi[]): { latitude: number; longitude: number }[] {
  return [
    ...items.filter((i) => hasCoords(i) && i.delisted !== true).map((i) => ({ latitude: i.latitude!, longitude: i.longitude! })),
    ...pois.filter(poiHasCoords).map((p) => ({ latitude: p.latitude, longitude: p.longitude })),
  ]
}

/** 강조 핀의 크기(px). */
const FOCUS_PIN = { w: 48, h: 60 }

/** 강조 핀 — 사진을 품은 큰 보라 물방울(랜딩의 핀과 같은 모양). */
function focusPinHtml(photo: string | null): string {
  const img = photo ? `<img src="${photo.replace(/"/g, '&quot;')}" alt="" referrerpolicy="no-referrer" />` : ''
  return `<span class="st-focus-pin">${img}</span>`
}

const spreadZoomOf = (map: MapHandle | null): number => (map ? Math.max(0, Math.min(22, Math.floor(map.getZoom()))) : DEFAULT_SPREAD_ZOOM)

/** 지도 칸의 보이는 영역 — 지도 판은 위로 CAMERA_TOP_INSET 만큼 칸 밖에 있다(PinMap). 그 아래부터 바닥까지의 bbox. */
function visibleBbox(map: MapHandle): string {
  const size = map.getSize()
  const nw = map.containerPointToLatLng([0, Math.min(size.y - 1, CAMERA_TOP_INSET)])
  const se = map.containerPointToLatLng([size.x, size.y])
  return [nw.lng, se.lat, se.lng, nw.lat].map((n) => n.toFixed(5)).join(',')
}

type NearbyPhase = 'idle' | 'loading' | 'loaded' | 'failed'

/**
 * 주변 편의시설 받기(§3-3·§7) — 칩이 켜지면 담은 곳 둘레(`nearbyBbox`)로 받고, 사람이 지도를 옮기면 보이는 영역으로 다시 받는다.
 * 코스 편집 화면(useAmbientPois)과 같은 「멈추고 0.35초」 규칙이되, 첫 받기는 지도 없이도 나간다(지도가 늦게 서거나 없는 환경에서도 칩이 동작한다).
 */
function useNearbyPois(kind: NearbyKind | null, map: MapHandle | null, points: readonly { latitude: number; longitude: number }[]) {
  const [state, setState] = useState<{ phase: NearbyPhase; places: RoutePlace[] }>({ phase: 'idle', places: [] })
  const ticket = useRef(0)
  const lastBbox = useRef<string | null>(null)
  const latest = useRef({ kind, map, points })
  latest.current = { kind, map, points }

  const load = useCallback((bbox: string) => {
    const k = latest.current.kind
    if (!k) return
    lastBbox.current = bbox
    const mine = (ticket.current += 1)
    setState((s) => ({ ...s, phase: 'loading' }))
    const query = { bbox, ...nearbyQuery(k), sort: 'spread' as const, spreadZoom: spreadZoomOf(latest.current.map), limit: NEARBY_LIMIT }
    ok(api.GET('/pois', { params: { query } }))
      .then((list) => {
        if (mine === ticket.current) setState({ phase: 'loaded', places: list.items.map(fromPoi) })
      })
      .catch(() => {
        if (mine === ticket.current) setState({ phase: 'failed', places: [] })
      })
  }, [])

  // 칩이 바뀌면 담은 곳 둘레로. 담은 곳이 아직 없으면(장바구니를 받는 중) 생길 때 받는다.
  const hasPoints = points.length > 0
  useEffect(() => {
    ticket.current += 1
    if (!kind) {
      lastBbox.current = null
      setState({ phase: 'idle', places: [] })
      return
    }
    const bbox = nearbyBbox(latest.current.points)
    if (bbox) load(bbox)
  }, [kind, hasPoints, load])

  // 사람이 지도를 옮기면(끌기·휠·두 손가락) 보이는 영역으로 다시 받는다. 카메라를 코드가 옮긴 것(핀 맞추기·줄을 눌러 옮김)은 세지 않는다.
  const userMoved = useRef(false)
  useEffect(() => {
    if (!map) return
    const el = map.container
    const touched = () => {
      userMoved.current = true
    }
    el.addEventListener('pointerdown', touched)
    el.addEventListener('wheel', touched, { passive: true })
    let timer: ReturnType<typeof setTimeout> | null = null
    const off = map.on('moveend', () => {
      if (!userMoved.current || !latest.current.kind) return
      if (timer) clearTimeout(timer)
      timer = setTimeout(() => load(visibleBbox(map)), NEARBY_QUIET_MS)
    })
    return () => {
      if (timer) clearTimeout(timer)
      off()
      el.removeEventListener('pointerdown', touched)
      el.removeEventListener('wheel', touched)
    }
  }, [map, load])

  const retry = useCallback(() => {
    const bbox = lastBbox.current ?? nearbyBbox(latest.current.points)
    if (bbox) load(bbox)
  }, [load])
  /** 코드가 카메라를 옮기기 직전에 부른다 — 그 이동으로는 다시 받지 않는다. */
  const cameraByCode = useCallback(() => {
    userMoved.current = false
  }, [])

  return { ...state, retry, cameraByCode }
}

/** 덮개가 빠질 때 주변 칩을 끄는 예약 — 곧바로 다시 붙으면(StrictMode) 취소한다. */
let pendingNearbyReset: ReturnType<typeof setTimeout> | null = null

export function MyMapCover({ onClose }: { onClose: () => void }): JSX.Element {
  useLang()
  const router = useTabRouter()
  const cart = useCart()
  // `?keep=` 으로 넘어와 담는 중이면 빈 화면 대신 받는 중(§9-4).
  const { arriving, nearby } = useKeep()

  // 홈을 거치지 않고 주소로 바로 들어온 경우 — 아직 받은 적이 없으면 받는다.
  useEffect(() => {
    if (cart.loadState === 'notLoaded') void cartActions.refresh()
    // eslint-disable-next-line react-hooks/exhaustive-deps -- 열릴 때 한 번.
  }, [])
  // 덮개가 빠지면 주변 칩을 끈다(§7·§8).
  // 끄기는 한 박자 미룬다 — 개발 모드(StrictMode)는 붙이자마자 떼었다 다시 붙이므로, 바로 끄면 시트가 켜 두고 연 칩이 꺼진다.
  useEffect(() => {
    if (pendingNearbyReset) clearTimeout(pendingNearbyReset)
    pendingNearbyReset = null
    return () => {
      pendingNearbyReset = setTimeout(() => {
        pendingNearbyReset = null
        keepActions.setNearby(null)
      }, 0)
    }
  }, [])

  const items = cart.items
  const pois = cart.pois
  const saved = myMapCount(items.length, pois.length)
  // 머리줄·「이 N곳으로」 의 N 은 목록에서 빠진 촬영지·폐업한 편의시설을 뺀 수(§9-5) — 그것들도 목록·핀에는 흐리게 남는다.
  const count = usableCount(items, pois)
  // 다시 받는 중이거나 다시 받기가 실패해도 들고 있는 목록은 그대로 보인다.
  const phase: 'loading' | 'failed' | 'empty' | 'loaded' =
    saved > 0 ? 'loaded' : arriving ? 'loading' : cart.loadState === 'failed' ? 'failed' : cart.loadState === 'loaded' ? 'empty' : 'loading'

  const openPlace = (item: CartItem) => {
    track(webEvent.myMap('open_place'))
    // 상세의 「뒤로」 가 내 지도로 돌아오게(§9-9).
    router.openPlace(placeOf(item), { returnTo: paths.myMap })
  }

  // ---- 지도·주변 편의시설·정보 카드
  const [map, setMap] = useState<MapHandle | null>(null)
  /** 목록에서 보고 있는 촬영지 줄(§9-5) — 그 핀을 강조한다. 스크롤하기 전에는 없다. */
  const [focus, setFocus] = useState<number | null>(null)
  const [refit, setRefit] = useState(0)
  const points = useMemo(() => savedPoints(items, pois), [items, pois])
  const ambient = useNearbyPois(nearby, map, points)
  const [card, setCard] = useState<RoutePlace | null>(null)
  const [reloadToken, setReloadToken] = useState(0)
  const [reviewing, setReviewing] = useState<{ target: PlaceTarget; title: string } | null>(null)
  const [viewing, setViewing] = useState<{ gallery: PhotoGallery; title: string; index: number } | null>(null)

  const toggleNearby = (kind: NearbyKind | null) => {
    // 내 지도에서 사람이 켤 때만 센다 — 시트에서 켜 두고 연 것은 keep_nearby 가 이미 셌다(§8).
    if (kind) track(webEvent.myMapNearby(kind))
    keepActions.setNearby(kind)
  }

  /**
   * 카드를 연다 — 카드가 지도 칸 아래쪽을 덮으므로 그곳을 칩 줄 바로 아래의 덮이지 않은 띠(CARD_PEEK)에 오게 옮긴다.
   * 줄을 눌러 열면(`zoomIn`) 그 동네가 보이게 확대도 하고, 지도의 점·핀을 눌렀으면 줌은 그대로 둔다.
   */
  const openCard = (place: RoutePlace, zoomIn: boolean) => {
    setCard(place)
    if (!map) return
    ambient.cameraByCode()
    const target = CAMERA_TOP_INSET + CHIPS_H + CARD_PEEK / 2
    const shiftY = map.getSize().y / 2 - target
    map.setView({ lat: place.latitude, lng: place.longitude }, zoomIn ? Math.max(map.getZoom(), PLACE_ZOOM - 1) : null, { animate: true, seconds: 0.4, shiftY })
  }
  const openSavedPoi = (p: CartPoi, move: boolean) => openCard(fromPoi(p), move)

  const cardPoiId = card ? poiIdOfPlace(card) : null
  const cardLinked = card?.linkedPlaceId ?? null
  const cardAdded = card !== null && ((cardPoiId !== null && cart.poiIds.has(cardPoiId)) || (cardLinked !== null && cart.placeIds.has(cardLinked)))
  // 담거나 빼는 중이면 단추가 눌리지 않는 모양 — 두 번째 누름은 요청을 보내지 않는다(§9-4).
  const cardBusy = usePoiBusy(cardPoiId)
  const cardBox = useRef<HTMLDivElement>(null)
  const addCard = () => {
    if (cardPoiId === null || cardBusy) return
    // 담는 순간 카드가 「내 지도」 로 날아간다(web-ad-landing.md §9-3).
    flyToMyMap(cardBox.current)
    void cartActions.addPoi({ id: cardPoiId, placeId: cardLinked })
  }
  const removeCard = () => {
    if (cardBusy) return
    if (cardPoiId !== null && cart.poiIds.has(cardPoiId)) void cartActions.removePoi(cardPoiId)
    else if (cardLinked !== null && cart.placeIds.has(cardLinked)) void cartActions.remove(cardLinked)
  }
  const openPhotos = (photos: CardPhoto[], index: number, title: string) => {
    const target = card ? targetOf(card) : null
    if (!target) return
    const gallery = new PhotoGallery(target)
    gallery.show(bookOf(photos, photos.length, []), target, gallery.generation)
    setViewing({ gallery, title, index })
  }

  // 주변 점 — 담은 편의시설(핀으로 따로 선다)과 담은 촬영지와 같은 곳은 또 그리지 않는다. 칸마다 1등만(코스 편집 화면 시안과 같은 솎기).
  const [zoom, setZoom] = useState(0)
  useEffect(() => {
    if (!map) return
    setZoom(map.getZoom())
    return map.on('zoomend', () => setZoom(map.getZoom()))
  }, [map])
  const dots = useMemo(
    () => (nearby ? withoutDotsUnderPins(ambient.places, cart.placeIds, cart.poiIds) : []),
    [nearby, ambient.places, cart.placeIds, cart.poiIds],
  )
  const shownDots = useMemo(() => (map ? spreadPois(dots, zoom).major : dots), [map, dots, zoom])
  const pickedDot = card && dots.some((d) => d.id === card.id) ? card : null
  const overlay = useMemo(() => ({ ...emptyOverlay, ambient: shownDots, picked: pickedDot }), [shownDots, pickedDot])
  useMapOverlay(map, overlay, { onSelectPlace: (p) => openCard(p, false) })

  const nearbyNote =
    nearby && ambient.phase === 'failed'
      ? 'failed'
      : nearby && ambient.phase === 'loaded' && dots.length === 0
        ? 'empty'
        : null

  return (
    <VStack spacing={0} align="stretch" style={{ height: '100%', minHeight: 0, background: 'var(--grouped-background)', paddingTop: 'var(--safe-top)' }}>
      <HStack spacing={4} style={{ padding: '4px 8px', minHeight: 52, flex: 'none' }}>
        <Pressable
          plain
          onClick={onClose}
          aria-label={say('뒤로', 'Back')}
          style={{ width: 44, height: 44, display: 'grid', placeItems: 'center', flex: 'none' }}
        >
          <Icon name="chevron.left" size={20} weight="semibold" />
        </Pressable>
        <Txt as="h1" size={19} weight="bold" lines={1} style={{ margin: 0, flex: 1, minWidth: 0 }}>
          {count > 0 ? say(`마이맵 · ${count}곳`, `My map · ${spots(count)}`) : say('마이맵', 'My map')}
        </Txt>
        {/* 「내 지도」 단추 — 다른 화면과 같은 자리(§9-3). 여기서는 담은 곳 전체가 보이게 지도를 다시 맞춘다. */}
        <MyMapButton onPress={() => { setFocus(null); setRefit((n) => n + 1) }} style={{ marginRight: 4 }} />
      </HStack>
      <InAppBand />

      {phase === 'loading' && (
        <div style={{ flex: 1, display: 'grid', placeItems: 'center' }} aria-busy="true">
          <Spinner size={28} />
        </div>
      )}

      {phase === 'failed' && (
        <Notice text={say('마이맵을 못 불러왔어요', "Couldn't load My map")}>
          <PrimaryButton onClick={() => void cartActions.refresh()}>{say('다시 시도', 'Try again')}</PrimaryButton>
        </Notice>
      )}

      {phase === 'empty' && (
        <Notice text={say('아직 담은 곳이 없어요', 'Nothing saved yet')}>
          <PrimaryButton onClick={() => router.openTrending()}>{say('작품 둘러보기', 'Browse shows')}</PrimaryButton>
        </Notice>
      )}

      {phase === 'loaded' && (
        <>
          {/* 지도 + 목록. 정보 카드는 이 칸의 아래에 떠서 아래 고정 단추를 가리지 않는다. */}
          <div style={{ position: 'relative', flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column' }}>
            <PinMap
              items={items}
              pois={pois}
              map={map}
              onMap={setMap}
              beforeCamera={ambient.cameraByCode}
              onTapPin={(place) => {
                const item = items.find((i) => i.placeId === place.id)
                if (item) openPlace(item)
              }}
              onTapPoi={(p) => openSavedPoi(p, false)}
              focus={focus}
              refit={refit}
            >
              <VStack spacing={2} align="stretch" style={{ position: 'absolute', top: 4, left: 0, right: 0, zIndex: 5, pointerEvents: 'none' }}>
                <div style={{ pointerEvents: 'auto' }}>
                  <NearbyChips value={nearby} loading={nearby !== null && ambient.phase === 'loading'} onChange={toggleNearby} onMap />
                </div>
                {nearbyNote && (
                  <div role="status" style={{ alignSelf: 'center', pointerEvents: 'auto', maxWidth: 'calc(100% - 32px)' }}>
                    <HStack spacing={8} style={{ padding: '6px 6px 6px 12px', borderRadius: radius.pill, background: 'var(--system-background)', boxShadow: '0 1px 4px rgba(0,0,0,0.22)' }}>
                      <Txt size={13} weight="semibold" lines={2}>
                        {nearbyNote === 'failed' ? say('불러오지 못했어요', "Couldn't load") : say('이 둘레에는 없어요. 지도를 옮겨 보세요', 'None around here. Try moving the map.')}
                      </Txt>
                      {nearbyNote === 'failed' ? (
                        // 보이는 캡슐은 30px 그대로, 눌리는 자리는 44px(§9-6) — 위아래로 7px 씩 넘친다.
                        <Pressable plain onClick={ambient.retry} style={{ flex: 'none', minHeight: 44, margin: '-7px 0', padding: '0 2px', display: 'grid', placeItems: 'center' }}>
                          <span style={{ display: 'grid', placeItems: 'center', minHeight: 30, padding: '0 10px', borderRadius: radius.pill, background: 'color-mix(in srgb, var(--accent) 14%, transparent)' }}>
                            <Txt size={13} weight="bold" color="accent">{say('다시 시도', 'Try again')}</Txt>
                          </span>
                        </Pressable>
                      ) : (
                        <span style={{ width: 6 }} />
                      )}
                    </HStack>
                  </div>
                )}
              </VStack>
            </PinMap>
            <GroupedList items={items} pois={pois} onOpen={openPlace} onOpenPoi={(p) => openSavedPoi(p, true)} onFocus={setFocus} />
            {card && (
              <div ref={cardBox} style={{ position: 'absolute', left: 12, right: 12, bottom: 12, zIndex: 20, maxHeight: `calc(100% - ${CHIPS_H + CARD_PEEK + 12}px)`, overflowY: 'auto', borderRadius: 16, ...contained }}>
                <PlaceCard
                  place={card}
                  added={cardAdded}
                  onAdd={cardPoiId !== null ? addCard : undefined}
                  onRemove={removeCard}
                  addLabel={say('마이맵에 담기', 'Save to My map')}
                  addedLabel={say('마이맵에 있음 · 빼기', 'On My map · Remove')}
                  addBusy={cardBusy}
                  onClose={() => setCard(null)}
                  reloadToken={reloadToken}
                  onOpenReviews={(target, title) => setReviewing({ target, title })}
                  onOpenPhotos={openPhotos}
                />
              </div>
            )}
          </div>
          <div
            style={{
              flex: 'none', padding: '12px 20px calc(12px + var(--safe-bottom))', background: 'var(--system-background)',
              borderTop: '0.5px solid var(--separator)',
            }}
          >
            <VStack spacing={8} align="stretch">
              <PrimaryButton
                onClick={() => {
                  track(webEvent.myMap('plan'))
                  track(webEvent.planStart('my_map'))
                  router.openPlanFromSaved()
                }}
              >
                {count > 0 ? say(`이 ${count}곳으로 계획 짜기`, `Plan a trip with these ${spots(count)}`) : say('계획 짜기', 'Plan my trip')}
              </PrimaryButton>
              <SecondaryButton
                onClick={() => {
                  track(webEvent.myMap('add_more'))
                  router.navigate(paths.addMore)
                }}
              >
                {say('더 담기', 'Add more')}
              </SecondaryButton>
            </VStack>
          </div>
        </>
      )}

      <ReviewsSheet
        open={reviewing !== null}
        onClose={() => setReviewing(null)}
        subject={reviewing?.target ?? { kind: 'place', id: 0 }}
        title={reviewing?.title ?? ''}
        onChanged={() => setReloadToken((n) => n + 1)}
      />
      {viewing && (
        <GalleryViewer
          gallery={viewing.gallery}
          open
          onClose={() => {
            setViewing(null)
            setReloadToken((n) => n + 1)
          }}
          index={viewing.index}
          onIndex={(index) => setViewing((v) => (v ? { ...v, index } : v))}
          reviews={{ kind: 'sheet', title: viewing.title, onChanged: () => setReloadToken((n) => n + 1) }}
          entry="card"
        />
      )}
    </VStack>
  )
}

function Notice({ text, children }: { text: string; children: JSX.Element }) {
  return (
    <VStack spacing={16} align="center" style={{ flex: 1, justifyContent: 'center', padding: '24px 32px calc(24px + var(--safe-bottom))' }}>
      <Icon name="mappin.and.ellipse" size={36} color="secondary" />
      <Txt size={17} weight="semibold" align="center">{text}</Txt>
      <div style={{ width: '100%', maxWidth: 320 }}>{children}</div>
    </VStack>
  )
}

/**
 * 담은 곳 핀 지도 — 검색 탭의 지도(MapView)를 그대로 쓴다. 그 지도는 카메라를 맞출 때 위쪽 CAMERA_TOP_INSET(검색바 자리)을 비우므로,
 * 지도 판을 그만큼 위로 늘려 칸 밖(잘린 곳)에 두었다. 카메라는 여기서 지도 핸들(`onMap`)로 직접 맞춘다 — 촬영지 핀과 담은 편의시설 핀을
 * 함께, 지도 위 칩 줄 아래에 들어오게. 담은 편의시설 핀은 촬영지 핀과 다른 색(savedPoiPin)이고 칩을 꺼도 남는다.
 */
function PinMap({ items, pois, map, onMap, beforeCamera, onTapPin, onTapPoi, focus = null, refit = 0, children }: {
  items: readonly CartItem[]
  pois: readonly CartPoi[]
  map: MapHandle | null
  onMap: (map: MapHandle | null) => void
  /** 코드가 카메라를 옮기기 직전 — 주변 점이 그 이동으로 다시 받지 않게. */
  beforeCamera: () => void
  onTapPin: (place: PlaceSummary) => void
  onTapPoi: (poi: CartPoi) => void
  /** 목록에서 보고 있는 촬영지 — 그 핀을 크게 강조하고, 지도 칸 밖이면 그쪽으로 옮긴다(§9-5). */
  focus?: number | null
  /** 늘면 담은 곳 전체에 다시 맞춘다. */
  refit?: number
  children?: JSX.Element
}) {
  // 좌표가 없거나 목록에서 빠진 촬영지는 핀을 꽂지 않는다(목록에만).
  const pins = useMemo(() => items.filter((i) => hasCoords(i) && i.delisted !== true).map(placeOf), [items])
  const poiPins = useMemo(() => pois.filter(poiHasCoords), [pois])
  const latest = useRef({ onTapPoi, beforeCamera, pins, poiPins })
  latest.current = { onTapPoi, beforeCamera, pins, poiPins }

  // 담은 편의시설 핀 — 지도 위 따로 한 층.
  useEffect(() => {
    if (!map) return
    const layer = map.createLayer()
    for (const p of poiPins) {
      const group = poiGroupOf(p.categoryGroup, p.category)
      const closed = p.closed === true
      layer.addMarker({
        position: { lat: p.latitude, lng: p.longitude },
        html: savedPoiPinHtml(group, poiGlyph(group, p.category), closed),
        className: 'st-pin',
        size: [SAVED_POI_PIN.w, SAVED_POI_PIN.h],
        anchor: [SAVED_POI_PIN.tipX, SAVED_POI_PIN.tipY],
        zIndex: Z_SAVED_POI,
        title: p.displayName ?? p.name,
        // 폐업한 곳은 목록 줄처럼 열리지 않는다.
        interactive: !closed,
        onClick: closed ? undefined : () => latest.current.onTapPoi(p),
      })
    }
    return () => layer.remove()
  }, [map, poiPins])

  // 카메라 — 지도가 서면 한 번, 담은 촬영지가 바뀔 때 다시 전체에 맞춘다. 편의시설을 담고 뺄 때는 맞추지 않는다(주변을 보던 화면이 튀지 않게).
  const placeKey = pins.map((p) => `${p.id}@${p.latitude},${p.longitude}`).join('|')
  useEffect(() => {
    if (!map) return
    const all = [...latest.current.pins, ...latest.current.poiPins].map((p) => ({ lat: p.latitude, lng: p.longitude }))
    if (all.length === 0) return
    latest.current.beforeCamera()
    const top = CAMERA_TOP_INSET + CHIPS_H
    if (all.length === 1) {
      map.setView(all[0]!, PLACE_ZOOM, { animate: false, shiftY: -top / 2 })
      return
    }
    const lats = all.map((p) => p.lat)
    const lngs = all.map((p) => p.lng)
    map.fitBounds(
      { southWest: { lat: Math.min(...lats), lng: Math.min(...lngs) }, northEast: { lat: Math.max(...lats), lng: Math.max(...lngs) } },
      { paddingTopLeft: [FIT_PAD, FIT_PAD + top], paddingBottomRight: [FIT_PAD, FIT_PAD], animate: false, maxZoom: 17 },
    )
  }, [map, placeKey, refit])

  // 보고 있는 줄의 핀 — 큰 사진 핀을 위에 겹친다. 지도 칸(칩 줄 아래) 밖이면 그 핀이 가운데 오게 옮긴다.
  useEffect(() => {
    if (!map || focus === null) return
    const pin = latest.current.pins.find((p) => p.id === focus)
    if (!pin) return
    const layer = map.createLayer()
    const photo = pin.imageThumbnailUrl ?? pin.imageUrl ?? null
    layer.addMarker({
      position: { lat: pin.latitude, lng: pin.longitude },
      html: focusPinHtml(photo),
      className: 'st-pin st-mymap-focus',
      size: [FOCUS_PIN.w, FOCUS_PIN.h],
      anchor: [FOCUS_PIN.w / 2, FOCUS_PIN.h],
      zIndex: 1000,
      interactive: false,
      title: pin.name,
    })
    const size = map.getSize()
    const z = map.getZoom()
    const c = project(map.getCenter(), z)
    const pt = project({ lat: pin.latitude, lng: pin.longitude }, z)
    const x = pt.x - c.x + size.x / 2
    const y = pt.y - c.y + size.y / 2
    const top = CAMERA_TOP_INSET + CHIPS_H + FOCUS_PIN.h
    if (x < 24 || x > size.x - 24 || y < top || y > size.y - 12) {
      latest.current.beforeCamera()
      map.setView({ lat: pin.latitude, lng: pin.longitude }, null, { animate: true, seconds: 0.35, shiftY: -(top - CAMERA_TOP_INSET) / 2 })
    }
    return () => layer.remove()
  }, [map, focus])

  const anything = pins.length > 0 || poiPins.length > 0
  return (
    <div style={{ position: 'relative', flex: `0 0 ${MAP_SHARE}`, minHeight: 180, overflow: 'hidden', background: color.brandFill }}>
      {anything ? (
        <>
          <div style={{ position: 'absolute', left: 0, right: 0, bottom: 0, top: -CAMERA_TOP_INSET }}>
            <MapView
              pins={pins}
              numbered={false}
              koreaToken={0}
              locateToken={0}
              fitToken={0}
              focusToken={0}
              focus={null}
              panToken={0}
              pan={null}
              sheetHeight={0}
              onTapPin={onTapPin}
              onLocateFailure={() => {}}
              onReady={() => {}}
              onMap={onMap}
            />
          </div>
          {children}
        </>
      ) : (
        <>
          <div style={{ position: 'absolute', inset: 0, display: 'grid', placeItems: 'center', padding: 24 }}>
            <Txt size={14} weight="semibold" align="center" style={{ color: '#fff' }}>
              {say('지도에 표시할 위치가 없어요', 'No locations to show on the map')}
            </Txt>
          </div>
          {children}
        </>
      )}
    </div>
  )
}

/** 작품별 묶음 목록. 묶음 순서·제목은 mapGroups 를 따른다(출처 없는 것은 맨 뒤 「그 밖」). 맨 아래 「주변에서 담은 곳」 — 담은 편의시설.
 * 줄 사이에 다음 장소까지의 거리 칩(§9-5). 스크롤하면 목록 위쪽에 걸친 촬영지 줄을 `onFocus` 로 알린다(지도 핀 강조). */
function GroupedList({ items, pois, onOpen, onOpenPoi, onFocus }: {
  items: readonly CartItem[]
  pois: readonly CartPoi[]
  onOpen: (item: CartItem) => void
  onOpenPoi: (poi: CartPoi) => void
  onFocus: (placeId: number | null) => void
}) {
  const lang = useLang()
  const groups = mapGroups(items)
  const busy = useStore(cartPoiBusyStore, (s) => s)
  const scroller = useRef<HTMLDivElement>(null)
  const frame = useRef<number | null>(null)
  const last = useRef<number | null>(null)

  // 목록 위쪽 띠(위에서 40% 안)에 처음 걸리는 촬영지 줄 = 보고 있는 줄.
  const onScroll = () => {
    if (frame.current !== null) return
    frame.current = requestAnimationFrame(() => {
      frame.current = null
      const box = scroller.current
      if (!box) return
      const top = box.getBoundingClientRect().top
      const line = top + box.clientHeight * 0.4
      let found: number | null = null
      for (const row of Array.from(box.querySelectorAll<HTMLElement>('[data-place-row]'))) {
        const r = row.getBoundingClientRect()
        if (r.bottom > top + 4 && r.top < line) {
          found = Number(row.dataset.placeRow)
          break
        }
      }
      if (found !== last.current) {
        last.current = found
        onFocus(found)
      }
    })
  }
  // 처음 그린 뒤 한 번 — 목록 맨 위 줄이 곧 보고 있는 줄이다(지도와 목록이 이어져 있다는 것을 처음부터 보인다).
  const rowCount = items.length
  useEffect(() => {
    onScroll()
    // eslint-disable-next-line react-hooks/exhaustive-deps -- 줄 수가 바뀔 때만 다시 잰다.
  }, [rowCount])
  useEffect(() => () => {
    if (frame.current !== null) cancelAnimationFrame(frame.current)
    frame.current = null
  }, [])

  return (
    <div ref={scroller} className="scroll-y" onScroll={onScroll} style={{ flex: 1, minHeight: 0, ...contained }}>
      <VStack spacing={18} align="stretch" style={{ padding: '16px 20px 20px' }}>
        {groups.map((g) => {
          const rows = items.filter((i) => (i.sourceContentId ?? null) === g.contentId)
          return (
            <VStack key={g.contentId ?? 'other'} spacing={8} align="stretch" aria-label={g.title ?? say('그 밖', 'Other')} role="group">
              <Txt as="h2" size={15} weight="bold" lines={1} style={{ margin: 0 }}>
                {g.title ?? say('그 밖', 'Other')}
                <span style={{ color: color.primaryText }}>{` · ${say(`${g.count}곳`, spots(g.count))}`}</span>
              </Txt>
              <VStack spacing={0} align="stretch" style={{ borderRadius: radius.m, overflow: 'hidden', background: 'var(--system-background)' }}>
                {rows.map((item, index) => {
                  const prev = index > 0 ? rows[index - 1]! : null
                  const gap = prev && hasCoords(prev) && hasCoords(item) && prev.delisted !== true && item.delisted !== true
                    ? distanceLabel(metersBetween(prev, item), lang)
                    : null
                  return (
                    <div key={item.placeId}>
                      {gap && <DistanceChip label={gap} />}
                      <PlaceRow item={item} first={index === 0 && !gap} onOpen={() => onOpen(item)} onRemove={() => void cartActions.remove(item.placeId)} />
                    </div>
                  )
                })}
              </VStack>
            </VStack>
          )
        })}
        {pois.length > 0 && (
          <VStack spacing={8} align="stretch" aria-label={say('주변에서 담은 곳', 'Saved nearby')} role="group">
            <Txt as="h2" size={15} weight="bold" lines={1} style={{ margin: 0 }}>
              {say('주변에서 담은 곳', 'Saved nearby')}
              <span style={{ color: color.primaryText }}>{` · ${say(`${pois.length}곳`, spots(pois.length))}`}</span>
            </Txt>
            <VStack spacing={0} align="stretch" style={{ borderRadius: radius.m, overflow: 'hidden', background: 'var(--system-background)' }}>
              {pois.map((p, index) => (
                <SavedPoiRow key={p.id} poi={p} divider={index > 0} busy={busy.has(p.id)} onOpen={() => onOpenPoi(p)} onRemove={() => void cartActions.removePoi(p.id)} />
              ))}
            </VStack>
          </VStack>
        )}
      </VStack>
    </div>
  )
}

/** 줄 사이 거리 칩 — 「0.5 km ›」(직선 거리, VisitKorea 일정의 칩). 누르는 것은 아니다. */
function DistanceChip({ label }: { label: string }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '0 12px', height: 30, borderTop: '0.5px solid var(--separator)' }}>
      <span aria-hidden style={{ width: 56, display: 'flex', justifyContent: 'center' }}>
        <span style={{ width: 0, height: 30, borderLeft: '2px dotted var(--system-gray3)' }} />
      </span>
      <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4, height: 22, padding: '0 9px', borderRadius: 999, border: '1px solid var(--system-gray4)', background: 'var(--system-background)' }}>
        <Icon name="point.topleft.down.curvedto.point.bottomright.up" size={11} color="secondary" />
        <Txt size={12} weight="semibold" color="secondary" mono>{label}</Txt>
        <Txt size={12} weight="bold" color="secondary">›</Txt>
      </span>
    </div>
  )
}

// ---------------------------------------------------------------- 장면 사진(줄 썸네일 모서리, §9-7)

/** 촬영지 → 장면 사진. 받았는데 없으면 null, 아직이면 키가 없다. 화면에 보인 줄만 받는다. */
const rowSceneStore = createStore<Record<number, string | null>>({})
const rowSceneAsked = new Set<number>()

function loadRowScene(placeId: number, contentId: number | null): void {
  if (rowSceneAsked.has(placeId)) return
  rowSceneAsked.add(placeId)
  void ok(api.GET('/places/{placeId}', { params: { path: { placeId } } }))
    .then((detail) => pickSceneThumb(detail, contentId))
    .catch(() => null)
    .then((url) => rowSceneStore.set((s) => ({ ...s, [placeId]: url })))
}

/** 줄이 화면에 들어오면 장면 사진을 받는다. 관찰 수단이 없는 환경(jsdom)에서는 받지 않는다. */
function useRowScene(el: HTMLElement | null, placeId: number, contentId: number | null): string | null {
  const url = useStore(rowSceneStore, (s) => s[placeId] ?? null)
  useEffect(() => {
    if (!el || rowSceneAsked.has(placeId) || typeof IntersectionObserver === 'undefined') return
    const observer = new IntersectionObserver((entries) => {
      if (entries.some((e) => e.isIntersecting)) {
        loadRowScene(placeId, contentId)
        observer.disconnect()
      }
    }, { rootMargin: '120px 0px' })
    observer.observe(el)
    return () => observer.disconnect()
  }, [el, placeId, contentId])
  return url
}

/** 담은 촬영지 한 줄. 오른쪽 빼기 단추는 목록에서 빠진 촬영지에도 있다 — 옛 장바구니 시트의 빼기를 옮겨 왔다(web-keep-flow.md §11-1). */
function PlaceRow({ item, first, onOpen, onRemove }: { item: CartItem; first: boolean; onOpen: () => void; onRemove: () => void }) {
  const gone = item.delisted === true
  const photo = smallImage(item.imageThumbnailUrl, item.imageUrl)
  const [el, setEl] = useState<HTMLDivElement | null>(null)
  const scene = useRowScene(gone ? null : el, item.placeId, item.sourceContentId ?? null)
  const body = (
    <HStack spacing={12} style={{ padding: '10px 4px 10px 12px', opacity: gone ? 0.5 : 1 }}>
      <div style={{ position: 'relative', width: 56, height: 56, flex: 'none' }}>
        <div style={{ width: 56, height: 56, borderRadius: radius.s, overflow: 'hidden', background: color.brandFill }}>
          {photo && <img src={photo} alt="" loading="lazy" referrerPolicy="no-referrer" style={{ width: '100%', height: '100%', objectFit: 'cover' }} />}
        </div>
        {/* 장면이 있던 곳 — 실제 사진 모서리에 작은 장면 사진(왜 담았는지가 남게). */}
        {scene && (
          <img
            src={scene}
            alt=""
            loading="lazy"
            referrerPolicy="no-referrer"
            style={{ position: 'absolute', right: -6, bottom: -6, width: 28, height: 28, objectFit: 'cover', borderRadius: 7, border: '2px solid var(--system-background)', boxShadow: '0 1px 4px rgba(0,0,0,0.3)' }}
          />
        )}
      </div>
      <VStack spacing={2} align="start" style={{ flex: 1, minWidth: 0 }}>
        <Txt size={15} weight="semibold" lines={2}>{item.name}</Txt>
        {gone ? (
          <Txt size={12} color="secondary" lines={1}>{unavailableMessage('place')}</Txt>
        ) : (
          item.address && <Txt size={12} color="secondary" lines={1}>{item.address}</Txt>
        )}
      </VStack>
      {!gone && <Icon name="chevron.right" size={13} color="secondary" />}
    </HStack>
  )
  const remove = (
    <Pressable
      plain
      onClick={onRemove}
      aria-label={say('마이맵에서 빼기', 'Remove from My map')}
      style={{ width: 44, height: 44, display: 'grid', placeItems: 'center', flex: 'none', marginRight: 4 }}
    >
      <Icon name="xmark.circle.fill" size={20} color="secondary" />
    </Pressable>
  )
  const line = { display: 'flex', alignItems: 'center', borderTop: first ? undefined : '0.5px solid var(--separator)' } as const
  // 목록에서 빠진 촬영지는 상세가 없으므로 누를 수 없게 흐리게만 둔다 — 빼기만 된다.
  if (gone) {
    return (
      <div style={line}>
        <div style={{ flex: 1, minWidth: 0 }}>{body}</div>
        {remove}
      </div>
    )
  }
  return (
    <div ref={setEl} data-place-row={item.placeId} style={line}>
      <Pressable plain onClick={onOpen} style={{ flex: 1, minWidth: 0, textAlign: 'left' }}>
        {body}
      </Pressable>
      {remove}
    </div>
  )
}
