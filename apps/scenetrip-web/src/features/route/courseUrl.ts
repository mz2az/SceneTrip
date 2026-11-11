/*
 * 열린 코스를 주소에 싣는 방법 (MZ2AZ-551).
 *
 * 목록(/courses)에서 연 코스는 /courses/:id 를 **쌓는다** — 브라우저 뒤로가 목록으로 돌아오게.
 * 새 코스 화면(/courses/new)이나 다른 코스 주소에서 바뀐 것은 그 자리를 바꾼다 — 뒤로가 빈 새 코스 화면으로 가지 않게.
 * 이미 그 주소면(주소창으로 바로 들어온 경우) 건드리지 않는다.
 */
import { paths } from '@/app/routes'

export type CourseUrlStep = { kind: 'push'; to: string } | { kind: 'replace'; to: string } | { kind: 'stay' }

export function courseUrlStep(pathname: string, courseId: number | null | undefined): CourseUrlStep {
  if (courseId == null) return { kind: 'stay' }
  const to = paths.course(courseId)
  if (pathname === to) return { kind: 'stay' }
  if (pathname === paths.courses) return { kind: 'push', to }
  return { kind: 'replace', to }
}
