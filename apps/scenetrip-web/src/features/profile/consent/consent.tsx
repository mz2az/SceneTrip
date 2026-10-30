/*
 * AI·위치 동의 — 훅과 시트 (iOS Models/AIConsent.swift · AIConsentSheet.swift · LocationConsent.swift · LocationConsentSheet.swift).
 *
 * 다른 화면에서 쓰는 법:
 *
 *   const ai = useAIConsent()
 *   <button onClick={() => ai.request(() => startGuideChat())}>…</button>   // 동의돼 있으면 곧장, 아니면 시트 → 「허용하고 계속」 뒤에 실행
 *   <AIConsentSheet gate={ai.gate} />                                          // 화면 어딘가에 한 번
 *
 *   const location = useLocationConsent()
 *   location.request(() => navigator.geolocation.getCurrentPosition(…))
 *   <LocationConsentSheet gate={location.gate} onCancel={() => …} />           // 취소·바깥 눌러 닫기에서 `onCancel`
 *
 * 두 동의는 따로 저장된다(state/consent.ts). 문(`gate`)은 훅을 부른 화면마다 하나 — 시트가 두 군데서 겹쳐 뜨지 않는다.
 */
import { useMemo, useRef, useSyncExternalStore } from 'react'
import { tr } from '@/i18n'
import { type ConsentGate, type ConsentStore, aiConsentStore, createConsentGate, locationConsentStore } from '@/state/consent'
import { LEGAL_LINKS } from '../legalLinks'
import { ConsentSheet } from './ConsentSheet'

export interface UseConsent {
  gate: ConsentGate
  /** 지금 동의돼 있는가(저장된 버전이 현재 안내와 같다). */
  allowed: boolean
  request: ConsentGate['request']
  /** 설정 토글용 — 켜면 안내를 읽고 허용해야 하고, 끄면 곧장 철회. */
  setEnabled: ConsentGate['setEnabled']
}

function useConsent(store: ConsentStore): UseConsent {
  const gate = useMemo(() => createConsentGate(store), [store])
  const allowed = useSyncExternalStore(store.subscribe, store.isAllowed)
  return { gate, allowed, request: gate.request, setEnabled: gate.setEnabled }
}

export const useAIConsent = (): UseConsent => useConsent(aiConsentStore)
export const useLocationConsent = (): UseConsent => useConsent(locationConsentStore)

function useGateOpen(gate: ConsentGate): boolean {
  return useSyncExternalStore(gate.subscribe, () => gate.state().isPresented)
}

/** AI 가이드·AI 코스 만들기가 입력·대화·코스 맥락을 해외로 보낸다는 안내. 문장은 iOS `AIConsentSheet.disclosure` 와 같다. */
export function AIConsentSheet({ gate }: { gate: ConsentGate }) {
  const open = useGateOpen(gate)
  return (
    <ConsentSheet
      open={open}
      title={tr('AI 기능 사용 안내')}
      disclosure={tr('AI 가이드와 AI 코스 만들기는 입력한 내용, 대화, 관심 작품·여행 조건·코스 맥락을 미국 오리건에 있는 Amazon Bedrock(AWS)으로 보내 답변과 일정을 만듭니다. 이름·이메일은 보내지 않아요. AI 답변은 틀릴 수 있어요.')}
      linkTitle={tr('개인정보 처리방침')}
      link={LEGAL_LINKS.privacy}
      allowTitle={tr('허용하고 계속')}
      identifier="ai-consent"
      allow={gate.allow}
      cancel={gate.cancel}
      swipeDismiss={gate.swipeDismiss}
      onClosed={gate.didDismiss}
    />
  )
}

/** 위치기반서비스 이용 동의 — iOS 권한과 별개다. 브라우저의 위치 권한 창은 이 동의 뒤에 뜬다. */
export function LocationConsentSheet({ gate, onCancel }: { gate: ConsentGate; onCancel?: () => void }) {
  const open = useGateOpen(gate)
  const approved = useRef(false)
  return (
    <ConsentSheet
      open={open}
      title={tr('위치기반서비스 이용 동의')}
      disclosure={tr('현재 위치를 주변 촬영지·편의시설 찾기, AI 가이드, 길 안내에 씁니다. 위치는 요청을 처리하는 데만 쓰고 바로 지우며, 길 안내 때는 경로 계산을 위해 카카오에 보냅니다.')}
      linkTitle={tr('위치기반서비스 이용약관')}
      link={LEGAL_LINKS.locationServices}
      allowTitle={tr('동의하고 계속')}
      identifier="location-consent"
      allow={() => {
        approved.current = true
        gate.allow()
      }}
      cancel={gate.cancel}
      swipeDismiss={gate.swipeDismiss}
      onClosed={() => {
        const cancelled = !approved.current
        approved.current = false
        gate.didDismiss()
        if (cancelled) onCancel?.()
      }}
    />
  )
}
