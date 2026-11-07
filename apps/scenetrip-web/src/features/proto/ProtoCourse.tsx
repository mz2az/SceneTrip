/*
 * 코스 목록 시안 — 코스마다 한 줄(왼쪽 사진 + 글자). 로컬 개발 서버 전용 껍데기(docs/project/plans/web-trip-first-prototypes.md).
 * 데이터는 진짜(DEV) — 목록은 `routeStore`, 카드의 사진·작품은 `routeActions.detail`. 카드를 누르면 원래 코스 편집 화면.
 */
import { useEffect, useMemo, useState } from 'react'
import { Icon } from '@/design/Icon'
import { HStack, Spacer, Txt, VStack } from '@/design/primitives'
import { homeCityStore } from '@/features/home/homeCityModel'
import { type RouteCourse, type RouteStop, courseStops, coursePlaceCount } from '@/features/route/models'
import { routeActions, useRouteState } from '@/features/route/store'
import { Pressable } from '@/features/route/ui/Press'
import { useStore } from '@/state/storage'
import { MockBadge } from './protoParts'
import { daysLabel, say } from './protoState'
import { color, contained, heading, radius, shadow } from './protoTokens'

// ---------------------------------------------------------------- 공통

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
 * 코스 목록 시안 — 코스마다 한 줄: 왼쪽 사진(첫 사진 있는 장소) + 이름·기간·작품, 여행 중인 코스가 맨 위.
 * 큰 사진 위 흰 글씨는 홈 카드에만 쓰고 여기서는 글자를 주인공으로 둔다(S2 — 같은 분위기를 되풀이하지 않게).
 * 원래 코스 화면(RouteCover)의 머리·목록 자리에 들어간다 — 새 코스 시트·마법사·편집 화면은 원래 것 그대로.
 */
export function ProtoCourseList({ onClose, onNew, onOpen }: { onClose: () => void; onNew: () => void; onOpen: (course: RouteCourse) => void }) {
  const route = useRouteState()
  const courses = [...route.routeCourses.filter((c) => c.isRunning), ...route.routeCourses.filter((c) => !c.isRunning)].filter((c) => c.serverId !== null)

  return (
    <div style={{ flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column' }}>
      <HStack spacing={8} style={{ padding: '12px 16px 6px', minHeight: 44 }}>
        <Pressable plain onClick={onClose} aria-label={say('닫기', 'Close')} style={{ width: 44, height: 44, display: 'grid', placeItems: 'center' }}>
          <Icon name="xmark" size={18} weight="semibold" color="primary" />
        </Pressable>
        <Spacer />
        <MockBadge />
      </HStack>
      <div className="scroll-y" style={{ flex: 1, minHeight: 0, ...contained }}>
        <VStack spacing={16} align="stretch" style={{ padding: '0 20px 32px' }}>
          <HStack spacing={8} align="baseline">
            <Txt as="h1" size={30} weight="heavy" style={heading}>{say('내 여행', 'My trips')}</Txt>
            <Txt size={15} color="secondary">{courses.length}</Txt>
            <Spacer />
            <Pressable plain onClick={onNew} style={{ height: 44, padding: '0 16px', borderRadius: radius.pill, background: color.primary }}>
              <HStack spacing={5}>
                <Icon name="plus" size={14} weight="bold" color="white" />
                <Txt size={14} weight="bold" style={{ color: color.onPrimary }}>{say('새 코스', 'New')}</Txt>
              </HStack>
            </Pressable>
          </HStack>
          {courses.length === 0 && (
            <VStack spacing={10} align="center" style={{ padding: '60px 0' }}>
              <Txt as="h2" size={17} weight="bold" style={heading}>{say('아직 코스가 없어요', 'No trips yet')}</Txt>
              <Txt size={15} color="secondary">{say('「새 코스」 로 첫 여행을 짜 보세요', 'Tap “New” to plan your first trip')}</Txt>
            </VStack>
          )}
          <ul style={{ listStyle: 'none', margin: 0, padding: 0, display: 'flex', flexDirection: 'column', gap: 10 }}>
            {courses.map((c) => <li key={c.uid}><CourseCard course={c} onOpen={() => onOpen(c)} /></li>)}
          </ul>
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
    <Pressable plain onClick={onOpen} style={{ display: 'block', width: '100%', textAlign: 'left', padding: 10, borderRadius: radius.m, background: 'var(--system-background)', boxShadow: shadow.card }}>
      <HStack spacing={12}>
        <div aria-hidden style={{ width: 76, height: 76, flex: 'none', borderRadius: radius.s, overflow: 'hidden', background: color.brandFill }}>
          {photos[0] && <img src={photos[0]} alt="" referrerPolicy="no-referrer" style={{ width: '100%', height: '100%', objectFit: 'cover' }} />}
        </div>
        <VStack spacing={4} align="start" style={{ flex: 1, minWidth: 0 }}>
          <Txt size={12} weight="bold" style={{ color: status.live ? color.primaryText : 'var(--label-secondary)' }}>
            {status.live && <Icon name="location.north.fill" size={11} color={color.primaryText} />} {status.text}
          </Txt>
          <Txt as="h2" size={17} weight="heavy" lines={1} style={heading}>{course.title}</Txt>
          <Txt size={13} color="secondary" lines={1}>{[daysLabel(course.days.length), say(`${count}곳`, count === 1 ? "1 place" : `${count} places`), ...works].join(' · ')}</Txt>
        </VStack>
        <Icon name="chevron.right" size={14} weight="semibold" color="tertiary" />
      </HStack>
    </Pressable>
  )
}
