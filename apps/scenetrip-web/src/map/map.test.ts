import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { MapCancelled, createMap, resolveProvider } from './index'
import { createNaverMap, splitColor, strokeStyleFor } from './NaverMapProvider'
import { shiftedCenter } from './mercator'
import type { NaverMaps } from './naver'
import { loadNaverMaps, naverScriptUrl, resetNaverScriptForTests } from './naverScript'

/** `window.naver.maps` 가짜 — 부른 것을 기록한다. */
function fakeMaps() {
  const calls: Array<[string, ...unknown[]]> = []
  const listeners = new Map<unknown, { name: string; handler: (e: { coord?: unknown }) => void }>()
  const overlay = (kind: string) =>
    class {
      opts: unknown
      constructor(opts: unknown) {
        this.opts = opts
        calls.push([`new ${kind}`, opts])
      }
      setMap(m: unknown) {
        calls.push([`${kind}.setMap`, m])
      }
      setPosition(p: unknown) {
        calls.push([`${kind}.setPosition`, p])
      }
    }
  class LatLng {
    constructor(public la: number, public ln: number) {}
    lat() {
      return this.la
    }
    lng() {
      return this.ln
    }
  }
  class FakeMap {
    constructor(public el: HTMLElement, public opts: unknown) {
      calls.push(['new Map', opts])
    }
    getZoom() {
      return 14
    }
    getCenter() {
      return new LatLng(37.5, 127)
    }
    getSize() {
      return { width: 400, height: 800 }
    }
    getProjection() {
      return { fromOffsetToCoord: (p: { x: number; y: number }) => new LatLng(37 - p.y * 0.001, 127 + p.x * 0.001) }
    }
    setCenter(c: unknown) {
      calls.push(['setCenter', c])
    }
    setSize() {}
    setOptions(k: string, v: unknown) {
      calls.push(['setOptions', k, v])
    }
    panTo(c: unknown, o: unknown) {
      calls.push(['panTo', c, o])
    }
    morph(c: unknown, z: number, o: unknown) {
      calls.push(['morph', c, z, o])
    }
    updateBy(c: unknown, z: number) {
      calls.push(['updateBy', c, z])
    }
    fitBounds(b: unknown, o: unknown) {
      calls.push(['fitBounds', b, o])
    }
    destroy() {
      calls.push(['destroy'])
    }
  }
  const maps = {
    Map: FakeMap,
    LatLng,
    LatLngBounds: class {
      constructor(public sw: unknown, public ne: unknown) {}
    },
    Point: class {
      constructor(public x: number, public y: number) {}
    },
    Size: class {
      constructor(public width: number, public height: number) {}
    },
    Marker: overlay('Marker'),
    Polyline: overlay('Polyline'),
    Position: { BOTTOM_LEFT: 10, BOTTOM_RIGHT: 12 },
    Event: {
      addListener(target: unknown, name: string, handler: (e: { coord?: unknown }) => void) {
        const token = { target, name }
        listeners.set(token, { name, handler })
        return token
      },
      removeListener(token: unknown) {
        listeners.delete(token)
      },
    },
  } as unknown as NaverMaps
  return { maps, calls, listeners }
}

const named = (calls: Array<[string, ...unknown[]]>, name: string) => calls.filter((c) => c[0] === name)

afterEach(() => {
  vi.unstubAllEnvs()
  delete window.naver
  delete window.navermap_authFailure
  document.head.querySelectorAll('script').forEach((s) => s.remove())
  resetNaverScriptForTests()
})

describe('공급자 선택', () => {
  it('클라이언트 ID 가 비어 있으면 스크립트를 부르지 않고 Leaflet', async () => {
    const loadNaver = vi.fn()
    expect(await resolveProvider({ clientId: '', loadNaver })).toBe('leaflet')
    expect(await resolveProvider({ clientId: '   ', loadNaver })).toBe('leaflet')
    expect(loadNaver).not.toHaveBeenCalled()
  })

  it('ID 가 있고 스크립트가 로드되면 네이버', async () => {
    const loadNaver = vi.fn().mockResolvedValue({})
    expect(await resolveProvider({ clientId: 'abc', loadNaver })).toBe('naver')
    expect(loadNaver).toHaveBeenCalledWith('abc')
  })

  it('스크립트 로드가 실패하면 Leaflet 으로 폴백', async () => {
    const loadNaver = vi.fn().mockRejectedValue(new Error('load'))
    expect(await resolveProvider({ clientId: 'abc', loadNaver })).toBe('leaflet')
  })

  it('createMap — ID 환경 변수가 없으면 Leaflet 지도가 뜬다', async () => {
    vi.stubEnv('VITE_NAVER_MAP_CLIENT_ID', '')
    const el = document.createElement('div')
    document.body.appendChild(el)
    const map = await createMap(el)
    expect(map.provider).toBe('leaflet')
    expect(document.head.querySelector('script[src*="maps.js"]')).toBeNull()
    map.destroy()
    el.remove()
  })

  it('createMap — 만드는 도중 취소되면 지도를 만들지 않는다(StrictMode 두 번 실행)', async () => {
    vi.stubEnv('VITE_NAVER_MAP_CLIENT_ID', '')
    const el = document.createElement('div')
    await expect(createMap(el, { isCancelled: () => true })).rejects.toBeInstanceOf(MapCancelled)
    expect(el.querySelector('.leaflet-pane')).toBeNull()
  })

  it('createMap — ID 가 있고 SDK 가 이미 있으면 네이버 지도가 뜬다', async () => {
    vi.stubEnv('VITE_NAVER_MAP_CLIENT_ID', 'client-1')
    const { maps, calls } = fakeMaps()
    window.naver = { maps }
    const el = document.createElement('div')
    const map = await createMap(el)
    expect(map.provider).toBe('naver')
    expect(named(calls, 'new Map')).toHaveLength(1)
  })

  it('createMap — ID 가 있어도 스크립트가 실패하면 Leaflet', async () => {
    vi.stubEnv('VITE_NAVER_MAP_CLIENT_ID', 'client-1')
    const el = document.createElement('div')
    document.body.appendChild(el)
    const pending = createMap(el)
    await vi.waitFor(() => expect(document.head.querySelector('script[src*="maps.js"]')).not.toBeNull())
    ;(document.head.querySelector('script[src*="maps.js"]') as HTMLScriptElement).onerror?.(new Event('error'))
    const map = await pending
    expect(map.provider).toBe('leaflet')
    map.destroy()
    el.remove()
  })
})

describe('네이버 스크립트 주입', () => {
  it('주소는 oapi.map.naver.com 의 maps.js 에 ncpKeyId 로 ID 를 싣는다', () => {
    expect(naverScriptUrl('my id')).toBe('https://oapi.map.naver.com/openapi/v3/maps.js?ncpKeyId=my%20id')
  })

  it('여러 번 불러도 <script> 는 한 번만 들어가고 같은 약속을 받는다', async () => {
    const a = loadNaverMaps('id1')
    const b = loadNaverMaps('id1')
    expect(a).toBe(b)
    expect(document.head.querySelectorAll('script[src*="maps.js"]')).toHaveLength(1)
    const { maps } = fakeMaps()
    window.naver = { maps }
    ;(document.head.querySelector('script[src*="maps.js"]') as HTMLScriptElement).onload?.(new Event('load'))
    await expect(a).resolves.toBe(maps)
    // 로드된 뒤에는 새 호출도 스크립트를 더 넣지 않는다.
    await loadNaverMaps('id1')
    expect(document.head.querySelectorAll('script[src*="maps.js"]')).toHaveLength(1)
  })

  it('로드 오류면 거절하고 script 를 치운다', async () => {
    const p = loadNaverMaps('id1')
    ;(document.head.querySelector('script[src*="maps.js"]') as HTMLScriptElement).onerror?.(new Event('error'))
    await expect(p).rejects.toThrow('load')
    expect(document.head.querySelector('script[src*="maps.js"]')).toBeNull()
  })

  it('인증 실패(navermap_authFailure)도 거절이다', async () => {
    const p = loadNaverMaps('id1')
    window.navermap_authFailure?.()
    await expect(p).rejects.toThrow('auth')
  })

  it('시간이 초과되면 거절이다', async () => {
    vi.useFakeTimers()
    try {
      const p = loadNaverMaps('id1')
      const caught = p.catch((e: Error) => e.message)
      await vi.advanceTimersByTimeAsync(10_001)
      await expect(caught).resolves.toBe('timeout')
    } finally {
      vi.useRealTimers()
    }
  })
})

describe('네이버 어댑터 (window.naver.maps 가짜)', () => {
  let el: HTMLElement
  beforeEach(() => {
    el = document.createElement('div')
  })

  it('로고·저작권·축척 컨트롤을 끄지 않는다', () => {
    const { maps, calls } = fakeMaps()
    createNaverMap(maps, el)
    const opts = named(calls, 'new Map')[0][1] as Record<string, unknown>
    expect(opts.logoControl).toBe(true)
    expect(opts.mapDataControl).toBe(true)
    expect(opts.scaleControl).toBe(true)
  })

  it('마커를 만들고 클릭을 잇고 지운다', () => {
    const { maps, calls, listeners } = fakeMaps()
    const map = createNaverMap(maps, el)
    const layer = map.createLayer()
    const onClick = vi.fn()
    const marker = layer.addMarker({ position: { lat: 37.5, lng: 127 }, html: '<b>1</b>', className: 'st-pin', size: [38, 50], anchor: [19, 45], zIndex: 5, title: 'x', onClick })
    const created = named(calls, 'new Marker')[0][1] as { icon: { content: string }; zIndex: number; clickable: boolean; title: string }
    expect(created.icon.content).toContain('class="st-pin"')
    expect(created.icon.content).toContain('<b>1</b>')
    expect(created.zIndex).toBe(5)
    expect(created.clickable).toBe(true)
    const click = [...listeners.values()].find((l) => l.name === 'click')
    click?.handler({})
    expect(onClick).toHaveBeenCalledOnce()
    marker.setPosition({ lat: 38, lng: 128 })
    expect(named(calls, 'Marker.setPosition')).toHaveLength(1)
    marker.remove()
    expect(named(calls, 'Marker.setMap').at(-1)?.[1]).toBeNull()
  })

  it('layer.clear 는 그 층의 마커·선을 모두 뗀다', () => {
    const { maps, calls } = fakeMaps()
    const layer = createNaverMap(maps, el).createLayer()
    layer.addMarker({ position: { lat: 1, lng: 1 }, html: '', className: 'a', size: [1, 1], anchor: [0, 0] })
    layer.addPolyline({ path: [{ lat: 1, lng: 1 }, { lat: 2, lng: 2 }], color: 'rgba(138,148,166,0.85)', weight: 4, dash: '1 8' })
    layer.clear()
    expect(named(calls, 'Marker.setMap').filter((c) => c[1] === null)).toHaveLength(1)
    expect(named(calls, 'Polyline.setMap').filter((c) => c[1] === null)).toHaveLength(1)
    const line = named(calls, 'new Polyline')[0][1] as { strokeColor: string; strokeOpacity: number; strokeStyle: string }
    expect(line.strokeColor).toBe('#8a94a6')
    expect(line.strokeOpacity).toBeCloseTo(0.85)
    expect(line.strokeStyle).toBe('shortdot')
  })

  it('fitBounds 는 여백을 top/right/bottom/left 로 옮기고 maxZoom 을 넘긴다', () => {
    const { maps, calls } = fakeMaps()
    const map = createNaverMap(maps, el)
    map.fitBounds({ southWest: { lat: 37, lng: 126 }, northEast: { lat: 38, lng: 127 } }, { paddingTopLeft: [40, 50], paddingBottomRight: [30, 120], maxZoom: 17 })
    expect(named(calls, 'fitBounds')[0][2]).toEqual({ left: 40, top: 50, right: 30, bottom: 120, maxZoom: 17 })
  })

  it('아래 여백(시트 높이)을 지도 padding 으로 올려 로고·저작권이 시트 위에 놓이게 한다', () => {
    const { maps, calls } = fakeMaps()
    const map = createNaverMap(maps, el)
    map.setBottomInset(300)
    map.setBottomInset(300)
    expect(named(calls, 'setOptions')).toEqual([['setOptions', 'padding', { top: 0, right: 0, bottom: 300, left: 0 }]])
    // padding 이 이미 떼어 놓은 높이는 fitBounds 아래 여백에서 뺀다.
    map.fitBounds({ southWest: { lat: 37, lng: 126 }, northEast: { lat: 38, lng: 127 } }, { paddingBottomRight: [10, 340] })
    expect((named(calls, 'fitBounds')[0][2] as { bottom: number }).bottom).toBe(40)
  })

  it('setView — 줌이 없으면 panTo, 있으면 morph, 애니메이션을 끄면 updateBy', () => {
    const { maps, calls } = fakeMaps()
    const map = createNaverMap(maps, el)
    map.setView({ lat: 37.5, lng: 127 }, null, { seconds: 0.5 })
    expect(named(calls, 'panTo')).toHaveLength(1)
    expect((named(calls, 'panTo')[0][2] as { duration: number }).duration).toBe(500)
    map.setView({ lat: 37.5, lng: 127 }, 16)
    expect(named(calls, 'morph')[0][2]).toBe(16)
    map.setView({ lat: 37.5, lng: 127 }, 15, { animate: false })
    expect(named(calls, 'updateBy')[0][2]).toBe(15)
  })

  it('이벤트: 지도 click 은 좌표를 주고, 돌려준 함수를 부르면 듣기를 그만둔다', () => {
    const { maps, listeners } = fakeMaps()
    const map = createNaverMap(maps, el)
    const seen: Array<{ lat: number; lng: number } | null> = []
    const off = map.on('click', (at) => seen.push(at))
    const l = [...listeners.values()][0]
    l.handler({ coord: new maps.LatLng(37.1, 127.2) })
    expect(seen).toEqual([{ lat: 37.1, lng: 127.2 }])
    off()
    expect(listeners.size).toBe(0)
  })

  it('destroy 는 지도를 파괴한다', () => {
    const { maps, calls } = fakeMaps()
    createNaverMap(maps, el).destroy()
    expect(named(calls, 'destroy')).toHaveLength(1)
  })
})

describe('보조 규칙', () => {
  it('색: rgba 는 16진수와 알파로 나눈다', () => {
    expect(splitColor('rgba(138,148,166,0.85)')).toEqual({ hex: '#8a94a6', alpha: 0.85 })
    expect(splitColor('#7a69ed')).toEqual({ hex: '#7a69ed', alpha: 1 })
  })

  it('점선: 점은 dot 계열, 긴 마디는 dash 계열, 없으면 실선', () => {
    expect(strokeStyleFor(undefined)).toBe('solid')
    expect(strokeStyleFor('0.1 14')).toBe('shortdot')
    expect(strokeStyleFor('8 12')).toBe('dash')
  })

  it('shiftedCenter — 0 이면 그대로, 아래로 밀면 위도가 내려간다', () => {
    const c = { lat: 37.5, lng: 127 }
    expect(shiftedCenter(c, 15, 0)).toBe(c)
    const moved = shiftedCenter(c, 15, 200)
    expect(moved.lat).toBeLessThan(c.lat)
    expect(moved.lng).toBeCloseTo(c.lng, 6)
  })
})
