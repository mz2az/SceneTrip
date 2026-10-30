/*
 * 화면 오른쪽 아래의 **해태 「내가 도와줄게!」** — iOS RouteTab/RouteGuideFloat.swift (RouteGuideFloatingChip) 와 RouteChatButton.swift (RouteGuideChipBody).
 *
 * 지도 안 오른쪽 위에 두었을 때는 말풍선 폭 때문에 「내 위치」·「발자취」 동그라미가 왼쪽으로 밀렸고, 동작 줄의 「AI 가이드」 단추와 하는 일이 같아 둘이 됐다
 * (2026-09-16 사용자 지적). 그래서 단추는 없애고 이 동그라미 하나를 지도가 아니라 화면에 띄운다. **꾹 누르면 옮길 수 있다.** 옮긴 자리는 기기에 남아 다음에 열어도
 * 그대로다 — 카드·시트가 자주 겹치는 자리라 사용자가 스스로 비켜 둘 수 있어야 한다. 탭과 끌기를 여기서 직접 다룬다(단추가 길게 누르기를 먼저 먹어 끌기가 시작되지
 * 않았다 — 2026-09-16 사용자 확인).
 */
import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { readStorage, writeStorage } from '@/state/storage'
import { tr } from '@/i18n'
import './guide.css'
import { type Box, CHIP_BOTTOM, CHIP_EDGE, chipMoved, chipOffsetX, chipOffsetY, clampChip, clearanceOf } from './guideRules'

const KEY_X = 'scenetrip.guideChip.dx'
const KEY_Y = 'scenetrip.guideChip.dy'
/** 꾹 누름으로 치는 시간(초) — LongPressGesture(minimumDuration: 0.3). */
const LONG_PRESS_MS = 300
const FACE = 46
const LIFT_SCALE = 1.08

const num = (key: string): number => {
  const v = Number(readStorage(key))
  return Number.isFinite(v) ? v : 0
}

/** 접힌 가이드의 **그림만.** 단추가 아니다. `bubble` 은 정보 카드가 떠 있는 동안 접어 얼굴만 남긴다(MZ2AZ-367). */
export function GuideChipBody({ bubble = true }: { bubble?: boolean }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
      {bubble && <span className="gd-chip-bubble">{tr('내가 도와줄게!')}</span>}
      <span className="gd-chip-face" style={{ width: FACE, height: FACE }}>
        <img src="/generated/haetae-face.png" alt="" draggable={false} />
      </span>
    </div>
  )
}

export interface GuideFloatingChipProps {
  /** 열려 있는 가이드 창·핀 찍기 중에는 숨긴다. 창은 같은 구석에서 나오고, 핀 찍기는 지도를 눌러야 하는데 손에 걸린다. */
  hidden?: boolean
  /** 화면 바닥에 뜬 정보 카드·성지 카드의 윗변 높이(바닥에서). 카드가 없으면 null — 카드가 떠 있는 동안은 말풍선을 접고 카드 위로 비킨다. */
  cardTop?: number | null
  /** 카드를 비켜 선 자리에서 덮으면 안 되는 단추의 틀(컨테이너 좌표) — 일차 머리줄의 「머무는 시간 ›」. */
  keepClear?: Box | null
  onTap: () => void
}

/** 컨테이너(position: relative 인 오버레이 층) 안의 오른쪽 아래에 붙는다. */
export function GuideFloatingChip({ hidden = false, cardTop = null, keepClear = null, onTap }: GuideFloatingChipProps) {
  const root = useRef<HTMLDivElement>(null)
  const [size, setSize] = useState({ width: 402, height: 874 })
  const [stored, setStored] = useState(() => ({ x: num(KEY_X), y: num(KEY_Y) }))
  const [drag, setDrag] = useState<{ x: number; y: number } | null>(null)
  const [lifted, setLifted] = useState(false)
  const press = useRef<{ id: number; x: number; y: number; timer: ReturnType<typeof setTimeout> | null; lifted: boolean; dx: number; dy: number } | null>(null)

  useLayoutEffect(() => {
    const parent = root.current?.parentElement
    if (!parent) return
    const measure = () => setSize({ width: parent.clientWidth, height: parent.clientHeight })
    measure()
    const ro = new ResizeObserver(measure)
    ro.observe(parent)
    return () => ro.disconnect()
  }, [])
  useEffect(() => () => void (press.current?.timer && clearTimeout(press.current.timer)), [])

  const clear = clearanceOf(keepClear, { x: 0, y: 0, width: size.width, height: size.height })
  /** 지금 서 있을 자리(기본 자리에서 잰 이동) — 저장된 자리 + 카드·단추를 비킨 만큼 + 끄는 만큼. */
  const spot = (d: { x: number; y: number }) =>
    clampChip({ x: chipOffsetX({ storedX: stored.x, storedY: stored.y, dragging: d.x, cardTop, clear }), y: chipOffsetY({ stored: stored.y, dragging: d.y, cardTop }) }, size)
  const at = spot(drag ?? { x: 0, y: 0 })

  const onDown = (e: React.PointerEvent) => {
    e.currentTarget.setPointerCapture(e.pointerId)
    const p = { id: e.pointerId, x: e.clientX, y: e.clientY, timer: null as ReturnType<typeof setTimeout> | null, lifted: false, dx: 0, dy: 0 }
    p.timer = setTimeout(() => {
      p.lifted = true
      setLifted(true)
    }, LONG_PRESS_MS)
    press.current = p
  }
  const onMove = (e: React.PointerEvent) => {
    const p = press.current
    if (!p || p.id !== e.pointerId) return
    p.dx = e.clientX - p.x
    p.dy = e.clientY - p.y
    // 꾹 누르기 전에 움직이면 끌기가 아니다 — 지도·시트의 손짓과 싸우지 않는다.
    if (!p.lifted) {
      if (chipMoved(p.dx, p.dy)) {
        if (p.timer) clearTimeout(p.timer)
        press.current = null
      }
      return
    }
    setDrag({ x: p.dx, y: p.dy })
  }
  const onUp = (e: React.PointerEvent) => {
    const p = press.current
    if (!p || p.id !== e.pointerId) return
    if (p.timer) clearTimeout(p.timer)
    press.current = null
    if (!p.lifted) return onTap()
    // 꾹 눌렀다 그대로 떼면 저장하지 않는다 — 카드를 비켜 서 있던 자리가 제자리로 굳는다. 보이던 그 자리를 남긴다.
    if (chipMoved(p.dx, p.dy)) {
      const next = spot({ x: p.dx, y: p.dy })
      setStored({ x: next.x, y: next.y })
      writeStorage(KEY_X, String(next.x))
      writeStorage(KEY_Y, String(next.y))
    }
    setDrag(null)
    setLifted(false)
  }

  return (
    <div
      ref={root}
      className={`gd-chip${hidden ? ' gd-chip-hidden' : ''}${drag ? ' gd-chip-dragging' : ''}`}
      role="button"
      aria-label={tr('내가 도와줄게!')}
      tabIndex={hidden ? -1 : 0}
      onPointerDown={onDown}
      onPointerMove={onMove}
      onPointerUp={onUp}
      onPointerCancel={() => {
        if (press.current?.timer) clearTimeout(press.current.timer)
        press.current = null
        setDrag(null)
        setLifted(false)
      }}
      onKeyDown={(e) => (e.key === 'Enter' || e.key === ' ') && onTap()}
      style={{ right: CHIP_EDGE, bottom: CHIP_BOTTOM, transform: `translate(${at.x}px, ${at.y}px) scale(${lifted ? LIFT_SCALE : 1})`, filter: lifted ? 'drop-shadow(0 4px 10px rgba(0,0,0,0.3))' : undefined }}
    >
      <GuideChipBody bubble={cardTop === null} />
    </div>
  )
}
