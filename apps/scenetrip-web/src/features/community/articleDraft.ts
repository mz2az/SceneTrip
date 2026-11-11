/*
 * 블로그형 글쓰기의 초안 — 글 조각과 사진이 쓴 순서대로 놓인 목록(2026-11-09 사용자 요청: 사진을 원하는 문단 사이에).
 *
 * 저장 형식은 그대로다(계약의 `body` + `bodyBlocks`, MZ2AZ-428). 이 목록에서 본문(`storyOf`)과 사진마다 「몇 번째 문단 뒤」(`placementsOf`)를
 * 계산해 기존 `article.blocks()` 에 넘긴다. 글 조각 하나에 빈 줄이 있으면 문단이 여럿이다.
 */
import { type PhotoPlacement, newPlacement, paragraphs } from './article'

export type DraftItem = { kind: 'text'; id: string; text: string } | { kind: 'photo'; id: string }

/** 사진 하나에 붙는 설명·출처·AI 표시 — 위치는 목록이 정한다. */
export type PhotoMeta = Omit<PhotoPlacement, 'afterParagraph'>

/** 글 조각을 쓴 순서대로 빈 줄로 이은 본문. 빈 조각은 건너뛴다. */
export function storyOf(items: readonly DraftItem[]): string {
  return items
    .flatMap((i) => (i.kind === 'text' && i.text.trim() !== '' ? [i.text.trim()] : []))
    .join('\n\n')
}

/** 사진마다 「앞에 있는 문단 수」 = 그 사진이 들어갈 자리. 설명 등은 `meta` 에서(없으면 빈 값). */
export function placementsOf(items: readonly DraftItem[], meta: Readonly<Record<string, PhotoMeta>>): Record<string, PhotoPlacement> {
  const out: Record<string, PhotoPlacement> = {}
  let before = 0
  for (const item of items) {
    if (item.kind === 'text') before += paragraphs(item.text).length
    else out[item.id] = { ...newPlacement(before), ...(meta[item.id] ?? {}), afterParagraph: before }
  }
  return out
}

/** 목록의 사진 순서 — 첫 사진이 대표(목록 썸네일)이므로 사진 칸 순서를 이것에 맞춘다. */
export function photoOrder(items: readonly DraftItem[]): string[] {
  return items.flatMap((i) => (i.kind === 'photo' ? [i.id] : []))
}

/** `at` 번째 항목 뒤(−1 이면 맨 앞)에 사진들을 넣는다. 이미 있는 사진 id 는 다시 넣지 않는다. */
export function insertPhotos(items: readonly DraftItem[], at: number, ids: readonly string[]): DraftItem[] {
  const present = new Set(photoOrder(items))
  const fresh: DraftItem[] = [...new Set(ids)].filter((id) => !present.has(id)).map((id) => ({ kind: 'photo', id }))
  if (fresh.length === 0) return [...items]
  const cut = Math.min(Math.max(at + 1, 0), items.length)
  return [...items.slice(0, cut), ...fresh, ...items.slice(cut)]
}

/** 사진 칸에서 빠진 사진을 목록에서도 뺀다. */
export function dropMissingPhotos(items: readonly DraftItem[], slotIds: readonly string[]): DraftItem[] {
  const keep = new Set(slotIds)
  return items.filter((i) => i.kind === 'text' || keep.has(i.id))
}

/**
 * 모양을 다듬는다 — 글 조각이 하나도 없으면 빈 조각 하나, 사진이 맨 끝이면 그 뒤에 빈 조각(이어서 쓰게), 빈 글 조각이 둘 이어지면 하나로.
 * 새 조각의 id 는 `newId` 로 만든다.
 */
export function normalize(items: readonly DraftItem[], newId: () => string): DraftItem[] {
  const out: DraftItem[] = []
  for (const item of items) {
    const prev = out[out.length - 1]
    if (item.kind === 'text' && item.text.trim() === '' && prev?.kind === 'text' && prev.text.trim() === '') continue
    out.push(item)
  }
  if (!out.some((i) => i.kind === 'text') || out[out.length - 1]?.kind === 'photo') out.push({ kind: 'text', id: newId(), text: '' })
  return out
}

/** `from` 의 항목을 `to` 자리로 옮긴다. 범위 밖이면 그대로. */
export function moveItem(items: readonly DraftItem[], from: number, to: number): DraftItem[] {
  if (from < 0 || to < 0 || from >= items.length || to >= items.length) return [...items]
  const next = [...items]
  const [it] = next.splice(from, 1)
  next.splice(to, 0, it)
  return next
}
