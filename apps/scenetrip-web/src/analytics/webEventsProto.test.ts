/*
 * 새 홈(시안 A)에 빠진 분석 이벤트 — 정본: docs/project/plans/web-proto-events.md §1.
 * 모두 `webEvent` 에 있고, 공통 `appEvent`(26개 + 웹 전용 start_directions = 27개)의 개수는 그대로다.
 * 매개변수 값은 문자열·정수뿐, 참/거짓은 1·0.
 */
import { describe, expect, it } from 'vitest'
import { appEvent, webEvent } from './events'

// 새 빌더는 아직 타입에 없다 — 이름으로 꺼내 부른다.
const w = webEvent as unknown as Record<string, (...args: unknown[]) => { name: string; params: Record<string, unknown> }>

describe('select_home_city — 새 홈의 출처 값 (proto_chip · proto_grid)', () => {
  it.each(['proto_chip', 'proto_grid'] as const)('source=%s', (source) => {
    expect(w.selectHomeCity('부산', source)).toEqual({ name: 'select_home_city', params: { city: '부산', source } })
  })
})

describe('home_scroll · home_tap', () => {
  it.each([25, 50, 75, 100])('homeScroll(%i) → depth 정수', (depth) => {
    expect(w.homeScroll(depth)).toEqual({ name: 'home_scroll', params: { depth } })
    expect(typeof w.homeScroll(depth).params.depth).toBe('number')
  })
  it.each(['trending_title', 'trending_all', 'today_place', 'today_save', 'community', 'my_record', 'plan_bar'])('homeTap(%s)', (section) => {
    expect(w.homeTap(section)).toEqual({ name: 'home_tap', params: { section } })
  })
})

describe('plan_start · plan_step · plan_failed', () => {
  it.each(['city_chip', 'city_map', 'fork_city', 'fork_works', 'fork_saved', 'plan_bar'])('planStart(%s)', (entry) => {
    expect(w.planStart(entry)).toEqual({ name: 'plan_start', params: { entry } })
  })
  it.each([
    ['city', 'city'], ['city', 'days'], ['city', 'how'], ['city', 'plan'], ['city', 'picker'],
    ['works', 'trip'], ['works', 'works'], ['works', 'planning'],
  ])('planStep(%s, %s)', (flow, step) => {
    expect(w.planStep(flow, step)).toEqual({ name: 'plan_step', params: { flow, step } })
  })
  it.each([
    ['city', 'empty'], ['city', 'error'], ['city', 'save'], ['works', 'error'],
  ])('planFailed(%s, %s)', (flow, reason) => {
    expect(w.planFailed(flow, reason)).toEqual({ name: 'plan_failed', params: { flow, reason } })
  })
})

describe('커뮤니티 — view_post · like_post · comment_post · save_post_course', () => {
  const POST = 'post-7f3a'
  it('viewPost → post_id', () => {
    expect(w.viewPost(POST)).toEqual({ name: 'view_post', params: { post_id: POST } })
  })
  it('likePost → post_id, liked 는 1·0', () => {
    expect(w.likePost(POST, true)).toEqual({ name: 'like_post', params: { post_id: POST, liked: 1 } })
    expect(w.likePost(POST, false)).toEqual({ name: 'like_post', params: { post_id: POST, liked: 0 } })
  })
  it('commentPost → post_id 만 — 본문을 받는 자리가 없다', () => {
    expect(w.commentPost(POST)).toEqual({ name: 'comment_post', params: { post_id: POST } })
    expect(w.commentPost).toHaveLength(1)
  })
  it('savePostCourse → post_id', () => {
    expect(w.savePostCourse(POST)).toEqual({ name: 'save_post_course', params: { post_id: POST } })
  })
})

describe('sign_in_prompt', () => {
  it.each(['like', 'comment', 'save_course', 'compose', 'my_posts', 'review', 'block', 'directions', 'nudge', 'profile', 'session', 'server', 'other'])(
    'signInPrompt(%s)',
    (reason) => {
      expect(w.signInPrompt(reason)).toEqual({ name: 'sign_in_prompt', params: { reason } })
    },
  )
})

describe('새 이벤트는 웹 전용 — 공통 appEvent 에 섞이지 않는다', () => {
  const NEW = ['homeScroll', 'homeTap', 'planStart', 'planStep', 'planFailed', 'viewPost', 'likePost', 'commentPost', 'savePostCourse', 'signInPrompt']
  it('webEvent 에 모두 있다', () => {
    for (const key of NEW) expect(typeof w[key], key).toBe('function')
  })
  it('appEvent 개수(27)는 그대로, 새 이름은 appEvent 에 없다', () => {
    expect(Object.keys(appEvent)).toHaveLength(27)
    for (const key of NEW) expect(Object.keys(appEvent)).not.toContain(key)
  })
  it('매개변수 값은 문자열·정수뿐', () => {
    const events = [
      w.homeScroll(50), w.homeTap('community'), w.planStart('plan_bar'), w.planStep('city', 'days'), w.planFailed('works', 'error'),
      w.viewPost('p'), w.likePost('p', true), w.commentPost('p'), w.savePostCourse('p'), w.signInPrompt('other'),
    ]
    for (const e of events) {
      for (const value of Object.values(e.params)) {
        expect(['string', 'number']).toContain(typeof value)
        if (typeof value === 'number') expect(Number.isInteger(value)).toBe(true)
      }
    }
  })
})

// 검증 뒤 추가 — 코치 투어는 웹 전용 coach_tour 로 센다(web-proto-events.md §1).
describe('coachTour', () => {
  it('안내 id 와 begin·complete', () => {
    expect(webEvent.coachTour('home-plan-trip', 'begin')).toEqual({ name: 'coach_tour', params: { tour: 'home-plan-trip', action: 'begin' } })
    expect(webEvent.coachTour('fork-options', 'complete')).toEqual({ name: 'coach_tour', params: { tour: 'fork-options', action: 'complete' } })
  })
})

