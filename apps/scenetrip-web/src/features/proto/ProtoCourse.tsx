/*
 * 코스 시안 — 코스 목록(사진 카드)과 코스 한 개(사진 있는 하루 일정표). 로컬 개발 서버 전용 껍데기(docs/project/plans/web-trip-first-prototypes.md §9).
 * 데이터는 진짜(DEV) — 목록은 `routeStore`, 한 코스의 속은 `routeActions.detail`. 고치기·여행 시작의 실제 동작은 원래 코스 편집 화면에 맡긴다.
 */
import { type CSSProperties, useEffect, useMemo, useRef, useState } from 'react'
import { paths } from '@/app/routes'
import { useTabRouter } from '@/app/tabRouter'
import { Icon } from '@/design/Icon'
import { HStack, Spacer, Spinner, Txt, VStack } from '@/design/primitives'
import { distanceKm } from '@/features/home/homeCities'
import { homeCityStore } from '@/features/home/homeCityModel'
import { type RouteCourse, type RouteStop, courseStops, coursePlaceCount } from '@/features/route/models'
import { routeActions, useRouteState } from '@/features/route/store'
import { Pressable } from '@/features/route/ui/Press'
import { useStore } from '@/state/storage'
import { type MapHandle, type MapLayer, createMap } from '@/map'
import { MockBadge } from './protoParts'
import { daysLabel, protoActions, say } from './protoState'

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

/** 두 곳 사이 이동 — 직선 거리로 어림한다(걸어서 시속 4.5km, 2km 넘으면 차·대중교통 시속 25km). 시안이라 실제 길찾기는 부르지 않는다. */
function leg(a: RouteStop, b: RouteStop): { km: number; minutes: number; walk: boolean } {
  const km = distanceKm({ lat: a.place.latitude, lng: a.place.longitude }, { lat: b.place.latitude, lng: b.place.longitude })
  const walk = km <= 2
  return { km, minutes: Math.max(1, Math.round((km / (walk ? 4.5 : 25)) * 60)), walk }
}

const fmtMinutes = (m: number) => (m >= 60 ? say(`${Math.floor(m / 60)}시간${m % 60 ? ` ${m % 60}분` : ''}`, `${Math.floor(m / 60)}h${m % 60 ? ` ${m % 60}m` : ''}`) : say(`${m}분`, `${m} min`))
const fmtKm = (km: number) => (km < 1 ? `${Math.round(km * 1000)} m` : `${km.toFixed(1)} km`)

function statusOf(c: RouteCourse): { text: string; live: boolean } {
  if (c.isRunning) return { text: say(`여행 중 · ${c.currentDayNo ?? 1}일차`, `On trip · Day ${c.currentDayNo ?? 1}`), live: true }
  return { text: say('다가오는 여행', 'Upcoming'), live: false }
}

// ---------------------------------------------------------------- 코스 목록

/** 코스 목록 시안 — 코스마다 큰 사진 카드(첫 장소 사진 + 장소 사진 줄), 여행 중인 코스가 맨 위. */
export function ProtoCourseList({ onClose }: { onClose: () => void }) {
  const router = useTabRouter()
  const route = useRouteState()
  const courses = [...route.routeCourses.filter((c) => c.isRunning), ...route.routeCourses.filter((c) => !c.isRunning)].filter((c) => c.serverId !== null)

  return (
    <div style={{ position: 'absolute', inset: 0, zIndex: 45, display: 'flex', flexDirection: 'column', background: 'var(--grouped-background)' }}>
      <HStack spacing={8} style={{ padding: 'calc(env(safe-area-inset-top, 0px) + 12px) 16px 6px', minHeight: 44 }}>
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
            <Pressable plain onClick={() => router.navigate(paths.courseNew)} style={{ height: 36, padding: '0 14px', borderRadius: 999, background: 'var(--pin-gradient)', boxShadow: glow }}>
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
          {courses.map((c) => <CourseCard key={c.uid} course={c} onOpen={() => protoActions.view(c.serverId!)} />)}
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

// ---------------------------------------------------------------- 코스 한 개 — 하루 일정표

export function ProtoCourseView({ courseId, onClose }: { courseId: number; onClose: () => void }) {
  const router = useTabRouter()
  const route = useRouteState()
  const summary = route.routeCourses.find((c) => c.serverId === courseId) ?? null
  const detail = useDetail(summary)
  const workOf = useWorkOf()
  const [dayIndex, setDayIndex] = useState(0)
  const day = detail?.days[Math.min(dayIndex, Math.max(0, (detail?.days.length ?? 1) - 1))] ?? null
  const stops = day?.stops ?? []
  const legs = stops.slice(1).map((s, i) => leg(stops[i], s))
  const stayMinutes = stops.reduce((n, s) => n + s.stayMinutes, 0)
  const moveMinutes = legs.reduce((n, l) => n + l.minutes, 0)
  const status = summary ? statusOf(summary) : null
  const edit = () => {
    onClose()
    router.openCourse(courseId)
  }

  return (
    <div style={{ position: 'absolute', inset: 0, zIndex: 46, display: 'flex', flexDirection: 'column', background: 'var(--grouped-background)' }}>
      <div style={{ position: 'relative', height: '38%', minHeight: 230, flex: 'none' }}>
        <DayMap stops={stops} />
        <HStack spacing={8} style={{ position: 'absolute', top: 'calc(env(safe-area-inset-top, 0px) + 10px)', left: 14, right: 14, zIndex: 500 }}>
          <Pressable plain onClick={onClose} aria-label={say('뒤로', 'Back')} style={{ width: 40, height: 40, borderRadius: 999, background: 'var(--system-background)', boxShadow: '0 2px 10px rgba(0,0,0,0.15)', display: 'grid', placeItems: 'center' }}>
            <Icon name="chevron.left" size={18} weight="semibold" color="primary" />
          </Pressable>
          <Spacer />
          <MockBadge onPhoto />
          <Pressable plain onClick={edit} style={{ height: 40, padding: '0 14px', borderRadius: 999, background: 'var(--system-background)', boxShadow: '0 2px 10px rgba(0,0,0,0.15)' }}>
            <HStack spacing={5}>
              <Icon name="pencil" size={14} weight="semibold" color="accent" />
              <Txt size={14} weight="bold" color="accent">{say('고치기', 'Edit')}</Txt>
            </HStack>
          </Pressable>
        </HStack>
      </div>

      <div className="scroll-y" style={{ flex: 1, minHeight: 0, marginTop: -18, borderRadius: '22px 22px 0 0', background: 'var(--grouped-background)', position: 'relative', zIndex: 1, boxShadow: '0 -4px 16px rgba(0,0,0,0.06)' }}>
        <VStack spacing={14} align="stretch" style={{ padding: '18px 20px 110px' }}>
          <VStack spacing={4} align="start">
            {status && (
              <Txt size={12} weight="bold" style={{ color: status.live ? 'var(--accent)' : 'var(--label-secondary)', letterSpacing: 0.3 }}>{status.text}</Txt>
            )}
            <Txt size={26} weight="heavy" lines={2}>{summary?.title ?? ''}</Txt>
          </VStack>

          {detail && detail.days.length > 1 && (
            <div className="scroll-x" style={{ margin: '0 -20px' }} data-sheet-nodrag>
              <HStack spacing={8} style={{ padding: '2px 20px', width: 'max-content' }}>
                {detail.days.map((d, i) => (
                  <Pressable key={d.uid} plain onClick={() => setDayIndex(i)} aria-pressed={i === dayIndex} style={{ height: 36, padding: '0 16px', borderRadius: 999, background: i === dayIndex ? 'var(--pin-gradient)' : 'var(--system-background)', boxShadow: i === dayIndex ? glow : '0 1px 4px rgba(0,0,0,0.08)' }}>
                    <Txt size={14} weight="bold" style={{ color: i === dayIndex ? '#fff' : 'var(--label)' }}>{say(`${i + 1}일차`, `Day ${i + 1}`)}</Txt>
                  </Pressable>
                ))}
              </HStack>
            </div>
          )}

          {!detail ? (
            <HStack spacing={8}><Spinner size={16} /><Txt size={14} color="secondary">{say('일정을 불러오는 중…', 'Loading the plan…')}</Txt></HStack>
          ) : (
            <>
              <Txt size={14} color="secondary">
                {say(`${stops.length}곳 · 약 ${fmtMinutes(stayMinutes + moveMinutes)} (이동 ${fmtMinutes(moveMinutes)})`, `${stops.length} places · about ${fmtMinutes(stayMinutes + moveMinutes)} (${fmtMinutes(moveMinutes)} on the move)`)}
              </Txt>
              {stops.length === 0 && <Txt size={15} color="secondary">{say('이 날은 아직 장소가 없어요', 'No places on this day yet')}</Txt>}
              <VStack spacing={0} align="stretch">
                {stops.map((s, i) => (
                  <div key={s.uid}>
                    <StopRow stop={s} index={i} work={workOf(s)} />
                    {i < legs.length && <LegRow leg={legs[i]} />}
                  </div>
                ))}
              </VStack>
              <Pressable plain onClick={edit} style={{ height: 46, borderRadius: 14, border: '1.5px dashed color-mix(in srgb, var(--accent) 45%, transparent)', textAlign: 'center' }}>
                <HStack spacing={6} justify="center">
                  <Icon name="plus" size={15} weight="bold" color="accent" />
                  <Txt size={15} weight="bold" color="accent">{say('장소 더하기 · 순서 바꾸기', 'Add places · reorder')}</Txt>
                </HStack>
              </Pressable>
            </>
          )}
        </VStack>
      </div>

      <div style={{ position: 'absolute', left: 0, right: 0, bottom: 0, zIndex: 2, padding: '12px 20px calc(env(safe-area-inset-bottom, 0px) + 16px)', background: 'linear-gradient(to top, var(--grouped-background) 65%, transparent)' }}>
        <Pressable plain onClick={edit} style={{ width: '100%', height: 52, borderRadius: 16, background: 'var(--pin-gradient)', boxShadow: glow, textAlign: 'center' }}>
          <HStack spacing={8} justify="center">
            <Icon name="location.north.fill" size={16} color="white" />
            <Txt size={17} weight="bold" color="white">{summary?.isRunning ? say('이어서 길안내', 'Continue directions') : say(`${dayIndex + 1}일차 여행 시작`, `Start day ${dayIndex + 1}`)}</Txt>
          </HStack>
        </Pressable>
      </div>
    </div>
  )
}

function StopRow({ stop, index, work }: { stop: RouteStop; index: number; work: string | null }) {
  const photo = photoOf(stop)
  return (
    <HStack spacing={12} align="start" style={{ position: 'relative' }}>
      <div style={{ width: 28, flex: 'none', display: 'flex', justifyContent: 'center', paddingTop: 20 }}>
        <span style={{ width: 26, height: 26, borderRadius: 999, background: stop.visited ? 'var(--system-green)' : 'var(--pin-gradient)', color: '#fff', fontSize: 13, fontWeight: 800, display: 'grid', placeItems: 'center', boxShadow: '0 2px 6px rgba(92,74,214,0.35)' }}>
          {stop.visited ? '✓' : index + 1}
        </span>
      </div>
      <HStack spacing={12} style={{ ...card, flex: 1, minWidth: 0, padding: 10 }}>
        <div style={{ width: 64, height: 64, flex: 'none', borderRadius: 14, overflow: 'hidden', background: 'linear-gradient(135deg, var(--pin-light), var(--pin-deep))' }}>
          {photo && <img src={photo} alt="" loading="lazy" referrerPolicy="no-referrer" style={{ width: '100%', height: '100%', objectFit: 'cover' }} />}
        </div>
        <VStack spacing={3} align="start" style={{ flex: 1, minWidth: 0 }}>
          <Txt size={16} weight="bold" lines={1}>{stop.place.name}</Txt>
          {work && <Txt size={12} weight="semibold" color="purple" lines={1}>{work}</Txt>}
          <Txt size={12} color="secondary">{say(`머무는 시간 ${fmtMinutes(stop.stayMinutes)}`, `Stay ${fmtMinutes(stop.stayMinutes)}`)}</Txt>
        </VStack>
      </HStack>
    </HStack>
  )
}

function LegRow({ leg: l }: { leg: ReturnType<typeof leg> }) {
  return (
    <HStack spacing={12} style={{ minHeight: 38 }}>
      <div style={{ width: 28, flex: 'none', display: 'flex', justifyContent: 'center', alignSelf: 'stretch' }}>
        <div style={{ width: 2, background: 'repeating-linear-gradient(to bottom, color-mix(in srgb, var(--accent) 45%, transparent) 0 4px, transparent 4px 8px)' }} />
      </div>
      <HStack spacing={6}>
        <Icon name={l.walk ? 'footprints' : 'car'} size={13} color="secondary" />
        <Txt size={12} color="secondary">{`${fmtMinutes(l.minutes)} · ${fmtKm(l.km)}`}</Txt>
      </HStack>
    </HStack>
  )
}

/** 그날 장소를 번호 핀과 동선으로. 바탕 지도는 색을 빼 핀과 선이 또렷하게. */
function DayMap({ stops }: { stops: readonly RouteStop[] }) {
  const el = useRef<HTMLDivElement>(null)
  const map = useRef<MapHandle | null>(null)
  const layer = useRef<MapLayer | null>(null)
  const [ready, setReady] = useState(false)
  const key = useMemo(() => stops.map((s) => s.uid).join(','), [stops])

  useEffect(() => {
    let cancelled = false
    if (!el.current) return
    void createMap(el.current, { isCancelled: () => cancelled }).then((m) => {
      if (cancelled) return m.destroy()
      map.current = m
      layer.current = m.createLayer()
      setReady(true)
    }).catch(() => undefined)
    return () => {
      cancelled = true
      map.current?.destroy()
      map.current = null
    }
  }, [])

  useEffect(() => {
    const m = map.current
    const l = layer.current
    if (!m || !l || !ready) return
    l.clear()
    const pts = stops.map((s) => ({ lat: s.place.latitude, lng: s.place.longitude }))
    if (pts.length > 1) l.addPolyline({ path: pts, color: '#7a69ed', weight: 4, opacity: 0.85, lineCap: 'round', lineJoin: 'round' })
    stops.forEach((s, i) =>
      l.addMarker({
        position: pts[i],
        html: `<div style="width:30px;height:30px;border-radius:999px;background:linear-gradient(135deg,#8fccf7,#7a69ed);color:#fff;font:800 14px/30px system-ui;text-align:center;border:2.5px solid #fff;box-shadow:0 2px 8px rgba(60,40,160,.4)">${i + 1}</div>`,
        className: 'proto-stop-pin',
        size: [30, 30],
        anchor: [15, 15],
        title: s.place.name,
      }),
    )
    if (pts.length > 0) {
      const lats = pts.map((p) => p.lat)
      const lngs = pts.map((p) => p.lng)
      m.fitBounds(
        { southWest: { lat: Math.min(...lats), lng: Math.min(...lngs) }, northEast: { lat: Math.max(...lats), lng: Math.max(...lngs) } },
        { paddingTopLeft: [40, 70], paddingBottomRight: [40, 40], maxZoom: 15, animate: false },
      )
    }
  }, [key, ready, stops])

  return (
    <>
      <div ref={el} className="proto-day-map" style={{ position: 'absolute', inset: 0, background: '#eef0f4' }} />
      <style>{'.proto-day-map .leaflet-tile-pane { filter: saturate(0.35) brightness(1.06) contrast(0.92); }'}</style>
    </>
  )
}
