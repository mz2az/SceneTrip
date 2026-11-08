// 정한 여행 맞추기 — 기기에 남은 것과 계정(/me/trip)에 저장된 것 (MZ2AZ-542)
import { describe, expect, it } from 'vitest'
import { type ProtoTrip, reconcileTrip } from './protoState'

const local: ProtoTrip = { city: '부산', days: 2, pace: 'relaxed', courseId: null }
const server: ProtoTrip = { city: '서울', days: 3, pace: 'packed', courseId: 12 }

describe('reconcileTrip', () => {
  it('서버에 있으면 서버 것을 쓰고 올리지 않는다', () => {
    expect(reconcileTrip(null, server)).toEqual({ use: server, push: false })
  })

  it('기기 것과 달라도 서버가 이긴다', () => {
    expect(reconcileTrip(local, server)).toEqual({ use: server, push: false })
  })

  it('서버에 없고 기기에 있으면 기기 것을 쓰고 서버로 올린다', () => {
    expect(reconcileTrip(local, null)).toEqual({ use: local, push: true })
  })

  it('둘 다 없으면 없음이고 올리지 않는다', () => {
    expect(reconcileTrip(null, null)).toEqual({ use: null, push: false })
  })
})
