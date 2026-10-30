/*
 * 번호가 박힌 핀 이미지 — 파스텔 하늘→보라 그라데이션에 그림자, 번호는 머리의 흰 원 배지 안에 컬러로.
 * 출처: apps/scenetrip-ios/src/SearchTab/NaverMapView.swift (PinImage.numbered) — 크기·좌표·색을 그대로 옮겼다.
 *
 * 번호는 「목록의 N번 = 지도의 N번」 을 잇기 위한 것이다. 번호가 없으면 흰 배지와 숫자 없는 민 핀(첫 화면의 순위표 핀).
 */

/** 그라데이션 양끝 색. 목록의 번호 배지도 같은 색을 써서 핀과 짝이 맞는다(tokens.css 의 --pin-light/--pin-deep). */
export const PIN_LIGHT = '#8fccf7' // UIColor(0.56, 0.80, 0.97)
export const PIN_DEEP = '#7a69ed' // UIColor(0.48, 0.41, 0.93)

export const PIN_SIZE = { width: 38, height: 50 } as const
/** 머리 원의 중심·꼬리 끝(아이콘 안 좌표). 핀의 꼬리 끝이 좌표에 닿는다. */
export const PIN_HEAD = { x: 19, y: 17, radius: 14, badgeRadius: 10 } as const
export const PIN_TIP = { x: 19, y: 45 } as const

/** 세 자리 번호는 글자를 줄인다. */
export function pinNumberFontSize(n: number): number {
  return n < 100 ? 11.5 : 9
}

/** 핀 SVG 한 장. `uid` 는 같은 문서 안에서 그라데이션 id 가 겹치지 않게 하는 값(번호별로 하나면 충분하다). */
export function pinSvg(number: number | null): string {
  const { x, y, radius, badgeRadius } = PIN_HEAD
  // 머리 원의 아래쪽 좌우(135°→45°, 시계방향으로 위를 지나)에서 꼬리 끝점으로 이어 물방울 모양을 만든다.
  const dx = radius * Math.SQRT1_2
  const path = `M${(x - dx).toFixed(2)} ${(y + dx).toFixed(2)} A${radius} ${radius} 0 1 1 ${(x + dx).toFixed(2)} ${(y + dx).toFixed(2)} L${PIN_TIP.x} ${PIN_TIP.y} Z`
  const id = `pg${number ?? 'n'}`
  const badge =
    number === null
      ? ''
      : `<circle cx="${x}" cy="${y}" r="${badgeRadius}" fill="#fff"/>` +
        `<text x="${x}" y="${y}" text-anchor="middle" dominant-baseline="central" fill="${PIN_DEEP}" font-weight="800" font-size="${pinNumberFontSize(number)}" font-family="-apple-system,BlinkMacSystemFont,'SF Pro Text','Apple SD Gothic Neo',sans-serif">${number}</text>`
  return (
    `<svg xmlns="http://www.w3.org/2000/svg" width="${PIN_SIZE.width}" height="${PIN_SIZE.height}" viewBox="0 0 ${PIN_SIZE.width} ${PIN_SIZE.height}" style="overflow:visible;filter:drop-shadow(0 2px 1.75px rgba(0,0,0,0.28))">` +
    `<defs><linearGradient id="${id}" gradientUnits="userSpaceOnUse" x1="${x}" y1="0" x2="${x}" y2="${PIN_TIP.y}"><stop offset="0" stop-color="${PIN_LIGHT}"/><stop offset="1" stop-color="${PIN_DEEP}"/></linearGradient></defs>` +
    `<path d="${path}" fill="url(#${id})" stroke="#fff" stroke-width="1.5" stroke-linejoin="round"/>` +
    badge +
    `</svg>`
  )
}
