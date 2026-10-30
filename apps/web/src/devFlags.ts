/*
 * 개발 편의 — 스크린샷/검증을 URL 만으로 재현하게 하는 뒷문. 개발 서버(import.meta.env.DEV)에서만 동작하고
 * 제품 빌드에는 들어가지 않는다 (iOS 의 `simctl launch … -initialTab` 에 해당).
 *
 *   ?dev-lang=ko|en   앱 언어를 정해 둔다 (고른 것으로 친다)
 *   ?dev-seen=1       사용법을 본 것으로 친다
 *   ?dev-nosplash=1   스플래시를 건너뛴다
 *   ?dev-account=1 · ?dev-stamps=1   계정·방문 기록이 있는 읽기 전용 상태 (devMock.ts)
 */
import { chooseLanguage } from './i18n'
import { installDevFakeGuide } from './features/route/guide/devFake'
import { installDevMock } from './devMock'
import { writeStorage } from './state/storage'

export let skipSplash = false

export function applyDevFlags(): void {
  if (!import.meta.env.DEV) return
  const q = new URLSearchParams(window.location.search)
  const lang = q.get('dev-lang')
  if (lang === 'ko' || lang === 'en') chooseLanguage(lang)
  if (q.get('dev-seen') === '1') writeStorage('scenetrip.onboarding.seenVersion', '1')
  skipSplash = q.get('dev-nosplash') === '1'
  installDevMock(q)
  installDevFakeGuide()
}
