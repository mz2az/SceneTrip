/*
 * 앱의 맨 바깥 — 첫 화면(인트로) 덮개와 그 밑의 앱. 정본: docs/project/plans/web-ad-landing.md §10-3-1 · §10-3-2 · §11.
 *
 * 덮개가 떠 있는 동안 밑의 앱(AppRoot — 홈·탭·지도·분석)은 그리지도 받지도 않는다. 덮개 코드도 따로 떨어진 덩어리라,
 * 처음 온 사람은 이 파일·라우터·React 와 덮개 덩어리만 받고 첫 화면을 본다. ③ 이 그려진 뒤 밑의 앱을 붙여 둔다(덮개가 가린다).
 * 덮개가 걷히면 그 자리의 앱이 보인다 — 주소는 그대로, 담기를 누른 경우만 「더 담기」(/add).
 *
 * 앱이 떠 있는 동안 `/go/{id}` 로 오면(홈의 작품 칸) 덮개를 다시 띄우지 않고 그 작품 상세로 간다(§11).
 *
 * 분석(§6·§10-3-2): 덮개가 뜨면 `screen_view(intro)`, 박자가 바뀔 때마다 `landing(beat)`, 담기 전에 페이지를 떠나면 `landing(leave)`.
 * 떠남은 덮개 덩어리(LandingCover)를 받기 전부터 여기서 듣는다 — 보라 대기 화면에서 떠난 사람(`beat=loading`)도 남게.
 */
import { type JSX, Suspense, lazy, useCallback, useEffect, useRef, useState } from 'react'
import { useLocation, useNavigate } from 'react-router'
import { type LandingBeat, type LandingLeaveBeat, appEvent, track, webEvent } from '@/analytics'
import type { IntroClose } from '@/features/landing/introClose'
import { IntroSplash } from '@/features/landing/IntroSplash'
import { releaseDeferred } from './deferred'
import { introOpenStore, markIntroDone } from './intro'
import { landingIdOf, paths } from './routes'

const loadAppRoot = () => import('./AppRoot')
const AppRoot = lazy(() => loadAppRoot().then((m) => ({ default: m.AppRoot })))
const LandingCover = lazy(() => import('@/features/landing/LandingCover').then((m) => ({ default: m.LandingCover })))

/** ③ 이 그려진 뒤 밑의 앱을 붙이기까지(ms) — ③ 의 아래 판이 올라오는 움직임과 겹치지 않게. */
const MOUNT_APP_AFTER_REST_MS = 600

export function IntroShell({ initialIntro }: { initialIntro: { contentId: number; fromGo: boolean } | null }): JSX.Element {
  const navigate = useNavigate()
  const { pathname } = useLocation()
  const [intro, setIntro] = useState(initialIntro)
  const [appMounted, setAppMounted] = useState(initialIntro === null)
  const sentView = useRef(false)
  const [startedAt] = useState(() => (typeof performance === 'undefined' ? 0 : performance.now()))
  /** 마지막으로 본 박자 · 담기를 눌렀나 · 떠남을 보냈나 — 떠날 때 한 번만 보낸다. */
  const lastBeat = useRef<LandingLeaveBeat>('loading')
  const keptRef = useRef(false)
  const leftRef = useRef(false)
  const contentId = intro?.contentId ?? null

  const onBeat = useCallback(
    (beat: LandingBeat) => {
      if (contentId === null || lastBeat.current === beat) return
      lastBeat.current = beat
      track(webEvent.landingBeat(contentId, beat))
    },
    [contentId],
  )
  const onKeep = useCallback(() => {
    keptRef.current = true
  }, [])

  // 담기 전에 페이지를 떠났다 — pagehide 또는 가려짐(앱 전환·탭 닫기), 처음 한 번만.
  useEffect(() => {
    if (contentId === null) return
    const leave = () => {
      if (leftRef.current || keptRef.current) return
      leftRef.current = true
      const now = typeof performance === 'undefined' ? startedAt : performance.now()
      track(webEvent.landingLeave(contentId, lastBeat.current, now - startedAt), { leaving: true })
    }
    const onVisibility = () => {
      if (document.visibilityState === 'hidden') leave()
    }
    window.addEventListener('pagehide', leave)
    document.addEventListener('visibilitychange', onVisibility)
    return () => {
      window.removeEventListener('pagehide', leave)
      document.removeEventListener('visibilitychange', onVisibility)
    }
  }, [contentId, startedAt])

  // 덮개가 떠 있는 동안은 밑의 앱이 화면 이름을 보내지 않는다 — 덮개의 화면 이름은 `intro`.
  useEffect(() => {
    introOpenStore.set(intro !== null)
    if (intro !== null && !sentView.current) {
      sentView.current = true
      track(appEvent.screenView('intro'))
    }
  }, [intro])

  // 앱이 떠 있는 동안의 `/go/{id}` — 덮개 없이 작품 상세로.
  const goId = landingIdOf(pathname)
  useEffect(() => {
    if (goId !== null && intro === null) navigate(paths.searchContent(goId), { replace: true })
  }, [goId, intro, navigate])

  const onRest = useCallback(() => {
    // 끝까지 봤다 — 다시 와도 첫 화면을 띄우지 않는다(§11).
    markIntroDone()
    releaseDeferred()
    // 밑의 앱 덩어리를 지금 받아 둔다 — 담기 뒤 「더 담기」 가 곧바로 뜨게. 받기만 해도 모듈이 실행되며 서버를 부르는 것이 있어(시안의 /me/trip)
    // ③ 전에는 받지 않는다(§11 — 덮개가 떠 있는 동안 밑의 앱은 API 0).
    void loadAppRoot().catch(() => {})
    setTimeout(() => setAppMounted(true), MOUNT_APP_AFTER_REST_MS)
  }, [])

  const onClose = useCallback(
    (how: IntroClose) => {
      const fromGo = intro?.fromGo === true
      const contentId = intro?.contentId ?? null
      // 받지 못한 것은 다음에 다시 보일 수 있게 남겨 둔다. 그 밖은 끝까지 봤거나 건너뛰었거나 처음 온 사람이 아니다.
      if (how !== 'failed') markIntroDone()
      setIntro(null)
      setAppMounted(true)
      releaseDeferred()
      if (how === 'kept') navigate(paths.addMore)
      else if (how === 'myMap') navigate(paths.myMap)
      else if (how === 'notNew' && fromGo && contentId !== null) navigate(paths.searchContent(contentId), { replace: true })
    },
    [intro, navigate],
  )

  return (
    <>
      {appMounted && (
        <Suspense fallback={null}>
          <AppRoot />
        </Suspense>
      )}
      {intro !== null && (
        <div style={{ position: 'absolute', inset: 0, zIndex: 45 }}>
          <Suspense fallback={<IntroSplash />}>
            <LandingCover key={intro.contentId} contentId={intro.contentId} startedAt={startedAt} onRest={onRest} onClose={onClose} onBeat={onBeat} onKeep={onKeep} />
          </Suspense>
        </div>
      )}
    </>
  )
}
