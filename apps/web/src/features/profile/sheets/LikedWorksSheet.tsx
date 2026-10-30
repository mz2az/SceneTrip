/*
 * 찜한 작품 — iOS `LikedWorksSheet` (ProfileTab/ProfileSheets.swift:111). 행을 누르면 **바로 아래 상세가 펼쳐진다**(아코디언).
 * 여기서 고치는 것은 찜 빼기(휴지통)뿐이다 — 작품 상세는 작품검색 탭이 맡는다.
 *
 * 비어 있음·받는 중·못 받음은 서로 다른 화면이다 — 없는 것과 못 받은 것은 다른 일이다.
 */
import { useState } from 'react'
import type { components } from '@/api/generated/schema'
import { Icon } from '@/design/Icon'
import { HStack, Pressable, Spinner, Txt, VStack } from '@/design/primitives'
import { noticeForContent, unavailableMessage } from '@/features/search/rules/unavailableItem'
import { tr } from '@/i18n'
import { likeActions, useLikes } from '@/state/likes'
import { ContentUnavailable } from '../ui/ContentUnavailable'
import { GlassSheet, SheetBody, SheetHeader } from '../ui/GlassSheet'
import { PLAIN_LIST_PAD, RemoteImage, UnavailableNotice } from '../ui/parts'

type ContentSummary = components['schemas']['ContentSummary']

const POSTER = { width: 34, height: 46 }
const DIM = 0.65

/** 방송사 · 연도 — 있는 것만 · 로 잇는다. */
export const workSubtitle = (w: Pick<ContentSummary, 'broadcaster' | 'releaseYear'>): string =>
  [w.broadcaster, w.releaseYear].filter((v) => v !== null && v !== undefined && v !== '').join(' · ')

function WorkDetail({ work }: { work: ContentSummary }) {
  return (
    <VStack spacing={5} style={{ padding: 10, background: 'var(--system-gray6)', borderRadius: 10, marginTop: 6 }}>
      {work.genres && work.genres.length > 0 && <Txt size={11} color="accent">{work.genres.join(' · ')}</Txt>}
      <Txt size={11} color="secondary">{tr('촬영지 %lld곳', work.placeCount)}</Txt>
      <Txt size={11} color="tertiary">{tr('촬영지는 작품검색 탭에서 지도로 볼 수 있어요')}</Txt>
    </VStack>
  )
}

function WorkRow({ work, expanded, onToggle, last }: { work: ContentSummary; expanded: boolean; onToggle: () => void; last: boolean }) {
  const delisted = work.delisted === true
  const notice = noticeForContent(work)
  return (
    <div style={{ margin: `0 ${PLAIN_LIST_PAD}px`, padding: '10px 0', borderBottom: last ? undefined : '0.5px solid rgba(60,60,67,0.2)' }}>
      <HStack>
        <Pressable plain onClick={onToggle} aria-expanded={expanded} style={{ flex: 1, minWidth: 0, textAlign: 'left' }}>
          <HStack spacing={10}>
            <RemoteImage url={work.posterUrl} symbol="film" fits style={{ ...POSTER, borderRadius: 5, opacity: delisted ? DIM : 1 }} />
            <VStack spacing={2} style={{ flex: 1, minWidth: 0 }}>
              <Txt size={15} weight="medium" style={{ opacity: delisted ? DIM : 1, lineHeight: '17.9px' }}>{work.title}</Txt>
              <Txt size={12} color="secondary">{workSubtitle(work)}</Txt>
            </VStack>
            <Icon name="heart.fill" size={12} color="red" />
            <Icon name={expanded ? 'chevron.up' : 'chevron.down'} size={11} color="tertiary" />
          </HStack>
        </Pressable>
        <Pressable plain aria-label={tr('찜 빼기')} onClick={() => void likeActions.toggle(work.id)} style={{ minWidth: 44, minHeight: 44, display: 'grid', placeItems: 'center' }}>
          <Icon name="trash" size={17} />
        </Pressable>
      </HStack>
      {notice && (
        <div style={{ marginTop: 6 }}>
          <UnavailableNotice message={unavailableMessage(notice)} />
        </div>
      )}
      {expanded && <WorkDetail work={work} />}
    </div>
  )
}

/** 찜은 검색 목록·상세, 코스 마법사, 찜 목록에서 같은 실패 안내를 쓴다 — UnavailableItem.swift:121 `LikeFailureNotice`. */
function LikeFailureNotice() {
  const { failure } = useLikes()
  if (!failure) return null
  return (
    <HStack align="start" spacing={12} style={{ margin: '0 12px 12px', padding: 12, background: 'rgba(250,250,250,0.9)', backdropFilter: 'blur(20px)', borderRadius: 12, boxShadow: '0 1px 6px rgba(0,0,0,0.08)' }} role="alert">
      <VStack spacing={4} style={{ flex: 1 }}>
        <Txt size={12} weight="semibold">{tr('찜을 바꾸지 못했습니다')}</Txt>
        <Txt size={12}>{failure.userMessage}</Txt>
      </VStack>
      <Pressable plain onClick={likeActions.clearFailure} style={{ minHeight: 44, display: 'grid', placeItems: 'center' }}>
        <Txt size={12}>{tr('닫기')}</Txt>
      </Pressable>
    </HStack>
  )
}

export function LikedWorksSheet({ open, onClose, loading, failure }: { open: boolean; onClose: () => void; loading: boolean; failure: string | null }) {
  const { likedWorks } = useLikes()
  const [expanded, setExpanded] = useState<number | null>(null)
  return (
    <GlassSheet open={open} onClose={onClose} detent="medium" label={tr('찜한 작품')}>
      <SheetHeader title={tr('찜한 작품')} onClose={onClose} />
      {failure ? (
        <ContentUnavailable title={tr('작품 목록을 받지 못했습니다')} symbol="wifi.exclamationmark" description={<span style={{ fontSize: 11 }}>{failure}</span>} />
      ) : loading && likedWorks.length === 0 ? (
        <VStack align="center" justify="center" spacing={8} style={{ flex: 1 }}>
          <Spinner />
          <Txt size={12} color="secondary">{tr('작품 목록을 받아오는 중입니다')}</Txt>
        </VStack>
      ) : likedWorks.length === 0 ? (
        <ContentUnavailable title={tr('찜한 작품이 없습니다')} symbol="heart" description={tr('작품검색 탭에서 하트를 눌러 보세요')} />
      ) : (
        <SheetBody>
          {likedWorks.map((work, i) => (
            <WorkRow key={work.id} work={work} expanded={expanded === work.id} onToggle={() => setExpanded(expanded === work.id ? null : work.id)} last={i === likedWorks.length - 1} />
          ))}
        </SheetBody>
      )}
      <LikeFailureNotice />
    </GlassSheet>
  )
}
