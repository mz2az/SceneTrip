/*
 * 가이드·길찾기 담당의 동의 문(`features/route/guide/consentPort.ts`)이 시트를 올려 달라고 부탁하는 자리 — `setConsentPresenter`.
 * 문이 `presenter(kind, { allow, cancel })` 을 부르면 여기서 AI·위치 동의 시트를 올리고, 「허용하고 계속」 이면 시트가 닫힌 뒤 `allow()`,
 * 취소·바깥 눌러 닫기면 `cancel()` 을 부른다(iOS `AIConsentGate.didDismiss` 와 같은 순서 — 시트가 내려간 뒤에 이어서 한다).
 * `allow()` 가 동의를 저장한다(문이 `scenetrip.aiConsentVersion` / `locationConsentVersion` 을 쓴다 — 우리 저장소와 같은 키·버전).
 */
import { useEffect, useRef, useState } from 'react'
import { type ConsentKind, type ConsentPresenter, setConsentPresenter } from '@/features/route/guide/consentPort'
import { tr } from '@/i18n'
import { LEGAL_LINKS } from '../legalLinks'
import { ConsentSheet } from './ConsentSheet'

interface Pending {
  kind: ConsentKind
  resolve: { allow: () => void; cancel: () => void }
}

export function ConsentPresenterHost() {
  const [pending, setPending] = useState<Pending | null>(null)
  const [open, setOpen] = useState(false)
  const approved = useRef(false)
  const current = useRef<Pending | null>(null)

  useEffect(() => {
    const present: ConsentPresenter = (kind, resolve) => {
      current.current = { kind, resolve }
      approved.current = false
      setPending({ kind, resolve })
      setOpen(true)
    }
    setConsentPresenter(present)
    return () => setConsentPresenter(null)
  }, [])

  const finish = () => {
    const p = current.current
    current.current = null
    setPending(null)
    if (!p) return
    if (approved.current) p.resolve.allow()
    else p.resolve.cancel()
    approved.current = false
  }

  const kind = pending?.kind ?? 'ai'
  const ai = kind === 'ai'
  return (
    <ConsentSheet
      open={open}
      title={ai ? tr('AI 기능 사용 안내') : tr('위치기반서비스 이용 동의')}
      disclosure={
        ai
          ? tr('AI 가이드와 AI 코스 만들기는 입력한 내용, 대화, 관심 작품·여행 조건·코스 맥락을 미국 오리건에 있는 Amazon Bedrock(AWS)으로 보내 답변과 일정을 만듭니다. 이름·이메일은 보내지 않아요. AI 답변은 틀릴 수 있어요.')
          : tr('현재 위치를 주변 촬영지·편의시설 찾기, AI 가이드, 길 안내에 씁니다. 위치는 요청을 처리하는 데만 쓰고 바로 지우며, 길 안내 때는 경로 계산을 위해 카카오에 보냅니다.')
      }
      linkTitle={ai ? tr('개인정보 처리방침') : tr('위치기반서비스 이용약관')}
      link={ai ? LEGAL_LINKS.privacy : LEGAL_LINKS.locationServices}
      allowTitle={ai ? tr('허용하고 계속') : tr('동의하고 계속')}
      identifier={ai ? 'ai-consent' : 'location-consent'}
      allow={() => {
        approved.current = true
        setOpen(false)
      }}
      cancel={() => setOpen(false)}
      swipeDismiss={() => setOpen(false)}
      onClosed={finish}
    />
  )
}
