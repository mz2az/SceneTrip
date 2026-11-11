/**
 * 작품 고르기 단계 전용 작품 목록 — 30개씩 쪽 넘김, 작품 이름 검색(0.3초 멈추면 서버 `q`).
 * 계획: docs/project/plans/motion-works-drawer-parity.md §1. 순수 규칙은 `worksBrowse.ts`.
 *
 * 코스 스토어의 작품 재료(`routeStore.works`, 30개 고정)는 옛 마법사·홈이 함께 쓰므로 건드리지 않고 여기서 따로 받는다.
 * 검색을 비우면 받아 둔 원래 목록을 그대로 보여 준다(다시 받지 않는다).
 */
import { useCallback, useEffect, useRef, useState } from 'react'
import { api, ok } from '@/api/client'
import type { ContentSummary } from '@/features/route/models'
import { onLanguageChange } from '@/i18n'
import { appendPage, nextOffset, worksQuery } from './worksBrowse'

/** 글자를 넣고 이만큼 멈추면 묻는다. */
export const WORKS_SEARCH_QUIET_MS = 300

export interface WorksList {
  items: ContentSummary[]
  /** 서버가 알려 준 전체 수. 아직 첫 쪽이 안 왔으면 null. */
  total: number | null
  loading: boolean
  failed: boolean
}

const empty: WorksList = { items: [], total: null, loading: false, failed: false }

/** 목록 하나(검색어 하나)의 쪽 넘김. `search` 가 바뀌면 처음부터 다시 받는다. */
function usePagedWorks(search: string, enabled: boolean): { list: WorksList; loadMore: () => void } {
  const [list, setList] = useState<WorksList>(empty)
  const [lang, setLang] = useState(0)
  /** 검색어·언어가 바뀌면 올라간다 — 늦게 온 앞 검색의 응답을 버린다. */
  const ticket = useRef(0)
  const busy = useRef(false)
  const state = useRef(list)
  state.current = list

  useEffect(() => onLanguageChange(() => setLang((n) => n + 1)), [])

  const fetchPage = useCallback((offset: number, mine: number) => {
    busy.current = true
    setList((l) => ({ ...l, loading: true, failed: false }))
    ok(api.GET('/contents', { params: { query: worksQuery(search, offset) } }))
      .then((page) => {
        if (mine !== ticket.current) return
        busy.current = false
        setList((l) => ({ items: appendPage(offset === 0 ? [] : l.items, page.items), total: page.total, loading: false, failed: false }))
      })
      .catch(() => {
        if (mine !== ticket.current) return
        busy.current = false
        setList((l) => ({ ...l, loading: false, failed: true }))
      })
  }, [search])

  useEffect(() => {
    const mine = ++ticket.current
    busy.current = false
    setList(empty)
    if (enabled) fetchPage(0, mine)
  }, [fetchPage, enabled, lang])

  const loadMore = useCallback(() => {
    const l = state.current
    if (!enabled || busy.current || l.failed || l.total === null) return
    const next = nextOffset(l.items.length, l.total)
    if (next !== null) fetchPage(next, ticket.current)
  }, [enabled, fetchPage])

  return { list, loadMore }
}

/**
 * 원래 목록(인기순)과 검색 결과를 함께 든다. `text` 는 검색칸 글자 그대로.
 * 돌려주는 `shown` 이 지금 격자에 그릴 목록, `popular` 는 아무것도 안 골랐을 때 AI 요청이 쓰는 원래 목록이다.
 */
export function useWorksBrowse(text: string) {
  const [query, setQuery] = useState('')
  useEffect(() => {
    const q = text.trim()
    if (!q) {
      setQuery('')
      return
    }
    const t = setTimeout(() => setQuery(q), WORKS_SEARCH_QUIET_MS)
    return () => clearTimeout(t)
  }, [text])

  const base = usePagedWorks('', true)
  const found = usePagedWorks(query, query !== '')
  const searching = query !== ''
  /** 글자를 넣었지만 아직 0.3초가 안 지났다. */
  const typing = text.trim() !== query
  return {
    searching,
    typing,
    query,
    shown: searching ? found.list : base.list,
    popular: base.list.items,
    loadMore: searching ? found.loadMore : base.loadMore,
  }
}
