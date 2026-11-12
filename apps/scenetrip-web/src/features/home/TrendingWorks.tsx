/*
 * 홈 「지금 뜨는 작품」의 「전체 보기」 — 인기순 작품 전체를 포스터 격자로(MZ2AZ-534).
 *
 * 전에는 「전체 보기」가 작품검색 탭을 열어 탭 막대의 「작품검색」과 같았다. 이제 홈 위에 덮이는 덮개(/trending)이고 「뒤로」가 홈으로 돌아간다.
 * 포스터를 누르면 작품검색 탭의 그 작품 상세(촬영지 목록 + 지도)로 간다 — 홈 선반에서 누를 때와 같은 길이다. 상세의 「뒤로」는 이 격자로 돌아온다.
 * 순서·숫자는 서버 값 그대로다(선반과 같은 조회, 한도만 크게 — trendingModel.ts). 분석은 상세가 이미 보내는 view_title 뿐이다.
 */
import { DeferredImg } from '@/design/DeferredImg'
import { useCallback, useEffect, useState } from 'react'
import { paths } from '@/app/routes'
import { useTabRouter } from '@/app/tabRouter'
import { Icon } from '@/design/Icon'
import { Pressable, Txt, VStack } from '@/design/primitives'
import { tr, useLang } from '@/i18n'
import type { components } from '@/api/generated/schema'
import { BorderedButton } from '../search/ui/common'
import { cachedTrending, loadTrending } from './trendingModel'
import './trending.css'

type ContentSummary = components['schemas']['ContentSummary']

export type TrendingState = { kind: 'loading' } | { kind: 'failed' } | { kind: 'loaded'; works: readonly ContentSummary[] }

/** 자리표시 격자의 칸 수 — 첫 화면을 채울 만큼. */
const SKELETON_TILES = 6
const NAV_HEIGHT = 44

/** 덮개 — 내비게이션 바(뒤로 · 제목) 아래 격자. */
export function TrendingCover({ onClose }: { onClose: () => void }) {
  useLang()
  const router = useTabRouter()
  const [state, setState] = useState<TrendingState>(() => {
    const cached = cachedTrending()
    return cached ? { kind: 'loaded', works: cached } : { kind: 'loading' }
  })

  const load = useCallback(() => {
    let cancelled = false
    setState((s) => (s.kind === 'failed' ? { kind: 'loading' } : s))
    loadTrending()
      .then((works) => !cancelled && setState({ kind: 'loaded', works }))
      // 들고 있는 목록이 있으면 그대로 둔다 — 새로 고침 실패로 보던 격자를 지우지 않는다.
      .catch(() => !cancelled && setState((s) => (s.kind === 'loaded' ? s : { kind: 'failed' })))
    return () => {
      cancelled = true
    }
  }, [])
  useEffect(load, [load])

  return (
    <VStack spacing={0} align="stretch" style={{ height: '100%', background: 'var(--grouped-background)', paddingTop: 'var(--safe-top)' }}>
      <div style={{ position: 'relative', height: NAV_HEIGHT, flex: 'none', display: 'grid', placeItems: 'center' }}>
        <Pressable
          plain
          aria-label={tr('이전')}
          onClick={onClose}
          style={{ position: 'absolute', left: 8, top: 0, width: 44, height: 44, display: 'grid', placeItems: 'center' }}
        >
          <Icon name="chevron.left" size={22} weight="semibold" color="accent" />
        </Pressable>
        <Txt as="h1" size={17} weight="semibold" lines={1} style={{ margin: 0, maxWidth: 'calc(100% - 120px)' }}>{tr('지금 뜨는 작품')}</Txt>
      </div>
      <div className="scroll-y trending-scroll" style={{ flex: 1, minHeight: 0, overflowX: 'hidden' }}>
        <div style={{ padding: '6px 20px 10px' }}>
          <Txt size={13} color="secondary">{tr('인기순')}</Txt>
        </div>
        <TrendingGrid state={state} onOpen={(w) => router.openContent(w, { returnTo: paths.trending })} onRetry={load} />
      </div>
    </VStack>
  )
}

/** 격자 본체 — 불러오는 중(자리표시) · 실패(다시 시도) · 빈 목록 · 포스터들. */
export function TrendingGrid({ state, onOpen, onRetry }: { state: TrendingState; onOpen: (w: ContentSummary) => void; onRetry: () => void }) {
  if (state.kind === 'loading') {
    return (
      <div className="trending-grid trending-skeleton" data-testid="trending-skeleton" aria-busy="true" aria-label={tr('지금 뜨는 작품')}>
        {Array.from({ length: SKELETON_TILES }, (_, i) => (
          <VStack key={i} spacing={6} align="start">
            <div className="trending-poster" />
            <div className="trending-line" />
          </VStack>
        ))}
      </div>
    )
  }
  if (state.kind === 'failed') {
    return (
      <VStack spacing={12} align="center" style={{ padding: '48px 24px' }}>
        <Txt size={14} color="secondary" align="center">{tr('서버에 연결하지 못했어요. 잠시 뒤 다시 시도해 주세요')}</Txt>
        <BorderedButton onClick={onRetry}>
          <Txt size={15} weight="semibold" color="accent">{tr('다시 시도')}</Txt>
        </BorderedButton>
      </VStack>
    )
  }
  if (state.works.length === 0) {
    return (
      <div style={{ padding: '48px 24px', textAlign: 'center' }}>
        <Txt size={14} color="secondary">{tr('검색된 작품이 없어요')}</Txt>
      </div>
    )
  }
  return (
    <div className="trending-grid">
      {state.works.map((work) => (
        <Pressable
          key={work.id}
          plain
          data-content-id={work.id}
          aria-label={`${work.title}, ${tr('촬영지 %lld곳', work.placeCount)}`}
          onClick={() => onOpen(work)}
          style={{ textAlign: 'left', minWidth: 0 }}
        >
          <VStack spacing={6} align="stretch">
            <div className="trending-poster">
              {work.posterUrl && (
                <DeferredImg src={work.posterUrl} alt="" loading="lazy" referrerPolicy="no-referrer" draggable={false} style={{ position: 'absolute', inset: 0, width: '100%', height: '100%', objectFit: 'cover' }} />
              )}
              <div style={{ position: 'absolute', inset: 0, background: 'linear-gradient(to bottom, transparent 50%, rgba(0,0,0,0.65))' }} />
              <div style={{ position: 'absolute', left: 0, right: 0, bottom: 0, padding: 10 }}>
                <Txt size={13} weight="bold" color="white" lines={2}>{work.title}</Txt>
              </div>
            </div>
            <Txt size={11} color="secondary">{tr('촬영지 %lld곳', work.placeCount)}</Txt>
          </VStack>
        </Pressable>
      ))}
    </div>
  )
}
