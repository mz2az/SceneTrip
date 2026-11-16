/*
 * 홈 화면 추가 단추와 권유 띠 — 정본: docs/project/plans/web-home-install.md §1·§2·§4 (MZ2AZ-589).
 *
 *   AddToHomeButton()   홈 머리줄 동그라미 단추. 홈 화면 앱(installed)으로 열었으면 없다.
 *   AddToHomeNudge()    아래 띠 「다음엔 홈 화면에서 바로 열어요」 + 「추가」 + ✕. RootTabs 에 한 번 붙는다.
 *
 * 누르면 환경(web-keep-flow.md §2-1)마다 — 설치 창 / 삼성 인터넷은 크롬(Play Protect 회피, MZ2AZ-588) / 앱 안 브라우저는 바깥 브라우저 /
 * 그 밖은 메뉴 안내 시트. 넘기지 못하면(1.5초 뒤에도 화면이 보이면) 그 환경의 메뉴 안내 시트.
 * 이벤트 add_to_home(env, action, where, trigger?) — 단추는 where=header, 띠는 where=nudge + trigger.
 */
import { type JSX, useEffect, useRef, useState } from 'react'
import { handoffParams, track, webEvent } from '@/analytics'
import type { AddToHomeWhere } from '@/analytics/events'
import { Icon } from '@/design/Icon'
import { Sheet } from '@/design/overlay'
import { HStack, Txt, VStack, shadow } from '@/design/primitives'
import { PrimaryButton } from '@/features/proto/protoParts'
import { heading, radius } from '@/features/proto/protoTokens'
import { Pressable } from '@/features/route/ui/Press'
import { say, useLang } from '@/i18n'
import { cartStore } from '@/state/cart'
import { handoff } from './handoff'
import { installNudge, useInstallNudge } from './installNudge'
import type { NudgeTrigger } from './installNudgeRules'
import { currentKeepEnv, promptInstall } from './installPrompt'
import { type KeepEnv, handoffHref, handoffKeepUrl } from './keepRules'

/** 바깥 브라우저로 넘긴 뒤 이만큼 지나도 화면이 보이면 넘어가지 못한 것으로 본다(간직하기와 같은 규칙). */
export const ADD_TO_HOME_HANDOFF_CHECK_MS = 1500

/** 안내 시트를 띄울 환경(설치 창, 홈 화면 앱은 안내가 없다). */
type GuideEnv = Exclude<KeepEnv, 'installable' | 'installed'>

/** 단추와 띠가 같이 쓰는 누름 흐름. 안내 시트는 띠가 내려간 뒤에도 남아야 해서 이 훅을 쓰는 쪽이 그린다. */
function useAddToHome(where: AddToHomeWhere) {
  const [guide, setGuide] = useState<GuideEnv | null>(null)
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null)
  useEffect(() => () => {
    if (timer.current) clearTimeout(timer.current)
  }, [])

  const showGuide = (env: KeepEnv, guideEnv: GuideEnv, trigger?: NudgeTrigger) => {
    track(webEvent.addToHome(env, 'guide', where, trigger))
    setGuide(guideEnv)
  }

  const press = async (trigger?: NudgeTrigger) => {
    const env = currentKeepEnv()
    track(webEvent.addToHome(env, 'tap', where, trigger))
    if (env === 'installed') return
    if (env === 'installable') {
      const outcome = await promptInstall()
      if (outcome === 'unavailable') showGuide(env, 'android_menu', trigger)
      else track(webEvent.addToHome(env, outcome === 'accepted' ? 'install_accepted' : 'install_dismissed', where, trigger))
      return
    }
    if (env === 'samsung_android' || env === 'in_app_android' || env === 'in_app_ios') {
      const href = handoffHref(env, handoffKeepUrl(window.location.origin, [], cartStore.get(), handoffParams()))
      if (!href) return
      track(webEvent.addToHome(env, 'open_browser', where, trigger))
      handoff.open(href)
      if (timer.current) clearTimeout(timer.current)
      timer.current = setTimeout(() => {
        if (document.visibilityState === 'visible') showGuide(env, env, trigger)
      }, ADD_TO_HOME_HANDOFF_CHECK_MS)
      return
    }
    showGuide(env, env, trigger)
  }

  const gotIt = (trigger?: NudgeTrigger) => {
    track(webEvent.addToHome(currentKeepEnv(), 'got_it', where, trigger))
    setGuide(null)
  }

  return { guide, press, gotIt, closeGuide: () => setGuide(null) }
}

const HOME_ADD = () => say('홈 화면에 추가', 'Add to Home Screen')

export function AddToHomeButton(): JSX.Element | null {
  useLang()
  const [env] = useState<KeepEnv>(currentKeepEnv)
  const { guide, press, gotIt, closeGuide } = useAddToHome('header')
  if (env === 'installed') return null
  return (
    <>
      <Pressable
        plain
        onClick={() => void press()}
        aria-label={HOME_ADD()}
        style={{ width: 36, height: 36, borderRadius: '50%', background: 'var(--system-background)', display: 'grid', placeItems: 'center', boxShadow: shadow('rgba(0,0,0,0.08)', 1.5, 1), flex: 'none' }}
      >
        <Icon name="arrow.down.to.line" size={17} weight="medium" />
      </Pressable>
      <GuideSheet env={guide} onGotIt={() => gotIt()} onClose={closeGuide} />
    </>
  )
}

export function AddToHomeNudge(): JSX.Element | null {
  useLang()
  const trigger = useInstallNudge()
  // 띠가 내려간 뒤 안내 시트가 뜰 수 있다 — 그때 쓸 이유를 붙잡아 둔다.
  const [lastTrigger, setLastTrigger] = useState<NudgeTrigger | null>(trigger)
  const { guide, press, gotIt, closeGuide } = useAddToHome('nudge')
  const viewed = useRef<NudgeTrigger | null>(null)

  useEffect(() => {
    if (trigger === null) {
      viewed.current = null
      return
    }
    setLastTrigger(trigger)
    if (viewed.current === trigger) return
    viewed.current = trigger
    track(webEvent.addToHome(currentKeepEnv(), 'view', 'nudge', trigger))
  }, [trigger])

  if (trigger === null && guide === null) return null

  const close = () => {
    if (trigger) track(webEvent.addToHome(currentKeepEnv(), 'dismiss', 'nudge', trigger))
    installNudge.dismiss()
  }
  const add = () => {
    const t = trigger ?? undefined
    installNudge.accept()
    void press(t)
  }

  return (
    <>
      {trigger !== null && (
        <div
          role="status"
          style={{
            position: 'absolute', left: 12, right: 12, bottom: 'calc(var(--tabbar-height) + var(--safe-bottom) + 10px)', zIndex: 40,
            display: 'flex', alignItems: 'center', gap: 10, padding: '10px 10px 10px 14px', borderRadius: radius.l,
            background: 'var(--system-background)', boxShadow: shadow('rgba(0,0,0,0.18)', 12, 4),
          }}
        >
          <img src="/generated/haetae-face.png" alt="" width={30} height={26} style={{ objectFit: 'contain', flex: 'none' }} draggable={false} />
          <Txt size={14} weight="semibold" style={{ flex: 1, minWidth: 0 }}>{say('다음엔 홈 화면에서 바로 열어요', 'Open SceneTrip from your Home Screen next time')}</Txt>
          <Pressable plain onClick={add} style={{ flex: 'none', padding: '8px 14px', borderRadius: 999, background: 'var(--home-purple, #5b3fd6)' }}>
            <Txt size={14} weight="bold" style={{ color: '#fff' }}>{say('추가', 'Add')}</Txt>
          </Pressable>
          <Pressable plain onClick={close} aria-label={say('닫기', 'Close')} style={{ flex: 'none', width: 32, height: 32, display: 'grid', placeItems: 'center' }}>
            <Icon name="xmark" size={14} weight="bold" color="secondary" />
          </Pressable>
        </div>
      )}
      <GuideSheet env={guide} onGotIt={() => gotIt(lastTrigger ?? undefined)} onClose={closeGuide} />
    </>
  )
}

/** 메뉴 안내 두 줄 — 간직하기(KeepSheet, KeepBeat)와 같은 말. */
function guideLines(env: GuideEnv): [string, string] {
  switch (env) {
    case 'android_menu':
      return [say('1. 오른쪽 위 ⋮ 를 누르세요', '1. Tap ⋮ at the top right'), say('2. 「홈 화면에 추가」 를 고르세요', '2. Choose “Add to Home screen”')]
    case 'ios_safari':
      return [say('1. 아래쪽 공유 단추를 누르세요', '1. Tap the Share button below'), say('2. 「홈 화면에 추가」 를 고르세요', '2. Choose “Add to Home Screen”')]
    case 'samsung_android':
      return [say('1. 아래 ≡ 를 누르세요', '1. Tap ≡ at the bottom'), say('2. 「현재 페이지 추가」 에서 「북마크」 를 고르세요', '2. Choose “Add page to”, then “Bookmarks”')]
    case 'in_app_android':
      return [say('1. 오른쪽 위 ⋮ 를 누르세요', '1. Tap ⋮ at the top right'), say('2. 「다른 브라우저로 열기」 를 고르세요', '2. Choose “Open in external browser”')]
    case 'in_app_ios':
      return [say('1. 오른쪽 위 ⋯ 를 누르세요', '1. Tap ⋯ at the top right'), say('2. 「외부 브라우저에서 열기」 를 고르세요', '2. Choose “Open in external browser”')]
    default:
      return [say('1. 브라우저 메뉴를 여세요', '1. Open your browser menu'), say('2. 「즐겨찾기 추가」 를 고르세요', '2. Choose “Add bookmark”')]
  }
}

function GuideSheet({ env, onGotIt, onClose }: { env: GuideEnv | null; onGotIt: () => void; onClose: () => void }) {
  const title = say('홈 화면에 SceneTrip 을 추가하세요', 'Add SceneTrip to your Home Screen')
  return (
    <Sheet open={env !== null} onClose={onClose} floating detent={300} radius={30} label={title}>
      {env !== null && (
        <VStack spacing={16} align="stretch" style={{ padding: '22px 16px', background: 'var(--grouped-background)', height: '100%' }}>
          <Txt as="h2" size={20} weight="heavy" lines={3} style={{ ...heading, padding: '0 4px' }}>{title}</Txt>
          <VStack spacing={4} align="start" style={{ padding: '10px 12px', borderRadius: radius.s, background: 'var(--system-background)', alignSelf: 'stretch' }}>
            {guideLines(env).map((line, i) => (
              <HStack key={line} spacing={6}>
                <Txt size={14}>{line}</Txt>
                {env === 'ios_safari' && i === 0 && <Icon name="square.and.arrow.up" size={14} color="accent" />}
              </HStack>
            ))}
          </VStack>
          <PrimaryButton onClick={onGotIt}>{say('알겠어요', 'Got it')}</PrimaryButton>
        </VStack>
      )}
    </Sheet>
  )
}
