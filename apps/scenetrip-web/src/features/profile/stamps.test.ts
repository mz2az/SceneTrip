import { describe, expect, it } from 'vitest'
import type { components } from '@/api/generated/schema'
import { collectStamps, stampTilt, stampsOf } from './stamps'

type Course = components['schemas']['CourseSummary']
type Detail = components['schemas']['CourseDetail']

const course = (id: number, title: string): Course => ({ id, title, dayCount: 1, status: 'upcoming', origin: 'self', placeCount: 2, createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z' }) as Course
const detail = (c: Course, items: Array<{ id: number; name: string; visitedAt?: string | null; work?: string | null }>): Detail =>
  ({
    ...c,
    days: [
      {
        dayNumber: 1,
        dwellMinutes: 0, travelMinutes: 0, totalMinutes: 0, travelBasis: 'straight',
        items: items.map((i) => ({ id: i.id, source: 'place', name: i.name, latitude: 0, longitude: 0, dwellMinutes: 30, visitedAt: i.visitedAt ?? null, sourceContentTitle: i.work ?? null })),
      },
    ],
  }) as unknown as Detail

describe('VisitStamp', () => {
  it('방문 시각이 찍힌 곳만 모은다 — 안 간 곳은 도장이 아니다', () => {
    const c = course(1, '서울 코스')
    const stamps = stampsOf(c, detail(c, [{ id: 1, name: 'N서울타워', visitedAt: '2026-10-01T03:00:00Z', work: '이태원 클라쓰' }, { id: 2, name: '남산', visitedAt: null }]))
    expect(stamps).toHaveLength(1)
    expect(stamps[0]).toMatchObject({ id: 1, name: 'N서울타워', workTitle: '이태원 클라쓰', courseTitle: '서울 코스' })
  })

  it('코스마다 상세를 받아 합치고 최근 것부터 정렬한다', async () => {
    const a = course(1, 'A')
    const b = course(2, 'B')
    const details: Record<number, Detail> = {
      1: detail(a, [{ id: 10, name: '가', visitedAt: '2026-10-01T00:00:00Z' }]),
      2: detail(b, [{ id: 20, name: '나', visitedAt: '2026-10-05T00:00:00Z' }, { id: 21, name: '다', visitedAt: '2026-10-03T00:00:00Z' }]),
    }
    const stamps = await collectStamps([a, b], async (id) => details[id])
    expect(stamps.map((s) => s.name)).toEqual(['나', '다', '가'])
  })

  it('상세를 못 받은 코스는 건너뛰고 나머지를 보인다', async () => {
    const a = course(1, 'A')
    const b = course(2, 'B')
    const stamps = await collectStamps([a, b], async (id) => (id === 1 ? null : detail(b, [{ id: 20, name: '나', visitedAt: '2026-10-05T00:00:00Z' }])))
    expect(stamps.map((s) => s.name)).toEqual(['나'])
  })

  it('기울기는 −3°~+3° 이고 같은 도장은 늘 같다', () => {
    for (let id = 0; id < 30; id++) {
      const t = stampTilt(id)
      expect(t).toBeGreaterThanOrEqual(-3)
      expect(t).toBeLessThanOrEqual(3)
      expect(stampTilt(id)).toBe(t)
    }
  })
})
