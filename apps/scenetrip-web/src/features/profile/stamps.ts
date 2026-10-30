/*
 * 방문 스탬프 — iOS `VisitStamp` (ProfileTab/ProfileStamps.swift).
 * 여행 중 성지 반경 100 m 에 들어(또는 「여기 도착함」을 눌러) 서버에 남은 `visitedAt` 이 근거다. **지어낸 기록이 아니다.**
 * 코스마다 상세를 받아 `visitedAt` 이 찍힌 것만 모은다 — 코스 수만큼 요청이 나가지만 나란히 보낸다. 마이페이지와 홈이 같이 쓴다.
 */
import type { components } from '@/api/generated/schema'

type CourseSummary = components['schemas']['CourseSummary']
type CourseDetail = components['schemas']['CourseDetail']

export interface VisitStamp {
  id: number
  name: string
  workTitle: string | null
  courseTitle: string
  /** epoch ms */
  visitedAt: number
}

/** 첫 화면 가로 줄에 보이는 도장 수 — 넘으면 「+N」 (ProfileTabView.swift:107). */
export const STAMP_PREVIEW_COUNT = 12

/** 코스 하나의 상세에서 방문한 곳만 뽑는다. */
export function stampsOf(course: Pick<CourseSummary, 'title'>, detail: CourseDetail): VisitStamp[] {
  return detail.days
    .flatMap((day) => day.items)
    .flatMap((item) =>
      item.visitedAt
        ? [{ id: item.id, name: item.name, workTitle: item.sourceContentTitle ?? null, courseTitle: course.title, visitedAt: Date.parse(item.visitedAt) }]
        : [],
    )
}

/** 코스마다 상세를 받아(`getCourse` — 못 받은 코스는 null) 방문 도장을 모은다. 최근 것부터. */
export async function collectStamps(courses: readonly CourseSummary[], getCourse: (courseId: number) => Promise<CourseDetail | null>): Promise<VisitStamp[]> {
  const parts = await Promise.all(
    courses.map(async (course) => {
      const detail = await getCourse(course.id)
      return detail ? stampsOf(course, detail) : []
    }),
  )
  return parts.flat().sort((a, b) => b.visitedAt - a.visitedAt)
}

/** 도장은 살짝 기울여 「찍었다」는 손맛을 준다 — 아이디로 정한다(−3°~+3°). */
export const stampTilt = (id: number): number => (id % 7) - 3
