import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.hoisted(() => {
  const env = import.meta.env as Record<string, string>
  env.VITE_API_BASE = 'http://test.local'
})

import { chooseLanguage } from '@/i18n'
import { api } from './client'

let langs: Array<string | null>

beforeEach(() => {
  langs = []
  chooseLanguage('en')
  vi.stubGlobal(
    'fetch',
    vi.fn(async (req: Request) => {
      langs.push(req.headers.get('Accept-Language'))
      return new Response(JSON.stringify({ items: [], total: 0, limit: 1, offset: 0 }), { status: 200, headers: { 'Content-Type': 'application/json' } })
    }),
  )
})
afterEach(() => vi.unstubAllGlobals())

describe('api client — Accept-Language', () => {
  it('주지 않으면 앱 언어를 싣는다', async () => {
    await api.GET('/places', { params: { query: { limit: 1 } } })
    expect(langs).toEqual(['en'])
  })

  it('호출부가 준 값은 덮어쓰지 않는다(도시 열쇠용 한국어 목록, MZ2AZ-531)', async () => {
    await api.GET('/places', { params: { header: { 'Accept-Language': 'ko' }, query: { limit: 1 } } })
    expect(langs).toEqual(['ko'])
  })
})
