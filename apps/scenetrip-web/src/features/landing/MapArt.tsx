/*
 * 「마이맵」 그림 — 세 번 접힌 종이 지도와 그 위의 길, 분홍 핀.
 * 전에는 덮개 달린 상자(가방)였는데 무엇인지 알아보기 어렵고 해태 그림체와 동떨어졌다(2026-11-16 사용자 지적).
 * 해태처럼 굵은 남색 외곽선과 연한 바탕으로 그린다. 담긴 수는 그림 밖 `.st-film__bag-count` 가 보인다.
 */
import type { JSX } from 'react'

const INK = '#2a1856'

/**
 * `bite` — 해태가 입에 무는 모양. 윗변이 곧아야 입 선과 맞으므로 접힌 지그재그를 아랫변으로 옮긴다.
 */
export function MapArt({ bite = false }: { bite?: boolean }): JSX.Element {
  return (
    <svg className="st-film__map" viewBox="0 0 100 80" width={100} height={80} aria-hidden focusable="false">
      <g stroke={INK} strokeWidth={3.5} strokeLinejoin="round">
        {bite ? (
          <>
            <path d="M6 10 L35 10 L35 66 L6 74 Z" fill="#fff4dc" />
            <path d="M35 10 L65 10 L65 74 L35 66 Z" fill="#f0dcb0" />
            <path d="M65 10 L94 10 L94 66 L65 74 Z" fill="#fff4dc" />
          </>
        ) : (
          <>
            <path d="M6 16 L35 7 L35 63 L6 72 Z" fill="#fff4dc" />
            <path d="M35 7 L65 16 L65 72 L35 63 Z" fill="#f0dcb0" />
            <path d="M65 16 L94 7 L94 63 L65 72 Z" fill="#fff4dc" />
          </>
        )}
      </g>
      {/* 공원 한 조각과 강 — 지도로 읽히게 하는 것만 */}
      <path d="M10 30 Q18 24 26 29 Q24 38 14 37 Z" fill="#a8dca0" />
      <path d="M38 20 Q48 30 44 44 T 56 66" fill="none" stroke="#9fd0f5" strokeWidth={5} strokeLinecap="round" />
      <path d="M14 58 C 26 44, 40 60, 52 46 S 72 36, 80 30" fill="none" stroke="#7b69ed" strokeWidth={3.2} strokeLinecap="round" strokeDasharray="5 5" />
      <g transform="translate(80 4)">
        <path d="M0 26 C -3 19, -10 15, -10 9 A 10 10 0 1 1 10 9 C 10 15, 3 19, 0 26 Z" fill="#ff5d8f" stroke={INK} strokeWidth={3} strokeLinejoin="round" />
        <circle cx={0} cy={9} r={3.6} fill="#fff" />
      </g>
    </svg>
  )
}
