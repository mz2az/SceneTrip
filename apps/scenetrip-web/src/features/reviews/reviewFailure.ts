/*
 * 리뷰 저장·지우기가 실패했을 때 보일 한 줄 — 서버 문장이 아니라 code 로 가른다(MZ2AZ-345).
 * 출처: apps/scenetrip-ios/src/Reviews/ReviewComposeView.swift (failureText), Auth/AuthRules.swift (action)
 */
import { ApiFailure } from '@/api/client'
import { tr } from '@/i18n'

/** 401 중 세션이 풀린 것(로그인 화면이 위로 올라와야 하는 것). */
export function isSignOut(status: number | null, code: string | null): boolean {
  return status === 401 && (code === 'ACCESS_TOKEN_INVALID' || code === 'SESSION_REQUIRED' || code === 'REFRESH_TOKEN_INVALID')
}

/** 금칙어(MZ2AZ-461) — 어떤 말인지는 서버가 알려 주지 않는다. 신고·차단 모듈의 `textNotAllowedMessage` 와 같은 문구다. */
export const TEXT_NOT_ALLOWED_CODE = 'TEXT_NOT_ALLOWED'

export function reviewFailureText(error: unknown): string {
  if (error instanceof ApiFailure) {
    const { statusCode, apiCode } = error
    // 세션이 풀렸다 — 쓰던 글은 그대로 둔다.
    if (isSignOut(statusCode, apiCode)) return tr('로그인이 풀렸어요. 다시 로그인해 주세요')
    switch (apiCode) {
      case 'SIGN_IN_REQUIRED':
        return tr('로그인한 뒤에 쓸 수 있어요')
      case 'REVIEW_PHOTO_INVALID':
        return tr('사진을 다시 올려 주세요')
      case 'RATE_LIMITED':
        return tr('요청이 많아요. 잠시 뒤 다시 시도해 주세요')
      case TEXT_NOT_ALLOWED_CODE:
        return tr('쓸 수 없는 말이 들어 있어요. 고친 뒤 다시 저장해 주세요.')
    }
  }
  return tr('저장하지 못했어요. 잠시 뒤 다시 해 주세요')
}
