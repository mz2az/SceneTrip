/*
 * 화면에 적는 말의 작은 규칙 셋 — 출처: apps/scenetrip-ios/src/Models/Wording.swift (MZ2AZ-372 의 7번).
 * 셋 다 값만 보는 순수 함수다.
 */
import { tr, trAt } from '@/i18n'

/** 수가 든 문구. 영어는 하나일 때 낱말이 달라진다 — 「1 places」 가 아니라 「1 place」 (번역 표의 `원문|하나`). */
export function trCount(korean: string, count: number): string {
  return count === 1 ? trAt(korean, '하나', count) : tr(korean, count)
}

/** 지도 핀 아래 이름표 — 영어 이름은 길어 옆 핀을 덮으므로 앞머리만. 전체 이름은 눌러 연 카드에 있다. */
export const PinCaption = {
  /** 이보다 길면 자른다. */
  limit: 28,
  /** 이름표의 폭(px). 넘으면 줄을 바꾼다. */
  width: 132,
  text(name: string): string {
    const trimmed = name.trim()
    const chars = [...trimmed]
    if (chars.length <= PinCaption.limit) return trimmed
    return chars.slice(0, PinCaption.limit - 1).join('').trim() + '…'
  },
}

function clean(text: string | null | undefined): string | null {
  const t = text?.trim()
  return t ? t : null
}

function same(a: string, b: string): boolean {
  return a.toLowerCase() === b.toLowerCase()
}

/** 로마자(라틴 문자)로 적힌 말인가 — 글자가 하나는 있고, 글자는 전부 라틴이다. 숫자·문장부호는 따지지 않는다. */
export function isRoman(text: string): boolean {
  const letters = [...text].filter((c) => /\p{L}/u.test(c))
  return letters.length > 0 && letters.every((c) => c.codePointAt(0)! < 0x250)
}

/**
 * 자동완성 작품 카드의 다른 이름 배지 (MZ2AZ-372).
 * 1) 사용자가 친 말이 별칭에 걸렸으면 그 표기 2) 아니면 로마자로 적힌 첫 별칭(제목과 같으면 건너뜀) 3) 없으면 배지 없음.
 */
export function pickAliasBadge(title: string, aliases: readonly string[], matched: string | null | undefined): string | null {
  const m = clean(matched)
  if (m && !same(m, title)) return m
  for (const raw of aliases) {
    const a = clean(raw)
    if (a && isRoman(a) && !same(a, title)) return a
  }
  return null
}
