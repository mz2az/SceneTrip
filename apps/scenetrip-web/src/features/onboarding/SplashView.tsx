/*
 * 앱을 열면 처음 보이는 화면. (iOS: Onboarding/SplashView.swift)
 * 핀(해태)이 위에서 떨어져 한 번 눌렸다 펴고, 글자는 늦게 올라온다. 1.9초 뒤 onDone.
 */
import { useEffect, useRef } from 'react'
import { PinoMascot, pinoTipInset } from '@/design/PinoMascot'
import { Txt, VStack } from '@/design/primitives'
import { useDiagonalAngle } from '@/design/useDiagonalAngle'
import './splash.css'

export function SplashView({ onDone }: { onDone: () => void }) {
  const [gradRef, angle] = useDiagonalAngle(155.3)
  const done = useRef(onDone)
  done.current = onDone
  useEffect(() => {
    const t = setTimeout(() => done.current(), 1900)
    return () => clearTimeout(t)
  }, [])

  const w = 216
  return (
    <div ref={gradRef} className="splash" style={{ ['--splash-angle' as string]: `${angle}deg`, position: 'absolute', inset: 0, zIndex: 100, overflow: 'hidden' }}>
      <svg width="100%" height="100%" viewBox="0 0 390 844" preserveAspectRatio="none" style={{ position: 'absolute', inset: 0 }} aria-hidden>
        {/* RoadTracery — 바탕에 아주 흐리게 깔리는 길과 교차점 (SplashView.swift:RoadTracery, 폭 390·높이 844 기준 비율) */}
        <g fill="none" stroke="rgba(255,255,255,0.13)" strokeWidth="1">
          <path d={`M-20 ${844 * 0.24} C${390 * 0.36} ${844 * 0.28} ${390 * 0.62} ${844 * 0.27} ${390 + 20} ${844 * 0.32}`} />
          <path d={`M-20 ${844 * 0.73} C${390 * 0.3} ${844 * 0.7} ${390 * 0.68} ${844 * 0.8} ${390 + 20} ${844 * 0.77}`} />
          <path d={`M${390 * 0.18} -20 C${390 * 0.06} ${844 * 0.36} ${390 * 0.34} ${844 * 0.64} ${390 * 0.27} ${844 + 20}`} />
          <path d={`M${390 * 0.76} -20 C${390 * 0.64} ${844 * 0.34} ${390 * 0.92} ${844 * 0.66} ${390 * 0.84} ${844 + 20}`} />
        </g>
      </svg>
      <VStack align="center" justify="center" style={{ position: 'absolute', inset: 0 }}>
        <div style={{ position: 'relative', width: w }}>
          {/* 착지 그림자 — 핀과 따로 움직여야 「바닥에 꽂혔다」로 보인다. 핀 끝 밑에 깐다. */}
          <div className="splash-shadow" style={{ position: 'absolute', left: '50%', bottom: 11 - pinoTipInset(w) < 0 ? 0 : -(11 - pinoTipInset(w)), width: 108, height: 22, marginLeft: -54 }} />
          <div className="splash-pin">
            {/* 뒤에서 도는 빛무리 */}
            <div style={{ position: 'absolute', left: '50%', top: 4, width: 190, height: 190, marginLeft: -95, borderRadius: '50%', background: 'rgba(255,255,255,0.28)', filter: 'blur(26px)' }} />
            <div style={{ position: 'relative' }}>
              <PinoMascot pose="plain" width={w} />
            </div>
          </div>
        </div>
        <div style={{ height: 30 }} />
        <VStack spacing={12} align="center" className="splash-text">
          <Txt size={44} weight="heavy" kerning={-1.2} color="white" style={{ transform: 'translateY(-0.7px)' }}>SceneTrip</Txt>
          <Txt size={15} weight="medium" style={{ color: 'rgba(255,255,255,0.82)', marginTop: 0.7 }}>Stand where the scene happened</Txt>
        </VStack>
      </VStack>
    </div>
  )
}
