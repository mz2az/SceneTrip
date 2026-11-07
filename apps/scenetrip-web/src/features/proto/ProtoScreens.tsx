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
import { PinoMascot } from '@/design/PinoMascot'
import { useRouteState } from '@/features/route/store'
import { useCart } from '@/state/cart'
import { useStore } from '@/state/storage'
import { mockPlanCityTrip, nearestUnused } from './mockPlan'
import {
  ASK_CITIES, ChoiceRow, CityGrid, CityPicker, DAY_OPTIONS, MockBadge, PrimaryButton, ProtoScreen, SecondaryButton, SpotRow, cityByKey, daysLabel,
  saveCourse, useCities,
} from './protoParts'
import { type Pace, type Proto, protoActions, protoStore, say, tripLength } from './protoState'

type PlaceSummary = components['schemas']['PlaceSummary']

// ---------------------------------------------------------------- 시안 A — 홈 맨 위 질문 카드

export function AskCard() {
  const { cities } = useCities()
  return (
    <VStack spacing={12} align="stretch" style={{ margin: '0 20px', padding: 18, ...homeCard(20), background: 'linear-gradient(135deg, color-mix(in srgb, var(--accent) 14%, var(--system-background)), var(--system-background))' }}>
      <HStack spacing={8}>
        <Txt size={20} weight="heavy">{say('어디로 여행 가세요?', 'Where are you traveling?')}</Txt>
        <Spacer />
        <MockBadge />
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
        <Pressable plain onClick={() => protoActions.pick(trip.city)} style={{ minHeight: 44, display: 'flex', alignItems: 'center' }}>
          <Txt size={14} weight="semibold" color="accent">{say(`${city?.name ?? ''} 촬영지 더 보기`, 'More spots here')}</Txt>
        </Pressable>
        <Pressable plain onClick={() => protoActions.ask(true, true)} style={{ minHeight: 44, display: 'flex', alignItems: 'center' }}>
          <Txt size={14} weight="semibold" color="accent">{say('+ 다른 여행', '+ New trip')}</Txt>
        </Pressable>
      </HStack>
    </VStack>
  )
}

// ---------------------------------------------------------------- B — 질문 화면(도시 → 며칠 → 어떻게)

type Step = 'city' | 'days' | 'how' | 'plan'

function QuestionFlow() {
  const { cities, loading, failed, retry } = useCities()
  const trip = useStore(protoStore, (s) => s.trip)
  const [step, setStep] = useState<Step>('city')
  const [city, setCity] = useState<string | null>(null)
  const [days, setDays] = useState<number | null>(null)
  const close = trip ? () => protoActions.ask(false) : undefined

  if (step === 'city') {
    return (
      <ProtoScreen
        step={[1, 3]}
        onBack={close}
        title={
          <VStack spacing={10} align="start">
            <HStack spacing={10}>
              <PinoMascot width={44} />
              <Txt size={14} weight="semibold" color="accent">{say('드라마·영화 촬영지로 짜는 한국 여행', 'Korea trips through K-drama & film spots')}</Txt>
            </HStack>
            <Txt size={28} weight="heavy">{say('한국 어디로 여행 가세요?', 'Where in Korea are you going?')}</Txt>
          </VStack>
        }
      >
        {failed ? (
          <VStack spacing={10} align="start">
            <Txt size={15} color="secondary">{say('도시 목록을 못 불러왔어요', "Couldn't load the cities")}</Txt>
            <SecondaryButton onClick={retry}>{say('다시 시도', 'Try again')}</SecondaryButton>
          </VStack>
        ) : (
          <CityGrid cities={cities} loading={loading} selected={city} onPick={(k) => { setCity(k); setStep('days') }} />
        )}
        <Pressable plain onClick={() => protoActions.ask(false)} style={{ alignSelf: 'center', padding: '12px 16px', minHeight: 44 }}>
          <Txt size={15} color="secondary">{say('아직 몰라요 — 그냥 둘러볼게요', 'Not sure yet — just browsing')}</Txt>
        </Pressable>
      </ProtoScreen>
    )
  }

  const found = cityByKey(cities, city)
  const cityName = found?.name ?? city ?? ''
  if (step === 'days') {
    return (
      <ProtoScreen step={[2, 3]} onBack={() => setStep('city')} title={say(`${cityName}에서 며칠 머무세요?`, `How long in ${cityName}?`)}>
        <ChoiceRow options={DAY_OPTIONS} value={days} label={daysLabel} onPick={(n) => { setDays(n); setStep('how') }} />
      </ProtoScreen>
    )
  }

  if (step === 'how') {
    return (
      <ProtoScreen
        step={[3, 3]}
        onBack={() => setStep('days')}
        title={say('코스를 어떻게 짤까요?', 'How should we plan it?')}
      >
        {/* 두 갈래를 같은 크기의 카드로 — 무엇을 하게 되는지 한 줄씩. 처음 온 사람에게 부담이 적은 「알아서」 를 위에 채운 색으로(결정 3). */}
        <HowCard
          primary
          icon="sparkles"
          title={say('알아서 짜 주세요', 'Plan it for me')}
          detail={say(`${cityName} 인기 촬영지를 동선대로 이어 ${daysLabel(days!)} 코스를 바로 만들어요. 빼고 바꾸는 건 그다음에.`, `We'll link ${cityName}'s top filming spots into a ${tripLength(days!)} route right away. Tweak it after.`)}
          onClick={() => setStep('plan')}
        />
        <HowCard
          icon="checkmark.circle.fill"
          title={say('직접 고를게요', "I'll pick the spots")}
          detail={say(`${cityName} 촬영지 ${found?.places.length ?? ''}곳을 보고 가고 싶은 곳만 담아요.`, `Browse ${found?.places.length ?? ''} spots in ${cityName} and add the ones you like.`)}
          onClick={() => {
            protoActions.setTrip({ city: city!, days: days!, pace: 'normal', courseId: null })
            protoActions.ask(false)
            protoActions.pick(city)
          }}
        />
      </ProtoScreen>
    )
  }

  return <PlanPreview city={found} days={days!} pace="normal" onBack={() => setStep('how')} />
}

function HowCard({ primary, icon, title, detail, onClick }: { primary?: boolean; icon: string; title: string; detail: string; onClick: () => void }) {
  return (
    <Pressable
      plain
      onClick={onClick}
      style={{ width: '100%', padding: 18, borderRadius: 18, textAlign: 'left', background: primary ? 'var(--accent)' : 'var(--system-background)', boxShadow: 'var(--card-shadow)', border: primary ? 'none' : '1.5px solid var(--accent)' }}
    >
      <HStack spacing={14} align="start">
        <Icon name={icon} size={24} weight="semibold" color={primary ? 'white' : 'accent'} />
        <VStack spacing={6} align="start" style={{ flex: 1, minWidth: 0 }}>
          <Txt size={19} weight="heavy" style={{ color: primary ? '#fff' : 'var(--label)' }}>{title}</Txt>
          <Txt size={15} style={{ color: primary ? 'rgba(255,255,255,0.9)' : 'var(--label-secondary)' }}>{detail}</Txt>
        </VStack>
        <Icon name="chevron.right" size={16} weight="semibold" color={primary ? 'white' : 'accent'} />
      </HStack>
    </Pressable>
  )
}

// ---------------------------------------------------------------- 「알아서 짜 주세요」 — 코스 초안 미리보기(껍데기 API)

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
    const id = await saveCourse(say(`${city.name} ${daysLabel(days)}`, `${city.name} · ${tripLength(days)} trip`), plan, pace, true)
    setBusy(false)
    if (id === null) return setFailed(true)
    protoActions.setTrip({ city: city.key, days, pace, courseId: id })
    protoActions.ask(false)
    protoActions.created({ city: city.name, days })
    router.openCourse(id)
  }

  return (
    <ProtoScreen
      onBack={onBack}
      title={
        <VStack spacing={6} align="start">
          <HStack spacing={8}><Txt size={26} weight="heavy">{say(`${city.name} ${daysLabel(days)} 추천 코스`, `${city.name} · ${daysLabel(days)}`)}</Txt></HStack>
          <HStack spacing={6}><MockBadge label={say('껍데기 AI', 'Mock AI')} /><Txt size={14} color="secondary">{say('빼고 바꾸고 순서를 고친 뒤 저장하세요', 'Remove, swap or reorder, then save')}</Txt></HStack>
        </VStack>
      }
      footer={
        <VStack spacing={6} align="stretch">
          {failed && <Txt size={13} color="red">{say('저장하지 못했어요. 다시 눌러 주세요', "Couldn't save. Try again")}</Txt>}
          <PrimaryButton disabled={busy || plan.flat().length === 0} onClick={() => void save()}>{busy ? say('저장하는 중…', 'Saving…') : say('이 코스로 저장', 'Save this course')}</PrimaryButton>
        </VStack>
      }
    >
      {plan.flat().length === 0 && <Txt size={15} color="secondary">{say('이 도시는 촬영지가 적어서 코스를 못 짰어요. 뒤로 가서 「직접 고를게요」 를 눌러 주세요', 'Not enough spots here to plan a route — go back and pick them yourself')}</Txt>}
      {plan.map((stops, day) => (
        <VStack key={day} spacing={10} align="stretch">
          <Txt size={17} weight="heavy">{say(`${day + 1}일차`, `Day ${day + 1}`)} <Txt size={13} color="secondary">{say(`${stops.length}곳`, `${stops.length} spots`)}</Txt></Txt>
          {stops.map((p, idx) => (
            <SpotRow
              key={`${p.id}-${idx}`}
              place={p}
              trailing={
                <VStack spacing={4}>
                  <HStack spacing={4}>
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
    <Pressable plain onClick={onClick} aria-label={label} title={label} style={{ width: 44, height: 44, borderRadius: 999, background: 'var(--system-gray6)', display: 'grid', placeItems: 'center' }}>
      <Icon name={name} size={16} weight="semibold" color="accent" />
    </Pressable>
  )
}

// ---------------------------------------------------------------- 덮개 — 질문 화면·촬영지 목록을 탭 위에 띄운다

export function ProtoLayer() {
  const router = useTabRouter()
  const state = useStore(protoStore, (s) => s)
  const cart = useCart()
  const route = useRouteState()
  const { cities } = useCities()

  // 처음 온 사람만 전체 화면 질문(결정 2) — 장바구니·코스를 받아 본 뒤 하나라도 있으면 질문을 거둔다.
  const loaded = cart.loadState === 'loaded' && route.courseList === 'loaded'
  const firstTimer = loaded && cart.placeIds.size === 0 && route.routeCourses.length === 0
  useEffect(() => {
    if (state.asking && !state.forced && loaded && !firstTimer) protoActions.ask(false)
  }, [state.asking, state.forced, loaded, firstTimer])

  // 방금 만든 코스의 한 줄 안내는 6초 뒤 사라진다(결정 4).
  useEffect(() => {
    if (!state.justCreated) return
    const t = setTimeout(() => protoActions.created(null), 6000)
    return () => clearTimeout(t)
  }, [state.justCreated])

  if (!state.proto) return null

  if (state.justCreated) {
    const { city, days } = state.justCreated
    return (
      <div role="status" style={{ position: 'absolute', top: 'calc(env(safe-area-inset-top, 0px) + 64px)', left: 16, right: 16, zIndex: 70, padding: '12px 14px', borderRadius: 14, background: 'var(--accent)', boxShadow: '0 6px 20px rgba(0,0,0,0.18)' }}>
        <Txt size={15} weight="semibold" color="white">
          {say(`${city} ${daysLabel(days)} 코스가 준비됐어요 — 장소를 눌러 바꾸거나 끌어서 순서를 바꾸세요`, `Your ${tripLength(days)} ${city} course is ready — tap a place to change it, drag to reorder`)}
        </Txt>
      </div>
    )
  }

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
          protoActions.created({ city: city.name, days: n })
          router.openCourse(id)
        }}
      />
    )
  }
  if (state.proto === 'b' && state.asking && (state.forced || firstTimer)) return <QuestionFlow />
  return null
}

/** 오른쪽 아래 「시안」 단추 — A·B·C·끔 바꾸기와 「처음부터」. */
export function ProtoSwitcher() {
  const proto = useStore(protoStore, (s) => s.proto)
  const [open, setOpen] = useState(false)
  const options: ReadonlyArray<[Proto | null, string]> = [['a', 'A 질문 카드만'], ['b', 'B 질문 먼저'], [null, '끔(지금 화면)']]
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
