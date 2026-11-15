/*
 * 홈 첫 카드(담은 것이 없는 사람) — 정본: docs/project/plans/web-ad-landing.md §13-2.
 *
 *   배경 = 그 작품의 첫 촬영지의 실제 사진(우리 사진, imageThumbnailUrl ?? imageUrl). 장면 그림은 쓰지 않는다 — /places/{id} 를 부르지 않는다.
 *   작품 공식 포스터를 작은 세로 카드로 겹친다. 글: 「가장 인기 있는 작품」, 작품명, 「{도시}의 실제 장소 {N}곳」, 단추 「이 작품의 장소 N곳 보기」.
 */
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const h = vi.hoisted(() => ({ GET: vi.fn() }))
vi.mock('@/api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/client')>()),
  api: { GET: (...args: unknown[]) => h.GET(...args), POST: vi.fn(), DELETE: vi.fn() },
}))

import type { components } from '@/api/generated/schema'
import { chooseLanguage } from '@/i18n'
import { ShowHero } from './HomeStart'

type ContentSummary = components['schemas']['ContentSummary']

const respond = (data: unknown) => ({ data, error: undefined, response: { ok: true, status: 200, headers: new Headers() } })

let nextId = 500
const work = (): ContentSummary =>
  ({ id: nextId++, category: 'movie', title: '케이팝 데몬 헌터스', posterUrl: 'https://x/poster.jpg', placeCount: 3 }) as ContentSummary

let items: { id: number; name: string; imageUrl: string | null; imageThumbnailUrl: string | null; address: string }[]

beforeEach(() => {
  chooseLanguage('ko')
  items = [
    { id: 1, name: '낙산공원', imageUrl: 'https://x/naksan.jpg', imageThumbnailUrl: 'https://x/naksan-s.jpg', address: '서울특별시 종로구' },
    { id: 2, name: '남산', imageUrl: 'https://x/namsan.jpg', imageThumbnailUrl: null, address: '서울특별시 중구' },
    { id: 3, name: '북촌', imageUrl: null, imageThumbnailUrl: null, address: '서울특별시 종로구' },
  ]
  h.GET.mockReset()
  h.GET.mockImplementation(async (path: string) => {
    if (path === '/contents/{contentId}/places') return respond({ items, total: items.length, limit: 100, offset: 0 })
    return respond({})
  })
})

afterEach(() => {
  cleanup()
  chooseLanguage('ko')
})

async function show(w = work()) {
  const onOpen = vi.fn()
  render(<ShowHero work={w} onOpen={onOpen} />)
  await act(async () => {
    await Promise.resolve()
    await Promise.resolve()
  })
  return onOpen
}

describe('홈 첫 카드(§13-2)', () => {
  it('배경은 첫 촬영지의 실제 사진(작은 판) — 장면 그림을 받지 않는다(/places/{id} 0)', async () => {
    await show()
    const bg = document.querySelector('img[data-hero="place"]')
    expect(bg?.getAttribute('src')).toBe('https://x/naksan-s.jpg')
    expect(h.GET.mock.calls.some((c) => c[0] === '/places/{placeId}')).toBe(false)
    expect(h.GET.mock.calls.filter((c) => c[0] === '/contents/{contentId}/places')).toHaveLength(1)
  })

  it('첫 촬영지에 사진이 없으면 사진이 있는 다음 촬영지(작은 판이 없으면 원본)', async () => {
    items[0] = { ...items[0]!, imageUrl: null, imageThumbnailUrl: null }
    await show()
    expect(document.querySelector('img[data-hero="place"]')?.getAttribute('src')).toBe('https://x/namsan.jpg')
  })

  it('공식 포스터를 작은 세로 카드로 겹친다', async () => {
    await show()
    const poster = document.querySelector('[data-hero="poster"] img')
    expect(poster?.getAttribute('src')).toBe('https://x/poster.jpg')
  })

  it('글 — 칩·작품명·「서울의 실제 장소 3곳」·단추', async () => {
    const onOpen = await show()
    expect(screen.getByText('가장 인기 있는 작품')).toBeTruthy()
    expect(screen.getByText('케이팝 데몬 헌터스')).toBeTruthy()
    expect(screen.getByText('서울의 실제 장소 3곳')).toBeTruthy()
    expect(document.body.textContent).not.toContain('이 장면, 진짜 있는 곳이에요')
    fireEvent.click(screen.getByText('이 작품의 장소 3곳 보기').closest('button')!)
    expect(onOpen).toHaveBeenCalledTimes(1)
  })

  it('영어 — 「3 real places in Seoul」 · 「See 3 places from this show」', async () => {
    chooseLanguage('en')
    await show()
    expect(screen.getByText('Most popular show')).toBeTruthy()
    expect(screen.getByText('3 real places in Seoul')).toBeTruthy()
    expect(screen.getByText('See 3 places from this show')).toBeTruthy()
  })
})
