/*
 * 작품검색 탭 상세의 「뒤로」가 주소를 어떻게 바꾸는가 — 값만 보고 정한다(MZ2AZ-534).
 *
 * 홈 「지금 뜨는 작품」 격자가 작품을 열면 이동 state 에 `{ returnTo: '/trending', contentId }` 를 싣는다(tabRouter.openContent).
 *  - 그 작품의 상세를 닫으면 격자로 한 칸 물린다(물릴 기록이 없으면 격자 주소로 바꿔 간다).
 *  - 상세 안에서 검색으로 다른 작품을 열었으면 표시는 무효다 — 그 작품의 뒤로는 전처럼 검색 탭이다.
 *  - 작품 상세 위에서 연 촬영지를 닫으면 작품 상세가 남아 있으므로 그 주소와 state 를 지킨다(전에는 /search 로 바꿔 표시를 잃었다).
 *  - 내 지도가 촬영지를 열면 state 에 `{ returnTo: '/my-map', placeId }` 를 싣는다(tabRouter.openPlace) — 그 촬영지를 닫으면 내 지도로 돌아간다
 *    (web-keep-flow.md §9-9). 작품 상세 없이 바로 연 촬영지일 때만 본다.
 */
import { paths } from '@/app/routes'
import { placeReturnToOf, returnToOf } from '@/app/tabRouter'

export type BackStep = { kind: 'pop' } | { kind: 'replace'; to: string; state?: unknown } | { kind: 'stay' }

const inDetail = (pathname: string) => pathname.startsWith(paths.search + '/')

/** 작품 상세를 닫을 때. `contentId` 는 닫히는 작품. */
export function contentBack(p: { pathname: string; state: unknown; contentId: number | null; canGoBack: boolean }): BackStep {
  if (!inDetail(p.pathname)) return { kind: 'stay' }
  const returnTo = p.contentId === null ? null : returnToOf(p.state, p.contentId)
  if (returnTo) return p.canGoBack ? { kind: 'pop' } : { kind: 'replace', to: returnTo }
  return { kind: 'replace', to: paths.search }
}

/**
 * 촬영지 상세를 닫을 때. `contentId` 는 그 밑에 남아 있는 작품 상세(없으면 null).
 * `placeId`·`canGoBack` 은 표시(`returnTo`)를 실어 연 촬영지를 알아보는 데만 쓴다 — 주지 않으면 전과 같다.
 */
export function placeBack(p: { pathname: string; state: unknown; contentId: number | null; placeId?: number | null; canGoBack?: boolean }): BackStep {
  if (p.contentId === null && p.placeId != null && inDetail(p.pathname)) {
    const returnTo = placeReturnToOf(p.state, p.placeId)
    if (returnTo) return p.canGoBack ? { kind: 'pop' } : { kind: 'replace', to: returnTo }
  }
  if (p.contentId !== null) {
    const to = paths.searchContent(p.contentId)
    if (p.pathname === to) return { kind: 'stay' }
    return { kind: 'replace', to, state: returnToOf(p.state, p.contentId) ? p.state : undefined }
  }
  if (!inDetail(p.pathname)) return { kind: 'stay' }
  return { kind: 'replace', to: paths.search }
}
