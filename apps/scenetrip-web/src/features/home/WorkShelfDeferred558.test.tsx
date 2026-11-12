// MZ2AZ-558 — 「지금 뜨는 작품」 가로 줄은 화면 근처에 들어온 포스터만 받는다.
import { act, cleanup, render } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { chooseLanguage } from '@/i18n'
import type { components } from '@/api/generated/schema'
import { WorkShelf } from './HomeCards'

type ContentSummary = components['schemas']['ContentSummary']
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
/** 요소마다 교차 여부를 정해 알린다. */
function report(isIntersecting: (el: Element) => boolean) {
  act(() => {
    for (const o of FakeObserver.instances) {
      for (const el of o.observed) {
        const hit = isIntersecting(el)
        o.callback([{ target: el, isIntersecting: hit, intersectionRatio: hit ? 1 : 0 }], o)
      }
    }
  })
}

const original = Object.getOwnPropertyDescriptor(globalThis, 'IntersectionObserver')
beforeEach(() => {
  chooseLanguage('ko')
  FakeObserver.instances = []
  Object.defineProperty(globalThis, 'IntersectionObserver', { value: FakeObserver, configurable: true, writable: true })
})
afterEach(() => {
  cleanup()
  if (original) Object.defineProperty(globalThis, 'IntersectionObserver', original)
  else delete (globalThis as { IntersectionObserver?: unknown }).IntersectionObserver
})

const work = (id: number): ContentSummary =>
  ({ id, category: 'drama', title: `작품 ${id}`, placeCount: id, posterUrl: `https://x/poster-${id}.jpg` }) as ContentSummary

describe('WorkShelf 포스터 지연 로딩', () => {
  it('교차한 첫 포스터만 src 가 있고 나머지는 src 속성이 없다', () => {
    const { container } = render(<WorkShelf works={[work(1), work(2), work(3)]} failed={false} onOpen={vi.fn()} onAll={vi.fn()} />)
    const imgs = Array.from(container.querySelectorAll('img'))
    expect(imgs).toHaveLength(3)
    // 렌더 직후에는 아무것도 받지 않는다.
    for (const img of imgs) expect(img.getAttribute('src')).toBeNull()

    report((el) => el === imgs[0] || el.contains(imgs[0]))

    const after = Array.from(container.querySelectorAll('img'))
    expect(after[0].getAttribute('src')).toBe('https://x/poster-1.jpg')
    expect(after[1].getAttribute('src')).toBeNull()
    expect(after[2].getAttribute('src')).toBeNull()
  })
})
