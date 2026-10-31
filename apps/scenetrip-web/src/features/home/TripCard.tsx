/*
 * 내 여행 이어가기 — 홈 맨 위 고정 카드. iOS HomeTab/HomeCards.swift `HomeTripPager` · `HomeTripCard`. 경로여정 탭이 없어진 자리를 이 카드가 맡는다.
 * 네 모습: 코스가 있으면 제목·기간·스탬프 진행과 단추 둘, 아직 받는 중이면 자리만 지키고, 못 받았으면 그렇게 적고, 코스가 정말 없을 때만 「코스 만들기」다.
 * 코스가 둘 이상이면 옆으로 넘기는 페이저, 아래에 1·2·3 점. 여행 중인 코스가 첫 장이다. (TabView(.page) 대신 스크롤 정지 방식 — 세로 스크롤 안에서 아래 줄의 탭을 가로채지 않는다.)
 */
import { useEffect, useRef, useState } from 'react'
import { HStack, Txt, VStack, shadow } from '@/design/primitives'
import { Pressable } from '../route/ui/Press'
import { tr } from '@/i18n'
import { coursePlaceCount, courseSpanLabel } from '../route/models'
import { trAt } from '@/i18n'
import { useDiagonalAngle } from '@/design/useDiagonalAngle'
import type { HomeTrip } from './homeModel'
import type { TripCard as TripCardKind } from './homeRules'
import '../route/ui/route.css'

/** 페이저 카드 영역 높이(pt) — iOS `.frame(height: 196)`. */
const PAGER_HEIGHT = 196
const gradient = (deg: number) => `linear-gradient(${deg}deg, var(--pin-light), var(--pin-deep))`

interface CardProps {
  hasCourses: boolean
  onNavigate: (trip: HomeTrip) => void
  onOpenCourse: (trip: HomeTrip) => void
  onCreate: () => void
}

export function TripPager({ trips, card, ...rest }: CardProps & { trips: readonly HomeTrip[]; card: TripCardKind }) {
  const scroller = useRef<HTMLDivElement>(null)
  const [page, setPage] = useState(0)
  useEffect(() => {
    const el = scroller.current
    if (!el) return
    const onScroll = () => setPage(Math.round(el.scrollLeft / Math.max(el.clientWidth, 1)))
    el.addEventListener('scroll', onScroll, { passive: true })
    return () => el.removeEventListener('scroll', onScroll)
  }, [trips.length])

  if (trips.length > 1) {
    return (
      <VStack spacing={8} align="center">
        <div ref={scroller} className="pager-x" style={{ width: '100%', height: PAGER_HEIGHT }}>
          {trips.map((trip, i) => (
            <div key={trip.course.uid} style={{ height: '100%' }}>
              <TripCard trip={trip} rank={i + 1} card="filled" {...rest} />
            </div>
          ))}
        </div>
        <HStack spacing={6}>
          {trips.map((t, i) => (
            <span key={t.course.uid} style={{ width: 6, height: 6, borderRadius: '50%', background: i === page ? 'var(--home-purple)' : 'var(--system-gray4)' }} />
          ))}
        </HStack>
      </VStack>
    )
  }
  return <TripCard trip={trips[0] ?? null} rank={null} card={card} {...rest} />
}

function Capsule({ children, weight = 'bold' }: { children: React.ReactNode; weight?: 'bold' | 'semibold' }) {
  return (
    <span style={{ padding: '3px 9px', borderRadius: 999, background: 'rgba(255,255,255,0.22)', fontSize: 12, fontWeight: weight === 'bold' ? 700 : 600, whiteSpace: 'nowrap' }}>{children}</span>
  )
}

function WhiteButton({ children, onClick, disabled }: { children: string; onClick: () => void; disabled?: boolean }) {
  return (
    <Pressable
      plain
      onClick={onClick}
      disabled={disabled}
      style={{ flex: 1, padding: '10px 0', borderRadius: 14, background: '#fff', opacity: disabled ? 0.6 : 1, textAlign: 'center' }}
    >
      <Txt size={14} weight="bold" align="center" style={{ color: 'var(--home-purple)' }}>{children}</Txt>
    </Pressable>
  )
}

function TripCard({ trip, rank, card, hasCourses, onNavigate, onOpenCourse, onCreate }: CardProps & { trip: HomeTrip | null; rank: number | null; card: TripCardKind }) {
  const progress = trip && trip.total > 0 ? trip.visited / trip.total : 0
  const count = trip ? coursePlaceCount(trip.course) : 0
  const [gradRef, angle] = useDiagonalAngle(115.4)
  return (
    <div
      ref={gradRef}
      style={{
        margin: '0 20px', padding: '16px 18px', borderRadius: 22, background: gradient(angle), color: '#fff',
        boxShadow: shadow('rgba(122,105,237,0.28)', 8, 6),
      } as React.CSSProperties}
    >
      <VStack spacing={10} align="stretch">
        {trip ? (
          <>
            <HStack spacing={8}>
              {rank !== null && (
                <span style={{ width: 22, height: 22, borderRadius: '50%', background: 'rgba(255,255,255,0.28)', display: 'grid', placeItems: 'center', fontSize: 12, fontWeight: 800 }}>{rank}</span>
              )}
              <Capsule>{trip.course.isRunning ? tr('여행 중') : tr('예정')}</Capsule>
              <span style={{ fontSize: 12, opacity: 0.85 }}>
                {count === 1 ? trAt('%@ · %lld곳', '하나', courseSpanLabel(trip.course), count) : tr('%@ · %lld곳', courseSpanLabel(trip.course), count)}
              </span>
            </HStack>
            <Txt size={18} weight="heavy" lines={1} color="white">{trip.course.title}</Txt>
            <HStack spacing={10}>
              <div style={{ flex: 1, height: 6, borderRadius: 3, background: 'rgba(255,255,255,0.3)', overflow: 'hidden' }}>
                <div style={{ height: '100%', width: `${progress * 100}%`, background: '#fff', borderRadius: 3 }} />
              </div>
              <span style={{ fontSize: 12, fontWeight: 600, whiteSpace: 'nowrap' }}>{tr('스탬프 %lld/%lld', trip.visited, trip.total)}</span>
            </HStack>
            <HStack spacing={8}>
              <WhiteButton onClick={() => onNavigate(trip)} disabled={trip.nextStop === null}>{tr('이어서 길찾기')}</WhiteButton>
              <Pressable plain onClick={() => onOpenCourse(trip)} style={{ padding: '10px 14px', borderRadius: 14, background: 'rgba(255,255,255,0.2)' }}>
                <Txt size={14} weight="semibold" color="white">{tr('코스 보기')}</Txt>
              </Pressable>
            </HStack>
          </>
        ) : card === 'loading' || card === 'filled' ? (
          <div style={{ minHeight: 72, display: 'flex', alignItems: 'center', fontSize: 14, opacity: 0.9 }}>{tr('내 여행을 불러오는 중…')}</div>
        ) : card === 'unavailable' ? (
          <div style={{ minHeight: 72, display: 'flex', alignItems: 'center', fontSize: 14, opacity: 0.9 }}>{tr('내 여행을 불러오지 못했어요')}</div>
        ) : (
          <VStack spacing={10} align="start">
            <Capsule>{hasCourses ? tr('코스') : tr('첫 여행')}</Capsule>
            <Txt size={18} weight="heavy" color="white">{tr('여행을 시작해 볼까요?')}</Txt>
            <Txt size={13} color="white" style={{ opacity: 0.9 }}>{tr('보고 싶은 작품과 기간만 고르면 촬영지를 이어서 일정으로 짜 드립니다')}</Txt>
            <div style={{ width: '100%', display: 'flex' }}>
              <WhiteButton onClick={onCreate}>{tr('코스 만들기')}</WhiteButton>
            </div>
          </VStack>
        )}
      </VStack>
    </div>
  )
}
