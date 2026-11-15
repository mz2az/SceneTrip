// MZ2AZ-582 — 가로 배너 포스터를 세로 카드에 (docs/project/web-app.md §15).
// 그림 비율이 상자 비율의 1.3배를 넘으면 자르지 않고(contain, 세로 38%) 뒤에 같은 그림을 흐리게 깐다. 그 밖은 cover.
import { act, cleanup, fireEvent, render } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { PosterImg } from './PosterImg'

// jsdom 에는 IntersectionObserver 가 없다고 보고(DeferredImg 가 바로 src 를 단다) 테스트마다 확실히 비운다.
const original = Object.getOwnPropertyDescriptor(globalThis, 'IntersectionObserver')
beforeEach(() => {
  Object.defineProperty(globalThis, 'IntersectionObserver', { value: undefined, configurable: true, writable: true })
})
afterEach(() => {
  cleanup()
  if (original) Object.defineProperty(globalThis, 'IntersectionObserver', original)
  else delete (globalThis as { IntersectionObserver?: unknown }).IntersectionObserver
})

const SRC = 'https://x/poster.jpg'

function root(container: HTMLElement) {
  return container.querySelector('[data-poster-fit]') as HTMLElement | null
}
function mainImg(container: HTMLElement) {
  return container.querySelector('img:not([aria-hidden="true"])') as HTMLImageElement | null
}
function backdrop(container: HTMLElement) {
  return container.querySelector('img[aria-hidden="true"]') as HTMLImageElement | null
}

/** 그림 원본 크기와 상자 크기를 박고 load 를 쏜다. */
function load(img: HTMLImageElement, natural: [number, number], box: [number, number]) {
  const def = (k: string, v: number) => Object.defineProperty(img, k, { value: v, configurable: true })
  def('naturalWidth', natural[0])
  def('naturalHeight', natural[1])
  def('clientWidth', box[0])
  def('clientHeight', box[1])
  act(() => {
    fireEvent.load(img)
  })
}

describe('PosterImg', () => {
  it('src 가 null 이면 그림을 그리지 않는다', () => {
    const { container } = render(<PosterImg src={null} />)
    expect(container.querySelectorAll('img')).toHaveLength(0)
  })

  it('src 가 undefined 이어도 그림을 그리지 않는다', () => {
    const { container } = render(<PosterImg src={undefined} />)
    expect(container.querySelectorAll('img')).toHaveLength(0)
  })

  it('받기 전에는 뒤 흐린 그림이 없고 앞 그림 하나만 있다', () => {
    const { container } = render(<PosterImg src={SRC} />)
    expect(container.querySelectorAll('img')).toHaveLength(1)
    expect(backdrop(container)).toBeNull()
    expect(mainImg(container)?.getAttribute('src')).toBe(SRC)
  })

  it('세로 포스터(300x420)를 108x144 상자에 → cover, 뒤 그림 없음, 기본 위치 center', () => {
    const { container } = render(<PosterImg src={SRC} />)
    load(mainImg(container)!, [300, 420], [108, 144])
    expect(root(container)?.getAttribute('data-poster-fit')).toBe('cover')
    const img = mainImg(container)!
    expect(img.style.objectFit).toBe('cover')
    expect(img.style.objectPosition).toBe('center')
    expect(backdrop(container)).toBeNull()
    expect(container.querySelectorAll('img')).toHaveLength(1)
  })

  it('cover 일 때 position 을 주면 그 자리로 자른다', () => {
    const { container } = render(<PosterImg src={SRC} position="center 15%" />)
    load(mainImg(container)!, [300, 420], [108, 144])
    expect(root(container)?.getAttribute('data-poster-fit')).toBe('cover')
    expect(mainImg(container)!.style.objectPosition).toBe('center 15%')
  })

  it('16:9 배너(400x225)를 108x144 상자에 → wide, contain·center 38%, 같은 그림을 흐리게 뒤에 깐다', () => {
    const { container } = render(<PosterImg src={SRC} position="center 15%" />)
    load(mainImg(container)!, [400, 225], [108, 144])
    expect(root(container)?.getAttribute('data-poster-fit')).toBe('wide')

    const img = mainImg(container)!
    expect(img.style.objectFit).toBe('contain')
    expect(img.style.objectPosition).toBe('center 38%')

    const back = backdrop(container)
    expect(back).not.toBeNull()
    expect(back!.getAttribute('src')).toBe(SRC)
    expect(back!.getAttribute('alt')).toBe('')
    expect(back!.style.objectFit).toBe('cover')
    expect(back!.style.filter).toContain('blur(')
    expect(container.querySelectorAll('img')).toHaveLength(2)
  })

  it('경계: 그림 비율이 상자 비율의 정확히 1.3배면 그대로 cover', () => {
    const { container } = render(<PosterImg src={SRC} />)
    load(mainImg(container)!, [130, 100], [100, 100])
    expect(root(container)?.getAttribute('data-poster-fit')).toBe('cover')
    expect(mainImg(container)!.style.objectFit).toBe('cover')
    expect(backdrop(container)).toBeNull()
  })

  it('경계 바로 위(1.31배)는 wide', () => {
    const { container } = render(<PosterImg src={SRC} />)
    load(mainImg(container)!, [131, 100], [100, 100])
    expect(root(container)?.getAttribute('data-poster-fit')).toBe('wide')
    expect(backdrop(container)).not.toBeNull()
  })

  it('정사각 상자(「더 담기」 작은 칸 150x150)에 16:9 그림 → wide', () => {
    const { container } = render(<PosterImg src={SRC} />)
    load(mainImg(container)!, [1600, 900], [150, 150])
    expect(root(container)?.getAttribute('data-poster-fit')).toBe('wide')
    expect(mainImg(container)!.style.objectFit).toBe('contain')
    expect(backdrop(container)?.getAttribute('src')).toBe(SRC)
  })
})
