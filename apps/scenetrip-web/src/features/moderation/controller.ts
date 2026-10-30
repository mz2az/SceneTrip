/*
 * 신고·차단 흐름 — iOS `ModerationController` · `ReportSheetPresence` (Models/Moderation.swift).
 *
 * 목록 화면 하나가 컨트롤러를 들고, 줄의 「⋯」 가 부른다 — 신고 시트·차단 확인 창·안내를 화면 한 곳에 단다
 * (`<ModerationSheets>`). 지연 목록의 줄마다 시트를 달면 줄이 사라질 때 같이 사라진다.
 */
import { useMemo } from 'react'
import { authStore, promptSignIn as authPromptSignIn } from '@/state/auth'
import { type Store, createStore, useStore } from '@/state/storage'
import { type ModerationClient, serverModerationClient } from './client'
import { type ModerationEvents, moderationEvents } from './events'
import {
  type BlockOutcome,
  type ModerationTarget,
  type ReportOutcome,
  type ReportReason,
  blockMessage,
  blockOutcome,
  reportDetail,
  reportOutcome,
} from './rules'

export interface ModerationState {
  /** 신고 시트에 띄운 글. */
  reporting: ModerationTarget | null
  /** 차단 확인 창에 띄운 글. */
  confirmingBlock: ModerationTarget | null
  /** 차단하지 못한 까닭. */
  notice: string | null
  sending: boolean
}

export interface ModerationDeps {
  client?: ModerationClient
  signedIn?: () => boolean
  promptSignIn?: () => void
  events?: ModerationEvents
}

export interface ModerationController {
  readonly store: Store<ModerationState>
  askReport(target: ModerationTarget): void
  /** 차단은 가입한 사람만 — 비회원이면 확인 창 대신 로그인 화면을 올린다. */
  askBlock(target: ModerationTarget): void
  report(target: ModerationTarget, reason: ReportReason, detail: string): Promise<ReportOutcome>
  /** 차단했으면 `true` — 부른 화면이 그 글을 지우고 목록을 다시 받는다. */
  block(target: ModerationTarget): Promise<boolean>
  dismissReport(): void
  dismissBlockConfirm(): void
  dismissNotice(): void
}

export function createModerationController(deps: ModerationDeps = {}): ModerationController {
  const client = deps.client ?? serverModerationClient
  const signedIn = deps.signedIn ?? (() => authStore.get().session !== null)
  const promptSignIn = deps.promptSignIn ?? authPromptSignIn
  const events = deps.events ?? moderationEvents
  const store = createStore<ModerationState>({ reporting: null, confirmingBlock: null, notice: null, sending: false })
  const patch = (p: Partial<ModerationState>) => store.set((s) => ({ ...s, ...p }))

  return {
    store,
    askReport: (target) => patch({ reporting: target }),
    askBlock(target) {
      if (!signedIn()) {
        promptSignIn()
        return
      }
      patch({ confirmingBlock: target })
    },
    async report(target, reason, detail) {
      if (store.get().sending) return 'failed'
      patch({ sending: true })
      try {
        await client.report(target, reason, reportDetail(detail))
        return 'received'
      } catch (error) {
        return reportOutcome(error)
      } finally {
        patch({ sending: false })
      }
    },
    async block(target) {
      patch({ confirmingBlock: null })
      if (!signedIn()) {
        promptSignIn()
        return false
      }
      if (store.get().sending) return false
      patch({ sending: true })
      let outcome: BlockOutcome
      try {
        await client.block(target)
        outcome = 'blocked'
      } catch (error) {
        outcome = blockOutcome(error)
      } finally {
        patch({ sending: false })
      }
      if (outcome === 'signInRequired') promptSignIn()
      patch({ notice: blockMessage(outcome) })
      if (outcome === 'blocked') events.changed()
      return outcome === 'blocked'
    },
    dismissReport: () => patch({ reporting: null }),
    dismissBlockConfirm: () => patch({ confirmingBlock: null }),
    dismissNotice: () => patch({ notice: null }),
  }
}

/** 목록 화면이 하나 들고 `<ModerationSheets>` 와 `<ModerationMenu>` 에 넘긴다. 컴포넌트 수명 동안 같은 것. */
export function useModerationController(): ModerationController {
  return useMemo(() => createModerationController(), [])
}

export function useModerationState(controller: ModerationController): ModerationState {
  return useStore(controller.store, (s) => s)
}

// ---------------------------------------------------------------- ReportSheetPresence

/**
 * 신고 시트가 떠 있는가 (MZ2AZ-461). 떠 있는 동안 그 밑의 화면은 로그인 화면을 올리지 않는다 — 덮개 밑에서는
 * 시트가 뜨지 않는다(`signInSheet` 규칙). 신고 중에 세션이 풀리면 신고 시트가 스스로 닫히고, 닫힌 뒤 밑의 화면이 올린다.
 */
export class ReportSheetPresence {
  private _open = false
  private readonly listeners = new Set<() => void>()
  get open(): boolean {
    return this._open
  }
  appeared(signInRequested: boolean, dismiss: () => void): void {
    this.set(true)
    this.signInChanged(signInRequested, dismiss)
  }
  signInChanged(requested: boolean, dismiss: () => void): void {
    if (requested) dismiss()
  }
  disappeared(): void {
    this.set(false)
  }
  subscribe = (l: () => void): (() => void) => {
    this.listeners.add(l)
    return () => this.listeners.delete(l)
  }
  private set(value: boolean) {
    if (this._open === value) return
    this._open = value
    this.listeners.forEach((l) => l())
  }
}

export const reportSheetPresence = new ReportSheetPresence()
