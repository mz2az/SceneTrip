/*
 * 시안 A·B·C 가 같이 쓰는 조각 — 도시 고르기, 며칠·속도 고르기, 추천 촬영지 목록(담기), 코스로 저장.
 * 껍데기라 시험·번역·분석 이벤트가 없다(docs/project/plans/web-trip-first-prototypes.md §4).
 */
import { type CSSProperties, type ReactNode, useEffect, useState } from 'react'
import type { components } from '@/api/generated/schema'
import { Icon } from '@/design/Icon'
import { HStack, Spacer, Spinner, Txt, VStack, homeCard } from '@/design/primitives'
import { type HomeCity, homeCityStore, loadHomeCities } from '@/features/home/homeCityModel'
import { hasPhoto } from '@/features/home/homeRules'
import { Pressable } from '@/features/route/ui/Press'
import { makeCourse, makeDay, makeStop } from '@/features/route/models'
import { routeActions } from '@/features/route/store'
import { useLang } from '@/i18n'
import { cartActions, useCart } from '@/state/cart'
import { useStore } from '@/state/storage'
import { type Pace, daysLabel, paceLabel, say } from './protoState'

type PlaceSummary = components['schemas']['PlaceSummary']

/** 질문에 내보일 도시 수 — 촬영지가 적은 도시까지 다 늘어놓으면 고르기 어렵다. */
export const ASK_CITIES = 8

export function useCities(): { cities: readonly HomeCity[]; loading: boolean } {
  const lang = useLang()
  const state = useStore(homeCityStore, (s) => s)
  useEffect(() => {
    void loadHomeCities()
  }, [lang])
  return { cities: state.cities, loading: state.status !== 'loaded' }
}

export const cityByKey = (cities: readonly HomeCity[], key: string | null | undefined) => cities.find((c) => c.key === key) ?? null

/** 시안 표시 — 껍데기 화면이라는 것을 헷갈리지 않게. */
export function MockBadge({ label = say('시안', 'Prototype') }: { label?: string }) {
  return (
    <span style={{ fontSize: 10, fontWeight: 700, padding: '2px 6px', borderRadius: 6, background: 'rgba(255,149,0,0.15)', color: 'rgb(200,110,0)', letterSpacing: 0.3 }}>
      {label}
    </span>
  )
}

export function CityGrid({ cities, selected, onPick }: { cities: readonly HomeCity[]; selected?: string | null; onPick: (key: string) => void }) {
  return (
    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(2, minmax(0, 1fr))', gap: 10 }}>
      {cities.slice(0, ASK_CITIES).map((c) => {
        const cover = c.places.find(hasPhoto)
        const on = c.key === selected
        return (
          <Pressable
            key={c.key}
            plain
            onClick={() => onPick(c.key)}
            aria-pressed={on}
            style={{ position: 'relative', height: 92, borderRadius: 16, overflow: 'hidden', textAlign: 'left', outline: on ? '3px solid var(--accent)' : 'none', background: 'var(--pin-light)' }}
          >
            {cover && <img src={cover.imageUrl!} alt="" referrerPolicy="no-referrer" style={{ position: 'absolute', inset: 0, width: '100%', height: '100%', objectFit: 'cover' }} />}
            <div style={{ position: 'absolute', inset: 0, background: 'linear-gradient(to top, rgba(0,0,0,0.65), rgba(0,0,0,0.05))' }} />
            <VStack spacing={1} align="start" style={{ position: 'absolute', left: 12, bottom: 10 }}>
              <Txt size={17} weight="heavy" color="white">{c.name}</Txt>
              <Txt size={12} color="white" style={{ opacity: 0.85 }}>{say(`촬영지 ${c.places.length}곳`, `${c.places.length} spots`)}</Txt>
            </VStack>
          </Pressable>
        )
      })}
    </div>
  )
}

export function ChoiceRow<T extends string | number>({ options, value, label, onPick }: { options: readonly T[]; value: T | null; label: (v: T) => string; onPick: (v: T) => void }) {
  return (
    <VStack spacing={10} align="stretch">
      {options.map((o) => (
        <Pressable
          key={String(o)}
          plain
          onClick={() => onPick(o)}
          style={{ padding: '16px 18px', borderRadius: 14, textAlign: 'left', background: o === value ? 'var(--accent)' : 'var(--system-background)', boxShadow: 'var(--card-shadow)' }}
        >
          <Txt size={17} weight="semibold" style={{ color: o === value ? '#fff' : 'var(--label)' }}>{label(o)}</Txt>
        </Pressable>
      ))}
    </VStack>
  )
}

export const DAY_OPTIONS = [1, 2, 3, 4] as const
export const PACE_OPTIONS: readonly Pace[] = ['relaxed', 'normal', 'packed']
export { daysLabel, paceLabel }

/** 화면 전체를 덮는 시안 화면의 틀 — 위 제목줄, 가운데 내용(스크롤), 아래 단추. */
export function ProtoScreen({ title, onBack, children, footer }: { title: ReactNode; onBack?: () => void; children: ReactNode; footer?: ReactNode }) {
  return (
    <div style={{ position: 'absolute', inset: 0, zIndex: 40, display: 'flex', flexDirection: 'column', background: 'var(--grouped-background)' }}>
      <HStack spacing={8} style={{ padding: 'calc(env(safe-area-inset-top, 0px) + 12px) 16px 8px', minHeight: 44 }}>
        {onBack ? (
          <Pressable plain onClick={onBack} aria-label={say('뒤로', 'Back')} style={{ padding: 6 }}>
            <Icon name="chevron.left" size={20} weight="semibold" color="accent" />
          </Pressable>
        ) : (
          <div style={{ width: 32 }} />
        )}
        <Spacer />
        <MockBadge />
      </HStack>
      <div className="scroll-y" style={{ flex: 1, minHeight: 0 }}>
        <VStack spacing={18} align="stretch" style={{ padding: '4px 20px 24px' }}>
          {typeof title === 'string' ? <Txt size={26} weight="heavy" style={{ textWrap: 'balance' } as CSSProperties}>{title}</Txt> : title}
          {children}
        </VStack>
      </div>
      {footer && <div style={{ padding: '10px 20px calc(env(safe-area-inset-bottom, 0px) + 14px)', background: 'var(--grouped-background)', borderTop: '0.5px solid var(--separator)' }}>{footer}</div>}
    </div>
  )
}

export function PrimaryButton({ children, onClick, disabled }: { children: ReactNode; onClick: () => void; disabled?: boolean }) {
  return (
    <Pressable
      plain
      onClick={onClick}
      disabled={disabled}
      style={{ width: '100%', padding: '15px 0', borderRadius: 14, background: 'var(--accent)', opacity: disabled ? 0.45 : 1, textAlign: 'center' }}
    >
      <Txt size={17} weight="bold" color="white">{children}</Txt>
    </Pressable>
  )
}

/** 추천 촬영지 한 줄 — 사진·이름·작품·담기 단추. */
export function SpotRow({ place, saved, onToggle, trailing }: { place: PlaceSummary; saved?: boolean; onToggle?: () => void; trailing?: ReactNode }) {
  const work = place.contents?.[0]?.title
  return (
    <HStack spacing={12} style={{ padding: 10, ...homeCard(16) }}>
      <div style={{ width: 72, height: 72, flex: 'none', borderRadius: 12, overflow: 'hidden', background: 'var(--pin-light)' }}>
        {hasPhoto(place) && <img src={place.imageUrl!} alt="" loading="lazy" referrerPolicy="no-referrer" style={{ width: '100%', height: '100%', objectFit: 'cover' }} />}
      </div>
      <VStack spacing={3} align="start" style={{ flex: 1, minWidth: 0 }}>
        <Txt size={15} weight="bold" lines={1}>{place.name}</Txt>
        {work && <Txt size={12} weight="semibold" color="purple" lines={1}>{work}</Txt>}
        <Txt size={12} color="secondary" lines={1}>{place.address ?? ''}</Txt>
      </VStack>
      {trailing}
      {onToggle && (
        <Pressable
          plain
          onClick={onToggle}
          aria-pressed={saved}
          style={{ flex: 'none', padding: '8px 12px', borderRadius: 999, background: saved ? 'var(--accent)' : 'var(--system-gray6)' }}
        >
          <HStack spacing={4}>
            <Icon name={saved ? 'checkmark' : 'plus'} size={13} weight="bold" color={saved ? 'white' : 'accent'} />
            <Txt size={13} weight="semibold" style={{ color: saved ? '#fff' : 'var(--accent)' }}>{saved ? say('담음', 'Added') : say('담기', 'Add')}</Txt>
          </HStack>
        </Pressable>
      )}
    </HStack>
  )
}

/** 고른 촬영지를 날마다 나눠 코스로 저장하고 서버 id 를 돌려준다(진짜 `POST /courses` + `PUT`). */
export async function saveCourse(title: string, days: readonly (readonly PlaceSummary[])[], pace: Pace, madeByAI: boolean): Promise<number | null> {
  const course = makeCourse({
    title,
    pace: pace === 'packed' ? 'tight' : 'loose',
    madeByAI,
    days: days.map((stops) => makeDay(stops.map((p) => makeStop(p)))),
  })
  const saved = await routeActions.save(course)
  return saved?.serverId ?? null
}

/** 담은 곳을 며칠로 고르게 나눈다 — 순서는 담은 순(목록 순) 그대로. */
export function splitDays<T>(items: readonly T[], days: number): T[][] {
  const per = Math.max(1, Math.ceil(items.length / days))
  return Array.from({ length: days }, (_, i) => items.slice(i * per, (i + 1) * per))
}

/**
 * 도시 하나의 추천 촬영지 목록 — 담기(진짜 장바구니) → 아래 「N곳으로 코스 만들기」. 시안 A·B 가 쓴다.
 * `days` 가 있으면(B: 질문에서 받음) 바로 만들고, 없으면(A) 며칠인지 한 번 묻는다.
 */
export function CityPicker({ city, days, onBack, onCreated }: {
  city: HomeCity
  days: number | null
  onBack: () => void
  onCreated: (courseId: number, days: number) => void
}) {
  const cart = useCart()
  const [askDays, setAskDays] = useState(false)
  const [busy, setBusy] = useState(false)
  const [failed, setFailed] = useState(false)
  const chosen = city.places.filter((p) => cart.placeIds.has(p.id))

  const create = async (n: number) => {
    setBusy(true)
    setFailed(false)
    const id = await saveCourse(say(`${city.name} 여행`, `${city.name} trip`), splitDays(chosen, n), 'normal', false)
    setBusy(false)
    if (id === null) return setFailed(true)
    onCreated(id, n)
  }

  if (askDays) {
    return (
      <ProtoScreen title={say(`${city.name}, 며칠 머무세요?`, `How long in ${city.name}?`)} onBack={() => setAskDays(false)}>
        <ChoiceRow options={DAY_OPTIONS} value={null} label={daysLabel} onPick={(n) => void create(n)} />
        {busy && <HStack spacing={8}><Spinner size={16} /><Txt size={14} color="secondary">{say('코스 만드는 중…', 'Creating your course…')}</Txt></HStack>}
        {failed && <Txt size={14} color="red">{say('코스를 만들지 못했어요. 다시 눌러 주세요', "Couldn't create the course. Try again")}</Txt>}
      </ProtoScreen>
    )
  }

  return (
    <ProtoScreen
      onBack={onBack}
      title={
        <VStack spacing={6} align="start">
          <Txt size={26} weight="heavy">{say(`${city.name}의 촬영지`, `Filming spots in ${city.name}`)}</Txt>
          <Txt size={15} color="secondary">{say('가 보고 싶은 곳을 담으면 동선을 이어 코스로 만들어 드려요', "Add the places you like — we'll turn them into a route")}</Txt>
        </VStack>
      }
      footer={
        <VStack spacing={6} align="stretch">
          {failed && <Txt size={13} color="red">{say('코스를 만들지 못했어요. 다시 눌러 주세요', "Couldn't create the course. Try again")}</Txt>}
          <PrimaryButton disabled={chosen.length === 0 || busy} onClick={() => (days ? void create(days) : setAskDays(true))}>
            {busy ? say('만드는 중…', 'Creating…') : chosen.length === 0 ? say('가 볼 곳을 담아 주세요', 'Add places to start') : say(`${chosen.length}곳으로 코스 만들기`, `Make a course with ${chosen.length}`)}
          </PrimaryButton>
        </VStack>
      }
    >
      <VStack spacing={10} align="stretch">
        {city.places.slice(0, 30).map((p) => (
          <SpotRow key={p.id} place={p} saved={cart.placeIds.has(p.id)} onToggle={() => void (cart.placeIds.has(p.id) ? cartActions.remove(p.id) : cartActions.add(p.id))} />
        ))}
      </VStack>
    </ProtoScreen>
  )
}
