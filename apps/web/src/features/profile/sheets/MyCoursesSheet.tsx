/*
 * 내 코스 — iOS `MyCoursesSheet` (ProfileTab/ProfileSheets.swift:11). 행을 누르면 **바로 아래 상세가 펼쳐진다** — 아코디언.
 * 여기서 고치지는 않는다 — 코스 편집은 경로여정이 맡고, 「경로여정에서 열기」가 그리로 데려간다(`useTabRouter().openCourse`).
 *
 * 경로여정 담당이 같은 시트를 `features/route/MyCoursesSheet.tsx` 로 만들면 ProfileCover 의 import 한 줄만 바꾸면 된다(보고서 참조).
 */
import { useState } from 'react'
import type { components } from '@/api/generated/schema'
import { api, installHeader, ok } from '@/api/client'
import { useTabRouter } from '@/app/tabRouter'
import { Icon } from '@/design/Icon'
import { HStack, Pressable, Spinner, Txt, VStack } from '@/design/primitives'
import { noticeForCourseItem, unavailableMessage } from '@/features/route/availability'
import { tr } from '@/i18n'
import { abbreviatedDate } from '../dates'
import type { CourseSummary } from '../profileData'
import { ContentUnavailable } from '../ui/ContentUnavailable'
import { GlassSheet, SheetBody, SheetHeader } from '../ui/GlassSheet'
import { PLAIN_LIST_PAD, UnavailableNotice } from '../ui/parts'

type CourseDetail = components['schemas']['CourseDetail']
const GREEN = 'var(--system-green)'

/** 「3일 · 2026년 10월 1일」 — 떠나는 날이 없으면 일수만. */
export function courseSubtitle(course: Pick<CourseSummary, 'dayCount' | 'startDate'>): string {
  return tr('%d일', course.dayCount) + (course.startDate ? ` · ${abbreviatedDate(`${course.startDate}T00:00:00`)}` : '')
}

function DetailBlock({ course, detail, onOpen }: { course: CourseSummary; detail: CourseDetail | undefined; onOpen: () => void }) {
  if (!detail) {
    return (
      <HStack spacing={8} style={{ marginTop: 6 }}>
        <Spinner size={14} />
        <Txt size={11} color="secondary">{tr('일정을 받아오는 중입니다')}</Txt>
      </HStack>
    )
  }
  return (
    <VStack spacing={6} style={{ marginTop: 6, padding: 10, background: 'var(--system-gray6)', borderRadius: 10 }}>
      {detail.days.map((day) => (
        <HStack key={day.dayNumber} align="start" spacing={6}>
          <Txt size={11} weight="semibold" color="deep" style={{ width: 38, flex: 'none' }}>{tr('%d일차', day.dayNumber)}</Txt>
          <VStack spacing={4} style={{ flex: 1, minWidth: 0 }}>
            {day.items.map((item) => {
              const notice = noticeForCourseItem(item)
              return (
                <VStack key={item.id} spacing={4}>
                  <Txt size={11} style={{ opacity: notice ? 0.65 : 1 }}>{item.name}</Txt>
                  {notice && <UnavailableNotice message={unavailableMessage(notice)} />}
                </VStack>
              )
            })}
          </VStack>
        </HStack>
      ))}
      <Pressable
        plain
        onClick={onOpen}
        aria-label={`${tr('경로여정에서 열기')} — ${course.title}`}
        style={{ width: '100%', padding: '8px 0', borderRadius: 8, background: 'rgba(0,136,255,0.12)', textAlign: 'center' }}
      >
        <HStack spacing={4} justify="center">
          <Icon name="arrow.up.right" size={12} weight="semibold" color="accent" />
          <Txt size={12} weight="semibold" color="accent">{tr('경로여정에서 열기')}</Txt>
        </HStack>
      </Pressable>
    </VStack>
  )
}

export function MyCoursesSheet({ open, onClose, courses }: { open: boolean; onClose: () => void; courses: readonly CourseSummary[] }) {
  const router = useTabRouter()
  const [expanded, setExpanded] = useState<number | null>(null)
  const [details, setDetails] = useState<Record<number, CourseDetail>>({})

  const toggle = (course: CourseSummary) => {
    setExpanded(expanded === course.id ? null : course.id)
    if (details[course.id]) return
    void ok(api.GET('/courses/{courseId}', { params: { header: installHeader(), path: { courseId: course.id } } }))
      .then((d) => setDetails((s) => ({ ...s, [course.id]: d })))
      .catch(() => {})
  }

  return (
    <GlassSheet open={open} onClose={onClose} detent="medium" label={tr('내 코스')}>
      <SheetHeader title={tr('내 코스')} onClose={onClose} />
      {courses.length === 0 ? (
        <ContentUnavailable title={tr('아직 코스가 없습니다')} symbol="point.topleft.down.to.point.bottomright.curvepath" description={tr('경로여정 탭에서 첫 코스를 만들어 보세요')} />
      ) : (
        <SheetBody>
          {courses.map((course, i) => (
            <div key={course.id} style={{ margin: `0 ${PLAIN_LIST_PAD}px`, padding: '12px 0', borderBottom: i === courses.length - 1 ? undefined : '0.5px solid rgba(60,60,67,0.2)' }}>
              <Pressable plain onClick={() => toggle(course)} aria-expanded={expanded === course.id} style={{ width: '100%', textAlign: 'left' }}>
                <HStack spacing={10}>
                  <VStack spacing={2} style={{ flex: 1, minWidth: 0 }}>
                    <Txt size={15} weight="medium">{course.title}</Txt>
                    <Txt size={12} color="secondary">{courseSubtitle(course)}</Txt>
                  </VStack>
                  {course.status === 'active' && (
                    <Txt size={11} weight="semibold" style={{ padding: '3px 7px', borderRadius: 999, background: 'rgba(52,199,89,0.15)', color: GREEN }}>{tr('여행 중')}</Txt>
                  )}
                  <Icon name={expanded === course.id ? 'chevron.up' : 'chevron.down'} size={11} color="tertiary" />
                </HStack>
              </Pressable>
              {expanded === course.id && (
                <DetailBlock
                  course={course}
                  detail={details[course.id]}
                  onOpen={() => {
                    // 탭이 바뀌고 편집 화면이 열린다. 시트는 스스로 닫는다.
                    router.openCourse(course.id)
                    onClose()
                  }}
                />
              )}
            </div>
          ))}
        </SheetBody>
      )}
    </GlassSheet>
  )
}
