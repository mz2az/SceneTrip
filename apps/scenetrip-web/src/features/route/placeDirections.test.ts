// 촬영지 상세 「길찾기」(MZ2AZ-532) — 한 곳짜리 코스를 만들어 곧바로 여행 모드를 연다. 비회원이면 로그인 뒤 이어서.
// 서버·로그인 창은 전부 가짜다(DEV 에 쓰지 않는다).
import { beforeEach, describe, expect, it } from 'vitest'
import type { AppEvent } from '@/analytics'
import { appEvent } from '@/analytics'
import { chooseLanguage } from '@/i18n'
import type { PlaceSummary, RouteCourse } from './models'
import { courseStops } from './models'
import { type PlaceDirectionsDeps, createPlaceDirections, directionsCourse } from './placeDirections'

const place: PlaceSummary = { id: 42, name: '도깨비 방파제', address: '강릉시 주문진읍', latitude: 37.89, longitude: 128.83 }

/** 늦게 끝나는 약속 — 「진행 중에 또 누른다」를 재기 위해. */
function deferred<T>() {
  let resolve!: (v: T) => void
  const promise = new Promise<T>((r) => (resolve = r))
  return { promise, resolve }
}

interface Fake {
  deps: PlaceDirectionsDeps
  created: RouteCourse[]
  started: RouteCourse[]
  opened: number[]
  events: AppEvent[]
  signInAsked: number
  /** 가짜 서버의 코스(id → 코스). 시작하면 isRunning 이 켜진다. */
  server: Map<number, RouteCourse>
}

function fake(over: Partial<PlaceDirectionsDeps> & { signedIn?: boolean } = {}): Fake {
  const f: Fake = { deps: null as unknown as PlaceDirectionsDeps, created: [], started: [], opened: [], events: [], signInAsked: 0, server: new Map() }
  let signedIn = over.signedIn ?? true
  f.deps = {
    isSignedIn: () => signedIn,
    askSignIn: async () => {
      f.signInAsked += 1
      return false
    },
    createCourse: async (course) => {
      f.created.push(course)
      const saved = { ...course, serverId: 900 + f.created.length, detailLoaded: true }
      f.server.set(saved.serverId, saved)
      return { ok: true, course: saved }
    },
    startCourse: async (course) => {
      f.started.push(course)
      const id = course.serverId!
      const now = f.server.get(id)
      if (now) f.server.set(id, { ...now, isRunning: true })
      return { ok: true }
    },
    made: new Map(),
    existingCandidates: () => [],
    loadCourse: async (id) => f.server.get(id) ?? null,
    openTrip: (id) => f.opened.push(id),
    track: (e) => f.events.push(e),
    ...over,
  }
  // 로그인 성공을 흉내 내는 askSignIn 은 상태도 바꾼다.
  if (over.askSignIn) {
    const inner = over.askSignIn
    f.deps.askSignIn = async () => {
      f.signInAsked += 1
      const ok = await inner()
      if (ok) signedIn = true
      return ok
    }
  }
  return f
}

beforeEach(() => chooseLanguage('ko'))

describe('directionsCourse — 한 곳짜리 코스', () => {
  it('1일 1곳, 그 장소 id, 「{장소} 가는 길」 제목, 직접 짠 코스', () => {
    const course = directionsCourse(place)
    expect(course.serverId).toBeNull()
    expect(course.days).toHaveLength(1)
    const stops = courseStops(course)
    expect(stops).toHaveLength(1)
    expect(stops[0].place.id).toBe(42)
    expect(stops[0].kind).toEqual({ type: 'place' })
    expect(course.title).toBe('도깨비 방파제 가는 길')
    expect(course.madeByAI).toBe(false)
  })
  it('영어면 "Trip to {place}"', () => {
    chooseLanguage('en')
    expect(directionsCourse(place).title).toBe('Trip to 도깨비 방파제')
    chooseLanguage('ko')
  })
})

describe('createPlaceDirections — 로그인한 사람', () => {
  it('코스를 하나 만들고, 시작하고, 그 코스로 여행 모드를 연다', async () => {
    const f = fake()
    const outcome = await createPlaceDirections(f.deps).start(place)
    expect(f.created).toHaveLength(1)
    expect(courseStops(f.created[0]).map((s) => s.place.id)).toEqual([42])
    expect(f.created[0].title).toBe('도깨비 방파제 가는 길')
    expect(f.started.map((c) => c.serverId)).toEqual([901])
    expect(f.opened).toEqual([901])
    expect(outcome).toEqual({ kind: 'started', courseId: 901 })
    expect(f.signInAsked).toBe(0)
  })

  it('두 번 눌러도 만들기는 한 번이다', async () => {
    const gate = deferred<void>()
    const f = fake()
    const create = f.deps.createCourse
    f.deps.createCourse = async (c) => {
      await gate.promise
      return create(c)
    }
    const d = createPlaceDirections(f.deps)
    const first = d.start(place)
    const second = await d.start(place)
    expect(second).toEqual({ kind: 'busy' })
    gate.resolve()
    await first
    expect(f.created).toHaveLength(1)
    expect(f.opened).toEqual([901])
  })

  it('만들기가 실패하면 그 문구를 돌려주고 시작·열기를 하지 않는다', async () => {
    const f = fake({ createCourse: async () => ({ ok: false, message: '연결이 원활하지 않아요' }) })
    const outcome = await createPlaceDirections(f.deps).start(place)
    expect(outcome).toEqual({ kind: 'failed', message: '연결이 원활하지 않아요' })
    expect(f.started).toHaveLength(0)
    expect(f.opened).toHaveLength(0)
  })

  it('시작이 실패하면 열지 않고, 다시 누르면 새로 만들지 않고 시작만 다시 한다', async () => {
    let fails = 1
    const f = fake()
    const startOk = f.deps.startCourse
    f.deps.startCourse = async (course) => {
      if (fails > 0) {
        f.started.push(course)
        fails -= 1
        return { ok: false, message: '시작하지 못했어요' }
      }
      return startOk(course)
    }
    const d = createPlaceDirections(f.deps)
    expect(await d.start(place)).toEqual({ kind: 'failed', message: '시작하지 못했어요' })
    expect(f.opened).toHaveLength(0)
    expect(await d.start(place)).toEqual({ kind: 'started', courseId: 901 })
    expect(f.created).toHaveLength(1)
    expect(f.started.map((c) => c.serverId)).toEqual([901, 901])
    expect(f.opened).toEqual([901])
  })
})

describe('createPlaceDirections — 비회원', () => {
  it('로그인 시트를 띄우고, 닫으면 아무것도 만들지 않는다', async () => {
    const f = fake({ signedIn: false })
    const outcome = await createPlaceDirections(f.deps).start(place)
    expect(f.signInAsked).toBe(1)
    expect(outcome).toEqual({ kind: 'cancelled' })
    expect(f.created).toHaveLength(0)
    expect(f.started).toHaveLength(0)
    expect(f.opened).toHaveLength(0)
  })

  it('로그인되면 같은 장소로 이어서 만들고 시작하고 연다', async () => {
    const f = fake({ signedIn: false, askSignIn: async () => true })
    const outcome = await createPlaceDirections(f.deps).start(place)
    expect(f.signInAsked).toBe(1)
    expect(f.created).toHaveLength(1)
    expect(courseStops(f.created[0])[0].place.id).toBe(42)
    expect(f.opened).toEqual([901])
    expect(outcome).toEqual({ kind: 'started', courseId: 901 })
  })

  it('로그인 시트가 떠 있는 동안 또 눌러도 시트를 또 띄우지 않는다', async () => {
    const gate = deferred<boolean>()
    const f = fake({ signedIn: false, askSignIn: () => gate.promise })
    const d = createPlaceDirections(f.deps)
    const first = d.start(place)
    expect(await d.start(place)).toEqual({ kind: 'busy' })
    gate.resolve(true)
    await first
    expect(f.signInAsked).toBe(1)
    expect(f.created).toHaveLength(1)
  })

  it('로그인을 기다리는 사이 화면을 떠났으면(dispose) 로그인돼도 만들지 않는다', async () => {
    const gate = deferred<boolean>()
    const f = fake({ signedIn: false, askSignIn: () => gate.promise })
    const d = createPlaceDirections(f.deps)
    const pending = d.start(place)
    d.dispose()
    gate.resolve(true)
    expect(await pending).toEqual({ kind: 'cancelled' })
    expect(f.created).toHaveLength(0)
    expect(f.opened).toHaveLength(0)
  })
})

describe('start_directions 이벤트', () => {
  it('로그인한 사람: source=place_detail, signed_in=1 — 누를 때 한 번', async () => {
    const f = fake()
    await createPlaceDirections(f.deps).start(place)
    const mine = f.events.filter((e) => e.name === 'start_directions')
    expect(mine).toEqual([{ name: 'start_directions', params: { source: 'place_detail', signed_in: 1 } }])
  })
  it('비회원: signed_in=0, 로그인 뒤 이어 갈 때 또 보내지 않는다', async () => {
    const f = fake({ signedIn: false, askSignIn: async () => true })
    await createPlaceDirections(f.deps).start(place)
    const mine = f.events.filter((e) => e.name === 'start_directions')
    expect(mine).toEqual([{ name: 'start_directions', params: { source: 'place_detail', signed_in: 0 } }])
  })
  it('진행 중 겹친 누름은 세지 않는다', async () => {
    const gate = deferred<boolean>()
    const f = fake({ signedIn: false, askSignIn: () => gate.promise })
    const d = createPlaceDirections(f.deps)
    const first = d.start(place)
    await d.start(place)
    gate.resolve(false)
    await first
    expect(f.events.filter((e) => e.name === 'start_directions')).toHaveLength(1)
  })
  it('빌더 모양 — appEvent.startDirections', () => {
    expect(appEvent.startDirections('place_detail', true)).toEqual({ name: 'start_directions', params: { source: 'place_detail', signed_in: 1 } })
    expect(appEvent.startDirections('place_detail', false).params.signed_in).toBe(0)
  })
})


describe('코스 제목 — 계약의 50자 제한(CourseCreate·CourseReplace title maxLength 50)', () => {
  const long: PlaceSummary = { ...place, name: 'Former Seoul Station Plaza and the Old Train Platform Café Street Market' }
  it('영어 「Trip to …」가 50자를 넘으면 장소 이름을 말줄임으로 잘라 50자 안에 둔다', () => {
    chooseLanguage('en')
    const title = directionsCourse(long).title
    chooseLanguage('ko')
    expect(title.length).toBeLessThanOrEqual(50)
    expect(title.startsWith('Trip to Former Seoul Station')).toBe(true)
    expect(title.endsWith('…')).toBe(true)
  })
  it('한국어도 50자 안, 짧으면 자르지 않는다', () => {
    const k = directionsCourse({ ...place, name: '가'.repeat(60) }).title
    expect(k.length).toBeLessThanOrEqual(50)
    expect(k.endsWith('… 가는 길')).toBe(true)
    expect(directionsCourse(place).title).toBe('도깨비 방파제 가는 길')
  })
  it('만들기(POST)와 채우기(PUT)에 같은 제목이 간다 — 둘 다 이 코스의 title 이다', async () => {
    chooseLanguage('en')
    const f = fake()
    await createPlaceDirections(f.deps).start(long)
    chooseLanguage('ko')
    expect(f.created[0].title.length).toBeLessThanOrEqual(50)
  })
})

describe('이미 있는 「가는 길」 코스를 다시 연다', () => {
  it('받아 둔 목록에 같은 장소의 여행 중 1곳짜리 코스가 있으면 만들지 않고 시작도 다시 하지 않고 그것을 연다', async () => {
    const f = fake()
    const existing = { ...directionsCourse(place), serverId: 700, isRunning: true, detailLoaded: true }
    f.server.set(700, existing)
    f.deps.existingCandidates = () => [700]
    const outcome = await createPlaceDirections(f.deps).start(place)
    expect(outcome).toEqual({ kind: 'started', courseId: 700 })
    expect(f.created).toHaveLength(0)
    expect(f.started).toHaveLength(0)
    expect(f.opened).toEqual([700])
  })
  it('후보가 다른 장소의 코스면 새로 만든다', async () => {
    const f = fake()
    f.server.set(701, { ...directionsCourse({ ...place, id: 7 }), serverId: 701, isRunning: true, detailLoaded: true })
    f.deps.existingCandidates = () => [701]
    await createPlaceDirections(f.deps).start(place)
    expect(f.created).toHaveLength(1)
    expect(f.opened).toEqual([901])
  })
  it('상세를 닫았다 다시 열어도(새 컨트롤러) 같은 장소면 새로 만들지 않는다 — 기억은 모듈 단위', async () => {
    const f = fake()
    await createPlaceDirections(f.deps).start(place)
    await createPlaceDirections(f.deps).start(place)
    expect(f.created).toHaveLength(1)
    expect(f.opened).toEqual([901, 901])
  })
  it('시작이 실패한 코스를 다른 컨트롤러가 다시 누르면 만들지 않고 시작만 한다', async () => {
    const f = fake()
    const startOk = f.deps.startCourse
    let fail = true
    f.deps.startCourse = async (c) => {
      if (fail) {
        fail = false
        return { ok: false, message: 'x' }
      }
      return startOk(c)
    }
    await createPlaceDirections(f.deps).start(place)
    const outcome = await createPlaceDirections(f.deps).start(place)
    expect(outcome).toEqual({ kind: 'started', courseId: 901 })
    expect(f.created).toHaveLength(1)
  })
})

describe('요청 중에 화면을 떠나면', () => {
  it('만들기 중에 떠났으면 시작(active)으로 바꾸지 않고 열지 않는다', async () => {
    const gate = deferred<void>()
    const entered = deferred<void>()
    const f = fake()
    const create = f.deps.createCourse
    f.deps.createCourse = async (c) => {
      entered.resolve()
      await gate.promise
      return create(c)
    }
    const d = createPlaceDirections(f.deps)
    const pending = d.start(place)
    await entered.promise // POST 가 나가 있는 동안 떠난다
    d.dispose()
    gate.resolve()
    expect(await pending).toEqual({ kind: 'cancelled' })
    expect(f.created).toHaveLength(1)
    expect(f.started).toHaveLength(0)
    expect(f.opened).toHaveLength(0)
  })
  it('기존 코스를 확인하는 중에 떠났어도 시작·열기를 하지 않는다', async () => {
    const gate = deferred<void>()
    const f = fake()
    f.server.set(702, { ...directionsCourse(place), serverId: 702, isRunning: false, detailLoaded: true })
    f.deps.existingCandidates = () => [702]
    const load = f.deps.loadCourse
    f.deps.loadCourse = async (id) => {
      await gate.promise
      return load(id)
    }
    const d = createPlaceDirections(f.deps)
    const pending = d.start(place)
    d.dispose()
    gate.resolve()
    expect(await pending).toEqual({ kind: 'cancelled' })
    expect(f.started).toHaveLength(0)
    expect(f.opened).toHaveLength(0)
  })
})
