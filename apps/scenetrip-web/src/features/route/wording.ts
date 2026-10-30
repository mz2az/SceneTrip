/*
 * 화면에 적는 말의 작은 규칙 — iOS Models/Wording.swift (trCount).
 * 수가 든 문구. 영어는 하나일 때 낱말이 달라진다(「1 places」가 아니라 「1 place」).
 * 번역 표의 관례 그대로: 하나일 때는 `원문|하나` 를 찾고, 그 줄이 없으면 여럿의 번역으로 물러선다.
 */
import { tr, trAt } from '@/i18n'

export function trCount(korean: string, count: number): string {
  return count === 1 ? trAt(korean, '하나', count) : tr(korean, count)
}
