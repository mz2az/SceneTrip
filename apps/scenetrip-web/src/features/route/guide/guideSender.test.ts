/*
 * 챗봇 한 턴 보내기 시험 — iOS tests/GuideChatRetrySessionTests · GuideChatRequestBuildTests.
 * 전송(`env.post`)·시계·쉬기는 가짜다. 실제 서버도 공용 클라이언트도 부르지 않는다.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { GuideSendError, TransportError, sendGuideTurn, type SenderEnv, type WireResponse } from './guideSender'
import type { ResendReason } from './guideRetry'
import { buildContext, buildRequest } from './guideRequest'
import { ledgerEntry, ledgerStore } from './rateLimit'

const REPLY = { reply: '답', toolsUsed: [{ tool: 'poi_nearby', arguments: {} }], places: [], tookSeconds: 2, effects: [], ui: [], route: null }
const ok = (over: Partial<typeof REPLY> = {}, headers: Record<string, string> = {}): WireResponse => ({ status: 200, headers: hdr(headers), body: { ...REPLY, ...over } })
const err = (status: number, code: string | null = null, headers: Record<string, string> = {}): WireResponse => ({ status, headers: hdr(headers), body: code ? { code, message: 'x' } : null })
const hdr = (h: Record<string, string>) => ({ get: (n: string) => Object.entries(h).find(([k]) => k.toLowerCase() === n.toLowerCase())?.[1] ?? null })

type Step = WireResponse | 'lost' | 'offline'

interface Rig {
  env: SenderEnv
  calls: Array<{ key: string; request: unknown }>
  sleeps: number[]
  clock: { ms: number }
  script: Step[]
}

function rig(script: Step[]): Rig {
  const r: Rig = { env: null as unknown as SenderEnv, calls: [], sleeps: [], clock: { ms: 1_000_000 }, script }
  r.env = {
    post: async (request, key) => {
      r.calls.push({ key, request })
      const next = r.script.shift()
      if (next === undefined) throw new Error('스크립트가 끝났다')
      if (next === 'lost') throw new TransportError('network')
      if (next === 'offline') throw new TransportError('offline')
      return next
    },
    nowMs: () => r.clock.ms,
    sleep: async (ms) => {
      r.sleeps.push(ms / 1000)
      r.clock.ms += ms
    },
    random: () => 0.5, // 지터 0
  }
  return r
}

const request = buildRequest({ history: [{ id: 1, role: 'user', text: '근처 카페' }], here: { latitude: 37.5663, longitude: 126.9779 }, sessionId: 's-1' })
const args = (key: string, over: Partial<Parameters<typeof sendGuideTurn>[0]> = {}) => ({ request, key, lang: 'ko' as const, keyed: true, signal: new AbortController().signal, ...over })
const failureOf = async (p: Promise<unknown>) => {
  try {
    await p
  } catch (e) {
    if (e instanceof GuideSendError) return e.failure
    throw e
  }
  throw new Error('실패해야 한다')
}

beforeEach(() => ledgerStore.set({ entries: {}, seen: false }))
afterEach(() => vi.useRealTimers())

describe('보내기', () => {
  it('답이 오면 화면 모양으로 돌려주고 남은 양을 장부에 적는다', async () => {
    const r = rig([ok({}, { 'RateLimit-Limit': '15', 'RateLimit-Remaining': '3', 'RateLimit-Reset': '120' })])
    const answer = await sendGuideTurn(args('k1'), r.env)
    expect(answer).toMatchObject({ reply: '답', tools: ['poi_nearby'], seconds: 2 })
    expect(r.calls).toHaveLength(1)
    expect(ledgerEntry('guide')).toEqual({ limit: 15, remaining: 3, resetsAtMs: expect.any(Number) })
  })

  it('끊긴 턴은 같은 키·같은 요청으로 한 번만 자동으로 다시 보낸다', async () => {
    const r = rig(['lost', ok()])
    const resends: ResendReason[] = []
    await sendGuideTurn(args('k2', { onResend: (x) => resends.push(x) }), r.env)
    expect(r.calls.map((c) => c.key)).toEqual(['k2', 'k2'])
    expect(r.calls[1].request).toBe(r.calls[0].request)
    expect(r.sleeps).toEqual([1])
    expect(resends).toEqual(['failed'])
  })

  it('자동 재시도는 한 번뿐이다 — 두 번째 실패는 올라간다', async () => {
    const r = rig(['lost', 'lost', ok()])
    expect(await failureOf(sendGuideTurn(args('k3'), r.env))).toEqual({ kind: 'unreachable' })
    expect(r.calls).toHaveLength(2)
    const r2 = rig([err(503), err(503)])
    expect(await failureOf(sendGuideTurn(args('k3b'), r2.env))).toEqual({ kind: 'unavailable' })
  })

  it('4xx 와 유료 한도는 한 번만 보낸다', async () => {
    for (const [res, kind] of [[err(400, 'INVALID_PARAMETER'), 'badRequest'], [err(401, 'SIGN_IN_REQUIRED'), 'signInRequired'], [err(429, 'GUIDE_LIMIT_REACHED', { 'Retry-After': '1200' }), 'limitReached'], [err(422, 'IDEMPOTENCY_KEY_REUSED'), 'keyReused']] as const) {
      const r = rig([res])
      const f = await failureOf(sendGuideTurn(args(`k-${kind}`), r.env))
      expect(f.kind).toBe(kind)
      expect(r.calls, kind).toHaveLength(1)
    }
  })

  it('한도 실패는 Retry-After 를 싣는다', async () => {
    const r = rig([err(429, 'GUIDE_LIMIT_REACHED', { 'Retry-After': '1200' })])
    expect(await failureOf(sendGuideTurn(args('kl'), r.env))).toEqual({ kind: 'limitReached', retryAfter: 1200 })
  })

  it('오프라인은 다시 보내지 않고 그렇다고 말한다', async () => {
    const r = rig(['offline', ok()])
    expect(await failureOf(sendGuideTurn(args('ko'), r.env))).toEqual({ kind: 'offline' })
    expect(r.calls).toHaveLength(1)
  })

  it('분당 한도는 기다렸다가 같은 키로 보낸다', async () => {
    const r = rig([err(429, 'RATE_LIMITED', { 'Retry-After': '7' }), ok()])
    const resends: ResendReason[] = []
    await sendGuideTurn(args('kr', { onResend: (x) => resends.push(x) }), r.env)
    expect(r.calls.map((c) => c.key)).toEqual(['kr', 'kr'])
    expect(r.sleeps).toEqual([7])
    expect(resends).toEqual(['rateLimited'])
  })

  it('서버가 키를 모르면 한 번만 보낸다', async () => {
    const r = rig(['lost', ok()])
    expect(await failureOf(sendGuideTurn(args('ku', { keyed: false }), r.env))).toEqual({ kind: 'unreachable' })
    expect(r.calls).toHaveLength(1)
    const r2 = rig([err(409, 'IDEMPOTENCY_IN_PROGRESS'), ok()])
    await failureOf(sendGuideTurn(args('ku2', { keyed: false }), r2.env))
    expect(r2.calls).toHaveLength(1)
  })

  it('끊긴 뒤 서버가 「처리 중」 이면 저장된 답이 올 때까지 3초마다 같은 키로 되묻는다', async () => {
    const r = rig(['lost', err(409, 'IDEMPOTENCY_IN_PROGRESS'), err(409, 'IDEMPOTENCY_IN_PROGRESS'), ok()])
    const resends: ResendReason[] = []
    await sendGuideTurn(args('kp', { onResend: (x) => resends.push(x) }), r.env)
    expect(r.calls.map((c) => c.key)).toEqual(['kp', 'kp', 'kp', 'kp'])
    expect(r.sleeps).toEqual([1, 3, 3])
    expect(resends).toEqual(['failed', 'inProgress', 'inProgress'])
  })

  it('되묻기는 서버가 키를 잊는 1분 앞에서 멈추고 「오래 걸려 멈췄어요」 로 끝낸다', async () => {
    const script: Step[] = Array.from({ length: 40 }, () => err(409, 'IDEMPOTENCY_IN_PROGRESS'))
    const r = rig(script)
    expect(await failureOf(sendGuideTurn(args('kd'), r.env))).toEqual({ kind: 'timedOut' })
    // 3초 간격, 처음 보낸 때부터 55초 안 — 열일곱 번 되묻고 끝난다
    const waited = r.sleeps.reduce((a, b) => a + b, 0)
    expect(waited).toBeLessThan(55)
    expect(r.calls.length).toBeLessThanOrEqual(19)
  })

  it('같은 키로 다시 시도해도 키를 처음 보낸 때부터 센다', async () => {
    const r = rig([ok()])
    // 이 키를 처음 보낸 때를 적는다 — 그 뒤 52초가 흘렀다(「다시 시도」 로 새 요청이 나간다) — 되물을 여유가 없다
    await sendGuideTurn(args('kc'), r.env)
    r.clock.ms += 52_000
    const r2 = rig([err(409, 'IDEMPOTENCY_IN_PROGRESS')])
    r2.clock.ms = r.clock.ms
    expect(await failureOf(sendGuideTurn(args('kc'), r2.env))).toEqual({ kind: 'timedOut' })
    expect(r2.calls).toHaveLength(1)
    // 다른 키는 제 시계가 있다
    const r3 = rig([err(409, 'IDEMPOTENCY_IN_PROGRESS'), ok()])
    r3.clock.ms = r.clock.ms
    await sendGuideTurn(args('kc-other'), r3.env)
    expect(r3.calls).toHaveLength(2)
  })

  it('429 는 키를 나이 들게 하지 않는다 — 처리 전에 거절됐다', async () => {
    const r = rig([err(429, 'GUIDE_LIMIT_REACHED', { 'Retry-After': '30' })])
    await failureOf(sendGuideTurn(args('kn'), r.env))
    r.clock.ms += 120_000
    const r2 = rig([err(409, 'IDEMPOTENCY_IN_PROGRESS'), ok()])
    r2.clock.ms = r.clock.ms
    await sendGuideTurn(args('kn'), r2.env) // 새로 센다 — 되물을 수 있다
    expect(r2.calls).toHaveLength(2)
  })

  it('쉬는 동안 취소하면 더 보내지 않는다', async () => {
    const controller = new AbortController()
    const r = rig(['lost', ok()])
    r.env.sleep = async () => controller.abort()
    expect(await failureOf(sendGuideTurn(args('kx', { signal: controller.signal }), r.env))).toEqual({ kind: 'interrupted' })
    expect(r.calls).toHaveLength(1)
  })

  it('처음부터 취소돼 있으면 보내지 않는다', async () => {
    const controller = new AbortController()
    controller.abort()
    const r = rig([ok()])
    expect(await failureOf(sendGuideTurn(args('ky', { signal: controller.signal }), r.env))).toEqual({ kind: 'interrupted' })
    expect(r.calls).toHaveLength(0)
  })
})

describe('벽', () => {
  it('한 번의 요청은 50초를 기다리다 끊기고 닿았는지 모르니 한 번 다시 보낸다', async () => {
    vi.useFakeTimers()
    const hangs: SenderEnv = {
      post: vi.fn(() => new Promise<WireResponse>(() => {})),
      nowMs: () => Date.now(),
      sleep: async () => {},
      random: () => 0.5,
    }
    const run = failureOf(sendGuideTurn(args('kw'), hangs))
    await vi.advanceTimersByTimeAsync(50_000)
    expect(hangs.post).toHaveBeenCalledTimes(2)
    await vi.advanceTimersByTimeAsync(50_000)
    expect(await run).toEqual({ kind: 'timedOut' })
  })

  it('키가 없으면 50초에 끊고 다시 보내지 않는다', async () => {
    vi.useFakeTimers()
    const post = vi.fn(() => new Promise<WireResponse>(() => {}))
    const env: SenderEnv = { post, nowMs: () => Date.now(), sleep: async () => {}, random: () => 0.5 }
    const run = failureOf(sendGuideTurn(args('kw2', { keyed: false }), env))
    await vi.advanceTimersByTimeAsync(50_000)
    expect(await run).toEqual({ kind: 'timedOut' })
    expect(post).toHaveBeenCalledTimes(1)
  })
})

describe('요청 만들기 (GuideChatRequestBuildTests)', () => {
  it('이력은 마지막 40 개만 싣고 마지막이 이번 질문이다', () => {
    const history = Array.from({ length: 45 }, (_, i) => ({ id: i, role: (i % 2 === 0 ? 'user' : 'assistant') as 'user' | 'assistant', text: `m${i}` }))
    const req = buildRequest({ history, here: { latitude: 1, longitude: 2 }, sessionId: 's' })
    expect(req.messages).toHaveLength(40)
    expect(req.messages[0].content).toBe('m5')
    expect(req.messages[39].content).toBe('m44')
    expect(req).toMatchObject({ sessionId: 's', latitude: 1, longitude: 2 })
    expect('context' in req).toBe(false)
  })

  it('화면 상태는 번호째 싣고 좌표는 서버가 번호를 좌표로 바꾸는 데 쓴다', () => {
    const context = buildContext({
      stops: [{ number: 1, name: '북촌', kind: '촬영지', latitude: 37.58, longitude: 126.98 }],
      picked: { number: 1, name: '북촌', kind: null, latitude: 37.58, longitude: 126.98, visited: true },
      trip: { phase: 'guiding', course: '내 코스', day: 1, days: 2, targetNumber: 1, targetMeters: 320, walkedKilometers: 1.2 },
    })
    expect(context.stops[0]).toEqual({ number: 1, name: '북촌', category: '촬영지', latitude: 37.58, longitude: 126.98, visited: false })
    expect(context.picked?.visited).toBe(true)
    expect(context.trip?.targetMeters).toBe(320)
    expect(buildRequest({ history: [], here: { latitude: 0, longitude: 0 }, sessionId: 's', context }).context).toBe(context)
  })
})
