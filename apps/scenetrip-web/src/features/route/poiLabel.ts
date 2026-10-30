/*
 * 편의시설을 화면에 뭐라고 적을지 — iOS RouteTab/PoiLabel.swift (MZ2AZ-360).
 * 편의시설의 name·address·category 는 언제나 한국어 원본이다. 앱 언어의 값은 서버가 새 칸으로 준다
 * (displayName·displayAddress·categoryLabel·nameRoman). 제목은 `displayName ?? name`, 제목 아래 한 줄은
 * 영어 이름이 있으면 한국어 이름(간판과 맞춰 본다), 없으면 로마자 읽기다.
 * (「가이드·길찾기·POI」 담당이 같은 규칙을 poi/ 에 둘 수 있다 — 겹침은 나중에 합친다.)
 */
export interface PoiLabel {
  title: string
  reading: string | null
  roman: string | null
  category: string | null
  address: string | null
}

const filled = (text: string | null | undefined): string | null => {
  const t = text?.trim()
  return t ? t : null
}

export interface PoiLabelInput {
  name: string
  displayName?: string | null
  nameRoman?: string | null
  category?: string | null
  categoryLabel?: string | null
  address?: string | null
  displayAddress?: string | null
  korean: boolean
}

export function poiLabel(i: PoiLabelInput): PoiLabel {
  // 한국어 화면은 언제나 원본이다.
  if (i.korean) return { title: i.name, reading: null, roman: null, category: i.category ?? null, address: i.address ?? null }
  const shown = filled(i.displayName)
  const original = i.name.trim()
  let roman: string | null = null
  // 이름이 원래 로마자면(「GS25」) 읽는 법이 같은 글자다 — 두 번 적지 않는다.
  const reading = filled(i.nameRoman)
  if (shown === null && reading !== null && reading.toLowerCase() !== original.toLowerCase()) roman = reading
  // 영어 이름이 제목이면 그 아래는 한국어 이름이다(같은 글자면 생략).
  let korean: string | null = null
  if (shown !== null && shown.toLowerCase() !== original.toLowerCase()) korean = i.name
  return {
    title: shown ?? i.name,
    reading: korean ?? roman,
    roman,
    category: filled(i.categoryLabel) ?? i.category ?? null,
    address: filled(i.displayAddress) ?? i.address ?? null,
  }
}
