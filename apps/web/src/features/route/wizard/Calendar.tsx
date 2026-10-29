/*
 * `DatePicker(displayedComponents: .date).datePickerStyle(.graphical)` — 한 달 달력. 오늘 이전은 흐리게 눌리지 않는다(`in: Date()...`).
 * 고른 날은 강조색 원 안에 흰 굵은 숫자. 한국어는 「2026년 10월」 · 일월화수목금토, 영어는 「October 2026」 · S M T W T F S.
 */
import { useState } from 'react'
import { Icon } from '@/design/Icon'
import { HStack, Pressable, Txt } from '@/design/primitives'
import { currentLang } from '@/i18n'
import { parseDate, toIso } from '../models'

const CELL = 50
const ROW = 56
const KO_WEEK = ['일', '월', '화', '수', '목', '금', '토']
const EN_WEEK = ['S', 'M', 'T', 'W', 'T', 'F', 'S']

export function Calendar({ value, min, onPick }: { value: string; min: string; onPick: (iso: string) => void }) {
  const selected = parseDate(value)
  const [month, setMonth] = useState(() => new Date(selected.getFullYear(), selected.getMonth(), 1, 12))
  const first = month.getDay()
  const days = new Date(month.getFullYear(), month.getMonth() + 1, 0).getDate()
  const cells: Array<number | null> = [...Array(first).fill(null), ...Array.from({ length: days }, (_, i) => i + 1)]
  while (cells.length % 7 !== 0) cells.push(null)
  const minDate = parseDate(min)
  const atMin = month.getFullYear() === minDate.getFullYear() && month.getMonth() === minDate.getMonth()
  const lang = currentLang()
  const title = new Intl.DateTimeFormat(lang === 'ko' ? 'ko-KR' : 'en-US', { year: 'numeric', month: 'long' }).format(month)
  const shift = (d: number) => setMonth(new Date(month.getFullYear(), month.getMonth() + d, 1, 12))

  return (
    <div style={{ padding: '0 0 8px', width: '100%' }}>
      <HStack justify="between" style={{ padding: '14px 18px 0 18px', height: 50 }}>
        <HStack spacing={4}>
          <Txt size={17} weight="semibold">{title}</Txt>
          <Icon name="chevron.right" size={13} weight="semibold" color="accent" />
        </HStack>
        <HStack spacing={26}>
          <Pressable plain disabled={atMin} onClick={() => shift(-1)} aria-label="previous month" style={{ opacity: atMin ? 0.35 : 1 }}>
            <Icon name="chevron.left" size={20} weight="semibold" color={atMin ? 'secondary' : 'accent'} />
          </Pressable>
          <Pressable plain onClick={() => shift(1)} aria-label="next month">
            <Icon name="chevron.right" size={20} weight="semibold" color="accent" />
          </Pressable>
        </HStack>
      </HStack>
      <div style={{ display: 'grid', gridTemplateColumns: `repeat(7, ${CELL}px)`, justifyContent: 'center', marginTop: 12 }}>
        {(lang === 'ko' ? KO_WEEK : EN_WEEK).map((w, i) => (
          <div key={i} style={{ height: 24, textAlign: 'center' }}>
            <Txt size={13} weight="semibold" color="secondary" align="center">{w}</Txt>
          </div>
        ))}
        {cells.map((n, i) => {
          if (n === null) return <div key={i} style={{ height: ROW }} />
          const date = new Date(month.getFullYear(), month.getMonth(), n, 12)
          const iso = toIso(date)
          const disabled = iso < min
          const on = iso === value
          return (
            <Pressable key={i} plain disabled={disabled} onClick={() => onPick(iso)} aria-label={iso} aria-pressed={on} style={{ height: ROW, display: 'grid', placeItems: 'center' }}>
              <span
                style={{
                  width: 44, height: 44, borderRadius: '50%', display: 'grid', placeItems: 'center', background: on ? 'var(--accent)' : 'transparent',
                }}
              >
                <Txt size={22} weight={on ? 'semibold' : 'regular'} style={{ color: on ? '#fff' : 'var(--label)', opacity: disabled ? 0.2 : 1 }}>{n}</Txt>
              </span>
            </Pressable>
          )
        })}
      </div>
    </div>
  )
}
