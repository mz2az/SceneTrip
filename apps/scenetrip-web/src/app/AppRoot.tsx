/*
 * 앱을 열었을 때의 순서를 정하는 자리. (iOS: Onboarding/AppRoot.swift)
 *
 *   스플래시 ─┬─ (처음이면) 언어 고르기 ─ 사용법 넉 장 ─┐
 *             └─────────────────────────────────────────┴─ 앱
 *
 * 진짜 앱은 처음부터 아래에 깔려 있고 스플래시를 덮개로 얹는다 — 스플래시가 도는 동안 첫 화면의 조회가 끝난다.
 * 덮개는 들어올 때는 애니메이션 없이 꽉 차 있고, 걷힐 때만 0.32초 서서히 사라진다(아래 흰 화면이 비치지 않게).
 */
import { type ReactNode, useEffect, useState } from 'react'
import { LanguagePickView } from '@/features/onboarding/LanguagePickView'
import { OnboardingView, hasSeenOnboarding } from '@/features/onboarding/OnboardingView'
import { SplashView } from '@/features/onboarding/SplashView'
import { skipSplash } from '@/devFlags'
import { hasChosenLanguage, useLang } from '@/i18n'
import { RootTabs } from './RootTabs'

type Stage = 'splash' | 'language' | 'lessons' | 'app'

const FADE_MS = 320

/** `show` 가 꺼져도 FADE_MS 동안 남아 서서히 사라진다. */
function FadeOut({ show, children }: { show: boolean; children: ReactNode }) {
  const [mounted, setMounted] = useState(show)
  useEffect(() => {
    if (show) {
      setMounted(true)
      return
    }
    const t = setTimeout(() => setMounted(false), FADE_MS)
    return () => clearTimeout(t)
  }, [show])
  if (!mounted && !show) return null
  return (
    <div style={{ position: 'absolute', inset: 0, zIndex: 100, opacity: show ? 1 : 0, transition: `opacity ${FADE_MS}ms ease-in-out`, pointerEvents: show ? 'auto' : 'none' }}>
      {children}
    </div>
  )
}

export function AppRoot() {
  const lang = useLang()
  const afterLanguage = (): Stage => (hasSeenOnboarding() ? 'app' : 'lessons')
  const afterSplash = (): Stage => (hasChosenLanguage() ? afterLanguage() : 'language')
  const [stage, setStage] = useState<Stage>(() => (skipSplash ? afterSplash() : 'splash'))

  return (
    <>
      {/* 언어가 바뀌면 화면을 통째로 다시 만든다 — 서버 내용도 그 언어로 다시 받아야 한다. */}
      <RootTabs key={lang} />
      <FadeOut show={stage === 'splash'}>
        <SplashView onDone={() => setStage(afterSplash())} />
      </FadeOut>
      <FadeOut show={stage === 'language'}>
        <LanguagePickView onDone={() => setStage(afterLanguage())} />
      </FadeOut>
      <FadeOut show={stage === 'lessons'}>
        <OnboardingView onDone={() => setStage('app')} />
      </FadeOut>
    </>
  )
}
