/*
 * SwiftUI `.topLeading → .bottomTrailing` 그라데이션의 CSS 각도.
 * SwiftUI 는 축이 모서리-모서리 대각선이고 등색선이 그 축에 수직이다. CSS `to bottom right` 는 등색선이 반대 대각선과 평행이라 다르다.
 * 그래서 요소 크기로 각도 `180° − atan(폭/높이)` 를 계산해 쓴다.
 */
import { useCallback, useRef, useState } from 'react'

export function diagonalAngle(w: number, h: number): number {
  if (w <= 0 || h <= 0) return 135
  return 180 - (Math.atan(w / h) * 180) / Math.PI
}

/** [콜백 ref, 각도(deg)] — 요소가 보이면 크기를 재고, 크기가 바뀌면 다시 잰다. */
export function useDiagonalAngle(initial = 135): [(el: HTMLElement | null) => void, number] {
  const [angle, setAngle] = useState(initial)
  const ro = useRef<ResizeObserver | null>(null)
  const ref = useCallback((el: HTMLElement | null) => {
    ro.current?.disconnect()
    ro.current = null
    if (!el) return
    const measure = () => setAngle(diagonalAngle(el.offsetWidth, el.offsetHeight))
    measure()
    if (typeof ResizeObserver !== 'undefined') {
      ro.current = new ResizeObserver(measure)
      ro.current.observe(el)
    }
  }, [])
  return [ref, angle]
}
