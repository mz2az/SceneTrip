/*
 * 지도 만들기의 단일 입구. 화면 컴포넌트는 `createMap(el)` 만 부르고 어느 구현이 떴는지 신경 쓰지 않는다.
 *
 * 공급자 선택 규칙
 *   1. `VITE_NAVER_MAP_CLIENT_ID` 가 비어 있다(공백뿐 포함)         → Leaflet(OSM)
 *   2. ID 가 있다 → 네이버 스크립트를 한 번 주입해 본다
 *        로드 성공                                                → 네이버
 *        로드 실패·시간 초과·인증 실패(서비스 URL 미등록 등)       → Leaflet(폴백)
 * Leaflet 은 정적으로 묶는다 — 폴백이 첫 화면에서 한 틱도 늦지 않아야 지도가 예전과 같은 시점에 뜬다(동적 import 는 첫 화면의 카메라 맞춤 시점을 늦췄다).
 */
import { createLeafletMap } from './LeafletMapProvider'
import { createNaverMap } from './NaverMapProvider'
import { loadNaverMaps } from './naverScript'
import type { CreateMapOptions, MapHandle, MapProviderKind } from './types'

export type { CreateMapOptions, LatLng, MapBounds, MapHandle, MapLayer, MapProviderKind, MarkerHandle, MarkerSpec, PolylineSpec } from './types'

/** `createMap` 이 `isCancelled` 때문에 지도를 만들지 않고 물러났다. 오류가 아니다. */
export class MapCancelled extends Error {
  constructor() {
    super('map creation cancelled')
  }
}

/** `.catch(ignoreCancelled)` — 취소는 삼키고 진짜 오류는 다시 던진다. */
export function ignoreCancelled(e: unknown): void {
  if (!(e instanceof MapCancelled)) throw e
}

export function configuredClientId(): string {
  const raw: unknown = import.meta.env.VITE_NAVER_MAP_CLIENT_ID
  return typeof raw === 'string' ? raw.trim() : ''
}

export interface ProviderDeps {
  clientId: string
  loadNaver: (clientId: string) => Promise<unknown>
}

/** 어느 공급자로 뜰지. ID 없음 → leaflet, ID 있음 → 스크립트가 로드되면 naver, 아니면 leaflet. */
export async function resolveProvider({ clientId, loadNaver }: ProviderDeps): Promise<MapProviderKind> {
  if (!clientId.trim()) return 'leaflet'
  try {
    await loadNaver(clientId.trim())
    return 'naver'
  } catch {
    return 'leaflet'
  }
}

function createLeaflet(el: HTMLElement, options: CreateMapOptions): MapHandle {
  if (options.isCancelled?.()) throw new MapCancelled()
  return createLeafletMap(el, options)
}

/**
 * 클라이언트 ID 가 없으면 공급자가 이미 정해져 있으므로(Leaflet) 기다릴 것이 없다 — 지도를 **즉시** 만들어 돌려준다. 예전처럼 컴포넌트가 마운트되는
 * 바로 그 커밋에서 지도가 서 있어야 첫 화면의 카메라 맞춤 시점이 달라지지 않는다. ID 가 있으면 스크립트를 기다려야 하므로 null — `createMap` 을 쓴다.
 */
export function createMapNow(el: HTMLElement, options: CreateMapOptions = {}): MapHandle | null {
  if (configuredClientId()) return null
  return createLeaflet(el, options)
}

export async function createMap(el: HTMLElement, options: CreateMapOptions = {}): Promise<MapHandle> {
  const clientId = configuredClientId()
  const kind = await resolveProvider({ clientId, loadNaver: loadNaverMaps })
  if (options.isCancelled?.()) throw new MapCancelled()
  if (kind === 'naver' && window.naver?.maps) {
    try {
      return createNaverMap(window.naver.maps, el)
    } catch {
      // 지도 객체를 못 만들면(인증 직후 오류 등) 폴백한다.
    }
  }
  return createLeaflet(el, options)
}
