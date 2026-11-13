/*
 * 홈에서 비회원에게 구글 원탭을 띄운다(MZ2AZ-565, docs/project/plans/web-google-one-tap.md §1).
 * 그리는 것은 없다 — 창은 구글(GIS)이 띄운다. 홈이 보이고 1.5 초 뒤, 조건이 그대로일 때만.
 */
import { useEffect } from 'react'
import { accountActions, useAccount } from './account'
import { isWebShell } from './nativeAuth'
import { googleClientId } from './oauth'
import { cancelOneTap, defaultMakeNonce, loadGoogleIdentity, oneTapAllowed, oneTapPromptedThisSession, startOneTap } from './oneTap'

const DELAY_MS = 1500

export function GoogleOneTap({ onHome }: { onHome: boolean }) {
  const { signedIn, busy, showingSignIn } = useAccount()
  const allowed = oneTapAllowed({
    signedIn, clientId: googleClientId(), webShell: isWebShell(), onHome,
    promptedThisSession: oneTapPromptedThisSession(), busy: busy || showingSignIn,
  })

  useEffect(() => {
    if (!allowed) return
    const clientId = googleClientId()
    if (!clientId) return
    // 조건이 1.5 초 동안 그대로여야 띄운다 — 그 사이 홈을 떠나거나 로그인하면 이 타이머가 지워진다.
    const timer = window.setTimeout(() => {
      void startOneTap({ clientId, loadScript: loadGoogleIdentity, signIn: accountActions.signInWithGoogleCredential, makeNonce: defaultMakeNonce })
    }, DELAY_MS)
    return () => window.clearTimeout(timer)
  }, [allowed])

  // 창이 떠 있는 동안 홈을 떠나거나 로그인했거나 로그인 시트가 열리면 원탭 창을 닫는다.
  const keepOpen = onHome && !signedIn && !showingSignIn
  useEffect(() => {
    if (!keepOpen) cancelOneTap()
  }, [keepOpen])

  return null
}
