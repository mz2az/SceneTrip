/*
 * 첫 실행의 언어 고르기. 사용법보다 먼저 나온다. 이 화면만은 두 언어를 같이 적는다 (verbatim).
 * 출처: apps/scenetrip-ios/src/Onboarding/LanguagePickView.swift
 */
import { appEvent, track } from '@/analytics'
import { PinoMascot } from '@/design/PinoMascot'
import { Pressable, Txt, VStack } from '@/design/primitives'
import { LANG_CHOICES, chooseLanguage, langName, useLang } from '@/i18n'

export function LanguagePickView({ onDone }: { onDone: () => void }) {
  const current = useLang()
  return (
    <VStack align="center" style={{ position: 'absolute', inset: 0, zIndex: 100, background: 'var(--system-background)' }}>
      <div style={{ flex: 1 }} />
      <PinoMascot width={150} />
      <Txt size={22} weight="bold" style={{ marginTop: 28 }}>언어를 선택하세요</Txt>
      <Txt size={20} color="secondary" style={{ marginTop: 4 }}>Choose your language</Txt>
      <div style={{ flex: 1 }} />
      <VStack spacing={12} fill pad="0 24px 40px">
        {LANG_CHOICES.map((lang) => (
          <Pressable
            key={lang}
            plain
            onClick={() => {
              chooseLanguage(lang)
              track(appEvent.selectLanguage(lang))
              onDone()
            }}
            style={{
              height: 54, borderRadius: 27, display: 'grid', placeItems: 'center', width: '100%',
              background: lang === current ? 'var(--accent)' : 'var(--system-gray6)',
            }}
          >
            <Txt size={17} weight="semibold" style={{ color: lang === current ? '#fff' : 'var(--label)' }}>{langName(lang)}</Txt>
          </Pressable>
        ))}
      </VStack>
    </VStack>
  )
}
