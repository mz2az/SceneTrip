/*
 * 홈 화면 추가 이벤트 — 정본: docs/project/plans/web-home-install.md §4 (MZ2AZ-589).
 *
 *   add_to_home    webEvent.addToHome(env, action, where, trigger?)   trigger 는 띠(nudge)일 때만, 없으면 빠진다
 *   app_installed  webEvent.appInstalled()                             매개변수 없음
 */
import { describe, expect, it } from 'vitest'
import { webEvent } from './events'

describe('add_to_home', () => {
  it.each(['in_app_android', 'in_app_ios', 'samsung_android', 'installable', 'android_menu', 'ios_safari', 'installed', 'other'] as const)(
    'env=%s, 머리줄 단추(trigger 없음)',
    (env) => {
      expect(webEvent.addToHome(env, 'tap', 'header')).toEqual({ name: 'add_to_home', params: { env, action: 'tap', where: 'header' } })
    },
  )

  it.each(['view', 'tap', 'install_accepted', 'install_dismissed', 'open_browser', 'guide', 'got_it', 'dismiss'] as const)('action=%s', (action) => {
    expect(webEvent.addToHome('installable', action, 'nudge', 'course_created')).toEqual({
      name: 'add_to_home',
      params: { env: 'installable', action, where: 'nudge', trigger: 'course_created' },
    })
  })

  it.each(['course_created', 'return_visit'] as const)('띠의 trigger=%s', (trigger) => {
    expect(webEvent.addToHome('other', 'view', 'nudge', trigger).params).toEqual({ env: 'other', action: 'view', where: 'nudge', trigger })
  })

  it('trigger 가 undefined 면 키 자체가 없다', () => {
    const { params } = webEvent.addToHome('other', 'tap', 'header', undefined)
    expect('trigger' in params).toBe(false)
  })
})

describe('app_installed', () => {
  it('appInstalled() → 매개변수 없음', () => {
    expect(webEvent.appInstalled()).toEqual({ name: 'app_installed', params: {} })
  })
})
