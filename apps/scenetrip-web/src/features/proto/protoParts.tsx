/*
 * 시안 A·B·C 가 같이 쓰는 조각 — 도시 고르기, 며칠·속도 고르기, 추천 촬영지 목록(담기), 코스로 저장.
 * 껍데기라 시험·번역·분석 이벤트가 없다(docs/project/plans/web-trip-first-prototypes.md §4).
 */
import { type CSSProperties, type ReactNode, useEffect, useRef, useState } from 'react'
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
import { type Pace, daysLabel, paceLabel, rememberProtoCourse, say } from './protoState'
import { CITY_COVERS, fixedCoverOf } from './protoCovers'
import { showsPrototypeMarks } from './protoGate'
import { color, contained, heading, radius, shadow, z } from './protoTokens'
import './proto.css'

type PlaceSummary = components['schemas']['PlaceSummary']

/** 질문에 내보일 도시 수 — 촬영지가 적은 도시까지 다 늘어놓으면 고르기 어렵다. */
export const ASK_CITIES = 8

export function useCities(): { cities: readonly HomeCity[]; loading: boolean; failed: boolean; retry: () => void } {
  const lang = useLang()
  const state = useStore(homeCityStore, (s) => s)
  useEffect(() => {
    void loadHomeCities()
  }, [lang])
  const failed = state.status === 'failed' || (state.status === 'loaded' && state.cities.length === 0)
  // 도시 목록은 촬영지 목록을 여러 쪽 받아 만든다 — 요청 한도(429)에 걸려 실패하기 쉽다. 실패하면 15초 뒤 한 번 더.
  useEffect(() => {
    if (state.status !== 'failed') return
    const t = setTimeout(() => void loadHomeCities(), 15_000)
    return () => clearTimeout(t)
  }, [state.status])
  return { cities: state.cities, loading: state.status === 'idle' || state.status === 'loading', failed, retry: () => void loadHomeCities() }
}

export const cityByKey = (cities: readonly HomeCity[], key: string | null | undefined) => cities.find((c) => c.key === key) ?? null

/** 시안 표시 — 껍데기 화면이라는 것을 헷갈리지 않게. */
export function MockBadge({ label = say('시안', 'Prototype'), onPhoto }: { label?: string; onPhoto?: boolean }) {
  // 「시안」 표시는 로컬 개발 서버에서만 — 시험용 웹에서는 실제 화면처럼 보인다(MZ2AZ-539).
  if (!showsPrototypeMarks(import.meta.env.DEV)) return null
  return (
    <span style={{ flex: 'none', fontSize: 12, fontWeight: 700, padding: '2px 7px', borderRadius: radius.s, background: onPhoto ? 'rgb(255,204,128)' : 'rgba(255,149,0,0.15)', color: 'rgb(120,62,0)' }}>
      {label}
    </span>
  )
}

export function CityGrid({ cities, loading, selected, onPick }: { cities: readonly HomeCity[]; loading?: boolean; selected?: string | null; onPick: (key: string) => void }) {
  return (
    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(2, minmax(0, 1fr))', gap: 10 }}>
      {loading && Array.from({ length: ASK_CITIES }, (_, i) => <div key={i} aria-hidden style={{ height: 92, borderRadius: radius.m, background: 'var(--system-gray5)', opacity: 0.6 }} />)}
      {cities.slice(0, ASK_CITIES).map((c) => {
        const photo = cityPhotoOf(c)
        const on = c.key === selected
        return (
          <Pressable
            key={c.key}
            plain
            onClick={() => onPick(c.key)}
            aria-pressed={on}
            style={{ position: 'relative', height: 92, borderRadius: radius.m, overflow: 'hidden', textAlign: 'left', outline: on ? `3px solid ${color.primary}` : undefined, background: color.brandFill }}
          >
            {photo && <img src={photo} alt="" referrerPolicy="no-referrer" style={{ position: 'absolute', inset: 0, width: '100%', height: '100%', objectFit: 'cover' }} />}
            <div style={{ position: 'absolute', inset: 0, background: 'linear-gradient(to top, rgba(0,0,0,0.65), rgba(0,0,0,0.05))' }} />
            <VStack spacing={1} align="start" style={{ position: 'absolute', left: 12, bottom: 10 }}>
              <Txt size={17} weight="heavy" style={{ color: color.onPhoto }}>{c.name}</Txt>
              <Txt size={12} style={{ color: color.onPhoto, opacity: 0.9 }}>{say(`촬영지 ${c.places.length}곳`, `${c.places.length} spots`)}</Txt>
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
          aria-pressed={o === value}
          style={{ padding: '16px 18px', borderRadius: radius.m, textAlign: 'left', background: o === value ? color.primaryFill : 'var(--system-background)', boxShadow: shadow.card }}
        >
          <Txt size={17} weight="semibold" style={{ color: o === value ? color.onPrimary : 'var(--label)' }}>{label(o)}</Txt>
        </Pressable>
      ))}
    </VStack>
  )
}

export const DAY_OPTIONS = [1, 2, 3, 4] as const
export const PACE_OPTIONS: readonly Pace[] = ['relaxed', 'normal', 'packed']
export { daysLabel, paceLabel }

/**
 * 화면 전체를 덮는 시안 화면의 틀 — 위 제목줄, 가운데 내용(스크롤), 아래 단추.
 * 덮는 창으로 알린다(role="dialog" + aria-modal): 열리면 첫 단추로 포커스가 가고 Esc 는 「뒤로」(A2). 제목은 h1(A4).
 */
export function ProtoScreen({ title, onBack, children, footer, step, hero, label }: {
  title: ReactNode
  onBack?: () => void
  children: ReactNode
  footer?: ReactNode
  step?: [number, number]
  /** 화면 맨 위를 가장자리까지 채우는 사진 머리. 있으면 위 줄(뒤로·단계 점)이 사진 위에 흰색으로 얹힌다. */
  hero?: ReactNode
  /** 화면 읽기에 알릴 이름 — 제목이 글자가 아닐 때(사진 머리 등) 준다. */
  label?: string
}) {
  const root = useRef<HTMLDivElement>(null)
  useEffect(() => {
    root.current?.querySelector<HTMLElement>('button:not([disabled]), [href], [tabindex="0"]')?.focus({ preventScroll: true })
  }, [])
  const bar = (
    <HStack spacing={8} style={{ padding: 'calc(env(safe-area-inset-top, 0px) + 12px) 16px 8px', minHeight: 44, ...(hero ? { position: 'absolute', top: 0, left: 0, right: 0, zIndex: 2 } : {}) }}>
      {onBack ? (
        <Pressable plain className="proto-press" onClick={onBack} aria-label={say('뒤로', 'Back')} style={{ width: 44, height: 44, display: 'grid', placeItems: 'center' }}>
          <Icon name="chevron.left" size={20} weight="semibold" color={hero ? 'white' : 'accent'} />
        </Pressable>
      ) : (
        <div style={{ width: 44 }} />
      )}
      <Spacer />
      {step && <StepDots at={step[0]} of={step[1]} onPhoto={!!hero} />}
      <Spacer />
      <MockBadge onPhoto={!!hero} />
    </HStack>
  )
  return (
    <div
      ref={root}
      role="dialog"
      aria-modal="true"
      aria-label={label ?? (typeof title === 'string' ? title : undefined)}
      onKeyDown={(e) => {
        if (e.key === 'Escape' && onBack) {
          e.stopPropagation()
          onBack()
        }
      }}
      style={{ position: 'absolute', inset: 0, zIndex: z.screen, display: 'flex', flexDirection: 'column', background: 'var(--grouped-background)' }}
    >
      {bar}
      <div className="scroll-y" style={{ flex: 1, minHeight: 0, ...contained }}>
        {hero}
        <VStack spacing={18} align="stretch" style={{ padding: hero ? '16px 20px 24px' : '4px 20px 24px' }}>
          {typeof title === 'string' ? <Txt as="h1" size={26} weight="heavy" style={{ ...heading, textWrap: 'balance' } as CSSProperties}>{title}</Txt> : title}
          {children}
        </VStack>
      </div>
      {footer && <div style={{ padding: '10px 20px calc(env(safe-area-inset-bottom, 0px) + 14px)', background: 'var(--grouped-background)', borderTop: '0.5px solid var(--separator)' }}>{footer}</div>}
    </div>
  )
}

/** 질문 단계 점 — 지금 단계만 길고 진하게. 화면 읽기에는 「1/3단계」. */
function StepDots({ at, of, onPhoto }: { at: number; of: number; onPhoto?: boolean }) {
  return (
    <HStack spacing={6} role="img" aria-label={say(`${of}단계 중 ${at}단계`, `Step ${at} of ${of}`)}>
      {Array.from({ length: of }, (_, i) => (
        <div key={i} style={{ width: i + 1 === at ? 20 : 7, height: 7, borderRadius: radius.pill, background: onPhoto ? (i + 1 <= at ? color.onPhoto : 'rgba(255,255,255,0.45)') : i + 1 <= at ? color.primary : 'var(--system-gray4)', transition: 'width 0.2s' }} />
      ))}
    </HStack>
  )
}

export function SecondaryButton({ children, onClick }: { children: ReactNode; onClick: () => void }) {
  return (
    <Pressable plain className="proto-press" onClick={onClick} style={{ width: '100%', padding: '15px 0', borderRadius: radius.m, border: `1.5px solid ${color.primary}`, background: 'var(--system-background)', textAlign: 'center' }}>
      <Txt size={17} weight="bold" style={{ color: color.primaryText }}>{children}</Txt>
    </Pressable>
  )
}

export function PrimaryButton({ children, onClick, disabled }: { children: ReactNode; onClick: () => void; disabled?: boolean }) {
  return (
    <Pressable
      plain
            className="proto-press"
      onClick={onClick}
      disabled={disabled}
      style={{ width: '100%', padding: '15px 0', borderRadius: radius.m, background: color.primaryFill, opacity: disabled ? 0.45 : 1, textAlign: 'center' }}
    >
      <Txt size={17} weight="bold" style={{ color: color.onPrimary }}>{children}</Txt>
    </Pressable>
  )
}

/** 추천 촬영지 한 줄 — 사진·이름·작품·담기 단추. */
export function SpotRow({ place, saved, onToggle, trailing }: { place: PlaceSummary; saved?: boolean; onToggle?: () => void; trailing?: ReactNode }) {
  const work = place.contents?.[0]?.title
  return (
    <HStack spacing={12} style={{ padding: 10, ...homeCard(radius.m) }}>
      <div style={{ width: 72, height: 72, flex: 'none', borderRadius: radius.s, overflow: 'hidden', background: color.brandFill }}>
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
            className="proto-press"
          onClick={onToggle}
          aria-pressed={saved}
          style={{ flex: 'none', minHeight: 44, padding: '0 14px', borderRadius: radius.pill, background: saved ? color.primaryFill : 'var(--system-gray6)' }}
        >
          <HStack spacing={4}>
            <Icon name={saved ? 'checkmark' : 'plus'} size={13} weight="bold" color={saved ? 'white' : color.primaryText} />
            <Txt size={13} weight="semibold" style={{ color: saved ? color.onPrimary : color.primaryText }}>{saved ? say('담음', 'Added') : say('담기', 'Add')}</Txt>
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
  const id = saved?.serverId ?? null
  if (id !== null) rememberProtoCourse(id)
  return id
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
      label={say(`${city.name}의 촬영지`, `Filming spots in ${city.name}`)}
      title={
        <VStack spacing={6} align="start">
          <Txt as="h1" size={26} weight="heavy" style={heading}>{say(`${city.name}의 촬영지`, `Filming spots in ${city.name}`)}</Txt>
          <Txt size={15} color="secondary">{say('가 보고 싶은 곳을 담으면 동선을 이어 코스로 만들어 드려요', "Add the places you like. We'll turn them into a route.")}</Txt>
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
        {city.places.length === 0 && <Txt size={15} color="secondary">{say('아직 이 도시의 촬영지가 없어요. 뒤로 가서 다른 도시를 골라 주세요', 'No filming spots here yet. Go back and pick another city.')}</Txt>}
        {city.places.slice(0, 30).map((p) => (
          <SpotRow key={p.id} place={p} saved={cart.placeIds.has(p.id)} onToggle={() => void (cart.placeIds.has(p.id) ? cartActions.remove(p.id) : cartActions.add(p.id))} />
        ))}
      </VStack>
    </ProtoScreen>
  )
}

/** 도시를 대표하는 사진 있는 촬영지 — 사진을 고정하지 않은 도시는 인기 1위. */
export function coverOf(city: HomeCity): PlaceSummary | undefined {
  return city.places.find(hasPhoto)
}

/** 도시 칸·바로가기에 쓰는 사진 주소 — 고른 대표 사진(웹에 동봉)이 먼저, 없으면 인기 1위 촬영지 사진. */
export function cityPhotoOf(city: HomeCity): string | null {
  return fixedCoverOf(city.key)?.src ?? coverOf(city)?.imageUrl ?? null
}

/** 사진 머리 한 장. */
export interface CitySlide {
  city: string
  place: { id: number; name: string; imageUrl: string; contents?: { title: string }[] }
}

/**
 * 사용자가 도시마다 고른 사진(`protoCovers.ts`)을 4초마다 넘긴다. 움직임 줄이기 설정이면 첫 장에 멈춘다.
 * 사진이 웹에 함께 실려 있어 촬영지 목록을 기다리지 않는다 — 목록에 기대던 때는 시험용 웹 첫 방문에서 15~23초 걸렸다(2026-11-09 실측, MZ2AZ-545).
 */
export function useCitySlides() {
  const slides: CitySlide[] = CITY_COVERS.map((c) => ({
    city: say(c.city.ko, c.city.en),
    place: { id: c.placeId, name: say(c.place.ko, c.place.en), imageUrl: c.src, contents: [{ title: say(c.work.ko, c.work.en) }] },
  }))
  const [at, setAt] = useState(0)
  useEffect(() => {
    if (slides.length < 2 || window.matchMedia?.('(prefers-reduced-motion: reduce)').matches) return
    const t = setInterval(() => setAt((i) => (i + 1) % slides.length), 4000)
    return () => clearInterval(t)
  }, [slides.length])
  return { slides, at, now: slides[at % Math.max(1, slides.length)] }
}

/** 겹쳐 바뀌는 사진들 — 부모가 position: relative 여야 한다. */
export function SlideImages({ slides, at }: { slides: readonly CitySlide[]; at: number }) {
  const n = slides.length
  const i0 = n === 0 ? 0 : at % n
  return (
    <>
      {slides.map((s, i) => {
        // 지금 장·사라지는 앞 장·다음 장만 받는다 — 여섯 장을 한꺼번에 받으면 첫 장이 대역폭을 나눠 늦게 뜬다.
        const shown = i === i0
        if (!shown && i !== (i0 + 1) % n && i !== (i0 - 1 + n) % n) return null
        return (
          <img
            key={s.place.id}
            src={s.place.imageUrl}
            alt=""
            referrerPolicy="no-referrer"
            decoding="async"
            fetchPriority={shown ? 'high' : 'low'}
            style={{
              position: 'absolute', inset: 0, width: '100%', height: '100%', objectFit: 'cover',
              opacity: shown ? 1 : 0, transform: shown ? 'scale(1.06)' : 'scale(1)', transition: 'opacity 1.2s ease, transform 5s ease-out',
            }}
          />
        )
      })}
    </>
  )
}

/**
 * 첫 질문 화면의 사진 머리 — 큰 도시들의 대표 촬영지 사진이 4초마다 천천히 겹쳐 바뀐다. 아래쪽은 화면 바탕색으로 녹아들어
 * 도시 칸과 이어진다. 사진마다 어디인지(도시 · 촬영지 · 작품) 작게 적는다 — 「예쁜 그림」 이 아니라 실제로 갈 수 있는 곳이라는 것.
 * 움직임 줄이기 설정이면 첫 사진에 멈춘다.
 */
export function CityHero({ children }: { children: ReactNode }) {
  const { slides, at, now } = useCitySlides()
  return (
    <div style={{ position: 'relative', height: 'min(46dvh, 390px)', minHeight: 300, overflow: 'hidden', background: color.brandFill }}>
      <SlideImages slides={slides} at={at} />
      {/* 위는 상태줄·단추가 읽히게 살짝, 아래는 글자가 읽히고 바탕색으로 이어지게 진하게. */}
      <div style={{ position: 'absolute', inset: 0, background: 'linear-gradient(to bottom, rgba(0,0,0,0.35) 0%, rgba(0,0,0,0) 28%, rgba(0,0,0,0.15) 52%, rgba(0,0,0,0.7) 82%, var(--grouped-background) 100%)' }} />
      <div style={{ position: 'absolute', left: 20, right: 20, bottom: 34 }}>{children}</div>
      {now && (
        <div style={{ position: 'absolute', right: 14, top: 'calc(env(safe-area-inset-top, 0px) + 52px)', maxWidth: '60%', textAlign: 'right' }}>
          <Txt size={12} weight="semibold" lines={1} style={{ color: color.onPhoto, opacity: 0.9, textShadow: '0 1px 3px rgba(0,0,0,0.5)' }}>
            {now.city} · {now.place.name}{now.place.contents?.[0]?.title ? ` · ${now.place.contents[0].title}` : ''}
          </Txt>
        </div>
      )}
    </div>
  )
}
