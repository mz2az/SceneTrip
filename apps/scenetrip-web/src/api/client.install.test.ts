import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

// Node 의 Request 는 상대 주소를 받지 못한다 — 시험에서는 절대 기준 주소를 준다(client.ts 가 읽기 전에).
vi.hoisted(() => {
  const env = import.meta.env as Record<string, string>
  env.VITE_API_BASE = 'http://test.local'
  // .env.local 의 개발 안전장치는 켜 두면 이 시험의 쓰기 요청을 막는다 — 가짜 fetch 라 서버에는 아무것도 가지 않는다.
  env.VITE_BLOCK_WRITES = ''
  env.VITE_BLOCK_PATHS = ''
})

import { authHooks, clearSession, dismissSignInPrompt, setSession } from '@/state/auth'
import { installId } from '@/state/install'
import { api, installHeader } from './client'

// 가입 계정에 묶인 설치 번호 — 토큰을 잃은 브라우저가 그 번호로 토큰 없이 부르면 서버가 영영 401 SESSION_REQUIRED 를 준다.
// 클라이언트는 그 번호를 버리고 새 번호로 바꾼 뒤, 조회(GET)만 한 번 다시 보낸다.
const json = (status: number, body: unknown) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
const sessionRequired = () => json(401, { code: 'SESSION_REQUIRED' })

const KEY = 'scenetrip.installId'
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

let calls: Array<{ url: string; method: string; installId: string | null; auth: string | null }>
/** 요청마다 응답을 고른다. 기본은 200 빈 목록. */
let respond: (req: Request) => Response

beforeEach(() => {
  calls = []
  respond = () => json(200, { items: [], totalCount: 0 })
  clearSession()
  authHooks.refresh = null
  authHooks.loseSession = null
  dismissSignInPrompt()
  vi.stubGlobal(
    'fetch',
    vi.fn(async (req: Request) => {
      calls.push({ url: new URL(req.url).pathname, method: req.method, installId: req.headers.get('X-Install-Id'), auth: req.headers.get('Authorization') })
      return respond(req)
    }),
  )
})
afterEach(() => vi.unstubAllGlobals())

/** 이 번호로 나간 요청에만 SESSION_REQUIRED 를 준다(서버가 그 번호를 가입 계정에 묶어 둔 상태). */
const boundTo = (bound: string) => (req: Request) => (req.headers.get('X-Install-Id') === bound ? sessionRequired() : json(200, { items: [], totalCount: 0 }))

describe('api client — 가입 계정에 묶인 설치 번호', () => {
  it('토큰 없이 보낸 조회가 SESSION_REQUIRED 를 받으면 설치 번호를 새 UUID 로 바꾸고 새 번호로 한 번 다시 보낸다', async () => {
    const old = installId()
    respond = boundTo(old)
    const res = await api.GET('/courses', { params: { header: installHeader() } })

    const renewed = installId()
    expect(renewed).not.toBe(old)
    expect(renewed).toMatch(UUID)
    expect(localStorage.getItem(KEY)).toBe(renewed)
    // 처음은 낡은 번호, 다시 보낸 것은 새 번호 — 호출하는 쪽이 미리 만든 헤더 값을 갈아 끼운다.
    expect(calls.map((c) => c.installId)).toEqual([old, renewed])
    expect(calls.every((c) => c.auth === null)).toBe(true)
    // 호출하는 쪽은 다시 보낸 응답을 받는다.
    expect(res.response.status).toBe(200)
    expect(res.data).toEqual({ items: [], totalCount: 0 })
  })

  it('다시 보낸 것도 SESSION_REQUIRED 면 그 401 을 돌려주고 더 바꾸거나 보내지 않는다', async () => {
    const old = installId()
    respond = () => sessionRequired()
    const res = await api.GET('/courses', { params: { header: installHeader() } })

    expect(res.response.status).toBe(401)
    expect(calls).toHaveLength(2)
    const renewed = installId()
    expect(renewed).not.toBe(old)
    // 번호는 한 번만 바뀌었다 — 다시 보낸 요청의 번호가 지금 번호다.
    expect(calls[1].installId).toBe(renewed)
  })

  it('같은 낡은 번호로 동시에 나간 조회 여럿이 모두 SESSION_REQUIRED 를 받아도 번호는 한 번만 바뀌고 모두 같은 새 번호로 다시 나간다', async () => {
    const old = installId()
    respond = boundTo(old)
    const header = installHeader()
    const results = await Promise.all([
      api.GET('/courses', { params: { header } }),
      api.GET('/courses', { params: { header } }),
      api.GET('/courses', { params: { header } }),
    ])

    expect(results.map((r) => r.response.status)).toEqual([200, 200, 200])
    const renewed = installId()
    expect(renewed).not.toBe(old)
    const resent = calls.filter((c) => c.installId !== old)
    expect(calls.filter((c) => c.installId === old)).toHaveLength(3)
    expect(resent).toHaveLength(3)
    expect(new Set(resent.map((c) => c.installId))).toEqual(new Set([renewed]))
  })

  it('쓰기(POST /events)가 SESSION_REQUIRED 를 받으면 번호는 바꾸되 다시 보내지 않고 401 을 돌려준다', async () => {
    const old = installId()
    respond = boundTo(old)
    const res = await api.POST('/events', { params: { header: installHeader() }, body: { type: 'view', entityType: 'place', entityId: 1 } })

    expect(res.response.status).toBe(401)
    expect(calls).toHaveLength(1)
    const renewed = installId()
    expect(renewed).not.toBe(old)
    expect(renewed).toMatch(UUID)
    expect(localStorage.getItem(KEY)).toBe(renewed)
  })

  it('로그인한 채(Authorization 을 실어) 받은 SESSION_REQUIRED 는 번호를 바꾸지 않는다 — 기존 로그아웃 처리가 맡는다', async () => {
    setSession({ accessToken: 'tok', refreshToken: 'r1' })
    const lost = vi.fn()
    authHooks.loseSession = lost
    const old = installId()
    respond = () => sessionRequired()
    const res = await api.GET('/courses', { params: { header: installHeader() } })

    expect(res.response.status).toBe(401)
    expect(calls).toHaveLength(1)
    expect(calls[0].auth).toBe('Bearer tok')
    expect(installId()).toBe(old)
    expect(lost).toHaveBeenCalledTimes(1)
  })

  it('401 이 도착할 즈음 이미 로그인했으면(그 사이 세션이 생겼다) 번호를 바꾸지 않는다', async () => {
    const old = installId()
    respond = () => {
      // 토큰 없이 나간 요청이 서버에 있는 사이 사용자가 로그인했다.
      setSession({ accessToken: 'tok', refreshToken: 'r1' })
      return sessionRequired()
    }
    await api.GET('/courses', { params: { header: installHeader() } })

    expect(calls[0].auth).toBeNull()
    expect(installId()).toBe(old)
  })
})
