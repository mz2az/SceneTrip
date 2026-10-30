/*
 * 목록 요약은 편집하지 않는다. 상세 조회 성공만 편집 화면의 표시 상태로 전달한다 — iOS RouteCourseOpening.
 */
import { useCallback, useRef, useState } from 'react'
import { type RouteCourse } from './models'
import { routeActions } from './store'

export interface CourseOpening {
  editing: RouteCourse | null
  failedCourse: RouteCourse | null
  isLoading: boolean
  open(course: RouteCourse): Promise<void>
  retry(): Promise<void>
  /** 편집 화면을 닫았다. */
  close(): void
}

export function useCourseOpening(): CourseOpening {
  const [editing, setEditing] = useState<RouteCourse | null>(null)
  const [failedCourse, setFailed] = useState<RouteCourse | null>(null)
  const [isLoading, setLoading] = useState(false)
  const busy = useRef(false)

  const open = useCallback(async (course: RouteCourse) => {
    if (busy.current) return
    busy.current = true
    setLoading(true)
    setEditing(null)
    setFailed(null)
    try {
      const detail = await routeActions.detail(course)
      if (detail === null) setFailed(course)
      else setEditing(detail)
    } finally {
      busy.current = false
      setLoading(false)
    }
  }, [])

  const retry = useCallback(async () => {
    if (failedCourse) await open(failedCourse)
  }, [failedCourse, open])

  return { editing, failedCourse, isLoading, open, retry, close: () => setEditing(null) }
}
