/*
 * 목록 줄 끌어서 순서 바꾸기 — iOS List `.onMove`. 편집 모드를 켜지 않고도(켜면 행 안의 버튼이 눌리지 않는다) 손잡이를 끌어 옮긴다.
 *
 * 끄는 동안 그 줄은 포인터를 따라가고, 이웃 줄의 절반을 넘으면 바로 자리를 바꾼다. 자리를 바꾼 만큼 시작점을 옮겨 줄이 손가락 아래에서 튀지 않게 한다.
 */
import { type PointerEvent as ReactPointerEvent, type RefObject, useCallback, useRef, useState } from 'react'

export interface ReorderState {
  draggingUid: string | null
  offsetY: number
}

export function useReorder(container: RefObject<HTMLElement | null>, uids: readonly string[], onMove: (from: number, to: number) => void) {
  const [state, setState] = useState<ReorderState>({ draggingUid: null, offsetY: 0 })
  const drag = useRef<{ uid: string; startY: number; pointerId: number } | null>(null)
  const latest = useRef({ uids, onMove })
  latest.current = { uids, onMove }

  const rowHeight = (uid: string): number => {
    const el = container.current?.querySelector<HTMLElement>(`[data-stop="${CSS.escape(uid)}"]`)
    return el?.getBoundingClientRect().height ?? 0
  }

  const handleProps = useCallback(
    (uid: string) => ({
      onPointerDown: (e: ReactPointerEvent) => {
        if (e.pointerType === 'mouse' && e.button !== 0) return
        e.preventDefault()
        e.stopPropagation()
        const target = e.currentTarget as HTMLElement
        target.setPointerCapture(e.pointerId)
        drag.current = { uid, startY: e.clientY, pointerId: e.pointerId }
        setState({ draggingUid: uid, offsetY: 0 })
        const move = (ev: PointerEvent) => {
          const d = drag.current
          if (!d || ev.pointerId !== d.pointerId) return
          let dy = ev.clientY - d.startY
          const { uids: list, onMove: move } = latest.current
          const index = list.indexOf(d.uid)
          if (index < 0) return
          if (dy > 0 && index < list.length - 1) {
            const h = rowHeight(list[index + 1])
            if (h > 0 && dy > h / 2) {
              move(index, index + 1)
              d.startY += h
              dy -= h
            }
          } else if (dy < 0 && index > 0) {
            const h = rowHeight(list[index - 1])
            if (h > 0 && -dy > h / 2) {
              move(index, index - 1)
              d.startY -= h
              dy += h
            }
          }
          setState({ draggingUid: d.uid, offsetY: dy })
        }
        const end = () => {
          drag.current = null
          setState({ draggingUid: null, offsetY: 0 })
          target.removeEventListener('pointermove', move)
          target.removeEventListener('pointerup', end)
          target.removeEventListener('pointercancel', end)
        }
        target.addEventListener('pointermove', move)
        target.addEventListener('pointerup', end)
        target.addEventListener('pointercancel', end)
      },
    }),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [],
  )

  return { ...state, handleProps }
}
