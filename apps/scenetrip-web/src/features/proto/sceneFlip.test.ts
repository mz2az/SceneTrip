import { describe, expect, it } from 'vitest'
import type { components } from '@/api/generated/schema'
import { peekCardIndex, pickSceneImage } from './sceneFlip'

type Scene = components['schemas']['Scene']

const scene = (contentId: number, sceneImageUrls: string[] = []): Scene => ({
  contentId,
  contentTitle: `작품${contentId}`,
  sceneImageUrls,
})
const detail = (...scenes: Scene[]) => ({ scenes })

describe('pickSceneImage — 뒷면 장면 그림 고르기', () => {
  it('카드 작품과 같은 장면의 첫 장', () => {
    const d = detail(scene(1, ['https://x/1a.png']), scene(2, ['https://x/2a.png']))
    expect(pickSceneImage(d, 2)).toBe('https://x/2a.png')
  })

  it('맞는 장면에 그림이 여러 장이면 첫 장만', () => {
    const d = detail(scene(7, ['https://x/7a.png', 'https://x/7b.png', 'https://x/7c.png']))
    expect(pickSceneImage(d, 7)).toBe('https://x/7a.png')
  })

  it('맞는 장면이 앞의 다른 장면보다 우선한다(순서가 아니라 작품)', () => {
    const d = detail(scene(1, ['https://x/1a.png']), scene(3, ['https://x/3a.png', 'https://x/3b.png']))
    expect(pickSceneImage(d, 3)).toBe('https://x/3a.png')
  })

  it('맞는 장면에 그림이 없으면 그림이 있는 첫 장면', () => {
    const d = detail(scene(1, []), scene(2, []), scene(5, ['https://x/5a.png']), scene(6, ['https://x/6a.png']))
    expect(pickSceneImage(d, 2)).toBe('https://x/5a.png')
  })

  it('작품 번호가 없으면(undefined) 그림이 있는 첫 장면', () => {
    const d = detail(scene(1, []), scene(4, ['https://x/4a.png', 'https://x/4b.png']))
    expect(pickSceneImage(d, undefined)).toBe('https://x/4a.png')
  })

  it('작품 번호가 null 이어도 그림이 있는 첫 장면', () => {
    const d = detail(scene(9, ['https://x/9a.png']))
    expect(pickSceneImage(d, null)).toBe('https://x/9a.png')
  })

  it('작품과 맞는 장면이 아예 없으면 그림이 있는 첫 장면', () => {
    const d = detail(scene(1, []), scene(2, ['https://x/2a.png']))
    expect(pickSceneImage(d, 99)).toBe('https://x/2a.png')
  })

  it('어느 장면에도 그림이 없으면 null', () => {
    expect(pickSceneImage(detail(scene(1, []), scene(2, [])), 1)).toBeNull()
  })

  it('장면이 비었으면 null', () => {
    expect(pickSceneImage(detail(), 1)).toBeNull()
  })

  it('scenes 필드가 없거나 상세가 없으면 null', () => {
    expect(pickSceneImage({}, 1)).toBeNull()
    expect(pickSceneImage(null, 1)).toBeNull()
    expect(pickSceneImage(undefined, undefined)).toBeNull()
  })
})

describe('peekCardIndex — 처음 한 번 살짝 돌 카드', () => {
  it('첫 카드가 뒤집히면 0', () => {
    expect(peekCardIndex([true, true, false], false, false)).toBe(0)
  })

  it('뒤집히는 첫 카드가 맨 앞이 아니면 그 자리', () => {
    expect(peekCardIndex([false, false, true, true], false, false)).toBe(2)
  })

  it('한 번이라도 뒤집은 적이 있으면 null', () => {
    expect(peekCardIndex([true, true], true, false)).toBeNull()
    expect(peekCardIndex([false, true], true, false)).toBeNull()
  })

  it('동작 줄이기면 null', () => {
    expect(peekCardIndex([true, true], false, true)).toBeNull()
    expect(peekCardIndex([false, true], false, true)).toBeNull()
  })

  it('뒤집은 적도 있고 동작 줄이기여도 null', () => {
    expect(peekCardIndex([true], true, true)).toBeNull()
  })

  it('뒤집히는 카드가 없으면 null', () => {
    expect(peekCardIndex([false, false, false], false, false)).toBeNull()
  })

  it('카드가 없으면 null', () => {
    expect(peekCardIndex([], false, false)).toBeNull()
  })
})
