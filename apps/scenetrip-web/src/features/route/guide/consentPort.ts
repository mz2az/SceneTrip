/*
 * 「동의가 필요하다」는 분기 — iOS Models/AIConsent.swift (AIConsentStore · AIConsentGate) 와 Models/LocationConsent.swift.
 *
 * AI 동의 시트와 위치 동의 시트 자체는 마이페이지 담당이 `features/profile/consent/` 에 만든다. 이쪽은 「동의했는가」를 읽고, 안 했으면 시트를 올려 달라고
 * 부탁하고, 허용되면 이어서 하고, 취소하면 그만두는 문(`ConsentGate`)만 든다. 시트는 `setConsentPresenter` 로 연결한다 — 연결이 없으면 시트를 올릴 수
 * 없으니 동의가 안 된 채로는 아무것도 하지 않는다(AI·위치 전송은 동의 없이 나가지 않는다).
 *
 * 저장 키는 iOS 와 같다(`scenetrip.aiConsentVersion`·`scenetrip.locationConsentVersion`, 현재 버전 1 — 안내 내용이 바뀌면 올린다. 이전 버전의
 * 허용은 새 안내의 동의가 아니다).
 */
import { readStorage, writeStorage } from '@/state/storage'

export type ConsentKind = 'ai' | 'location'

const KEYS: Record<ConsentKind, string> = { ai: 'scenetrip.aiConsentVersion', location: 'scenetrip.locationConsentVersion' }
export const CONSENT_VERSION = 1

export const isConsentAllowed = (kind: ConsentKind): boolean => Number(readStorage(KEYS[kind])) === CONSENT_VERSION
export const allowConsent = (kind: ConsentKind): void => writeStorage(KEYS[kind], String(CONSENT_VERSION))
export const revokeConsent = (kind: ConsentKind): void => writeStorage(KEYS[kind], '0')

/** 동의 시트를 올리는 쪽(마이페이지 담당). 허용하면 `allow()`, 닫거나 취소하면 `cancel()` 을 부른다. */
export type ConsentPresenter = (kind: ConsentKind, resolve: { allow: () => void; cancel: () => void }) => void

let presenter: ConsentPresenter | null = null
export function setConsentPresenter(next: ConsentPresenter | null): void {
  presenter = next
}

/** 화면마다 대기 중 동작만 들고, 동의 자체는 공통 저장소에서 읽는다 (AIConsentGate). */
export interface ConsentGate {
  isAllowed(): boolean
  /** 허용돼 있으면 바로 `action`. 아니면 시트를 올리고, 허용하면 `action`, 취소하면 `onCancel`. 대기 중이면 새 요청은 무시한다. */
  request(action: () => void, onCancel?: () => void): void
  /** 대기 중인 요청을 버린다(화면을 떠날 때). */
  cancel(): void
}

export function createConsentGate(kind: ConsentKind): ConsentGate {
  let pending = false
  let ticket = 0
  return {
    isAllowed: () => isConsentAllowed(kind),
    request(action, onCancel) {
      if (pending) return
      if (isConsentAllowed(kind)) return action()
      if (!presenter) return onCancel?.()
      pending = true
      const mine = (ticket += 1)
      presenter(kind, {
        allow: () => {
          if (mine !== ticket || !pending) return
          pending = false
          allowConsent(kind)
          action()
        },
        cancel: () => {
          if (mine !== ticket || !pending) return
          pending = false
          onCancel?.()
        },
      })
    },
    cancel() {
      pending = false
      ticket += 1
    },
  }
}

/** 기기 위치는 위치 동의가 있어야 쓴다. 사용자가 지도에서 고른 자리(`userSelected`)는 동의 없이 된다 (AIConsentGate.requestLocation). */
export function requestLocationFor(gate: ConsentGate, source: 'device' | 'userSelected', action: () => void, onCancel?: () => void): void {
  if (source === 'device') gate.request(action, onCancel)
  else action()
}
