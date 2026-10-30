/*
 * 해태 마스코트 — iOS `PinoMascot` (Onboarding/PinoMascot.swift). 정지 일러스트에 코드 모션(숨쉬기·살랑)을 얹는다.
 * 포즈별 그림: plain/speech = haetae-sit, magnifier = haetae-bow, sparkle = haetae-joy(+반짝별), paw = haetae-pinhold.
 */
import './pino.css'

export type PinoPose = 'plain' | 'magnifier' | 'sparkle' | 'paw' | 'speech'

/** 그림의 세로/가로 비율 (Pino.aspect) */
export const PINO_ASPECT = 582 / 525

/** 프레임 바닥과 발끝 사이 — 스플래시의 그림자가 쓴다 (Pino.tipInset). */
export function pinoTipInset(width: number): number {
  return width * 0.05
}

const IMAGE: Record<PinoPose, string> = {
  plain: 'haetae-sit',
  speech: 'haetae-sit',
  magnifier: 'haetae-bow',
  sparkle: 'haetae-joy',
  paw: 'haetae-pinhold',
}

/** 네 갈래 별 (PinoSparkle). `arm` 은 팔 길이, 중심은 (0,0). */
function sparklePath(arm: number): string {
  const w = arm * 0.42
  return `M0 ${-arm} L${w} ${-w} L${arm} 0 L${w} ${w} L0 ${arm} L${-w} ${w} L${-arm} 0 L${-w} ${-w} Z`
}

export function PinoMascot({ pose = 'plain', width = 180, alive = true }: { pose?: PinoPose; width?: number; alive?: boolean }) {
  const h = width * PINO_ASPECT
  return (
    <div style={{ width, height: h, display: 'grid', placeItems: 'center', flex: 'none' }} aria-hidden>
      <div className={alive ? 'pino-alive' : undefined} style={{ position: 'relative', width, height: h, display: 'grid', placeItems: 'center' }}>
        <div style={{ position: 'relative', width, alignSelf: 'center' }}>
          <img src={`/generated/${IMAGE[pose]}.png`} alt="" style={{ width, height: 'auto', display: 'block' }} draggable={false} />
          {pose === 'sparkle' && (
            <svg width={width} height={width} style={{ position: 'absolute', left: 0, top: 0, overflow: 'visible' }}>
              <path d={sparklePath(width * 0.07)} fill="#FFD426" transform={`translate(${width * 0.94} ${width * 0.12})`} />
              <path d={sparklePath(width * 0.045)} fill="#FFD426" transform={`translate(${width * 0.08} ${width * 0.5})`} />
            </svg>
          )}
          {pose === 'speech' && (
            <div
              style={{
                position: 'absolute', left: width * 0.72, top: -width * 0.04, width: width * 0.3, height: width * 0.19,
                borderRadius: width * 0.07, background: 'var(--accent)', display: 'flex', alignItems: 'center', justifyContent: 'center', gap: width * 0.035,
              }}
            >
              {[0, 1, 2].map((i) => (
                <span key={i} style={{ width: width * 0.032, height: width * 0.032, borderRadius: '50%', background: '#fff' }} />
              ))}
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
