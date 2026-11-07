/*
 * 시안 화면 — A 질문 카드(홈 맨 위), B·C 질문 화면(전체 화면), C 코스 초안 미리보기, B·C 여행 카드(두 번째부터의 홈 맨 위), 시안 바꾸기 단추.
 * 계획: docs/project/plans/web-trip-first-prototypes.md
 */
import { useEffect, useState } from 'react'
import type { components } from '@/api/generated/schema'
import { useTabRouter } from '@/app/tabRouter'
import { Icon } from '@/design/Icon'
import { HStack, Spacer, Spinner, Txt, VStack, homeCard } from '@/design/primitives'
import type { HomeCity } from '@/features/home/homeCityModel'
import { Pressable } from '@/features/route/ui/Press'
import { useCart } from '@/state/cart'
import { useStore } from '@/state/storage'
import { mockPlanCityTrip, nearestUnused } from './mockPlan'
import {
  ASK_CITIES, ChoiceRow, CityGrid, CityPicker, DAY_OPTIONS, MockBadge, PACE_OPTIONS, PrimaryButton, ProtoScreen, SpotRow, cityByKey, daysLabel,
  paceLabel, saveCourse, useCities,
} from './protoParts'
import { type Pace, type Proto, protoActions, protoStore, say } from './protoState'

type PlaceSummary = components['schemas']['PlaceSummary']

// ---------------------------------------------------------------- 시안 A — 홈 맨 위 질문 카드

export function AskCard() {
  const { cities } = useCities()
  return (
    <VStack spacing={12} align="stretch" style={{ margin: '0 20px', padding: 18, ...homeCard(20), background: 'linear-gradient(135deg, color-mix(in srgb, var(--accent) 14%, var(--system-background)), var(--system-background))' }}>
      <HStack spacing={8}>
        <Txt size={20} weight="heavy">{say('어디로 여행 가세요?', 'Where are you traveling?')}</Txt>
        <Spacer />
        <MockBadge label="A" />
      </HStack>
      <Txt size={14} color="secondary">{say('도시를 고르면 그곳의 드라마·영화 촬영지를 골라 담고, 동선대로 코스를 만들어 드려요', "Pick a city, add the K-drama spots you like, and we'll build the route")}</Txt>
      <div className="scroll-x" style={{ margin: '0 -18px' }}>
        <HStack spacing={8} style={{ padding: '2px 18px', width: 'max-content' }}>
          {cities.slice(0, ASK_CITIES).map((c) => (
            <Pressable key={c.key} plain onClick={() => protoActions.pick(c.key)} style={{ flex: 'none', padding: '9px 14px', borderRadius: 999, background: 'var(--accent)' }}>
              <HStack spacing={5}>
                <Txt size={15} weight="semibold" color="white">{c.name}</Txt>
                <Txt size={12} color="white" style={{ opacity: 0.8 }}>{c.places.length}</Txt>
              </HStack>
            </Pressable>
          ))}
        </HStack>
      </div>
    </VStack>
  )
}

// ---------------------------------------------------------------- B·C — 두 번째부터 홈 맨 위의 여행 카드

export function TripHomeCard() {
  const router = useTabRouter()
  const { cities } = useCities()
  const trip = useStore(protoStore, (s) => s.trip)
  const cart = useCart()
  if (!trip) return null
  const city = cityByKey(cities, trip.city)
  const saved = city ? city.places.filter((p) => cart.placeIds.has(p.id)).length : 0
  return (
    <VStack spacing={12} align="stretch" style={{ margin: '0 20px', padding: 18, ...homeCard(20), background: 'linear-gradient(135deg, color-mix(in srgb, var(--accent) 16%, var(--system-background)), var(--system-background))' }}>
      <HStack spacing={8}>
        <Icon name="paperplane.fill" size={16} color="accent" />
        <Txt size={13} weight="semibold" color="accent">{say('내 여행', 'My trip')}</Txt>
        <Spacer />
        <MockBadge />
      </HStack>
      <Txt size={22} weight="heavy">{say(`${city?.name ?? trip.city} 여행 · ${daysLabel(trip.days)}`, `${city?.name ?? trip.city} · ${daysLabel(trip.days)}`)}</Txt>
      {trip.courseId !== null ? (
        <>
          <Txt size={14} color="secondary">{say('코스가 준비됐어요. 언제든 고칠 수 있어요', 'Your course is ready — edit it anytime')}</Txt>
          <PrimaryButton onClick={() => router.openCourse(trip.courseId!)}>{say('코스 보기 · 고치기', 'View & edit course')}</PrimaryButton>
        </>
      ) : (
        <>
          <Txt size={14} color="secondary">{saved > 0 ? say(`${saved}곳 담았어요`, `${saved} places added`) : say('아직 담은 곳이 없어요', 'No places added yet')}</Txt>
          <PrimaryButton onClick={() => protoActions.pick(trip.city)}>{say('촬영지 고르고 코스 만들기', 'Pick spots & make a course')}</PrimaryButton>
        </>
      )}
      <HStack spacing={16}>
        <Pressable plain onClick={() => protoActions.pick(trip.city)}>
          <Txt size={14} weight="semibold" color="accent">{say(`${city?.name ?? ''} 촬영지 더 보기`, 'More spots here')}</Txt>
        </Pressable>
        <Pressable plain onClick={() => protoActions.ask(true)}>
          <Txt size={14} weight="semibold" color="accent">{say('+ 다른 여행', '+ New trip')}</Txt>
        </Pressable>
      </HStack>
    </VStack>
  )
}

// ---------------------------------------------------------------- B·C — 질문 화면

type Step = 'city' | 'days' | 'pace' | 'plan'

function QuestionFlow({ proto }: { proto: Proto }) {
  const { cities, loading } = useCities()
  const trip = useStore(protoStore, (s) => s.trip)
  const [step, setStep] = useState<Step>('city')
  const [city, setCity] = useState<string | null>(null)
  const [days, setDays] = useState<number | null>(null)
  const [pace, setPace] = useState<Pace | null>(null)
  const close = trip ? () => protoActions.ask(false) : undefined

  if (step === 'city') {
    return (
      <ProtoScreen
        onBack={close}
        title={
          <VStack spacing={6} align="start">
            <Txt size={28} weight="heavy">{say('한국 어디로 여행 가세요?', 'Where in Korea are you going?')}</Txt>
            <Txt size={15} color="secondary">{say('그 도시의 드라마·영화 촬영지로 여행 코스를 짜 드려요', "We'll plan a route through K-drama & film spots there")}</Txt>
          </VStack>
        }
      >
        {loading ? <Spinner /> : <CityGrid cities={cities} selected={city} onPick={(k) => { setCity(k); setStep('days') }} />}
        <Pressable plain onClick={() => protoActions.ask(false)} style={{ alignSelf: 'center', padding: 8 }}>
          <Txt size={14} color="secondary">{say('아직 몰라요 — 그냥 둘러볼게요', 'Not sure yet — just browsing')}</Txt>
        </Pressable>
      </ProtoScreen>
    )
  }

  const cityName = cityByKey(cities, city)?.name ?? city ?? ''
  if (step === 'days') {
    return (
      <ProtoScreen onBack={() => setStep('city')} title={say(`${cityName}에서 며칠 머무세요?`, `How long in ${cityName}?`)}>
        <ChoiceRow
          options={DAY_OPTIONS}
          value={days}
          label={daysLabel}
          onPick={(n) => {
            setDays(n)
            if (proto === 'c') return setStep('pace')
            protoActions.setTrip({ city: city!, days: n, pace: 'normal', courseId: null })
            protoActions.ask(false)
            protoActions.pick(city)
          }}
        />
      </ProtoScreen>
    )
  }

  if (step === 'pace') {
    return (
      <ProtoScreen onBack={() => setStep('days')} title={say('얼마나 빡빡하게 다닐까요?', 'How packed should each day be?')}>
        <ChoiceRow
          options={PACE_OPTIONS}
          value={pace}
          label={(p) => `${paceLabel(p)} · ${say(`하루 ${p === 'relaxed' ? 3 : p === 'normal' ? 5 : 7}곳`, `${p === 'relaxed' ? 3 : p === 'normal' ? 5 : 7} spots a day`)}`}
          onPick={(p) => { setPace(p); setStep('plan') }}
        />
      </ProtoScreen>
    )
  }

  return <PlanPreview city={cityByKey(cities, city)} days={days!} pace={pace!} onBack={() => setStep('pace')} />
}

// ---------------------------------------------------------------- C — 코스 초안 미리보기(껍데기 API)

function PlanPreview({ city, days, pace, onBack }: { city: HomeCity | null; days: number; pace: Pace; onBack: () => void }) {
  const router = useTabRouter()
  const [plan, setPlan] = useState<PlaceSummary[][] | null>(null)
  const [busy, setBusy] = useState(false)
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    if (!city) return
    let live = true
    void mockPlanCityTrip({ places: city.places, days, pace }).then((p) => { if (live) setPlan(p) })
    return () => { live = false }
  }, [city, days, pace])

  if (!city || !plan) {
    return (
      <ProtoScreen onBack={onBack} title={say('코스를 짜는 중이에요…', 'Planning your route…')}>
        <HStack spacing={10}><Spinner /><Txt size={15} color="secondary">{say('촬영지를 동선대로 잇고 있어요', 'Linking spots into a route')}</Txt></HStack>
      </ProtoScreen>
    )
  }

  const used = new Set(plan.flat().map((p) => p.id))
  const edit = (day: number, fn: (stops: PlaceSummary[]) => PlaceSummary[]) => setPlan(plan.map((d, i) => (i === day ? fn([...d]) : d)))
  const swap = (day: number, idx: number) => {
    const next = nearestUnused(plan[day][idx], city.places, used)
    if (next) edit(day, (s) => { s[idx] = next; return s })
  }
  const move = (day: number, idx: number, by: -1 | 1) => edit(day, (s) => {
    const j = idx + by
    if (j < 0 || j >= s.length) return s
    ;[s[idx], s[j]] = [s[j], s[idx]]
    return s
  })

  const save = async () => {
    setBusy(true)
    setFailed(false)
    const id = await saveCourse(say(`${city.name} ${daysLabel(days)}`, `${city.name} ${daysLabel(days)}`), plan, pace, true)
    setBusy(false)
    if (id === null) return setFailed(true)
    protoActions.setTrip({ city: city.key, days, pace, courseId: id })
    protoActions.ask(false)
    router.openCourse(id)
  }

  return (
    <ProtoScreen
      onBack={onBack}
      title={
        <VStack spacing={6} align="start">
          <HStack spacing={8}><Txt size={26} weight="heavy">{say(`${city.name} ${daysLabel(days)} 추천 코스`, `${city.name} · ${daysLabel(days)}`)}</Txt></HStack>
          <HStack spacing={6}><MockBadge label={say('껍데기 AI', 'Mock AI')} /><Txt size={13} color="secondary">{say('빼고 바꾸고 순서를 고친 뒤 저장하세요', 'Remove, swap or reorder, then save')}</Txt></HStack>
        </VStack>
      }
      footer={
        <VStack spacing={6} align="stretch">
          {failed && <Txt size={13} color="red">{say('저장하지 못했어요. 다시 눌러 주세요', "Couldn't save. Try again")}</Txt>}
          <PrimaryButton disabled={busy || plan.flat().length === 0} onClick={() => void save()}>{busy ? say('저장하는 중…', 'Saving…') : say('이 코스로 저장', 'Save this course')}</PrimaryButton>
        </VStack>
      }
    >
      {plan.map((stops, day) => (
        <VStack key={day} spacing={10} align="stretch">
          <Txt size={17} weight="heavy">{say(`${day + 1}일차`, `Day ${day + 1}`)} <Txt size={13} color="secondary">{say(`${stops.length}곳`, `${stops.length} spots`)}</Txt></Txt>
          {stops.map((p, idx) => (
            <SpotRow
              key={`${p.id}-${idx}`}
              place={p}
              trailing={
                <VStack spacing={6}>
                  <HStack spacing={6}>
                    <IconButton name="arrow.up.arrow.down" label={say('아래로', 'Move down')} onClick={() => move(day, idx, idx === stops.length - 1 ? -1 : 1)} />
                    <IconButton name="arrow.clockwise" label={say('다른 곳으로', 'Swap')} onClick={() => swap(day, idx)} />
                    <IconButton name="xmark" label={say('빼기', 'Remove')} onClick={() => edit(day, (s) => s.filter((_, i) => i !== idx))} />
                  </HStack>
                </VStack>
              }
            />
          ))}
        </VStack>
      ))}
    </ProtoScreen>
  )
}

function IconButton({ name, label, onClick }: { name: string; label: string; onClick: () => void }) {
  return (
    <Pressable plain onClick={onClick} aria-label={label} title={label} style={{ width: 30, height: 30, borderRadius: 999, background: 'var(--system-gray6)', display: 'grid', placeItems: 'center' }}>
      <Icon name={name} size={14} weight="semibold" color="accent" />
    </Pressable>
  )
}

// ---------------------------------------------------------------- 덮개 — 질문 화면·촬영지 목록을 탭 위에 띄운다

export function ProtoLayer() {
  const router = useTabRouter()
  const state = useStore(protoStore, (s) => s)
  const { cities } = useCities()
  if (!state.proto) return null

  if (state.picking) {
    const city = cityByKey(cities, state.picking)
    if (!city) return null
    const days = state.proto === 'a' ? null : (state.trip?.days ?? null)
    return (
      <CityPicker
        city={city}
        days={days}
        onBack={() => protoActions.pick(null)}
        onCreated={(id, n) => {
          protoActions.setTrip({ city: city.key, days: n, pace: state.trip?.pace ?? 'normal', courseId: id })
          protoActions.pick(null)
          router.openCourse(id)
        }}
      />
    )
  }
  if (state.asking && (state.proto === 'b' || state.proto === 'c')) return <QuestionFlow proto={state.proto} />
  return null
}

/** 오른쪽 아래 「시안」 단추 — A·B·C·끔 바꾸기와 「처음부터」. */
export function ProtoSwitcher() {
  const proto = useStore(protoStore, (s) => s.proto)
  const [open, setOpen] = useState(false)
  const options: ReadonlyArray<[Proto | null, string]> = [['a', 'A 질문 카드'], ['b', 'B 질문 먼저'], ['c', 'C 바로 추천 코스'], [null, '끔(지금 화면)']]
  return (
    <div style={{ position: 'absolute', right: 12, bottom: 'calc(var(--tabbar-height, 64px) + env(safe-area-inset-bottom, 0px) + 12px)', zIndex: 60 }}>
      {open && (
        <VStack spacing={4} align="stretch" style={{ marginBottom: 8, padding: 8, borderRadius: 14, background: 'var(--system-background)', boxShadow: '0 6px 24px rgba(0,0,0,0.18)' }}>
          {options.map(([p, label]) => (
            <Pressable key={label} plain onClick={() => { protoActions.choose(p); setOpen(false) }} style={{ padding: '8px 10px', borderRadius: 8, textAlign: 'left', background: p === proto ? 'var(--system-gray6)' : 'transparent' }}>
              <Txt size={14} weight={p === proto ? 'bold' : 'regular'}>{label}</Txt>
            </Pressable>
          ))}
          <Pressable plain onClick={() => { protoActions.reset(); setOpen(false) }} style={{ padding: '8px 10px', textAlign: 'left' }}>
            <Txt size={14} color="red">처음부터 (여행 지우기)</Txt>
          </Pressable>
        </VStack>
      )}
      <Pressable plain onClick={() => setOpen(!open)} style={{ float: 'right', padding: '8px 12px', borderRadius: 999, background: 'rgb(255,149,0)', boxShadow: '0 4px 14px rgba(0,0,0,0.2)' }}>
        <Txt size={13} weight="bold" color="white">{proto ? `시안 ${proto.toUpperCase()}` : '시안 끔'}</Txt>
      </Pressable>
    </div>
  )
}
