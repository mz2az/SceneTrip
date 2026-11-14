/*
 * 홈 탭 아래 떠 있는 가입 권유 알약(MZ2AZ-564, docs/project/plans/web-signup-nudge.md §1).
 * 규칙·저장은 signUpNudgeRules.ts. 누르면 기존 로그인 시트, ✕ 면 3 일 숨긴다.
 */
import { useEffect, useState } from 'react'
import { track, webEvent } from '@/analytics'
import { Icon } from '@/design/Icon'
import { say, useLang } from '@/i18n'
import { useStore } from '@/state/storage'
import { cartStore } from '@/state/cart'
import { likeStore } from '@/state/likes'
import { accountActions, useAccount } from './account'
import { dismiss, markShown, nudgeMessage, nudgeShownThisSession, nudgeTrigger, nudgeVisible, readNudgeState } from './signUpNudgeRules'

export function SignUpNudge({ onHome, protoOn = false }: { onHome: boolean; protoOn?: boolean }) {
  const lang = useLang()
  const { signedIn } = useAccount()
  const savedPlaces = useStore(cartStore, (s) => s.placeIds.size)
  const likedWorks = useStore(likeStore, (s) => s.contentIds.size)
  const [closed, setClosed] = useState(false)

  const visible = !closed && nudgeVisible(readNudgeState(), {
    signedIn, onHome, protoOn, savedPlaces, likedWorks, now: Date.now(), shownThisSession: nudgeShownThisSession(),
  })
  const trigger = nudgeTrigger(savedPlaces, likedWorks)

  useEffect(() => {
    if (visible) markShown(trigger)
  }, [visible, trigger])

  // 알약이 떠 있는 동안 홈 목록의 바닥 여백을 늘려 마지막 카드가 알약 뒤에 숨지 않게 한다(HomeTab 이 --nudge-inset 을 읽는다).
  useEffect(() => {
    if (!visible) return
    const root = document.documentElement
    root.style.setProperty('--nudge-inset', '88px')
    return () => {
      root.style.removeProperty('--nudge-inset')
    }
  }, [visible])

  if (!visible) return null

  const onOpen = () => {
    track(webEvent.signupNudgeClick(trigger))
    accountActions.openSignInFor(null, 'nudge')
  }
  const onDismiss = () => {
    dismiss(trigger)
    setClosed(true)
  }

  return (
    <div
      style={{
        position: 'absolute', left: 16, right: 16, zIndex: 10,
        // 새 홈 화면은 탭 내용이 화면 바닥까지 내려가고 「Plan a trip」 단추가 탭바 위에 뜬다 — 그 단추 바로 위에 둔다.
        bottom: protoOn ? 'calc(var(--tabbar-height) + var(--safe-bottom) + 80px)' : 12,
        display: 'flex', justifyContent: 'center', pointerEvents: 'none',
      }}
    >
      {/* 눈에 띄게(사용자 피드백 11/13) — 하늘→보라 그라데이션 카드, 앞에 기호, 오른쪽에 흰 「로그인」 칩. 아래에서 올라오며 나타난다. */}
      <div
        style={{
          pointerEvents: 'auto', display: 'flex', alignItems: 'center', width: '100%', maxWidth: 420,
          background: 'var(--pin-gradient)', color: '#fff', borderRadius: 20,
          boxShadow: '0 10px 28px rgba(60, 40, 180, 0.35)', animation: 'st-nudge-in 0.42s cubic-bezier(0.2, 0.9, 0.3, 1.2) both',
        }}
      >
        <button
          type="button"
          onClick={onOpen}
          style={{
            flex: 1, minWidth: 0, minHeight: 64, padding: '10px 6px 10px 12px', border: 0, background: 'transparent',
            color: 'inherit', font: 'inherit', textAlign: 'left', cursor: 'pointer',
            display: 'flex', alignItems: 'center', gap: 10,
          }}
        >
          <span aria-hidden style={{ flex: 'none', width: 38, height: 38, borderRadius: 999, background: 'rgba(255,255,255,0.22)', display: 'grid', placeItems: 'center' }}>
            <Icon name={savedPlaces + likedWorks > 0 ? 'heart.fill' : 'person.fill'} size={17} weight="semibold" color="#fff" />
          </span>
          <span
            style={{
              flex: 1, minWidth: 0, fontSize: 15, fontWeight: 700, lineHeight: 1.3,
              display: '-webkit-box', WebkitLineClamp: 2, WebkitBoxOrient: 'vertical', overflow: 'hidden',
            }}
          >
            {nudgeMessage(savedPlaces, likedWorks, lang)}
          </span>
          <span
            aria-hidden
            style={{
              flex: 'none', padding: '7px 12px', borderRadius: 999, background: '#fff', color: 'var(--home-purple)',
              fontSize: 13, fontWeight: 800, whiteSpace: 'nowrap',
            }}
          >
            {say('로그인', 'Sign in')}
          </span>
        </button>
        <button
          type="button"
          aria-label={say('닫기', 'Dismiss')}
          onClick={onDismiss}
          style={{
            flex: 'none', width: 44, height: 44, marginRight: 4, border: 0, borderRadius: 999, background: 'transparent',
            color: 'rgba(255,255,255,0.85)', fontSize: 16, lineHeight: 1, cursor: 'pointer',
          }}
        >
          <span aria-hidden>✕</span>
        </button>
      </div>
      <style>{'@keyframes st-nudge-in { from { opacity: 0; transform: translateY(24px) scale(0.96) } to { opacity: 1; transform: none } }'}</style>
    </div>
  )
}
