/*
 * 브라우저 저장소와 작은 외부 스토어 도우미.
 *
 * iOS 의 UserDefaults/Keychain 에 해당하는 자리는 localStorage 다. 모든 읽기/쓰기는 try/catch 로 감싼다 —
 * 사생활 보호 창이나 저장소 차단에서는 던질 수 있고, 그때도 앱은 그려져야 한다(그 실행 동안만 메모리에 둔다).
 * 키는 iOS 와 같이 `scenetrip.` 접두를 쓴다.
 */
import { useSyncExternalStore } from 'react'

const memory = new Map<string, string>()

export function readStorage(key: string): string | null {
  try {
    return localStorage.getItem(key)
  } catch {
    return memory.get(key) ?? null
  }
}

export function writeStorage(key: string, value: string): void {
  try {
    localStorage.setItem(key, value)
  } catch {
    memory.set(key, value)
  }
}

export function removeStorage(key: string): void {
  try {
    localStorage.removeItem(key)
  } catch {
    memory.delete(key)
  }
}

export function readJSON<T>(key: string, fallback: T): T {
  const raw = readStorage(key)
  if (raw === null) return fallback
  try {
    return JSON.parse(raw) as T
  } catch {
    return fallback
  }
}

export function writeJSON(key: string, value: unknown): void {
  writeStorage(key, JSON.stringify(value))
}

/**
 * `ObservableObject` 대용의 작은 스토어. 상태는 불변 객체로 두고 `set` 으로 통째로 바꾼다.
 *
 *   const cart = createStore({ ids: new Set<number>() })
 *   const ids = useStore(cart, (s) => s.ids)
 */
export interface Store<S> {
  get(): S
  set(next: S | ((prev: S) => S)): void
  subscribe(listener: () => void): () => void
}

export function createStore<S>(initial: S): Store<S> {
  let state = initial
  const listeners = new Set<() => void>()
  return {
    get: () => state,
    set(next) {
      const value = typeof next === 'function' ? (next as (p: S) => S)(state) : next
      if (Object.is(value, state)) return
      state = value
      listeners.forEach((l) => l())
    },
    subscribe(listener) {
      listeners.add(listener)
      return () => listeners.delete(listener)
    },
  }
}

export function useStore<S, R>(store: Store<S>, select: (s: S) => R): R {
  return useSyncExternalStore(store.subscribe, () => select(store.get()))
}
