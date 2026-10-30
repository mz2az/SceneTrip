/*
 * 신고·차단 창구 — 생성 타입의 `api` 만 부른다 (iOS `ModerationClient` / `ModerationServerClient`). 시험에서 가짜로 바꾼다.
 */
import { api, installHeader, ok } from '@/api/client'
import { type BlockedUser, type ModerationTarget, type ReportReason, targetType } from './rules'

export interface ModerationClient {
  report(target: ModerationTarget, reason: ReportReason, detail: string | null): Promise<void>
  block(target: ModerationTarget): Promise<BlockedUser>
  blocks(): Promise<BlockedUser[]>
  unblock(blockId: number): Promise<void>
}

export const serverModerationClient: ModerationClient = {
  /** 비회원도 신고한다 — 설치 id 로 찾은 계정이 신고한 사람이 된다. 로그인했으면 공통 장치가 토큰을 싣는다. */
  async report(target, reason, detail) {
    await ok(
      api.POST('/reports', {
        params: { header: installHeader() },
        body: { targetType: targetType(target), targetId: target.id, reason, detail },
      }),
    )
  },
  async block(target) {
    return ok(api.POST('/me/blocks', { body: { targetType: targetType(target), targetId: target.id } }))
  },
  async blocks() {
    return (await ok(api.GET('/me/blocks'))).items
  },
  async unblock(blockId) {
    await ok(api.DELETE('/me/blocks/{blockId}', { params: { path: { blockId } } }))
  },
}
