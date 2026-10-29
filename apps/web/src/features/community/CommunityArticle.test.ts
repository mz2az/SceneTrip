// iOS tests/CommunityArticleTests.swift · CommunityAuthorTests.swift
import { beforeEach, describe, expect, it } from 'vitest'
import { chooseLanguage, tr } from '@/i18n'
import {
  type PhotoPlacement, blocks, canCompose, captionLength, legacyBlocks, metadataValid, newPlacement, paragraphs, plainBody,
} from './article'
import { type CommunityPost, type PostDetail, authorInitial, authorName, isMine, parseLegacyPosts, postFromDetail, postFromSummary } from './model'
import { matches } from './rules'

beforeEach(() => chooseLanguage('ko')) // jsdom 의 기본 언어는 영어다

const placement = (p: Partial<PhotoPlacement> & { afterParagraph: number }): PhotoPlacement => ({ ...newPlacement(p.afterParagraph), ...p })

describe('CommunityArticle', () => {
  it('사진 설명·출처 길이는 앞뒤 공백을 뗀 UTF-16 길이로 서버 한도와 맞춘다', () => {
    const p = placement({ afterParagraph: 1, caption: '😀'.repeat(250), credit: 'a'.repeat(1000) })
    expect(metadataValid(p)).toBe(true)
    expect(metadataValid({ ...p, caption: p.caption + '😀' })).toBe(false)
    const spaced = { ...p, caption: `  ${'가'.repeat(500)}  ` }
    expect(captionLength(spaced)).toBe(500)
    expect(metadataValid(spaced)).toBe(true)
    expect(metadataValid({ ...spaced, credit: `${spaced.credit}a` })).toBe(false)
  })

  it('사진은 고른 문단 뒤에 놓인다', () => {
    const out = blocks('첫 문단\n\n둘째 문단', ['a', 'b'], {
      a: placement({ afterParagraph: 2, caption: '바다', aiGenerated: true }),
      b: placement({ afterParagraph: 1, caption: '골목' }),
    })
    expect(out.map((b) => b.kind)).toEqual(['text', 'photo', 'text', 'photo'])
    expect(out.filter((b) => b.kind === 'photo').map((b) => b.photoIndex)).toEqual([1, 0])
    expect(out.at(-1)?.caption).toBe('바다')
    expect(out.at(-1)?.aiGenerated).toBe(true)
  })

  it('사진을 지워도 남은 사진의 설정이 유지되고 인덱스가 다시 매겨진다', () => {
    const out = blocks('짧은 문단', ['kept'], {
      removed: placement({ afterParagraph: 1 }),
      kept: placement({ afterParagraph: 9, caption: '남은 사진' }),
    })
    expect(out).toHaveLength(2)
    expect(out.at(-1)?.photoIndex).toBe(0)
    expect(out.at(-1)?.caption).toBe('남은 사진')
  })

  it('위치를 안 정한 사진은 n 번째 문단 뒤(없으면 마지막 뒤)로 간다', () => {
    const out = blocks('a\n\nb', ['x', 'y', 'z'], {})
    expect(out.map((b) => (b.kind === 'text' ? 't' : `p${b.photoIndex}`))).toEqual(['t', 'p0', 't', 'p1', 'p2'])
  })

  it('빈 줄과 윈도 줄바꿈을 정리하되 글자는 잃지 않는다', () => {
    expect(paragraphs(' 첫째\r\n문장\r\n\r\n\r\n둘째 ')).toEqual(['첫째\n문장', '둘째'])
    expect(plainBody(' 첫째\n\n\n둘째 ')).toBe('첫째\n\n둘째')
    expect(canCompose('문단\n\n'.repeat(65))).toBe(false)
    expect(canCompose('   ')).toBe(false)
  })

  it('옛 본문·사진은 모든 사진을 한 번씩 쓰고 본문을 그대로 둔다', () => {
    const out = legacyBlocks('첫째\n\n둘째\n\n셋째', 4)
    expect(out.filter((b) => b.kind === 'photo').map((b) => b.photoIndex)).toEqual([0, 1, 2, 3])
    expect(out.filter((b) => b.kind === 'text').map((b) => b.text).join('\n\n')).toBe('첫째\n\n둘째\n\n셋째')
  })

  it('응답 확인은 같은 배치일 때만 같은 글로 본다', () => {
    const bl = [{ kind: 'text' as const, text: '본문' }, { kind: 'photo' as const, photoIndex: 0 }]
    const input = { title: '제목', body: '본문', photoKeys: ['uploads/tmp/a.jpg'], bodyBlocks: bl }
    const detail: PostDetail = { id: 1, title: '제목', body: '본문', createdAt: '2026-10-01T00:00:00Z', photoUrls: ['https://example.com/posts/a.jpg?s=1'], isMine: true, bodyBlocks: bl }
    expect(matches(detail, input)).toBe(true)
    expect(matches({ ...detail, bodyBlocks: [...bl].reverse() }, input)).toBe(false)
    expect(matches({ ...detail, bodyBlocks: null }, input)).toBe(false)
  })
})

describe('글쓴이 이름', () => {
  const post = (author?: string | null): CommunityPost => ({ key: 'x', board: '장소 후기', title: '제주 이틀', body: '', createdAt: new Date(), author })

  it('내 글은 닉네임, 없으면 「나」', () => {
    expect(authorName(post(), 'gil')).toBe('gil')
    expect(authorName(post(), null)).toBe(tr('나'))
    expect(authorName(post(), '')).toBe(tr('나'))
    expect(authorName(post(), '  \n')).toBe(tr('나'))
  })

  it('남의 글에 내 닉네임이 붙지 않는다', () => {
    expect(authorName(post('제주러버'), 'gil')).toBe('제주러버')
    expect(authorName(post('제주러버'), null)).toBe('제주러버')
  })

  it('동그라미 글자는 보이는 이름의 첫 글자', () => {
    expect(authorInitial(post(), 'gil')).toBe('g')
    expect(authorInitial(post(), '여행자12345')).toBe('여')
    expect(authorInitial(post('제주러버'), 'gil')).toBe('제')
  })

  it('저장된 옛 글(글쓴이 칸 없음)은 내 글로 읽힌다', () => {
    const saved = parseLegacyPosts(JSON.stringify([{ id: '6F9619FF-8B86-D011-B42D-00C04FC964FF', board: '장소 후기', title: 't', body: 'b', createdAt: 0 }]))
    expect(saved).toHaveLength(1)
    expect(isMine(saved[0]!)).toBe(true)
    expect(authorName(saved[0]!, 'gil')).toBe('gil')
    // Swift Date(기준 2001-01-01) 0 초
    expect(saved[0]!.createdAt.toISOString()).toBe('2001-01-01T00:00:00.000Z')
  })

  it('작성자가 없는 서버 글은 「탈퇴한 사용자」이고 내 글이 아니다', () => {
    const p = postFromSummary({ id: 42, title: 't', excerpt: 'e', createdAt: '2026-10-01T00:00:00Z', photoCount: 0, isMine: false })
    expect(isMine(p)).toBe(false)
    expect(authorName(p, 'my nickname')).toBe(tr('탈퇴한 사용자'))
  })

  it('옛 글 파싱은 깨진 줄을 걸러 낸다', () => {
    expect(parseLegacyPosts('not json')).toEqual([])
    expect(parseLegacyPosts(null)).toEqual([])
    expect(parseLegacyPosts(JSON.stringify([{ nope: 1 }, { id: 'a', title: 't', body: 'b', createdAt: 0 }]))).toHaveLength(1)
  })

  it('서버 상세는 사진 순서와 코스 일차 순서를 지킨다', () => {
    const d: PostDetail = {
      id: 9007199254740, title: 't', body: 'full', createdAt: '2026-10-01T00:00:00Z', isMine: true,
      photoUrls: ['https://s.test/posts/a.jpg?s=1', 'https://s.test/posts/b.jpg?s=2'],
      course: { title: 'trip', days: [
        { dayNumber: 2, stops: [{ source: 'place', placeId: 2, name: 'B', latitude: 1, longitude: 2, dwellMinutes: 30 }] },
        { dayNumber: 1, stops: [
          { source: 'place', placeId: 42, name: 'Palace', latitude: 37, longitude: 127, dwellMinutes: 30 },
          { source: 'poi', poiId: 123, name: '식당', displayName: 'Restaurant', address: '서울', displayAddress: 'Seoul', latitude: 37.1, longitude: 127.1, dwellMinutes: 60 },
        ] },
      ] },
    }
    const post = postFromDetail(d)
    expect(post.remotePhotos).toEqual(d.photoUrls)
    expect(post.course?.days.map((day) => day.length)).toEqual([2, 1])
    expect(post.course?.days[0]!.map((s) => s.placeId ?? null)).toEqual([42, null])
    expect(post.course?.days[0]!.map((s) => s.poiId ?? null)).toEqual([null, 123])
    expect(post.course?.days[0]![1]!.displayName).toBe('Restaurant')
    expect(post.detailLoaded).toBe(true)
  })
})
