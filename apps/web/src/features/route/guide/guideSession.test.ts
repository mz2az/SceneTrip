/*
 * 가이드 대화(턴·재시도·한도) 시험 — iOS tests/RouteGuideTurnTests · RouteGuideLimitTests(Session) · RouteGuideSessionTests.
 * 밖과 닿는 자리(`GuideEnv`)는 가짜다 — 모델도 서버도 부르지 않는다.
 */
import { beforeEach, describe, expect, it } from 'vitest'
import { chooseLanguage, type Lang } from '@/i18n'
import { type GuideFailure } from './guideFailure'
import { type GuideAnswer, GuideSendError } from './guideSender'
import { GuideSession, type GuideEnv } from './guideSession'
import { makeTurn, turnFailing, turnResending, turnRetried } from './guideTurn'
import type { LedgerEntry } from './rateLimit'
import type { ResendReason } from './guideRetry'
import { makeSpot } from '../poi/poiPlace'

beforeEach(() => chooseLanguage('ko'))

const answer = (text: string, over: Partial<GuideAnswer> = {}): GuideAnswer => ({ reply: text, tools: [], places: [], seconds: 1, effects: [], ui: [], route: null, ...over })
const fail = (f: GuideFailure) => new GuideSendError(f)
const SEOUL = { latitude: 37.5663, longitude: 126.9779 }

/** 가짜 창구 — 나간 것을 적고, 정해 둔 답을 준다. */
class Wire {
  sent: Array<{ key: string; request: unknown; keyed: boolean; lang: Lang }> = []
  replies: Array<GuideAnswer | GuideSendError> = []
  /** 켜면 답하지 않는다 — 취소될 때까지 떠 있다. */
  hangs = false
  /** 답하기 전에 다시 보내기를 알린다. */
  resends: ResendReason[] = []
  onResend: ((r: ResendReason) => void) | null = null
  async send(turn: Parameters<GuideEnv['send']>[0], signal: AbortSignal, onResend: (r: ResendReason) => void): Promise<GuideAnswer> {
    this.sent.push({ key: turn.key, request: turn.request, keyed: turn.keyed, lang: turn.lang })
    this.onResend = onResend
    for (const r of this.resends) onResend(r)
    if (this.hangs) {
      await new Promise<void>((resolve) => {
        if (signal.aborted) return resolve()
        signal.addEventListener('abort', () => resolve())
      })
      throw fail({ kind: 'interrupted' })
    }
    const next = this.replies.shift() ?? answer('기본 답')
    if (next instanceof Error) throw next
    return next
  }
}

interface Rig {
  session: GuideSession
  wire: Wire
  state: { keyed: boolean; lang: Lang; asked: number; now: number; wall: number; quota: LedgerEntry | null; sleeps: number[]; wake: boolean }
  panel: string
}

function rig(): Rig {
  const wire = new Wire()
  const state = { keyed: true, lang: 'en' as Lang, asked: 0, now: 9000, wall: 2_000_000_000, quota: null as LedgerEntry | null, sleeps: [] as number[], wake: false }
  let n = 0
  const env: GuideEnv = {
    send: (turn, signal, onResend) => wire.send(turn, signal, onResend),
    serverKeepsKeys: () => state.keyed,
    lang: () => state.lang,
    newKey: () => `key-${(n += 1)}`,
    asked: () => (state.asked += 1),
    now: () => state.now,
    wall: () => state.wall,
    quota: () => state.quota,
    sleep: async (seconds, signal) => {
      state.sleeps.push(seconds)
      while (!state.wake && !signal.aborted) await tick()
      state.wake = false
    },
  }
  const session = new GuideSession(env)
  const panel = 'panel-1'
  session.setAttended(true, panel)
  return { session, wire, state, panel }
}

/** 마이크로태스크만 돌면 타이머·입출력이 굶는다 — 한 박자 쉰다. */
const tick = (): Promise<void> => new Promise((resolve) => setTimeout(resolve, 0))

const limited = (retryAfter: number | null) => fail({ kind: 'limitReached', retryAfter })
const texts = (s: GuideSession) => s.state.turns.map((t) => t.text)

describe('가이드 턴', () => {
  it('답이 오면 턴이 비워지고 대화에 쌓인다', async () => {
    const { session, wire, state } = rig()
    wire.replies = [answer('덕수궁 옆에 카페가 있어요')]
    await session.ask('근처 카페', SEOUL, null)
    expect(session.state.pending).toBeNull()
    expect(session.state.failure).toBeNull()
    expect(session.asking).toBe(false)
    expect(session.canRetry).toBe(false)
    expect(texts(session)).toEqual(['근처 카페', '덕수궁 옆에 카페가 있어요'])
    expect(wire.sent).toHaveLength(1)
    expect(wire.sent[0].keyed).toBe(true)
    expect(state.asked).toBe(1)
  })

  it('말마다 새 멱등 키다', async () => {
    const { session, wire } = rig()
    await session.ask('하나', SEOUL, null)
    await session.ask('둘', SEOUL, null)
    expect(wire.sent).toHaveLength(2)
    expect(wire.sent[0].key).not.toBe(wire.sent[1].key)
  })

  it('다시 시도는 같은 키·같은 요청이고 질문을 또 적지 않고 분석에도 한 번만 센다', async () => {
    const { session, wire, state } = rig()
    wire.replies = [fail({ kind: 'unavailable' }), answer('이제 답')]
    await session.ask('근처 카페', SEOUL, null)
    expect(session.state.failure).toEqual({ kind: 'unavailable' })
    expect(session.canRetry).toBe(true)
    expect(session.state.pending?.stage).toEqual({ kind: 'failed', failure: { kind: 'unavailable' } })
    await session.retry()
    expect(wire.sent).toHaveLength(2)
    expect(wire.sent[1].key).toBe(wire.sent[0].key)
    expect(wire.sent[1].request).toBe(wire.sent[0].request) // 처음 만든 것을 그대로 — 내용이 달라지면 서버는 422 다
    expect(texts(session)).toEqual(['근처 카페', '이제 답'])
    expect(session.state.pending).toBeNull()
    expect(session.state.failure).toBeNull()
    expect(state.asked).toBe(1)
  })

  it('다시 해 볼 만한 실패는 턴을 남기고 끝난 실패는 버린다', async () => {
    for (const f of [{ kind: 'timedOut' }, { kind: 'unreachable' }, { kind: 'offline' }, { kind: 'unavailable' }, { kind: 'rateLimited' }, { kind: 'other', status: 500 }] as GuideFailure[]) {
      const { session, wire } = rig()
      wire.replies = [fail(f)]
      await session.ask('질문', SEOUL, null)
      expect(session.state.failure, f.kind).toEqual(f)
      expect(session.state.pending?.stage.kind, f.kind).toBe('failed')
      expect(session.canRetry, f.kind).toBe(true)
    }
    for (const f of [{ kind: 'signInRequired' }, { kind: 'sessionExpired' }, { kind: 'badRequest' }, { kind: 'other', status: 404 }] as GuideFailure[]) {
      const { session, wire } = rig()
      wire.replies = [fail(f)]
      await session.ask('질문', SEOUL, null)
      expect(session.state.failure, f.kind).toEqual(f)
      expect(session.state.pending, f.kind).toBeNull()
      expect(session.canRetry, f.kind).toBe(false)
    }
  })

  it('키 재사용(422)은 새 키로 다시 보낸다', async () => {
    const { session, wire } = rig()
    wire.replies = [fail({ kind: 'keyReused' }), answer('됐다')]
    await session.ask('질문', SEOUL, null)
    await session.retry()
    expect(wire.sent[1].key).not.toBe(wire.sent[0].key)
  })

  it('다시 시도 사이에 앱 언어가 바뀌었으면 새 키로 보낸다(지문이 달라진다)', async () => {
    const { session, wire, state } = rig()
    wire.replies = [fail({ kind: 'unavailable' }), answer('답')]
    await session.ask('질문', SEOUL, null)
    state.lang = 'ko'
    await session.retry()
    expect(wire.sent[1].key).not.toBe(wire.sent[0].key)
    expect(wire.sent[1].lang).toBe('ko')
  })

  it('실패한 뒤의 새 질문은 새 턴이다', async () => {
    const { session, wire } = rig()
    wire.replies = [fail({ kind: 'unavailable' }), answer('답')]
    await session.ask('하나', SEOUL, null)
    await session.ask('둘', SEOUL, null)
    expect(wire.sent[1].key).not.toBe(wire.sent[0].key)
    expect(texts(session)).toEqual(['하나', '둘', '답'])
    expect(session.state.failure).toBeNull()
    expect(session.state.pending).toBeNull()
  })

  it('서버가 멱등 키를 안다는 증거가 없으면 다시 시도 단추가 없다', async () => {
    const { session, wire, state } = rig()
    state.keyed = false
    wire.replies = [fail({ kind: 'unavailable' })]
    await session.ask('질문', SEOUL, null)
    expect(wire.sent[0].keyed).toBe(false)
    expect(session.state.failure).toEqual({ kind: 'unavailable' })
    expect(session.state.pending).toBeNull()
    expect(session.canRetry).toBe(false)
  })

  it('답을 기다리는 동안 전송은 잠긴다 — 두 번 누름은 여기서 막힌다', async () => {
    const { session, wire } = rig()
    wire.hangs = true
    const first = session.ask('하나', SEOUL, null)
    await Promise.resolve()
    expect(session.asking).toBe(true)
    await session.ask('둘', SEOUL, null)
    expect(wire.sent).toHaveLength(1)
    expect(texts(session)).toEqual(['하나'])
    session.clear()
    await first
  })

  it('다시 보내기 알림이 단계를 옮긴다', async () => {
    const { session, wire } = rig()
    wire.hangs = true
    wire.resends = ['failed']
    const run = session.ask('질문', SEOUL, null)
    await Promise.resolve()
    expect(session.state.pending?.stage.kind).toBe('reconnecting')
    wire.onResend?.('inProgress')
    expect(session.state.pending?.stage.kind).toBe('awaitingServer')
    wire.onResend?.('rateLimited')
    expect(session.state.pending?.stage.kind).toBe('waitingForLimit')
    session.clear()
    await run
  })

  it('창을 닫으면 기다리던 다시 보내기를 끊고, 끊긴 턴은 남아 다시 시도가 된다', async () => {
    const { session, wire, panel } = rig()
    wire.hangs = true
    wire.resends = ['failed']
    const run = session.ask('질문', SEOUL, null)
    await Promise.resolve()
    session.setAttended(false, panel)
    await run
    expect(session.state.failure).toEqual({ kind: 'interrupted' })
    expect(session.canRetry).toBe(true)
    expect(session.state.pending?.stage.kind).toBe('failed')
  })

  it('창 하나가 사라져도 다른 창이 떠 있으면 닫힌 것이 아니다', async () => {
    const { session, wire, panel } = rig()
    session.setAttended(true, 'panel-2')
    wire.hangs = true
    wire.resends = ['failed']
    const run = session.ask('질문', SEOUL, null)
    await Promise.resolve()
    session.setAttended(false, panel)
    expect(session.state.pending?.stage.kind).toBe('reconnecting')
    session.setAttended(false, 'panel-2')
    await run
    expect(session.state.failure).toEqual({ kind: 'interrupted' })
  })

  it('처음 보낸 요청은 창을 닫아도 끊지 않는다(묻고 지도를 보려고 창을 내린다)', async () => {
    const { session, wire, panel } = rig()
    wire.replies = [answer('답')]
    wire.hangs = false
    const run = session.ask('질문', SEOUL, null)
    session.setAttended(false, panel)
    await run
    expect(texts(session)).toEqual(['질문', '답'])
  })

  it('다른 코스를 열면 턴·대화·장소를 버리고 같은 코스는 이어진다', async () => {
    const { session, wire } = rig()
    session.bind('course-1')
    wire.replies = [answer('답', { places: [makeSpot({ id: 'poi-1', name: '가', category: '카페', latitude: 1, longitude: 2 })] })]
    await session.ask('질문', SEOUL, null)
    expect(session.state.places).toHaveLength(1)
    session.bind('course-1')
    expect(session.isEmpty).toBe(false)
    session.rekey('course-9')
    session.bind('course-9')
    expect(session.isEmpty).toBe(false)
    session.bind('course-2')
    expect(session.isEmpty).toBe(true)
    expect(session.state.places).toEqual([])
    expect(session.state.pending).toBeNull()
  })

  it('장소를 새로 찾아 왔을 때만 목록을 갈아 끼운다(되물음은 추천을 지우지 않는다)', async () => {
    const { session, wire } = rig()
    const spot = makeSpot({ id: 'poi-1', name: '가', category: '카페', latitude: 1, longitude: 2 })
    wire.replies = [answer('추천', { places: [spot], tools: ['poi_nearby'] }), answer('현위치 기준이에요')]
    await session.ask('근처 카페', SEOUL, null)
    session.setPicked(spot)
    await session.ask('어디 기준이야?', SEOUL, null)
    expect(session.state.places).toEqual([spot])
    expect(session.state.picked).toBe(spot)
    expect(session.state.tools).toEqual([])
    expect(session.state.answerTick).toBe(2)
  })

  it('빈 답은 「답을 받지 못했습니다.」', async () => {
    const { session, wire } = rig()
    wire.replies = [answer('')]
    await session.ask('질문', SEOUL, null)
    expect(texts(session)).toEqual(['질문', '답을 받지 못했습니다.'])
  })

  it('턴 전이는 순수 함수다', () => {
    const base = makeTurn({ key: 'k', request: { sessionId: 's', latitude: 1, longitude: 2, messages: [] }, lang: 'ko', keyed: true })
    expect(turnResending(base, 'failed').stage.kind).toBe('reconnecting')
    const failed = turnFailing(base, { kind: 'unavailable' })!
    expect(turnResending(failed, 'failed')).toBe(failed)
    expect(turnFailing(base, { kind: 'badRequest' })).toBeNull()
    expect(turnFailing({ ...base, keyed: false }, { kind: 'unavailable' })).toBeNull()
    expect(turnRetried(base, 'ko', () => 'n')).toBeNull()
    expect(turnRetried(failed, 'ko', () => 'n')?.key).toBe('k')
    expect(turnRetried(failed, 'en', () => 'n')?.key).toBe('n')
    expect(turnRetried(turnFailing(base, { kind: 'keyReused' })!, 'ko', () => 'n')?.key).toBe('n')
  })
})

describe('챗봇 한도가 걸린 대화', () => {
  const settle = async (done: () => boolean) => {
    for (let i = 0; i < 200 && !done(); i += 1) await tick()
  }

  it('짧은 창 — 잠그고, 자동으로 다시 보내지 않고, 풀리면 같은 키·같은 요청으로 다시 시도', async () => {
    const { session, wire, state } = rig()
    wire.replies = [limited(20), answer('이제 답')]
    await session.ask('근처 카페', SEOUL, null)
    expect(session.state.failure).toEqual({ kind: 'limitReached', retryAfter: 20 })
    expect(session.state.limit).toEqual({ kind: 'reached', block: { window: 'short', until: state.now + 20 } })
    expect(session.isLimited).toBe(true)
    expect(session.canRetry).toBe(false) // 풀리기 전에는 단추가 없다
    expect(wire.sent).toHaveLength(1)

    // 잠긴 동안에는 새 질문도 다시 시도도 나가지 않는다 — 뻔히 429 다
    await session.ask('다른 질문', SEOUL, null)
    await session.retry()
    expect(wire.sent).toHaveLength(1)
    expect(texts(session)).toEqual(['근처 카페'])

    await settle(() => state.sleeps.length > 0)
    state.now += 20
    state.wake = true
    await settle(() => session.state.limit.kind === 'lifted')
    expect(state.sleeps).toEqual([20])
    expect(session.isLimited).toBe(false)
    expect(session.canRetry).toBe(true)

    await session.retry()
    expect(wire.sent).toHaveLength(2)
    expect(wire.sent[1].key).toBe(wire.sent[0].key)
    expect(wire.sent[1].request).toBe(wire.sent[0].request)
    expect(texts(session)).toEqual(['근처 카페', '이제 답'])
    expect(session.state.limit).toEqual({ kind: 'clear' })
    expect(session.state.failure).toBeNull()
  })

  it('타이머가 늦어도(앱이 뒤에 있었다) 보내기 직전과 창을 열 때 시계를 다시 본다', async () => {
    const { session, wire, state } = rig()
    wire.replies = [limited(20)]
    await session.ask('하나', SEOUL, null)
    state.now += 21
    expect(session.isLimited).toBe(false)
    session.setAttended(true, 'another')
    expect(session.state.limit.kind).toBe('lifted')
    await session.ask('둘', SEOUL, null) // 풀린 뒤의 새 질문은 새 키다
    expect(wire.sent).toHaveLength(2)
    expect(wire.sent[1].key).not.toBe(wire.sent[0].key)
    expect(session.state.limit).toEqual({ kind: 'clear' })
  })

  it('하루 한도도 같은 잠금이다', async () => {
    const { session, wire } = rig()
    wire.replies = [limited(30000)]
    await session.ask('하나', SEOUL, null)
    const limit = session.state.limit
    expect(limit.kind === 'reached' && limit.block.window).toBe('day')
    expect(session.isLimited).toBe(true)
    expect(session.canRetry).toBe(false)
  })

  it('잠금은 연속 시계로 잰다 — 벽시계를 돌려도 늘거나 줄지 않는다', async () => {
    const { session, wire, state } = rig()
    wire.replies = [limited(600)]
    await session.ask('하나', SEOUL, null)
    state.wall += 86400_000
    session.refreshLimit()
    expect(session.isLimited).toBe(true)
    state.wall -= 3 * 86400_000
    state.now += 599
    expect(session.isLimited).toBe(true)
    state.now += 1
    expect(session.isLimited).toBe(false)
    session.refreshLimit()
    expect(session.state.limit.kind).toBe('lifted')
  })

  it('Retry-After 가 없으면 잠그지 않는다 — 안내는 보이고 다시 시도가 바로 뜬다', async () => {
    const { session, wire, state } = rig()
    wire.replies = [limited(null), limited(null), answer('답')]
    await session.ask('하나', SEOUL, null)
    expect(session.state.limit).toEqual({ kind: 'reached', block: { window: 'unknown', until: null } })
    expect(session.isLimited).toBe(false)
    expect(session.canRetry).toBe(true)
    expect(state.sleeps).toEqual([])
    await session.retry()
    expect(wire.sent[1].key).toBe(wire.sent[0].key)
    expect(session.canRetry).toBe(true)
    await session.ask('둘', SEOUL, null)
    expect(wire.sent[2].key).not.toBe(wire.sent[0].key)
    expect(session.state.limit).toEqual({ kind: 'clear' })
    expect(texts(session)).toEqual(['하나', '둘', '답'])
  })

  it('한도는 계정의 것이다 — 다른 코스를 열어도 잠겨 있고 계정이 바뀌면 풀린다', async () => {
    const { session, wire } = rig()
    session.bind('course-1')
    wire.replies = [limited(600)]
    await session.ask('하나', SEOUL, null)
    session.bind('course-2')
    expect(session.isEmpty).toBe(true)
    expect(session.state.failure).toBeNull()
    expect(session.isLimited).toBe(true)
    await session.ask('둘', SEOUL, null)
    expect(wire.sent).toHaveLength(1)
    session.forgetLimit()
    expect(session.state.limit).toEqual({ kind: 'clear' })
    expect(session.isLimited).toBe(false)
    expect(session.state.quota).toBeNull()
  })

  it('요청이 떠 있는 사이에 계정이 바뀌면 늦게 돌아온 429 도 답도 버린다', async () => {
    for (const late of [limited(600), answer('앞 계정의 답')]) {
      const { session, wire } = rig()
      let release: () => void = () => {}
      const gate = new Promise<void>((resolve) => (release = resolve))
      const original = wire.send.bind(wire)
      wire.send = async (...args) => {
        await gate
        wire.replies = [late]
        return original(...args)
      }
      const run = session.ask('앞 계정의 질문', SEOUL, null)
      await Promise.resolve()
      session.forgetLimit()
      release()
      await run
      expect(session.state.limit).toEqual({ kind: 'clear' })
      expect(session.isLimited).toBe(false)
      expect(session.state.failure).toBeNull()
      expect(session.state.pending).toBeNull()
      expect(texts(session)).toEqual(['앞 계정의 질문'])
    }
  })

  it('서버가 멱등 키를 모르면 턴을 남기지 않는다 — 풀린 뒤에도 다시 시도는 없다', async () => {
    const { session, wire, state } = rig()
    state.keyed = false
    wire.replies = [limited(20)]
    await session.ask('하나', SEOUL, null)
    expect(session.state.pending).toBeNull()
    expect(session.isLimited).toBe(true)
    state.now += 20
    session.refreshLimit()
    expect(session.state.limit.kind).toBe('lifted')
    expect(session.canRetry).toBe(false)
  })

  it('남은 양은 응답마다 장부에서 다시 읽는다', async () => {
    const { session, state } = rig()
    expect(session.state.quota).toBeNull()
    state.quota = { limit: 15, remaining: 3, resetsAtMs: state.wall + 1_200_000 }
    await session.ask('하나', SEOUL, null)
    expect(session.state.quota).toEqual({ limit: 15, remaining: 3, refillsAt: state.now + 1200 })
    // 헤더 없는 답 — 장부가 같은 값을 든다. 그 사이 기기 시계가 바뀌어도 옮겨 둔 시각은 그대로다.
    state.wall += 86400_000
    state.now += 60
    await session.ask('하나 반', SEOUL, null)
    expect(session.state.quota?.refillsAt).toBe(9000 + 1200)
    state.quota = null
    session.forgetLimit()
    await session.ask('셋', SEOUL, null)
    expect(session.state.quota).toBeNull()
  })
})
