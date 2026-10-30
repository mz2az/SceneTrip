/*
 * 로그인 시트 — iOS `SignInView` (Auth/SignInView.swift, MZ2AZ-336·415·455). 반쯤 올라오는 시트다.
 *
 * 로그인은 **가입을 겸한다** — 처음 보는 구글·Apple 계정이면 그 자리에서 계정이 만들어지고, 이 기기에서 비회원으로 담아 둔
 * 장바구니·코스·찜이 그 계정으로 옮겨 간다. 체크박스는 없고, 버튼 바로 아래 「계속하면 … 동의하는 것으로 봅니다」 안내가 있다.
 * 웹용 OAuth 클라이언트 ID 가 없으면 버튼을 눌렀을 때 「준비 중」 안내가 뜬다(oauth.ts).
 */
import { HStack, Pressable, Spinner, Txt, VStack } from '@/design/primitives'
import { tr } from '@/i18n'
import { LEGAL_LINKS } from '../legalLinks'
import { GlassSheet } from '../ui/GlassSheet'
import { accountActions, useAccount } from './account'

/** Apple 공식 로고 (Sign in with Apple 버튼 지침 — 모양·색을 바꾸지 않는다). */
function AppleLogo({ size = 18 }: { size?: number }) {
  return (
    <svg width={size} height={size * 1.23} viewBox="0 0 814 1000" aria-hidden>
      <path
        fill="#fff"
        d="M788.1 340.9c-5.8 4.5-108.2 62.2-108.2 190.5 0 148.4 130.3 200.9 134.2 202.2-.6 3.2-20.7 71.9-68.7 141.9-42.8 61.6-87.5 123.1-155.5 123.1s-85.5-39.5-164-39.5c-76.5 0-103.7 40.8-165.9 40.8s-105.6-57-155.5-127C46.7 790.7 0 663 0 541.8c0-194.4 126.4-297.5 250.8-297.5 66.1 0 121.2 43.4 162.7 43.4 39.5 0 101.1-46 176.3-46 28.5 0 130.9 2.6 198.3 99.2zm-234-181.5c31.1-36.9 53.1-88.1 53.1-139.3 0-7.1-.6-14.3-1.9-20.1-50.6 1.9-110.8 33.7-147.1 75.8-28.5 32.4-55.1 83.6-55.1 135.5 0 7.8 1.3 15.6 1.9 18.1 3.2.6 8.4 1.3 13.6 1.3 45.4 0 102.5-30.4 135.5-71.3z"
      />
    </svg>
  )
}

/** 큰 글자에서도 누르는 자리는 44 이상 — 캡션 높이만으로는 손가락이 빗나간다. */
function LegalLink({ href, children }: { href: string; children: string }) {
  return (
    <a href={href} target="_blank" rel="noreferrer" style={{ color: 'var(--accent)', textDecoration: 'underline', minHeight: 44, display: 'inline-flex', alignItems: 'center' }}>
      {children}
    </a>
  )
}

/** 가입 동의 안내 (MZ2AZ-455) — 로그인이 가입을 겸하므로 단추 바로 아래. */
function LegalNotice() {
  return (
    <VStack align="center" style={{ textAlign: 'center' }}>
      <Txt as="div" size={12} color="secondary" align="center" style={{ lineHeight: '15px' }}>
        {tr('계속하면 이용약관과 개인정보 처리방침에 동의하는 것으로 봅니다.')}
      </Txt>
      <HStack spacing={6} justify="center" wrap style={{ fontSize: 12 }}>
        <LegalLink href={LEGAL_LINKS.terms}>{tr('이용약관')}</LegalLink>
        <span aria-hidden style={{ color: 'var(--label-secondary)' }}>·</span>
        <LegalLink href={LEGAL_LINKS.privacy}>{tr('개인정보 처리방침')}</LegalLink>
      </HStack>
    </VStack>
  )
}

export function SignInSheet() {
  const { showingSignIn, busy, message, appleSignInEnabled } = useAccount()
  return (
    <GlassSheet open={showingSignIn} onClose={accountActions.closeSignIn} detent="medium" dismissDisabled={busy} zIndex={60} label={tr('SceneTrip 로그인')}>
      <div className="scroll-y" style={{ flex: 1, minHeight: 0 }}>
        <VStack align="center" style={{ padding: '0 24px' }}>
          <img src="/generated/haetae-joy.png" alt="" draggable={false} style={{ height: 72, width: 'auto', marginTop: 18 }} />
          <Txt as="h2" size={20} weight="bold" style={{ marginTop: 14, lineHeight: '25px' }}>{tr('SceneTrip 로그인')}</Txt>
          <Txt as="p" size={15} color="secondary" align="center" style={{ marginTop: 6, lineHeight: '17.9px' }}>
            {tr('담아 둔 장바구니·코스·찜이 계정에 저장돼요.\n다른 기기에서도 그대로 이어집니다.')}
          </Txt>

          <div style={{ height: 16, flex: 'none' }} /> {/* ScrollView 안의 Spacer(minLength: 16) 는 16 으로 접힌다 */}

          {message && (
            <Txt as="div" size={13} color="red" align="center" style={{ paddingBottom: 10, lineHeight: '16px' }} role="alert">{message}</Txt>
          )}
          {busy && (
            <div style={{ paddingBottom: 10 }}>
              <Spinner />
            </div>
          )}

          {appleSignInEnabled && (
            <Pressable
              plain
              disabled={busy}
              onClick={() => void accountActions.signInWithApple()}
              aria-label={tr('Apple 계정으로 계속하기')}
              data-testid="apple-sign-in"
              style={{ width: '100%', height: 50, borderRadius: 25, background: '#000', marginBottom: 10, opacity: busy ? 0.5 : 1 }}
            >
              <HStack spacing={8} justify="center" style={{ height: '100%' }}>
                <AppleLogo />
                <Txt size={16} weight="medium" color="white">{tr('Apple 계정으로 계속하기')}</Txt>
              </HStack>
            </Pressable>
          )}

          {/* 구글 브랜드 지침의 밝은 단추 — 흰 바탕, #747775 테두리, #1F1F1F 글자, 공식 「G」 로고. */}
          <Pressable
            plain
            disabled={busy}
            onClick={() => void accountActions.signInWithGoogle()}
            data-testid="google-sign-in"
            style={{ width: '100%', height: 50, borderRadius: 25, background: '#fff', border: '1px solid #747775', opacity: busy ? 0.5 : 1 }}
          >
            <HStack spacing={10} justify="center" style={{ height: '100%' }}>
              <img src="/generated/google-g.svg" alt="" width={20} height={20} draggable={false} />
              <Txt size={16} weight="medium" color="#1f1f1f">{tr('Google 계정으로 계속하기')}</Txt>
            </HStack>
          </Pressable>

          <div style={{ marginTop: 12 }}>
            <LegalNotice />
          </div>

          <Pressable plain disabled={busy} onClick={accountActions.closeSignIn} style={{ margin: '14px 0 18px' }}>
            <Txt size={15} color="secondary">{tr('나중에 할게요')}</Txt>
          </Pressable>
        </VStack>
      </div>
    </GlassSheet>
  )
}
