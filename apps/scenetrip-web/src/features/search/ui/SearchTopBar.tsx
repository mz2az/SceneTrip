/*
 * 검색 탭의 지도 위에 얹히는 것들 — 검색바와 지도 조작 버튼. 출처: apps/scenetrip-ios/src/SearchTab/SearchTabOverlays.swift
 */
import { type RefObject } from 'react'
import { Icon } from '@/design/Icon'
import { HStack, Pressable, Txt, shadow } from '@/design/primitives'
import { tr } from '@/i18n'
import { FONT } from './common'

/** SwiftUI 기본 `.shadow(radius: 3, y: 1)` — 색은 검정 33%. */
const SOFT = shadow('rgba(0,0,0,0.33)', 3, 1)

/**
 * 검색어와 장바구니가 한 캡슐이다 — 프로토타입의 검색바와 같다.
 * 장바구니 단추는 스크린샷에서 파랑(accent)이다: 소스는 `.foregroundStyle(.primary)` 지만 단추 안의 계층 스타일은 tint 로 풀린다.
 */
export function SearchBar({
  draft, onDraft, onSubmit, onClear, onFocus, inputRef, cartCount, onCart,
}: {
  draft: string
  onDraft: (v: string) => void
  onSubmit: () => void
  onClear: () => void
  onFocus: () => void
  inputRef: RefObject<HTMLInputElement | null>
  cartCount: number
  onCart: () => void
}) {
  return (
    <form
      onSubmit={(e) => {
        e.preventDefault()
        onSubmit()
      }}
      style={{ margin: '8px 14px 0' }}
    >
      <HStack spacing={8} pad="11px 14px" style={{ background: 'var(--system-background)', borderRadius: 999, boxShadow: SOFT }}>
        {/* iOS: 돋보기 잉크 16.3 (lucide 는 칸의 0.8 만 칠한다) → 20, 입력 시작 x=56 */}
        <Icon name="magnifyingglass" size={20} color="secondary" />
        <input
          ref={inputRef}
          value={draft}
          onChange={(e) => onDraft(e.target.value)}
          onFocus={onFocus}
          placeholder={tr('작품·배우·장소로 검색')}
          enterKeyHint="search"
          autoCorrect="off"
          autoCapitalize="none"
          spellCheck={false}
          aria-label={tr('작품·배우·장소로 검색')}
          className="st-search-input"
          style={{ flex: 1, minWidth: 0, border: 0, outline: 0, background: 'transparent', fontSize: FONT.body, padding: 0, lineHeight: '22px' }}
        />
        {draft !== '' && (
          <Pressable plain onClick={onClear} aria-label={tr('지우기')} style={{ flex: 'none' }}>
            <Icon name="xmark.circle.fill" size={FONT.body} color="tertiary" />
          </Pressable>
        )}
        <div style={{ width: 0.5, height: 22, background: 'var(--separator)', flex: 'none' }} />
        <Pressable plain onClick={onCart} aria-label={tr('장바구니')} style={{ position: 'relative', flex: 'none', width: 20, height: 20, display: 'grid', placeItems: 'center', marginRight: 2 }}>
          <Icon name="cart" size={20.5} weight="medium" color="accent" />
          {cartCount > 0 && (
            <Txt
              size={FONT.caption2}
              weight="bold"
              color="white"
              style={{ position: 'absolute', top: -8, right: -10, padding: '2px 5px', borderRadius: 999, background: 'var(--system-red)', lineHeight: 1.2 }}
            >
              {cartCount}
            </Txt>
          )}
        </Pressable>
      </HStack>
    </form>
  )
}

/** 지도 위 원형 조작 버튼 — 보이는 원은 38 이지만 누르는 자리는 44(애플 최소 권장). */
export function MapControl({ label, symbol, onClick }: { label: string; symbol: string; onClick: () => void }) {
  return (
    <Pressable plain onClick={onClick} aria-label={label} style={{ width: 44, height: 44, display: 'grid', placeItems: 'center', flex: 'none' }}>
      <span style={{ width: 38, height: 38, borderRadius: '50%', background: 'var(--system-background)', boxShadow: SOFT, display: 'grid', placeItems: 'center' }}>
        <Icon name={symbol} size={FONT.body} weight="medium" color="accent" />
      </span>
    </Pressable>
  )
}

/**
 * 「현 지도 내 성지 검색」 — 목업의 토글 버튼. 켜면 지도 범위 안의 촬영지만 남고, 다시 누르면 검색 전 상태로 돌아간다.
 * 켜지면 검정 캡슐에 「이 지도에서 12곳 · 해제」.
 */
export function NearbyButton({ active, label, onClick }: { active: boolean; label: string; onClick: () => void }) {
  return (
    <Pressable
      plain
      onClick={onClick}
      style={{
        display: 'flex', alignItems: 'center', gap: 6, padding: '9px 14px', borderRadius: 999, boxShadow: SOFT,
        background: active ? 'var(--label)' : 'var(--system-background)', color: active ? 'var(--system-background)' : 'var(--label)',
      }}
    >
      <Icon name={active ? 'xmark' : 'arrow.clockwise'} size={FONT.footnote} weight="semibold" color={active ? 'var(--system-background)' : 'primary'} />
      <Txt size={FONT.subheadline} weight="medium" style={{ color: 'inherit' }}>{label}</Txt>
    </Pressable>
  )
}
