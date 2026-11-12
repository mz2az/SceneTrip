// MZ2AZ-558 — 숨긴 탭(Layer visible=false) 안의 포스터는 받지 않는다. Layer 가 LayerVisibleContext 로 visible 을 내려 준다.
import { act, cleanup, render } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { DeferredImg } from '@/design/DeferredImg'
import { Layer } from './RootTabs'

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
/** 관찰 중인 모든 요소를 교차 중으로 알린다. */
function intersectAll() {
  act(() => {
    for (const o of FakeObserver.instances) {
      for (const el of o.observed) o.callback([{ target: el, isIntersecting: true, intersectionRatio: 1 }], o)
    }
  })
}

const original = Object.getOwnPropertyDescriptor(globalThis, 'IntersectionObserver')
beforeEach(() => {
  FakeObserver.instances = []
  Object.defineProperty(globalThis, 'IntersectionObserver', { value: FakeObserver, configurable: true, writable: true })
})
afterEach(() => {
  cleanup()
  if (original) Object.defineProperty(globalThis, 'IntersectionObserver', original)
  else delete (globalThis as { IntersectionObserver?: unknown }).IntersectionObserver
})

describe('RootTabs Layer → LayerVisibleContext', () => {
  it('숨긴 Layer 안에서는 교차해도 src 가 없고, Layer 가 보이면 src 를 단다', () => {
    const tree = (visible: boolean) => (
      <Layer visible={visible}>
        <DeferredImg src="https://x/poster.jpg" alt="" />
      </Layer>
    )
    const { container, rerender } = render(tree(false))
    intersectAll()
    expect(container.querySelector('img')!.getAttribute('src')).toBeNull()

    rerender(tree(true))
    intersectAll()
    expect(container.querySelector('img')!.getAttribute('src')).toBe('https://x/poster.jpg')
  })
})
