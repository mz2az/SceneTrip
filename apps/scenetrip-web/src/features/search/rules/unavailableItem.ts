/*
 * 서버의 숨김·폐업 표시. 항목을 지우거나 저장 ID 를 바꾸는 근거로 쓰지 않는다(MZ2AZ-458).
 * 출처: apps/scenetrip-ios/src/Models/UnavailableItem.swift
 * 거절 문구(`rejection`)는 api/failure.ts 의 `unavailableRejection` — ApiFailure.userMessage 가 쓴다.
 */
import { tr } from '@/i18n'

export type UnavailableKind = 'place' | 'content' | 'closedPoi'

export function unavailableMessage(kind: UnavailableKind): string {
  switch (kind) {
    case 'place':
      return tr('더 이상 소개하지 않는 장소예요')
    case 'content':
      return tr('더 이상 소개하지 않는 작품이에요')
    case 'closedPoi':
      return tr('폐업했어요')
  }
}

export function noticeForCartItem(item: { delisted?: boolean | null }): UnavailableKind | null {
  return item.delisted === true ? 'place' : null
}

export function noticeForContent(item: { delisted?: boolean | null }): UnavailableKind | null {
  return item.delisted === true ? 'content' : null
}

/** 새로 담기만 막는다. 이미 담은 것을 빼는 동작은 항상 가능하다. */
export function canToggle(delisted: boolean | null | undefined, saved: boolean): boolean {
  return saved || delisted !== true
}

/** 상세를 다시 받으면 누락도 false 다 — 옛 요약의 true 를 계속 붙들지 않는다. */
export function contentDelisted(summary: { delisted?: boolean | null }, detail: { delisted?: boolean | null } | null): boolean {
  return detail ? detail.delisted === true : summary.delisted === true
}
