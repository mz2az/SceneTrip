/*
 * 서버가 준 외부 페이지·사진 주소를 열어도 되는 것으로 추린다.
 * 출처: apps/scenetrip-ios/src/Models/WebAddress.swift, RouteTab/NaverMapLink.swift, SearchTab/ProducerLink.swift
 */

/** 공통 HTTP(S) 경계 — 공백·잘못된 % 인코딩·계정 정보가 든 주소는 버린다. 통과하면 정규화한 주소. */
export function webUrl(raw: string | null | undefined): string | null {
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
  return url.toString()
}

const HOST_CHARS = /^[a-z0-9.-]+$/

function isNaverHost(host: string): boolean {
  return HOST_CHARS.test(host) && (host === 'naver.com' || host.endsWith('.naver.com') || host === 'naver.me')
}

/**
 * 「네이버 지도에서 보기」 — 서버가 상세에 실어 주는 `naverPlaceUrl` 만 연다. 값이 없으면 링크도 버튼도 없다.
 * https · 포트 없음 · 호스트가 네이버(naver.com·그 아래·naver.me)일 때만. 이름으로 검색한 화면으로는 넘기지 않는다.
 */
export function naverPlaceUrl(raw: string | null | undefined): string | null {
  const text = raw?.trim()
  if (!text) return null
  // 퍼센트 인코딩을 풀기 전 글자를 본다 — `evil.io%2F.naver.com` 은 풀면 `.naver.com` 으로 끝난다.
  const m = /^https:\/\/([^/?#]*)/i.exec(text)
  if (!m) return null
  const authority = m[1].toLowerCase()
  if (authority.includes('@') || authority.includes(':')) return null // 계정 정보·포트
  if (!isNaverHost(authority)) return null
  return webUrl(text)
}

/** 작품 제작사 페이지 — 상세 응답이 정본이며 없는 주소를 추정하지 않는다(MZ2AZ-392). */
export function producerDestination(
  summary: { id: number; producerUrl?: string | null },
  detail: { id: number; producerUrl?: string | null } | null,
): string | null {
  if (detail && detail.id === summary.id) return webUrl(detail.producerUrl)
  return webUrl(summary.producerUrl)
}
