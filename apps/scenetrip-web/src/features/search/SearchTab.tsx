/*
 * 작품검색 탭 — 지도 + 바텀시트 3단 (계획서 §3). 출처: apps/scenetrip-ios/src/SearchTab/SearchTabView.swift
 *
 * 항상 마운트돼 있고 보일 때만 `active` 가 true 다. 상태와 동작은 `useSearchTab`, 지도는 `map/MapView`(공급자 교체 지점),
 * 시트 안은 목록(SearchList)·작품 상세·촬영지 상세 가운데 하나다.
 */
import { useEffect, useRef, useState } from 'react'
import { Alert } from '@/design/overlay'
import { HStack, Pressable, Spinner, Txt, VStack } from '@/design/primitives'
import { tr } from '@/i18n'
import { LocationConsentSheet } from '@/features/profile/consent'
import { likeActions, useLikes } from '@/state/likes'
import './icons'
import { MapView } from './map/MapView'
import { CAMERA_TOP_INSET } from './map/mapRules'
import './search.css'
import { CartSheet } from './ui/CartSheet'
import { ContentDetail } from './ui/ContentDetail'
import { BottomSheet } from './ui/BottomSheet'
import { ErrorView, FONT } from './ui/common'
import { PlaceDetail } from './ui/PlaceDetail'
import { MapControl, NearbyButton, SearchBar } from './ui/SearchTopBar'
import { SearchList } from './ui/SearchList'
import { SuggestionPanel } from './ui/SuggestionPanel'
import { useSearchTab } from './useSearchTab'
import { shownTotal } from '@/state/shared'

/** 탭바 높이 — 시트는 탭바 밑으로 깔린다(tokens.css 의 --tabbar-height). */
const TAB_BAR = 56
/**
 * 시트의 비율(14%/48%) 기준 높이 ÷ 화면 높이. iOS 스크린샷에서 읽은 값이다(2026-10-30): 402x874 화면에서 중간 단의 윗단이 y=437 이라
 * 0.48 × 910.4 — 첫 레이아웃 기준 높이가 화면보다 4% 크다. 같은 위치에 오도록 같은 비율을 쓴다.
 */
const SHEET_TOTAL_RATIO = 910.4 / 874

export function SearchTab({ active }: { active: boolean }) {
  const t = useSearchTab()
  const likes = useLikes()
  const root = useRef<HTMLDivElement>(null)
  const [height, setHeight] = useState(0)
  const [visibleSheet, setVisibleSheet] = useState(0)

  useEffect(() => {
    const el = root.current
    if (!el) return
    const measure = () => setHeight(el.clientHeight)
    measure()
    const ro = new ResizeObserver(measure)
    ro.observe(el)
    return () => ro.disconnect()
  }, [])
  useEffect(() => {
    if (!active) t.blurSearch()
    // eslint-disable-next-line react-hooks/exhaustive-deps -- 숨겨질 때만.
  }, [active])

  // 시트의 기준 높이(iOS 와 같은 비율) / 최대 높이는 검색바 바로 아래까지.
  const total = Math.max(0, (height + TAB_BAR) * SHEET_TOTAL_RATIO)
  const maxHeight = Math.max(80, height + TAB_BAR - CAMERA_TOP_INSET)

  const placesLoaded = t.scene.places.length
  const placeTotal = shownTotal(t.scene.placePaging, placesLoaded)
  const nearbyLabel = t.nearby
    ? placesLoaded < placeTotal
      ? tr('이 지도에서 %2$d곳 중 %1$d곳 · 해제', placesLoaded, placeTotal)
      : tr('이 지도에서 %d곳 · 해제', placeTotal)
    : tr('현 지도 내 성지 검색')

  const sheetContent = () => {
    const { phase } = t.scene
    if (phase.kind === 'failed') {
      return <ErrorView message={phase.failure.userMessage} retryable={phase.failure.isRetryable} traceId={phase.failure.traceId} onRetry={() => t.data.retry()} />
    }
    if (phase.kind === 'loading' && t.scene.places.length === 0 && t.scene.contents.length === 0) {
      return <div style={{ flex: 1, display: 'grid', placeItems: 'center' }}><Spinner size={20} /></div>
    }
    if (t.selectedPlace) {
      return (
        <PlaceDetail
          key={t.selectedPlace.id}
          summary={t.selectedPlace}
          sourceContentId={t.selectedContent?.id}
          load={(id) => t.data.detailOfPlace(id)}
          onBack={t.closePlace}
        />
      )
    }
    if (t.selectedContent) {
      return (
        <ContentDetail
          key={t.selectedContent.id}
          summary={t.selectedContent}
          places={t.contentPlaces}
          loading={t.contentLoading}
          onBack={t.closeContent}
          onSelectPlace={t.openPlace}
          onSave={(place) => t.save(place, t.selectedContent!.id)}
        />
      )
    }
    return (
      <SearchList
        scene={t.data}
        state={t.scene}
        committed={t.committed}
        tab={t.tab}
        onTab={t.setTab}
        chip={t.chip}
        onChip={t.setChip}
        nearby={t.nearby}
        initial={t.initial}
        visiblePlaces={t.visiblePlaces}
        likedIds={likes.contentIds}
        cartIds={t.cart.placeIds}
        chipFillRetry={t.chipFillRetry}
        onChipFillRetry={t.bumpChipFill}
        onOpenContent={t.openContent}
        onOpenPlace={t.openPlace}
        onLike={t.toggleLike}
        onSave={(place) => t.save(place)}
        onClearSearch={t.clearSearch}
        onSearchViewport={t.searchViewport}
      />
    )
  }

  return (
    <div ref={root} className="st-root" style={{ ['--st-sheet-visible' as string]: `${visibleSheet}px` }}>
      <MapView
        pins={t.mapPlaces}
        numbered={t.numbered}
        koreaToken={t.koreaToken}
        locateToken={t.locateToken}
        fitToken={t.fitToken}
        focusToken={t.focusToken}
        focus={t.selectedPlace}
        panToken={t.panToken}
        pan={t.panTarget}
        sheetHeight={visibleSheet}
        onTapPin={t.onTapPin}
        onLocateFailure={t.setLocateFailure}
        onReady={(c) => (t.camera.current = c)}
      />

      <BottomSheet
        detent={t.detent}
        onDetentChange={t.setDetent}
        total={total}
        maxHeight={maxHeight}
        bottomOverlap={TAB_BAR}
        onHeightChange={(h) => {
          const v = Math.max(0, h - TAB_BAR)
          setVisibleSheet(v)
          t.setSheetHeight(v)
        }}
      >
        {sheetContent()}
      </BottomSheet>

      {/* 검색 중에는 지도·시트를 스크림으로 덮는다 — 패널 밖을 누르면 닫힌다. */}
      {t.searchFocused && <div className="st-scrim" onClick={t.blurSearch} />}

      {/* 자동완성은 검색창 바로 아래에 붙어 내려오는 드롭다운이다. */}
      <VStack spacing={2} className="st-top">
        <SearchBar
          draft={t.draft}
          onDraft={t.setDraft}
          onSubmit={() => t.commit(t.draft)}
          onClear={t.clearSearch}
          onFocus={() => t.setSearchFocused(true)}
          inputRef={t.inputRef}
          cartCount={t.cart.items.length}
          onCart={() => t.setShowCart(true)}
        />
        {!t.searchFocused ? (
          // 「현 지도 내 성지 검색」은 가운데, 조작 버튼은 오른쪽 — 위 검색창의 장바구니와 같은 세로선에 놓아 지도를 덜 가린다.
          <div className="st-controls">
            <span />
            <NearbyButton active={t.nearby} label={nearbyLabel} onClick={t.toggleNearby} />
            <HStack spacing={8} justify="end" style={{ paddingRight: 20 }}>
              {t.outsideKorea && <MapControl label={tr('한국으로')} symbol="map" onClick={() => t.setKoreaToken((n) => n + 1)} />}
              <MapControl label={tr('내 위치')} symbol="dot.scope" onClick={() => t.setLocateToken((n) => n + 1)} />
            </HStack>
          </div>
        ) : (
          <SuggestionPanel
            draft={t.draft}
            suggestions={t.suggestions}
            topWork={t.topWork}
            onCommit={t.commit}
            onOpenWork={(work) => t.openContent({ id: work.id, category: work.category, title: work.title, posterUrl: work.posterUrl, broadcaster: work.broadcaster, releaseYear: work.releaseYear, genres: work.genres, placeCount: work.placeCount, producerUrl: work.producerUrl, delisted: work.delisted })}
            onSelectPlace={t.selectPlaceSuggestion}
          />
        )}
      </VStack>

      {/* 계약이 409 에 「이미 저장된 장소입니다」 를 띄우라고 적어 뒀다. 담기는 화면을 옮기지 않으므로 알림이 그 자리에 떠야 한다. */}
      {t.cart.toast && (
        <div className="st-toast" role="status">
          <Txt size={FONT.subheadline}>{t.cart.toast}</Txt>
        </div>
      )}
      {likes.failure && (
        <HStack spacing={12} align="start" className="st-notice" role="alert">
          <VStack spacing={4} align="start" style={{ flex: 1, minWidth: 0 }}>
            <Txt size={FONT.caption} weight="semibold">{tr('찜을 바꾸지 못했습니다')}</Txt>
            <Txt size={FONT.caption}>{likes.failure.userMessage}</Txt>
          </VStack>
          <Pressable plain onClick={() => likeActions.clearFailure()} style={{ minHeight: 44 }}>
            <Txt size={FONT.caption} color="accent">{tr('닫기')}</Txt>
          </Pressable>
        </HStack>
      )}

      <CartSheet open={t.showCart} onClose={() => t.setShowCart(false)} />
      <LocationConsentSheet gate={t.location.gate} />
      <LocateAlert outcome={t.locateFailure} onClose={() => t.setLocateFailure(null)} />
    </div>
  )
}

/**
 * 「내 위치」 가 실패한 경우에만 뜬다 — 성공하면 지도가 그 자리로 날아가므로 사용자가 눈으로 안다. 출처: SearchTab/LocateAlert.swift
 * 웹에는 「설정 열기」 로 보낼 수 있는 설정 화면이 없다(브라우저가 사이트 권한을 관리한다) — 거부 안내는 닫기 단추만 둔다.
 */
function LocateAlert({ outcome, onClose }: { outcome: 'denied' | 'failed' | null; onClose: () => void }) {
  // 닫히는 동안에도 문구가 남도록 마지막 결과를 든다.
  const last = useRef(outcome)
  if (outcome) last.current = outcome
  const shown = outcome ?? last.current
  if (!shown) return null
  return shown === 'denied' ? (
    <Alert
      open={outcome === 'denied'}
      title={tr('위치 권한이 필요합니다')}
      message={tr('설정에서 위치 접근을 허용하면 현재 위치를 보여 드립니다.')}
      buttons={[{ label: tr('닫기'), role: 'cancel' }]}
      onClose={onClose}
    />
  ) : (
    <Alert
      open={outcome === 'failed'}
      title={tr('현재 위치를 찾지 못했습니다')}
      message={tr('실내이거나 신호가 약할 수 있습니다. 잠시 후 다시 눌러 주세요.')}
      buttons={[{ label: tr('확인') }]}
      onClose={onClose}
    />
  )
}
