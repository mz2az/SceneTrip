/*
 * 넘겨받은 익명 id — 정본: docs/project/plans/analytics-events.md 「이벤트 보강 (2026-11-16, MZ2AZ-586)」 귀속 1.
 *
 *   adoptAnonId(candidate, token, read?, write?) → boolean
 *     candidate 가 [A-Za-z0-9-]{8,64} 이고, 이 브라우저에 PostHog 라이브러리 id 도 우리 익명 id 도 없을 때만
 *     ANON_ID_KEY 에 candidate 를 적고 true. 그 밖은 아무것도 적지 않고 false.
 *   적은 뒤에는 anonId(), bootstrapDistinctId() 가 그 id 를 돌려준다.
 */
import { describe, expect, it } from 'vitest'
import { ANON_ID_KEY, adoptAnonId, anonId, bootstrapDistinctId } from './anonId'

const TOKEN = 'phc_test'

function memory() {
  const m = new Map<string, string>()
  return { m, read: (k: string) => m.get(k) ?? null, write: (k: string, v: string) => void m.set(k, v) }
}

describe('adoptAnonId', () => {
  it('비어 있는 브라우저면 넘겨받은 id 를 적고 true — 이후 anonId, bootstrap 이 그 id', () => {
    const { m, read, write } = memory()
    const aid = '0b6f1c2e-3a4d-4e5f-8a9b-1c2d3e4f5a6b'
    expect(adoptAnonId(aid, TOKEN, read, write)).toBe(true)
    expect(m.get(ANON_ID_KEY)).toBe(aid)
    expect(anonId(TOKEN, read, write, () => 'fresh')).toBe(aid)
    expect(bootstrapDistinctId(TOKEN, read, write)).toBe(aid)
  })

  it('null 이면 false, 아무것도 적지 않는다', () => {
    const { m, read, write } = memory()
    expect(adoptAnonId(null, TOKEN, read, write)).toBe(false)
    expect(m.size).toBe(0)
  })

  it.each([
    ['짧음(7자)', 'abcdefg'],
    ['김(65자)', 'a'.repeat(65)],
    ['빈 문자열', ''],
    ['밑줄', 'abc_defgh'],
    ['공백', 'abcd efgh'],
    ['기호', 'abcdefgh<script>'],
    ['한글', '가나다라마바사아'],
  ])('형식이 아니면(%s) false, 적지 않는다', (_label, candidate) => {
    const { m, read, write } = memory()
    expect(adoptAnonId(candidate, TOKEN, read, write)).toBe(false)
    expect(m.has(ANON_ID_KEY)).toBe(false)
  })

  it.each([
    ['8자', 'abcdEF12'],
    ['64자', 'a'.repeat(64)],
    ['붙임표', 'abc-def-123'],
  ])('경계 안(%s)이면 받는다', (_label, candidate) => {
    const { read, write } = memory()
    expect(adoptAnonId(candidate, TOKEN, read, write)).toBe(true)
  })

  it('PostHog 라이브러리 id 가 이미 있으면 false — 우리 열쇠도 적지 않는다', () => {
    const { m, read, write } = memory()
    m.set('ph_phc_test_posthog', JSON.stringify({ distinct_id: 'lib-id' }))
    expect(adoptAnonId('handed-over-1', TOKEN, read, write)).toBe(false)
    expect(m.has(ANON_ID_KEY)).toBe(false)
    expect(anonId(TOKEN, read, write)).toBe('lib-id')
  })

  it('우리 익명 id 가 이미 있으면 false — 덮어쓰지 않는다', () => {
    const { m, read, write } = memory()
    m.set(ANON_ID_KEY, 'mine-already')
    expect(adoptAnonId('handed-over-1', TOKEN, read, write)).toBe(false)
    expect(m.get(ANON_ID_KEY)).toBe('mine-already')
  })
})
