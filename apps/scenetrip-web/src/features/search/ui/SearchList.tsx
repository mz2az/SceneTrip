/*
 * 검색 탭 시트의 목록 — 작품 / 장소 두 탭, 분류 칩, 그리고 이어 받기(MZ2AZ-372).
 * 출처: apps/scenetrip-ios/src/SearchTab/{SearchTabList,SearchTabView(ChipRow)}.swift
 */
import { type ReactNode, useEffect, useRef } from 'react'
import { HStack, Pressable, Txt, VStack } from '@/design/primitives'
import { tr } from '@/i18n'
import { hasMore, shownTotal } from '@/state/shared'
import { CHIP_FILL_PAGES, type ListTab, chipIsFilling, isPartial, tabCount, tabLabel, visiblePlacesOf, wantsMorePlaces } from '../listRules'
import { CHIP_ALL, CHIP_NAMES } from '../rules/placeType'
import type { PlaceSummary, SceneData, SceneState } from '../sceneData'
import { FONT, DetailHeader, ListDivider, ListEmptyNote, ListMoreRetry, ListMoreSpinner } from './common'
import { PlaceRow, WorkRow } from './Rows'
import { Segmented } from './Segmented'

/** 카테고리 칩 줄. 목록과 지도를 둘 다 좁히고 카메라는 건드리지 않는다(§3-5). */
function ChipRow({ selected, onSelect }: { selected: string; onSelect: (name: string) => void }) {
  return (
    <div className="scroll-x" style={{ paddingBottom: 7.3, touchAction: 'pan-x', flex: 'none' }} data-sheet-nodrag>
      <HStack spacing={8} pad="0 14px" style={{ width: 'max-content' }}>
        {CHIP_NAMES.map((name) => {
          const on = selected === name
          return (
            <Pressable
              key={name}
              plain
              onClick={() => onSelect(on ? CHIP_ALL : name)}
              style={{ padding: '6px 13px', borderRadius: 999, background: on ? 'var(--accent)' : 'var(--system-gray6)', color: on ? '#fff' : 'var(--label)' }}
            >
              <Txt size={FONT.subheadline} style={{ color: 'inherit' }}>{tr(name)}</Txt>
            </Pressable>
          )
        })}
      </HStack>
    </div>
  )
}

/** 줄이 화면에 들어오면 한 번 부른다 — 열쇠가 바뀌면(한 쪽이 붙어 offset 이 바뀌면) 다시 본다. */
function LoadMoreSentinel({ watch, onVisible, children }: { watch: string; onVisible: () => void; children: ReactNode }) {
  const ref = useRef<HTMLDivElement>(null)
  const cb = useRef(onVisible)
  cb.current = onVisible
  useEffect(() => {
    const el = ref.current
    if (!el) return
    const io = new IntersectionObserver((entries) => entries.some((e) => e.isIntersecting) && cb.current(), { root: el.closest('[data-sheet-scroll]'), rootMargin: '120px' })
    io.observe(el)
    return () => io.disconnect()
  }, [watch])
  return <div ref={ref}>{children}</div>
}

/** 클릭 가능한 줄 — 줄 안에 단추가 있어 `<button>` 으로 감쌀 수 없다. */
function RowButton({ onOpen, children }: { onOpen: () => void; children: ReactNode }) {
  return (
    <div
      role="button"
      tabIndex={0}
      onClick={onOpen}
      onKeyDown={(e) => (e.key === 'Enter' || e.key === ' ') && (e.preventDefault(), onOpen())}
      style={{ cursor: 'pointer' }}
    >
      {children}
    </div>
  )
}

export interface SearchListProps {
  scene: SceneData
  state: SceneState
  committed: string
  tab: ListTab
  onTab: (t: ListTab) => void
  chip: string
  onChip: (c: string) => void
  nearby: boolean
  initial: boolean
  visiblePlaces: readonly PlaceSummary[]
  likedIds: ReadonlySet<number>
  cartIds: ReadonlySet<number>
  chipFillRetry: number
  onChipFillRetry: () => void
  onOpenContent: (c: SceneState['contents'][number]) => void
  onOpenPlace: (p: PlaceSummary) => void
  onLike: (contentId: number) => void
  onSave: (p: PlaceSummary) => void
  onClearSearch: () => void
  onSearchViewport: () => void
}

export function SearchList(p: SearchListProps) {
  const { scene, state } = p
  const loaded = state.phase.kind === 'loaded'
  const placesLoaded = state.places.length
  const placeTotal = shownTotal(state.placePaging, placesLoaded)
  const wantsMore = wantsMorePlaces(state.placePaging, p.initial, p.visiblePlaces.length)
  const filling = chipIsFilling(p.chip, p.initial, state.placePaging)

  // 칩이 켜지면 목록 끝이 보이든 말든 끝까지 받는다(MZ2AZ-372). 칩을 끄거나 바꾸거나 화면을 떠나면 멈춘다.
  const fillKey = `${p.chip}-${state.listSerial}-${loaded}-${p.chipFillRetry}`
  useEffect(() => {
    if (p.chip === CHIP_ALL) return
    let cancelled = false
    void (async () => {
      let pages = 0
      const wants = () => {
        const s = scene.store.get()
        return wantsMorePlaces(s.placePaging, p.initial, visiblePlacesOf(s.places, p.chip, p.initial).length)
      }
      while (!cancelled && pages < CHIP_FILL_PAGES && wants()) {
        if (!(await scene.loadNextPlaces())) return
        pages += 1
      }
    })()
    return () => {
      cancelled = true
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- fillKey 가 칩·검색·재시도를 모두 든다.
  }, [fillKey])

  const counts = (tab: ListTab) =>
    tabLabel(
      tab,
      p.initial,
      tabCount(tab, {
        initial: p.initial, chip: p.chip, contentPaging: state.contentPaging, placePaging: state.placePaging,
        contentsLoaded: state.contents.length, placesLoaded, visibleCount: p.visiblePlaces.length,
      }),
    )

  return (
    <VStack style={{ flex: 1, minHeight: 0 }}>
      {/* 검색을 하고 들어온 목록이면 나가는 길을 준다 — 상세와 같은 헤더를 같은 자리에. 한 단계는 「검색 전」 이다. */}
      {p.committed !== '' && <DetailHeader title={p.committed} subtitle="" onBack={p.onClearSearch} />}

      <div style={{ padding: '0 14px 8px', flex: 'none' }}>
        <Segmented
          items={[{ id: 'work', label: counts('work') }, { id: 'place', label: counts('place') }]}
          selected={p.tab}
          onSelect={p.onTab}
        />
      </div>

      {p.tab === 'place' && (
        <>
          {/* 새 검색이 깔리면 칩 줄도 처음 자리로 — 옆으로 밀어 둔 채 「전체」 로 돌아가면 켜진 칩이 안 보인다. */}
          <ChipRow key={state.listSerial} selected={p.chip} onSelect={p.onChip} />
          {isPartial(p.initial, loaded, placesLoaded, placeTotal) && (
            <VStack spacing={3} align="start" pad="0 14px 8px" style={{ flex: 'none' }}>
              {filling ? (
                <>
                  <Txt size={FONT.caption} weight="semibold">{tr('이 분류를 %2$d곳 중 %1$d곳에서 찾았어요', placesLoaded, placeTotal)}</Txt>
                  <Txt size={FONT.caption2} color="secondary">{state.morePlacesFailed ? tr('나머지를 확인하지 못했어요') : tr('나머지를 확인하는 중이에요')}</Txt>
                </>
              ) : (
                <>
                  <Txt size={FONT.caption} weight="semibold">{tr('%2$d곳 중 %1$d곳을 보고 있어요', placesLoaded, placeTotal)}</Txt>
                  <HStack spacing={6}>
                    <Txt size={FONT.caption2} color="secondary">{p.nearby ? tr('지도를 확대하면 더 보여요') : tr('목록을 내리면 이어서 보여요')}</Txt>
                    {p.nearby && (
                      <Pressable plain onClick={p.onSearchViewport}>
                        <Txt size={FONT.caption2} weight="semibold" color="accent">{tr('이 화면 다시 검색')}</Txt>
                      </Pressable>
                    )}
                  </HStack>
                </>
              )}
            </VStack>
          )}
        </>
      )}

      {/* 탭이 바뀌거나 새 검색이 깔리면 스크롤 뷰째 새로 만든다 — 앞 검색에서 내려가 있던 자리가 남지 않게(실측). */}
      <div key={`${p.tab}-${state.listSerial}`} className="scroll-y" data-sheet-scroll style={{ flex: 1, minHeight: 0, touchAction: 'pan-y' }}>
        {p.tab === 'work' ? <WorkRows {...p} /> : <PlaceRows {...p} wantsMore={wantsMore} />}
      </div>
    </VStack>
  )
}

function WorkRows(p: SearchListProps) {
  const { state, scene } = p
  return (
    <>
      {state.contents.map((content) => (
        <div key={content.id}>
          <RowButton onOpen={() => p.onOpenContent(content)}>
            <WorkRow content={content} onLike={() => p.onLike(content.id)} liked={p.likedIds.has(content.id)} />
          </RowButton>
          <ListDivider />
        </div>
      ))}
      {state.moreContentsFailed ? (
        <ListMoreRetry onRetry={() => scene.loadMoreContents()} />
      ) : hasMore(state.contentPaging) ? (
        // 이 줄이 화면에 들어오면 다음 쪽을 받는다. 한 쪽이 붙으면 offset 이 바뀌어 다시 본다.
        <LoadMoreSentinel watch={`${state.contentPaging.offset}-${state.phase.kind === 'loaded'}`} onVisible={() => scene.loadMoreContents()}>
          <ListMoreSpinner />
        </LoadMoreSentinel>
      ) : state.contents.length === 0 && state.phase.kind === 'loaded' ? (
        <ListEmptyNote title={tr('검색된 작품이 없어요')} detail="" />
      ) : null}
    </>
  )
}

function PlaceRows(p: SearchListProps & { wantsMore: boolean }) {
  const { state, scene } = p
  // 번호는 지도 핀과 같은 배열의 같은 순서다 — 「3번 행 = 3번 핀」.
  return (
    <>
      {p.visiblePlaces.map((place, index) => (
        <div key={place.id}>
          <RowButton onOpen={() => p.onOpenPlace(place)}>
            <PlaceRow place={place} number={index + 1} onAdd={() => p.onSave(place)} saved={p.cartIds.has(place.id)} />
          </RowButton>
          <ListDivider />
        </div>
      ))}
      {state.morePlacesFailed && p.wantsMore ? (
        // 칩이 켜져 있으면 한 쪽이 아니라 끝까지 다시 받는다.
        <ListMoreRetry onRetry={() => (p.chip === CHIP_ALL ? scene.loadMorePlaces() : p.onChipFillRetry())} />
      ) : p.wantsMore ? (
        <LoadMoreSentinel watch={`${state.placePaging.offset}-${state.phase.kind === 'loaded'}`} onVisible={() => scene.loadMorePlaces()}>
          <ListMoreSpinner />
        </LoadMoreSentinel>
      ) : p.visiblePlaces.length === 0 && state.phase.kind === 'loaded' ? (
        <EmptyPlaces chip={p.chip} nearby={p.nearby} />
      ) : null}
    </>
  )
}

/** 빈 묶음의 안내. 전에는 「음식점·카페」 를 눌러 0건이면 빈 화면뿐이라 고장으로 보였다. */
function EmptyPlaces({ chip, nearby }: { chip: string; nearby: boolean }) {
  if (chip !== CHIP_ALL) return <ListEmptyNote title={tr('이 분류의 촬영지가 없어요')} detail={tr('다른 분류를 누르거나 「전체」 로 돌아가 보세요')} />
  if (nearby) return <ListEmptyNote title={tr('이 지도 안에는 촬영지가 없어요')} detail={tr('지도를 옮기거나 축소한 뒤 다시 검색해 보세요')} />
  return <ListEmptyNote title={tr('검색된 장소가 없어요')} detail="" />
}
