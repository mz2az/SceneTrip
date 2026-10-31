// iOS tests/ModerationTests.swift — 흐름(컨트롤러·차단 목록·신호·신고 시트 존재)
import { beforeEach, describe, expect, it } from 'vitest'
import { ApiFailure } from '@/api/failure'
import { chooseLanguage } from '@/i18n'
import { createBlockedUsersStore } from './blockedUsers'
import type { ModerationClient } from './client'
import { createModerationController } from './controller'
import { ReportSheetPresence } from './controller'
import { createModerationEvents } from './events'
import { type BlockedUser, type ModerationTarget, type ReportReason, postTarget, reviewTarget } from './rules'

const sample = (id: number, nickname: string): BlockedUser => ({ id, nickname, blockedAt: '2026-10-01T00:00:00Z' })

class FakeClient implements ModerationClient {
  reports: Array<{ target: ModerationTarget; reason: ReportReason; detail: string | null }> = []
  blocked: ModerationTarget[] = []
  unblocked: number[] = []
  list: BlockedUser[] = []
  reportError: unknown = null
  blockError: unknown = null
  unblockError: unknown = null
  listError: unknown = null
  blocksGate: (() => Promise<void>) | null = null
  async report(target: ModerationTarget, reason: ReportReason, detail: string | null) {
    if (this.reportError) throw this.reportError
    this.reports.push({ target, reason, detail })
  }
  async block(target: ModerationTarget) {
    if (this.blockError) throw this.blockError
    this.blocked.push(target)
    return sample(1, 'x')
  }
  async blocks() {
    const snapshot = [...this.list]
    if (this.blocksGate) await this.blocksGate()
    if (this.listError) throw this.listError
    return snapshot
  }
  async unblock(id: number) {
    if (this.unblockError) throw this.unblockError
    this.unblocked.push(id)
  }
}

beforeEach(() => chooseLanguage('ko')) // jsdom 의 기본 언어는 영어다

const fail = (statusCode: number, apiCode: string) => new ApiFailure({ statusCode, apiCode })

describe('ModerationController', () => {
  it('비회원이 차단하려 하면 확인 창 대신 로그인을 청한다', async () => {
    const client = new FakeClient()
    let prompted = 0
    const c = createModerationController({ client, signedIn: () => false, promptSignIn: () => (prompted += 1), events: createModerationEvents() })
    c.askBlock(reviewTarget(3))
    expect(c.store.get().confirmingBlock).toBeNull()
    expect(prompted).toBe(1)
    expect(await c.block(reviewTarget(3))).toBe(false)
    expect(client.blocked).toEqual([])
  })

  it('차단은 대상을 보내고 성공을 알린다', async () => {
    const client = new FakeClient()
    const events = createModerationEvents()
    const c = createModerationController({ client, signedIn: () => true, promptSignIn: () => {}, events })
    c.askBlock(postTarget(5))
    expect(c.store.get().confirmingBlock).toEqual(postTarget(5))
    expect(await c.block(postTarget(5))).toBe(true)
    expect(c.store.get().confirmingBlock).toBeNull()
    expect(c.store.get().notice).toBeNull()
    expect(client.blocked).toEqual([postTarget(5)])
    expect(events.version()).toBe(1)
  })

  it('차단 실패는 까닭을 안내하고 신호를 울리지 않는다', async () => {
    const client = new FakeClient()
    client.blockError = fail(400, 'BLOCK_TARGET_HAS_NO_AUTHOR')
    const events = createModerationEvents()
    const c = createModerationController({ client, signedIn: () => true, promptSignIn: () => {}, events })
    expect(await c.block(reviewTarget(8))).toBe(false)
    expect(c.store.get().notice).toBe('탈퇴한 사용자는 차단할 수 없어요')
    expect(events.version()).toBe(0)
  })

  it('세션이 풀려 401 SIGN_IN_REQUIRED 를 받으면 로그인을 청하고 안내는 없다', async () => {
    const client = new FakeClient()
    client.blockError = fail(401, 'SIGN_IN_REQUIRED')
    let prompted = 0
    const c = createModerationController({ client, signedIn: () => true, promptSignIn: () => (prompted += 1), events: createModerationEvents() })
    expect(await c.block(postTarget(2))).toBe(false)
    expect(prompted).toBe(1)
    expect(c.store.get().notice).toBeNull()
  })

  it('신고는 비회원도 되고 설명을 다듬어 보낸다', async () => {
    const client = new FakeClient()
    const c = createModerationController({ client, signedIn: () => false, promptSignIn: () => expect.unreachable('신고는 로그인이 필요 없다'), events: createModerationEvents() })
    expect(await c.report(reviewTarget(4), 'other', '  이상한 링크  ')).toBe('received')
    expect(client.reports[0]).toEqual({ target: reviewTarget(4), reason: 'other', detail: '이상한 링크' })
    client.reportError = fail(400, 'CANNOT_REPORT_OWN')
    expect(await c.report(reviewTarget(4), 'spam', '')).toBe('own')
    expect(c.store.get().sending).toBe(false)
  })

  it('보내는 중에 또 보내면 막는다', async () => {
    const client = new FakeClient()
    let release: () => void = () => {}
    client.report = () => new Promise<void>((r) => (release = r))
    const c = createModerationController({ client, signedIn: () => true, promptSignIn: () => {}, events: createModerationEvents() })
    const first = c.report(reviewTarget(1), 'spam', '')
    expect(c.store.get().sending).toBe(true)
    expect(await c.report(reviewTarget(1), 'spam', '')).toBe('failed')
    release()
    expect(await first).toBe('received')
  })
})

describe('BlockedUsersStore', () => {
  it('목록을 받고 풀면 줄이 빠지고 신호가 울린다', async () => {
    const client = new FakeClient()
    client.list = [sample(1, '가'), sample(2, '나')]
    const events = createModerationEvents()
    const store = createBlockedUsersStore({ client, events })
    await store.reload()
    expect(store.state().phase).toBe('loaded')
    expect(store.state().items.map((u) => u.id)).toEqual([1, 2])
    expect(await store.unblock(store.state().items[0]!)).toBe(true)
    expect(store.state().items.map((u) => u.id)).toEqual([2])
    expect(client.unblocked).toEqual([1])
    expect(events.version()).toBe(1)
  })

  it('이미 없는 줄(BLOCK_NOT_FOUND)은 풀린 것이고 다른 실패는 줄을 남긴다', async () => {
    const client = new FakeClient()
    client.list = [sample(1, '가'), sample(2, '나')]
    const store = createBlockedUsersStore({ client, events: createModerationEvents() })
    await store.reload()
    client.unblockError = fail(404, 'BLOCK_NOT_FOUND')
    await store.unblock(store.state().items[0]!)
    expect(store.state().items.map((u) => u.id)).toEqual([2])
    client.unblockError = new ApiFailure({ statusCode: null, isOffline: true })
    expect(await store.unblock(store.state().items[0]!)).toBe(false)
    expect(store.state().items.map((u) => u.id)).toEqual([2])
    expect(store.state().message).not.toBeNull()
  })

  it('목록 받기가 실패하면 다시 시도 상태, 받아 둔 목록이 있으면 그대로 둔다', async () => {
    const client = new FakeClient()
    client.listError = new ApiFailure({ statusCode: null })
    const store = createBlockedUsersStore({ client, events: createModerationEvents() })
    await store.reload()
    expect(store.state().phase).toBe('failed')
    client.listError = null
    client.list = [sample(1, '가')]
    await store.reload()
    expect(store.state().phase).toBe('loaded')
    client.listError = new ApiFailure({ statusCode: null })
    await store.reload()
    expect(store.state().phase).toBe('loaded')
    expect(store.state().items).toHaveLength(1)
  })

  it('받는 사이 푼 줄은 늦게 온 목록에 들어 있어도 되살아나지 않는다', async () => {
    const client = new FakeClient()
    client.list = [sample(1, '가'), sample(2, '나')]
    const store = createBlockedUsersStore({ client, events: createModerationEvents() })
    await store.reload()
    let resume: () => void = () => {}
    let entered: () => void = () => {}
    const reached = new Promise<void>((r) => (entered = r))
    client.blocksGate = () => new Promise<void>((r) => { resume = r; entered() })
    const late = store.reload()
    await reached
    await store.unblock(store.state().items[0]!)
    expect(store.state().items.map((u) => u.id)).toEqual([2])
    resume()
    await late
    expect(store.state().items.map((u) => u.id)).toEqual([2])
  })

  it('같은 줄을 풀고 있는 중이면 또 풀지 않는다', async () => {
    const client = new FakeClient()
    client.list = [sample(1, '가')]
    const store = createBlockedUsersStore({ client, events: createModerationEvents() })
    await store.reload()
    let release: () => void = () => {}
    client.unblock = () => new Promise<void>((r) => (release = r))
    const user = store.state().items[0]!
    const first = store.unblock(user)
    expect(store.state().unblocking.has(1)).toBe(true)
    expect(await store.unblock(user)).toBe(false)
    release()
    expect(await first).toBe(true)
    expect(store.state().unblocking.size).toBe(0)
  })
})

describe('ReportSheetPresence', () => {
  it('뜨기 전·뜨는 중·취소 뒤의 로그인 요청을 넘긴다', () => {
    const presence = new ReportSheetPresence()
    let dismissed = 0
    presence.appeared(true, () => (dismissed += 1))
    expect(dismissed).toBe(1)
    expect(presence.open).toBe(true)
    presence.disappeared()
    expect(presence.open).toBe(false)
    presence.appeared(false, () => (dismissed += 1))
    expect(dismissed).toBe(1)
    presence.signInChanged(true, () => (dismissed += 1))
    expect(dismissed).toBe(2)
    presence.disappeared()
    presence.disappeared()
    expect(presence.open).toBe(false)
  })
})

describe('ModerationEvents', () => {
  it('바뀔 때마다 버전이 오르고 구독자가 깨어난다', () => {
    const events = createModerationEvents()
    let woke = 0
    const off = events.subscribe(() => (woke += 1))
    events.changed()
    events.changed()
    off()
    events.changed()
    expect(events.version()).toBe(3)
    expect(woke).toBe(2)
  })
})
