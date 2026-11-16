/*
 * 시험용 초기화 링크 `?reset=1` — 규칙(resetLink.ts)과 그 규칙을 실제로 돌리는 index.html 의 인라인 스크립트를 같은 사례표로 본다.
 *   - 시험용 웹·로컬에서만 지운다, 운영은 지우지 않는다(주소에서 떼기만).
 *   - 다른 쿼리·해시는 남는다.
 *   - 지우는 키: scenetrip.*(설치 id·첫 화면 기록 포함) · proto.* · ph_*(PostHog 익명 id). 다른 키는 남는다.
 */
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'
import { DEV_SITE_HOSTS } from '@/features/proto/protoGate'
import { INTRO_DONE_KEY } from './intro'
import { RESET_KEY_PREFIXES, keysToClear, searchWithoutReset, shouldReset } from './resetLink'

const PROD_HOSTS = ['scenetrip.io', 'app.scenetrip.io', 'www.scenetrip.io', 'scenetrip-app.pages.dev', 'evil-localhost.com']
const LOCAL = ['localhost', '127.0.0.1']

const APP_KEYS = [
  INTRO_DONE_KEY,
  'scenetrip.installId',
  'scenetrip.auth.session',
  'scenetrip.analytics.anonId',
  'scenetrip.keep.introSeen',
  'scenetrip.utm',
  'scenetrip.nickname.asked.42',
  'proto.coach.seen',
  'proto.sceneFlip.seen',
  'ph_phc_aPLbSLcEQ_posthog',
]
const OTHER_KEYS = ['naver.maps.cache', 'something', 'xscenetrip.a', 'debug']

describe('shouldReset', () => {
  it('시험용 웹·로컬에서 reset=1 이면 지운다', () => {
    for (const h of [...DEV_SITE_HOSTS, ...LOCAL, 'DEV.SceneTrip.io']) expect(shouldReset(h, '?reset=1', false)).toBe(true)
  })
  it('개발 서버면 주소와 관계없이 지운다', () => {
    expect(shouldReset('192.168.0.10', '?a=1&reset=1', true)).toBe(true)
  })
  it('운영 주소에서는 무시한다', () => {
    for (const h of PROD_HOSTS) expect(shouldReset(h, '?reset=1', false)).toBe(false)
  })
  it('reset=1 이 아니면 하지 않는다', () => {
    for (const s of ['', '?reset=0', '?reset=', '?resetx=1', '?x=reset=1']) expect(shouldReset('dev.scenetrip.io', s, true)).toBe(false)
  })
})

describe('keysToClear', () => {
  it('이 앱의 키만 고른다 — 설치 id·첫 화면 기록·PostHog 포함', () => {
    expect(keysToClear([...APP_KEYS, ...OTHER_KEYS])).toEqual(APP_KEYS)
    expect(keysToClear(APP_KEYS)).toContain('scenetrip.installId')
  })
  it('접두는 셋', () => {
    expect([...RESET_KEY_PREFIXES]).toEqual(['scenetrip.', 'proto.', 'ph_'])
  })
})

describe('searchWithoutReset', () => {
  it('reset 만 떼고 나머지 쿼리는 남긴다', () => {
    expect(searchWithoutReset('?reset=1&utm_source=x')).toBe('?utm_source=x')
    expect(searchWithoutReset('?a=1&reset=1&b=2')).toBe('?a=1&b=2')
    expect(searchWithoutReset('?reset=1')).toBe('')
  })
  it('reset=1 이 없으면 주소를 고치지 않는다', () => {
    expect(searchWithoutReset('?utm_source=x')).toBeNull()
    expect(searchWithoutReset('?reset=0')).toBeNull()
  })
})

// --- index.html 의 인라인 스크립트 -------------------------------------------------------------

const html = readFileSync('index.html', 'utf8')
const inline = /<script data-scenetrip-reset>([\s\S]*?)<\/script>/.exec(html)?.[1] ?? ''

class MemStorage {
  private m = new Map<string, string>()
  get length() {
    return this.m.size
  }
  key(i: number) {
    return [...this.m.keys()][i] ?? null
  }
  getItem(k: string) {
    return this.m.get(k) ?? null
  }
  setItem(k: string, v: string) {
    this.m.set(k, v)
  }
  removeItem(k: string) {
    this.m.delete(k)
  }
  keys() {
    return [...this.m.keys()]
  }
}

function runInline(hostname: string, pathname: string, search: string, hash = '') {
  const local = new MemStorage()
  const session = new MemStorage()
  for (const k of [...APP_KEYS, ...OTHER_KEYS]) {
    local.setItem(k, '1')
    session.setItem(k, '1')
  }
  let replacedTo: string | null = null
  const history = { state: null, replaceState: (_s: unknown, _t: string, url: string) => (replacedTo = url) }
  const location = { hostname, pathname, search, hash }
  new Function('location', 'history', 'localStorage', 'sessionStorage', inline)(location, history, local, session)
  return { local: local.keys(), session: session.keys(), replacedTo }
}

describe('index.html 의 초기화 인라인 스크립트', () => {
  it('첫 화면을 정하는 스크립트보다 먼저 있다', () => {
    expect(inline).not.toBe('')
    expect(html.indexOf('data-scenetrip-reset')).toBeLessThan(html.indexOf("localStorage.getItem('scenetrip.intro.done')"))
    expect(html.indexOf('data-scenetrip-reset')).toBeLessThan(html.indexOf('/src/main.tsx'))
  })

  const cases: Array<[string, string]> = [
    ...[...DEV_SITE_HOSTS, ...LOCAL, ...PROD_HOSTS].map((h): [string, string] => [h, '?reset=1&utm_source=x']),
    ['dev.scenetrip.io', '?utm_source=x'],
    ['dev.scenetrip.io', '?reset=0'],
    ['dev.scenetrip.io', '?a=1&reset=1&b=2'],
    ['dev.scenetrip.io', '?reset=1'],
    ['intro-lab.scenetrip-app.pages.dev', '?reset=1'],
    ['other-lab.scenetrip-app.pages.dev', '?reset=1'],
  ]
  for (const [host, search] of cases) {
    it(`${host}${search} — resetLink.ts 와 같게`, () => {
      const r = runInline(host, '/', search, '#h')
      const cleared = shouldReset(host, search, false) ? keysToClear([...APP_KEYS, ...OTHER_KEYS]) : []
      const expected = [...APP_KEYS, ...OTHER_KEYS].filter((k) => !cleared.includes(k))
      expect(r.local).toEqual(expected)
      expect(r.session).toEqual(expected)
      const rest = searchWithoutReset(search)
      expect(r.replacedTo).toBe(rest === null ? null : `/${rest}#h`)
    })
  }

  it('dev 에서 연 /?reset=1&utm_source=x 는 키가 비고 주소가 /?utm_source=x 가 된다', () => {
    const r = runInline('dev.scenetrip.io', '/', '?reset=1&utm_source=x')
    expect(r.local).toEqual(OTHER_KEYS)
    expect(r.session).toEqual(OTHER_KEYS)
    expect(r.replacedTo).toBe('/?utm_source=x')
  })
})
