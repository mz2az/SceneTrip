/*
 * 코스를 짜다가 여기서 바로 장소를 찾아 담는 시트 — iOS RouteTab/RouteSearchSheet.swift (MZ2AZ-247).
 *
 * 앞서 코스에 장소를 넣는 길은 장바구니·핀 찍기뿐이었다. 장바구니는 검색 탭에서 미리 담아 둔 것이라, 코스를 짜다가 한 곳이 떠오르면 편집을 두고
 * 검색 탭으로 건너가 담고 돌아와야 했다(2026-08-24 사용자 지적). 그래서 같은 검색을 편집 화면 안으로 들인다. 닫으면 짜던 자리로 그대로 돌아온다.
 * 장소 이름과 작품 이름 둘 다로 찾는다 — 여행자가 기억하는 것은 장소 이름이 아니라 작품인 경우가 많다.
 */
import { useEffect, useRef, useState } from 'react'
import { Icon } from '@/design/Icon'
import { HStack, Spinner, Txt, VStack } from '@/design/primitives'
import { Pressable } from '../ui/Press'
import { tr, useLang } from '@/i18n'
import { hasMore } from '@/state/shared'
import type { PlaceSummary } from '../models'
import { DetentSheet, GlassButton, NavBar } from '../ui/DetentSheet'
import { ErrorBlock } from '../ui/ErrorBlock'
import { RemoteImage } from '../ui/RemoteImage'
import { usePlaceSearch } from './placeSearch'

export interface PickSheetProps {
  open: boolean
  onClose: () => void
  /** 이미 코스에 담긴 촬영지 — 체크로 보여 주고 다시 못 고르게 한다(2026-08-25 사용자 지적). */
  taken: ReadonlySet<number>
  /** 고르는 대로 바깥에 알린다 — 담기 전에 지도에 빨간 해태로 뜨게 하는 것이다. */
  onPreview: (places: PlaceSummary[]) => void
  onAdd: (places: PlaceSummary[]) => void
}

export function SearchSheet(p: PickSheetProps) {
  return (
    <DetentSheet open={p.open} onClose={p.onClose} label={tr('장소 검색')}>
      {p.open && <SearchBody {...p} />}
    </DetentSheet>
  )
}

function SearchBody({ onClose, taken, onPreview, onAdd }: PickSheetProps) {
  useLang()
  const search = usePlaceSearch()
  const [query, setQuery] = useState('')
  const [picked, setPicked] = useState<number[]>([])
  const first = useRef(true)

  // 글자마다 부르지 않는다 — 입력이 멎으면 한 번. 처음 열 때는 인기순.
  useEffect(() => {
    search.search(query)
    first.current = false
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [query])
  // 시트가 닫히면 미리보기를 비운다 — 담지 않고 나갔는데 빨간 핀이 남아 있으면 안 된다.
  useEffect(() => () => onPreview([]), [onPreview])

  const resolve = (ids: number[]) => ids.map((id) => search.place(id)).filter((v): v is PlaceSummary => v !== undefined)
  const toggle = (id: number) => {
    const next = picked.includes(id) ? picked.filter((v) => v !== id) : [...picked, id]
    setPicked(next)
    onPreview(resolve(next))
  }
  // 고른 순서대로 넣는다. results 순서로 넣으면 사용자가 3·1·2 로 골라도 목록 순서대로 들어가 의도가 사라진다.
  const addAndClose = () => {
    onAdd(resolve(picked))
    onClose()
  }

  const { phase, results } = search
  return (
    <VStack style={{ flex: 1, minHeight: 0 }}>
      <NavBar
        title={tr('장소 검색')}
        leading={<GlassButton onClick={onClose}>{tr('닫기')}</GlassButton>}
        trailing={<GlassButton strong disabled={picked.length === 0} onClick={addAndClose}>{tr('추가 %d', picked.length)}</GlassButton>}
      />
      <div style={{ padding: '0 16px 10px', flex: 'none' }}>
        <SearchField value={query} onChange={setQuery} placeholder={tr('장소나 작품 이름')} />
      </div>
      {phase.kind === 'failed' ? (
        <ErrorBlock failure={phase.failure} onRetry={() => search.search(query)} />
      ) : phase.kind === 'loading' && results.length === 0 ? (
        <VStack align="center" justify="center" style={{ flex: 1 }}><Spinner /></VStack>
      ) : phase.kind === 'loaded' && results.length === 0 ? (
        <NoResults query={query} />
      ) : (
        <div className="scroll-y" style={{ flex: 1, minHeight: 0, background: 'var(--system-background)' }}>
          {results.map((place, i) => (
            <PlaceRow key={place.id} place={place} taken={taken.has(place.id)} order={picked.indexOf(place.id)} last={i === results.length - 1} onPress={() => toggle(place.id)} />
          ))}
          <More search={search} />
        </div>
      )}
    </VStack>
  )
}

/** `.searchable(placement: .navigationBarDrawer)` — 둥근 흰 검색칸. */
export function SearchField({ value, onChange, placeholder }: { value: string; onChange: (v: string) => void; placeholder: string }) {
  return (
    <HStack spacing={8} style={{ height: 44, padding: '0 16px', borderRadius: 999, background: 'rgba(255,255,255,0.9)', boxShadow: '0 1px 8px rgba(0,0,0,0.06)' }}>
      <Icon name="magnifyingglass" size={18} />
      <input
        className="bare-input"
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder={placeholder}
        maxLength={400}
        enterKeyHint="search"
        autoCapitalize="off"
        autoCorrect="off"
        style={{ flex: 1, fontSize: 17 }}
        aria-label={placeholder}
      />
      {value && (
        <Pressable plain onClick={() => onChange('')} aria-label={tr('닫기')}>
          <Icon name="xmark.circle.fill" size={18} color="secondary" />
        </Pressable>
      )}
    </HStack>
  )
}

function NoResults({ query }: { query: string }) {
  const ko = tr('가') === '가'
  return (
    <VStack spacing={6} align="center" justify="center" style={{ flex: 1, padding: 24 }}>
      <Icon name="magnifyingglass" size={40} color="secondary" />
      <Txt size={22} weight="bold" align="center">{ko ? '검색 결과 없음' : 'No Results'}</Txt>
      <Txt size={15} color="secondary" align="center">{ko ? `‘${query.trim()}’에 대한 새로운 검색을 시도해 보세요.` : `Check the spelling or try a new search.`}</Txt>
    </VStack>
  )
}

function PlaceRow({ place, taken, order, last, onPress }: { place: PlaceSummary; taken: boolean; order: number; last: boolean; onPress: () => void }) {
  const work = place.contents?.[0]?.title
  return (
    <div style={{ opacity: taken ? 0.5 : 1 }}>
      <Pressable plain disabled={taken} onClick={onPress} style={{ width: '100%', textAlign: 'left' }}>
        <HStack spacing={12} style={{ padding: '10px 16px' }}>
          <RemoteImage url={place.imageUrl} symbol="mappin.and.ellipse" size={{ w: 44, h: 44 }} radius={8} />
          <VStack spacing={3} align="start" style={{ flex: 1, minWidth: 0 }}>
            <Txt size={15} weight="medium">{place.name}</Txt>
            {/* 어느 작품에 나왔는지가 이름보다 중요한 단서일 때가 많다. */}
            {work && <Txt size={12} color="accent">{work}</Txt>}
            {place.address && <Txt size={11} color="tertiary" lines={1}>{place.address}</Txt>}
          </VStack>
          {/* 고른 순서를 번호로 보여 준다 — 여러 곳을 고를 때 어느 순서로 들어가는지 미리 알아야 담고 나서 다시 끌어 옮기지 않는다. */}
          {taken ? (
            <Icon name="checkmark.circle.fill" size={20} color="color-mix(in srgb, var(--accent) 45%, transparent)" />
          ) : order >= 0 ? (
            <span style={{ width: 22, height: 22, borderRadius: '50%', background: 'var(--accent)', display: 'grid', placeItems: 'center' }}>
              <Txt size={11} weight="heavy" color="white">{order + 1}</Txt>
            </span>
          ) : (
            <Icon name="plus.circle" size={20} color="tertiary" />
          )}
        </HStack>
      </Pressable>
      {!last && <div style={{ height: 1, marginLeft: 72, marginRight: 16, background: 'var(--separator)', transform: 'scaleY(0.3333)' }} />}
    </div>
  )
}

/** 목록 끝 — 더 있으면 이어 받고, 못 받았으면 다시 누르게 한다. 저절로 다시 부르지 않는다(서버가 아픈데 목록 끝이 보이는 내내 두드리게 된다). */
function More({ search }: { search: ReturnType<typeof usePlaceSearch> }) {
  const sentinel = useRef<HTMLDivElement>(null)
  const more = hasMore(search.paging) && search.phase.kind === 'loaded'
  const { loadMore, moreFailed } = search
  useEffect(() => {
    const el = sentinel.current
    if (!el || !more || moreFailed) return
    const io = new IntersectionObserver((entries) => entries.some((e) => e.isIntersecting) && loadMore(), { rootMargin: '120px' })
    io.observe(el)
    return () => io.disconnect()
  }, [more, moreFailed, loadMore, search.paging.offset])
  if (moreFailed) {
    return (
      <Pressable plain onClick={loadMore} style={{ width: '100%', padding: '16px 0', textAlign: 'center' }}>
        <Txt size={13} color="accent" align="center">{tr('더 불러오지 못했어요 · 다시 시도')}</Txt>
      </Pressable>
    )
  }
  if (!more) return null
  return (
    <div ref={sentinel} style={{ display: 'grid', placeItems: 'center', padding: '18px 0' }}>
      <Spinner />
    </div>
  )
}
