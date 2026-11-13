/*
 * 홈 탭 아래 떠 있는 가입 권유 알약(MZ2AZ-564, docs/project/plans/web-signup-nudge.md §1).
 * 규칙·저장은 signUpNudgeRules.ts. 누르면 기존 로그인 시트, ✕ 면 3 일 숨긴다.
 */
import { useEffect, useState } from 'react'
import { track, webEvent } from '@/analytics'
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
    root.style.setProperty('--nudge-inset', '72px')
    return () => {
      root.style.removeProperty('--nudge-inset')
    }
  }, [visible])

  if (!visible) return null

  const onOpen = () => {
    track(webEvent.signupNudgeClick(trigger))
    accountActions.openSignIn()
  }
  const onDismiss = () => {
    dismiss(trigger)
    setClosed(true)
  }

  return (
    <div
      style={{
        position: 'absolute', left: 16, right: 16, bottom: 12, zIndex: 10,
        display: 'flex', justifyContent: 'center', pointerEvents: 'none',
      }}
    >
      <div
        style={{
          pointerEvents: 'auto', display: 'flex', alignItems: 'center', width: '100%', maxWidth: 420,
          background: 'var(--accent)', color: '#fff', borderRadius: 999, boxShadow: '0 6px 20px rgba(0,0,0,0.18)',
        }}
      >
        <button
          type="button"
          onClick={onOpen}
          style={{
            flex: 1, minWidth: 0, minHeight: 48, padding: '8px 4px 8px 18px', border: 0, background: 'transparent',
            color: 'inherit', font: 'inherit', fontSize: 14, fontWeight: 600, lineHeight: 1.3, textAlign: 'left', cursor: 'pointer',
            display: '-webkit-box', WebkitLineClamp: 2, WebkitBoxOrient: 'vertical', overflow: 'hidden',
          }}
        >
          {nudgeMessage(savedPlaces, likedWorks, lang)}
        </button>
        <button
          type="button"
          aria-label={say('닫기', 'Dismiss')}
          onClick={onDismiss}
          style={{
            flex: 'none', width: 44, height: 44, marginRight: 4, border: 0, borderRadius: 999, background: 'transparent',
            color: 'inherit', fontSize: 18, lineHeight: 1, cursor: 'pointer',
          }}
        >
          <span aria-hidden>✕</span>
        </button>
      </div>
    </div>
  )
}
