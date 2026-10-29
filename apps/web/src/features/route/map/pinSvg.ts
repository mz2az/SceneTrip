/*
 * 코스 지도의 핀 그림 — 번호 핀(PinImage.numbered, NaverMapView.swift)과 해태 핀(PinoPin.marker, RouteTab/PinoPin.swift).
 * 번호 핀은 38×50 물방울(머리 원 반지름 14, 꼬리 끝 (19,45)), 파스텔 하늘→보라 세로 그라데이션에 흰 테두리 1.5 와 그림자, 번호는 머리의 흰 원 배지 안에 컬러로.
 * 해태 핀은 40×52 — 같은 물방울에 해태 얼굴을 넣는다. 평소는 하늘→보라, 「지금 고른 곳」은 빨강 + 1.1배(파랑·보라 사이에서 빨강이 가장 잘 튄다).
 * 다녀온 곳은 번호 핀이 발바닥 도장(그라데이션 원 30)으로 바뀐다.
 */
export const PIN_LIGHT = '#8fccf7' // UIColor(0.56, 0.80, 0.97)
export const PIN_DEEP = '#7a69ed' // UIColor(0.48, 0.41, 0.93)
export const PICKED_LIGHT = '#ff8a80'
export const PICKED_DEEP = '#e53935'

export const NUMBERED_SIZE = { w: 38, h: 50, tipX: 19, tipY: 45, headY: 17, radius: 14, badge: 10 } as const
export const HAETAE_SIZE = { w: 40, h: 52 } as const

const FONT = "-apple-system,BlinkMacSystemFont,'SF Pro Text','Apple SD Gothic Neo',sans-serif"

function dropPath(cx: number, headY: number, r: number, tipY: number): string {
  const d = r * Math.SQRT1_2
  return `M${(cx - d).toFixed(2)} ${(headY + d).toFixed(2)} A${r} ${r} 0 1 1 ${(cx + d).toFixed(2)} ${(headY + d).toFixed(2)} L${cx} ${tipY} Z`
}

const gradient = (id: string, x: number, tipY: number, light: string, deep: string) =>
  `<defs><linearGradient id="${id}" gradientUnits="userSpaceOnUse" x1="${x}" y1="0" x2="${x}" y2="${tipY}"><stop offset="0" stop-color="${light}"/><stop offset="1" stop-color="${deep}"/></linearGradient></defs>`

const SHADOW = 'overflow:visible;filter:drop-shadow(0 2px 1.75px rgba(0,0,0,0.28))'

/** 번호 핀. number 가 null 이면 흰 배지와 숫자 없는 민 핀. */
export function numberedPinSvg(number: number | null): string {
  const { w, h, tipX, tipY, headY, radius, badge } = NUMBERED_SIZE
  const id = `pn${number ?? 'x'}`
  const label =
    number === null
      ? ''
      : `<circle cx="${tipX}" cy="${headY}" r="${badge}" fill="#fff"/><text x="${tipX}" y="${headY}" text-anchor="middle" dominant-baseline="central" fill="${PIN_DEEP}" font-weight="800" font-size="${number < 100 ? 11.5 : 9}" font-family="${FONT}">${number}</text>`
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${w}" height="${h}" viewBox="0 0 ${w} ${h}" style="${SHADOW}">${gradient(id, tipX, tipY, PIN_LIGHT, PIN_DEEP)}<path d="${dropPath(tipX, headY, radius, tipY)}" fill="url(#${id})" stroke="#fff" stroke-width="1.5" stroke-linejoin="round"/>${label}</svg>`
}

/** 해태 핀. picked 는 빨강에 1.1배. */
export function haetaePinSvg(picked: boolean): string {
  const scale = picked ? 1.1 : 1
  const w = HAETAE_SIZE.w
  const h = HAETAE_SIZE.h
  const [light, deep] = picked ? [PICKED_LIGHT, PICKED_DEEP] : [PIN_LIGHT, PIN_DEEP]
  const id = picked ? 'ph1' : 'ph0'
  const cx = w / 2
  return (
    `<svg xmlns="http://www.w3.org/2000/svg" width="${w * scale}" height="${h * scale}" viewBox="0 0 ${w} ${h}" style="${SHADOW}">` +
    gradient(id, cx, h - 4, light, deep) +
    `<path d="${dropPath(cx, 19, 17, h - 4)}" fill="url(#${id})" stroke="#fff" stroke-width="1.5" stroke-linejoin="round"/>` +
    `<circle cx="${cx}" cy="19" r="12.5" fill="#fff"/>` +
    `<image href="/generated/haetae-face.png" x="${cx - 11}" y="10" width="22" height="19" preserveAspectRatio="xMidYMid meet"/>` +
    `</svg>`
  )
}

/** 다녀온 곳의 발바닥 도장 — 그라데이션 원 30 에 흰 발바닥. */
export function pawPinSvg(): string {
  return (
    `<svg xmlns="http://www.w3.org/2000/svg" width="30" height="30" viewBox="0 0 30 30" style="overflow:visible;filter:drop-shadow(0 1px 2px rgba(122,105,237,0.3))">` +
    `<defs><linearGradient id="pp" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="${PIN_LIGHT}"/><stop offset="1" stop-color="${PIN_DEEP}"/></linearGradient></defs>` +
    `<circle cx="15" cy="15" r="15" fill="url(#pp)"/>` +
    `<g fill="#fff" transform="rotate(-12 15 15)"><ellipse cx="15" cy="19.2" rx="4.6" ry="3.8"/><ellipse cx="9.2" cy="14" rx="1.9" ry="2.5"/><ellipse cx="13" cy="10.6" rx="1.9" ry="2.6"/><ellipse cx="17.6" cy="10.6" rx="1.9" ry="2.6"/><ellipse cx="21.2" cy="14" rx="1.9" ry="2.5"/></g>` +
    `</svg>`
  )
}
