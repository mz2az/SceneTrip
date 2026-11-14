/*
 * 시안 화면 — A 질문 카드(홈 맨 위), B·C 질문 화면(전체 화면), C 코스 초안 미리보기, B·C 여행 카드(두 번째부터의 홈 맨 위), 시안 바꾸기 단추.
 * 계획: docs/project/plans/web-trip-first-prototypes.md
 */
import { type CSSProperties, type ReactNode, useEffect, useRef, useState } from 'react'
import type { components } from '@/api/generated/schema'
import { paths } from '@/app/routes'
import { useTabRouter } from '@/app/tabRouter'
import { Icon } from '@/design/Icon'
import { HStack, Spinner, Txt, VStack, homeCard } from '@/design/primitives'
import { cityCount } from '@/features/home/homeCities'
import { Pressable } from '@/features/route/ui/Press'
import { PinoMascot } from '@/design/PinoMascot'
import { useRouteState } from '@/features/route/store'
import { useStore } from '@/state/storage'
import { api, ok } from '@/api/client'
import { appEvent, track, webEvent } from '@/analytics'
import { cityPlanRequest, planFromGuide, removeStop, swapNearbyQuery, swapStop } from './cityPlan'
import { peekCardIndex } from './sceneFlip'
import { SceneFlipPhoto, hasFlippedBefore, prefersReducedMotion } from './SceneFlipPhoto'
import { CoachTour } from './CoachTour'
import {
  ASK_CITIES, ChoiceRow, CityGrid, CityHero, SlideImages, useCitySlides, CityPicker, DAY_OPTIONS, MockBadge, PrimaryButton, ProtoScreen, SecondaryButton, cityByKey, daysLabel,
  StepTransition, saveCourse, useCities,
} from './protoParts'
import { type Pace, type Proto, markFreshCourse, protoActions, protoStore, say, tripLength } from './protoState'
import { onceWhileBusy } from './onceWhileBusy'
import { showsPrototypeMarks } from './protoGate'
import { color, heading, radius, shadow, z } from './protoTokens'
import './proto.css'

type PlaceSummary = components['schemas']['PlaceSummary']

// ---------------------------------------------------------------- 시안 A — 홈 맨 위 질문 카드

export function AskCard() {
  const { cities } = useCities()
  const { slides, at, now } = useCitySlides()
  return (
    // 사진 카드 — 도시 대표 촬영지 사진이 천천히 바뀌고, 그 위에 흰 글씨와 반투명 도시 단추. 사진을 못 받으면 앱의 보라·하늘 그라데이션.
    <section aria-labelledby="proto-ask-title" style={{ position: 'relative', margin: '0 20px', borderRadius: radius.l, overflow: 'hidden', minHeight: 250, background: color.brandFill, boxShadow: shadow.card }}>
      <SlideImages slides={slides} at={at} />
      {/* 질문은 위에 붙이고 가운데는 비워 사진이 보이게 — 위·아래만 글자가 읽힐 만큼 어둡게(2026-11-09 사용자 지적). */}
      <div style={{ position: 'absolute', inset: 0, background: 'linear-gradient(to bottom, rgba(0,0,0,0.55) 0%, rgba(0,0,0,0.12) 34%, rgba(0,0,0,0) 52%, rgba(0,0,0,0.1) 66%, rgba(0,0,0,0.6) 100%)' }} />
      <VStack spacing={8} align="stretch" style={{ position: 'relative', padding: '16px 18px 14px', minHeight: 250 }}>
        <HStack spacing={8} align="start">
          <VStack spacing={4} align="start" style={{ flex: 1, minWidth: 0 }}>
            <Txt as="h2" id="proto-ask-title" size={24} weight="heavy" style={{ ...heading, color: color.onPhoto, textShadow: '0 2px 8px rgba(0,0,0,0.35)' }}>{say('어디로 여행 가세요?', 'Where are you traveling?')}</Txt>
            <Txt size={14} style={{ color: color.onPhoto, opacity: 0.92, textShadow: '0 1px 4px rgba(0,0,0,0.4)' }}>{say('도시를 고르면 그곳 드라마·영화 촬영지로 코스를 만들어 드려요', "Pick a city. We'll build a route through its K\u2011drama spots.")}</Txt>
          </VStack>
          <MockBadge onPhoto />
        </HStack>
        <div style={{ flex: 1, minHeight: 44 }} />
        {now && (
          <Txt size={12} weight="semibold" lines={1} style={{ color: color.onPhoto, opacity: 0.9, minWidth: 0, textShadow: '0 1px 3px rgba(0,0,0,0.5)' }}>
            {now.city} · {now.place.name}
          </Txt>
        )}
        <div className="scroll-x" style={{ margin: '2px -18px 0' }}>
          <HStack spacing={6} style={{ padding: '0 18px', width: 'max-content' }}>
            {/* 도시 단추 — 사진 위의 작은 반투명 알약(높이 32). 흰 통 알약(44)은 사진을 가리고 투박했다(2026-11-08 사용자 지적).
                누르는 칸은 위아래로 늘려 44 를 지킨다. */}
            {cities.slice(0, ASK_CITIES).map((c) => (
              <Pressable key={c.key} plain className="proto-press" onClick={() => { track(webEvent.selectHomeCity(c.key, 'proto_chip')); track(webEvent.planStart('city_chip')); protoActions.plan(c.key) }} style={{ flex: 'none', minHeight: 44, display: 'grid', alignItems: 'center' }}>
                <span
                  style={{
                    display: 'inline-flex', alignItems: 'baseline', gap: 4, height: 32, padding: '0 12px', borderRadius: radius.pill, lineHeight: '32px',
                    background: 'rgba(255,255,255,0.18)', border: '1px solid rgba(255,255,255,0.45)', backdropFilter: 'blur(8px)', WebkitBackdropFilter: 'blur(8px)',
                  }}
                >
                  <Txt size={13} weight="semibold" style={{ color: color.onPhoto }}>{c.name}</Txt>
                  <Txt size={11} style={{ color: 'rgba(255,255,255,0.75)' }}>{cityCount(c)}</Txt>
                </span>
              </Pressable>
            ))}
          </HStack>
        </div>
      </VStack>
    </section>
  )
}

// ---------------------------------------------------------------- B — 질문 화면(도시 → 며칠 → 어떻게)

type Step = 'city' | 'days' | 'how' | 'plan'
const STEP_ORDER: Record<Step, number> = { city: 0, days: 1, how: 2, plan: 3 }

function QuestionFlow() {
  const { cities, loading, failed, retry } = useCities()
  const startCity = useStore(protoStore, (s) => s.startCity)
  const [step, setStep] = useState<Step>(startCity ? 'days' : 'city')
  const [city, setCity] = useState<string | null>(startCity)
  const [days, setDays] = useState<number | null>(null)
  const close = () => protoActions.ask(false)
  // 코스 만들기의 단계 — 어디서 그만두는지 퍼널로 본다(web-proto-events.md).
  useEffect(() => {
    track(webEvent.planStep('city', step))
  }, [step])

  const content = ((): ReactNode => {
    if (step === 'city') {
      return (
        <ProtoScreen
          step={[1, 3]}
          onBack={close}
          title={null}
          label={say('한국 어디로 여행 가세요?', 'Where in Korea are you going?')}
          hero={
            <CityHero>
              <VStack spacing={10} align="start">
                <HStack spacing={8}>
                  <PinoMascot width={40} />
                  <Txt size={14} weight="semibold" style={{ color: color.onPhoto, textShadow: '0 1px 4px rgba(0,0,0,0.4)' }}>{say('드라마·영화 촬영지로 짜는 한국 여행', 'Korea trips through K-drama & film spots')}</Txt>
                </HStack>
                <Txt as="h1" size={32} weight="heavy" style={{ ...heading, color: color.onPhoto, lineHeight: 1.12, textShadow: '0 2px 10px rgba(0,0,0,0.35)' }}>{say('한국 어디로 여행 가세요?', 'Where in Korea are you going?')}</Txt>
              </VStack>
            </CityHero>
          }
        >
          {failed ? (
            <VStack spacing={10} align="start">
              <Txt size={15} color="secondary">{say('도시 목록을 못 불러왔어요', "Couldn't load the cities")}</Txt>
              <SecondaryButton onClick={retry}>{say('다시 시도', 'Try again')}</SecondaryButton>
            </VStack>
          ) : (
            <CityGrid cities={cities} loading={loading} selected={city} onPick={(k) => { track(webEvent.selectHomeCity(k, 'proto_grid')); setCity(k); setStep('days') }} />
          )}
          <Pressable plain className="proto-press" onClick={() => protoActions.ask(false)} style={{ alignSelf: 'center', padding: '12px 16px', minHeight: 44 }}>
            <Txt size={15} color="secondary">{say('아직 몰라요. 그냥 둘러볼게요', 'Not sure yet, just browsing')}</Txt>
          </Pressable>
        </ProtoScreen>
      )
    }

    const found = cityByKey(cities, city)
    const cityName = found?.name ?? city ?? ''
    if (step === 'days') {
      return (
        <ProtoScreen step={[2, 3]} onBack={startCity ? close : () => setStep('city')} title={say(`${cityName}에서 며칠 머무세요?`, `How long in ${cityName}?`)}>
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
            detail={say(`${cityName} 촬영지 ${found ? cityCount(found) : ''}곳을 보고 가고 싶은 곳만 담아요.`, `Browse ${found ? cityCount(found) : ''} spots in ${cityName} and add the ones you like.`)}
            onClick={() => {
              track(webEvent.planStep('city', 'picker'))
              protoActions.setTrip({ city: city!, days: days!, pace: 'normal', courseId: null })
              protoActions.ask(false)
              protoActions.pick(city)
            }}
          />
        </ProtoScreen>
      )
    }

    return <PlanPreview cityKey={city ?? ''} cityName={cityName} days={days!} pace="normal" onBack={() => setStep('how')} />
  })()
  // 단계가 바뀔 때 밀려 들어오고 나간다(§5) — 열쇠는 단계 이름, 순서는 도시 → 며칠 → 어떻게 → 추천 코스.
  return <StepTransition step={step} order={STEP_ORDER[step]}>{content}</StepTransition>
}

function HowCard({ primary, icon, title, detail, onClick }: { primary?: boolean; icon: string; title: string; detail: string; onClick: () => void }) {
  return (
    <Pressable
      plain
            className="proto-press"
      onClick={onClick}
      style={{ width: '100%', padding: 18, borderRadius: radius.l, textAlign: 'left', background: primary ? color.primaryFill : 'var(--system-background)', boxShadow: shadow.card, border: primary ? 'none' : `1.5px solid ${color.primary}` }}
    >
      <HStack spacing={14} align="start">
        <Icon name={icon} size={24} weight="semibold" color={primary ? 'white' : color.primaryText} />
        <VStack spacing={6} align="start" style={{ flex: 1, minWidth: 0 }}>
          <Txt size={19} weight="heavy" style={{ color: primary ? color.onPrimary : 'var(--label)' }}>{title}</Txt>
          <Txt size={15} style={{ color: primary ? 'rgba(255,255,255,0.92)' : 'var(--label-secondary)' }}>{detail}</Txt>
        </VStack>
        <Icon name="chevron.right" size={16} weight="semibold" color={primary ? 'white' : color.primaryText} />
      </HStack>
    </Pressable>
  )
}

// ---------------------------------------------------------------- 「알아서 짜 주세요」 — 서버 AI 추천 코스 미리보기

/**
 * 서버 AI 일정(`POST /guide/plan {city, days, pace}`, 계약 1.28.0)을 응답만으로 카드로 그린다 — 도시 촬영지 목록은 받지 않고, 못 받으면 앱 계산으로
 * 대신하지 않고 「다시 시도」 를 보인다(계획 city-ai-course.md §5, 정본 iOS `CityPlanPreview`). 사진 위 작은 단추로 다른 곳으로 바꾸거나 뺀다
 * (꾹 눌러 순서 바꾸기는 2026-11-10 뺐다). 사진을 누르면 뒤집혀 장면 그림(motion-works-drawer-parity.md §6).
 */
function PlanPreview({ cityKey, cityName, days, pace, onBack }: { cityKey: string; cityName: string; days: number; pace: Pace; onBack: () => void }) {
  const router = useTabRouter()
  const [plan, setPlan] = useState<PlaceSummary[][] | null>(null)
  /** AI 일정을 못 받았다(503·시간 초과·네트워크·카드로 그릴 곳이 하나도 없음) — 「다시 시도」. */
  const [planFailed, setPlanFailed] = useState(false)
  /** 다시 시도를 누를 때마다 오른다 — 일정을 다시 묻는다. */
  const [attempt, setAttempt] = useState(0)
  const [busy, setBusy] = useState(false)
  const [failed, setFailed] = useState(false)
  // 처음 한 번 살짝 돌 카드 — 화면이 뜰 때 한 번만 정한다(모든 카드가 뒤집히므로 첫 카드).
  const [peekFirst] = useState(() => peekCardIndex([true], hasFlippedBefore(), prefersReducedMotion()) === 0)

  useEffect(() => {
    let live = true
    setPlan(null)
    setPlanFailed(false)
    ok(api.POST('/guide/plan', { body: cityPlanRequest(cityKey, days, pace) }))
      .then((reply) => {
        if (!live) return
        const made = planFromGuide(reply.plan)
        if (made.length === 0) {
          track(webEvent.planFailed('city', 'empty'))
          setPlanFailed(true)
        } else {
          track(appEvent.generatePlan(days, 0))
          setPlan(made)
        }
      })
      .catch(() => {
        if (!live) return
        track(webEvent.planFailed('city', 'error'))
        setPlanFailed(true)
      })
    return () => { live = false }
  }, [cityKey, days, pace, attempt])
  // 빠르게 두 번 눌러 코스가 둘 생기지 않게(MZ2AZ-551) — busy 상태는 다음 그리기 전까지 두 번째 누름을 못 막는다.
  const saveLatest = useRef<() => Promise<void>>(async () => {})
  const saveOnce = useRef(onceWhileBusy(() => saveLatest.current()))

  if (!plan) {
    return (
      <ProtoScreen onBack={onBack} title={say('코스를 짜는 중이에요…', 'Planning your route…')}>
        {planFailed ? (
          <VStack spacing={10} align="start">
            <Txt size={15} color="secondary">{say('코스를 못 짰어요', "Couldn't plan a route")}</Txt>
            <SecondaryButton onClick={() => setAttempt((n) => n + 1)}>{say('다시 시도', 'Try again')}</SecondaryButton>
          </VStack>
        ) : (
          <HStack spacing={10}><Spinner /><Txt size={15} color="secondary">{say('AI 가 동선을 짜고 있어요', 'AI is planning your route')}</Txt></HStack>
        )}
      </ProtoScreen>
    )
  }

  // 「다른 곳으로 바꾸기」 — 그 카드 자리에서 가까운 그 도시 촬영지 10곳만 묻고, 아직 코스에 없는 첫 곳으로. 못 받으면 그대로.
  const swap = (day: number, idx: number) => {
    const spot = plan[day]?.[idx]
    if (!spot) return
    void ok(api.GET('/places', { params: { query: swapNearbyQuery(cityKey, spot) } }))
      .then((page) => setPlan((now) => (now ? swapStop(now, day, idx, page.items) : now)))
      .catch(() => {})
  }
  const firstId = plan.flat()[0]?.id

  const save = async () => {
    setBusy(true)
    setFailed(false)
    const id = await saveCourse(say(`${cityName} ${daysLabel(days)}`, `${cityName} · ${tripLength(days)} trip`), plan, pace, true)
    setBusy(false)
    if (id === null) {
      track(webEvent.planFailed('city', 'save'))
      return setFailed(true)
    }
    protoActions.setTrip({ city: cityKey, days, pace, courseId: id })
    protoActions.ask(false)
    protoActions.created({ city: cityName, days })
    markFreshCourse(id)
    router.openCourse(id)
  }
  saveLatest.current = save

  return (
    <ProtoScreen
      onBack={onBack}
      label={say(`${cityName} ${daysLabel(days)} 추천 코스`, `${cityName} · ${daysLabel(days)}`)}
      title={
        <VStack spacing={6} align="start">
          <Txt as="h1" size={26} weight="heavy" style={heading}>{say(`${cityName} ${daysLabel(days)} 추천 코스`, `${cityName} · ${daysLabel(days)}`)}</Txt>
          <Txt size={14} color="secondary">{say('빼거나 다른 곳으로 바꾼 뒤 저장하세요', 'Swap or remove spots, then save')}</Txt>
        </VStack>
      }
      footer={
        <VStack spacing={6} align="stretch">
          {failed && <Txt size={13} color="red">{say('저장하지 못했어요. 다시 눌러 주세요', "Couldn't save. Try again")}</Txt>}
          <PrimaryButton disabled={busy || plan.flat().length === 0} onClick={() => void saveOnce.current()}>{busy ? say('저장하는 중…', 'Saving…') : say('이 코스로 만들기', 'Make this course')}</PrimaryButton>
        </VStack>
      }
    >
      {plan.map((stops, day) => (
        <PlanDay
          key={day}
          day={day}
          stops={stops}
          peekId={peekFirst ? firstId : undefined}
          onSwap={(idx) => swap(day, idx)}
          onRemove={(idx) => setPlan((now) => (now ? removeStop(now, day, idx) : now))}
        />
      ))}
    </ProtoScreen>
  )
}

/** 하루치 카드 줄. 꾹 눌러 순서 바꾸기는 뺐다(2026-11-10 사용자 결정 — city-ai-course.md §5). */
function PlanDay({ day, stops, peekId, onSwap, onRemove }: {
  day: number
  stops: readonly PlaceSummary[]
  /** 처음 한 번 살짝 돌 카드(없으면 undefined). */
  peekId: number | undefined
  onSwap: (idx: number) => void
  onRemove: (idx: number) => void
}) {
  return (
    <VStack spacing={10} align="stretch">
      {/* 날 사이가 카드에 묻히지 않게 크게(2026-11-10 사용자 요청, iOS 17 → 24). */}
      <Txt as="h2" size={24} weight="heavy" style={heading}>{say(`${day + 1}일차`, `Day ${day + 1}`)} <Txt size={15} color="secondary">{say(`${stops.length}곳`, `${stops.length} spots`)}</Txt></Txt>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
        {stops.map((p, idx) => (
          <PlanSpotCard key={p.id} place={p} number={idx + 1} peek={p.id === peekId} onSwap={() => onSwap(idx)} onRemove={() => onRemove(idx)} />
        ))}
      </div>
    </VStack>
  )
}

/**
 * 추천 코스의 촬영지 카드 — 사진을 크게 위에, 그 아래 「어느 작품의」(보라 표) 「어떤 장소」(이름) 「어떤 장면」(설명). 처음 듣는 곳이라 이 셋이 한눈에
 * 들어와야 고를 수 있다(2026-11-09 사용자 지적). 바꾸기·빼기는 사진 위 작은 단추(앞뒤 모두 그대로 눌린다). 사진 칸은 누르면 뒤집힌다(`SceneFlipPhoto`).
 */
function PlanSpotCard({ place, number, peek, onSwap, onRemove }: { place: PlaceSummary; number: number; peek: boolean; onSwap: () => void; onRemove: () => void }) {
  const work = place.contents?.[0]?.title
  const scene = place.sceneDescription?.trim()
  return (
    <article style={{ ...homeCard(radius.l), overflow: 'hidden' }}>
      <div style={{ position: 'relative', aspectRatio: '16 / 9', background: color.brandFill, overflow: 'hidden' }}>
        {/* 다른 곳으로 바뀌면 열쇠가 바뀌어 앞면부터 다시 그린다. */}
        <SceneFlipPhoto key={place.id} place={place} peek={peek} />
        <span aria-hidden style={{ position: 'absolute', left: 10, top: 10, minWidth: 26, height: 26, padding: '0 8px', borderRadius: radius.pill, display: 'grid', placeItems: 'center', background: color.primaryFill, color: color.onPrimary, fontSize: 13, fontWeight: 800, fontVariantNumeric: 'tabular-nums', pointerEvents: 'none' }}>{number}</span>
        <HStack spacing={6} style={{ position: 'absolute', right: 8, top: 8 }}>
          <PhotoButton name="arrow.clockwise" label={say('다른 곳으로 바꾸기', 'Swap for another spot')} onClick={onSwap} />
          <PhotoButton name="xmark" label={say('빼기', 'Remove')} onClick={onRemove} />
        </HStack>
      </div>
      <VStack spacing={6} align="start" style={{ padding: '12px 14px 14px' }}>
        {work && (
          <HStack spacing={5} style={{ maxWidth: '100%', padding: '3px 9px', borderRadius: radius.pill, background: 'color-mix(in srgb, var(--home-purple) 12%, transparent)' }}>
            <Icon name="film" size={12} color={color.primaryText} />
            <Txt size={12} weight="bold" lines={1} style={{ color: color.primaryText }}>{work}</Txt>
          </HStack>
        )}
        <Txt as="h3" size={17} weight="heavy" lines={2} style={{ margin: 0 }}>{place.name}</Txt>
        {scene ? (
          <Txt size={14} color="secondary" lines={3} style={{ lineHeight: 1.45 }}>{scene}</Txt>
        ) : (
          place.address && <Txt size={13} color="tertiary" lines={1}>{place.address}</Txt>
        )}
      </VStack>
    </article>
  )
}

/** 사진 위의 작은 둥근 단추 — 사진 위에서도 읽히게 흰 바탕. 보이는 크기는 32, 누르는 자리는 44. */
function PhotoButton({ name, label, onClick }: { name: string; label: string; onClick: () => void }) {
  return (
    <Pressable plain className="proto-press" onClick={onClick} aria-label={label} title={label} style={{ position: 'relative', width: 32, height: 32, borderRadius: radius.pill, background: 'rgba(255,255,255,0.92)', boxShadow: '0 1px 4px rgba(0,0,0,0.18)', display: 'grid', placeItems: 'center' }}>
      <span aria-hidden style={{ position: 'absolute', inset: -6 }} />
      <Icon name={name} size={14} weight="bold" color="rgb(60,60,67)" />
    </Pressable>
  )
}

// ---------------------------------------------------------------- 덮개 — 질문 화면·촬영지 목록을 탭 위에 띄운다

export function ProtoLayer() {
  const router = useTabRouter()
  const state = useStore(protoStore, (s) => s)
  const { cities } = useCities()

  // 방금 만든 코스의 한 줄 안내는 6초 뒤 사라진다(결정 4).
  useEffect(() => {
    if (!state.justCreated) return
    const t = setTimeout(() => protoActions.created(null), 6000)
    return () => clearTimeout(t)
  }, [state.justCreated])

  if (!state.proto) return null

  if (state.justCreated) return <CreatedBanner city={state.justCreated.city} days={state.justCreated.days} />

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
          markFreshCourse(id)
          router.openCourse(id)
        }}
      />
    )
  }
  if (state.asking) return <QuestionFlow key={state.startCity ?? '-'} />
  return null
}

function CreatedBanner({ city, days }: { city: string; days: number }) {
  return (
    <div role="status" style={{ position: 'absolute', top: 'calc(env(safe-area-inset-top, 0px) + 64px)', left: 16, right: 16, zIndex: z.toast, padding: '12px 14px', borderRadius: radius.m, background: color.primaryFill, boxShadow: shadow.float }}>
      <Txt size={15} weight="semibold" style={{ color: color.onPrimary }}>
        {say(`${city} ${daysLabel(days)} 코스가 준비됐어요. 장소를 눌러 바꾸거나 끌어서 순서를 바꾸세요`, `Your ${tripLength(days)} ${city} course is ready. Tap a place to change it, or drag to reorder.`)}
      </Txt>
    </div>
  )
}

/** 오른쪽 아래 「시안」 단추 — A·B·C·끔 바꾸기와 「처음부터」. */
export function ProtoSwitcher() {
  // 스위치는 로컬 개발 서버에서만 — 시험용 웹에서는 새 화면이 기본이고 바꿀 일이 없다(MZ2AZ-539).
  if (!showsPrototypeMarks(import.meta.env.DEV)) return null
  return <Switcher />
}

function Switcher() {
  const proto = useStore(protoStore, (s) => s.proto)
  const [open, setOpen] = useState(false)
  // 「처음부터」 결과 — 몇 개 지웠고 몇 개 남았는지 단추 옆에 잠깐 보인다.
  const [note, setNote] = useState<string | null>(null)
  const reset = async () => {
    setOpen(false)
    setNote('지우는 중…')
    const r = await protoActions.reset(setNote)
    setNote(r.failed > 0 || r.left > 0 ? `지움 ${r.deleted} · 실패 ${r.failed} · 남음 ${r.left}` : `코스 ${r.deleted}개 지움`)
    setTimeout(() => setNote(null), 5000)
  }
  const options: ReadonlyArray<[Proto | null, string]> = [['a', 'A 질문 카드만'], ['b', 'B 질문 먼저'], [null, '끔(지금 화면)']]
  return (
    <div style={{ position: 'absolute', right: 0, top: '42%', zIndex: z.switcher }}>
      {open && (
        <VStack spacing={4} align="stretch" style={{ position: 'absolute', top: 34, right: 8, width: 200, padding: 8, borderRadius: radius.m, background: 'var(--system-background)', boxShadow: shadow.float }}>
          {options.map(([p, label]) => (
            <Pressable key={label} plain onClick={() => { protoActions.choose(p); setOpen(false) }} style={{ minHeight: 44, padding: '0 10px', borderRadius: radius.s, textAlign: 'left', background: p === proto ? 'var(--system-gray6)' : 'transparent' }}>
              <Txt size={14} weight={p === proto ? 'bold' : 'regular'}>{label}</Txt>
            </Pressable>
          ))}
          <Pressable plain className="proto-press" onClick={() => void reset()} style={{ minHeight: 44, padding: '0 10px', textAlign: 'left' }}>
            <Txt size={14} color="red">처음부터 (내 코스 모두 지우기)</Txt>
          </Pressable>
        </VStack>
      )}
      <Pressable plain className="proto-press" onClick={() => setOpen(!open)} aria-expanded={open} style={{ minHeight: 44, padding: '0 8px 0 12px', borderRadius: `${radius.pill}px 0 0 ${radius.pill}px`, background: 'rgb(255,204,128)', boxShadow: shadow.float }}>
        <Txt size={13} weight="bold" style={{ color: 'rgb(120,62,0)' }}>{proto ? `시안 ${proto.toUpperCase()}` : '시안 끔'}</Txt>
      </Pressable>
      {note && <span role="status" style={{ position: 'absolute', right: '100%', top: 2, marginRight: 8, whiteSpace: 'nowrap', padding: '4px 8px', borderRadius: radius.s, background: 'rgba(0,0,0,0.78)', color: color.onPhoto, fontSize: 12, fontWeight: 600 }}>{note}</span>}
    </div>
  )
}

/** 펼친 줄에 넣는 코스 수(사용자 결정 2026-11-07). */
const FLOAT_TRIPS = 5
/** 펼치고 접는 움직임 — 살짝 튀는 감속. */
const EASE = 'cubic-bezier(0.22, 1, 0.36, 1)'

/**
 * 홈 오른쪽 아래에 떠 있는 「여행 코스」 단추(사용자 요청 2026-11-07, 홈의 코스 카드를 대신한다). 단색 보라(흰 글씨 대비 6:1), 높이 44.
 *  - 여행 중인 코스가 있으면 단추가 곧 그 코스다 — 누르면 바로 그 코스로. 옆의 작은 단추로 줄을 펼친다.
 *  - 없으면 「여행 코스 짜기」. 누르면 **같은 줄에서** 단추가 사라지고 그 자리부터 내 코스(최대 5개)가 이름을 감싼 알약으로
 *    오른쪽에서 미끄러져 들어온다. 옆으로 끌어 넘기고, 맨 오른쪽 「✕」 로 접는다. 「+ 새 코스」 는 ✕ 바로 옆.
 *    코스가 하나도 없으면 펼치지 않고 바로 코스 짜기 질문으로.
 *  움직임 줄이기 설정이면 움직임 없이 바로 바뀐다.
 */
export function TripFloatBar() {
  const router = useTabRouter()
  const route = useRouteState()
  const [open, setOpen] = useState(false)
  const courses = [...route.routeCourses.filter((c) => c.isRunning), ...route.routeCourses.filter((c) => !c.isRunning)]
    .filter((c) => c.serverId !== null)
    .slice(0, FLOAT_TRIPS)
  const running = courses.find((c) => c.isRunning) ?? null
  const row = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if (open) row.current?.scrollTo?.({ left: row.current.scrollWidth })
  }, [open])

  const bottom = 'calc(var(--tabbar-height, 64px) + env(safe-area-inset-bottom, 0px) + 22px)'
  const soft = shadow.float
  const mainPill: CSSProperties = { height: 44, padding: '0 16px 0 14px', borderRadius: radius.pill, background: color.primaryFill, boxShadow: shadow.float, maxWidth: 240, color: color.onPrimary }
  const round: CSSProperties = { width: 44, height: 44, borderRadius: radius.pill, background: 'var(--system-background)', boxShadow: soft, display: 'grid', placeItems: 'center', flex: 'none' }
  const label = (text: string) => (
    <span style={{ minWidth: 0, overflow: 'hidden', whiteSpace: 'nowrap', textOverflow: 'ellipsis', fontSize: 14, fontWeight: 700, color: 'inherit' }}>{text}</span>
  )
  // 코스는 원래 코스 편집 화면으로 연다(사용자 결정 2026-11-07 — 하루 일정표 시안은 뺐다).
  const openCourse = (id: number) => {
    setOpen(false)
    router.openCourse(id)
  }
  const allCourses = () => {
    setOpen(false)
    router.openRoute()
  }
  // 「Plan a trip」·「+ 새 코스」 는 원래 앱의 코스 만들기로 — 코스 화면을 열고 「AI 로 짜기 / 직접 짜기」 를 고르게 한다(사용자 요청 2026-11-07).
  const newPlan = () => {
    // 시작은 갈래 시트에서 센다(plan_start fork_*) — 여기서도 세면 한 번 시작이 둘로 잡힌다(검증 지적). 줄을 누른 것은 home_tap 이 센다.
    setOpen(false)
    router.navigate(paths.courseNew)
  }
  // 펼친 줄의 알약 하나하나 — 오른쪽(단추 자리)에 가까운 것부터 차례로 들어온다.
  const enter = (i: number): CSSProperties => ({
    opacity: open ? 1 : 0,
    transform: open ? 'translateX(0) scale(1)' : 'translateX(28px) scale(0.92)',
    transition: `opacity 0.28s ${EASE} ${open ? i * 45 : 0}ms, transform 0.42s ${EASE} ${open ? i * 45 : 0}ms`,
  })
  const items = [
    { key: 'close', node: (
      <Pressable plain className="proto-press" onClick={() => setOpen(false)} aria-label={say('접기', 'Close')} style={round}>
        <Icon name="xmark" size={15} weight="bold" color={color.primaryText} />
      </Pressable>
    ) },
    { key: 'all', node: (
      <Pressable plain className="proto-press" onClick={allCourses} aria-label={say('전체 코스 보기', 'All trips')} title={say('전체 코스', 'All trips')} style={round}>
        <Icon name="list.bullet" size={16} weight="bold" color={color.primaryText} />
      </Pressable>
    ) },
    { key: 'new', node: (
      <Pressable plain className="proto-press" onClick={newPlan} style={{ height: 44, padding: '0 16px', borderRadius: radius.pill, background: 'var(--system-background)', boxShadow: soft, flex: 'none' }}>
        <HStack spacing={5}>
          <Icon name="plus" size={14} weight="bold" color={color.primaryText} />
          <Txt size={14} weight="bold" style={{ color: color.primaryText, whiteSpace: 'nowrap' }}>{say('새 코스', 'New')}</Txt>
        </HStack>
      </Pressable>
    ) },
    ...courses.map((c) => ({ key: c.uid, node: (
      <Pressable
        plain
            className="proto-press"
        onClick={() => openCourse(c.serverId!)}
        style={{ height: 44, padding: '0 16px', borderRadius: radius.pill, flex: 'none', maxWidth: 220, color: c.isRunning ? color.onPrimary : color.primaryText, background: c.isRunning ? color.primaryFill : 'var(--system-background)', boxShadow: soft }}
      >
        <HStack spacing={6}>
          <Icon name={c.isRunning ? 'location.north.fill' : 'paperplane'} size={13} color={c.isRunning ? 'white' : color.primaryText} />
          {label(c.title)}
        </HStack>
      </Pressable>
    ) })),
  ]

  return (
    <>
    <CoachTour
      id="home-plan-trip"
      active={!running && !open}
      delay={900}
      steps={[{
        target: '[data-coach="plan-trip"]',
        title: say('첫 여행 계획을 짜 보세요', 'Plan your first trip'),
        text: say('여기를 누르면 도시·작품·담은 곳 중 하나로 여행 코스를 만들 수 있어요.', 'Tap here to build a trip from a city, your favorite shows, or places you saved.'),
      }]}
    />
    <div
      onKeyDown={(e) => { if (e.key === 'Escape' && open) setOpen(false) }}
      style={{ position: 'absolute', left: 0, right: 0, bottom, height: 56, zIndex: z.float, pointerEvents: 'none' }}
    >
      {/* 접힌 단추 — 펼치면 오그라들며 사라진다. */}
      <div
        inert={open}
        style={{
          position: 'absolute', right: 14, top: 6, display: 'flex', alignItems: 'center', gap: 8,
          opacity: open ? 0 : 1, transform: open ? 'scale(0.8)' : 'scale(1)', transformOrigin: 'right center',
          transition: `opacity 0.2s ${EASE}, transform 0.3s ${EASE}`, pointerEvents: open ? 'none' : 'auto',
        }}
      >
        {running ? (
          <>
            <Pressable plain className="proto-press" onClick={() => setOpen(true)} aria-label={say('내 코스 펼치기', 'Show my trips')} aria-expanded={open} style={round}>
              <Icon name="list.bullet" size={16} weight="bold" color={color.primaryText} />
            </Pressable>
            <Pressable plain className="proto-press" onClick={() => openCourse(running.serverId!)} aria-label={say(`여행 중: ${running.title} 열기`, `On trip: open ${running.title}`)} style={mainPill}>
              <HStack spacing={7}>
                <Icon name="location.north.fill" size={14} color="white" />
                {label(running.title)}
              </HStack>
            </Pressable>
          </>
        ) : (
          <Pressable data-coach="plan-trip" plain className="proto-press" onClick={() => { track(webEvent.homeTap('plan_bar')); if (courses.length === 0) newPlan(); else setOpen(true) }} aria-expanded={courses.length === 0 ? undefined : open} style={mainPill}>
            <HStack spacing={7}>
              <Icon name="paperplane.fill" size={14} color="white" />
              {label(say('여행 코스 짜기', 'Plan a trip'))}
            </HStack>
          </Pressable>
        )}
      </div>
      {/* 펼친 줄 — 같은 높이, 오른쪽 끝(단추 자리)부터 늘어서고 옆으로 끌어 넘긴다. */}
      <div
        ref={row}
        className="scroll-x proto-trip-row"
        inert={!open}
        style={{ position: 'absolute', inset: 0, display: 'flex', pointerEvents: open ? 'auto' : 'none', visibility: open ? 'visible' : 'hidden', transition: `visibility 0s linear ${open ? 0 : 420}ms` }}
        data-sheet-nodrag
      >
        <div style={{ display: 'flex', flexDirection: 'row-reverse', alignItems: 'center', gap: 8, padding: '6px 14px', width: 'max-content', marginLeft: 'auto' }}>
          {items.map((it, i) => (
            <div key={it.key} style={{ flex: 'none', ...enter(i) }}>{it.node}</div>
          ))}
        </div>
      </div>
    </div>
    </>
  )
}

/** 떠 있는 여행 계획 줄을 언제 띄우나 — 시안이 켜져 있고, 홈 탭이고, 덮개·질문·촬영지 목록이 없을 때. */
export function ProtoHomeBar({ onHome }: { onHome: boolean }) {
  const state = useStore(protoStore, (s) => s)
  if (!state.proto || !onHome || state.asking || state.picking ) return null
  return <TripFloatBar />
}
