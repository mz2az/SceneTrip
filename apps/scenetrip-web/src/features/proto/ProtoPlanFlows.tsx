/*
 * 새 코스의 두 흐름 시안 — 여행 앱 레퍼런스 조사(2026-11-07)를 따라 질문을 줄였다. 로컬 개발 서버 + 시안일 때만 원래 마법사 대신 뜬다.
 *
 * ① AI 로 짜기: 다섯 화면(기간·날짜·작품·속도·확인) → 두 화면.
 *    - 「어떤 여행이에요?」 한 화면에 기간·떠나는 날(선택)·속도. Tripadvisor·Trip.com·트리플이 3~4문항으로 끝내고 날짜를 강요하지 않는 것을 따랐다.
 *    - 「누구의 장면을 따라갈까요?」 포스터 격자. 찜한 작품을 미리 골라 둔다(서버가 제목을 5개까지만 받는다).
 *      서버의 작품 전부를 30개씩 이어 받고, 위 검색칸으로 이름을 찾는다. 고른 작품은 검색·쪽 넘김과 상관없이 남는다(MZ2AZ-547).
 *    - 짜는 동안은 전용 화면 — 고른 것 요약과 지금 하는 일 세 줄. 버튼 안 스피너 하나만 돌던 것을 바꿨다. 결과는 원래 편집 화면(날짜별 목록 + 지도).
 * ② 담은 곳으로: 기간 두 화면 → 빈 편집 화면 이던 것을, 기간·날짜 한 화면 → 담은 곳이 날마다 나뉘어 들어간 편집 화면으로.
 *    - 담은 곳을 고르는 단계와 나눠 담기 미리보기는 뺐다 — 「길이·날짜만 정하고 이후에 알아서 수정」(2026-11-07 사용자 결정).
 *    - 가까운 곳끼리 이어 날마다 나눈다(Wanderlog 「Auto-fill day」·마이로의 자동 배치). 빼기·순서는 편집 화면에서.
 * 모양: 회색 바탕 위 흰 묶음 칸에 얇은 구분선 — 앱 원래 화면(insetGrouped)과 같은 틀. 칩·칸마다 그림자를 두지 않는다
 *   (「빈 도화지에 틀도 없이 적어 놓은 느낌」 2026-11-07 사용자 지적).
 * 계획: docs/project/plans/web-trip-first-prototypes.md.
 */
import { useEffect, useRef, useState } from 'react'
import { Icon } from '@/design/Icon'
import { HStack, Spacer, Spinner, Txt, VStack } from '@/design/primitives'
import { AIConsentSheet, LocationConsentSheet, useAIConsent, useLocationConsent } from '@/features/profile/consent'
import { RouteEditor } from '@/features/route/editor/RouteEditor'
import {
  type ContentSummary, PACE_CAPTION, PACE_SYMBOL, PACE_TITLE, type PlaceSummary, type RouteCourse, type RoutePace, SPAN_NIGHTS,
  addDays, formatDay, makeCourse, makeDay, makeStop, spanDays, toIso,
} from '@/features/route/models'
import { type PlanFailure, planFailureMessage } from '@/features/route/planFailure'
import { guideDraft, routeActions, routeStore } from '@/features/route/store'
import { GlassDialog } from '@/features/route/ui/GlassDialog'
import { Pressable } from '@/features/route/ui/Press'
import { Calendar } from '@/features/route/wizard/Calendar'
import { tr, trAt } from '@/i18n'
import { type CartItem, useCart } from '@/state/cart'
import { likeActions, useLikes } from '@/state/likes'
import { PrimaryButton, ProtoScreen, StepTransition } from './protoParts'
import { placesForNewCourse } from './savedPlan'
import { ProtoWorkStep } from './ProtoWizardSteps'
import { daysLabel, say } from './protoState'
import { color, radius, shadow } from './protoTokens'
import { useWorksBrowse } from './useWorksBrowse'
import { MAX_PICKED_WORKS, appendPage, favoritesFirst, requestTitles, togglePick } from './worksBrowse'
import './proto.css'
import { smallImage } from '@/shared/smallImage'

/** 서버(`POST /guide/plan`)가 받는 작품 제목 수. 넘게 고르면 뒤엣것이 버려지므로 고를 때 막는다. */
const MAX_WORKS = MAX_PICKED_WORKS
/** 묶음 칸 — 앱 원래 화면의 insetGrouped 카드(흰 바탕·모서리 12·그림자 없음). */
const GROUP_RADIUS = 12
const HAIRLINE = '0.5px solid var(--separator)'

const todayIso = (): string => toIso(new Date())

/** 묶음 하나 — 위 작은 제목, 흰 칸, 아래 도움말. 칸 안의 줄은 `Row` 로 나누고 줄 사이에 얇은 선이 들어간다. */
function Group({ label, footnote, children }: { label: string; footnote?: string; children: React.ReactNode }) {
  return (
    <section aria-label={label}>
      <Txt as="h2" size={13} weight="semibold" color="secondary" style={{ margin: '0 4px 7px' }}>{label}</Txt>
      <div style={{ borderRadius: GROUP_RADIUS, background: 'var(--system-background)', overflow: 'hidden' }}>{children}</div>
      {footnote && <Txt size={12} color="secondary" style={{ margin: '7px 4px 0' }}>{footnote}</Txt>}
    </section>
  )
}

/** 묶음 안의 한 줄 — 첫 줄이 아니면 위에 얇은 선. */
function Row({ first, children, pad = '12px 14px' }: { first?: boolean; children: React.ReactNode; pad?: string }) {
  return <div style={{ padding: pad, borderTop: first ? undefined : HAIRLINE }}>{children}</div>
}

/** 기간 — 3열 칩. 고르면 주 색. */
function SpanChips({ nights, onPick }: { nights: number; onPick: (n: number) => void }) {
  return (
    <div role="radiogroup" aria-label={say('여행 기간', 'Trip length')} style={{ display: 'grid', gridTemplateColumns: 'repeat(3, minmax(0, 1fr))', gap: 8 }}>
      {SPAN_NIGHTS.map((n) => {
        const on = n === nights
        return (
          <Pressable
            key={n}
            plain
            className="proto-press"
            role="radio"
            aria-checked={on}
            onClick={() => onPick(n)}
            style={{ minHeight: 44, borderRadius: radius.s, background: on ? color.primaryFill : 'var(--system-gray6)' }}
          >
            <Txt size={15} weight={on ? 'bold' : 'semibold'} align="center" style={{ color: on ? color.onPrimary : 'var(--label)' }}>{daysLabel(n + 1)}</Txt>
          </Pressable>
        )
      })}
    </div>
  )
}

/** 떠나는 날 — 묶음 안 한 줄(「정하지 않음 ⌄」). 누르면 같은 칸 안에 달력이 펼쳐진다. 안 정해도 된다. */
function DateRows({ date, nights, onPick }: { date: string | null; nights: number; onPick: (iso: string | null) => void }) {
  const [open, setOpen] = useState(false)
  return (
    <>
      <Pressable plain aria-expanded={open} onClick={() => setOpen(!open)} style={{ width: '100%', minHeight: 48, padding: '0 14px', textAlign: 'left' }}>
        <HStack spacing={10} style={{ width: '100%' }}>
          <Txt size={15}>{say('떠나는 날', 'Start date')}</Txt>
          <Spacer />
          <Txt size={15} color={date ? 'primary' : 'secondary'}>{date ? `${formatDay(date)} – ${formatDay(addDays(date, nights))}` : say('정하지 않음', 'Not set')}</Txt>
          <Icon name={open ? 'chevron.up' : 'chevron.down'} size={12} weight="semibold" color="tertiary" />
        </HStack>
      </Pressable>
      {open && (
        <div style={{ padding: '0 6px', borderTop: HAIRLINE }}>
          <Calendar value={date ?? todayIso()} min={todayIso()} onPick={(iso) => { onPick(iso); setOpen(false) }} />
        </div>
      )}
      {date && (
        <Pressable plain onClick={() => onPick(null)} style={{ width: '100%', minHeight: 44, padding: '0 14px', textAlign: 'left', borderTop: HAIRLINE }}>
          <Txt size={15} color="red">{say('날짜 지우기', 'Clear date')}</Txt>
        </Pressable>
      )}
    </>
  )
}

/** 기간·떠나는 날 묶음 — 두 흐름이 같이 쓴다. */
function TripGroup({ nights, date, onNights, onDate, footnote }: { nights: number; date: string | null; onNights: (n: number) => void; onDate: (d: string | null) => void; footnote?: string }) {
  return (
    <Group label={say('일정', 'Dates')} footnote={footnote}>
      <Row first><SpanChips nights={nights} onPick={onNights} /></Row>
      <div style={{ borderTop: HAIRLINE }}><DateRows date={date} nights={nights} onPick={onDate} /></div>
    </Group>
  )
}

/** 다니는 속도 — 묶음 안 두 줄, 고른 줄 오른쪽에 체크(앱 설정 화면처럼). */
function PaceRows({ pace, onPick }: { pace: RoutePace; onPick: (p: RoutePace) => void }) {
  return (
    <div role="radiogroup" aria-label={say('다니는 속도', 'Pace')}>
      {(['loose', 'tight'] as const).map((p, i) => {
        const on = p === pace
        return (
          <Pressable key={p} plain role="radio" aria-checked={on} onClick={() => onPick(p)} style={{ width: '100%', padding: '11px 14px', textAlign: 'left', borderTop: i > 0 ? HAIRLINE : undefined }}>
            <HStack spacing={12} style={{ width: '100%' }}>
              <Icon name={PACE_SYMBOL[p]} size={17} color={color.primaryText} />
              <VStack spacing={1} align="start" style={{ flex: 1, minWidth: 0 }}>
                <Txt size={15} weight={on ? 'semibold' : 'regular'}>{tr(PACE_TITLE[p])}</Txt>
                <Txt size={12} color="secondary">{tr(PACE_CAPTION[p])}</Txt>
              </VStack>
              {on && <Icon name="checkmark" size={16} weight="bold" color={color.primaryText} />}
            </HStack>
          </Pressable>
        )
      })}
    </div>
  )
}

/** 고른 것 요약 칩 — 짜는 중 화면 위. */
function SummaryChips({ items }: { items: readonly string[] }) {
  return (
    <HStack spacing={6} wrap style={{ justifyContent: 'center' }}>
      {items.map((t) => (
        <span key={t} style={{ padding: '5px 11px', borderRadius: radius.pill, fontSize: 13, fontWeight: 700, color: color.primaryText, background: 'color-mix(in srgb, var(--home-purple) 10%, transparent)' }}>{t}</span>
      ))}
    </HStack>
  )
}

/** 지금 하는 일 세 줄 — 시간이 지나면 앞 줄부터 체크. 서버가 단계를 알려 주지 않으므로 시각으로 넘기는 눈속임이다(마지막 줄은 끝날 때까지 돈다). */
const STAGES: ReadonlyArray<{ ko: string; en: string; at: number }> = [
  { ko: '고른 작품의 촬영지를 모으는 중', en: 'Gathering filming spots', at: 0 },
  { ko: '가까운 곳끼리 묶는 중', en: 'Grouping nearby spots', at: 3500 },
  { ko: '하루씩 동선을 잇는 중', en: 'Linking each day', at: 8000 },
]

function PlanningScreen({ posters, summary, onCancel }: { posters: readonly string[]; summary: readonly string[]; onCancel: () => void }) {
  const [elapsed, setElapsed] = useState(0)
  useEffect(() => {
    const start = Date.now()
    const id = window.setInterval(() => setElapsed(Date.now() - start), 500)
    return () => window.clearInterval(id)
  }, [])
  const current = STAGES.reduce((at, s, i) => (elapsed >= s.at ? i : at), 0)
  return (
    <ProtoScreen title="" label={say('일정을 짜는 중', 'Planning')} footer={
      <Pressable plain className="proto-press" onClick={onCancel} style={{ width: '100%', minHeight: 50, borderRadius: radius.m, background: 'var(--system-background)', border: '1px solid var(--separator)' }}>
        <Txt size={16} weight="semibold" align="center">{say('그만두기', 'Cancel')}</Txt>
      </Pressable>
    }>
      <VStack spacing={22} align="center" style={{ paddingTop: 24 }}>
        {/* 고른 작품 포스터를 부채꼴로 — 무엇으로 짜고 있는지 보인다 */}
        <div aria-hidden style={{ position: 'relative', width: 200, height: 150 }}>
          {posters.slice(0, 3).map((url, i, all) => {
            const mid = (all.length - 1) / 2
            return (
              <div
                key={url}
                className="proto-float"
                style={{
                  position: 'absolute', left: 64 + (i - mid) * 46, top: 8 + Math.abs(i - mid) * 8, width: 72, height: 108, borderRadius: radius.s, overflow: 'hidden',
                  transform: `rotate(${(i - mid) * 9}deg)`, boxShadow: shadow.float, background: color.brandFill, animationDelay: `${i * 0.25}s`,
                }}
              >
                <img src={url} alt="" referrerPolicy="no-referrer" style={{ width: '100%', height: '100%', objectFit: 'cover' }} />
              </div>
            )
          })}
          {posters.length === 0 && <div style={{ position: 'absolute', inset: '20px 60px', borderRadius: radius.m, background: color.brandFill }} />}
        </div>
        <VStack spacing={8} align="center">
          <Txt as="h1" size={24} weight="heavy" align="center" style={{ margin: 0 }}>{say('코스를 짜고 있어요', 'Planning your trip')}</Txt>
          <SummaryChips items={summary} />
        </VStack>
        <VStack spacing={12} align="stretch" style={{ width: '100%', maxWidth: 320 }} aria-live="polite">
          {STAGES.map((s, i) => {
            const done = i < current
            const now = i === current
            return (
              <HStack key={s.ko} spacing={10} style={{ opacity: i > current ? 0.4 : 1 }}>
                <span style={{ width: 22, height: 22, flex: 'none', display: 'grid', placeItems: 'center' }}>
                  {done ? <Icon name="checkmark.circle.fill" size={20} color={color.primaryText} /> : now ? <Spinner size={16} /> : <Icon name="circle" size={18} color="var(--system-gray3)" />}
                </span>
                <Txt size={15} weight={now ? 'bold' : 'regular'}>{say(s.ko, s.en)}</Txt>
              </HStack>
            )
          })}
        </VStack>
        <Txt size={12} color="secondary" align="center">{say('조금 걸릴 수 있어요. 다 되면 바로 고칠 수 있게 열어 드려요', 'This can take a moment. You can edit it right after.')}</Txt>
      </VStack>
    </ProtoScreen>
  )
}

/** 지금 어디에 있나 — 원래 마법사와 같이 시작할 때 한 번 물어 둔다. 못 받아도 코스는 만들어진다. */
function useHereOnce() {
  const [here, setHere] = useState<{ lat: number; lng: number } | null>(null)
  useEffect(() => {
    if (!('geolocation' in navigator)) return
    let live = true
    navigator.geolocation.getCurrentPosition((p) => live && setHere({ lat: p.coords.latitude, lng: p.coords.longitude }), () => undefined, { timeout: 8000, maximumAge: 60_000 })
    return () => {
      live = false
    }
  }, [])
  return here
}

// ---------------------------------------------------------------- ① AI 로 짜기

export function ProtoAIPlan({ onClose }: { onClose: () => void }) {
  const [step, setStep] = useState<'trip' | 'works'>('trip')
  const [nights, setNights] = useState(1)
  const [date, setDate] = useState<string | null>(null)
  const [pace, setPace] = useState<RoutePace>('loose')
  /** 고른 작품 — 작품 자체를 든다(검색 결과·받은 쪽에 없어도 남는다). */
  const [picked, setPicked] = useState<ContentSummary[]>([])
  const [search, setSearch] = useState('')
  const [planning, setPlanning] = useState(false)
  const [draft, setDraft] = useState<RouteCourse | null>(null)
  const [failure, setFailure] = useState<PlanFailure | null>(null)
  const [tooMany, setTooMany] = useState(false)
  const browse = useWorksBrowse(search)
  const likes = useLikes()
  const popular = browse.popular
  const ai = useAIConsent()
  const location = useLocationConsent()
  const here = useHereOnce()
  /** 짜는 중에 「그만두기」 를 누르면 올라가는 번호 — 늦게 온 응답이 이미 닫은 화면을 열지 않게. */
  const run = useRef(0)
  const preset = useRef(false)

  // 촬영지 재료(초안의 촬영지 이름·주소)는 코스 스토어에서. 작품 목록은 이 단계가 따로 받는다(useWorksBrowse).
  useEffect(() => {
    void routeActions.refreshMaterials()
  }, [])
  // 찜한 작품을 미리 골라 둔다 — 작품 첫 쪽이 온 뒤 한 번만(사람이 고친 것을 덮지 않게). 찜 목록의 작품이 먼저, 그다음 받은 것 안의 찜.
  useEffect(() => {
    if (preset.current || popular.length === 0) return
    preset.current = true
    setPicked(appendPage(likes.likedWorks, popular).filter((w) => likes.contentIds.has(w.id)).slice(0, MAX_WORKS))
  }, [popular, likes.likedWorks, likes.contentIds])

  const plan = () => {
    const mine = ++run.current
    setPlanning(true)
    void guideDraft({
      nights, startDate: date, workIds: new Set(picked.map((w) => w.id)), pace, near: here, locationAllowed: location.allowed,
      sortedWorks: picked.length > 0 ? picked : popular, titles: requestTitles(picked, popular), places: routeStore.get().places,
    }).then((res) => {
      if (mine !== run.current) return
      setPlanning(false)
      if (res.ok) setDraft(res.course)
      else setFailure(res.failure)
    })
  }
  const start = () => ai.request(() => (here !== null ? location.request(plan) : plan()))

  if (draft) return <RouteEditor course={draft} isNew onClose={onClose} />

  const chosen = picked
  const pickedIds = new Set(picked.map((w) => w.id))
  const shown = browse.shown
  const emptyQuery = browse.searching && !browse.typing && !shown.loading && !shown.failed && shown.total === 0 ? browse.query : null
  const posters = (chosen.length > 0 ? chosen : popular.slice(0, 3)).map((w) => w.posterUrl).filter((u): u is string => !!u)
  const summary = [daysLabel(nights + 1), date ? say(`${formatDay(date)} 출발`, `From ${formatDay(date)}`) : null, tr(PACE_TITLE[pace]), chosen.length > 0 ? say(`작품 ${chosen.length}`, `${chosen.length} shows`) : say('인기 작품', 'Popular shows')]
    .filter((t): t is string => !!t)

  return (
    <div style={{ position: 'relative', height: '100%' }}>
      {/* 단계가 바뀔 때 밀려 들어오고 나간다(계획 motion-works-drawer-parity.md §5) — 여행 정보 → 작품 → 짜는 중. 「그만두기」 는 뒤로. */}
      <StepTransition step={planning ? 'planning' : step} order={planning ? 2 : step === 'works' ? 1 : 0}>
      {planning ? (
        <PlanningScreen posters={posters} summary={summary} onCancel={() => { run.current++; setPlanning(false) }} />
      ) : step === 'trip' ? (
        <ProtoScreen
          title={say('어떤 여행이에요?', 'What kind of trip?')}
          step={[1, 2]}
          onBack={onClose}
          footer={<PrimaryButton onClick={() => setStep('works')}>{say('다음', 'Next')}</PrimaryButton>}
        >
          <TripGroup nights={nights} date={date} onNights={setNights} onDate={setDate} />
          <Group label={say('다니는 속도', 'Pace')} footnote={say('방문 수는 고른 작품과 이동 거리에 따라 달라져요', 'Stops vary with the shows and distances.')}>
            <PaceRows pace={pace} onPick={setPace} />
          </Group>
        </ProtoScreen>
      ) : (
        <ProtoScreen
          title={say('누구의 장면을 따라갈까요?', 'Whose scenes will you follow?')}
          step={[2, 2]}
          onBack={() => setStep('trip')}
          footer={
            <VStack spacing={8} align="stretch">
              <Txt size={12} color="secondary" align="center">{chosen.length > 0 ? say(`${chosen.length}개 골랐어요 · 최대 ${MAX_WORKS}개`, `${chosen.length} picked · up to ${MAX_WORKS}`) : say('안 고르면 인기 작품으로 짜요', 'None picked — we’ll use popular shows')}</Txt>
              <PrimaryButton onClick={start}>
                <HStack spacing={6} style={{ justifyContent: 'center' }}>
                  <Icon name="sparkles" size={15} color="white" />
                  <span>{say('AI로 코스 짜기', 'Plan with AI')}</span>
                </HStack>
              </PrimaryButton>
            </VStack>
          }
        >
          <ProtoWorkStep
            works={favoritesFirst(shown.items, likes.contentIds)}
            liked={likes.contentIds}
            selected={pickedIds}
            onToggleSelect={(w) => {
              const r = togglePick(picked, w, MAX_WORKS)
              if (r.tooMany) setTooMany(true)
              else setPicked(r.picked)
            }}
            onToggleLike={(id) => void likeActions.toggle(id)}
            search={search}
            onSearch={setSearch}
            emptyQuery={emptyQuery}
            loading={shown.loading}
            onNearEnd={browse.loadMore}
          />
        </ProtoScreen>
      )}
      </StepTransition>

      <GlassDialog
        open={tooMany}
        variant="alert"
        title={say(`작품은 ${MAX_WORKS}개까지 고를 수 있어요`, `Up to ${MAX_WORKS} shows`)}
        message={say('하나를 빼고 다시 골라 주세요', 'Remove one to pick another.')}
        buttons={[{ label: say('확인', 'OK'), role: 'cancel' }]}
        onClose={() => setTooMany(false)}
      />
      <GlassDialog
        open={failure !== null}
        variant="alert"
        title={say('일정을 짜지 못했어요', 'Couldn’t plan the trip')}
        message={failure ? planFailureMessage(failure) : undefined}
        buttons={[{ label: say('확인', 'OK'), role: 'cancel' }]}
        onClose={() => setFailure(null)}
      />
      <AIConsentSheet gate={ai.gate} />
      <LocationConsentSheet gate={location.gate} onCancel={plan} />
    </div>
  )
}

// ---------------------------------------------------------------- ② 담은 곳으로

/** 시·도 이름을 짧게 — 「서울특별시」→「서울」, 「경상북도」→「경북」. 주소가 없으면 「기타」. 같은 시·도끼리 먼저 잇는 데 쓴다. */
const PROVINCE: Record<string, string> = {
  경상북도: '경북', 경상남도: '경남', 전라북도: '전북', 전북특별자치도: '전북', 전라남도: '전남', 충청북도: '충북', 충청남도: '충남',
  강원도: '강원', 강원특별자치도: '강원', 제주특별자치도: '제주', 경기도: '경기', 세종특별자치시: '세종',
}
export function regionOf(address: string | null | undefined): string {
  const head = address?.trim().split(/\s+/)[0]
  if (!head) return '기타'
  return PROVINCE[head] ?? head.replace(/(특별시|광역시)$/, '')
}

const placeOf = (i: CartItem): PlaceSummary => ({
  id: i.placeId, name: i.name, address: i.address ?? null, latitude: i.latitude ?? 0, longitude: i.longitude ?? 0, imageUrl: i.imageUrl ?? null,
  imageThumbnailUrl: i.imageThumbnailUrl ?? null,
})

/** 지도 위 거리의 눈대중(위도·경도 차의 제곱합). 같은 시·도 안에서 순서를 정하는 데만 쓴다. */
const near = (a: PlaceSummary, b: PlaceSummary) => (a.latitude - b.latitude) ** 2 + ((a.longitude - b.longitude) * 0.8) ** 2

/** 가까운 곳 잇기 — 시·도 묶음을 큰 것부터, 묶음 안에서는 앞 장소에서 가장 가까운 곳을 차례로. 좌표가 없는 곳은 맨 뒤. */
export function arrange(places: readonly PlaceSummary[]): PlaceSummary[] {
  const groups = new Map<string, PlaceSummary[]>()
  for (const p of places) groups.set(regionOf(p.address), [...(groups.get(regionOf(p.address)) ?? []), p])
  const out: PlaceSummary[] = []
  for (const group of [...groups.values()].sort((a, b) => b.length - a.length)) {
    const located = group.filter((p) => p.latitude !== 0 || p.longitude !== 0)
    const rest = group.filter((p) => !located.includes(p))
    let at = located.shift()
    while (at) {
      out.push(at)
      if (located.length === 0) break
      const from: PlaceSummary = at
      located.sort((a, b) => near(from, a) - near(from, b))
      at = located.shift()
    }
    out.push(...rest)
  }
  return out
}

/** 차례대로 날마다 고르게 나눈다 — 남는 것은 앞날부터 하나씩. 날이 장소보다 많으면 뒷날은 비어 있다. */
export function splitEven<T>(items: readonly T[], days: number): T[][] {
  const base = Math.floor(items.length / days)
  const extra = items.length % days
  const out: T[][] = []
  let at = 0
  for (let d = 0; d < days; d++) {
    const n = base + (d < extra ? 1 : 0)
    out.push(items.slice(at, at + n))
    at += n
  }
  return out
}

/**
 * 담은 곳으로 — 기간·날짜와 넣을 곳을 묻고, 고른 곳을 날마다 나눠 넣은 편집 화면을 연다.
 * 넣을 곳은 담아 둔 곳 중에서 고른다 — 처음에는 아무것도 골라져 있지 않다(MZ2AZ-551: 전에는 담아 둔 곳이 전부 들어갔다).
 */
export function ProtoSavedPlan({ onClose }: { onClose: () => void }) {
  const cart = useCart()
  const items = cart.items.filter((i) => !i.delisted)
  const [nights, setNights] = useState(1)
  const [date, setDate] = useState<string | null>(null)
  const [course, setCourse] = useState<RouteCourse | null>(null)
  const [picked, setPicked] = useState<ReadonlySet<number>>(() => new Set())

  if (course) return <RouteEditor course={course} isNew onClose={onClose} />

  const chosen = placesForNewCourse(items, picked)
  const create = () => {
    const days = splitEven(arrange(chosen.map(placeOf)), spanDays(nights))
    setCourse(makeCourse({ title: trAt('내 코스', '코스 제목'), startDate: date, days: days.map((ps) => makeDay(ps.map((p) => makeStop(p)))) }))
  }
  const toggle = (id: number) => {
    const next = new Set(picked)
    if (!next.delete(id)) next.add(id)
    setPicked(next)
  }

  return (
    <div style={{ position: 'relative', height: '100%' }}>
      <ProtoScreen
        title={say('며칠 다녀오나요?', 'How long is the trip?')}
        onBack={onClose}
        footer={<PrimaryButton onClick={create}>{chosen.length > 0 ? say(`${chosen.length}곳으로 코스 만들기`, `Create trip with ${chosen.length}`) : say('코스 만들기', 'Create trip')}</PrimaryButton>}
      >
        <TripGroup nights={nights} date={date} onNights={setNights} onDate={setDate} />
        <Group
          label={say('넣을 곳', 'Places')}
          footnote={items.length > 0
            ? say('넣을 곳을 골라 주세요. 가까운 곳끼리 날마다 나눠 넣고, 빼거나 순서를 바꾸는 건 다음 화면에서 할 수 있어요', 'Pick the places to include. We split them by day, nearest first — reorder on the next screen.')
            : say('담아 둔 곳이 없어 빈 코스로 시작해요. 다음 화면에서 하나씩 넣으면 돼요', 'Nothing saved, so this starts empty. Add places on the next screen.')}
        >
          {items.length === 0 ? (
            <Row first pad="10px 14px">
              <HStack spacing={12} style={{ width: '100%' }}>
                <Icon name="bag" size={20} color="secondary" />
                <Txt size={15}>{say('담아 둔 곳', 'Saved places')}</Txt>
                <Spacer />
                <Txt size={15} color="secondary">{say('0곳', '0')}</Txt>
              </HStack>
            </Row>
          ) : (
            items.map((i, k) => {
              const on = picked.has(i.placeId)
              return (
                <Pressable
                  key={i.placeId}
                  plain
                  role="checkbox"
                  aria-checked={on}
                  onClick={() => toggle(i.placeId)}
                  style={{ width: '100%', textAlign: 'left', padding: '10px 14px', borderTop: k > 0 ? HAIRLINE : undefined }}
                >
                  <HStack spacing={12} style={{ width: '100%' }}>
                    <div aria-hidden style={{ width: 36, height: 36, flex: 'none', borderRadius: radius.s, overflow: 'hidden', background: color.brandFill, display: 'grid', placeItems: 'center' }}>
                      {i.imageUrl
                        ? <img src={smallImage(i.imageThumbnailUrl, i.imageUrl)!} alt="" referrerPolicy="no-referrer" style={{ width: '100%', height: '100%', objectFit: 'cover' }} />
                        : <Icon name="mappin.and.ellipse" size={16} color="secondary" />}
                    </div>
                    <Txt size={15} lines={1} style={{ flex: 1, minWidth: 0 }}>{i.name}</Txt>
                    <span aria-hidden style={{ width: 22, height: 22, flex: 'none', borderRadius: 6, display: 'grid', placeItems: 'center', background: on ? color.primaryFill : undefined, border: on ? undefined : '1.5px solid var(--system-gray3)' }}>
                      {on && <Icon name="checkmark" size={12} weight="bold" color="white" />}
                    </span>
                  </HStack>
                </Pressable>
              )
            })
          )}
        </Group>
      </ProtoScreen>
    </div>
  )
}
