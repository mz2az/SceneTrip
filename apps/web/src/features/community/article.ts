/*
 * 본문 블록 규칙 — iOS `CommunityArticle` · `CommunityPhotoPlacement` (CommunityTab/CommunityArticle.swift).
 * 본문은 빈 줄로 나눈 문단이고, 사진은 「몇 번째 문단 뒤」에 넣는다. 서버에는 문단·사진이 섞인 `bodyBlocks` 로 보낸다.
 */
import type { PostBlock } from './model'
import { normalized } from './rules'

export const PARAGRAPH_LIMIT = 64
export const CAPTION_LIMIT = 500
export const CREDIT_LIMIT = 1000

/** 사진 한 장의 배치·설명 — 사진 id 에 연결해 초안에서 사진을 빼도 남은 사진의 설명·삽입 위치를 유지한다. */
export interface PhotoPlacement {
  afterParagraph: number
  caption: string
  credit: string
  aiGenerated: boolean
}

export const newPlacement = (afterParagraph: number): PhotoPlacement => ({ afterParagraph, caption: '', credit: '', aiGenerated: false })

/** 서버 길이 기준 — 앞뒤 공백을 뗀 UTF-16 길이(Swift `utf16.count`, 계약 maxLength). */
export const captionLength = (p: PhotoPlacement): number => normalized(p.caption).length
export const creditLength = (p: PhotoPlacement): number => normalized(p.credit).length
export const metadataValid = (p: PhotoPlacement): boolean => captionLength(p) <= CAPTION_LIMIT && creditLength(p) <= CREDIT_LIMIT

export function paragraphs(body: string): string[] {
  return body
    .replace(/\r\n/g, '\n')
    .replace(/\r/g, '\n')
    .split('\n\n')
    .map(normalized)
    .filter((p) => p !== '')
}

export const plainBody = (body: string): string => paragraphs(body).join('\n\n')

export function canCompose(body: string): boolean {
  const parts = paragraphs(body)
  return parts.length > 0 && parts.length <= PARAGRAPH_LIMIT
}

const nonempty = (text: string): string | undefined => {
  const t = normalized(text)
  return t === '' ? undefined : t
}

/**
 * 서버로 보낼 블록. 사진마다 놓일 위치(문단 뒤)를 지키고, 같은 위치면 고른 순서를 지킨다.
 * 위치가 없으면 사진 n 장은 n 번째 문단 뒤(문단 수를 넘으면 마지막 뒤).
 */
export function blocks(body: string, photoIds: readonly string[], placements: Readonly<Record<string, PhotoPlacement>>): PostBlock[] {
  const parts = paragraphs(body)
  const out: PostBlock[] = []
  for (let position = 0; position <= parts.length; position += 1) {
    if (position > 0) out.push({ kind: 'text', text: parts[position - 1] })
    photoIds.forEach((id, index) => {
      const place = placements[id] ?? newPlacement(Math.min(index + 1, parts.length))
      if (Math.min(Math.max(0, place.afterParagraph), parts.length) !== position) return
      const block: PostBlock = { kind: 'photo', photoIndex: index }
      const caption = nonempty(place.caption)
      const credit = nonempty(place.credit)
      if (caption !== undefined) block.caption = caption
      if (credit !== undefined) block.credit = credit
      if (place.aiGenerated) block.aiGenerated = true
      out.push(block)
    })
  }
  return out
}

/** 옛 본문·사진은 읽을 때만 배치한다. 저장된 원문이나 사진을 다시 쓰지 않는다. */
export function legacyBlocks(body: string, photoCount: number): PostBlock[] {
  const parts = paragraphs(body)
  const out: PostBlock[] = []
  let nextPhoto = 0
  parts.forEach((text, offset) => {
    out.push({ kind: 'text', text })
    const target = Math.floor(((offset + 1) * photoCount) / Math.max(1, parts.length))
    while (nextPhoto < target) out.push({ kind: 'photo', photoIndex: nextPhoto++ })
  })
  while (nextPhoto < photoCount) out.push({ kind: 'photo', photoIndex: nextPhoto++ })
  return out
}
