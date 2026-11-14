/*
 * 담은 뒤 시트(② keep → ③ plan) — 정본: docs/project/plans/web-keep-flow.md §5-2·§6, web-ad-landing.md §2.
 * 상태는 keepStore(useKeep·keepActions). RootTabs 가 늘 붙여 두고, 시트는 `open` 일 때만 보인다.
 *
 *   ① more(다른 작품도 담기)는 없어졌다 — 「더 담기」 덮개(/add)가 대신한다. `openAfterSave` 가 남긴 쪽지(addMoreRequestStore)를
 *     여기(라우터 안)서 읽어 /add 로 옮긴다.
 *   ② 환경(currentKeepEnv)마다 한 가지 권유 — 바깥 브라우저로 넘기기 · 홈 화면 추가 · 메뉴 안내. 홈 화면에서 연 앱이면 건너뛴다.
 *   ③ 「이 N곳으로 여행 계획을 짜 볼까요?」 — 계획 짜기 · 내 지도 보기
 *
 * 모양은 새 홈의 떠 있는 시트(proto/ProtoFork.tsx)와 같다. 맨 위에 해태 얼굴과 SceneTrip 글자 — 광고로 처음 온 사람이 앱 이름을 알게.
 */
import { type JSX, useCallback, useEffect, useRef, useState } from 'react'
import { track, webEvent } from '@/analytics'
import { useTabRouter } from '@/app/tabRouter'
import { Icon } from '@/design/Icon'
import { Sheet } from '@/design/overlay'
import { HStack, Txt, VStack } from '@/design/primitives'
import { accountActions, useAccount } from '@/features/profile/auth/account'
import { PrimaryButton, SecondaryButton } from '@/features/proto/protoParts'
import { color, contained, heading, radius } from '@/features/proto/protoTokens'
import { Pressable } from '@/features/route/ui/Press'
import { paths } from '@/app/routes'
import { say, useLang } from '@/i18n'
import { spots } from '@/shared/spots'
import { useCart } from '@/state/cart'
import { useStore } from '@/state/storage'
import { handoff } from './handoff'
import { currentKeepEnv, promptInstall } from './installPrompt'
import { type KeepEnv, handoffHref, keepUrl } from './keepRules'
import { myMapCount } from './keepPoiRules'
import { type KeepStep, addMoreRequestStore, keepActions, useKeep } from './keepStore'

/** 바깥 브라우저로 넘긴 뒤 이만큼 지나도 화면이 보이면 넘어가지 못한 것으로 본다. */
const HANDOFF_CHECK_MS = 1500
/** 단추·누르는 자리의 최소 높이(접근성). */
const TAP = 44
/** 내용을 재기 전(첫 그림·재는 수단이 없는 환경)의 높이. */
const FALLBACK_HEIGHT = 360

const isInApp = (env: KeepEnv) => env === 'in_app_android' || env === 'in_app_ios'
/** 메뉴로 안내하는 환경 — 두 줄 안내 + 「알겠어요」. */
const isGuide = (env: KeepEnv) => env === 'android_menu' || env === 'ios_safari' || env === 'other'

export function KeepSheet(): JSX.Element | null {
  useLang()
  const { open, step } = useKeep()
  const router = useTabRouter()
  // 「모두 담기」 가 끝났다 — 「더 담기」 로(처음 그릴 때의 값은 지나간 쪽지라 옮기지 않는다).
  const addMoreRequest = useStore(addMoreRequestStore, (n) => n)
  const seenRequest = useRef(addMoreRequest)
  useEffect(() => {
    if (addMoreRequest === seenRequest.current) return
    seenRequest.current = addMoreRequest
    router.navigate(paths.addMore)
    // eslint-disable-next-line react-hooks/exhaustive-deps -- 쪽지가 바뀔 때만.
  }, [addMoreRequest])
  // 시트 높이는 내용에 맞춘다(§9-8) — 내용 높이를 재어 손잡이 줄을 더한다. 화면보다 크면 화면 안에서 멈추고 안에서 스크롤한다.
  const [contentH, setContentH] = useState<number | null>(null)
  const measure = useCallback((el: HTMLDivElement | null) => {
    if (!el) return
    const read = () => setContentH(el.offsetHeight)
    read()
    if (typeof ResizeObserver === 'undefined') return
    const observer = new ResizeObserver(read)
    observer.observe(el)
    return () => observer.disconnect()
  }, [])
  const wanted = contentH && contentH > 0 ? TAP + contentH : FALLBACK_HEIGHT
  const detent = Math.min(wanted, typeof window === 'undefined' ? wanted : window.innerHeight - 24)

  return (
    <Sheet open={open} onClose={keepActions.close} dim={0.25} floating detent={detent} radius={30} label={say('내 지도에 담았어요', 'Saved to your map')}>
      <VStack spacing={0} align="stretch" style={{ background: 'var(--grouped-background)', height: '100%', minHeight: 0 }}>
        {/* 손잡이는 가운데 — Pressable 은 세로 flex 라 가로 가운데는 alignItems 가 맡는다. */}
        <Pressable plain onClick={keepActions.close} aria-label={say('닫기', 'Close')} style={{ flex: 'none', width: '100%', height: TAP, justifyContent: 'flex-start', alignItems: 'center', paddingTop: 8 }}>
          <span aria-hidden style={{ display: 'block', width: 40, height: 5, borderRadius: 3, background: 'var(--system-gray3)' }} />
        </Pressable>
        <div className="scroll-y" style={{ flex: 1, minHeight: 0, ...contained }}>
          <div ref={measure}>
            <VStack spacing={14} align="stretch" style={{ padding: '0 16px 22px' }}>
              <Brand />
              <StepBody step={step} />
            </VStack>
          </div>
        </div>
      </VStack>
    </Sheet>
  )
}

/** 앱 이름 — 홈 머리줄과 같은 해태 얼굴 + 강조색 SceneTrip. */
function Brand() {
  return (
    <HStack spacing={6} style={{ padding: '0 4px' }}>
      <img src="/generated/haetae-face.png" alt="" width={26} height={22} style={{ objectFit: 'contain', flex: 'none' }} draggable={false} />
      <Txt size={14} weight="heavy" style={{ color: color.primaryText }}>SceneTrip</Txt>
    </HStack>
  )
}

function StepBody({ step }: { step: KeepStep }) {
  // 옛 ① more 로 열려도 ② 부터 보인다.
  if (step === 'plan') return <PlanStep />
  return <KeepPromptStep />
}

// ---------------------------------------------------------------- ② keep

function KeepPromptStep() {
  const { contentIds } = useKeep()
  const { poiIds } = useCart()
  const { signedIn } = useAccount()
  // 이 단계에 들어온 순간의 환경으로 고정한다 — 설치 창을 쓰고 나면 `installable` 이 아니게 되어 화면이 바뀌면 안 된다.
  const [env] = useState<KeepEnv>(currentKeepEnv)
  const [hint, setHint] = useState(false)
  const [installing, setInstalling] = useState(false)
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const viewed = useRef(false)

  useEffect(() => {
    if (env === 'installed') {
      keepActions.go('plan')
      return
    }
    if (viewed.current) return
    viewed.current = true
    track(webEvent.keepPrompt(env, 'view'))
  }, [env])
  useEffect(() => () => {
    if (timer.current) clearTimeout(timer.current)
  }, [])

  const inApp = isInApp(env)
  const showSignIn = !signedIn && !inApp
  const guide = isGuide(env)
  if (env === 'installed') return null

  const toPlan = () => keepActions.go('plan')
  const act = (action: 'got_it' | 'skip') => {
    track(webEvent.keepPrompt(env, action))
    toPlan()
  }

  const openBrowser = () => {
    const href = handoffHref(env, keepUrl(window.location.origin, contentIds, [...poiIds]))
    if (!href) return
    track(webEvent.keepPrompt(env, 'open_browser'))
    handoff.open(href)
    if (timer.current) clearTimeout(timer.current)
    timer.current = setTimeout(() => {
      if (document.visibilityState === 'visible') setHint(true)
    }, HANDOFF_CHECK_MS)
  }

  const install = async () => {
    if (installing) return
    setInstalling(true)
    track(webEvent.keepPrompt(env, 'install'))
    const outcome = await promptInstall()
    // 설치 창을 띄우지 못했으면(붙잡은 것이 없거나 띄우다 실패) 거절로 세지 않는다 — 이벤트 없이 ③ 으로.
    if (outcome !== 'unavailable') track(webEvent.keepPrompt(env, outcome === 'accepted' ? 'install_accepted' : 'install_dismissed'))
    setInstalling(false)
    toPlan()
  }

  const signIn = () => {
    track(webEvent.keepPrompt(env, 'sign_in'))
    keepActions.close()
    accountActions.openSignInFor(null, 'keep')
  }

  const title =
    env === 'in_app_android'
      ? say('브라우저에서 열어 내 지도를 간직하세요', 'Open in your browser to keep your map')
      : env === 'in_app_ios'
        ? say('사파리에서 열어 내 지도를 간직하세요', 'Open in Safari to keep your map')
        : env === 'other'
          ? say('이 페이지를 즐겨찾기에 추가하세요', 'Bookmark this page')
          : say('홈 화면에 SceneTrip 을 추가하세요', 'Add SceneTrip to your Home Screen')

  return (
    <VStack spacing={16} align="stretch">
      <VStack spacing={8} align="start" style={{ padding: '0 4px' }}>
        <Txt as="h2" size={20} weight="heavy" lines={3} style={heading}>{title}</Txt>
        {inApp && (
          <HStack spacing={6} align="start">
            <Icon name="exclamationmark.triangle.fill" size={14} color="var(--system-orange, #ff9500)" />
            <Txt size={14} weight="semibold" color="secondary">{say('이 창을 닫으면 담은 곳이 사라져요', 'Close this window and your saved spots are gone')}</Txt>
          </HStack>
        )}
        {env === 'installable' && <Txt size={14} color="secondary">{say('홈 화면에서 바로 열어 담은 곳을 다시 볼 수 있어요', 'Open it from your Home Screen and find your spots again')}</Txt>}
        {guide && <GuideLines env={env} />}
      </VStack>

      {inApp ? (
        <PrimaryButton onClick={openBrowser}>{env === 'in_app_ios' ? say('사파리에서 열기', 'Open in Safari') : say('브라우저에서 열기', 'Open in browser')}</PrimaryButton>
      ) : env === 'installable' ? (
        <PrimaryButton disabled={installing} onClick={() => void install()}>{say('홈 화면에 추가', 'Add to Home Screen')}</PrimaryButton>
      ) : (
        <PrimaryButton onClick={() => act('got_it')}>{say('알겠어요', 'Got it')}</PrimaryButton>
      )}

      {hint && (
        <div role="status" style={{ padding: '10px 12px', borderRadius: radius.s, background: 'var(--system-background)' }}>
          <Txt size={13} color="secondary">{say('안 열리면 오른쪽 위 ⋯ 를 눌러 「외부 브라우저에서 열기」 를 고르세요', "Didn't open? Tap ⋯ at the top right, then “Open in external browser”.")}</Txt>
        </div>
      )}

      {showSignIn && <SecondaryButton onClick={signIn}>{say('로그인해서 어디서나 보기', 'Sign in to see it anywhere')}</SecondaryButton>}

      <TextButton onClick={() => act('skip')}>{say('나중에', 'Later')}</TextButton>
    </VStack>
  )
}

/** 메뉴 안내 두 줄. */
function GuideLines({ env }: { env: KeepEnv }) {
  const lines =
    env === 'android_menu'
      ? [say('1. 오른쪽 위 ⋮ 를 누르세요', '1. Tap ⋮ at the top right'), say('2. 「홈 화면에 추가」 를 고르세요', '2. Choose “Add to Home screen”')]
      : env === 'ios_safari'
        ? [say('1. 아래쪽 공유 단추를 누르세요', '1. Tap the Share button below'), say('2. 「홈 화면에 추가」 를 고르세요', '2. Choose “Add to Home Screen”')]
        : [say('1. 브라우저 메뉴를 여세요', '1. Open your browser menu'), say('2. 「즐겨찾기 추가」 를 고르세요', '2. Choose “Add bookmark”')]
  return (
    <VStack spacing={4} align="start" style={{ padding: '10px 12px', borderRadius: radius.s, background: 'var(--system-background)', alignSelf: 'stretch' }}>
      {lines.map((line, i) => (
        <HStack key={line} spacing={6}>
          <Txt size={14}>{line}</Txt>
          {env === 'ios_safari' && i === 0 && <Icon name="square.and.arrow.up" size={14} color="accent" />}
        </HStack>
      ))}
    </VStack>
  )
}

// ---------------------------------------------------------------- ③ plan

function PlanStep() {
  const router = useTabRouter()
  const cart = useCart()
  // 목록에서 빠진 촬영지·폐업한 편의시설은 세지 않는다(keep-poi.md §9-5). 담은 직후라 목록(items·pois)보다 id 집합이 앞서 있을 수 있어 id 집합에서 뺀다.
  const delisted = cart.items.filter((i) => i.delisted === true && cart.placeIds.has(i.placeId)).length
  const closed = cart.pois.filter((p) => p.closed === true && cart.poiIds.has(p.id)).length
  const n = myMapCount(cart.placeIds.size - delisted, cart.poiIds.size - closed)

  const plan = () => {
    keepActions.close()
    track(webEvent.planStart('keep_sheet'))
    router.openPlanFromSaved()
  }
  const openMap = () => {
    keepActions.close()
    track(webEvent.myMap('open_keep'))
    router.openMyMap()
  }

  return (
    <VStack spacing={14} align="stretch">
      <Txt as="h2" size={20} weight="heavy" lines={3} style={{ ...heading, padding: '0 4px' }}>
        {say(`이 ${n}곳으로 여행 계획을 짜 볼까요?`, `Plan a trip with these ${spots(n)}?`)}
      </Txt>
      <PrimaryButton onClick={plan}>{say('계획 짜기', 'Plan my trip')}</PrimaryButton>
      <SecondaryButton onClick={openMap}>{say('내 지도 보기', 'See my map')}</SecondaryButton>
      <TextButton onClick={keepActions.close}>{say('닫기', 'Close')}</TextButton>
    </VStack>
  )
}

/** 옅은 글자 단추 — 「나중에」·「닫기」. 누르는 자리는 44px. */
function TextButton({ children, onClick }: { children: string; onClick: () => void }) {
  return (
    <Pressable plain onClick={onClick} style={{ width: '100%', minHeight: TAP, display: 'grid', placeItems: 'center' }}>
      <Txt size={15} weight="semibold" color="secondary">{children}</Txt>
    </Pressable>
  )
}
