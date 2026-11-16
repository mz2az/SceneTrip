/*
 * 방문 맥락 — 정본: docs/project/plans/analytics-events.md 「방문 맥락과 이벤트 정리 (2026-11-16, MZ2AZ-585)」.
 *
 *   launchModeOf({ standalone, userAgent }) 홈 화면 앱이면 standalone, 앱 안 브라우저(isInAppBrowser)면 in_app, 그 밖은 browser
 *   startVisit(input)                       launch_mode, visit_kind(first·return), first_entry(intro·app), first_utm_* 를 props 로.
 *                                           이 브라우저에 전에 온 적이 있고 새 탭 세션이면 returnVisit { daysSinceFirst, via } 한 번.
 *   localStorage   scenetrip.analytics.firstSeen  처음 온 때(밀리초 문자열)
 *                  scenetrip.analytics.firstUtm   처음 꼬리표가 있던 방문의 utm_*(덮어쓰지 않는다)
 *   sessionStorage scenetrip.analytics.visit      이 탭 세션에서 정한 visit_kind, first_entry
 *   저장소가 없거나 던져도 던지지 않는다 — 처음 온 사람으로 본다.
 */
import { describe, expect, it } from 'vitest'
import {
  FIRST_SEEN_KEY,
  FIRST_UTM_KEY,
  VISIT_SESSION_KEY,
  type VisitInput,
  type VisitStore,
  launchModeOf,
  startVisit,
} from './visit'

const DAY = 86_400_000
const T0 = Date.UTC(2026, 10, 1, 9, 0, 0)

class MemStore implements VisitStore {
  data = new Map<string, string>()
  getItem(key: string): string | null {
    return this.data.has(key) ? this.data.get(key)! : null
  }
  setItem(key: string, value: string): void {
    this.data.set(key, value)
  }
}

const throwing: VisitStore = {
  getItem: () => {
    throw new Error('SecurityError')
  },
  setItem: () => {
    throw new Error('QuotaExceededError')
  },
}

function input(over: Partial<VisitInput> = {}): VisitInput {
  return {
    now: T0,
    search: '',
    referrer: '',
    hostname: 'app.scenetrip.io',
    intro: false,
    launchMode: 'browser',
    local: new MemStore(),
    session: new MemStore(),
    ...over,
  }
}

const UA = {
  chromeDesktop: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36',
  iphoneSafari:
    'Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.1 Mobile/15E148 Safari/604.1',
  fbAndroid:
    'Mozilla/5.0 (Linux; Android 14; SM-S918N Build/UP1A; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/130.0 Mobile Safari/537.36 [FB_IAB/FB4A;FBAV/480.0.0.0;]',
  fbIphone:
    'Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148 [FBAN/FBIOS;FBAV/480.0.0.0;]',
  igIphone:
    'Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148 Instagram 350.0.0.0 (iPhone15,3; iOS 18_1; ko_KR)',
}

/** 처음 온 브라우저 — 이번 세션을 끝까지 쓴 뒤의 local 을 돌려준다. */
function firstVisitAt(now: number, over: Partial<VisitInput> = {}): MemStore {
  const local = new MemStore()
  startVisit(input({ now, local, session: new MemStore(), ...over }))
  return local
}

// ---------------------------------------------------------------- launch_mode

describe('launchModeOf', () => {
  it('홈 화면 앱이면 standalone — 앱 안 브라우저 표식이 있어도 standalone 이 이긴다', () => {
    expect(launchModeOf({ standalone: true, userAgent: UA.iphoneSafari })).toBe('standalone')
    expect(launchModeOf({ standalone: true, userAgent: UA.fbAndroid })).toBe('standalone')
  })
  it.each([
    ['페이스북 안드로이드', UA.fbAndroid],
    ['페이스북 아이폰', UA.fbIphone],
    ['인스타 아이폰', UA.igIphone],
  ])('%s 앱 안 브라우저 → in_app', (_name, ua) => {
    expect(launchModeOf({ standalone: false, userAgent: ua })).toBe('in_app')
  })
  it.each([
    ['크롬 데스크톱', UA.chromeDesktop],
    ['아이폰 사파리', UA.iphoneSafari],
    ['UA 없음', ''],
  ])('%s → browser', (_name, ua) => {
    expect(launchModeOf({ standalone: false, userAgent: ua })).toBe('browser')
  })
})

// ---------------------------------------------------------------- 처음 온 때

describe('처음 온 브라우저', () => {
  it('visit_kind=first, firstSeen 에 지금을 적고, returnVisit 은 없다', () => {
    const local = new MemStore()
    const v = startVisit(input({ local }))
    expect(v.props.visit_kind).toBe('first')
    expect(v.returnVisit).toBeNull()
    expect(local.getItem(FIRST_SEEN_KEY)).toBe(String(T0))
  })

  it('props 에 launch_mode, visit_kind, first_entry 가 실린다', () => {
    const v = startVisit(input({ intro: true, launchMode: 'in_app' }))
    expect(v.props).toEqual({ launch_mode: 'in_app', visit_kind: 'first', first_entry: 'intro' })
  })

  it('first_entry 는 이 세션이 덮개로 시작했으면 intro, 아니면 app', () => {
    expect(startVisit(input({ intro: true })).props.first_entry).toBe('intro')
    expect(startVisit(input({ intro: false })).props.first_entry).toBe('app')
  })

  it('세션 저장소에 이번 세션의 판정을 적는다', () => {
    const session = new MemStore()
    startVisit(input({ session }))
    expect(session.getItem(VISIT_SESSION_KEY)).not.toBeNull()
  })
})

// ---------------------------------------------------------------- 다시 온 때

describe('다시 온 브라우저(새 탭 세션)', () => {
  it('visit_kind=return, returnVisit 의 days_since_first 는 내림 정수', () => {
    const local = firstVisitAt(T0)
    const v = startVisit(input({ now: T0 + 3 * DAY + DAY - 1, local, session: new MemStore() }))
    expect(v.props.visit_kind).toBe('return')
    expect(v.returnVisit).toEqual({ daysSinceFirst: 3, via: 'direct' })
  })

  it('같은 날 다시 오면 0일', () => {
    const local = firstVisitAt(T0)
    const v = startVisit(input({ now: T0 + 60_000, local, session: new MemStore() }))
    expect(v.returnVisit?.daysSinceFirst).toBe(0)
  })

  it('firstSeen 을 덮어쓰지 않는다', () => {
    const local = firstVisitAt(T0)
    startVisit(input({ now: T0 + 5 * DAY, local, session: new MemStore() }))
    expect(local.getItem(FIRST_SEEN_KEY)).toBe(String(T0))
  })

  it('미리 적힌 firstSeen 만 있어도 return', () => {
    const local = new MemStore()
    local.setItem(FIRST_SEEN_KEY, String(T0 - 10 * DAY))
    const v = startVisit(input({ local }))
    expect(v.props.visit_kind).toBe('return')
    expect(v.returnVisit).toEqual({ daysSinceFirst: 10, via: 'direct' })
  })

  describe('via', () => {
    const again = (over: Partial<VisitInput>) => startVisit(input({ now: T0 + 2 * DAY, local: firstVisitAt(T0), session: new MemStore(), ...over })).returnVisit?.via

    it('주소에 utm_* 가 있으면 utm — 다른 사이트에서 왔어도 utm 이 이긴다', () => {
      expect(again({ search: '?utm_source=meta&utm_campaign=kdh' })).toBe('utm')
      expect(again({ search: '?UTM_SOURCE=meta', referrer: 'https://l.instagram.com/' })).toBe('utm')
    })
    it('빈 utm 값은 꼬리표로 치지 않는다', () => {
      expect(again({ search: '?utm_source=' })).toBe('direct')
    })
    it('utm_ 이 아닌 매개변수는 꼬리표가 아니다', () => {
      expect(again({ search: '?show=32&fbclid=abc' })).toBe('direct')
    })
    it('다른 사이트에서 오면 referrer', () => {
      expect(again({ referrer: 'https://l.instagram.com/?u=x' })).toBe('referrer')
      expect(again({ referrer: 'https://www.google.com/' })).toBe('referrer')
    })
    it('같은 사이트 안에서 온 것은 direct', () => {
      expect(again({ referrer: 'https://app.scenetrip.io/my-map' })).toBe('direct')
    })
    it('둘 다 없으면 direct(즐겨찾기, 주소 입력, 홈 화면 앱)', () => {
      expect(again({})).toBe('direct')
      expect(again({ launchMode: 'standalone' })).toBe('direct')
    })
  })
})

// ---------------------------------------------------------------- 같은 탭 세션

describe('같은 탭 세션 안에서 다시 계산할 때(새로 고침, 주소 바뀜)', () => {
  it('first_entry 는 세션 첫 판정을 쓴다 — 덮개를 걷은 뒤 intro=false 여도 intro', () => {
    const local = new MemStore()
    const session = new MemStore()
    startVisit(input({ intro: true, local, session }))
    const v = startVisit(input({ now: T0 + 60_000, intro: false, local, session }))
    expect(v.props.first_entry).toBe('intro')
  })

  it('처음 온 세션은 끝까지 first — 이미 firstSeen 이 적혔어도 return 으로 바뀌지 않는다', () => {
    const local = new MemStore()
    const session = new MemStore()
    startVisit(input({ local, session }))
    const v = startVisit(input({ now: T0 + 2 * DAY, local, session }))
    expect(v.props.visit_kind).toBe('first')
    expect(v.returnVisit).toBeNull()
  })

  it('재방문은 세션에 한 번 — 같은 세션에서 다시 부르면 returnVisit 은 없고 visit_kind 는 return 그대로', () => {
    const local = firstVisitAt(T0)
    const session = new MemStore()
    const first = startVisit(input({ now: T0 + 4 * DAY, local, session, referrer: 'https://www.google.com/' }))
    expect(first.returnVisit).toEqual({ daysSinceFirst: 4, via: 'referrer' })
    const second = startVisit(input({ now: T0 + 4 * DAY + 1000, local, session, search: '?utm_source=meta' }))
    expect(second.returnVisit).toBeNull()
    expect(second.props.visit_kind).toBe('return')
  })

  it('launch_mode 는 적어 둔 것이 아니라 지금 값', () => {
    const local = new MemStore()
    const session = new MemStore()
    startVisit(input({ launchMode: 'in_app', local, session }))
    const v = startVisit(input({ launchMode: 'browser', local, session }))
    expect(v.props.launch_mode).toBe('browser')
  })
})

// ---------------------------------------------------------------- 처음 꼬리표

describe('first_utm_*', () => {
  it('꼬리표가 한 번도 없으면 first_utm_* 가 없다', () => {
    const v = startVisit(input({ search: '?show=32' }))
    expect(Object.keys(v.props).filter((k) => k.startsWith('first_utm_'))).toEqual([])
  })

  it('처음 꼬리표가 있으면 적고 first_ 를 붙여 싣는다', () => {
    const local = new MemStore()
    const v = startVisit(input({ local, search: '?utm_source=meta&utm_campaign=kdh&utm_content=a&show=32' }))
    expect(v.props).toMatchObject({ first_utm_source: 'meta', first_utm_campaign: 'kdh', first_utm_content: 'a' })
    expect(v.props).not.toHaveProperty('first_show')
    expect(JSON.parse(local.getItem(FIRST_UTM_KEY)!)).toEqual({ utm_source: 'meta', utm_campaign: 'kdh', utm_content: 'a' })
  })

  it('이번 방문의 utm_* 자체는 props 에 싣지 않는다(세션 꼬리표는 utm.ts 가 따로 싣는다)', () => {
    const v = startVisit(input({ search: '?utm_source=meta' }))
    expect(v.props).not.toHaveProperty('utm_source')
  })

  it('utmFrom 과 같은 규칙 — 키는 소문자로, 빈 값은 뺀다', () => {
    const v = startVisit(input({ search: '?UTM_Source=meta&utm_medium=' }))
    expect(v.props.first_utm_source).toBe('meta')
    expect(v.props).not.toHaveProperty('first_utm_medium')
  })

  it('이미 적혀 있으면 덮어쓰지 않고 옛것을 싣는다', () => {
    const local = firstVisitAt(T0, { search: '?utm_source=meta&utm_campaign=kdh' })
    const v = startVisit(input({ now: T0 + DAY, local, session: new MemStore(), search: '?utm_source=google&utm_campaign=brand' }))
    expect(v.props.first_utm_source).toBe('meta')
    expect(v.props.first_utm_campaign).toBe('kdh')
    expect(JSON.parse(local.getItem(FIRST_UTM_KEY)!)).toEqual({ utm_source: 'meta', utm_campaign: 'kdh' })
  })

  it('첫 방문엔 꼬리표가 없고 나중에 꼬리표를 달고 오면 그때 것을 처음 꼬리표로 적는다', () => {
    const local = firstVisitAt(T0)
    const v = startVisit(input({ now: T0 + DAY, local, session: new MemStore(), search: '?utm_source=meta' }))
    expect(v.props.first_utm_source).toBe('meta')
    expect(v.props.visit_kind).toBe('return')
  })

  it('다시 와서 꼬리표 없이 들어와도 적어 둔 처음 꼬리표를 싣는다', () => {
    const local = firstVisitAt(T0, { search: '?utm_campaign=kdh' })
    const v = startVisit(input({ now: T0 + DAY, local, session: new MemStore() }))
    expect(v.props.first_utm_campaign).toBe('kdh')
  })
})

// ---------------------------------------------------------------- 저장소 문제

describe('저장소를 쓰지 못할 때', () => {
  it('local, session 이 없으면(null) 던지지 않고 처음 온 사람으로 본다', () => {
    const v = startVisit(input({ local: null, session: null, intro: true }))
    expect(v.props).toMatchObject({ launch_mode: 'browser', visit_kind: 'first', first_entry: 'intro' })
    expect(v.returnVisit).toBeNull()
  })

  it('읽기, 쓰기가 던져도 던지지 않고 처음 온 사람으로 본다', () => {
    let v: ReturnType<typeof startVisit> | undefined
    expect(() => {
      v = startVisit(input({ local: throwing, session: throwing, search: '?utm_source=meta' }))
    }).not.toThrow()
    expect(v!.props.visit_kind).toBe('first')
    expect(v!.returnVisit).toBeNull()
  })

  it('세션 저장소만 던져도 던지지 않는다', () => {
    const local = firstVisitAt(T0)
    expect(() => startVisit(input({ now: T0 + DAY, local, session: throwing }))).not.toThrow()
  })

  it('적힌 값이 깨져 있어도 던지지 않는다', () => {
    const local = new MemStore()
    local.setItem(FIRST_SEEN_KEY, 'not-a-number')
    local.setItem(FIRST_UTM_KEY, '{bad json')
    const session = new MemStore()
    session.setItem(VISIT_SESSION_KEY, '{bad json')
    expect(() => startVisit(input({ local, session }))).not.toThrow()
  })
})

// ---------------------------------------------------------------- launch_mode — iOS 웹뷰 껍데기(MZ2AZ-591)

describe('launchModeOf — iOS 웹뷰 껍데기', () => {
  const SHELL_IPHONE =
    'Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148 SceneTripShell/1'

  it('껍데기 UA → ios_app', () => {
    expect(launchModeOf({ standalone: false, userAgent: SHELL_IPHONE })).toBe('ios_app')
  })

  it('standalone 보다 먼저 ios_app', () => {
    expect(launchModeOf({ standalone: true, userAgent: SHELL_IPHONE })).toBe('ios_app')
  })

  it('앱 안 브라우저 표식이 섞여 있어도 in_app 보다 먼저 ios_app', () => {
    expect(launchModeOf({ standalone: false, userAgent: `${UA.igIphone} SceneTripShell/1` })).toBe('ios_app')
  })

  it('껍데기가 아니면 지금 그대로', () => {
    expect(launchModeOf({ standalone: false, userAgent: UA.iphoneSafari })).toBe('browser')
    expect(launchModeOf({ standalone: true, userAgent: UA.iphoneSafari })).toBe('standalone')
    expect(launchModeOf({ standalone: false, userAgent: UA.fbIphone })).toBe('in_app')
  })
})
