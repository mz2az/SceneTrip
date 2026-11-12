// MZ2AZ-558 — 보이지 않는 포스터를 받지 않는다. 탭이 보이고(LayerVisibleContext) 화면 근처에 들어왔을 때(IntersectionObserver)만 src 를 단다.
import { act, cleanup, render } from '@testing-library/react'
import { useContext } from 'react'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { DeferredImg, LayerVisibleContext, shouldReveal } from './DeferredImg'

// --- 손으로 쓴 IntersectionObserver 대역 -------------------------------------------------
type Callback = (entries: Partial<IntersectionObserverEntry>[], observer: unknown) => void
class FakeObserver {
  static instances: FakeObserver[] = []
  observed = new Set<Element>()
  constructor(public callback: Callback, public options?: IntersectionObserverInit) {
    FakeObserver.instances.push(this)
  }
  observe(el: Element) { this.observed.add(el) }
  unobserve(el: Element) { this.observed.delete(el) }
  disconnect() { this.observed.clear() }
  takeRecords() { return [] }
}
function trigger(el: Element, isIntersecting: boolean) {
  act(() => {
    for (const o of FakeObserver.instances) {
      if (o.observed.has(el)) o.callback([{ target: el, isIntersecting, intersectionRatio: isIntersecting ? 1 : 0 }], o)
    }
  })
}

const original = Object.getOwnPropertyDescriptor(globalThis, 'IntersectionObserver')
function installObserver() {
  FakeObserver.instances = []
  Object.defineProperty(globalThis, 'IntersectionObserver', { value: FakeObserver, configurable: true, writable: true })
}
function removeObserver() {
  Object.defineProperty(globalThis, 'IntersectionObserver', { value: undefined, configurable: true, writable: true })
}
afterEach(() => {
  cleanup()
  if (original) Object.defineProperty(globalThis, 'IntersectionObserver', original)
  else delete (globalThis as { IntersectionObserver?: unknown }).IntersectionObserver
})

const imgOf = (c: HTMLElement) => c.querySelector('img') as HTMLImageElement

describe('shouldReveal', () => {
  it('한 번 보인 것은 계속 보인다', () => {
    expect(shouldReveal({ layerVisible: false, intersecting: false, observerSupported: true, alreadyRevealed: true })).toBe(true)
    expect(shouldReveal({ layerVisible: false, intersecting: false, observerSupported: false, alreadyRevealed: true })).toBe(true)
  })
  it('숨은 탭에서는 처음 보이게 하지 않는다', () => {
    expect(shouldReveal({ layerVisible: false, intersecting: true, observerSupported: true, alreadyRevealed: false })).toBe(false)
    expect(shouldReveal({ layerVisible: false, intersecting: false, observerSupported: false, alreadyRevealed: false })).toBe(false)
  })
  it('관찰자가 없으면 탭이 보일 때 바로 보인다', () => {
    expect(shouldReveal({ layerVisible: true, intersecting: false, observerSupported: false, alreadyRevealed: false })).toBe(true)
  })
  it('관찰자가 있으면 탭이 보이고 교차할 때만 보인다', () => {
    expect(shouldReveal({ layerVisible: true, intersecting: true, observerSupported: true, alreadyRevealed: false })).toBe(true)
    expect(shouldReveal({ layerVisible: true, intersecting: false, observerSupported: true, alreadyRevealed: false })).toBe(false)
  })
})

describe('DeferredImg', () => {
  beforeEach(installObserver)

  it('교차 전에는 img 를 그리되 src 속성이 없다(빈 문자열도 아님), loading=lazy 와 다른 속성은 그대로', () => {
    const { container } = render(<DeferredImg src="https://x/a.jpg" alt="포스터" loading="lazy" referrerPolicy="no-referrer" draggable={false} style={{ width: 10 }} className="p" />)
    const img = imgOf(container)
    expect(img).not.toBeNull()
    expect(img.getAttribute('src')).toBeNull()
    expect(img.getAttribute('loading')).toBe('lazy')
    expect(img.getAttribute('alt')).toBe('포스터')
    expect(img.getAttribute('referrerpolicy')).toBe('no-referrer')
    expect(img.className).toBe('p')
  })

  it('loading 을 주지 않아도 loading=lazy 를 단다', () => {
    const { container } = render(<DeferredImg src="https://x/a.jpg" alt="" />)
    expect(imgOf(container).getAttribute('loading')).toBe('lazy')
  })

  it('관찰자에 비어 있지 않은 rootMargin 을 넘기고 img 를 관찰한다', () => {
    const { container } = render(<DeferredImg src="https://x/a.jpg" alt="" />)
    const img = imgOf(container)
    const o = FakeObserver.instances.find((i) => i.observed.has(img))
    expect(o).toBeDefined()
    expect(typeof o!.options?.rootMargin).toBe('string')
    expect(o!.options!.rootMargin!.trim().length).toBeGreaterThan(0)
  })

  it('교차하면 src 를 달고, 교차에서 벗어나도 떼지 않는다', () => {
    const { container } = render(<DeferredImg src="https://x/a.jpg" alt="" />)
    const img = imgOf(container)
    trigger(img, true)
    expect(imgOf(container).getAttribute('src')).toBe('https://x/a.jpg')
    trigger(img, false)
    expect(imgOf(container).getAttribute('src')).toBe('https://x/a.jpg')
  })

  it('숨은 탭 안에서는 교차해도 src 를 달지 않고, 탭이 보이면 단다. 다시 숨겨도 떼지 않는다', () => {
    const tree = (visible: boolean) => (
      <LayerVisibleContext.Provider value={visible}>
        <DeferredImg src="https://x/a.jpg" alt="" />
      </LayerVisibleContext.Provider>
    )
    const { container, rerender } = render(tree(false))
    trigger(imgOf(container), true)
    expect(imgOf(container).getAttribute('src')).toBeNull()

    rerender(tree(true))
    // 이미 교차 중이라는 사실을 기억하든, 다시 관찰해 알든 — 필요하면 한 번 더 알려 준다.
    if (imgOf(container).getAttribute('src') === null) trigger(imgOf(container), true)
    expect(imgOf(container).getAttribute('src')).toBe('https://x/a.jpg')

    rerender(tree(false))
    expect(imgOf(container).getAttribute('src')).toBe('https://x/a.jpg')
  })

  it('보인 뒤 src 가 바뀌면 새 src 를 보인다', () => {
    const { container, rerender } = render(<DeferredImg src="https://x/a.jpg" alt="" />)
    trigger(imgOf(container), true)
    rerender(<DeferredImg src="https://x/b.jpg" alt="" />)
    expect(imgOf(container).getAttribute('src')).toBe('https://x/b.jpg')
  })

  it.each([null, undefined, ''])('src 가 %s 이면 src 속성 없이 그리고 던지지 않는다', (src) => {
    const { container } = render(<DeferredImg src={src as string | undefined} alt="" />)
    const img = imgOf(container)
    if (img) {
      trigger(img, true)
      expect(imgOf(container).getAttribute('src')).toBeNull()
    }
  })
})

describe('DeferredImg — IntersectionObserver 가 없는 환경', () => {
  beforeEach(removeObserver)

  it('탭이 보이면 바로 src 를 단다', () => {
    const { container } = render(<DeferredImg src="https://x/a.jpg" alt="" />)
    expect(imgOf(container).getAttribute('src')).toBe('https://x/a.jpg')
  })

  it('탭이 숨어 있으면 달지 않다가, 보이면 단다', () => {
    const tree = (visible: boolean) => (
      <LayerVisibleContext.Provider value={visible}>
        <DeferredImg src="https://x/a.jpg" alt="" />
      </LayerVisibleContext.Provider>
    )
    const { container, rerender } = render(tree(false))
    expect(imgOf(container).getAttribute('src')).toBeNull()
    rerender(tree(true))
    expect(imgOf(container).getAttribute('src')).toBe('https://x/a.jpg')
  })
})

describe('LayerVisibleContext', () => {
  it('기본값은 true', () => {
    let seen: boolean | undefined
    function Probe() {
      seen = useContext(LayerVisibleContext)
      return null
    }
    render(<Probe />)
    expect(seen).toBe(true)
  })
})
