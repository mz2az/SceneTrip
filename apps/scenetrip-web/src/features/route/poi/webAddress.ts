/*
 * 서버가 준 외부 페이지 주소의 공통 HTTP(S) 경계 — iOS Models/WebAddress.swift, NaverMapLink.place.
 * 앱 밖(브라우저·다른 앱)으로 넘기는 값이라 한 번 더 본다.
 */

/** http(s) 이고 호스트가 있고 공백·깨진 퍼센트 인코딩·사용자 정보가 없을 때만 주소를 돌려준다. */
export function webAddress(raw: string | null | undefined): string | null {
  if (raw == null) return null
  const value = raw.trim()
  if (!value || /\s/.test(value) || /%(?![0-9A-Fa-f]{2})/.test(value)) return null
  let url: URL
  try {
    url = new URL(value)
  } catch {
    return null
  }
  if (url.protocol !== 'http:' && url.protocol !== 'https:') return null
  if (!url.hostname || url.username || url.password) return null
  return value
}

const HOST_CHARACTERS = /^[a-z0-9.-]+$/

function isNaverHost(host: string): boolean {
  return HOST_CHARACTERS.test(host) && (host === 'naver.com' || host.endsWith('.naver.com') || host === 'naver.me')
}

/**
 * 「네이버 지도에서 보기」가 갈 곳 (MZ2AZ-374·383) — 서버가 상세에 실어 주는 `naverPlaceUrl`(그 장소의 네이버
 * 장소 화면)만 연다. https 이고 포트가 없고 호스트가 네이버(`naver.com`·그 아래·`naver.me`)일 때만. 아니면 null —
 * 버튼이 서지 않는다. 이름 검색 화면으로는 넘기지 않는다(정권호 결정 2026-10-09).
 */
export function naverPlaceLink(raw: string | null | undefined): string | null {
  const text = raw?.trim()
  if (!text) return null
  // 호스트는 퍼센트 인코딩을 풀기 전 글자로 본다 — `evil.io%2F.naver.com` 은 풀면 `.naver.com` 으로 끝난다.
  const m = /^https:\/\/([^/?#]*)/i.exec(text)
  if (!m) return null
  const authority = m[1]
  if (authority.includes('@') || authority.includes(':')) return null
  if (!isNaverHost(authority.toLowerCase())) return null
  return webAddress(text)
}
