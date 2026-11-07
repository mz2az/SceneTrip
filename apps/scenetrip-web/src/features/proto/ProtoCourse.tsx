/*
 * 코스 목록 시안 — 코스마다 사진 카드. 로컬 개발 서버 전용 껍데기(docs/project/plans/web-trip-first-prototypes.md).
 * 데이터는 진짜(DEV) — 목록은 `routeStore`, 카드의 사진·작품은 `routeActions.detail`. 카드를 누르면 원래 코스 편집 화면.
 */
import { type CSSProperties, useEffect, useMemo, useState } from 'react'
import { Icon } from '@/design/Icon'
import { HStack, Spacer, Txt, VStack } from '@/design/primitives'
import { homeCityStore } from '@/features/home/homeCityModel'
import { type RouteCourse, type RouteStop, courseStops, coursePlaceCount } from '@/features/route/models'
import { routeActions, useRouteState } from '@/features/route/store'
import { Pressable } from '@/features/route/ui/Press'
import { useStore } from '@/state/storage'
import { MockBadge } from './protoParts'
import { daysLabel, say } from './protoState'

// ---------------------------------------------------------------- 공통

const glow = '0 6px 18px rgba(92, 74, 214, 0.3)'
const card: CSSProperties = { borderRadius: 20, background: 'var(--system-background)', boxShadow: '0 2px 12px rgba(30, 20, 90, 0.08)', overflow: 'hidden' }

/** 코스 한 개의 속(일차·장소)을 받는다. 목록 요약에는 속이 없다. */
function useDetail(course: RouteCourse | null): RouteCourse | null {
  const [detail, setDetail] = useState<RouteCourse | null>(null)
  const id = course?.serverId ?? null
  useEffect(() => {
    setDetail(null)
    if (!course || id === null) return
    let live = true
    void routeActions.detail(course).then((d) => { if (live) setDetail(d) })
    return () => { live = false }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- 같은 코스면 다시 받지 않는다.
  }, [id])
  return detail
}

const photoOf = (s: RouteStop | undefined) => s?.place.imageUrl ?? null

/**
 * 촬영지 id → 작품 이름. 코스 상세의 장소에는 작품이 실려 오지 않아, 홈이 이미 받아 둔 촬영지 목록(도시별 묶음)에서 찾는다.
 * 목록이 아직 없거나 편의시설이면 비어 있다.
 */
function useWorkOf(): (stop: RouteStop) => string | null {
  const cities = useStore(homeCityStore, (s) => s.cities)
  const byId = useMemo(() => {
    const m = new Map<number, string>()
    for (const c of cities) for (const p of c.places) { const t = p.contents?.[0]?.title; if (t) m.set(p.id, t) }
    return m
  }, [cities])
  return (stop) => stop.place.contents?.[0]?.title ?? byId.get(stop.place.id) ?? null
}

function statusOf(c: RouteCourse): { text: string; live: boolean } {
  if (c.isRunning) return { text: say(`여행 중 · ${c.currentDayNo ?? 1}일차`, `On trip · Day ${c.currentDayNo ?? 1}`), live: true }
  return { text: say('다가오는 여행', 'Upcoming'), live: false }
}

// ---------------------------------------------------------------- 코스 목록

/**
 * 코스 목록 시안 — 코스마다 큰 사진 카드(첫 사진 있는 장소 + 다른 장소 사진 줄), 여행 중인 코스가 맨 위.
 * 원래 코스 화면(RouteCover)의 머리·목록 자리에 들어간다 — 새 코스 시트·마법사·편집 화면은 원래 것 그대로.
 */
export function ProtoCourseList({ onClose, onNew, onOpen }: { onClose: () => void; onNew: () => void; onOpen: (course: RouteCourse) => void }) {
  const route = useRouteState()
  const courses = [...route.routeCourses.filter((c) => c.isRunning), ...route.routeCourses.filter((c) => !c.isRunning)].filter((c) => c.serverId !== null)

  return (
    <div style={{ flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column' }}>
      <HStack spacing={8} style={{ padding: '12px 16px 6px', minHeight: 44 }}>
        <Pressable plain onClick={onClose} aria-label={say('닫기', 'Close')} style={{ width: 40, height: 40, display: 'grid', placeItems: 'center' }}>
          <Icon name="xmark" size={18} weight="semibold" color="primary" />
        </Pressable>
        <Spacer />
        <MockBadge />
      </HStack>
      <div className="scroll-y" style={{ flex: 1, minHeight: 0 }}>
        <VStack spacing={16} align="stretch" style={{ padding: '0 20px 32px' }}>
          <HStack spacing={8} align="baseline">
            <Txt size={30} weight="heavy">{say('내 여행', 'My trips')}</Txt>
            <Txt size={15} color="secondary">{courses.length}</Txt>
            <Spacer />
            <Pressable plain onClick={onNew} style={{ height: 36, padding: '0 14px', borderRadius: 999, background: 'var(--pin-gradient)', boxShadow: glow }}>
              <HStack spacing={5}>
                <Icon name="plus" size={14} weight="bold" color="white" />
                <Txt size={14} weight="bold" color="white">{say('새 코스', 'New')}</Txt>
              </HStack>
            </Pressable>
          </HStack>
          {courses.length === 0 && (
            <VStack spacing={10} align="center" style={{ padding: '60px 0' }}>
              <Txt size={17} weight="bold">{say('아직 코스가 없어요', 'No trips yet')}</Txt>
              <Txt size={15} color="secondary">{say('「새 코스」 로 첫 여행을 짜 보세요', 'Tap “New” to plan your first trip')}</Txt>
            </VStack>
          )}
          {courses.map((c) => <CourseCard key={c.uid} course={c} onOpen={() => onOpen(c)} />)}
        </VStack>
      </div>
    </div>
  )
}

function CourseCard({ course, onOpen }: { course: RouteCourse; onOpen: () => void }) {
  const detail = useDetail(course)
  const workOf = useWorkOf()
  const stops = detail ? courseStops(detail) : []
  const photos = stops.map(photoOf).filter((u): u is string => !!u)
  const status = statusOf(course)
  const count = detail ? stops.length : coursePlaceCount(course)
  const works = [...new Set(stops.map(workOf).filter((t): t is string => !!t))].slice(0, 2)

  return (
    <Pressable plain onClick={onOpen} style={{ ...card, textAlign: 'left', display: 'block', width: '100%' }}>
      <div style={{ position: 'relative', height: 150, background: 'var(--pin-gradient)' }}>
        {photos[0] && <img src={photos[0]} alt="" referrerPolicy="no-referrer" style={{ position: 'absolute', inset: 0, width: '100%', height: '100%', objectFit: 'cover' }} />}
        <div style={{ position: 'absolute', inset: 0, background: 'linear-gradient(to bottom, rgba(0,0,0,0) 35%, rgba(0,0,0,0.6) 100%)' }} />
        <span style={{ position: 'absolute', top: 12, left: 12, display: 'inline-flex', alignItems: 'center', gap: 5, padding: '4px 10px', borderRadius: 999, fontSize: 12, fontWeight: 700, background: status.live ? 'var(--pin-gradient)' : 'rgba(255,255,255,0.92)', color: status.live ? '#fff' : 'var(--home-purple)' }}>
          {status.live && <Icon name="location.north.fill" size={11} color="white" />}
          {status.text}
        </span>
        <VStack spacing={2} align="start" style={{ position: 'absolute', left: 14, right: 14, bottom: 12 }}>
          <Txt size={21} weight="heavy" color="white" lines={1} style={{ textShadow: '0 1px 6px rgba(0,0,0,0.35)' }}>{course.title}</Txt>
          <Txt size={13} color="white" style={{ opacity: 0.9 }}>{`${daysLabel(course.days.length)} · ${say(`${count}곳`, `${count} places`)}`}</Txt>
        </VStack>
      </div>
      <HStack spacing={8} style={{ padding: '12px 14px' }}>
        <HStack spacing={-8}>
          {photos.slice(1, 5).map((u, i) => (
            <img key={i} src={u} alt="" referrerPolicy="no-referrer" style={{ width: 32, height: 32, borderRadius: 999, objectFit: 'cover', border: '2px solid var(--system-background)', position: 'relative', zIndex: 5 - i }} />
          ))}
        </HStack>
        <Txt size={13} color="purple" weight="semibold" lines={1} style={{ minWidth: 0, flex: 1 }}>{works.join(' · ')}</Txt>
        <Icon name="chevron.right" size={14} weight="semibold" color="tertiary" />
      </HStack>
    </Pressable>
  )
}
