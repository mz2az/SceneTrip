/*
 * 마이페이지 본문 뒤쪽 절들 — 발자취 · 도움 · 이용 통계 · AI·위치 설정 · 약관 및 정책 · 계정 · 이 기기의 기록 · 준비 중
 * (iOS ProfileTabView.swift:192~620, ProfileAISettings.swift, ProfileDeviceDataSection.swift, BlockedUsersView.swift 의 SupportContactRow).
 * 광고 추적 허용(ATT) 줄은 웹에 없어 숨긴다 — docs/project/web-app.md §5.
 */
import { useRef, useState } from 'react'
import { Alert } from '@/design/overlay'
import { Spinner, Txt } from '@/design/primitives'
import { chooseLanguage, langName, tr, useLang, LANG_CHOICES } from '@/i18n'
import { footprintActions, useFootprints } from '@/state/footprints'
import { installId } from '@/state/install'
import { accountActions, useAccount } from './auth/account'
import { deviceDeleteMessage, isGuestDeleteVisible } from './auth/guestDataDeletion'
import { useAIConsent, useLocationConsent, AIConsentSheet, LocationConsentSheet } from './consent'
import { LEGAL_LINKS, SUPPORT_MAIL_URL } from './legalLinks'
import { setStatisticsEnabled, useStatisticsEnabled } from './usageStatistics'
import { ChoicePopover } from './ui/ChoicePopover'
import { ListSection, Row, ToggleRow } from './ui/ListUI'
import { firstSentence } from './wording'

/** 문의하기 — 주소를 **글자로** 보인다. 메일 앱이 없는 기기에서도 주소를 눌러 복사할 수 있다. 옆의 단추는 메일 앱을 연다(MZ2AZ-461, App Store 1.2). */
function SupportContactRow() {
  return (
    <Row icon="envelope" tint="var(--accent)" title={tr('문의하기')} subtitle={<span style={{ userSelect: 'text' }}>{LEGAL_LINKS.supportEmail}</span>}
      trailing={
        <a href={SUPPORT_MAIL_URL} aria-label={LEGAL_LINKS.supportEmail} style={{ color: 'var(--accent)', fontSize: 15, fontWeight: 600, lineHeight: '20px' }}>
          {tr('메일 쓰기')}
        </a>
      }
    />
  )
}

// ---------------------------------------------------------------- 발자취

/** 발자취 — 이동 경로는 개인정보다(MZ2AZ-348). 기본은 꺼짐이고, 켤 때 무엇이 어디에 저장되는지 알리고 동의를 받는다. 기기에만 있는 기록이라 지우기도 여기서만 한다. */
export function FootprintSection() {
  const fp = useFootprints()
  const [askingConsent, setAskingConsent] = useState(false)
  const [clearing, setClearing] = useState(false)
  return (
    <>
      <ListSection header={tr('발자취')} footer={tr('이 기기에만 저장돼요. 서버로 보내지 않아요.')}>
        <ToggleRow label={tr('발자취 기록하기')} checked={fp.recording} onChange={(on) => (on ? setAskingConsent(true) : footprintActions.setRecording(false))} />
        <Row icon="shoeprints.fill" tint="deep" title={tr('기록한 거리')} value={tr('%.1f km · %d점', fp.kilometers, fp.points.length)} />
        <ToggleRow label={tr('지도에 발자취 보기')} checked={fp.enabled} onChange={footprintActions.setEnabled} />
        <Row title={tr('발자취 지우기')} titleColor={fp.points.length === 0 ? 'tertiary' : 'red'} disabled={fp.points.length === 0} onClick={() => setClearing(true)} />
      </ListSection>
      <Alert
        open={askingConsent}
        title={tr('발자취를 기록할까요?')}
        message={tr('여행 모드에서 지나간 길을 이 기기에만 저장해요. 서버로 보내지 않고, 로그아웃하거나 탈퇴하면 지워집니다.')}
        buttons={[{ label: tr('기록하기'), onPress: () => footprintActions.setRecording(true) }, { label: tr('취소'), role: 'cancel' }]}
        onClose={() => setAskingConsent(false)}
      />
      <Alert
        open={clearing}
        title={tr('발자취를 모두 지울까요? 복구할 수 없어요.')}
        buttons={[{ label: tr('지우기'), role: 'destructive', onPress: footprintActions.clear }, { label: tr('취소'), role: 'cancel' }]}
        onClose={() => setClearing(false)}
      />
    </>
  )
}

// ---------------------------------------------------------------- 도움

export function HelpSection({ onReplay, onLanguageChosen }: { onReplay: () => void; onLanguageChosen: () => void }) {
  const lang = useLang()
  const [choosing, setChoosing] = useState(false)
  const anchor = useRef<HTMLElement>(null)
  return (
    <>
      <ListSection header={tr('도움')}>
        <Row anchorRef={anchor} icon="globe" tint="var(--accent)" title={tr('언어')} value={langName(lang)} chevron onClick={() => setChoosing(true)} />
        <Row icon="questionmark.circle" tint="var(--accent)" title={tr('사용법 다시 보기')} onClick={onReplay} />
        <SupportContactRow />
      </ListSection>
      <LanguageChoice open={choosing} anchor={anchor} onClose={() => setChoosing(false)} onChosen={onLanguageChosen} />
    </>
  )
}

/** 언어 이름은 그 언어로 적는다 — 번역하지 않는다. 바꾸면 루트가 화면을 통째로 다시 만들어 이 덮개도 닫힌다. */
function LanguageChoice({ open, anchor, onClose, onChosen }: { open: boolean; anchor: React.RefObject<HTMLElement | null>; onClose: () => void; onChosen: () => void }) {
  return (
    <ChoicePopover
      open={open}
      anchor={anchor}
      title={tr('언어')}
      actions={LANG_CHOICES.map((l) => ({
        label: langName(l),
        onPress: () => {
          chooseLanguage(l)
          onChosen()
        },
      }))}
      onClose={onClose}
    />
  )
}

// ---------------------------------------------------------------- 이용 통계 · AI · 위치

export function StatisticsSection() {
  const on = useStatisticsEnabled()
  return (
    <ListSection footer={firstSentence(tr('꺼도 앱 기능은 그대로예요. 광고 추적 허용은 설정 앱에서 바꿀 수 있어요.'))}>
      <ToggleRow icon="chart.bar" tint="var(--accent)" label={tr('이용 통계 수집')} checked={on} onChange={(v) => setStatisticsEnabled(v)} testId="usage-statistics-toggle" />
    </ListSection>
  )
}

/** 이용 통계 아래의 AI·위치 동의 설정. 두 동의는 독립적으로 철회한다. */
export function ConsentSettingsSection() {
  const ai = useAIConsent()
  const location = useLocationConsent()
  return (
    <>
      <ListSection footer={tr('끄면 AI 가이드와 AI 코스 만들기를 쓸 때 다시 물어봐요.')}>
        <ToggleRow icon="st.sparkles" tint="#af52de" label={tr('AI 기능 사용')} checked={ai.allowed} onChange={ai.setEnabled} testId="ai-features-toggle" />
      </ListSection>
      <ListSection footer={tr('끄면 위치를 쓰는 기능을 쓸 때 다시 물어봐요.')}>
        <ToggleRow icon="st.location.arrow" tint="var(--accent)" label={tr('위치기반서비스 이용')} checked={location.allowed} onChange={location.setEnabled} testId="location-services-toggle" />
      </ListSection>
      <AIConsentSheet gate={ai.gate} />
      <LocationConsentSheet gate={location.gate} />
    </>
  )
}

// ---------------------------------------------------------------- 약관 · 계정 · 기기 기록 · 준비 중

/** 처리방침·약관(MZ2AZ-455, App Store 5.1.1 — 앱 안에서 찾을 수 있어야 한다). 비회원에게도 보인다. */
export function LegalSection() {
  return (
    <ListSection header={tr('약관 및 정책')}>
      <Row icon="st.hand.raised" tint="var(--accent)" title={tr('개인정보 처리방침')} chevron href={LEGAL_LINKS.privacy} />
      <Row icon="doc.text" tint="var(--accent)" title={tr('이용약관')} chevron href={LEGAL_LINKS.terms} />
    </ListSection>
  )
}

/** 계정 — 로그아웃과 탈퇴. 탈퇴는 App Store 요건이라 앱 안에 있어야 한다. */
export function AccountSection({ onDeleted }: { onDeleted: () => void }) {
  const { signedIn, busy } = useAccount()
  const [confirmingSignOut, setConfirmingSignOut] = useState(false)
  const [confirmingDelete, setConfirmingDelete] = useState(false)
  const [deleteFailed, setDeleteFailed] = useState(false)
  const anchor = useRef<HTMLElement>(null)
  if (!signedIn) return null
  return (
    <>
      <ListSection header={tr('계정')}>
        <Row anchorRef={anchor} icon="rectangle.portrait.and.arrow.right" tint="var(--system-gray)" title={tr('로그아웃')} disabled={busy} onClick={() => setConfirmingSignOut(true)} />
        <Row title={tr('회원 탈퇴')} titleColor="red" disabled={busy} onClick={() => setConfirmingDelete(true)} />
      </ListSection>
      <ChoicePopover
        open={confirmingSignOut}
        anchor={anchor}
        title={tr('로그아웃할까요? 이 기기는 비회원으로 돌아가요.')}
        actions={[{ label: tr('로그아웃'), role: 'destructive', onPress: () => void accountActions.signOut() }]}
        onClose={() => setConfirmingSignOut(false)}
      />
      <Alert
        open={confirmingDelete}
        title={tr('정말 탈퇴할까요?')}
        // 서버는 탈퇴 시 작성한 리뷰·여행후기와 사진도 삭제한다(MZ2AZ-466, MZ2AZ-452).
        message={tr('장바구니·코스·찜과 이 기기의 발자취, 작성한 리뷰·여행후기와 사진이 모두 지워지고 되돌릴 수 없어요.')}
        buttons={[
          { label: tr('탈퇴'), role: 'destructive', onPress: () => void accountActions.deleteAccount().then((ok) => (ok ? onDeleted() : setDeleteFailed(true))) },
          { label: tr('취소'), role: 'cancel' },
        ]}
        onClose={() => setConfirmingDelete(false)}
      />
      <Alert open={deleteFailed} title={tr('탈퇴하지 못했어요. 잠시 뒤 다시 해 주세요')} buttons={[{ label: tr('확인'), role: 'cancel' }]} onClose={() => setDeleteFailed(false)} />
    </>
  )
}

/** 비회원의 설치 기록 삭제. 가입자의 회원 탈퇴와 구분해 확인한다. */
export function DeviceDataSection() {
  const { signedIn, busy } = useAccount()
  const [confirming, setConfirming] = useState(false)
  const [message, setMessage] = useState<string | null>(null)
  if (!isGuestDeleteVisible(signedIn)) return null
  return (
    <>
      <ListSection footer={tr('이 기기의 발자취도 함께 지워져요.')}>
        <Row inset={16} disabled={busy} onClick={() => setConfirming(true)}>
          <div style={{ display: 'flex', alignItems: 'center', width: '100%' }}>
            <Txt size={15} color="red" style={{ flex: 1 }}>{tr('이 기기의 기록 지우기')}</Txt>
            {busy && <Spinner />}
          </div>
        </Row>
      </ListSection>
      <Alert
        open={confirming}
        title={tr('이 기기의 기록을 지울까요?')}
        message={tr('이 기기의 장바구니·코스·찜이 모두 지워지고 되돌릴 수 없어요.')}
        buttons={[
          { label: tr('지우기'), role: 'destructive', onPress: () => void accountActions.deleteDeviceData().then((o) => setMessage(deviceDeleteMessage(o))) },
          { label: tr('취소'), role: 'cancel' },
        ]}
        onClose={() => setConfirming(false)}
      />
      <Alert open={message !== null} title={message ?? ''} buttons={[{ label: tr('확인'), role: 'cancel' }]} onClose={() => setMessage(null)} />
    </>
  )
}

/** 자리를 미리 보여 준다 — 없는 척하다 갑자기 생기는 것보다 「여기 온다」가 보이는 쪽이 낫다. */
export function SoonSection() {
  return (
    <ListSection header={tr('준비 중')}>
      <Row icon="bell" tint="var(--system-gray)" title={tr('알림')} value={tr('준비 중')} dim />
    </ListSection>
  )
}

export function InstallIdFooter() {
  return (
    <Txt as="div" size={11} color="tertiary" align="center" style={{ marginTop: 36, lineHeight: '13px' }}>
      {tr('설치 식별자 %@…', installId().slice(0, 8).toUpperCase())}
    </Txt>
  )
}
