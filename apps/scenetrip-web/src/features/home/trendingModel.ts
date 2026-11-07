/*
 * 홈 「지금 뜨는 작품」 전체 — 선반과 같은 조회(`GET /contents`, 검색어 없음 = 서버 인기도 내림차순)를 한도만 늘려 받는다(MZ2AZ-534).
 * 새 끝점은 없다. 계약의 `limit` 상한이 100 이라 100편씩 `offset` 으로 이어 받아 `total` 까지 채운다(DEV 2026-11-07 기준 128편 = 두 번).
 * 순서는 서버 인기도 그대로라 선반(첫 20편)과 앞부분이 같다.
 *
 * 받은 목록은 모듈에 들어 둔다. 격자에서 작품을 열면 덮개가 내려가며 언마운트되는데, 뒤로 돌아올 때 자리표시부터 다시 깜빡이지 않게
 * 들어 둔 것을 먼저 그리고 새로 받는다. 작품 제목은 받을 때의 언어로 굳으므로 언어가 바뀌면 버린다.
 */
import { api, ok } from '@/api/client'
import type { components } from '@/api/generated/schema'
import { onLanguageChange } from '@/i18n'

type ContentSummary = components['schemas']['ContentSummary']

/** 한 번에 받는 수 — 계약(`components.parameters.Limit`)의 최댓값. */
export const TRENDING_LIMIT = 100
/** 이어 받는 횟수 상한 — 서버가 total 을 잘못 줘도 끝없이 돌지 않게. */
const MAX_PAGES = 5

let cache: ContentSummary[] | null = null
onLanguageChange(() => {
  cache = null
})

/** 직전에 받은 목록(없으면 null). */
export function cachedTrending(): readonly ContentSummary[] | null {
  return cache
}

/** 인기순 작품 목록을 받는다. 실패하면 던진다 — 화면이 「불러오지 못했습니다」를 띄운다. */
export async function loadTrending(): Promise<ContentSummary[]> {
  const works: ContentSummary[] = []
  const seen = new Set<number>()
  for (let pageNo = 0; pageNo < MAX_PAGES; pageNo++) {
    const page = await ok(api.GET('/contents', { params: { query: { limit: TRENDING_LIMIT, offset: works.length } } }))
    // 받는 사이 순위가 바뀌어 같은 작품이 두 쪽에 걸칠 수 있다 — 먼저 받은 자리를 지킨다.
    for (const w of page.items) {
      if (seen.has(w.id)) continue
      seen.add(w.id)
      works.push(w)
    }
    if (page.items.length < TRENDING_LIMIT || works.length >= page.total) break
  }
  cache = works
  return works
}
