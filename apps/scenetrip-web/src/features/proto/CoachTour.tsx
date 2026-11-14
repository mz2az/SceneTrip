/*
 * 처음 보는 사람을 위한 짧은 안내 — 버튼만 밝게 남기고 나머지를 어둡게 덮은 뒤, 말풍선이 그 버튼을 가리키며 무엇을 하는지 말한다(2026-11-09 사용자 요청).
 *
 * 안내마다 `id` 가 있고 한 번 끝까지 보거나 「건너뛰기」 하면 이 브라우저에서는 다시 띄우지 않는다(localStorage). 가리킬 버튼은 `data-coach`
 * 같은 선택자로 찾는다 — 화면 코드를 감싸지 않고 표식만 단다. 버튼이 아직 없으면(시트가 올라오는 중) 잠깐 기다리고, 끝내 없으면 그 단계를 건너뛴다.
 */
import { track, webEvent } from '@/analytics'
import { type CSSProperties, useEffect, useLayoutEffect, useRef, useState } from 'react'
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
/** 앱 틀 가장자리에서 말풍선·구멍이 떨어지는 거리. 노치·홈 막대(안전 영역)는 따로 더한다. */
const EDGE = 12
/** 가리킬 요소를 기다리는 시간 — 넘으면 그 단계를 건너뛴다. */
const WAIT_MS = 4000

// 주소에 `?coach=reset` 을 붙이면 본 기록을 지운다 — 안내를 처음부터 다시 볼 때(시험용).
try {
  if (new URLSearchParams(window.location.search).get('coach') === 'reset') writeJSON(SEEN_KEY, [])
} catch {
  // 주소를 못 읽으면 그대로 둔다.
}

export const coachSeen = (id: string): boolean => readJSON<string[]>(SEEN_KEY, []).includes(id)
const markSeen = (id: string) => writeJSON(SEEN_KEY, [...new Set([...readJSON<string[]>(SEEN_KEY, []), id])])

type Box = { top: number; left: number; width: number; height: number }

/** 안내를 그리는 곳 — 앱 틀(.app-frame) 안. 앱으로 감싸도(웹뷰) 틀 밖으로 나가지 않는다(2026-11-09 사용자 지적). */
const hostOf = (): HTMLElement => document.querySelector<HTMLElement>('.app-frame') ?? document.body

/** 휴대폰의 노치·홈 막대 높이(안전 영역). CSS env() 값을 한 번 재서 숫자로. */
let insets: { top: number; bottom: number } | null = null
function safeInsets(): { top: number; bottom: number } {
  if (insets) return insets
  const probe = document.createElement('div')
  probe.style.cssText = 'position:fixed;visibility:hidden;padding-top:env(safe-area-inset-top,0px);padding-bottom:env(safe-area-inset-bottom,0px)'
  document.body.appendChild(probe)
  const cs = getComputedStyle(probe)
  insets = { top: parseFloat(cs.paddingTop) || 0, bottom: parseFloat(cs.paddingBottom) || 0 }
  probe.remove()
  return insets
}

const clamp = (n: number, lo: number, hi: number) => Math.max(lo, Math.min(hi, n))

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
  /** 앱 틀의 화면상 자리 — 안내의 좌표는 이 틀 기준이다. */
  const [frame, setFrame] = useState<Box>({ top: 0, left: 0, width: window.innerWidth, height: window.innerHeight })
  const bubbleRef = useRef<HTMLDivElement>(null)
  const [bubbleH, setBubbleH] = useState(150)

  // 한 번에 안내 하나 — 다른 안내가 떠 있으면(예: 계획 화면 안내 중에 지도 검색 안내) 그것이 끝날 때까지 기다린다.
  useEffect(() => {
    if (!active || index !== null || coachSeen(id)) return
    let t = 0
    const tryStart = () => {
      if (document.querySelector('.proto-coach-bubble')) t = window.setTimeout(tryStart, 400)
      else {
        // 사용법 안내가 시작됐다 — 웹 전용 coach_tour(공통 tutorial_* 와 뜻이 달라 따로, web-proto-events.md).
        track(webEvent.coachTour(id, 'begin'))
        setIndex(0)
      }
    }
    t = window.setTimeout(tryStart, delay)
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
  const next = () => {
    if (index === null) return
    if (index + 1 < steps.length) return setIndex(index + 1)
    // 마지막 단계까지 봤다 — 건너뛰기는 세지 않는다.
    markSeen(id)
    track(webEvent.coachTour(id, 'complete'))
    setIndex(null)
  }

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
      const f = hostOf().getBoundingClientRect()
      setFrame((v) => (v.top === f.top && v.left === f.left && v.width === f.width && v.height === f.height ? v : { top: f.top, left: f.left, width: f.width, height: f.height }))
      const h = bubbleRef.current?.offsetHeight
      if (h) setBubbleH((v) => (v === h ? v : h))
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
  // 모든 좌표를 앱 틀 기준으로 바꾸고, 틀 가장자리·안전 영역 안으로 가둔다.
  const safe = safeInsets()
  const inner = { top: EDGE + safe.top, left: EDGE, right: frame.width - EDGE, bottom: frame.height - EDGE - safe.bottom }
  const t = { top: box.top - frame.top, left: box.left - frame.left, width: box.width, height: box.height }
  const hTop = clamp(t.top - PAD, inner.top - EDGE / 2, inner.bottom)
  const hLeft = clamp(t.left - PAD, 4, inner.right)
  const hRight = clamp(t.left + t.width + PAD, hLeft, frame.width - 4)
  const hBottom = clamp(t.top + t.height + PAD, hTop, inner.bottom + EDGE / 2)
  const hole = { top: hTop, left: hLeft, width: hRight - hLeft, height: hBottom - hTop }
  const width = Math.min(BUBBLE_W, inner.right - inner.left)
  // 말풍선은 아래에 자리가 있으면 아래, 아니면 위. 어느 쪽이든 틀 안으로 밀어 넣는다.
  const below = hole.top + hole.height + GAP + bubbleH <= inner.bottom || hole.top - GAP - bubbleH < inner.top
  const rawTop = below ? hole.top + hole.height + GAP : hole.top - GAP - bubbleH
  const bubbleTop = clamp(rawTop, inner.top, Math.max(inner.top, inner.bottom - bubbleH))
  const bubbleLeft = clamp(t.left + t.width / 2 - width / 2, inner.left, inner.right - width)
  const arrowLeft = clamp(t.left + t.width / 2 - bubbleLeft - 8, 18, width - 34)
  // 말풍선을 밀어 넣느라 구멍과 겹치면 화살표는 숨긴다(가리킬 곳이 말풍선 밑이다).
  const arrow = below ? bubbleTop >= hole.top + hole.height : bubbleTop + bubbleH <= hole.top
  const bubble: CSSProperties = { top: bubbleTop, left: bubbleLeft }
  const inFrame = hostOf() !== document.body

  return createPortal(
    <div role="dialog" aria-modal="true" aria-label={step.title} style={{ position: inFrame ? 'absolute' : 'fixed', inset: 0, zIndex: 10000, overflow: 'hidden' }}>
      {/* 덮개 — 누르면 다음으로. 구멍 밖을 어둡게 하는 것은 구멍 상자의 큰 그림자다. */}
      <div onClick={next} style={{ position: 'absolute', inset: 0 }} />
      <div
        aria-hidden
        className="proto-coach-hole"
        style={{ position: 'absolute', ...hole, borderRadius: Math.min(22, hole.height / 2), boxShadow: '0 0 0 9999px rgba(10,8,25,0.62)', pointerEvents: 'none' }}
      />
      <div ref={bubbleRef} className="proto-coach-bubble" style={{ position: 'absolute', width, ...bubble, background: 'var(--system-background)', borderRadius: radius.m, padding: '14px 16px 12px', boxShadow: '0 10px 30px rgba(0,0,0,0.25)' }}>
        {arrow && <span aria-hidden style={{ position: 'absolute', left: arrowLeft, [below ? 'top' : 'bottom']: -7, width: 16, height: 16, background: 'var(--system-background)', transform: 'rotate(45deg)', borderRadius: 3 }} />}
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
    hostOf(),
  )
}
