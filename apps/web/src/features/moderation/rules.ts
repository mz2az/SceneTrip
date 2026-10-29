/*
 * 신고·차단·금칙어의 규칙 — 값만 보는 순수 함수 (iOS `ModerationRules`, Models/Moderation.swift; App Store 심사 지침 1.2).
 *
 * 오류는 **서버 문장이 아니라 code 로 가른다**(MZ2AZ-345) — 계약이 `ApiError.message` 를 화면 문구가 아니라고 못 박았다.
 */
import type { components } from '@/api/generated/schema'
import { ApiFailure } from '@/api/failure'
import { tr } from '@/i18n'

export type ReportReason = components['schemas']['ReportReason']
export type ReportTargetType = components['schemas']['ReportTargetType']
export type BlockedUser = components['schemas']['BlockedUser']

/** 신고·차단할 글 (MZ2AZ-461). 계약은 **글을 기준으로** 받는다 — 앱에는 사용자 번호가 없고 닉네임은 바뀐다. 차단도 「이 글의 작성자」 를 차단한다. */
export type ModerationTarget = { kind: 'review'; id: number } | { kind: 'post'; id: number }

export const reviewTarget = (id: number): ModerationTarget => ({ kind: 'review', id })
export const postTarget = (id: number): ModerationTarget => ({ kind: 'post', id })

/** iOS `ModerationTarget.id` — 시트 열림의 동일성 비교용. */
export const targetKey = (t: ModerationTarget): string => `${t.kind}-${t.id}`
export const sameTarget = (a: ModerationTarget | null, b: ModerationTarget | null): boolean =>
  a === b || (a !== null && b !== null && targetKey(a) === targetKey(b))

/** 계약 `ReportCreate.targetType` */
export const targetType = (t: ModerationTarget): ReportTargetType => t.kind

/** 신고 이유 — 화면에 이 순서로 보인다. 계약 `ReportReason` 의 순서 그대로다. */
export const REPORT_REASONS: readonly ReportReason[] = ['spam', 'abuse', 'sexual', 'personal_info', 'inappropriate_nickname', 'other']

/** 덧붙이는 설명의 최대 길이(계약 `ReportCreate.detail` maxLength). */
export const DETAIL_LIMIT = 500

export function reasonLabel(reason: ReportReason): string {
  switch (reason) {
    case 'spam': return tr('스팸·광고')
    case 'abuse': return tr('욕설·혐오·괴롭힘')
    case 'sexual': return tr('성적인 내용')
    case 'personal_info': return tr('개인정보 노출')
    case 'inappropriate_nickname': return tr('닉네임이 부적절함')
    case 'other': return tr('기타')
  }
}

/** 보낼 설명 — 앞뒤 빈칸을 지우고, 비었으면 보내지 않고, 길면 자른다. */
export function reportDetail(raw: string): string | null {
  const trimmed = raw.trim()
  if (trimmed === '') return null
  return [...trimmed].slice(0, DETAIL_LIMIT).join('')
}

/** 「⋯」 메뉴를 둘 글인가 — 자기 글에는 두지 않는다(보내면 `CANNOT_REPORT_OWN`). 서버에 없는 기기 옛 글도 아니다. */
export function showsMenu(isMine: boolean, onServer = true): boolean {
  return !isMine && onServer
}

/** 차단을 둘 글인가 — 자기 글이 아니고 작성자가 있어야 한다(탈퇴한 작성자는 `BLOCK_TARGET_HAS_NO_AUTHOR`). */
export function canBlock(isMine: boolean, hasAuthor: boolean): boolean {
  return !isMine && hasAuthor
}

// ---------------------------------------------------------------- 신고 결과

export type ReportOutcome = 'received' | 'own' | 'gone' | 'rateLimited' | 'failed'

/** 시트를 닫아도 되는가 — 다시 보내도 같은 결과면 닫는다. 실패·한도는 남겨 다시 보내게 한다. */
export function reportFinishes(outcome: ReportOutcome): boolean {
  return outcome === 'received' || outcome === 'own' || outcome === 'gone'
}

function failureOf(error: unknown): ApiFailure | null {
  return error instanceof ApiFailure ? error : null
}

/** `error` 가 없으면(= 성공) 접수된 것이다. */
export function reportOutcome(error: unknown | null): ReportOutcome {
  if (error === null || error === undefined) return 'received'
  const f = failureOf(error)
  if (!f) return 'failed'
  switch (f.apiCode) {
    case 'CANNOT_REPORT_OWN': return 'own'
    case 'REPORT_TARGET_NOT_FOUND': return 'gone'
    case 'RATE_LIMITED': return 'rateLimited'
    default: return f.statusCode === 429 ? 'rateLimited' : 'failed'
  }
}

export function reportMessage(outcome: ReportOutcome): string {
  switch (outcome) {
    case 'received': return tr('신고가 접수됐어요. 24시간 안에 확인할게요.')
    case 'own': return tr('내 글은 신고할 수 없어요')
    case 'gone': return tr('이미 내려갔거나 없는 글이에요')
    case 'rateLimited': return tr('요청이 많아요. 잠시 뒤 다시 시도해 주세요')
    case 'failed': return tr('신고를 보내지 못했어요. 잠시 뒤 다시 해 주세요')
  }
}

// ---------------------------------------------------------------- 차단 결과

export type BlockOutcome = 'blocked' | 'signInRequired' | 'own' | 'noAuthor' | 'gone' | 'failed'

/** iOS `AuthRules.action(status:code:) == .promptSignIn` — 401 + SIGN_IN_REQUIRED. */
export function isSignInRequired(f: ApiFailure): boolean {
  return f.statusCode === 401 && f.apiCode === 'SIGN_IN_REQUIRED'
}

export function blockOutcome(error: unknown | null): BlockOutcome {
  if (error === null || error === undefined) return 'blocked'
  const f = failureOf(error)
  if (!f) return 'failed'
  if (isSignInRequired(f)) return 'signInRequired'
  switch (f.apiCode) {
    case 'CANNOT_BLOCK_SELF': return 'own'
    case 'BLOCK_TARGET_HAS_NO_AUTHOR': return 'noAuthor'
    case 'BLOCK_TARGET_NOT_FOUND': return 'gone'
    default: return 'failed'
  }
}

/** 차단하지 못했을 때의 안내. 차단했으면 글이 바로 사라지는 것이 안내다 — 문구를 따로 띄우지 않는다. */
export function blockMessage(outcome: BlockOutcome): string | null {
  switch (outcome) {
    case 'blocked':
    case 'signInRequired': return null
    case 'own': return tr('내 글의 작성자는 차단할 수 없어요')
    case 'noAuthor': return tr('탈퇴한 사용자는 차단할 수 없어요')
    case 'gone': return tr('이미 내려갔거나 없는 글이에요')
    case 'failed': return tr('차단하지 못했어요. 잠시 뒤 다시 해 주세요')
  }
}

// ---------------------------------------------------------------- 금칙어

/** 리뷰·여행후기·닉네임·코스 이름 저장이 금칙어에 걸렸다(`400 TEXT_NOT_ALLOWED`). */
export function isTextNotAllowed(error: unknown): boolean {
  return failureOf(error)?.apiCode === 'TEXT_NOT_ALLOWED'
}
export function isTextNotAllowedCode(code: string | null | undefined): boolean {
  return code === 'TEXT_NOT_ALLOWED'
}

/** 어떤 말이 걸렸는지는 서버가 알려 주지 않는다 — 고쳐 달라고만 한다. */
export function textNotAllowedMessage(): string {
  return tr('쓸 수 없는 말이 들어 있어요. 고친 뒤 다시 저장해 주세요.')
}
