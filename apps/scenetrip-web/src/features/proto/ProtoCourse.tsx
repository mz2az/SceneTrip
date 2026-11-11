/*
 * 코스 목록 시안 — 코스마다 한 줄(왼쪽 사진 + 글자). 로컬 개발 서버 전용 껍데기(docs/project/plans/web-trip-first-prototypes.md).
 * 데이터는 진짜(DEV) — 목록은 `routeStore`, 카드의 사진·작품은 `routeActions.detail`. 카드를 누르면 원래 코스 편집 화면.
 */
import { useContext, useEffect, useMemo, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { OverlayRootContext } from '@/design/overlay'
import { Icon } from '@/design/Icon'
import { HStack, Spacer, Txt, VStack } from '@/design/primitives'
import { homeCityStore } from '@/features/home/homeCityModel'
import { type RouteCourse, type RouteStop, courseStops, coursePlaceCount } from '@/features/route/models'
import { routeActions, useRouteState } from '@/features/route/store'
import { Pressable } from '@/features/route/ui/Press'
import { GlassDialog } from '@/features/route/ui/GlassDialog'
import { useStore } from '@/state/storage'
import { MockBadge } from './protoParts'
import { daysLabel, say } from './protoState'
import { color, contained, heading, radius } from './protoTokens'
import './proto.css'

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

// ---------------------------------------------------------------- 코스 목록

/**
 * 코스 목록 시안 — 「여행 중」·「다가오는 여행」 묶음마다 흰 칸 하나, 그 안에 코스 한 줄씩(왼쪽 사진 + 이름·기간·작품), 줄 사이 얇은 선.
 * 카드마다 그림자를 띄우던 모양은 「빈 도화지에 틀 없이 적은 느낌」(2026-11-07 사용자 지적)이라 앱 원래 목록(insetGrouped)의 틀로 바꿨다.
 * 원래 코스 화면(RouteCover)의 머리·목록 자리에 들어간다 — 새 코스 시트·마법사·편집 화면은 원래 것 그대로.
 */
export function ProtoCourseList({ onClose, onNew, onOpen, onDelete }: { onClose: () => void; onNew: () => void; onOpen: (course: RouteCourse) => void; onDelete: (course: RouteCourse) => void }) {
  const route = useRouteState()
  const saved = route.routeCourses.filter((c) => c.serverId !== null)
  const running = saved.filter((c) => c.isRunning)
  const upcoming = saved.filter((c) => !c.isRunning)
  // 고르기 — 줄을 꾹 누르면(마우스는 오른쪽 클릭) 켜지고 그 줄이 골라진다. 켜진 동안 줄을 누르면 체크만 바뀐다. null 이면 꺼져 있다.
  const [picked, setPicked] = useState<ReadonlySet<string> | null>(null)
  const [confirming, setConfirming] = useState(false)
  const [removing, setRemoving] = useState(false)
  const picking = picked !== null
  const toggle = (uid: string) =>
    setPicked((cur) => {
      const next = new Set(cur ?? [])
      if (!next.delete(uid)) next.add(uid)
      return next
    })
  const allPicked = picking && saved.length > 0 && saved.every((c) => picked.has(c.uid))
  const removePicked = async () => {
    const doomed = saved.filter((c) => picked?.has(c.uid))
    setRemoving(true)
    // 하나씩 지운다 — 서버에 여러 개 지우기가 없다. 지울 때마다 목록을 다시 받는다.
    for (const c of doomed) await routeActions.remove(c)
    setRemoving(false)
    setPicked(null)
  }
  const pick = { picked, onHold: (uid: string) => setPicked((cur) => (cur ? cur : new Set([uid]))), onToggle: toggle }

  return (
    <div style={{ flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column', background: 'var(--grouped-background)' }}>
      {picking ? (
        // 고르는 중 — 취소 · N개 선택 · 전체 선택
        <HStack spacing={8} style={{ padding: '8px 12px', minHeight: 56, background: 'var(--system-background)', borderBottom: '0.5px solid var(--separator)', flex: 'none' }}>
          <Pressable plain onClick={() => setPicked(null)} style={{ minHeight: 44, padding: '0 6px' }}>
            <Txt size={16} weight="semibold" style={{ color: color.primaryText }}>{say('취소', 'Cancel')}</Txt>
          </Pressable>
          <Spacer />
          <Txt as="h1" size={17} weight="bold" style={heading}>{picked.size > 0 ? say(`${picked.size}개 선택`, `${picked.size} selected`) : say('코스 선택', 'Select trips')}</Txt>
          <Spacer />
          <Pressable plain onClick={() => setPicked(allPicked ? new Set() : new Set(saved.map((c) => c.uid)))} style={{ minHeight: 44, padding: '0 6px' }}>
            <Txt size={16} weight="semibold" style={{ color: color.primaryText }}>{allPicked ? say('선택 해제', 'Deselect all') : say('전체 선택', 'Select all')}</Txt>
          </Pressable>
        </HStack>
      ) : (
      /* 위 줄 — 닫기 · 제목 · 새 코스. 아래 얇은 선으로 목록과 나눈다 */
      <HStack spacing={8} style={{ padding: '8px 12px', minHeight: 56, background: 'var(--system-background)', borderBottom: '0.5px solid var(--separator)', flex: 'none' }}>
        <Pressable plain className="proto-press" onClick={onClose} aria-label={say('닫기', 'Close')} style={{ width: 44, height: 44, display: 'grid', placeItems: 'center' }}>
          <Icon name="xmark" size={18} weight="semibold" color="primary" />
        </Pressable>
        <Txt as="h1" size={17} weight="bold" style={heading}>{say('내 여행', 'My trips')}</Txt>
        <Spacer />
        <MockBadge />
        <Pressable plain className="proto-press" onClick={onNew} style={{ height: 36, padding: '0 14px', borderRadius: radius.pill, background: color.primaryFill }}>
          <HStack spacing={4}>
            <Icon name="plus" size={13} weight="bold" color="white" />
            <Txt size={14} weight="bold" style={{ color: color.onPrimary }}>{say('새 코스', 'New')}</Txt>
          </HStack>
        </Pressable>
      </HStack>
      )}
      <div className="scroll-y" style={{ flex: 1, minHeight: 0, ...contained }}>
        <VStack spacing={24} align="stretch" style={{ padding: '18px 16px 32px' }}>
          {saved.length === 0 && (
            <VStack spacing={10} align="center" style={{ padding: '60px 0' }}>
              <Txt as="h2" size={17} weight="bold" style={heading}>{say('아직 코스가 없어요', 'No trips yet')}</Txt>
              <Txt size={15} color="secondary">{say('「새 코스」 로 첫 여행을 짜 보세요', 'Tap “New” to plan your first trip')}</Txt>
            </VStack>
          )}
          {running.length > 0 && <CourseGroup label={say('여행 중', 'On trip')} courses={running} onOpen={onOpen} onDelete={onDelete} pick={pick} />}
          {upcoming.length > 0 && <CourseGroup label={say('다가오는 여행', 'Upcoming')} courses={upcoming} onOpen={onOpen} onDelete={onDelete} pick={pick} />}
        </VStack>
      </div>
      {picking && (
        // 아래 고정 — 「N개 삭제」. 하나도 안 골랐으면 흐리게.
        <div style={{ flex: 'none', padding: '10px 16px calc(10px + var(--safe-bottom))', background: 'var(--system-background)', borderTop: '0.5px solid var(--separator)' }}>
          <Pressable
            plain
            disabled={picked.size === 0 || removing}
            onClick={() => setConfirming(true)}
            style={{ width: '100%', minHeight: 50, borderRadius: radius.m, background: 'var(--system-red)', opacity: picked.size === 0 || removing ? 0.4 : 1 }}
          >
            <HStack spacing={6} style={{ justifyContent: 'center' }}>
              <Icon name="trash" size={16} color="white" />
              <Txt size={16} weight="bold" style={{ color: '#fff' }}>{removing ? say('지우는 중…', 'Deleting…') : say(`${picked.size}개 삭제`, `Delete ${picked.size}`)}</Txt>
            </HStack>
          </Pressable>
        </div>
      )}
      <GlassDialog
        open={confirming}
        variant="popover"
        title={say(`코스 ${picked?.size ?? 0}개를 지울까요?`, `Delete ${picked?.size ?? 0} trips?`)}
        message={say('되돌릴 수 없습니다.', 'This can’t be undone.')}
        buttons={[{ label: say('삭제', 'Delete'), role: 'destructive', onPress: () => void removePicked() }]}
        onClose={() => setConfirming(false)}
      />
    </div>
  )
}

interface Pick {
  picked: ReadonlySet<string> | null
  onHold: (uid: string) => void
  onToggle: (uid: string) => void
}

/** 묶음 하나 — 위 작은 제목(개수), 흰 칸 안에 줄들. 줄 사이 선은 사진 오른쪽부터(앱 원래 목록처럼 글자 칸만 나눈다). */
function CourseGroup({ label, courses, onOpen, onDelete, pick }: { label: string; courses: readonly RouteCourse[]; onOpen: (c: RouteCourse) => void; onDelete: (c: RouteCourse) => void; pick: Pick }) {
  return (
    <section aria-label={label}>
      <HStack spacing={6} style={{ margin: '0 4px 7px' }}>
        <Txt as="h2" size={13} weight="semibold" color="secondary" style={heading}>{label}</Txt>
        <Txt size={13} color="tertiary">{courses.length}</Txt>
      </HStack>
      <ul style={{ listStyle: 'none', margin: 0, padding: 0, borderRadius: 12, background: 'var(--system-background)', overflow: 'hidden' }}>
        {courses.map((c, i) => <li key={c.uid}><CourseRow course={c} first={i === 0} onOpen={() => onOpen(c)} onDelete={() => onDelete(c)} picking={pick.picked !== null} checked={pick.picked?.has(c.uid) ?? false} onHold={() => pick.onHold(c.uid)} onToggle={() => pick.onToggle(c.uid)} /></li>)}
      </ul>
    </section>
  )
}

const THUMB = 60
const ROW_PAD = 12

/** 꾹 눌러 고르기를 켜는 시간(ms) — 해태 버튼 끌기와 같은 0.2초. 그 사이 이만큼 움직이면 스크롤로 본다. */
const HOLD_MS = 200
const HOLD_SLOP = 8
const MENU_W = 200

/**
 * 줄 메뉴 — 「⋯」 을 누르면 뜬다. 지금은 「삭제」 하나(확인 창은 코스 목록이 띄운다). 줄을 꾹 누르면(마우스는 오른쪽 클릭) 대신 여러 개 고르기가 켜진다.
 * 웹에서는 옆으로 밀기를 아무도 해 보지 않아, 지우기를 밀기 뒤에 숨기지 않는다(2026-11-08 사용자 결정).
 */
function RowMenu({ at, onClose, onDelete }: { at: { x: number; y: number }; onClose: () => void; onDelete: () => void }) {
  const root = useContext(OverlayRootContext)
  useEffect(() => {
    const esc = (e: KeyboardEvent) => e.key === 'Escape' && onClose()
    window.addEventListener('keydown', esc)
    return () => window.removeEventListener('keydown', esc)
  }, [onClose])
  if (!root) return null
  const frame = root.getBoundingClientRect()
  const left = Math.max(12, Math.min(at.x - frame.left - MENU_W + 24, frame.width - MENU_W - 12))
  const top = Math.min(at.y - frame.top + 8, frame.height - 70)
  return createPortal(
    <div style={{ position: 'absolute', inset: 0, zIndex: 90 }} onClick={onClose} onContextMenu={(e) => { e.preventDefault(); onClose() }}>
      <div role="menu" style={{ position: 'absolute', left, top, width: MENU_W, padding: '6px 0', borderRadius: 14, background: 'var(--system-background)', boxShadow: '0 8px 30px rgba(0,0,0,0.2)' }}>
        <Pressable plain role="menuitem" onClick={() => { onClose(); onDelete() }} style={{ width: '100%', minHeight: 44, padding: '0 16px' }}>
          <HStack spacing={10} style={{ width: '100%' }}>
            <Txt size={16} weight="semibold" style={{ color: 'var(--system-red)', flex: 1 }}>{say('삭제', 'Delete')}</Txt>
            <Icon name="trash" size={16} color="var(--system-red)" />
          </HStack>
        </Pressable>
      </div>
    </div>,
    root,
  )
}

function CourseRow({ course, first, onOpen, onDelete, picking, checked, onHold, onToggle }: {
  course: RouteCourse
  first: boolean
  onOpen: () => void
  onDelete: () => void
  /** 고르는 중이면 누르기가 체크가 되고 「⋯」 이 숨는다. */
  picking: boolean
  checked: boolean
  /** 꾹 누름 — 고르기를 켜고 이 줄을 고른다. */
  onHold: () => void
  onToggle: () => void
}) {
  const [menuAt, setMenuAt] = useState<{ x: number; y: number } | null>(null)
  const hold = useRef<{ x: number; y: number; timer: number; fired: boolean } | null>(null)
  const cancelHold = () => {
    if (hold.current) window.clearTimeout(hold.current.timer)
  }
  const detail = useDetail(course)
  const workOf = useWorkOf()
  const stops = detail ? courseStops(detail) : []
  const photos = stops.map(photoOf).filter((u): u is string => !!u)
  const count = detail ? stops.length : coursePlaceCount(course)
  const works = [...new Set(stops.map(workOf).filter((t): t is string => !!t))].slice(0, 2)

  return (
    <div style={{ position: 'relative' }}>
    <Pressable
      plain
      onClick={() => {
        // 꾹 눌러 메뉴를 띄웠으면 손을 뗀 그 클릭으로 코스가 열리지 않게 한다.
        if (hold.current?.fired) {
          hold.current = null
          return
        }
        if (picking) onToggle()
        else onOpen()
      }}
      onPointerDown={(e) => {
        if (e.pointerType === 'mouse' && e.button !== 0) return
        const h = { x: e.clientX, y: e.clientY, timer: 0, fired: false }
        if (picking) return
        h.timer = window.setTimeout(() => {
          h.fired = true
          navigator.vibrate?.(10)
          onHold()
        }, HOLD_MS)
        hold.current = h
      }}
      onPointerMove={(e) => {
        const h = hold.current
        if (h && !h.fired && Math.hypot(e.clientX - h.x, e.clientY - h.y) > HOLD_SLOP) cancelHold()
      }}
      onPointerUp={cancelHold}
      onPointerCancel={cancelHold}
      onContextMenu={(e) => {
        e.preventDefault()
        cancelHold()
        if (!picking) onHold()
      }}
      aria-pressed={picking ? checked : undefined}
      style={{ display: 'block', width: '100%', textAlign: 'left', padding: `0 0 0 ${ROW_PAD}px`, WebkitTouchCallout: 'none', userSelect: 'none', background: checked ? 'color-mix(in srgb, var(--home-purple) 13%, var(--system-background))' : undefined }}
    >
      <HStack spacing={12} style={{ width: '100%' }}>
        {picking && (
          <span aria-hidden style={{ flex: 'none', width: 24, height: 24, borderRadius: 999, display: 'grid', placeItems: 'center', background: checked ? color.primaryFill : 'transparent', border: checked ? 'none' : '1.5px solid var(--system-gray3)' }}>
            {checked && <Icon name="checkmark" size={12} weight="bold" color="white" />}
          </span>
        )}
        <div aria-hidden style={{ width: THUMB, height: THUMB, margin: `${ROW_PAD}px 0`, flex: 'none', borderRadius: radius.s, overflow: 'hidden', background: color.brandFill }}>
          {photos[0] && <img src={photos[0]} alt="" referrerPolicy="no-referrer" style={{ width: '100%', height: '100%', objectFit: 'cover' }} />}
        </div>
        {/* 글자 칸 — 위 선이 여기서부터 시작한다 */}
        <HStack spacing={8} style={{ flex: 1, minWidth: 0, alignSelf: 'stretch', padding: `${ROW_PAD}px ${ROW_PAD}px ${ROW_PAD}px 0`, borderTop: first ? undefined : '0.5px solid var(--separator)', marginTop: first ? 0 : -0.5 }}>
          <VStack spacing={3} align="start" style={{ flex: 1, minWidth: 0 }}>
            <Txt size={16} weight="semibold" lines={1}>{course.title}</Txt>
            <Txt size={13} color="secondary" lines={1}>
              {[course.isRunning ? say(`${course.currentDayNo ?? 1}일차`, `Day ${course.currentDayNo ?? 1}`) : null, daysLabel(course.days.length), say(`${count}곳`, count === 1 ? '1 place' : `${count} places`)].filter(Boolean).join(' · ')}
            </Txt>
            {works.length > 0 && <Txt size={12} color="purple" lines={1}>{works.join(' · ')}</Txt>}
          </VStack>
          {/* 오른쪽 끝은 「⋯」 단추 자리(줄 단추 밖의 형제라 단추 안에 단추가 없다). */}
          <span aria-hidden style={{ width: 32, flex: 'none' }} />
        </HStack>
      </HStack>
    </Pressable>
    {!picking && <Pressable
      plain
      className="proto-press"
      aria-label={say(`${course.title} 메뉴`, `${course.title} menu`)}
      aria-haspopup="menu"
      onClick={(e) => {
        const r = e.currentTarget.getBoundingClientRect()
        setMenuAt({ x: r.right - 12, y: r.bottom - 6 })
      }}
      style={{ position: 'absolute', right: 2, top: '50%', transform: 'translateY(-50%)', width: 44, height: 44, display: 'grid', placeItems: 'center' }}
    >
      <Icon name="ellipsis" size={17} weight="semibold" color="tertiary" />
    </Pressable>}
    {menuAt && <RowMenu at={menuAt} onClose={() => setMenuAt(null)} onDelete={onDelete} />}
    </div>
  )
}
