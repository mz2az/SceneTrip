import { describe, expect, it } from 'vitest'
import { decide, laneFor } from './retry'

const noJitter = () => 0.5

describe('retry.decide', () => {
  it('조회는 503 에 세 번까지 다시 보낸다', () => {
    expect(decide('read', { kind: 'status', status: 503 }, 0, noJitter)).toEqual({ retry: true, waitSeconds: 1 })
    expect(decide('read', { kind: 'status', status: 503 }, 2, noJitter)).toEqual({ retry: true, waitSeconds: 4 })
    expect(decide('read', { kind: 'status', status: 503 }, 3, noJitter)).toEqual({ retry: false })
  })
  it('400/404 는 다시 보내지 않는다', () => {
    expect(decide('read', { kind: 'status', status: 404 }, 0, noJitter)).toEqual({ retry: false })
  })
  it('POST 는 서버에 닿았을 수 있으면 다시 보내지 않는다', () => {
    expect(decide('unsafeWrite', { kind: 'network', reachedServer: true }, 0, noJitter)).toEqual({ retry: false })
    expect(decide('unsafeWrite', { kind: 'status', status: 503 }, 0, noJitter)).toEqual({ retry: false })
    expect(decide('unsafeWrite', { kind: 'network', reachedServer: false }, 0, noJitter).retry).toBe(true)
  })
  it('429 는 Retry-After 만큼 기다리되 60초를 넘으면 그만둔다', () => {
    expect(decide('read', { kind: 'status', status: 429, retryAfter: 20 }, 0, noJitter)).toEqual({ retry: true, waitSeconds: 20.5 })
    expect(decide('read', { kind: 'status', status: 429, retryAfter: 3600 }, 0, noJitter)).toEqual({ retry: false })
  })
  it('메서드로 갈래를 가른다', () => {
    expect(laneFor('get')).toBe('read')
    expect(laneFor('PUT')).toBe('idempotentWrite')
    expect(laneFor('POST')).toBe('unsafeWrite')
  })
})
