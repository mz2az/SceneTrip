import { describe, expect, it } from 'vitest'
import type { components } from '@/api/generated/schema'
import { type FlipFaces, flipFaces, flipPill, peekCardIndex, pickSceneImage, usablePhotoUrl } from './sceneFlip'

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

// ---- 모든 카드가 뒤집힌다(§6 표, 2026-11-10 사용자 결정 — 「장소 사진이 없으면 뒤집지 않는다」 를 바꿨다)

const PHOTO = 'https://img.example.com/place.jpg'
const SCENE = 'https://x/scene.png'

describe('usablePhotoUrl — 쓸 수 있는 장소 사진 주소', () => {
  it('https·http 에 호스트가 있으면 쓴다', () => {
    expect(usablePhotoUrl(PHOTO)).toBe(true)
    expect(usablePhotoUrl('http://img.example.com/place.jpg')).toBe(true)
    expect(usablePhotoUrl('HTTPS://img.example.com/a.jpg')).toBe(true)
  })

  it('없거나 비었으면 못 쓴다', () => {
    expect(usablePhotoUrl(null)).toBe(false)
    expect(usablePhotoUrl(undefined)).toBe(false)
    expect(usablePhotoUrl('')).toBe(false)
    expect(usablePhotoUrl('   ')).toBe(false)
  })

  it('http(s) 가 아니거나, 호스트가 없는 http(s) 주소면 못 쓴다', () => {
    for (const bad of [
      'ftp://img.example.com/place.jpg', 'file:///tmp/place.jpg', 'place.jpg', 'not a url', 'data:image/png;base64,AAAA', 'javascript:alert(1)',
      'https://', 'http://', 'https:///place.jpg',
    ]) {
      expect(usablePhotoUrl(bad), bad).toBe(false)
    }
  })
})

describe('flipFaces — 카드 앞뒤 면(§6 표)', () => {
  it('장소 사진 + 장면 그림: 앞 장소 사진, 뒤 장면 그림', () => {
    expect(flipFaces(PHOTO, SCENE)).toEqual<FlipFaces>({ front: PHOTO, back: { kind: 'scene', url: SCENE }, frontIsScene: false })
  })

  it('장면 그림만: 앞 장면 그림, 뒤 「장소 사진이 아직 없어요」', () => {
    expect(flipFaces(null, SCENE)).toEqual<FlipFaces>({ front: SCENE, back: { kind: 'noPhoto' }, frontIsScene: true })
    expect(flipFaces(undefined, SCENE)).toEqual<FlipFaces>({ front: SCENE, back: { kind: 'noPhoto' }, frontIsScene: true })
  })

  it('장소 사진만: 앞 장소 사진, 뒤 「장면 그림이 아직 없어요」', () => {
    expect(flipFaces(PHOTO, null)).toEqual<FlipFaces>({ front: PHOTO, back: { kind: 'noScene' }, frontIsScene: false })
  })

  it('둘 다 없음: 앞 「장소 사진이 아직 없어요」(null), 뒤 「장면 그림이 아직 없어요」', () => {
    expect(flipFaces(null, null)).toEqual<FlipFaces>({ front: null, back: { kind: 'noScene' }, frontIsScene: false })
    expect(flipFaces(undefined, undefined)).toEqual<FlipFaces>({ front: null, back: { kind: 'noScene' }, frontIsScene: false })
  })

  it('http 사진도 사진이다', () => {
    const plain = 'http://img.example.com/place.jpg'
    expect(flipFaces(plain, SCENE)).toEqual<FlipFaces>({ front: plain, back: { kind: 'scene', url: SCENE }, frontIsScene: false })
  })

  it('빈 사진 주소는 없는 것과 같다', () => {
    expect(flipFaces('', SCENE)).toEqual<FlipFaces>({ front: SCENE, back: { kind: 'noPhoto' }, frontIsScene: true })
    expect(flipFaces('', null)).toEqual<FlipFaces>({ front: null, back: { kind: 'noScene' }, frontIsScene: false })
  })

  it('http(s) 가 아니거나 호스트가 없는 사진 주소는 없는 것과 같다', () => {
    for (const bad of ['ftp://img.example.com/place.jpg', 'file:///tmp/place.jpg', 'place.jpg', 'https://', 'https:///place.jpg']) {
      expect(flipFaces(bad, SCENE), bad).toEqual<FlipFaces>({ front: SCENE, back: { kind: 'noPhoto' }, frontIsScene: true })
      expect(flipFaces(bad, null), bad).toEqual<FlipFaces>({ front: null, back: { kind: 'noScene' }, frontIsScene: false })
    }
  })
})

describe('flipPill — 사진 칸 왼쪽 아래 알약', () => {
  const both = { front: PHOTO, back: { kind: 'scene', url: SCENE }, frontIsScene: false } as const
  const sceneOnly = { front: SCENE, back: { kind: 'noPhoto' }, frontIsScene: true } as const
  const photoOnly = { front: PHOTO, back: { kind: 'noScene' }, frontIsScene: false } as const
  const neither = { front: null, back: { kind: 'noScene' }, frontIsScene: false } as const

  it('앞면이 장소 사진이면 「장면 보기」', () => {
    expect(flipPill(both, false)).toBe('scene')
    expect(flipPill(photoOnly, false)).toBe('scene')
  })

  it('앞면이 장면 그림이면 「「작품」 속 장면」', () => {
    expect(flipPill(sceneOnly, false)).toBe('work')
  })

  it('둘 다 없을 때 앞면(「장소 사진이 아직 없어요」)도 「장면 보기」', () => {
    expect(flipPill(neither, false)).toBe('scene')
  })

  it('뒷면은 늘 앞면으로 돌아가는 단추 — 앞면이 장소 사진(또는 빈 면)이면 「장소 사진 보기」', () => {
    expect(flipPill(both, true)).toBe('photo')
    expect(flipPill(photoOnly, true)).toBe('photo')
    expect(flipPill(neither, true)).toBe('photo')
  })

  it('앞면이 장면 그림이면 뒷면(「장소 사진이 아직 없어요」) 알약은 「장면 보기」', () => {
    expect(flipPill(sceneOnly, true)).toBe('scene')
  })
})
