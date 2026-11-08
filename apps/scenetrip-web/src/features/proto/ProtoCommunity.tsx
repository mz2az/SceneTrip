/*
 * 커뮤니티 탭 시안(2026-11-08 사용자 요청) — 다른 시안 화면과 같은 틀로.
 *  - 머리줄: 「커뮤니티」 왼쪽 정렬, 오른쪽에 검색 동그라미와 「글쓰기」 보라 알약(코스 목록의 「새 코스」 와 같은 모양). 아래 얇은 선.
 *  - 머리줄 밑 게시판 탭: 여행후기 · 자유게시판. 자유게시판은 서버에 아직 없어 빈 화면만 둔다.
 *  - 검색: 머리줄이 검색칸으로 바뀐다. 서버에 글 검색(`GET /posts?q=`)이 없어 **받아 둔 목록 안에서만** 거른다.
 *  - 글 읽기·글쓰기: 시트 대신 오른쪽에서 밀려 들어오는 새 화면(`ProtoPage`).
 * 원래 화면: features/community/CommunityTab.tsx. 로컬 개발 서버 + 시안일 때만 쓰인다.
 */
import { useContext, useEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { Icon } from '@/design/Icon'
import { OverlayRootContext } from '@/design/overlay'
import { HStack, Spacer, Txt, VStack } from '@/design/primitives'
import { Pressable } from '@/features/route/ui/Press'
import { MockBadge } from './protoParts'
import { say } from './protoState'
import { color, heading, radius } from './protoTokens'
import './proto.css'

export type Board = 'review' | 'free'

const BOARDS: readonly { id: Board; label: () => string }[] = [
  { id: 'review', label: () => say('여행후기', 'Trip reviews') },
  { id: 'free', label: () => say('자유게시판', 'Free board') },
]

/** 머리줄 + 게시판 탭. 검색 중에는 제목 줄이 검색칸이 된다. */
export function ProtoCommunityHeader({ board, onBoard, query, onQuery, onCompose, extras = true }: {
  /** 서버가 없는 검색·게시판 탭을 보일까 — 로컬 개발 서버에서만(protoGate.showsLocalOnlyFeatures). */
  extras?: boolean
  board: Board
  onBoard: (b: Board) => void
  /** null 이면 검색 중이 아니다. */
  query: string | null
  onQuery: (q: string | null) => void
  onCompose: () => void
}) {
  const input = useRef<HTMLInputElement>(null)
  const [closing, setClosing] = useState(false)
  useEffect(() => {
    if (query !== null) input.current?.focus()
  }, [query !== null]) // eslint-disable-line react-hooks/exhaustive-deps -- 검색을 연 순간에만.
  // 닫을 때는 검색칸이 돋보기 자리로 줄어드는 움직임(0.2초)을 보인 뒤 제목 줄로 돌아간다.
  const close = () => {
    setClosing(true)
    window.setTimeout(() => {
      setClosing(false)
      onQuery(null)
    }, 200)
  }
  return (
    <div style={{ flex: 'none', background: 'var(--system-background)', paddingTop: 'var(--safe-top)' }}>
      {query === null ? (
        <HStack spacing={4} style={{ padding: '4px 12px 0 16px', minHeight: 52 }}>
          <Txt as="h1" size={22} weight="heavy" style={heading}>{say('커뮤니티', 'Community')}</Txt>
          <Spacer />
          <MockBadge />
          {extras && (
            <Pressable plain className="proto-press" onClick={() => onQuery('')} aria-label={say('글 검색', 'Search posts')} style={{ width: 44, height: 44, display: 'grid', placeItems: 'center' }}>
              <Icon name="magnifyingglass" size={19} weight="semibold" color="primary" />
            </Pressable>
          )}
          <Pressable plain className="proto-press" onClick={onCompose} style={{ height: 36, padding: '0 14px', borderRadius: radius.pill, background: color.primaryFill }}>
            <HStack spacing={5}>
              <Icon name="square.and.pencil" size={13} weight="bold" color="white" />
              <Txt size={14} weight="bold" style={{ color: color.onPrimary }}>{say('글쓰기', 'Write')}</Txt>
            </HStack>
          </Pressable>
        </HStack>
      ) : (
        <HStack spacing={8} style={{ padding: '4px 8px 0 16px', minHeight: 52 }}>
          {/* 오른쪽 돋보기 자리에서 왼쪽으로 길어지며 열리고, 닫을 때는 거꾸로 줄어든다. 「취소」 는 살짝 늦게 떠오른다. */}
          <HStack className={closing ? 'proto-search-close-left' : 'proto-search-open-left'} spacing={6} style={{ flex: 1, minWidth: 0, height: 38, padding: '0 12px', borderRadius: radius.pill, background: 'var(--system-gray6)' }}>
            <Icon name="magnifyingglass" size={16} color="secondary" />
            <input
              ref={input}
              value={query}
              onChange={(e) => onQuery(e.target.value)}
              placeholder={say('제목·내용으로 찾기', 'Search titles and posts')}
              aria-label={say('글 검색', 'Search posts')}
              enterKeyHint="search"
              style={{ flex: 1, minWidth: 0, border: 0, outline: 0, background: 'transparent', fontSize: 16, padding: 0 }}
            />
            {query !== '' && (
              <Pressable plain onClick={() => onQuery('')} aria-label={say('지우기', 'Clear')} style={{ display: 'grid', placeItems: 'center' }}>
                <Icon name="xmark.circle.fill" size={16} color="tertiary" />
              </Pressable>
            )}
          </HStack>
          <Pressable plain className={closing ? 'proto-fade-out' : 'proto-search-results'} onClick={close} style={{ minHeight: 44, padding: '0 8px' }}>
            <Txt size={16} weight="semibold" style={{ color: color.primaryText }}>{say('취소', 'Cancel')}</Txt>
          </Pressable>
        </HStack>
      )}
      {/* 게시판 — 밑줄·칸 없이 글자만. 고른 것은 진한 글자, 아닌 것은 옅은 회색이고 바뀔 때 색이 스르르 옮겨 간다. 누르면 살짝 작아진다. */}
      {extras && <div role="tablist" aria-label={say('게시판', 'Boards')} style={{ display: 'flex', alignItems: 'baseline', gap: 16, padding: '0 16px 6px' }}>
        {BOARDS.map((b) => {
          const on = b.id === board
          return (
            <Pressable
              key={b.id}
              plain
              role="tab"
              aria-selected={on}
              className="proto-press"
              onClick={() => onBoard(b.id)}
              style={{ minHeight: 44, display: 'grid', alignItems: 'center' }}
            >
              <span style={{ fontSize: 18, fontWeight: 800, letterSpacing: '-0.01em', color: on ? 'var(--label)' : 'var(--system-gray3)', transition: 'color 180ms ease-out' }}>{b.label()}</span>
            </Pressable>
          )
        })}
      </div>}
    </div>
  )
}

/** 자유게시판 — 서버에 게시판이 아직 없다. */
export function FreeBoardEmpty() {
  return (
    <VStack spacing={8} align="center" style={{ padding: '80px 32px 40px' }}>
      <Icon name="bubble.left.and.bubble.right" size={40} color="secondary" />
      <Txt as="h2" size={20} weight="bold" align="center" style={heading}>{say('자유게시판은 준비 중이에요', 'Free board is coming soon')}</Txt>
      <Txt size={15} color="secondary" align="center">{say('여행 이야기·질문을 자유롭게 나누는 곳이 될 거예요', 'A place for travel talk and questions.')}</Txt>
      <MockBadge />
    </VStack>
  )
}

/** 검색 결과가 없을 때. */
export function NoMatch({ query }: { query: string }) {
  return (
    <VStack spacing={6} align="center" style={{ padding: '60px 32px' }}>
      <Txt size={17} weight="bold" align="center">{say(`「${query}」 에 맞는 글이 없어요`, `No posts match “${query}”`)}</Txt>
      <Txt size={14} color="secondary" align="center">{say('지금 불러온 글 안에서만 찾아요', 'Only searching loaded posts.')}</Txt>
    </VStack>
  )
}

/**
 * 오른쪽에서 밀려 들어오는 새 화면 — 탭 막대까지 덮는다. `title` 을 주면 위에 「‹ 제목」 줄을 단다(글 읽기). 안 주면 내용이 제 머리줄을 쓴다(글쓰기).
 */
export function ProtoPage({ open, title, onBack, children }: { open: boolean; title?: string; onBack: () => void; children: React.ReactNode }) {
  const root = useContext(OverlayRootContext)
  const [mounted, setMounted] = useState(open)
  const [closing, setClosing] = useState(false)
  useEffect(() => {
    if (open) {
      setMounted(true)
      setClosing(false)
      return
    }
    if (!mounted) return
    setClosing(true)
    const t = window.setTimeout(() => {
      setMounted(false)
      setClosing(false)
    }, 240)
    return () => window.clearTimeout(t)
  }, [open, mounted])
  if (!mounted || !root) return null
  return createPortal(
    <div
      role="dialog"
      aria-modal
      aria-label={title}
      className={closing ? 'proto-page-out' : 'proto-page-in'}
      style={{ position: 'absolute', inset: 0, zIndex: 50, display: 'flex', flexDirection: 'column', background: 'var(--system-background)', boxShadow: '-8px 0 24px rgba(0,0,0,0.12)' }}
    >
      {title !== undefined ? (
        <HStack spacing={4} style={{ flex: 'none', padding: 'calc(4px + var(--safe-top)) 8px 4px', minHeight: 52, borderBottom: '0.5px solid var(--separator)' }}>
          <Pressable plain className="proto-press" onClick={onBack} aria-label={say('뒤로', 'Back')} style={{ width: 44, height: 44, display: 'grid', placeItems: 'center' }}>
            <Icon name="chevron.left" size={19} weight="semibold" color="primary" />
          </Pressable>
          <Txt size={17} weight="bold" lines={1} style={{ flex: 1, minWidth: 0 }}>{title}</Txt>
        </HStack>
      ) : (
        <div style={{ flex: 'none', height: 'var(--safe-top)' }} />
      )}
      <div style={{ flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column' }}>{children}</div>
    </div>,
    root,
  )
}

/**
 * 글쓰기 위 「게시판」 고르기 — 누르면 바로 아래로 목록이 펼쳐진다(고른 것에 ✓). 바깥을 누르거나 Esc 면 닫힌다.
 * 서버에 게시판 구분이 아직 없어, 무엇을 골라도 올리면 여행후기로 들어간다(시안).
 */
export function BoardPicker({ board, onBoard }: { board: Board; onBoard: (b: Board) => void }) {
  const [open, setOpen] = useState(false)
  useEffect(() => {
    if (!open) return
    const esc = (e: KeyboardEvent) => e.key === 'Escape' && setOpen(false)
    window.addEventListener('keydown', esc)
    return () => window.removeEventListener('keydown', esc)
  }, [open])
  const current = BOARDS.find((b) => b.id === board)!
  // 작게 — 보라 옅은 칩 하나(「여행후기 ⌄」). 펼친 목록도 글자 크기만큼만: 줄 높이 36, 고른 줄은 옅은 보라 바탕(✓ 대신).
  return (
    <div style={{ position: 'relative', flex: 'none', padding: '14px 20px 4px', zIndex: 5 }}>
      <Pressable
        plain
        className="proto-press"
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-label={say(`게시판: ${current.label()}`, `Board: ${current.label()}`)}
        onClick={() => setOpen((o) => !o)}
        style={{ minHeight: 44, display: 'grid', alignItems: 'center' }}
      >
        <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4, height: 28, padding: '0 9px 0 11px', borderRadius: radius.pill, background: 'color-mix(in srgb, var(--home-purple) 10%, var(--system-background))' }}>
          <Txt size={13} weight="bold" style={{ color: color.primaryText }}>{current.label()}</Txt>
          <span style={{ display: 'inline-grid', transform: open ? 'rotate(180deg)' : 'none', transition: 'transform 180ms ease-out' }}>
            <Icon name="chevron.down" size={10} weight="bold" color={color.primaryText} />
          </span>
        </span>
      </Pressable>
      {open && (
        <>
          <div aria-hidden onClick={() => setOpen(false)} style={{ position: 'fixed', inset: 0 }} />
          <ul
            role="listbox"
            aria-label={say('게시판', 'Board')}
            className="proto-search-results"
            style={{ position: 'absolute', left: 20, top: 'calc(100% - 4px)', minWidth: 128, margin: 0, padding: 4, listStyle: 'none', borderRadius: 12, background: 'var(--system-background)', boxShadow: '0 6px 20px rgba(0,0,0,0.14)', border: '0.5px solid var(--separator)' }}
          >
            {BOARDS.map((b) => {
              const on = b.id === board
              return (
                <li key={b.id} role="option" aria-selected={on}>
                  <Pressable
                    plain
                    onClick={() => { onBoard(b.id); setOpen(false) }}
                    style={{ width: '100%', minHeight: 36, padding: '0 10px', borderRadius: 8, textAlign: 'left', background: on ? 'color-mix(in srgb, var(--home-purple) 10%, var(--system-background))' : undefined }}
                  >
                    <Txt size={14} weight={on ? 'bold' : 'medium'} style={{ color: on ? color.primaryText : 'var(--label)' }}>{b.label()}</Txt>
                  </Pressable>
                </li>
              )
            })}
          </ul>
        </>
      )}
    </div>
  )
}
