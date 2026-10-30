/*
 * 리뷰 쓰기·고치기 (MZ2AZ-363). 별점은 필수, 글은 선택이다 — 별점만 남겨도 된다. 출처: apps/scenetrip-ios/src/Reviews/ReviewComposeView.swift
 *
 * 한 대상에 한 개라, 열 때 내 리뷰를 물어(`GET …/reviews/me`) 있으면 그것을 고치는 화면이 된다. 가입한 사람만 쓴다 — 이 화면은 로그인한 뒤에만 뜬다.
 * 사진은 10장까지 — 고르는 즉시 줄여서 올리고(`ReviewPhotoDraft`), 저장할 때 그 키를 화면 순서대로 보낸다. 고칠 때는 붙어 있던 사진이 보이고 뺄 수 있다
 * (계약: 보낸 `photoKeys` 가 전부다). 올리는 중이거나 못 올린 사진이 있으면 저장하지 못한다 — 사진이 말없이 빠진 리뷰를 만들지 않는다.
 * 쓰기(PUT/DELETE)는 호출 경로만 두고 검증은 모의 시험으로 한다.
 */
import { useEffect, useMemo, useRef, useState } from 'react'
import { ApiFailure } from '@/api/client'
import { Icon } from '@/design/Icon'
import { Alert, Sheet } from '@/design/overlay'
import { HStack, Pressable, Spinner, Txt, VStack, rr } from '@/design/primitives'
import { tr } from '@/i18n'
import { useAccount } from '@/features/profile/auth/account'
import { FONT } from '@/features/search/ui/common'
import { accountGeneration } from '@/state/shared'
import { useStore } from '@/state/storage'
import { StarPicker } from './RatingViews'
import { type Review, type ReviewSubject, deleteMyReview, fetchMyReview, saveMyReview } from './reviewApi'
import { reviewFailureText } from './reviewFailure'
import { ReviewPhotoDraft } from './reviewPhotoDraft'
import { ReviewPhotoStrip } from './ReviewPhotoStrip'
import { BODY_LIMIT, bodyLength, canSave, changed, landed, normalizedBody } from './reviewRules'
import { useMountedWhile } from './useMounted'

export function ReviewCompose(props: { open: boolean; onClose: () => void; subject: ReviewSubject; title: string; onChanged: () => void }) {
  const mounted = useMountedWhile(props.open)
  return mounted ? <ComposeSheet {...props} /> : null
}

function ComposeSheet({ open, onClose, subject, title, onChanged }: { open: boolean; onClose: () => void; subject: ReviewSubject; title: string; onChanged: () => void }) {
  const account = useAccount()
  const opened = useRef({ epoch: accountGeneration(), me: account.me?.id ?? null })
  const draftStore = useMemo(() => new ReviewPhotoDraft('review'), [])
  const photos = useStore(draftStore.store, (s) => s)
  const [phase, setPhase] = useState<'loading' | 'ready' | 'failed'>('loading')
  const [existing, setExisting] = useState<Review | null>(null)
  const [rating, setRating] = useState(0)
  const [text, setText] = useState('')
  const [working, setWorking] = useState(false)
  const [message, setMessage] = useState<string | null>(null)
  const [confirmingDelete, setConfirmingDelete] = useState(false)
  const [confirmingDiscard, setConfirmingDiscard] = useState(false)

  /** 열었을 때와 같은 계정인가 — 요청이 끝날 때까지 함께 확인한다. */
  const isCurrent = () => account.signedIn && accountGeneration() === opened.current.epoch && (opened.current.me === null || account.me?.id === opened.current.me)

  const dirty = (() => {
    if (phase !== 'ready') return false
    if (existing === null) return rating !== 0 || normalizedBody(text) !== null || draftStore.changed
    return changed(existing, rating, text) || draftStore.changed
  })()
  void photos
  const savable = isCurrent() && !working && dirty && canSave(rating, text) && draftStore.canSave

  const load = async () => {
    try {
      const mine = await fetchMyReview(subject)
      setExisting(mine)
      setRating(mine?.rating ?? 0)
      setText(mine?.body ?? '')
      draftStore.start(mine?.photos ?? [])
      setPhase('ready')
    } catch {
      if (isCurrent()) setPhase('failed')
    }
  }
  useEffect(() => {
    void load()
    // eslint-disable-next-line react-hooks/exhaustive-deps -- 열 때 한 번.
  }, [])
  // 화면이 사라지면(저장·버리기·닫기) 올리던 사진을 그만둔다. 이미 올라간 것은 서버에 있어 영향이 없다.
  useEffect(() => () => draftStore.dispose(), [draftStore])
  // 계정이 바뀌었다 — 닫는다.
  useEffect(() => {
    if (!isCurrent()) {
      draftStore.cancel()
      onClose()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [account.epoch, account.signedIn])

  const requestClose = () => {
    if (working) return
    if (dirty) setConfirmingDiscard(true)
    else onClose()
  }

  const finish = () => {
    onChanged()
    onClose()
  }

  const save = async () => {
    if (!savable) return
    setWorking(true)
    setMessage(null)
    const body = normalizedBody(text)
    // 화면에 남아 있는 사진이 전부다 — 붙어 있던 것은 그 키로, 새로 올린 것은 새 키로, 보이는 순서대로.
    const keys = draftStore.keys
    try {
      await saveMyReview(subject, rating, body, keys)
      if (isCurrent()) finish()
    } catch (error) {
      if (!isCurrent()) return
      if (!(error instanceof ApiFailure) || error.apiCode !== 'REVIEW_PHOTO_INVALID') {
        setMessage(reviewFailureText(error))
        return
      }
      // 서버가 사진 키를 거절했다. 먼저 이미 저장된 것이 아닌지 본다 — 앞선 요청이 응답만 잃고 저장됐으면 다시 보낸 이 요청은 거절된다.
      const mine = await fetchMyReview(subject).catch(() => null)
      if (mine && landed(mine, rating, body, keys)) {
        finish()
        return
      }
      // 정말 거절이다 — 이번에 올린 사진을 「다시 시도」 로 돌려놓는다.
      draftStore.rejectUploaded()
      setMessage(reviewFailureText(error))
    } finally {
      setWorking(false)
    }
  }
  const remove = async () => {
    if (!isCurrent() || working || existing === null) return
    setWorking(true)
    setMessage(null)
    try {
      await deleteMyReview(subject)
      if (isCurrent()) finish()
    } catch (error) {
      if (isCurrent()) setMessage(reviewFailureText(error))
    } finally {
      setWorking(false)
    }
  }

  const count = bodyLength(text)

  return (
    <Sheet open={open} onClose={requestClose} dismissOnScrim={!dirty && !working} label={existing === null ? tr('리뷰 쓰기') : tr('리뷰 고치기')}>
      <Header existing={existing !== null} title={title} working={working} savable={savable} onCancel={requestClose} onSave={() => void save()} />
      <div className="hair" style={{ flex: 'none' }} />
      {/* 오류는 머리줄 바로 아래에 고정한다 — 글이 길면 본문 아래 줄은 화면 밖으로 밀린다. */}
      {message && (
        <div role="alert" style={{ background: 'var(--system-red)', padding: '9px 16px', flex: 'none' }}>
          <Txt size={FONT.footnote} color="white">{message}</Txt>
        </div>
      )}
      {phase === 'loading' && <div style={{ flex: 1, display: 'grid', placeItems: 'center' }}><Spinner size={20} /></div>}
      {phase === 'failed' && (
        <VStack align="center" justify="center" style={{ flex: 1 }}>
          <Txt size={FONT.subheadline} weight="medium">{tr('리뷰를 불러오지 못했어요')}</Txt>
          <Pressable plain onClick={() => (setPhase('loading'), void load())} style={{ marginTop: 8 }}>
            <Txt size={FONT.subheadline} color="accent">{tr('다시 시도')}</Txt>
          </Pressable>
        </VStack>
      )}
      {phase === 'ready' && (
        <div className="scroll-y" style={{ flex: 1, minHeight: 0 }}>
          <VStack spacing={18} pad="0 16px 24px">
            <VStack spacing={8} align="center" style={{ paddingTop: 22 }}>
              <StarPicker rating={rating} onChange={setRating} />
              <Txt size={FONT.footnote} color="secondary">{rating === 0 ? tr('별을 눌러 점수를 주세요') : tr('%d점', rating)}</Txt>
            </VStack>
            <VStack spacing={6}>
              <div style={{ position: 'relative', padding: 8, background: 'var(--secondary-system-background)', ...rr(12) }}>
                {/* 높이에 상한을 둔다 — 글만큼 계속 커지면 글자 수와 지우기 단추가 화면 밖으로 밀린다. */}
                <textarea
                  value={text}
                  onChange={(e) => setText(e.target.value)}
                  placeholder={tr('어땠는지 들려주세요 (선택)')}
                  aria-label={tr('리뷰 글')}
                  className="st-search-input"
                  style={{ display: 'block', width: '100%', minHeight: 150, maxHeight: 260, resize: 'none', border: 0, outline: 0, background: 'transparent', fontSize: FONT.body, padding: '0 5px', lineHeight: 1.3 }}
                />
              </div>
              <HStack justify="between">
                {count > BODY_LIMIT ? <Txt size={FONT.footnote} color="red">{tr('글이 너무 길어요')}</Txt> : <span />}
                <Txt size={FONT.caption2} mono color={count > BODY_LIMIT ? 'red' : 'tertiary'}>{`${count}/${BODY_LIMIT}`}</Txt>
              </HStack>
            </VStack>
            <ReviewPhotoStrip draft={draftStore} locked={working} />
            {existing !== null && (
              <Pressable plain disabled={working} onClick={() => setConfirmingDelete(true)} style={{ paddingTop: 4, alignSelf: 'flex-start', opacity: working ? 0.4 : 1 }}>
                <HStack spacing={6}>
                  <Icon name="trash" size={FONT.subheadline} color="red" />
                  <Txt size={FONT.subheadline} color="red">{tr('리뷰 지우기')}</Txt>
                </HStack>
              </Pressable>
            )}
          </VStack>
        </div>
      )}
      {/* 경고창으로 묻는다 — iOS 26 의 선택 팝업은 취소 단추를 숨겨 「버리기」 하나만 보였다. */}
      <Alert
        open={confirmingDiscard}
        title={tr('쓰던 리뷰를 버릴까요?')}
        buttons={[{ label: tr('버리기'), role: 'destructive', onPress: onClose }, { label: tr('계속 쓰기'), role: 'cancel' }]}
        onClose={() => setConfirmingDiscard(false)}
      />
      <Alert
        open={confirmingDelete}
        title={tr('리뷰를 지울까요?')}
        message={tr('지우면 되돌릴 수 없어요.')}
        buttons={[{ label: tr('지우기'), role: 'destructive', onPress: () => void remove() }, { label: tr('취소'), role: 'cancel' }]}
        onClose={() => setConfirmingDelete(false)}
      />
    </Sheet>
  )
}

function Header({ existing, title, working, savable, onCancel, onSave }: { existing: boolean; title: string; working: boolean; savable: boolean; onCancel: () => void; onSave: () => void }) {
  return (
    <div style={{ position: 'relative', flex: 'none', minHeight: 52 }}>
      <VStack spacing={1} align="center" justify="center" style={{ padding: '6px 76px', minHeight: 52 }}>
        <Txt size={FONT.headline} weight="semibold">{existing ? tr('리뷰 고치기') : tr('리뷰 쓰기')}</Txt>
        <Txt size={FONT.caption} color="secondary" lines={2} align="center">{title}</Txt>
      </VStack>
      {/* 단추는 머리줄이 길어져도 첫 52 의 가운데 — 늘 같은 자리다. */}
      <HStack justify="between" pad="0 16px" style={{ position: 'absolute', left: 0, right: 0, top: 0, height: 52 }}>
        <Pressable plain disabled={working} onClick={onCancel}>
          <Txt size={FONT.body} color="accent" style={{ opacity: working ? 0.4 : 1 }}>{tr('취소')}</Txt>
        </Pressable>
        <Pressable plain disabled={!savable} onClick={onSave} aria-label={working ? tr('저장 중') : undefined}>
          {working ? <Spinner size={20} /> : <Txt size={FONT.body} weight="semibold" color="accent" style={{ opacity: savable ? 1 : 0.4 }}>{tr('저장')}</Txt>}
        </Pressable>
      </HStack>
    </div>
  )
}
