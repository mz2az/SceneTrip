/*
 * 사용법 넉 장의 곁들인 그림 (OnboardingView.swift 의 MapFragment · DayCards · LegTrace · RadiusChips).
 * 300 x 300 안의 좌표를 그대로 옮겼다. 해태는 이 위에 따로 얹는다.
 */
import { HStack, Txt, VStack, shadow } from '@/design/primitives'
import { tr } from '@/i18n'

const BOX = 300

/** 곁들인 그림에 쓰는 작은 핀 (MiniPin) — 26 x 34 물방울. */
function MiniPin({ tint, cx, cy }: { tint: string; cx: number; cy: number }) {
  return (
    <svg width={26} height={34} viewBox="0 0 26 34" style={{ position: 'absolute', left: cx - 13, top: cy - 17 }} aria-hidden>
      <path d="M3.81 22.19 A13 13 0 1 1 22.19 22.19 L13 34 Z" fill={tint} />
    </svg>
  )
}

/** ① 지도 조각과 다른 촬영지 핀들. */
function MapFragment() {
  const spots: Array<[number, number]> = [[-104, -78], [112, 98], [108, -108]]
  return (
    <div style={{ position: 'absolute', inset: 0 }}>
      <svg width={268} height={236} viewBox="0 0 268 236" style={{ position: 'absolute', left: 16, top: 32, borderRadius: 20, background: 'var(--grouped-background)' }}>
        <path d="M0 92H268M0 176H268M78 0V236M196 0V236" stroke="var(--system-gray4)" strokeWidth={7} fill="none" />
      </svg>
      {spots.map(([dx, dy], i) => (
        <MiniPin key={i} tint="var(--system-gray3)" cx={BOX / 2 + dx} cy={BOX / 2 + dy} />
      ))}
    </div>
  )
}

/** ② 일차 카드 세 장. */
function DayCards() {
  const days = [
    { number: 1, places: ['덕수궁 돌담길', '정동길 · 서울시청'], fade: 1 },
    { number: 2, places: ['북촌한옥마을', '삼청동길 · 경복궁'], fade: 1 },
    { number: 3, places: ['주문진 방파제'], fade: 0.55 },
  ]
  return (
    <div style={{ position: 'absolute', inset: 0, display: 'flex', alignItems: 'center', paddingLeft: 2 }}>
      <VStack spacing={8} align="start">
        {days.map((day) => (
          <VStack
            key={day.number}
            spacing={3}
            align="start"
            style={{ width: 132, padding: '9px 11px', background: 'var(--system-background)', borderRadius: 12, boxShadow: shadow('rgba(0,0,0,0.10)', 5, 2), opacity: day.fade }}
          >
            <Txt size={11} weight="heavy" color="deep">{tr('%d일차', day.number)}</Txt>
            {day.places.map((p) => (
              <Txt key={p} size={12}>{tr(p)}</Txt>
            ))}
          </VStack>
        ))}
      </VStack>
    </div>
  )
}

/** ③ 도보(점선) → 대중교통(실선) → 도보. 길찾기 결과 화면이 실제로 그리는 모양이다. */
function LegTrace() {
  const captions: Array<[string, number, number]> = [
    ['현재 위치', 60, 268],
    ['도보 4분', 130, 220],
    ['2호선 · 6개 역', 196, 154],
    ['도보 6분', 246, 78],
  ]
  return (
    <div style={{ position: 'absolute', inset: 0 }}>
      <svg width={BOX} height={BOX} viewBox="0 0 300 300" style={{ position: 'absolute', inset: 0, overflow: 'visible' }}>
        <path d="M42 246 C62 232 70 216 90 208" stroke="var(--accent)" strokeWidth={5} strokeLinecap="round" strokeDasharray="1 9" fill="none" />
        <path d="M90 208 C136 186 162 120 200 92" stroke="var(--pin-deep)" strokeWidth={6} strokeLinecap="round" fill="none" />
        <path d="M200 92 C220 78 230 62 244 54" stroke="var(--accent)" strokeWidth={5} strokeLinecap="round" strokeDasharray="1 9" fill="none" />
        <circle cx={42} cy={246} r={9 - 2.5} fill="var(--system-background)" stroke="var(--accent)" strokeWidth={5} />
        <circle cx={90} cy={208} r={6} fill="var(--pin-deep)" />
        <circle cx={200} cy={92} r={6} fill="var(--pin-deep)" />
      </svg>
      <MiniPin tint="var(--pin-deep)" cx={244} cy={44} />
      {captions.map(([text, x, y]) => (
        <Txt key={text} size={11} weight="semibold" color="secondary" style={{ position: 'absolute', left: x, top: y, transform: 'translate(-50%, -50%)', whiteSpace: 'nowrap' }}>
          {tr(text)}
        </Txt>
      ))}
    </div>
  )
}

/** ④ 반경과 갈래 넷. 색은 RoutePoiTone — 음식점 빨강, 숙소 초록, 교통 노랑, 명소 파랑. */
function RadiusChips() {
  const chips = [
    { label: '음식점', color: '#E32933', dx: -116, dy: -100 },
    { label: '명소', color: 'var(--accent)', dx: 122, dy: 4 },
    { label: '교통', color: '#EDBA0D', dx: -112, dy: 66 },
    { label: '숙소', color: '#21A85E', dx: 104, dy: 92 },
  ]
  return (
    <div style={{ position: 'absolute', inset: 0 }}>
      <svg width={262} height={262} viewBox="0 0 262 262" style={{ position: 'absolute', left: 19, top: 19 }} aria-hidden>
        <circle cx={131} cy={131} r={130.25} fill="color-mix(in srgb, var(--accent) 6%, transparent)" stroke="color-mix(in srgb, var(--accent) 45%, transparent)" strokeWidth={1.5} strokeDasharray="6 6" />
      </svg>
      {chips.map((c) => (
        <HStack
          key={c.label}
          spacing={6}
          style={{
            position: 'absolute', left: BOX / 2 + c.dx, top: BOX / 2 + c.dy, transform: 'translate(-50%, -50%)',
            width: 'max-content', padding: '6px 12px 6px 8px', background: 'var(--system-background)', borderRadius: 999, boxShadow: shadow('rgba(0,0,0,0.10)', 5, 2),
          }}
        >
          <span style={{ width: 9, height: 9, borderRadius: '50%', background: c.color }} />
          <Txt size={13} weight="semibold">{tr(c.label)}</Txt>
        </HStack>
      ))}
    </div>
  )
}

export function Backdrop({ index }: { index: number }) {
  switch (index) {
    case 0:
      return <MapFragment />
    case 1:
      return <DayCards />
    case 2:
      return <LegTrace />
    default:
      return <RadiusChips />
  }
}
