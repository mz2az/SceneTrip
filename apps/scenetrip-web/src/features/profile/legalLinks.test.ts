import { describe, expect, it } from 'vitest'
import { hasEnglish, tr } from '@/i18n'
import { LEGAL_LINKS, SUPPORT_MAIL_URL } from './legalLinks'

// iOS tests/LegalLinksTests.swift — 처리방침·약관 주소는 우리 도메인의 https 페이지다 (MZ2AZ-455).
describe('LegalLinks', () => {
  it('우리 도메인의 https 이고 사용자 정보·포트가 없다', () => {
    for (const raw of [LEGAL_LINKS.privacy, LEGAL_LINKS.terms, LEGAL_LINKS.locationServices]) {
      const url = new URL(raw)
      expect(url.protocol).toBe('https:')
      expect(url.hostname).toBe('scenetrip.io')
      expect(url.username).toBe('')
      expect(url.port).toBe('')
    }
  })

  it('링크마다 자기 페이지가 있다', () => {
    expect(new URL(LEGAL_LINKS.privacy).pathname).toBe('/privacy')
    expect(new URL(LEGAL_LINKS.terms).pathname).toBe('/terms')
    expect(new URL(LEGAL_LINKS.locationServices).pathname).toBe('/terms')
    expect(new URL(LEGAL_LINKS.locationServices).hash).toBe('#location-services')
  })

  it('문의 메일은 mailto 로 같은 주소를 가리킨다', () => {
    expect(SUPPORT_MAIL_URL).toBe('mailto:support@scenetrip.io')
  })

  it('안내 문구의 번역이 빠지면 영어 화면에 한국어가 섞인다 — 이 화면들의 열쇠가 영어 표에 있다', () => {
    const keys = [
      '개인정보 처리방침', '이용약관', '약관 및 정책', '계속하면 이용약관과 개인정보 처리방침에 동의하는 것으로 봅니다.',
      '위치기반서비스 이용 동의', '위치기반서비스 이용약관', '동의하고 계속', '위치기반서비스 이용',
      '끄면 위치를 쓰는 기능을 쓸 때 다시 물어봐요.', 'AI 기능 사용 안내', '허용하고 계속', 'AI 기능 사용',
      '끄면 AI 가이드와 AI 코스 만들기를 쓸 때 다시 물어봐요.',
    ]
    for (const key of keys) expect(hasEnglish(key), key).toBe(true)
    expect(tr('개인정보 처리방침')).toBeTruthy()
  })
})
