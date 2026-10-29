/*
 * 마이페이지 「차단한 사용자」 — 목록 받기와 풀기 (iOS `BlockedUsersStore`, Models/Moderation.swift).
 *
 *   const blocked = useBlockedUsers()          // { items, phase, unblocking, message, reload, unblock }
 *   useEffect(() => { void blocked.reload() }, [])
 */
import { useMemo } from 'react'
import { ApiFailure } from '@/api/failure'
import { tr } from '@/i18n'
import { createStore, useStore } from '@/state/storage'
import { type ModerationClient, serverModerationClient } from './client'
import { type ModerationEvents, moderationEvents } from './events'
import type { BlockedUser } from './rules'

export type BlockedPhase = 'loading' | 'loaded' | 'failed'

export interface BlockedUsersState {
  items: readonly BlockedUser[]
  phase: BlockedPhase
  /** 풀고 있는 줄 — 단추를 잠근다. */
  unblocking: ReadonlySet<number>
  message: string | null
}

export interface BlockedUsersStore {
  state(): BlockedUsersState
  subscribe(listener: () => void): () => void
  reload(): Promise<void>
  /** 풀었으면 `true`. 이미 없는 줄(`BLOCK_NOT_FOUND`)도 풀린 것이다 — 목록에서 뺀다. */
  unblock(user: BlockedUser): Promise<boolean>
  dismissMessage(): void
}

export function createBlockedUsersStore(deps: { client?: ModerationClient; events?: ModerationEvents } = {}): BlockedUsersStore {
  const client = deps.client ?? serverModerationClient
  const events = deps.events ?? moderationEvents
  const store = createStore<BlockedUsersState>({ items: [], phase: 'loading', unblocking: new Set(), message: null })
  const patch = (p: Partial<BlockedUsersState>) => store.set((s) => ({ ...s, ...p }))
  /** 이 화면에서 푼 줄을 푼 차례대로 — 풀기 전에 떠난 목록 응답이 늦게 와서 푼 줄을 되살리지 않게 한다. */
  const unblockedLog: number[] = []

  return {
    state: store.get,
    subscribe: store.subscribe,
    async reload() {
      if (store.get().items.length === 0) patch({ phase: 'loading' })
      const mark = unblockedLog.length
      try {
        const fresh = await client.blocks()
        // 받는 사이 푼 줄은 늦은 응답에 남아 있어도 빼 둔다.
        const gone = new Set(unblockedLog.slice(mark))
        patch({ items: fresh.filter((u) => !gone.has(u.id)), phase: 'loaded' })
      } catch {
        // 받아 둔 목록이 있으면 그대로 둔다.
        if (store.get().phase !== 'loaded') patch({ phase: 'failed' })
      }
    },
    async unblock(user) {
      if (store.get().unblocking.has(user.id)) return false
      patch({ unblocking: new Set([...store.get().unblocking, user.id]) })
      try {
        try {
          await client.unblock(user.id)
        } catch (error) {
          // 다른 기기에서 이미 풀었다.
          if (!(error instanceof ApiFailure && error.apiCode === 'BLOCK_NOT_FOUND')) {
            patch({ message: tr('차단을 풀지 못했어요. 잠시 뒤 다시 해 주세요') })
            return false
          }
        }
        unblockedLog.push(user.id)
        patch({ items: store.get().items.filter((u) => u.id !== user.id) })
        events.changed()
        return true
      } finally {
        const next = new Set(store.get().unblocking)
        next.delete(user.id)
        patch({ unblocking: next })
      }
    },
    dismissMessage: () => patch({ message: null }),
  }
}

export interface UseBlockedUsers extends BlockedUsersState {
  reload(): Promise<void>
  unblock(user: BlockedUser): Promise<boolean>
  dismissMessage(): void
}

/** 화면 하나가 하나 든다. */
export function useBlockedUsers(): UseBlockedUsers {
  const blocked = useMemo(() => createBlockedUsersStore(), [])
  const state = useStore({ get: blocked.state, set: () => {}, subscribe: blocked.subscribe }, (s) => s)
  return { ...state, reload: blocked.reload, unblock: blocked.unblock, dismissMessage: blocked.dismissMessage }
}
