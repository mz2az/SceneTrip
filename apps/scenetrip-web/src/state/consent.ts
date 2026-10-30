/*
 * AI·위치 동의 — iOS `VersionedConsentStore` · `AIConsentStore` · `LocationConsentStore` · `AIConsentGate`
 * (Models/AIConsent.swift, Models/LocationConsent.swift, MZ2AZ-468).
 *
 * 두 동의는 같은 모양이고 따로 저장된다. 저장하는 값은 「동의한 안내의 버전」 하나다 — 안내 내용이 바뀌면 버전을 올려
 * 이전 버전의 허용이 새 안내의 동의가 되지 않게 한다. 기기(브라우저)에만 남는다.
 *
 * 쓰는 법 (검색·가이드 담당):
 *   const location = useLocationConsent()              // features/profile/consent
 *   location.request(() => navigator.geolocation…)     // 동의돼 있으면 곧장 실행, 아니면 시트를 올리고 허용 뒤 실행
 *   <LocationConsentSheet gate={location.gate} onCancel={…} />
 * 훅 없이 값만 볼 때는 `aiConsentStore.isAllowed()` / `locationConsentStore.isAllowed()`.
 */
import { createStore, readStorage, writeStorage } from './storage'

/** 동의 저장소를 바꿔 끼워 시험한다(iOS `AIConsentStorage`). */
export interface ConsentStorage {
  integer(key: string): number
  set(value: number, key: string): void
}

export function browserConsentStorage(): ConsentStorage {
  return {
    integer: (key) => Number(readStorage(key) ?? 0) || 0,
    set: (value, key) => writeStorage(key, String(value)),
  }
}

export function memoryConsentStorage(): ConsentStorage {
  const map = new Map<string, number>()
  return { integer: (key) => map.get(key) ?? 0, set: (value, key) => void map.set(key, value) }
}

export interface ConsentStore {
  isAllowed(): boolean
  allow(): void
  revoke(): void
  subscribe(listener: () => void): () => void
  readonly version: number
}

export function createConsentStore(storage: ConsentStorage, key: string, version: number): ConsentStore {
  if (version <= 0) throw new Error('version must be positive')
  const listeners = new Set<() => void>()
  const save = (value: number) => {
    storage.set(value, key)
    listeners.forEach((l) => l())
  }
  return {
    version,
    isAllowed: () => storage.integer(key) === version,
    allow: () => save(version),
    revoke: () => save(0),
    subscribe(listener) {
      listeners.add(listener)
      return () => listeners.delete(listener)
    },
  }
}

export const AI_CONSENT_KEY = 'scenetrip.aiConsentVersion'
export const LOCATION_CONSENT_KEY = 'scenetrip.locationConsentVersion'
/** 안내 내용이 바뀌면 올린다. 이전 버전의 허용은 새 안내의 동의가 아니다. */
export const AI_CONSENT_VERSION = 1
export const LOCATION_CONSENT_VERSION = 1

export const aiConsentStore = createConsentStore(browserConsentStorage(), AI_CONSENT_KEY, AI_CONSENT_VERSION)
export const locationConsentStore = createConsentStore(browserConsentStorage(), LOCATION_CONSENT_KEY, LOCATION_CONSENT_VERSION)

// ---------------------------------------------------------------- 시트를 올리는 문 (iOS `AIConsentGate`)

export interface ConsentGate {
  state(): { isPresented: boolean }
  subscribe(listener: () => void): () => void
  /** 동의돼 있으면 곧장 `action`, 아니면 시트를 올리고 대기 동작을 든다. 이미 대기 중이면 무시. */
  request(action: () => void): void
  /** 「허용하고 계속」 — 동의를 적고 시트를 내린다. 대기 동작은 시트가 닫힌 뒤(`didDismiss`) 한 번 돈다. */
  allow(): void
  /** 「취소」. */
  cancel(): void
  /** 끌어 내려 닫기(바깥 눌러 닫기). 대기 동작은 취소로 본다 — `didDismiss` 가 정리한다. */
  swipeDismiss(): void
  /** 시트가 닫힌 뒤 부른다. 명시적으로 허용한 경우에만 대기 동작을 한 번 돌린다. */
  didDismiss(): void
  /** 설정 토글 — 켤 때도 안내를 읽고 허용해야 한다. 끄면 곧장 철회. */
  setEnabled(enabled: boolean): void
  readonly store: ConsentStore
}

export function createConsentGate(store: ConsentStore): ConsentGate {
  const view = createStore({ isPresented: false })
  let pending: (() => void) | null = null
  let approved = false
  const present = (on: boolean) => view.set({ isPresented: on })

  const gate: ConsentGate = {
    store,
    state: view.get,
    subscribe: view.subscribe,
    request(action) {
      if (pending !== null) return
      if (!store.isAllowed()) {
        pending = action
        approved = false
        present(true)
        return
      }
      action()
    },
    allow() {
      if (!view.get().isPresented || pending === null) return
      store.allow()
      approved = true
      present(false)
    },
    cancel() {
      pending = null
      approved = false
      present(false)
    },
    swipeDismiss() {
      present(false)
    },
    didDismiss() {
      const action = approved && store.isAllowed() ? pending : null
      gate.cancel()
      action?.()
    },
    setEnabled(enabled) {
      if (enabled) {
        gate.request(() => {})
      } else {
        gate.cancel()
        store.revoke()
      }
    },
  }
  return gate
}

// 문은 화면마다 하나씩 만든다(iOS 도 화면마다 `AIConsentGate` 를 든다) — 시트가 두 군데서 겹쳐 뜨지 않게. 동의 값만 위 저장소를 같이 쓴다.
