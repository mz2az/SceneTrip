/*
 * 코스 목록 — iOS RouteTabView 의 `courseList` / `row` / `rowButton` (List .insetGrouped).
 *
 * 진행 중인 코스를 맨 위에 따로 모은다 — 여행 중에는 그것 말고 볼 것이 없다. 헤더 숫자는 이 절에 나열되는 것만 센다
 * (전체를 세니 「내 코스 3」 아래가 텅 빈 채였다 — 2026-09-05 사용자 지적).
 * 「여행 중」 코스도 지울 수 있어야 한다. 밀어야 나오는 것을 알려 주는 표시가 없어서 글자가 붙은 빨간 버튼이다(2026-08-24 사용자 지적).
 */
import { useState } from 'react'
import { Icon } from '@/design/Icon'
import { HStack, Spacer, Txt, VStack } from '@/design/primitives'
import { tr } from '@/i18n'
import { type RouteCourse, courseDateLabel, courseSpanLabel } from './models'
import { SwipeDelete } from './ui/SwipeDelete'
import { SwipeRow } from './ui/SwipeRow'
import { Pressable } from '@/design/primitives'

/** insetGrouped 의 좌우 여백과 카드 모서리 (iOS 26 스크린샷: 16pt / 26pt). */
const LIST_INSET = 16
const CARD_RADIUS = 26
/** 줄 높이 69pt — 한글 제목 15pt semibold + 12pt 부제 기준 (RouteTabView.row). */
const ROW_MIN_HEIGHT = 69
/** 밀었을 때 줄이 좁아지는 폭 = 캡슐 60 + 바깥 여백. */
const REVEAL = 81
const CAPSULE = { w: 60, h: 41, top: 4 }

export function SectionHeader({ children }: { children: string }) {
  return (
    <div style={{ padding: `6px ${LIST_INSET + 16}px 9px` }}>
      <Txt size={15} weight="semibold" color="secondary">{children}</Txt>
    </div>
  )
}

function CourseRowBody({ course }: { course: RouteCourse }) {
  return (
    <VStack spacing={3} align="start" style={{ padding: '0 16px', minHeight: ROW_MIN_HEIGHT, justifyContent: 'center', width: '100%' }}>
      <HStack spacing={6} style={{ width: '100%' }}>
        {course.madeByAI && <Icon name="sparkles" size={12} color="accent" />}
        <Txt size={15} weight="semibold" lines={1}>{course.title}</Txt>
        <Spacer />
        <Icon name="chevron.right" size={12} weight="semibold" color="tertiary" />
      </HStack>
      <Txt size={12} color="secondary">{courseDateLabel(course) ?? courseSpanLabel(course)}</Txt>
    </VStack>
  )
}

export function CourseList({ courses, busy, onOpen, onDelete }: {
  courses: readonly RouteCourse[]
  busy: boolean
  onOpen: (course: RouteCourse) => void
  onDelete: (course: RouteCourse) => void
}) {
  const [openId, setOpenId] = useState<string | null>(null)
  const running = courses.filter((c) => c.isRunning)
  const planned = courses.filter((c) => !c.isRunning)

  const rows = (list: readonly RouteCourse[]) =>
    list.map((course, i) => {
      const first = i === 0
      const last = i === list.length - 1
      const isOpen = openId === course.uid
      const radius = isOpen ? CARD_RADIUS : `${first ? CARD_RADIUS : 0}px ${first ? CARD_RADIUS : 0}px ${last ? CARD_RADIUS : 0}px ${last ? CARD_RADIUS : 0}px`
      return (
        <div key={course.uid}>
          <SwipeRow
            open={isOpen}
            onOpenChange={(o) => setOpenId(o ? course.uid : (openId === course.uid ? null : openId))}
            revealWidth={REVEAL}
            radius={radius}
            onFullSwipe={() => onDelete(course)}
            action={<SwipeDelete width={CAPSULE.w} height={CAPSULE.h} top={CAPSULE.top} onPress={() => { setOpenId(null); onDelete(course) }} />}
          >
            {/* 목록 카드에는 일차 속이 없다(CourseSummary). 열 때 상세를 받아온다 — 목록에서 전부 받아 두면 코스가 많을 때 첫 화면이 느려진다. */}
            <Pressable plain disabled={busy} onClick={() => onOpen(course)} style={{ width: '100%', textAlign: 'left' }}>
              <CourseRowBody course={course} />
            </Pressable>
          </SwipeRow>
          {/* 줄 사이 구분선 — 열린 줄 아래에서는 숨긴다. */}
          {!last && (
            <div style={{ height: 1, marginLeft: 16, background: isOpen ? 'transparent' : 'var(--separator)', transform: 'scaleY(0.3333)' }} />
          )}
        </div>
      )
    })

  return (
    <div className="scroll-y" style={{ flex: 1, minHeight: 0, paddingBottom: 40 }} onClick={() => openId && setOpenId(null)}>
      {running.length > 0 && (
        <>
          <SectionHeader>{tr('여행 중 %lld', running.length)}</SectionHeader>
          <div style={{ margin: `0 ${LIST_INSET}px` }}>{rows(running)}</div>
        </>
      )}
      <SectionHeader>{tr('예정 %lld', planned.length)}</SectionHeader>
      <div style={{ margin: `0 ${LIST_INSET}px` }}>
        {planned.length === 0 ? (
          <div style={{ background: 'var(--system-background)', borderRadius: CARD_RADIUS, padding: '14px 16px' }}>
            <Txt size={13} color="secondary">{running.length === 0 ? tr('아직 만든 코스가 없습니다') : tr('모든 코스가 여행 중이에요')}</Txt>
          </div>
        ) : rows(planned)}
      </div>
    </div>
  )
}
