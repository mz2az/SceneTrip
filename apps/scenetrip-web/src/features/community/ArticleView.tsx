/*
 * 본문 — iOS `CommunityArticleView` · `CommunityArticlePhoto` (CommunityTab/CommunityArticleView.swift).
 * 문단 사이에 사진을 끼워 블로그 글처럼 보인다. 사진은 원래 비율로 보이고, 누르면 전체 화면(확대)이 열린다.
 * 사진 아래에 설명·AI 변환 표시·출처. 주소가 죽었으면 한 번 새 주소를 받아 보고, 그래도 안 되면 「사진 다시 받기」.
 */
import { useEffect, useRef, useState } from 'react'
import { Icon } from '@/design/Icon'
import { Pressable, Spinner, Txt, VStack, rr } from '@/design/primitives'
import { tr } from '@/i18n'
import './icons'
import type { PostBlock } from './model'
import { referenceHeading, splitReferences } from './article'
import { PhotoViewer } from './PhotoViewer'
import { unsigned, usePhotoRenew } from './renewal'

/** 한 장의 그림이 어디서 오는가 — iOS `CommunityPhotoSource`. */
export type PhotoSource =
  | { kind: 'remote'; url: string }
  /** 쓰는 중 — 올릴 JPEG 의 미리보기(object URL). 없으면 못 읽은 사진. */
  | { kind: 'preview'; url: string | null }
  | { kind: 'pending' }
  | { kind: 'failed' }

/** 같은 사진인가 — 서명 쿼리가 새 것으로 바뀌어도 같은 사진이다. */
export function sourceKey(s: PhotoSource): string {
  return s.kind === 'remote' ? `remote:${unsigned(s.url)}` : s.kind === 'preview' ? `preview:${s.url ?? 'none'}` : s.kind
}
const retryable = (s: PhotoSource) => s.kind === 'remote'

type Status = 'loading' | 'loaded' | 'failed'
/** 읽을 그림이 없는 미리보기(못 읽은 사진)는 처음부터 실패다. */
const initialStatus = (s: PhotoSource): Status => (s.kind === 'failed' || (s.kind === 'preview' && s.url === null) ? 'failed' : 'loading')

const REPRESENTATIVE_MAX_HEIGHT = 220 // CommunityArticleView.swift:129
/** `.font(.body).lineSpacing(6)` — 줄 높이 22+6, 첫 줄 위·마지막 줄 아래에는 간격이 없다(위아래 3px 씩 덜어 낸다). */
const LINE_SPACING = 6
const BODY_LINE = 22 + LINE_SPACING
const PHOTO_RADIUS = 12
const EXPAND_BUTTON = 44

/**
 * `foldReferences` — 글 끝의 「References」·「Photo credits」 는 한 줄 뒤로 접는다(읽는 화면만). 길고 주소가 섞여 본문 끝이 지저분했다.
 * **Android 만 하던 동작을 맞춘다**(iOS 에는 없다).
 */
export function ArticleView({ blocks: all, photos, foldReferences = false }: { blocks: readonly PostBlock[]; photos: readonly PhotoSource[]; foldReferences?: boolean }) {
  const { body: blocks, references } = foldReferences ? splitReferences(all) : { body: all, references: [] as PostBlock[] }
  return (
    <VStack spacing={22} align="start" style={{ width: '100%' }}>
      {blocks.map((block, offset) => {
        if (block.kind === 'text' && block.text) {
          return (
            <Txt as="p" key={offset} size={17} style={{ width: '100%', userSelect: 'text', lineHeight: `${BODY_LINE}px`, margin: `-${LINE_SPACING / 2}px 0` }} data-testid={`community-paragraph-${offset}`}>
              {block.text}
            </Txt>
          )
        }
        const source = block.kind === 'photo' && block.photoIndex != null ? photos[block.photoIndex] : undefined
        if (source) return <ArticlePhoto key={offset} source={source} block={block} testId={`community-body-photo-${block.photoIndex}`} />
        return null
      })}
      {references.length > 0 && <ReferencesFold blocks={references} />}
    </VStack>
  )
}

/** 접힌 덧붙임 구간 — 줄 이름은 글의 제목 줄을 그대로 쓴다(글 언어를 따른다). */
function ReferencesFold({ blocks }: { blocks: readonly PostBlock[] }) {
  const [open, setOpen] = useState(false)
  const label = blocks.map(referenceHeading).filter(Boolean).join(' · ')
  return (
    <VStack spacing={8} align="start" style={{ width: '100%' }} data-testid="community-references">
      <Pressable
        onClick={() => setOpen((v) => !v)}
        aria-expanded={open}
        data-testid="community-references-toggle"
        style={{ display: 'inline-flex', alignItems: 'center', gap: 4, padding: '6px 0' }}
      >
        <Txt size={12} color="secondary">{label}</Txt>
        <Icon name={open ? 'chevron.up' : 'chevron.down'} size={14} color="var(--label-secondary)" />
      </Pressable>
      {open && (
        <VStack spacing={10} align="start" style={{ width: '100%', userSelect: 'text' }}>
          {blocks.map((block, i) =>
            block.text ? <Txt as="p" key={i} size={12} color="secondary" style={{ margin: 0, whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>{block.text}</Txt> : null,
          )}
        </VStack>
      )}
    </VStack>
  )
}

/** 원래 비율로 보여 주고, 같은 사진을 확대 화면으로 넘긴다. */
export function ArticlePhoto({ source, block, representative = false, testId }: { source: PhotoSource; block: PostBlock; representative?: boolean; testId?: string }) {
  const renew = usePhotoRenew()
  const [status, setStatus] = useState<Status>(() => initialStatus(source))
  const [attempt, setAttempt] = useState(0)
  const [expanded, setExpanded] = useState(false)
  const shown = useRef<string>('')
  const renewedFor = useRef<string | null>(null)
  const key = sourceKey(source)
  const url = source.kind === 'remote' ? source.url : source.kind === 'preview' ? source.url : null

  // 새 사진이면 처음부터, 같은 사진의 새 주소(서명 갱신)면 보이던 그림을 그대로 둔다.
  useEffect(() => {
    if (shown.current !== key) {
      shown.current = key
      setStatus(initialStatus(source))
    }
  }, [key, source])

  const reportFailure = () => {
    setStatus('failed')
    if (source.kind !== 'remote' || !renew || renewedFor.current === key) return
    renewedFor.current = key
    renew(false)
  }
  const retry = () => {
    setStatus('loading')
    setAttempt((n) => n + 1)
    if (source.kind === 'remote') renew?.(true)
  }

  const canvas =
    url && status !== 'failed' ? (
      <>
        {status === 'loading' && <Placeholder representative={representative}><Spinner /></Placeholder>}
        <div
          style={{
            position: 'relative', display: status === 'loaded' ? 'flex' : 'none', justifyContent: 'center', overflow: 'hidden',
            background: 'var(--secondary-system-background)', ...rr(PHOTO_RADIUS),
          }}
        >
          <img
            key={attempt}
            src={url}
            alt={block.caption ?? tr('후기 사진')}
            onLoad={() => setStatus('loaded')}
            onError={reportFailure}
            onClick={() => setExpanded(true)}
            style={{ width: '100%', objectFit: 'contain', height: 'auto', maxHeight: representative ? REPRESENTATIVE_MAX_HEIGHT : undefined, cursor: 'zoom-in' }}
          />
          <Pressable
            aria-label={tr('사진 크게 보기')}
            onClick={() => setExpanded(true)}
            style={{ position: 'absolute', right: 6, bottom: 6, width: EXPAND_BUTTON, height: EXPAND_BUTTON, background: 'rgba(0,0,0,0.5)', display: 'grid', placeItems: 'center', ...rr(10) }}
          >
            <Icon name="arrow.up.left.and.arrow.down.right" size={17} color="white" />
          </Pressable>
        </div>
      </>
    ) : source.kind === 'pending' ? (
      <Placeholder representative={representative}><Spinner /></Placeholder>
    ) : (
      <Placeholder representative={representative}>
        <VStack spacing={8} align="center">
          <Txt size={12} color="secondary">{retryable(source) ? tr('사진을 불러오지 못했어요') : tr('사진을 다시 골라 주세요')}</Txt>
          {retryable(source) && (
            <Pressable onClick={retry} style={{ minHeight: 44 }}><Txt size={17} color="accent">{tr('사진 다시 받기')}</Txt></Pressable>
          )}
        </VStack>
      </Placeholder>
    )

  return (
    <VStack spacing={7} align="start" style={{ width: '100%' }} data-testid={testId}>
      <div style={{ width: '100%' }}>{canvas}</div>
      {block.caption && <Txt size={12} color="secondary" style={{ userSelect: 'text' }}>{block.caption}</Txt>}
      <PhotoCredit block={block} />
      {expanded && url && <PhotoViewer url={url} block={block} onClose={() => setExpanded(false)} />}
    </VStack>
  )
}

/**
 * 사진 아래 AI 변환 표시와 출처. **AI 변환 표시는 고지라 늘 보이고, 길게 늘어지는 출처 문장은 「더 보기」 뒤로 접는다.**
 * Android 만 하던 동작을 맞춘다(iOS 에는 없다). 확대 화면(PhotoViewer)의 출처는 그대로 보인다.
 */
function PhotoCredit({ block }: { block: PostBlock }) {
  const [open, setOpen] = useState(false)
  const ai = block.aiGenerated === true
  const credit = block.credit || undefined
  if (!ai && !credit) return null
  return (
    <VStack spacing={7} align="start" style={{ width: '100%' }}>
      <div style={{ display: 'flex', width: '100%', alignItems: 'center', justifyContent: ai ? 'space-between' : 'flex-start' }}>
        {ai && (
          <span style={{ display: 'inline-flex', gap: 4, alignItems: 'center' }}>
            <Icon name="sparkles" size={11} color="var(--label-secondary)" />
            <Txt size={11} color="secondary">{tr('AI 변환 이미지')}</Txt>
          </span>
        )}
        {credit && (
          <Pressable
            onClick={() => setOpen((v) => !v)}
            aria-expanded={open}
            data-testid="community-photo-credit-toggle"
            style={{ display: 'inline-flex', alignItems: 'center', gap: 2, padding: '6px 0' }}
          >
            <Txt size={11} color="secondary">{tr('더 보기')}</Txt>
            <Icon name={open ? 'chevron.up' : 'chevron.down'} size={12} color="var(--label-secondary)" />
          </Pressable>
        )}
      </div>
      {open && credit && <Txt size={11} color="secondary" style={{ userSelect: 'text', wordBreak: 'break-word' }}>{credit}</Txt>}
    </VStack>
  )
}

/** 아직 못 받았거나 못 읽은 사진 자리 — 비율 1.5. */
function Placeholder({ children, representative }: { children: React.ReactNode; representative: boolean }) {
  return (
    <div
      style={{
        width: '100%', aspectRatio: '1.5', maxHeight: representative ? REPRESENTATIVE_MAX_HEIGHT : undefined,
        background: 'var(--secondary-system-background)', display: 'grid', placeItems: 'center', overflow: 'hidden', ...rr(PHOTO_RADIUS),
      }}
    >
      {children}
    </div>
  )
}
