import { describe, expect, it } from 'vitest'
import { courseUrlStep } from './courseUrl'

/*
 * MZ2AZ-551 — 코스 목록(/courses)에서 코스를 열면 편집 화면 주소를 **쌓는다**(push). 그래야 브라우저 뒤로가 목록으로 돌아온다.
 * 전에는 바꾸기(replace)라 뒤로를 누르면 경로여정 덮개 밖(홈)으로 나가 버렸다(RouteCover 의 「열린 코스를 주소에 싣는다」).
 */
describe('courseUrlStep — 열린 코스를 주소에 싣는 방법', () => {
  it('목록에서 연 코스는 /courses/:id 를 쌓는다', () => {
    expect(courseUrlStep('/courses', 7)).toEqual({ kind: 'push', to: '/courses/7' })
  })

  it('이미 그 코스 주소면 건드리지 않는다(주소창으로 바로 들어온 경우)', () => {
    expect(courseUrlStep('/courses/7', 7)).toEqual({ kind: 'stay' })
  })

  it('새 코스 화면(/courses/new)에서 만든 코스는 그 자리를 바꾼다 — 뒤로가 빈 새 코스 화면으로 가지 않게', () => {
    expect(courseUrlStep('/courses/new', 9)).toEqual({ kind: 'replace', to: '/courses/9' })
  })

  it('열린 코스가 없거나 서버 id 가 없으면 아무것도 하지 않는다', () => {
    expect(courseUrlStep('/courses', null)).toEqual({ kind: 'stay' })
    expect(courseUrlStep('/courses', undefined)).toEqual({ kind: 'stay' })
  })
})
