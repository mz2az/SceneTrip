/*
 * 가이드 규칙 시험 — iOS tests/RouteGuideLimitTests(규칙) · RouteGuideFailureTests · GuideChatRetryRulesTests ·
 * RouteGuideChipTests · RouteGuideDirectiveTests 와 같은 값.
 */
import { beforeEach, describe, expect, it } from 'vitest'
import { chooseLanguage } from '@/i18n'
import { blockIsOver, blockMessage, blockStopsSending, limitWindow, makeBlock, minutesLeft, quotaFromEntry, remainingOf, remainingText, type Quota } from './guideLimit'
import { classifyHttp, classifyNoResponse, failureMessage, failureRetry, isLimit, type GuideFailure } from './guideFailure'
import { CHAT_TUNING, newHistory, nextChatStep, recordStep, resendReason, type ChatOutcome, type ChatStep } from './guideRetry'
import { chipMoved, chipOffsetX, chipOffsetY, clampChip, clearanceOf, guideEntryPlacement, parseDirective, resolveAnchor } from './guideRules'
import { entryFromHeaders, intHeader, recordResponse, retryAfterOf, ledgerEntry, ledgerStore, serverKnowsLimits, forgetBucket, bucketFor } from './rateLimit'

beforeEach(() => chooseLanguage('ko'))

const NOW = 5000
const quota = (limit: number, left: number, reset: number): Quota => ({ limit, remaining: left, refillsAt: NOW + reset })
const hdr = (h: Record<string, string>) => ({ get: (n: string) => Object.entries(h).find(([k]) => k.toLowerCase() === n.toLowerCase())?.[1] ?? null })

describe('GuideLimit — 429 를 어느 안내로 가르나', () => {
  it('창은 얼마나 빨리 풀리는지로 가른다', () => {
    expect(limitWindow(1)).toBe('short')
    expect(limitWindow(60)).toBe('short')
    expect(limitWindow(3600)).toBe('short')
    expect(limitWindow(3601)).toBe('day')
    expect(limitWindow(86400)).toBe('day')
    expect(limitWindow(null)).toBe('unknown')
  })

  it('분은 올림이고 0분이라 말하지 않는다', () => {
    expect([1, 60, 61, 3600, -5].map(minutesLeft)).toEqual([1, 1, 2, 60, 1])
  })

  it('짧은 한도는 풀릴 때까지 전송을 잠근다', () => {
    const b = makeBlock(1200, NOW)
    expect(b.window).toBe('short')
    expect(b.until).toBe(NOW + 1200)
    expect(blockStopsSending(b, NOW)).toBe(true)
    expect(blockStopsSending(b, NOW + 1199)).toBe(true)
    expect(blockStopsSending(b, NOW + 1200)).toBe(false)
    expect(blockIsOver(b, NOW + 1200)).toBe(true)
  })

  it('Retry-After 가 없으면 잠그지 않고 풀렸다고도 하지 않는다', () => {
    const b = makeBlock(null, NOW)
    expect(b.window).toBe('unknown')
    expect(b.until).toBeNull()
    expect(blockStopsSending(b, NOW)).toBe(false)
    expect(blockIsOver(b, NOW + 100000)).toBe(false)
  })

  it('안내는 남은 분을 세고 하루 한도는 내일을 말한다', () => {
    const short = makeBlock(1200, NOW)
    expect(blockMessage(short, NOW)).toBe('가이드에게 잠시 많이 물어봤어요. 20분 뒤에 다시 물어볼 수 있어요')
    expect(blockMessage(short, NOW + 1150)).toBe('가이드에게 잠시 많이 물어봤어요. 1분 뒤에 다시 물어볼 수 있어요')
    expect(blockMessage(makeBlock(30000, NOW), NOW)).toBe('오늘은 가이드에게 물어볼 수 있는 횟수를 다 썼어요. 내일 다시 물어봐 주세요')
    const unknown = blockMessage(makeBlock(null, NOW), NOW)
    expect(unknown).toBe('지금은 가이드에게 물어볼 수 없어요. 잠시 뒤 다시 시도해 주세요')
    expect(unknown.includes('내일') || unknown.includes('오늘')).toBe(false)
    expect(blockMessage(makeBlock(1800, NOW), NOW)).toContain('30분 뒤')
  })

  it('안내와 실패 문구가 같은 말을 한다', () => {
    expect(failureMessage({ kind: 'limitReached', retryAfter: 600 })).toBe('가이드에게 잠시 많이 물어봤어요. 10분 뒤에 다시 물어볼 수 있어요')
    expect(failureMessage({ kind: 'limitReached', retryAfter: 30000 })).toContain('내일')
    expect(failureMessage({ kind: 'limitReached', retryAfter: null })).not.toContain('내일')
  })
})

describe('GuideLimit — 남은 양', () => {
  it('헤더가 없거나 넉넉하면 숨긴다', () => {
    expect(remainingOf(null, NOW)).toEqual({ kind: 'hidden' })
    expect(remainingOf(quota(15, 14, 1200), NOW)).toEqual({ kind: 'hidden' })
    expect(remainingOf(quota(15, 4, 1200), NOW)).toEqual({ kind: 'hidden' })
    expect(remainingOf(quota(0, 0, 1200), NOW)).toEqual({ kind: 'hidden' })
  })

  it('한도 크기의 20% 이하부터 보인다 — 15 면 3, 100 이면 20', () => {
    expect(remainingOf(quota(15, 3, 1200), NOW)).toEqual({ kind: 'few', count: 3, refillMinutes: 20 })
    expect(remainingOf(quota(100, 20, 40000), NOW)).toEqual({ kind: 'few', count: 20, refillMinutes: null })
    expect(remainingOf(quota(100, 21, 40000), NOW)).toEqual({ kind: 'hidden' })
    expect(remainingOf(quota(15, 1, 59), NOW)).toEqual({ kind: 'few', count: 1, refillMinutes: 1 })
  })

  it('0 이면 창이 다시 찰 때까지 그렇다고 말한다', () => {
    expect(remainingOf(quota(15, 0, 600), NOW)).toEqual({ kind: 'none', refillMinutes: 10 })
    expect(remainingOf(quota(100, 0, 50000), NOW)).toEqual({ kind: 'none', refillMinutes: null })
  })

  it('다시 찬 창의 수는 낡았다', () => {
    const low = quota(15, 2, 600)
    expect(remainingOf(low, NOW + 599).kind).not.toBe('hidden')
    expect(remainingOf(low, NOW + 600)).toEqual({ kind: 'hidden' })
    expect(remainingOf(quota(15, 0, 600), NOW + 601)).toEqual({ kind: 'hidden' })
  })

  it('장부의 다시 차는 때(벽시계)를 읽는 순간에 연속 시계로 옮긴다', () => {
    expect(quotaFromEntry({ limit: 15, remaining: 3, resetsAtMs: 1_000_000_000 + 1_200_000 }, 1_000_000_000, NOW)).toEqual({ limit: 15, remaining: 3, refillsAt: NOW + 1200 })
  })

  it('문구 — 한국어', () => {
    expect(remainingText({ kind: 'hidden' })).toBeNull()
    expect(remainingText({ kind: 'few', count: 3, refillMinutes: 20 })).toBe('3번 더 물어볼 수 있어요 · 20분 뒤 다시 채워져요')
    expect(remainingText({ kind: 'few', count: 1, refillMinutes: null })).toBe('오늘 1번 더 물어볼 수 있어요')
    expect(remainingText({ kind: 'none', refillMinutes: 10 })).toBe('지금은 더 물어볼 수 없어요 · 10분 뒤 다시 채워져요')
    expect(remainingText({ kind: 'none', refillMinutes: null })).toBe('오늘은 더 물어볼 수 없어요')
  })

  it('문구 — 영어는 하나일 때 낱말이 달라진다', () => {
    chooseLanguage('en')
    expect(remainingText({ kind: 'few', count: 1, refillMinutes: null })).not.toBe(remainingText({ kind: 'few', count: 2, refillMinutes: null })?.replace('2', '1'))
    expect(remainingText({ kind: 'few', count: 2, refillMinutes: null })).toMatch(/2/)
  })
})

describe('장부 (RateLimitLedger)', () => {
  const headers = { 'RateLimit-Limit': '15', 'RateLimit-Remaining': '3', 'RateLimit-Reset': '120' }

  it('헤더 셋이 다 있고 숫자일 때만 읽는다. 이름의 대소문자는 가리지 않는다', () => {
    expect(entryFromHeaders(hdr(headers), 1000)).toEqual({ limit: 15, remaining: 3, resetsAtMs: 121000 })
    expect(entryFromHeaders(hdr({ 'ratelimit-limit': '15', 'ratelimit-remaining': '3', 'ratelimit-reset': '120' }), 0)).not.toBeNull()
    expect(entryFromHeaders(hdr({ 'RateLimit-Limit': '15', 'RateLimit-Remaining': 'x', 'RateLimit-Reset': '1' }), 0)).toBeNull()
    expect(entryFromHeaders(hdr({ 'RateLimit-Limit': '15' }), 0)).toBeNull()
  })

  it('Retry-After 는 초이고 날짜 꼴은 모르는 것이다', () => {
    expect(retryAfterOf(hdr({ 'Retry-After': '1800' }))).toBe(1800)
    expect(retryAfterOf(hdr({ 'retry-after': '40000' }))).toBe(40000)
    expect(retryAfterOf(hdr({}))).toBeNull()
    expect(retryAfterOf(hdr({ 'Retry-After': 'soon' }))).toBeNull()
    expect(intHeader(hdr({ x: ' 7 ' }), 'x')).toBe(7)
  })

  it('경로가 묶음을 정하고, 헤더 없는 응답은 적어 둔 것을 지우지 않고, 잊어도 서버가 한도를 안다는 것은 그대로다', () => {
    expect(bucketFor('/v1/guide/chat')).toBe('guide')
    expect(bucketFor('/navigation/next-leg')).toBe('navigation')
    expect(bucketFor('/cart')).toBe('general')
    ledgerStore.set({ entries: {}, seen: false })
    expect(serverKnowsLimits()).toBe(false)
    recordResponse('/guide/chat', hdr(headers), 0)
    recordResponse('/guide/chat', hdr({}), 5)
    expect(ledgerEntry('guide')).toEqual({ limit: 15, remaining: 3, resetsAtMs: 120000 })
    forgetBucket('guide')
    expect(ledgerEntry('guide')).toBeNull()
    expect(serverKnowsLimits()).toBe(true)
  })
})

describe('GuideFailure — 계약 응답별 분류', () => {
  const http = (status: number, code: string | null = null, retryAfter: number | null = null) => classifyHttp({ status, code, retryAfter })

  it('상태 코드는 계약의 갈래로', () => {
    expect(http(401, 'SIGN_IN_REQUIRED')).toEqual({ kind: 'signInRequired' })
    expect(http(401, 'ACCESS_TOKEN_INVALID')).toEqual({ kind: 'sessionExpired' })
    expect(http(401)).toEqual({ kind: 'signInRequired' })
    expect(http(400, 'INVALID_PARAMETER')).toEqual({ kind: 'badRequest' })
    expect(http(503, 'GUIDE_UNAVAILABLE')).toEqual({ kind: 'unavailable' })
    expect(http(502)).toEqual({ kind: 'unavailable' })
    expect(http(504)).toEqual({ kind: 'unavailable' })
    expect(http(500)).toEqual({ kind: 'other', status: 500 })
  })

  it('멱등 키·한도 응답', () => {
    expect(http(409, 'IDEMPOTENCY_IN_PROGRESS')).toEqual({ kind: 'timedOut' })
    expect(http(409, 'SOMETHING_ELSE')).toEqual({ kind: 'other', status: 409 })
    expect(http(422, 'IDEMPOTENCY_KEY_REUSED')).toEqual({ kind: 'keyReused' })
    expect(http(429, 'RATE_LIMITED')).toEqual({ kind: 'rateLimited' })
    expect(http(429)).toEqual({ kind: 'rateLimited' })
    expect(http(429, 'GUIDE_LIMIT_REACHED', 1800)).toEqual({ kind: 'limitReached', retryAfter: 1800 })
    expect(http(429, 'GUIDE_LIMIT_REACHED')).toEqual({ kind: 'limitReached', retryAfter: null })
    expect(http(429, 'NAVIGATION_LIMIT_REACHED', 3)).toEqual({ kind: 'other', status: 429 })
    expect(isLimit({ kind: 'limitReached', retryAfter: 5 })).toBe(true)
    expect(isLimit({ kind: 'rateLimited' })).toBe(false)
  })

  it('응답이 없을 때는 왜 없는지를 가른다', () => {
    expect(classifyNoResponse('offline')).toEqual({ kind: 'offline' })
    expect(classifyNoResponse('unreached')).toEqual({ kind: 'unreachable' })
    expect(classifyNoResponse('timedOut')).toEqual({ kind: 'timedOut' })
    expect(classifyNoResponse('interrupted')).toEqual({ kind: 'interrupted' })
  })

  it('다시 시도는 같은 키, 새 키, 또는 단추 없음', () => {
    const same: GuideFailure[] = [{ kind: 'unavailable' }, { kind: 'timedOut' }, { kind: 'offline' }, { kind: 'unreachable' }, { kind: 'rateLimited' }, { kind: 'interrupted' }, { kind: 'other', status: 500 }, { kind: 'limitReached', retryAfter: 5 }]
    for (const f of same) expect(failureRetry(f), f.kind).toBe('sameKey')
    expect(failureRetry({ kind: 'keyReused' })).toBe('newKey')
    const none: GuideFailure[] = [{ kind: 'signInRequired' }, { kind: 'sessionExpired' }, { kind: 'badRequest' }, { kind: 'other', status: 429 }, { kind: 'other', status: 404 }]
    for (const f of none) expect(failureRetry(f), f.kind).toBe('none')
  })

  it('사용자에게 개발자 말이나 「준비 중」 을 하지 않는다', () => {
    const all: GuideFailure[] = [{ kind: 'signInRequired' }, { kind: 'sessionExpired' }, { kind: 'badRequest' }, { kind: 'unavailable' }, { kind: 'timedOut' }, { kind: 'offline' }, { kind: 'unreachable' }, { kind: 'rateLimited' }, { kind: 'interrupted' }, { kind: 'keyReused' }, { kind: 'other', status: 500 }]
    for (const f of all) {
      const m = failureMessage(f)
      expect(m).not.toMatch(/8081|백엔드|준비 중/)
      expect(m.length).toBeGreaterThan(0)
    }
    expect(failureMessage({ kind: 'offline' })).not.toBe(failureMessage({ kind: 'unreachable' }))
    expect(failureMessage({ kind: 'unreachable' })).not.toBe(failureMessage({ kind: 'timedOut' }))
  })
})

describe('GuideChatRetryRules — 챗봇 턴을 다시 보낼지', () => {
  const http = (status: number, code: string | null = null, retryAfter: number | null = null): ChatOutcome => ({ kind: 'http', status, code, retryAfter })
  const lost: ChatOutcome = { kind: 'noResponse', nature: 'lost', reason: 'timedOut' }
  const inProgress = http(409, 'IDEMPOTENCY_IN_PROGRESS')
  const next = (outcome: ChatOutcome, over: Partial<Parameters<typeof nextChatStep>[0]> = {}) => nextChatStep({ keyed: true, history: newHistory(), outcome, elapsed: 0, ...over })

  /** 같은 결과가 되풀이될 때의 걸음들. 그만둘 때까지. */
  const steps = (outcome: ChatOutcome, keyed = true): ChatStep[] => {
    let history = newHistory()
    const found: ChatStep[] = []
    while (found.length < 50) {
      const step = nextChatStep({ keyed, history, outcome, elapsed: 0 })
      if (step.kind === 'stop') break
      found.push(step)
      history = recordStep(step, outcome, history)
    }
    return found
  }

  it('숫자는 한 곳에 있다', () => {
    expect(CHAT_TUNING.chatRetries).toBe(1)
    expect(CHAT_TUNING.chatAttemptTimeout).toBe(50)
    expect(CHAT_TUNING.chatBudget).toBe(110)
    expect(CHAT_TUNING.inProgressMaxPolls).toBe(20)
  })

  it('끊김·502·503·504 는 자동으로 한 번만 다시 보낸다', () => {
    for (const o of [lost, http(502), http(503), http(504), http(500)] as ChatOutcome[]) {
      expect(steps(o), JSON.stringify(o)).toEqual([{ kind: 'retry', after: 1, forLimit: false }])
    }
  })

  it('4xx·유료 한도·오프라인은 다시 보내지 않는다', () => {
    const offline: ChatOutcome = { kind: 'noResponse', nature: 'final', reason: 'offline' }
    for (const o of [http(400), http(401), http(404), http(422, 'IDEMPOTENCY_KEY_REUSED'), http(429, 'GUIDE_LIMIT_REACHED', 30), http(409, 'OTHER'), offline]) {
      expect(steps(o), JSON.stringify(o)).toEqual([])
    }
  })

  it('분당 한도는 Retry-After 만큼 한 번 기다리고 60초 넘으면 그만둔다', () => {
    const o = http(429, 'RATE_LIMITED', 7)
    expect(next(o)).toEqual({ kind: 'retry', after: 7, forLimit: true })
    expect(next(http(429, null, null))).toEqual({ kind: 'retry', after: 1, forLimit: true })
    expect(next(http(429, 'RATE_LIMITED', 61))).toEqual({ kind: 'stop' })
    expect(next(o, { jitter: 1 })).toEqual({ kind: 'retry', after: 8, forLimit: true })
    expect(steps(o)).toHaveLength(1)
  })

  it('처리 중(409)은 3초마다 같은 키로 되묻고 재시도 횟수와 따로 센다', () => {
    expect(next(inProgress)).toEqual({ kind: 'poll', after: 3 })
    // 55초 벽이 먼저 닿는다 — 3초 간격이면 열여덟 번
    expect(steps(inProgress).filter((s) => s.kind === 'poll').length).toBe(20)
    let history = newHistory()
    history = recordStep({ kind: 'poll', after: 3 }, inProgress, history)
    expect(history).toMatchObject({ polls: 1, retries: 0, mayHaveReached: true })
    // 되묻다 실패해도 자동 한 번은 남아 있다
    expect(nextChatStep({ keyed: true, history, outcome: lost, elapsed: 5 })).toEqual({ kind: 'retry', after: 1, forLimit: false })
  })

  it('되묻기는 턴 벽과 서버가 키를 잊는 1분 앞에서 멈춘다', () => {
    expect(next(inProgress, { elapsed: 108 })).toEqual({ kind: 'stop' })
    expect(next(inProgress, { elapsed: 51, keyAge: 51 })).toEqual({ kind: 'poll', after: 3 })
    expect(next(inProgress, { elapsed: 52, keyAge: 52 })).toEqual({ kind: 'stop' })
    // 「다시 시도」 로 새 요청이 나가도 키를 처음 보낸 때부터 센다
    expect(next(inProgress, { elapsed: 2, keyAge: 52 })).toEqual({ kind: 'stop' })
    expect(next(inProgress, { elapsed: 40, keyAge: 10 })).toEqual({ kind: 'poll', after: 3 })
  })

  it('키가 없으면 어떤 실패도 다시 보내거나 되묻지 않는다', () => {
    for (const o of [lost, http(503), inProgress, http(429, 'RATE_LIMITED', 3)]) expect(steps(o, false)).toEqual([])
  })

  it('다시 보내기의 이유가 화면 말을 정한다', () => {
    expect(resendReason({ kind: 'poll', after: 3 })).toBe('inProgress')
    expect(resendReason({ kind: 'retry', after: 7, forLimit: true })).toBe('rateLimited')
    expect(resendReason({ kind: 'retry', after: 1, forLimit: false })).toBe('failed')
    expect(resendReason({ kind: 'stop' })).toBeNull()
  })
})

describe('가이드 입구와 떠 있는 동그라미 (RouteGuideChipTests)', () => {
  it('열린 카드가 있으면 안내 중에도 떠 있는 입구, 서랍·핀 찍기 중에는 숨긴다', () => {
    expect(guideEntryPlacement({ guiding: true, hasTarget: true, hasCard: true, panelOpen: false, pinning: false })).toBe('floating')
    expect(guideEntryPlacement({ guiding: true, hasTarget: true, hasCard: true, panelOpen: true, pinning: false })).toBe('hidden')
    expect(guideEntryPlacement({ guiding: true, hasTarget: true, panelOpen: false, pinning: false })).toBe('tripHeader')
    for (const [guiding, hasTarget] of [[false, false], [false, true], [true, false]] as const) expect(guideEntryPlacement({ guiding, hasTarget, panelOpen: false, pinning: false })).toBe('floating')
    for (const guiding of [false, true]) for (const [panelOpen, pinning] of [[true, false], [false, true], [true, true]] as const) expect(guideEntryPlacement({ guiding, hasTarget: true, panelOpen, pinning })).toBe('hidden')
  })

  it('세로 — 카드가 없으면 둔 자리 그대로, 있으면 윗변 위 8pt, 이미 더 위면 그대로', () => {
    expect(chipOffsetY({ stored: 0, cardTop: null })).toBe(0)
    expect(chipOffsetY({ stored: -120, cardTop: null })).toBe(-120)
    expect(chipOffsetY({ stored: 0, cardTop: 400 })).toBe(-332)
    expect(chipOffsetY({ stored: -500, cardTop: 400 })).toBe(-500)
  })

  it('카드가 뜬 채 끌면 비켜 선 자리에서부터 손가락을 따라오고, 놓으면 그 자리가 남는다', () => {
    expect(chipOffsetY({ stored: 0, dragging: -50, cardTop: 400 })).toBe(-382)
    expect(chipOffsetY({ stored: 0, dragging: 30, cardTop: 400 })).toBe(-302)
    const dropped = chipOffsetY({ stored: 0, dragging: -50, cardTop: 400 })
    expect(chipOffsetY({ stored: dropped, cardTop: 400 })).toBe(-382)
    expect(chipOffsetY({ stored: dropped, cardTop: null })).toBe(-382)
    expect(chipOffsetY({ stored: -120, dragging: 20, cardTop: null })).toBe(-100)
  })

  it('가만히 눌렀다 떼는 것은 옮긴 것이 아니다', () => {
    expect(chipMoved(0, 0)).toBe(false)
    expect(chipMoved(2, -3)).toBe(false)
    expect(chipMoved(0, -6)).toBe(true)
    expect(chipMoved(-40, 10)).toBe(true)
  })

  const stay = clearanceOf({ x: 322, y: 471, width: 63, height: 35 }, { x: 0, y: 0, width: 402, height: 874 })
  const offX = (storedX: number, storedY: number, cardTop: number | null, dragging = 0) => chipOffsetX({ storedX, storedY, dragging, cardTop, clear: stay })

  it('「머무는 시간 ›」 단추의 자리는 같은 구석에서 잰다', () => {
    expect(stay).toEqual({ left: 80, low: 368, high: 403 })
    expect(clearanceOf(null, { x: 0, y: 0, width: 402, height: 874 })).toBeNull()
  })

  it('올라선 동그라미가 단추와 겹치면 단추 왼쪽으로 8pt 더 비킨다', () => {
    expect(offX(0, 0, 347)).toBe(-76)
    expect(12 - offX(0, 0, 347)).toBe(88)
  })

  it('카드가 없거나 단추 줄과 높이가 다르면 옆으로 가지 않는다', () => {
    expect(offX(0, 0, null)).toBe(0)
    expect(offX(0, 0, 200)).toBe(0)
    expect(offX(0, 0, 400)).toBe(0)
  })

  it('사용자가 둔 자리는 건드리지 않고, 끄는 만큼은 비킨 자리에 더한다', () => {
    expect(offX(-150, 0, 347)).toBe(-150)
    expect(offX(0, -300, 347)).toBe(0)
    const dropped = offX(0, 0, 347, -30)
    expect(dropped).toBe(-106)
    expect(offX(dropped, 0, 347)).toBe(-106)
    expect(offX(dropped, 0, null)).toBe(-106)
  })

  it('화면 밖으로 못 나간다', () => {
    expect(clampChip({ x: 50, y: 50 }, { width: 402, height: 874 })).toEqual({ x: 0, y: 0 })
    expect(clampChip({ x: -999, y: -999 }, { width: 402, height: 874 })).toEqual({ x: -(402 - 12 - 180), y: -(874 - 76 - 60) })
  })
})

describe('화면 명령 (GuideUiDirective)', () => {
  it('아는 op 여섯은 갈래로', () => {
    expect(parseDirective({ op: 'map.focus', placeIds: [1, 2] })).toEqual({ kind: 'mapFocus', placeIds: [1, 2] })
    expect(parseDirective({ op: 'route.draw', placeIds: [3] })).toEqual({ kind: 'routeDraw', placeIds: [3] })
    expect(parseDirective({ op: 'place.card', placeId: 9 })).toEqual({ kind: 'placeCard', placeId: 9 })
    expect(parseDirective({ op: 'course.open', day: 2 })).toEqual({ kind: 'courseOpen', day: 2 })
    expect(parseDirective({ op: 'course.focus', day: 1, changed: ['개뿔'] })).toEqual({ kind: 'courseFocus', day: 1, changed: ['개뿔'] })
    expect(parseDirective({ op: 'sheet.collapse' })).toEqual({ kind: 'sheetCollapse' })
  })

  it('값이 빠진 명령과 모르는 op 는 조용히 무시한다', () => {
    for (const op of ['place.card', 'course.open', 'map.focus']) expect(parseDirective({ op })).toBeNull()
    expect(parseDirective({ op: 'hologram.show', day: 3 })).toBeNull()
  })
})

describe('검색 기준 (RouteGuideAnchor)', () => {
  const center = { latitude: 35.2, longitude: 129.1 }
  const device = { latitude: 37.5, longitude: 127 }
  it('지도 우선이면 지도 중심, 아니면 내 위치, 그것도 없으면 지도 중심', () => {
    expect(resolveAnchor({ current: device, mapCenter: center, preferMap: true })).toEqual({ spot: center, isMapCenter: true, source: 'userSelected' })
    expect(resolveAnchor({ current: device, mapCenter: center, preferMap: false })).toEqual({ spot: device, isMapCenter: false, source: 'device' })
    expect(resolveAnchor({ current: null, mapCenter: center, preferMap: false })).toMatchObject({ isMapCenter: true })
    expect(resolveAnchor({ current: null, mapCenter: null, preferMap: true })).toBeNull()
  })
  it('깨진 좌표는 쓰지 않는다', () => {
    expect(resolveAnchor({ current: { latitude: NaN, longitude: 1 }, mapCenter: center, preferMap: false })?.isMapCenter).toBe(true)
    expect(resolveAnchor({ current: null, mapCenter: { latitude: 95, longitude: 1 }, preferMap: true })).toBeNull()
  })
})
