/*
 * 날짜 표기 — iOS 의 `Date.formatted(.relative(presentation: .named))` 와 `.dateTime.year().month().day().hour().minute()`.
 * 모두 앱 언어(`AppLanguage.currentLocale`)로 적는다.
 */
import { currentLang } from '@/i18n'

const localeOf = () => (currentLang() === 'ko' ? 'ko-KR' : 'en-US')

const MINUTE = 60
const HOUR = 3600
const DAY = 86400
const WEEK = 7 * DAY
const MONTH = 30 * DAY
const YEAR = 365 * DAY

/** 「지난주」「2일 전」「3 days ago」 — 가장 큰 단위로, 이름이 있으면 이름으로(어제·last week). */
export function relativeTime(date: Date, now: Date = new Date()): string {
  const fmt = new Intl.RelativeTimeFormat(localeOf(), { numeric: 'auto' })
  const seconds = Math.round((date.getTime() - now.getTime()) / 1000)
  const abs = Math.abs(seconds)
  const unit = (value: number, u: Intl.RelativeTimeFormatUnit) => fmt.format(Math.trunc(value), u)
  if (abs < MINUTE) return fmt.format(0, 'second')
  if (abs < HOUR) return unit(seconds / MINUTE, 'minute')
  if (abs < DAY) return unit(seconds / HOUR, 'hour')
  if (abs < WEEK) return unit(seconds / DAY, 'day')
  if (abs < MONTH) return unit(seconds / WEEK, 'week')
  if (abs < YEAR) return unit(seconds / MONTH, 'month')
  return unit(seconds / YEAR, 'year')
}

/** 「2026년 10월 20일 오후 2:58」 / 「October 20, 2026 at 2:58 PM」 */
export function fullDate(date: Date): string {
  return date.toLocaleString(localeOf(), { year: 'numeric', month: 'long', day: 'numeric', hour: 'numeric', minute: '2-digit' })
}
