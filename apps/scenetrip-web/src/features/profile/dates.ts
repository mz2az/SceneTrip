/*
 * 날짜·시각을 **앱 언어로** 적는다 — iOS 는 `Date.formatted(...locale(AppLanguage.currentLocale))` 이고, 기기 언어가 아니라 앱 언어다
 * (영어 화면에 「어제」「오전 4:06」이 섞였다, MZ2AZ-351). 웹은 `Intl` 에 앱 언어를 직접 넘긴다.
 */
import { currentLang } from '@/i18n'

const monthStyle = (lang: string) => (lang === 'ko' ? 'long' : 'short')

/** `date.formatted(date: .abbreviated, time: .omitted)` — 한국어 「2026년 10월 29일」, 영어 「Oct 29, 2026」. */
export function abbreviatedDate(when: Date | string | number, lang = currentLang()): string {
  return new Intl.DateTimeFormat(lang, { year: 'numeric', month: monthStyle(lang), day: 'numeric' }).format(new Date(when))
}

/** `.dateTime.year().month().day().hour().minute()` — 한국어 「2026년 10월 29일 오후 3:04」, 영어 「Oct 29, 2026 at 3:04 PM」. */
export function dateWithMinutes(when: Date | string | number, lang = currentLang()): string {
  return new Intl.DateTimeFormat(lang, { year: 'numeric', month: monthStyle(lang), day: 'numeric', hour: 'numeric', minute: '2-digit' }).format(new Date(when))
}

const UNITS: Array<[Intl.RelativeTimeFormatUnit, number]> = [
  ['year', 365 * 24 * 3600],
  ['month', 30 * 24 * 3600],
  ['day', 24 * 3600],
  ['hour', 3600],
  ['minute', 60],
]

/** `.relative(presentation: .named)` — 「어제」·「3일 전」 / "yesterday"·"3 days ago". */
export function relativeTime(when: Date | string | number, now = Date.now(), lang = currentLang()): string {
  const seconds = Math.round((new Date(when).getTime() - now) / 1000)
  const rtf = new Intl.RelativeTimeFormat(lang, { numeric: 'auto' })
  for (const [unit, size] of UNITS) {
    if (Math.abs(seconds) >= size) return rtf.format(Math.round(seconds / size), unit)
  }
  return rtf.format(0, 'second')
}
