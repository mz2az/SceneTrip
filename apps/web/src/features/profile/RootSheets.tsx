/**
 * 루트에 상시 얹히는 시트들 — 로그인 시트(`.signInSheet()`)와 닉네임 시트(`.nicknameSheet()`). (iOS: Auth/AuthStore.swift 의 `SignInSheet`·`NicknameSheet`,
 * Auth/SignInView.swift, Reviews/NicknameView.swift)
 *
 * iOS 는 덮개(경로여정·마이페이지)가 떠 있으면 그 덮개가 시트를 올린다. 웹의 시트는 오버레이 루트에 포탈되어 덮개 위(z-index)에 뜨므로
 * 덮개 어느 쪽이든 여기서 한 번만 올리면 된다 — 그래서 `active` 와 상관없이 호스트한다.
 *
 * 하는 일:
 *  1. 앱이 뜨면 저장된 세션을 되살린다(`/me`)·401 처리 갈고리를 건다.
 *  2. 어디서든 `promptSignIn()`(state/auth.ts)이 불리면 로그인 시트를 올린다.
 *  3. 가이드·길찾기의 동의 문(`consentPort`)이 부탁하는 AI·위치 동의 시트를 올린다.
 *  4. 소셜 로그인 팝업이 돌아온 주소(`/auth/callback/<제공자>`)면 결과를 연 창에 넘기고 닫는다.
 */
import { useEffect } from 'react'
import { dismissSignInPrompt, signInPromptStore } from '@/state/auth'
import { useStore } from '@/state/storage'
import { ConsentPresenterHost } from './consent/ConsentPresenter'
import { accountActions } from './auth/account'
import { NicknameWelcomeSheet } from './auth/NicknameSheet'
import { relayCallbackToOpener } from './auth/oauth'
import { SignInSheet } from './auth/SignInSheet'

export function RootSheets({ active: _active }: { active: boolean }) {
  const requested = useStore(signInPromptStore, (s) => s.requested)

  useEffect(() => {
    if (relayCallbackToOpener()) return
    accountActions.start()
  }, [])

  useEffect(() => {
    if (!requested) return
    accountActions.openSignIn()
    dismissSignInPrompt()
  }, [requested])

  return (
    <>
      <SignInSheet />
      <NicknameWelcomeSheet />
      <ConsentPresenterHost />
    </>
  )
}
