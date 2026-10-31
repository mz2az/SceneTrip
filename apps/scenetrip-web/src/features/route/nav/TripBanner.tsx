/*
 * 편집 화면의 **여행 안내 띠** — iOS RouteTab/RouteEditorTrip.swift (`tripBanner` · `arrivalNotice` · `tripDetail` · `TripNavigationSummary`) 와
 * RouteEditorTripLimit.swift (`tripLimitNotice`).
 *
 * 일정 시트 맨 위 — 가는 중에만. 어디로 가는 중인지, 얼마나 걸리는지, 어떻게 가는지. 별도 창의 머리줄·요약·구간 목록을 한 띠로 줄였다. 지도가 주인공이다.
 * 도착하면 이 띠는 내려가고 `ArrivalNotice` 가 지도 위에 뜬다(2026-09-03 사용자 결정 — 경로는 아래에, 알림만 위에. 지도를 카드로 다 가리면 안 된다).
 */
import { useLayoutEffect, useRef } from 'react'
import { HStack, Pressable, Spinner, Txt, VStack, rr, shadow } from '@/design/primitives'
import { Icon } from '@/design/Icon'
import { tr } from '@/i18n'
import '../poi/icons'
import { GuideChipBody } from '../guide/GuideChip'
import { type NavFailure, navCanRetry, navFailureMessage } from './navFailure'
import { type DirectionsOffer, type MapApp, directionsOffers, offerOpensApp } from './externalDirections'
import { type Leg, formatDuration, guidanceNote, legIsVehicle, legSymbol, navChips, navSummaryLine, type NavResult } from './navModels'
import { navLimitMessage, useNavLimitState } from './navLimit'
import type { TripSession, TripState } from './tripSession'
import { currentLang } from '@/i18n'
import './tripUi.css'

const PIN_LIGHT = '143, 204, 247'
/** 앱 이름 — 네이버 길찾기 스킴의 `appname`. 웹은 번들 id 가 없어 도메인을 쓴다. */
const APP_NAME = 'io.scenetrip.web'

export interface TripBannerProps {
  session: TripSession
  state: TripState
  /** 이 일차에서 아직 안 간 첫 곳 — 도착 뒤 「다음 · N번으로」. */
  next: { number: number; name: string } | null
  /** 가이드 입구가 안내 띠에 서는가(`guideEntryPlacement` === 'tripHeader'). */
  guideInHeader?: boolean
  onOpenGuide?: () => void
  /** 방문 기록 저장 상태 — 도착 직후 서버에 남기는 중이거나 못 남겼을 때의 한 줄을 호스트가 그린다. */
  visitNotice?: React.ReactNode
}

function tripTitle(state: TripState, number: number): string {
  const target = state.target
  if (!target) return ''
  const prefix = number > 0 ? tr('%d번 ', number) : ''
  return state.phase === 'arrived' ? tr('%@%@ 도착', prefix, target.place.name) : tr('%@%@로 가는 중', prefix, target.place.name)
}

function Headline({ props, symbol }: { props: TripBannerProps; symbol: string }) {
  const { session, state } = props
  return (
    <HStack spacing={8}>
      <Icon name={symbol} size={13} weight="bold" color="deep" />
      <Txt size={15} weight="bold" style={{ flex: 1, minWidth: 0 }}>{tripTitle(state, session.targetNumber)}</Txt>
      {props.guideInHeader && (
        <Pressable plain onClick={props.onOpenGuide} aria-label={tr('내가 도와줄게!')} data-testid="trip-guide-entry" style={{ cursor: 'pointer' }}>
          <GuideChipBody bubble={false} />
        </Pressable>
      )}
      <Pressable plain onClick={() => session.end()} aria-label={tr('안내 끝')} style={{ width: 28, height: 28, display: 'grid', placeItems: 'center', cursor: 'pointer' }}>
        <Icon name="xmark" size={11} weight="semibold" color="secondary" />
      </Pressable>
    </HStack>
  )
}

/** 일정 시트 맨 위 — 가는 중에만. */
export function TripBanner(props: TripBannerProps) {
  const { state } = props
  if (!state.target || state.phase !== 'guiding') return null
  return (
    <VStack spacing={6} align="stretch" style={{ padding: '10px 16px 6px', background: `rgba(${PIN_LIGHT}, 0.10)` }}>
      <Headline props={props} symbol="location.fill" />
      <TripDetail {...props} />
    </VStack>
  )
}

/** 지도 맨 위의 도착 알림 카드 — 도착한 뒤 「다음은 사람이 고른다」를 말한다. 시트 안에 두니 성지 목록이 좁아져 넘기기 힘들었다(2026-09-03 사용자 요청). */
export function ArrivalNotice(props: TripBannerProps & { onHeight?: (px: number) => void }) {
  const { state, onHeight } = props
  const el = useRef<HTMLDivElement>(null)
  useLayoutEffect(() => {
    if (el.current) onHeight?.(el.current.offsetHeight)
  })
  if (!state.target || state.phase !== 'arrived') return null
  return (
    <div style={{ padding: '10px 12px 0' }}>
      <div ref={el} className="tp-arrival">
        <VStack spacing={6} align="stretch" style={{ padding: '10px 14px', background: 'var(--system-background)', boxShadow: shadow('rgba(0,0,0,0.14)', 8, 3), ...rr(14) }}>
          <Headline props={props} symbol="checkmark.seal.fill" />
          <TripDetail {...props} />
        </VStack>
      </div>
    </div>
  )
}

function TripDetail({ session, state, next, visitNotice }: TripBannerProps) {
  if (state.phase === 'arrived') {
    // 다음으로 넘기지 않는다. 둘러볼 시간은 사람의 것이다.
    return visitNotice ?? <Txt size={12} color="secondary">{next ? tr('둘러본 뒤 아래 「다음 · %d번으로」를 누르세요', next.number) : tr('오늘 일정을 모두 돌았어요')}</Txt>
  }
  if (state.result) return <GuidingDetail session={session} result={state.result} />
  if (state.failure && state.failure.kind === 'limitReached') return <TripLimitNotice session={session} />
  if (state.failure) return <FailureLine failure={state.failure} onRetry={() => session.retry()} />
  if (state.asking) {
    return (
      <HStack spacing={6}>
        <Spinner size={14} />
        <Txt size={12} color="secondary">{tr('길을 찾는 중입니다')}</Txt>
      </HStack>
    )
  }
  if (state.here === null) {
    return (
      <HStack spacing={6}>
        <Icon name="location.slash" size={12} color="secondary" />
        <Txt size={12} color="secondary">{tr('현재 위치를 찾는 중입니다')}</Txt>
      </HStack>
    )
  }
  return null
}

/** 안내 중 — 걸리는 시간·요약·구간 칩·머무름 힌트. */
function GuidingDetail({ session, result }: { session: TripSession; result: NavResult }) {
  const note = guidanceNote(currentLang(), result.guidanceLang)
  return (
    <>
      <NavigationSummary duration={formatDuration(result.totalMinutes)} summary={navSummaryLine(result)} />
      {/* 안내 언어가 앱 언어와 다르면 그대로 보여 주고 언어만 표시한다(MZ2AZ-305). 번역은 별도. */}
      {note && (
        <HStack spacing={6} align="start">
          <Icon name="character.bubble" size={11} color="secondary" style={{ marginTop: 1 }} />
          <Txt size={11} color="secondary">{note}</Txt>
        </HStack>
      )}
      <LegChips legs={navChips(result)} />
      <Txt size={11} color="tertiary">{session.dwellHint()}</Txt>
    </>
  )
}

/** 구간 — 도보·대중교통 조각을 한 줄로. 화면을 밀면 다 보인다. */
function LegChips({ legs }: { legs: readonly Leg[] }) {
  return (
    <div className="scroll-x" style={{ overflowX: 'auto' }}>
      <HStack spacing={6} style={{ width: 'max-content' }}>
        {legs.map((leg) => (
          <HStack key={leg.id} spacing={4} pad="4px 8px" style={{ borderRadius: 999, background: leg.mode === 'walk' ? 'var(--system-gray5)' : leg.mode === 'subway' ? 'color-mix(in srgb, var(--system-blue) 16%, transparent)' : 'color-mix(in srgb, var(--system-green) 18%, transparent)' }}>
            <Icon name={legSymbol(leg.mode)} size={10} weight="bold" />
            {/* 탈것은 노선까지 — 「간선 150 · 서울신문사 → 혜화역2번출구 · 10정거장 · 19분」. */}
            <Txt size={11} lines={1} style={{ whiteSpace: 'nowrap' }}>
              {legIsVehicle(leg.mode) ? [leg.title, leg.detail].filter(Boolean).join(' · ') : leg.detail === '' ? leg.title : leg.detail}
            </Txt>
            {leg.hasStairs && <Icon name="stairs" size={9} color="var(--system-red)" />}
          </HStack>
        ))}
      </HStack>
    </div>
  )
}

/** 걸리는 시간과 요약 — 한 줄에 다 들어가면 한 줄, 아니면 요약이 아래로 내려간다(MZ2AZ-117: 긴 번역·큰 글자는 요약을 다음 줄로 내려 전체 정보를 보존한다). */
export function NavigationSummary({ duration, summary }: { duration: string; summary: string }) {
  return (
    <div style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'baseline', columnGap: 8, rowGap: 4 }}>
      <Txt size={20} weight="bold">{duration}</Txt>
      <Txt size={12} color="secondary">{summary}</Txt>
    </div>
  )
}

/** 계약 응답별 문구(`NavFailure`). 다시 불러 달라질 수 있는 것에만 단추. */
function FailureLine({ failure, onRetry }: { failure: NavFailure; onRetry: () => void }) {
  return (
    <HStack spacing={8} align="baseline">
      <HStack spacing={6} align="start" style={{ minWidth: 0 }}>
        <Icon name="exclamationmark.triangle" size={12} color="secondary" style={{ marginTop: 1 }} />
        <Txt size={12} color="secondary">{navFailureMessage(failure)}</Txt>
      </HStack>
      {navCanRetry(failure) && <MiniBordered onClick={onRetry} id="nav-retry">{tr('다시 시도')}</MiniBordered>}
    </HStack>
  )
}

/** `.buttonStyle(.bordered).controlSize(.mini)` — 작은 캡슐. */
function MiniBordered({ children, onClick, id }: { children: React.ReactNode; onClick: () => void; id?: string }) {
  return (
    <Pressable plain onClick={onClick} data-testid={id} style={{ flex: 'none', padding: '3px 10px', borderRadius: 999, background: 'rgba(120,120,128,0.16)', cursor: 'pointer' }}>
      <Txt size={12} weight="semibold" color="accent" style={{ whiteSpace: 'nowrap' }}>{children}</Txt>
    </Pressable>
  )
}

// ---------------------------------------------------------------- 길찾기 한도 — 지도 앱으로 넘기기 (RouteEditorTripLimit)

/**
 * 앱 안 길찾기가 한도(429 `NAVIGATION_LIMIT_REACHED`)에 걸려도 여행은 멈추지 않는다. 안내는 켜진 채로(목적지·직선 계획선·머무름 도착 판정·「여기 도착함」 그대로)
 * 그 구간만 카카오맵·네이버 지도가 대신 안내한다. 규칙은 `navLimit.ts`, 상태는 `NavLimitStore`, 주소는 `externalDirections.ts`.
 */
export function TripLimitNotice({ session, installed = new Set<MapApp>() }: { session: TripSession; installed?: ReadonlySet<MapApp> }) {
  const state = useNavLimitState(session.limits)
  if (state.kind !== 'reached') {
    // 풀렸다(또는 계정이 바뀌어 잊었다). 저절로 다시 부르지 않는다 — 유료 호출은 사람이 누른다.
    return (
      <HStack spacing={8} align="baseline" data-testid="nav-limit-lifted">
        <HStack spacing={6} align="start">
          <Icon name="checkmark.circle" size={12} color="secondary" style={{ marginTop: 1 }} />
          <Txt size={12} color="secondary">{tr('이제 앱 안에서 다시 길을 찾을 수 있어요')}</Txt>
        </HStack>
        <MiniBordered onClick={() => session.retry()} id="nav-retry">{tr('다시 시도')}</MiniBordered>
      </HStack>
    )
  }
  const target = session.state.target
  const offers = target ? directionsOffers({ origin: null, destination: { name: target.place.name, latitude: target.place.latitude, longitude: target.place.longitude }, mode: 'transit', appName: APP_NAME, installed }) : []
  return (
    <VStack spacing={8} align="start">
      <HStack spacing={8} align="baseline">
        <HStack spacing={6} align="start" data-testid="nav-limit-notice">
          <Icon name="hourglass" size={12} color="var(--system-orange)" style={{ marginTop: 1 }} />
          <Txt size={12} style={{ color: 'var(--system-orange)' }}>{navLimitMessage(state.block.window)}</Txt>
        </HStack>
        {/* 풀리는 때를 모르면 잠그지 않는다 — 바로 다시 물어볼 수 있다. 서버가 다시 정한다. */}
        {state.block.until === null && <MiniBordered onClick={() => session.retry()} id="nav-retry">{tr('다시 시도')}</MiniBordered>}
      </HStack>
      <HandoffButtons offers={offers} />
      <Txt size={11} color="secondary">{tr('도착하면 돌아와서 「여기 도착함」 을 눌러 주세요')}</Txt>
    </VStack>
  )
}

/** 넘기는 단추 — 카카오맵은 언제나(앱이 없으면 웹), 네이버 지도는 앱이 깔려 있을 때만. 좁으면(영어·큰 글자) 두 줄로 내려간다. */
function HandoffButtons({ offers }: { offers: readonly DirectionsOffer[] }) {
  return (
    <div style={{ display: 'flex', flexWrap: 'wrap', gap: 0, columnGap: 8 }}>
      {offers.map((offer) => (
        <Pressable
          key={offer.app}
          plain
          onClick={() => openOffer(offer)}
          data-testid={`nav-handoff-${offer.app}`}
          aria-label={offer.app === 'kakao' ? tr('카카오맵에서 길찾기') : tr('네이버 지도에서 길찾기')}
          title={offerOpensApp(offer) ? tr('지도 앱이 열려요') : tr('브라우저에서 지도가 열려요')}
          // 보이는 알약은 낮아도 누를 자리는 44pt 다(걸으면서 누르는 단추다).
          style={{ minHeight: 44, display: 'flex', alignItems: 'center', cursor: 'pointer' }}
        >
          <AccentLike>
            <Icon name="arrow.up.forward.app" size={12} weight="semibold" color="accent" />
            {offer.app === 'kakao' ? tr('카카오맵에서 길찾기') : tr('네이버 지도에서 길찾기')}
          </AccentLike>
        </Pressable>
      ))}
    </div>
  )
}

const AccentLike = ({ children }: { children: React.ReactNode }) => (
  <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6, padding: '7px 12px', borderRadius: 16, background: 'color-mix(in srgb, var(--accent) 14%, transparent)', fontSize: 12, fontWeight: 600, color: 'var(--accent)' }}>{children}</span>
)

/** 연다. 앱 스킴이 끝내 안 열리면(방금 지웠다 등) 웹으로 물러선다. 안내는 끄지 않는다 — 돌아오면 그 자리에서 이어진다. */
export function openOffer(offer: DirectionsOffer): void {
  if (offerOpensApp(offer)) {
    window.location.href = offer.url
    if (offer.fallback) setTimeout(() => document.visibilityState === 'visible' && window.open(offer.fallback ?? '', '_blank', 'noopener'), 1200)
    return
  }
  window.open(offer.url, '_blank', 'noopener')
}

