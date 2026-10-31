/*
 * 코스 편집의 「장소 검색」이 서버에 묻는 것 — iOS RouteTab/RoutePlaceSearch.swift (MZ2AZ-372).
 * 서버 검색(`GET /places?q=`)이다 — 검색 탭의 장소 탭과 같은 창구라 같은 말에 같은 곳이 나온다(이름·별칭·설명·작품·출연진, 주소는 보지 않는다).
 *
 * - 글자마다 부르지 않는다. 입력이 멎고 DEBOUNCE_MS 뒤에 한 번. 그 사이 또 치면 앞의 것을 버린다.
 * - 늦게 온 답이 새 답을 덮지 않는다. 새로 물으면 앞 요청을 버리고, 버려진 것은 화면에 닿지 않는다.
 * - 재시도는 공통 계층(api/client)이 한다. 여기서 또 돌리지 않는다.
 * - 빈 검색어면 인기순 첫 쪽을 보인다 — 빈 화면보다 무엇이든 있는 편이 다음 행동을 부른다.
 */
import { useCallback, useEffect, useRef, useState } from 'react'
import { type ApiFailure, api, asFailure, ok } from '@/api/client'
import { type Paging, advanced, hasMore, merged, newPaging } from '@/state/shared'
import type { PlaceSummary } from '../models'

export const DEBOUNCE_MS = 350
export const PAGE_SIZE = 40
/** 계약의 `q` 최대 길이. */
export const MAX_LENGTH = 100

/** 입력을 서버에 보낼 검색어로. 앞뒤 공백을 떼고, 비었으면 null(전체), 길면 계약의 상한에서 자른다. */
export function keywordFrom(text: string): string | null {
  const t = text.trim()
  return t === '' ? null : [...t].slice(0, MAX_LENGTH).join('')
}

/** 기다렸다 물을 것인가. 빈 검색어(처음 열 때·지웠을 때)는 바로 보인다 — 기다릴 타자가 없다. */
export const delayFor = (keyword: string | null): number => (keyword === null ? 0 : DEBOUNCE_MS)

export type SearchPhase = { kind: 'loading' } | { kind: 'loaded' } | { kind: 'failed'; failure: ApiFailure }

export interface PlaceSearch {
  phase: SearchPhase
  results: readonly PlaceSummary[]
  paging: Paging
  loadingMore: boolean
  moreFailed: boolean
  search(text: string): void
  loadMore(): void
  /** 지금까지 결과에 나온 곳을 id 로 되짚는다 — 검색어를 바꿔 목록에서 사라진 뒤에도 고른 것을 찾는다. */
  place(id: number): PlaceSummary | undefined
}

export function usePlaceSearch(): PlaceSearch {
  const [phase, setPhase] = useState<SearchPhase>({ kind: 'loading' })
  const [results, setResults] = useState<readonly PlaceSummary[]>([])
  const [paging, setPaging] = useState<Paging>(newPaging())
  const [loadingMore, setLoadingMore] = useState(false)
  const [moreFailed, setMoreFailed] = useState(false)
  const timer = useRef<number | null>(null)
  const ticket = useRef(0)
  const keyword = useRef<string | null>(null)
  const seen = useRef(new Map<number, PlaceSummary>())

  const remember = (items: readonly PlaceSummary[]) => items.forEach((p) => seen.current.set(p.id, p))

  const search = useCallback((text: string) => {
    const wanted = keywordFrom(text)
    const mine = (ticket.current += 1)
    if (timer.current) window.clearTimeout(timer.current)
    setPhase({ kind: 'loading' })
    setLoadingMore(false)
    setMoreFailed(false)
    timer.current = window.setTimeout(async () => {
      try {
        const page = await ok(api.GET('/places', { params: { query: { ...(wanted ? { q: wanted } : {}), limit: PAGE_SIZE } } }))
        if (mine !== ticket.current) return
        keyword.current = wanted
        setResults(page.items)
        setPaging(advanced(newPaging(), page.items.length, page.total))
        remember(page.items)
        setPhase({ kind: 'loaded' })
      } catch (error) {
        if (mine !== ticket.current) return
        setPhase({ kind: 'failed', failure: asFailure(error) })
      }
    }, delayFor(wanted))
  }, [])

  const loadMore = useCallback(() => {
    if (phase.kind !== 'loaded' || loadingMore || !hasMore(paging)) return
    setLoadingMore(true)
    setMoreFailed(false)
    const asked = keyword.current
    const offset = paging.offset
    const mine = ticket.current
    void (async () => {
      try {
        const page = await ok(api.GET('/places', { params: { query: { ...(asked ? { q: asked } : {}), limit: PAGE_SIZE, offset } } }))
        // 받는 사이 검색어가 바뀌었으면 버린다 — 옛 검색의 줄이 새 목록에 붙는다.
        if (mine !== ticket.current) return
        setLoadingMore(false)
        setResults((r) => merged(r, page.items, (p) => p.id))
        setPaging((p) => advanced(p, page.items.length, page.total))
        remember(page.items)
      } catch {
        if (mine !== ticket.current) return
        setLoadingMore(false)
        setMoreFailed(true)
      }
    })()
  }, [phase.kind, loadingMore, paging])

  useEffect(() => () => {
    if (timer.current) window.clearTimeout(timer.current)
    ticket.current += 1
  }, [])

  return { phase, results, paging, loadingMore, moreFailed, search, loadMore, place: (id) => seen.current.get(id) }
}
