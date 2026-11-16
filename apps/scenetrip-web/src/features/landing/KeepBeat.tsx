/*
 * 첫 화면의 ⑤ 「간직하기」 — 정본: docs/project/plans/web-ad-landing.md §13-1.
 *
 * ④ 에서 담기가 끝나면(실패 0) 덮개 안에서 곧바로 이 박자를 보인다. 광고로 온 사람의 대부분은 인스타·페이스북 앱 안 브라우저라
 * 창을 닫으면 담은 것이 사라진다 — 담은 바로 그 순간 해태가 주머니를 안고 「로그인해서 간직하기」 와 「브라우저에서 열기」 를 권한다.
 *
 *   앱 안 브라우저  주 로그인(이유 keep) · 보조 브라우저에서 열기(안드로이드 intent, 아이폰 x-safari- → 1.5초 뒤에도 그대로면 안내) · 나중에
 *   그 밖           주 로그인 · 보조 홈 화면에 추가(설치 창) 또는 즐겨찾기·홈 화면 안내 · 나중에
 *
 * 로그인 창이 닫히면(성공·취소 모두) `onDone` — 덮개가 걷히며 「더 담기」(/add). 「나중에」 도 같다.
 * 로그인 창(SignInSheet)은 밑의 앱(RootSheets)에 있어 `onNeedApp` 으로 앱을 먼저 붙이고 `promptSignIn(null, 'keep')` 으로 부탁한다
 * (RootSheets 가 `accountActions.openSignInFor(null, 'keep')`). 계정 모듈은 받지 않는다 — 첫 화면 덩어리를 가볍게.
 * 이미 로그인했거나 홈 화면에서 연 앱이면 이 박자는 아예 오지 않는다(LandingCover 가 가른다).
 */
import { MapArt } from './MapArt'
import { type JSX, useEffect, useRef, useState } from 'react'
import { handoffParams, track, webEvent } from '@/analytics'
import { Icon } from '@/design/Icon'
import { Txt } from '@/design/primitives'
import { handoff } from '@/features/keep/handoff'
import { currentKeepEnv, promptInstall } from '@/features/keep/installPrompt'
import { type KeepEnv, handoffHref, handoffKeepUrl } from '@/features/keep/keepRules'
import { Pressable } from '@/features/route/ui/Press'
import { say, useLang } from '@/i18n'
import { promptSignIn, signInSheetOpenStore } from '@/state/auth'
import { cartStore } from '@/state/cart'
import { landingArt } from './art'

/** 바깥 브라우저로 넘긴 뒤 이만큼 지나도 화면이 보이면 넘어가지 못한 것으로 본다(담은 뒤 시트와 같은 규칙). */
export const KEEP_HANDOFF_CHECK_MS = 1500

const isInApp = (env: KeepEnv) => env === 'in_app_android' || env === 'in_app_ios'

export function KeepBeat({ count, contentId, onDone, onNeedApp }: {
  count: number
  contentId: number
  /** 덮개를 걷고 「더 담기」 로. */
  onDone: () => void
  /** 로그인 창을 띄우기 전에 밑의 앱(로그인 창이 사는 곳)을 붙여 달라. */
  onNeedApp?: () => void
}): JSX.Element {
  useLang()
  // 이 박자에 들어온 순간의 환경으로 고정한다 — 설치 창을 쓰고 나면 `installable` 이 아니게 되어 화면이 바뀌면 안 된다.
  const [env] = useState<KeepEnv>(currentKeepEnv)
  const inApp = isInApp(env)
  /** 바깥 브라우저로 넘기지 못했다 — 안드로이드는 한 줄, 아이폰은 해태가 오른쪽 위를 가리키는 그림 + 두 줄. */
  const [handoffStuck, setHandoffStuck] = useState(false)
  /** 앱 안 브라우저가 아닐 때 보조 단추로 연 메뉴 안내. */
  const [guide, setGuide] = useState(false)
  const [busy, setBusy] = useState(false)
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const unsubscribe = useRef<(() => void) | null>(null)
  /** 로그인 창을 부탁했고 닫히기를 기다리는 중. */
  const waitingSignIn = useRef(false)
  const done = useRef(false)
  const doneRef = useRef(onDone)
  useEffect(() => {
    doneRef.current = onDone
  }, [onDone])

  const finish = () => {
    if (done.current) return
    done.current = true
    doneRef.current()
  }

  const viewed = useRef(false)
  useEffect(() => {
    if (viewed.current) return
    viewed.current = true
    track(webEvent.keepPrompt(env, 'view', 'intro'))
  }, [env])
  useEffect(
    () => () => {
      if (timer.current) clearTimeout(timer.current)
      unsubscribe.current?.()
    },
    [],
  )

  const later = () => {
    track(webEvent.keepPrompt(env, 'skip', 'intro'))
    finish()
  }

  const signIn = () => {
    if (waitingSignIn.current) return
    waitingSignIn.current = true
    track(webEvent.keepPrompt(env, 'sign_in', 'intro'))
    // 로그인 창은 밑의 앱(RootSheets)이 그린다 — 앱을 붙이고, 기존 요청 길(promptSignIn → RootSheets → openSignInFor(null, 'keep'))로 부탁한다.
    // 계정 모듈은 받지 않는다(첫 화면 덩어리를 가볍게) — 창이 떴다 닫히는 것은 state/auth 의 비춤(signInSheetOpenStore)으로 본다.
    onNeedApp?.()
    let opened = signInSheetOpenStore.get()
    unsubscribe.current?.()
    unsubscribe.current = signInSheetOpenStore.subscribe(() => {
      const open = signInSheetOpenStore.get()
      if (open) {
        opened = true
        return
      }
      if (!opened) return
      // 로그인 창이 닫혔다(성공·취소 모두) — 「더 담기」 로.
      unsubscribe.current?.()
      unsubscribe.current = null
      finish()
    })
    promptSignIn(null, 'keep')
  }

  const openBrowser = () => {
    const href = handoffHref(env, handoffKeepUrl(window.location.origin, [contentId], cartStore.get(), handoffParams()))
    if (!href) return
    track(webEvent.keepPrompt(env, 'open_browser', 'intro'))
    handoff.open(href)
    if (timer.current) clearTimeout(timer.current)
    timer.current = setTimeout(() => {
      if (document.visibilityState === 'visible') {
        track(webEvent.keepPrompt(env, 'open_browser_stuck', 'intro'))
        setHandoffStuck(true)
      }
    }, KEEP_HANDOFF_CHECK_MS)
  }

  const install = async () => {
    if (busy) return
    setBusy(true)
    track(webEvent.keepPrompt(env, 'install', 'intro'))
    const outcome = await promptInstall()
    // 설치 창을 띄우지 못했으면 거절로 세지 않는다.
    if (outcome !== 'unavailable') track(webEvent.keepPrompt(env, outcome === 'accepted' ? 'install_accepted' : 'install_dismissed', 'intro'))
    setBusy(false)
    finish()
  }

  // 즐겨찾기, 홈 화면 추가 안내 단추 — 누른 것을 센다. 안내만 보고 「알겠어요」 없이 메뉴로 간 사람도 남게(MZ2AZ-585).
  const openGuide = () => {
    track(webEvent.keepPrompt(env, env === 'other' ? 'bookmark' : 'home_screen_guide', 'intro'))
    setGuide(true)
  }

  const gotIt = () => {
    track(webEvent.keepPrompt(env, 'got_it', 'intro'))
    finish()
  }

  // 이미 10곳을 담은 사람에게 겁을 주지 않는다 — 다음 할 일(코스 짜기, 길 안내)로 이끈다(2026-11-16 사용자 결정). 브라우저와 상관없이 한 문장.
  const line = say('이 지도로 코스를 짜 봐요\n길은 내가 안내해 줄게요!', "Plan a route with this map.\nI'll guide you there!")
  const pointing = handoffStuck && env === 'in_app_ios'

  return (
    <>
      {pointing ? (
        <PointGuide />
      ) : (
        <div className="st-keep__hero">
          <div className="st-film__bubble st-keep__bubble" role="status">
            <Txt as="h1" size={18} weight="heavy" style={{ margin: 0, color: '#2a1856', lineHeight: 1.25, whiteSpace: 'pre-line' }}>{line}</Txt>
          </div>
          <div className="st-keep__hug" aria-hidden>
            <img className="st-keep__haetae" src={landingArt.joy} alt="" draggable={false} />
            <div className="st-film__bag st-keep__bag">
              <MapArt bite />
              <span className="st-film__bag-count">{count}</span>
            </div>
            {/* 지도를 문 모양 — 같은 그림에서 윗입술과 송곳니만 잘라 지도 위에 다시 얹는다. */}
            <img className="st-keep__haetae st-keep__jaw" src={landingArt.joy} alt="" draggable={false} />
          </div>
        </div>
      )}

      <div className="st-landing__panel st-keep__panel" onClick={(e) => e.stopPropagation()}>
        {handoffStuck && env === 'in_app_android' && (
          <div role="status" className="st-keep__hint">
            <Txt size={13} weight="semibold" style={{ color: '#fff' }}>
              {say('안 열리면 오른쪽 위 ⋮ 를 눌러 「다른 브라우저로 열기」 를 고르세요', "Didn't open? Tap ⋮ at the top right, then “Open in external browser”.")}
            </Txt>
          </div>
        )}
        {guide && <GuideLines env={env} />}

        <Pressable plain className="proto-press st-landing__keep" disabled={busy} onClick={signIn}>
          <span style={{ display: 'inline-flex', alignItems: 'center', gap: 8, justifyContent: 'center' }}>
            <Icon name="person.fill" size={18} weight="bold" color="#2a1856" />
            <Txt size={17} weight="heavy" style={{ color: '#2a1856' }}>{say('로그인하고 코스 짜기', 'Sign in and plan my route')}</Txt>
          </span>
        </Pressable>

        {inApp ? (
          <Pressable plain className="proto-press st-keep__second" onClick={openBrowser}>
            <Txt size={16} weight="bold" style={{ color: '#fff' }}>{say('브라우저에서 열기', 'Open in browser')}</Txt>
          </Pressable>
        ) : env === 'installable' ? (
          <Pressable plain className="proto-press st-keep__second" disabled={busy} onClick={() => void install()}>
            <Txt size={16} weight="bold" style={{ color: '#fff' }}>{say('홈 화면에 추가', 'Add to Home Screen')}</Txt>
          </Pressable>
        ) : guide ? (
          <Pressable plain className="proto-press st-keep__second" onClick={gotIt}>
            <Txt size={16} weight="bold" style={{ color: '#fff' }}>{say('알겠어요', 'Got it')}</Txt>
          </Pressable>
        ) : (
          <Pressable plain className="proto-press st-keep__second" onClick={openGuide}>
            <Txt size={16} weight="bold" style={{ color: '#fff' }}>
              {env === 'other' ? say('즐겨찾기에 추가하기', 'Bookmark this page') : say('홈 화면에 추가하기', 'Add to Home Screen')}
            </Txt>
          </Pressable>
        )}

        <Pressable plain onClick={later} className="st-landing__browse">
          <Txt size={15} weight="semibold" style={{ color: 'rgba(255,255,255,0.86)' }}>{say('나중에', 'Later')}</Txt>
        </Pressable>
      </div>
    </>
  )
}

/** 아이폰 앱 안 브라우저 — 바깥으로 넘기지 못했을 때. 해태가 오른쪽 위(⋯ 메뉴)를 가리키고 두 줄로 안내한다. */
function PointGuide() {
  return (
    <div className="st-keep__point" role="status">
      <svg className="st-keep__arrow" viewBox="0 0 120 110" aria-hidden>
        <path d="M14 104 C 30 52, 62 22, 104 12" fill="none" stroke="#fff" strokeWidth="5" strokeLinecap="round" strokeDasharray="2 11" />
        <path d="M88 4 L108 11 L95 27" fill="none" stroke="#fff" strokeWidth="5" strokeLinecap="round" strokeLinejoin="round" />
      </svg>
      <div className="st-keep__pointer" aria-hidden>
        <img className="st-keep__pointer-body" src={landingArt.sit} alt="" draggable={false} />
        <img className="st-keep__pointer-paw" src={landingArt.pawRight} alt="" draggable={false} />
      </div>
      <div className="st-keep__steps">
        <Txt size={17} weight="heavy" style={{ color: '#2a1856' }}>{say('1. 오른쪽 위 ⋯ 를 누르세요', '1. Tap ⋯ at the top right')}</Txt>
        <Txt size={17} weight="heavy" style={{ color: '#2a1856' }}>{say('2. 「외부 브라우저에서 열기」', '2. “Open in external browser”')}</Txt>
      </div>
    </div>
  )
}

/** 앱 안 브라우저가 아닐 때의 메뉴 안내 두 줄 — 담은 뒤 시트와 같은 말. */
function GuideLines({ env }: { env: KeepEnv }) {
  const lines =
    env === 'android_menu'
      ? [say('1. 오른쪽 위 ⋮ 를 누르세요', '1. Tap ⋮ at the top right'), say('2. 「홈 화면에 추가」 를 고르세요', '2. Choose “Add to Home screen”')]
      : env === 'ios_safari'
        ? [say('1. 아래쪽 공유 단추를 누르세요', '1. Tap the Share button below'), say('2. 「홈 화면에 추가」 를 고르세요', '2. Choose “Add to Home Screen”')]
        : [say('1. 브라우저 메뉴를 여세요', '1. Open your browser menu'), say('2. 「즐겨찾기 추가」 를 고르세요', '2. Choose “Add bookmark”')]
  return (
    <div role="status" className="st-keep__hint">
      {lines.map((l) => (
        <Txt key={l} size={14} weight="semibold" style={{ color: '#fff', display: 'block' }}>{l}</Txt>
      ))}
    </div>
  )
}
