/*
 * 수가 든 문구 — iOS `trCount` (Models/Wording.swift). 영어는 하나일 때 낱말이 달라진다(「1 places」가 아니라 「1 place」).
 * 번역 표의 관례 그대로: 하나일 때는 `원문|하나` 를 찾고, 그 줄이 없으면 여럿의 번역으로 물러선다. 한국어는 수에 따라 달라지지 않는다.
 */
import { tr, trAt } from '@/i18n'

export function trCount(korean: string, count: number): string {
  return count === 1 ? trAt(korean, '하나', count) : tr(korean, count)
}

/** 첫 문장만 — 설정 앱을 가리키는 뒷문장은 웹에 맞지 않는다(광고 추적 줄이 없다). 한국어(「. 」)·영어 모두 문장 끝 마침표 뒤 공백으로 자른다. */
export function firstSentence(text: string): string {
  const cut = text.search(/[.。](\s|$)/)
  return cut < 0 ? text : text.slice(0, cut + 1)
}
