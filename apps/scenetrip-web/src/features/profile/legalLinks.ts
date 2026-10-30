/*
 * 개인정보 처리방침·이용약관 주소 — iOS `LegalLinks` (Models/LegalLinks.swift, MZ2AZ-455, App Store 5.1.1).
 *
 * **주소는 여기 한 곳에만 둔다** — 게시 주소가 바뀌면 이 줄들만 고친다. 마이페이지·로그인 시트·동의 시트가 이 값을 읽는다.
 * 2026-10-15(iOS 기준) 확인: `privacy` 는 초안이 게시돼 200, 영어판 주소는 없다(한국어 한 장). `terms` 는 게시 전이라 404 다 — 링크는 숨기지 않는다.
 */
export const LEGAL_LINKS = {
  privacy: 'https://scenetrip.io/privacy',
  locationServices: 'https://scenetrip.io/terms#location-services',
  terms: 'https://scenetrip.io/terms',
  /** 문의 메일(MZ2AZ-461, App Store 1.2 — 연락처 공개). 마이페이지·스토어 설명·처리방침이 같은 주소를 쓴다. */
  supportEmail: 'support@scenetrip.io',
} as const

export const SUPPORT_MAIL_URL = `mailto:${LEGAL_LINKS.supportEmail}`
