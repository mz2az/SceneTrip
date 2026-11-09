/*
 * 꾹 눌러 끌어서 순서 바꾸기 — 도시 추천 코스의 촬영지 카드(2026-11-09 사용자 요청: 「계획 세울 때처럼 꾹 눌러 옮기기」).
 *
 * 편집 화면의 `useReorder` 는 손잡이를 누르는 즉시 끈다. 카드는 손가락으로 목록을 넘기기도 하므로 0.3초 꾹 눌러야 들린다 — 그 전에 손가락이
 * 움직이면 넘기기로 보고 놓아 준다. 들린 뒤에는 화면이 같이 넘어가지 않게 막고, 이웃 카드의 절반을 넘으면 자리를 바꾼다.
 */
import { type MouseEvent as ReactMouseEvent, type PointerEvent as ReactPointerEvent, type RefObject, useCallback, useRef, useState } from 'react'

const HOLD_MS = 300
/** 이만큼 움직이면 꾹 누르기가 아니라 넘기기다. */
const SLOP_PX = 8

export function useHoldReorder(container: RefObject<HTMLElement | null>, ids: readonly string[], onMove: (from: number, to: number) => void) {
  const [state, setState] = useState<{ draggingId: string | null; offsetY: number }>({ draggingId: null, offsetY: 0 })
  const latest = useRef({ ids, onMove })
  latest.current = { ids, onMove }
  /** 끌고 난 직후의 click 은 카드 안 단추에 닿지 않게 버린다. */
  const justDragged = useRef(false)

  const heightOf = (id: string): number =>
    container.current?.querySelector<HTMLElement>(`[data-hold-id="${CSS.escape(id)}"]`)?.getBoundingClientRect().height ?? 0

  const bind = useCallback(
    (id: string) => ({
      'data-hold-id': id,
      onPointerDown: (e: ReactPointerEvent<HTMLElement>) => {
        if (e.pointerType === 'mouse' && e.button !== 0) return
        // 카드 안 단추(바꾸기·빼기)를 누른 것은 꾹 누르기가 아니다.
        if ((e.target as HTMLElement).closest('button')) return
        const target = e.currentTarget
        const pointerId = e.pointerId
        const start = { x: e.clientX, y: e.clientY }
        let originY = e.clientY
        let active = false

        const blockScroll = (ev: TouchEvent) => ev.preventDefault()
        const timer = window.setTimeout(() => {
          active = true
          try {
            target.setPointerCapture(pointerId)
          } catch {
            // 이미 놓은 손가락 — 아래 end 가 정리한다.
          }
          document.addEventListener('touchmove', blockScroll, { passive: false })
          navigator.vibrate?.(10)
          setState({ draggingId: id, offsetY: 0 })
        }, HOLD_MS)

        const move = (ev: PointerEvent) => {
          if (ev.pointerId !== pointerId) return
          if (!active) {
            if (Math.hypot(ev.clientX - start.x, ev.clientY - start.y) > SLOP_PX) end()
            return
          }
          let dy = ev.clientY - originY
          const { ids: list, onMove: shift } = latest.current
          const index = list.indexOf(id)
          if (index < 0) return
          if (dy > 0 && index < list.length - 1) {
            const h = heightOf(list[index + 1])
            if (h > 0 && dy > h / 2) {
              shift(index, index + 1)
              originY += h
              dy -= h
            }
          } else if (dy < 0 && index > 0) {
            const h = heightOf(list[index - 1])
            if (h > 0 && -dy > h / 2) {
              shift(index, index - 1)
              originY -= h
              dy += h
            }
          }
          setState({ draggingId: id, offsetY: dy })
        }
        const end = () => {
          window.clearTimeout(timer)
          if (active) {
            justDragged.current = true
            window.setTimeout(() => (justDragged.current = false), 0)
          }
          active = false
          document.removeEventListener('touchmove', blockScroll)
          window.removeEventListener('pointermove', move)
          window.removeEventListener('pointerup', end)
          window.removeEventListener('pointercancel', end)
          setState({ draggingId: null, offsetY: 0 })
        }
        window.addEventListener('pointermove', move)
        window.addEventListener('pointerup', end)
        window.addEventListener('pointercancel', end)
      },
      onClickCapture: (e: ReactMouseEvent) => {
        if (justDragged.current) {
          e.stopPropagation()
          e.preventDefault()
        }
      },
      // 꾹 누를 때 뜨는 길게 누르기 메뉴(사진 저장 등)를 막는다.
      onContextMenu: (e: ReactMouseEvent) => e.preventDefault(),
    }),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [],
  )

  return { ...state, bind }
}
