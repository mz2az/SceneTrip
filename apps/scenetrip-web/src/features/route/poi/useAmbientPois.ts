/*
 * 지도가 멈출 때마다 그 범위의 **주변 편의시설**을 받아 깔아 주는 훅 — iOS RouteEditorAmbient.viewportChanged (MZ2AZ-314 · 403 · 447).
 *
 * 카메라가 멈췄다 — 0.35초 조용하면 그 범위의 주변을 받는다. **너무 넓은 화면(줌 13 미만, 수십 km)에서는 안 부른다** — 점이 먼지처럼 흩어질 뿐이고
 * 서버도 헛돈다. 상한은 30 개(화면 가운데부터 채운다). 기기 위치에서 비롯한 지도(`source: 'device'`)는 위치 동의가 없으면 부르지 않는다.
 */
import type { MapHandle } from '@/map'
import { useEffect, useRef, useState } from 'react'
import { type PoiViewport, POI_LIMIT, fetchPois as defaultFetch, type PoiFetchResult } from './poiApi'
import type { RoutePlace } from './poiPlace'

/** 줌 13 미만에서는 안 부른다 (RouteEditorAmbient.viewportChanged). */
export const POI_MIN_ZOOM = 13
/** 카메라가 멈춘 뒤 조용해야 하는 시간(ms). */
export const POI_QUIET_MS = 350

export interface AmbientPoiOptions {
  /** 지도. 없으면 아무것도 하지 않는다. */
  map: MapHandle | null
  /** 안내 중에는 점을 다 끈다(2026-09-04 사용자 요청) — 경로선이 주인공인데 음식점·명소 점이 그 위를 덮었다. */
  enabled?: boolean
  /** 지금 지도의 중심이 기기 위치에서 왔는가. 그러면 위치 동의가 있어야 부른다. */
  source?: 'device' | 'userSelected'
  /** 위치기반서비스 동의 — 기본은 동의 없이 부르지 않는다. */
  locationAllowed?: () => boolean
  fetchPois?: (v: PoiViewport, limit?: number) => Promise<PoiFetchResult>
  /** 시트가 지도 아래를 덮은 높이(px). 보이는 영역은 이만큼 줄어든다. */
  bottomInset?: number
}

export interface AmbientPoiState {
  places: RoutePlace[]
  /** 조건에 맞는 전체 수가 받은 수보다 크면 잘린 것이다 — 「더 확대하세요」의 근거. */
  truncated: boolean
  /** 지금 줌이 너무 넓어 안 받는 중인가. */
  tooWide: boolean
}

/**
 * 지도의 「보이는 영역」 — 시트가 아래를 덮은 만큼(`bottomInset`, px)은 뺀다. iOS 는 `mapView.contentInset.bottom = panelHeight`(RouteMapView.swift:259)라
 * 카메라 중심과 `coveringBounds` 가 덮이지 않은 영역의 것이다. 웹 지도는 시트 뒤까지 꽉 차 있어, 그냥 `getCenter()` 를 쓰면
 * 가운데에서 가까운 30 개가 시트 밑에 깔려 점이 안 보인다.
 */
export function viewportOf(map: MapHandle, bottomInset = 0): PoiViewport {
  const size = map.getSize()
  const visibleHeight = Math.max(1, size.y - Math.max(0, bottomInset))
  const nw = map.containerPointToLatLng([0, 0])
  const se = map.containerPointToLatLng([size.x, visibleHeight])
  const c = map.containerPointToLatLng([size.x / 2, visibleHeight / 2])
  return { south: se.lat, west: nw.lng, north: nw.lat, east: se.lng, centerLat: c.lat, centerLng: c.lng }
}

export function useAmbientPois({ map, enabled = true, source = 'userSelected', locationAllowed = () => false, fetchPois = defaultFetch, bottomInset = 0 }: AmbientPoiOptions): AmbientPoiState {
  const [state, setState] = useState<AmbientPoiState>({ places: [], truncated: false, tooWide: false })
  const latest = useRef({ source, locationAllowed, fetchPois, bottomInset })
  latest.current = { source, locationAllowed, fetchPois, bottomInset }

  useEffect(() => {
    if (!map || !enabled) {
      setState((s) => (s.places.length === 0 ? s : { places: [], truncated: false, tooWide: false }))
      return
    }
    let timer: ReturnType<typeof setTimeout> | null = null
    let ticket = 0
    const run = () => {
      if (timer) clearTimeout(timer)
      const mine = (ticket += 1)
      if (map.getZoom() < POI_MIN_ZOOM) {
        setState({ places: [], truncated: false, tooWide: true })
        return
      }
      // 여행 시작의 동의 시트와 겹치지 않는다. 허용 뒤 다시 조회한다.
      if (latest.current.source === 'device' && !latest.current.locationAllowed()) {
        setState({ places: [], truncated: false, tooWide: false })
        return
      }
      timer = setTimeout(() => {
        void latest.current.fetchPois(viewportOf(map, latest.current.bottomInset), POI_LIMIT).then((found) => {
          if (mine === ticket) setState({ places: found.places, truncated: found.total > found.places.length, tooWide: false })
        })
      }, POI_QUIET_MS)
    }
    const off = map.on('moveend', run)
    run()
    return () => {
      ticket += 1
      if (timer) clearTimeout(timer)
      off()
    }
  }, [map, enabled])

  return state
}
