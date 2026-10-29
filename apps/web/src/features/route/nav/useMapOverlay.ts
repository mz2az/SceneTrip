/*
 * 코스 지도(`L.Map`)에 가이드·편의시설·여행 안내 층을 붙이는 훅.
 *
 *   const [map, setMap] = useState<L.Map | null>(null)
 *   <RouteMap … onReady={setMap} />                       // 경로여정 담당의 편집 지도 — 같은 층이 그 위에 얹힌다
 *   useMapOverlay(map, { ambient, guidePlaces, picked, legs, here, target, preview }, { onSelectPlace })
 */
import type L from 'leaflet'
import { useEffect, useRef } from 'react'
import { type MapOverlay, type OverlayHandlers, type OverlayState, createMapOverlay } from './mapOverlay'

export function useMapOverlay(map: L.Map | null, state: OverlayState, handlers: OverlayHandlers = {}): void {
  const overlay = useRef<MapOverlay | null>(null)
  const latestHandlers = useRef(handlers)
  latestHandlers.current = handlers
  const latestState = useRef(state)
  latestState.current = state

  useEffect(() => {
    if (!map) return
    const made = createMapOverlay(map, { onSelectPlace: (p) => latestHandlers.current.onSelectPlace?.(p) })
    overlay.current = made
    made.update(latestState.current)
    return () => {
      made.destroy()
      overlay.current = null
    }
  }, [map])

  useEffect(() => {
    overlay.current?.update(state)
  }, [state])
}
