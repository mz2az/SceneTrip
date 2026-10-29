/*
 * 앱을 열었을 때의 순서를 정하는 자리. (iOS: Onboarding/AppRoot.swift)
 *
 *   스플래시 ─┬─ (처음이면) 언어 고르기 ─ 사용법 넉 장 ─┐
 *             └─────────────────────────────────────────┴─ 앱
 *
 * 진짜 앱은 처음부터 아래에 깔려 있고 스플래시를 덮개로 얹는다 — 스플래시가 도는 동안 첫 화면의 조회가 끝난다.
 */
import { useState } from 'react'
import { LanguagePickView } from '@/features/onboarding/LanguagePickView'
import { OnboardingView, hasSeenOnboarding } from '@/features/onboarding/OnboardingView'
import { SplashView } from '@/features/onboarding/SplashView'
import { skipSplash } from '@/devFlags'
import { hasChosenLanguage, useLang } from '@/i18n'
import { RootTabs } from './RootTabs'

type Stage = 'splash' | 'language' | 'lessons' | 'app'

export function AppRoot() {
  const lang = useLang()
  const [stage, setStage] = useState<Stage>(() => (skipSplash ? 'app' : 'splash'))
  const afterLanguage: Stage = hasSeenOnboarding() ? 'app' : 'lessons'

  return (
    <>
      {/* 언어가 바뀌면 화면을 통째로 다시 만든다 — 서버 내용도 그 언어로 다시 받아야 한다. */}
      <RootTabs key={lang} />
      {stage === 'splash' && <SplashView onDone={() => setStage(hasChosenLanguage() ? afterLanguage : 'language')} />}
      {stage === 'language' && <LanguagePickView onDone={() => setStage(afterLanguage)} />}
      {stage === 'lessons' && <OnboardingView onDone={() => setStage('app')} />}
    </>
  )
}
