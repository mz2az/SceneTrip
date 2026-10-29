/*
 * 도착 스탬프 연출 — iOS RouteTab/PawStamp.swift. 크게 나타나 **쾅** 하고 자리 잡고, 잠시 뒤 사라진다. 도장을 찍는 손짓을 흉내 낸다: 크고 흐리게 시작해(공중)
 * 빠르게 줄어들며 진해진다(종이에 닿음). 살짝 기울인 채 찍혀 손으로 찍은 느낌을 남긴다. 햅틱 한 번.
 *
 * 발바닥 — 큰 패드 하나 + 발가락 셋. 지도 점에서 걷어낸 모양(2026-09-02)을 스탬프로 살렸다 — 「해태가 밟고 간 자리」라는 원래 뜻과 맞고, 화면 가운데 쾅 찍히는
 * 연출은 원보다 발바닥이 낫다.
 */
import { useEffect, useState } from 'react'
import { Txt, VStack } from '@/design/primitives'
import './tripUi.css'

/** 20×20 기준 좌표(원래 `guideDot` 의 수치) — 큰 패드 하나 + 발가락 셋. */
const PAW: ReadonlyArray<readonly [number, number, number, number]> = [
  [5.2, 10.2, 9.6, 7.6],
  [3.4, 5.4, 4.4, 4.6],
  [7.9, 3.2, 4.4, 4.6],
  [12.4, 5.4, 4.4, 4.6],
]

let gradientId = 0

export function PawShape({ size, gradient = true, color = '#fff', rotate = -12 }: { size: number; gradient?: boolean; color?: string; rotate?: number }) {
  const [id] = useState(() => `paw-g${(gradientId += 1)}`)
  return (
    <svg width={size} height={size} viewBox="0 0 20 20" style={{ transform: `rotate(${rotate}deg)` }} aria-hidden>
      {gradient && (
        <defs>
          <linearGradient id={id} x1="0" y1="0" x2="1" y2="1">
            <stop offset="0" stopColor="var(--pin-light)" />
            <stop offset="1" stopColor="var(--pin-deep)" />
          </linearGradient>
        </defs>
      )}
      {PAW.map(([x, y, w, h], i) => (
        <ellipse key={i} cx={x + w / 2} cy={y + h / 2} rx={w / 2} ry={h / 2} fill={gradient ? `url(#${id})` : color} />
      ))}
    </svg>
  )
}

const SHOW_MS = 1600

export function StampOverlay({ title, subtitle, onDone }: { title: string; subtitle: string; onDone: () => void }) {
  useEffect(() => {
    // 햅틱 한 번(지원하는 기기에서만).
    if (typeof navigator !== 'undefined' && 'vibrate' in navigator) navigator.vibrate?.(30)
    const t = setTimeout(onDone, SHOW_MS)
    return () => clearTimeout(t)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])
  return (
    <div className="tp-stamp-scrim" role="status" aria-label={`${title} ${subtitle}`}>
      <VStack spacing={14} align="center">
        <div className="tp-stamp-disc">
          <PawShape size={96} />
        </div>
        <VStack spacing={4} align="center" className="tp-stamp-label">
          <Txt size={20} weight="heavy">{title}</Txt>
          <Txt size={14} color="secondary" lines={1}>{subtitle}</Txt>
        </VStack>
      </VStack>
    </div>
  )
}
