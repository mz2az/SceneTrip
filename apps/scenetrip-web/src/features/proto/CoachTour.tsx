/*
 * 처음 보는 사람을 위한 짧은 안내 — 버튼만 밝게 남기고 나머지를 어둡게 덮은 뒤, 말풍선이 그 버튼을 가리키며 무엇을 하는지 말한다(2026-11-09 사용자 요청).
 *
 * 안내마다 `id` 가 있고 한 번 끝까지 보거나 「건너뛰기」 하면 이 브라우저에서는 다시 띄우지 않는다(localStorage). 가리킬 버튼은 `data-coach`
 * 같은 선택자로 찾는다 — 화면 코드를 감싸지 않고 표식만 단다. 버튼이 아직 없으면(시트가 올라오는 중) 잠깐 기다리고, 끝내 없으면 그 단계를 건너뛴다.
 */
import { type CSSProperties, useEffect, useLayoutEffect, useState } from 'react'
import { createPortal } from 'react-dom'
import { Txt } from '@/design/primitives'
import { readJSON, writeJSON } from '@/state/storage'
import { say } from './protoState'
import { color, radius } from './protoTokens'

export interface CoachStep {
  /** 가리킬 요소의 CSS 선택자(예: `[data-coach="plan-trip"]`). */
  target: string
  title: string
  text: string
}

const SEEN_KEY = 'proto.coach.seen'
const PAD = 6
const BUBBLE_W = 280
const GAP = 12
/** 가리킬 요소를 기다리는 시간 — 넘으면 그 단계를 건너뛴다. */
const WAIT_MS = 4000

export const coachSeen = (id: string): boolean => readJSON<string[]>(SEEN_KEY, []).includes(id)
const markSeen = (id: string) => writeJSON(SEEN_KEY, [...new Set([...readJSON<string[]>(SEEN_KEY, []), id])])

type Box = { top: number; left: number; width: number; height: number }

function boxOf(selector: string): Box | null {
  const el = document.querySelector(selector)
  if (!el) return null
  const r = el.getBoundingClientRect()
  if (r.width === 0 && r.height === 0) return null
  return { top: r.top, left: r.left, width: r.width, height: r.height }
}

/** `active` 가 되면(그리고 아직 안 봤으면) `delay` 뒤에 띄운다. */
export function CoachTour({ id, steps, active, delay = 500 }: { id: string; steps: readonly CoachStep[]; active: boolean; delay?: number }) {
  const [index, setIndex] = useState<number | null>(null)
  const [box, setBox] = useState<Box | null>(null)
  const [view, setView] = useState({ w: window.innerWidth, h: window.innerHeight })

  useEffect(() => {
    if (!active || index !== null || coachSeen(id)) return
    const t = window.setTimeout(() => setIndex(0), delay)
    return () => window.clearTimeout(t)
  }, [active, id, delay, index])

  // 화면을 떠나면(시트가 닫히는 등) 안내도 접는다. 다 본 것은 아니므로 다음에 다시 띄운다.
  useEffect(() => {
    if (!active) setIndex(null)
  }, [active])

  const finish = () => {
    markSeen(id)
    setIndex(null)
  }
  const next = () => setIndex((i) => (i === null ? null : i + 1 < steps.length ? i + 1 : (markSeen(id), null)))

  // 가리킬 요소의 자리를 계속 따라간다 — 시트가 올라오거나 화면이 움직여도 말풍선이 붙어 있게.
  useLayoutEffect(() => {
    if (index === null) return
    const step = steps[index]
    if (!step) return
    const started = Date.now()
    let frame = 0
    const tick = () => {
      const found = boxOf(step.target)
      setBox((prev) => (found && prev && Math.abs(prev.top - found.top) + Math.abs(prev.left - found.left) + Math.abs(prev.width - found.width) < 0.5 ? prev : found))
      setView((v) => (v.w === window.innerWidth && v.h === window.innerHeight ? v : { w: window.innerWidth, h: window.innerHeight }))
      if (!found && Date.now() - started > WAIT_MS) {
        next()
        return
      }
      frame = window.requestAnimationFrame(tick)
    }
    tick()
    return () => window.cancelAnimationFrame(frame)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [index])

  if (index === null || !box) return null
  const step = steps[index]
  const last = index === steps.length - 1
  const hole = { top: box.top - PAD, left: box.left - PAD, width: box.width + PAD * 2, height: box.height + PAD * 2 }
  // 말풍선은 아래쪽 자리가 넉넉하면 아래, 아니면 위.
  const below = hole.top + hole.height + GAP + 150 < view.h
  const bubbleLeft = Math.min(Math.max(16, box.left + box.width / 2 - BUBBLE_W / 2), view.w - 16 - BUBBLE_W)
  const arrowLeft = Math.min(Math.max(18, box.left + box.width / 2 - bubbleLeft - 8), BUBBLE_W - 34)
  const bubble: CSSProperties = below
    ? { top: hole.top + hole.height + GAP, left: bubbleLeft }
    : { bottom: view.h - hole.top + GAP, left: bubbleLeft }

  return createPortal(
    <div role="dialog" aria-modal="true" aria-label={step.title} style={{ position: 'fixed', inset: 0, zIndex: 10000 }}>
      {/* 덮개 — 누르면 다음으로. 구멍 밖을 어둡게 하는 것은 구멍 상자의 큰 그림자다. */}
      <div onClick={next} style={{ position: 'absolute', inset: 0 }} />
      <div
        aria-hidden
        className="proto-coach-hole"
        style={{ position: 'absolute', ...hole, borderRadius: Math.min(22, hole.height / 2), boxShadow: '0 0 0 9999px rgba(10,8,25,0.62)', pointerEvents: 'none' }}
      />
      <div className="proto-coach-bubble" style={{ position: 'absolute', width: BUBBLE_W, ...bubble, background: 'var(--system-background)', borderRadius: radius.m, padding: '14px 16px 12px', boxShadow: '0 10px 30px rgba(0,0,0,0.25)' }}>
        <span aria-hidden style={{ position: 'absolute', left: arrowLeft, [below ? 'top' : 'bottom']: -7, width: 16, height: 16, background: 'var(--system-background)', transform: 'rotate(45deg)', borderRadius: 3 }} />
        <Txt size={16} weight="heavy" style={{ display: 'block', marginBottom: 4 }}>{step.title}</Txt>
        <Txt size={14} color="secondary" style={{ display: 'block', lineHeight: 1.45 }}>{step.text}</Txt>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginTop: 12 }}>
          <Txt size={12} color="tertiary" style={{ fontVariantNumeric: 'tabular-nums' }}>{index + 1} / {steps.length}</Txt>
          <span style={{ flex: 1 }} />
          {!last && (
            <button type="button" onClick={finish} style={{ all: 'unset', cursor: 'pointer', padding: '8px 6px', fontSize: 14, color: 'var(--label-secondary)' }}>
              {say('건너뛰기', 'Skip')}
            </button>
          )}
          <button
            type="button"
            onClick={last ? finish : next}
            autoFocus
            style={{ all: 'unset', cursor: 'pointer', padding: '8px 14px', borderRadius: radius.pill, background: color.primaryFill, color: color.onPrimary, fontSize: 14, fontWeight: 700 }}
          >
            {last ? say('알겠어요', 'Got it') : say('다음', 'Next')}
          </button>
        </div>
      </div>
    </div>,
    document.body,
  )
}
