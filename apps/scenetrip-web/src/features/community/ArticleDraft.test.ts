// 블로그형 글쓰기 초안(articleDraft.ts) — 글 조각과 사진이 쓴 순서대로 놓인 목록을 기존 저장 형식(body + bodyBlocks)으로 바꾸는 규칙.
import { describe, expect, it } from 'vitest'
import { blocks, newPlacement, paragraphs } from './article'
import { type DraftItem, dropMissingPhotos, insertPhotos, moveItem, normalize, photoOrder, placementsOf, storyOf } from './articleDraft'

const text = (id: string, value: string): DraftItem => ({ kind: 'text', id, text: value })
const photo = (id: string): DraftItem => ({ kind: 'photo', id })

/** 부를 때마다 n1, n2, … 를 내는 id 생성기. */
const counter = () => {
  let n = 0
  return () => `n${++n}`
}

describe('storyOf', () => {
  it('글 조각을 쓴 순서대로 빈 줄로 잇고 사진은 건너뛴다', () => {
    expect(storyOf([text('t1', '가'), photo('p1'), text('t2', '나')])).toBe('가\n\n나')
  })

  it('비어 있거나 공백뿐인 조각은 건너뛴다', () => {
    expect(storyOf([text('t1', '가'), text('t2', ''), text('t3', '   \n  '), photo('p1'), text('t4', '나')])).toBe('가\n\n나')
  })

  it('조각 안의 빈 줄은 그대로 두어, paragraphs 로 다시 나누면 쓴 문단이 그대로 나온다', () => {
    const items = [text('t1', '가\n\n나'), photo('p1'), text('t2', ''), text('t3', '다')]
    expect(paragraphs(storyOf(items))).toEqual(['가', '나', '다'])
  })

  it('글 조각이 없으면 빈 본문', () => {
    expect(storyOf([])).toBe('')
    expect(storyOf([photo('p1'), text('t1', '  ')])).toBe('')
  })
})

describe('placementsOf', () => {
  it('사진 자리 = 그 앞 글 조각들의 문단 수(빈 줄이 있는 조각은 문단 여럿)', () => {
    const items = [text('t1', '가\n\n나'), photo('p1'), text('t2', ''), photo('p2'), text('t3', '다'), photo('p3')]
    const result = placementsOf(items, {})
    expect(result.p1.afterParagraph).toBe(2)
    expect(result.p2.afterParagraph).toBe(2)
    expect(result.p3.afterParagraph).toBe(3)
  })

  it('맨 앞 사진은 0 번째 문단 뒤', () => {
    expect(placementsOf([photo('p1'), text('t1', '가')], {}).p1.afterParagraph).toBe(0)
  })

  it('설명·출처·AI 표시는 meta 에서, 없으면 빈 값', () => {
    const items = [text('t1', '가'), photo('p1'), photo('p2')]
    const result = placementsOf(items, { p1: { caption: '바다', credit: '내 사진', aiGenerated: true } })
    expect(result).toEqual({
      p1: { afterParagraph: 1, caption: '바다', credit: '내 사진', aiGenerated: true },
      p2: newPlacement(1),
    })
  })

  it('사진이 아닌 항목은 결과에 없다', () => {
    expect(Object.keys(placementsOf([text('t1', '가'), photo('p1')], {}))).toEqual(['p1'])
  })
})

describe('photoOrder', () => {
  it('목록에 놓인 순서대로 사진 id', () => {
    expect(photoOrder([photo('p2'), text('t1', '가'), photo('p1'), text('t2', ''), photo('p3')])).toEqual(['p2', 'p1', 'p3'])
  })

  it('사진이 없으면 빈 배열', () => {
    expect(photoOrder([text('t1', '가')])).toEqual([])
  })
})

describe('insertPhotos', () => {
  const base = [text('t1', '가'), text('t2', '나')]

  it('at = -1 이면 맨 앞에 넣는다', () => {
    expect(insertPhotos(base, -1, ['p1', 'p2'])).toEqual([photo('p1'), photo('p2'), text('t1', '가'), text('t2', '나')])
  })

  it('at = i 이면 i 번째 항목 뒤에 넣는다', () => {
    expect(insertPhotos(base, 0, ['p1'])).toEqual([text('t1', '가'), photo('p1'), text('t2', '나')])
    expect(insertPhotos(base, 1, ['p1'])).toEqual([text('t1', '가'), text('t2', '나'), photo('p1')])
  })

  it('이미 있는 사진 id 는 다시 넣지 않고, 나머지는 받은 순서를 지킨다', () => {
    const items = [text('t1', '가'), photo('p1'), text('t2', '나')]
    expect(insertPhotos(items, 2, ['p3', 'p1', 'p2'])).toEqual([text('t1', '가'), photo('p1'), text('t2', '나'), photo('p3'), photo('p2')])
  })

  it('넣을 것이 없으면 같은 목록', () => {
    const items = [text('t1', '가'), photo('p1')]
    expect(insertPhotos(items, 0, ['p1'])).toEqual(items)
  })

  it('받은 목록을 바꾸지 않는다', () => {
    const items = [text('t1', '가')]
    insertPhotos(items, 0, ['p1'])
    expect(items).toEqual([text('t1', '가')])
  })
})

describe('dropMissingPhotos', () => {
  it('사진 칸에 없는 사진을 빼고 글 조각은 남긴다', () => {
    const items = [text('t1', '가'), photo('p1'), text('t2', '나'), photo('p2'), photo('p3')]
    expect(dropMissingPhotos(items, ['p3', 'p1'])).toEqual([text('t1', '가'), photo('p1'), text('t2', '나'), photo('p3')])
  })

  it('사진 칸이 비면 사진이 모두 빠진다', () => {
    expect(dropMissingPhotos([photo('p1'), text('t1', '가')], [])).toEqual([text('t1', '가')])
  })
})

describe('normalize', () => {
  it('빈 목록이면 newId 로 만든 빈 글 조각 하나', () => {
    expect(normalize([], counter())).toEqual([text('n1', '')])
  })

  it('사진이 맨 끝이면 그 뒤에 빈 글 조각을 붙인다', () => {
    expect(normalize([text('t1', '가'), photo('p1')], counter())).toEqual([text('t1', '가'), photo('p1'), text('n1', '')])
  })

  it('빈 글 조각이 둘 이어지면 하나로 줄인다', () => {
    const result = normalize([text('t1', '가'), photo('p1'), text('e1', ''), text('e2', ''), photo('p2'), text('t2', '나')], counter())
    expect(result.map((item) => item.kind)).toEqual(['text', 'photo', 'text', 'photo', 'text'])
    expect(result.map((item) => (item.kind === 'text' ? item.text : item.id))).toEqual(['가', 'p1', '', 'p2', '나'])
  })

  it('이미 모양이 맞으면 내용이 있는 조각은 id·글 그대로', () => {
    const items = [text('t1', '가\n\n나'), photo('p1'), text('t2', '다')]
    expect(normalize(items, counter())).toEqual(items)
  })
})

describe('moveItem', () => {
  const items = [text('a', '가'), photo('p1'), text('b', '나')]

  it('from 의 항목을 to 자리로 옮긴다', () => {
    expect(moveItem(items, 0, 2)).toEqual([photo('p1'), text('b', '나'), text('a', '가')])
    expect(moveItem(items, 2, 0)).toEqual([text('b', '나'), text('a', '가'), photo('p1')])
    expect(moveItem(items, 1, 1)).toEqual(items)
  })

  it('범위 밖이면 그대로', () => {
    expect(moveItem(items, -1, 0)).toEqual(items)
    expect(moveItem(items, 3, 0)).toEqual(items)
    expect(moveItem(items, 0, 3)).toEqual(items)
    expect(moveItem(items, 0, -1)).toEqual(items)
  })

  it('받은 목록을 바꾸지 않는다', () => {
    moveItem(items, 0, 2)
    expect(items).toEqual([text('a', '가'), photo('p1'), text('b', '나')])
  })
})

describe('초안 → 저장 블록(article.blocks)', () => {
  it('글 조각·사진을 쓴 순서대로 블록이 나온다', () => {
    const items = [text('t1', '가\n\n나'), photo('p1'), text('t2', '다'), photo('p2')]
    const result = blocks(storyOf(items), photoOrder(items), placementsOf(items, {}))
    expect(result).toEqual([
      { kind: 'text', text: '가' },
      { kind: 'text', text: '나' },
      { kind: 'photo', photoIndex: 0 },
      { kind: 'text', text: '다' },
      { kind: 'photo', photoIndex: 1 },
    ])
  })
})
