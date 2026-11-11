import { describe, expect, it } from 'vitest'
import {
  DEV_SITE_HOSTS,
  isDevSite,
  pickProto,
  protoEnabled,
  showsPrototypeMarks,
} from './protoGate'

describe('DEV_SITE_HOSTS', () => {
  it('dev 사이트 주소 두 개만 담는다', () => {
    expect([...DEV_SITE_HOSTS]).toEqual(['dev.scenetrip.io', 'dev.scenetrip-app.pages.dev'])
  })
})

describe('isDevSite', () => {
  it.each([
    ['dev.scenetrip.io', true],
    ['dev.scenetrip-app.pages.dev', true],
    ['DEV.SceneTrip.IO', true],
    ['Dev.Scenetrip-App.Pages.Dev', true],
    ['scenetrip.io', false],
    ['app.scenetrip.io', false],
    ['localhost', false],
    ['abc123.scenetrip-app.pages.dev', false],
    ['evil-dev.scenetrip.io.example.com', false],
    ['', false],
  ])('%s → %s', (hostname, expected) => {
    expect(isDevSite(hostname)).toBe(expected)
  })
})

describe('protoEnabled', () => {
  // 운영도 켠다(2026-11-11 사용자 결정): 어느 주소든 언제나 참
  it.each([
    [true, 'localhost', true],
    [true, 'scenetrip.io', true],
    [false, 'dev.scenetrip.io', true],
    [false, 'dev.scenetrip-app.pages.dev', true],
    [false, 'scenetrip.io', true],
    [false, 'app.scenetrip.io', true],
    [false, 'abc123.scenetrip-app.pages.dev', true],
    [false, 'localhost', true],
    [false, '', true],
  ])('dev=%s, host=%s → %s', (dev, hostname, expected) => {
    expect(protoEnabled(dev, hostname)).toBe(expected)
  })
})

describe('pickProto', () => {
  it.each([
    // 운영 주소(시험용 웹·로컬 개발 서버가 아닌 곳): 물은 값·기억한 값과 상관없이 언제나 a
    [false, 'scenetrip.io', 'a', null, 'a'],
    [false, 'scenetrip.io', 'off', null, 'a'],
    [false, 'scenetrip.io', 'off', 'b', 'a'],
    [false, 'scenetrip.io', 'b', null, 'a'],
    [false, 'scenetrip.io', 'b', 'a', 'a'],
    [false, 'scenetrip.io', 'c', null, 'a'],
    [false, 'scenetrip.io', 'zzz', null, 'a'],
    [false, 'scenetrip.io', null, null, 'a'],
    [false, 'scenetrip.io', null, 'b', 'a'],
    [false, 'scenetrip.io', null, 'c', 'a'],
    [false, 'scenetrip.io', null, 'off', 'a'],
    [false, 'scenetrip.io', null, 'zzz', 'a'],
    [false, 'app.scenetrip.io', 'b', 'a', 'a'],
    [false, 'app.scenetrip.io', 'off', 'off', 'a'],
    [false, 'abc123.scenetrip-app.pages.dev', null, null, 'a'],
    [false, 'abc123.scenetrip-app.pages.dev', 'b', 'b', 'a'],
    [false, 'localhost', 'off', null, 'a'],
    [false, 'localhost', null, 'b', 'a'],
    // off 는 언제나 끈다
    [false, 'dev.scenetrip.io', 'off', 'a', null],
    [true, 'localhost', 'off', 'b', null],
    // 물은 값이 기억한 값을 이긴다
    [false, 'dev.scenetrip.io', 'b', 'a', 'b'],
    [true, 'localhost', 'a', 'b', 'a'],
    // 물은 값이 없으면 기억한 값
    [false, 'dev.scenetrip.io', null, 'b', 'b'],
    [true, 'localhost', null, 'a', 'a'],
    // 예전 c 는 b 로
    [true, 'localhost', 'c', null, 'b'],
    [false, 'dev.scenetrip-app.pages.dev', null, 'c', 'b'],
    // 값이 없거나 모르는 값: dev 사이트는 a, 로컬 개발 서버는 null
    [false, 'dev.scenetrip.io', null, null, 'a'],
    [false, 'dev.scenetrip-app.pages.dev', 'zzz', null, 'a'],
    [true, 'localhost', null, null, null],
    [true, 'localhost', 'zzz', null, null],
  ] as const)('dev=%s, host=%s, asked=%s, stored=%s → %s', (dev, hostname, asked, stored, expected) => {
    expect(pickProto({ dev, hostname, asked, stored })).toBe(expected)
  })
})

describe('showsPrototypeMarks', () => {
  it.each([
    [true, true],
    [false, false],
  ])('dev=%s → %s (시안 표시·전환기는 로컬 개발 서버에서만)', (dev, expected) => {
    expect(showsPrototypeMarks(dev)).toBe(expected)
  })
})
