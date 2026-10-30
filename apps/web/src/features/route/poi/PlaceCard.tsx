/*
 * 핀을 눌렀을 때 뜨는 **정보 카드** — iOS RouteTab/RoutePlaceCard.swift (2026-08-27).
 *
 * 분류·주소·전화를 보여 주고, 더 보려면 네이버 지도로 넘긴다. **우리 자료만 보여 준다**(2026-10-05, MZ2AZ-354) — 사진·영업시간·리뷰 수·별점은 네이버 비공식 주소에서
 * 가져오던 것이라 걷어냈다. 메뉴·예약·리뷰는 「네이버 지도에서 보기」가 그쪽 화면으로 넘긴다 — 옮겨 담지 않는다.
 *
 * 연결: 코스에 담기(`onAdd`/`onRemove`)·길찾기(`onReroute`)·리뷰 시트(`onOpenReviews`)·사진첩(`onOpenPhotos`)은 모두 콜백이다 — 이 카드는 코스 데이터를 모른다.
 */
import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'
import { HStack, Pressable, Spinner, Txt, VStack, rr, shadow } from '@/design/primitives'
import { Icon } from '@/design/Icon'
import { tr } from '@/i18n'
import './icons'
import { CardPhotoStrip, RatingLine } from './CardParts'
import { PoiInfoSection } from './PoiInfoSection'
import { type PlaceTarget, targetOf } from './placePoiLink'
import { fetchCard as defaultFetchCard } from './poiApi'
import { type PlaceCardData, cardKeeps } from './poiCard'
import { poiHeadingOf } from './poiLabelLines'
import { type RoutePlace, placeLabel, placeShownMeters } from './poiPlace'

export interface PlaceCardProps {
  place: RoutePlace
  /** 「경로에 추가」. 주면 버튼이 뜬다. */
  onAdd?: () => void
  /** 이미 코스에 들어 있는가. 그러면 추가 단추가 체크 표시로 바뀌고, 한 번 더 누르면 경로에서 뺀다(2026-08-27). */
  added?: boolean
  onRemove?: () => void
  /** 「여기로 길찾기」. 여행 중 화면에서만 준다 — 계획 화면에서는 갈아탈 경로 자체가 없다. */
  onReroute?: () => void
  onClose: () => void
  /** 별점 줄을 눌렀다 — 리뷰 시트를 올린다. `title` 은 카드에 적힌 그대로의 한 줄 머리줄(MZ2AZ-367). */
  onOpenReviews?: (target: PlaceTarget, title: string) => void
  onOpenPhotos?: (photos: PlaceCardData['photos'], index: number, title: string) => void
  /** 값이 바뀌면 상세를 다시 받는다(리뷰 시트·사진첩을 닫은 뒤). 실패하면 가진 것을 둔다. */
  reloadToken?: number
  /** 시험·화면 확인이 끼워 넣는다. */
  fetchCard?: (place: RoutePlace) => Promise<PlaceCardData | null>
}

const NAVER_BG = 'rgba(5, 199, 89, 0.12)' // Color(red: 0.02, green: 0.78, blue: 0.35).opacity(0.12)
const NAVER_FG = 'rgb(5, 153, 71)' // Color(red: 0.02, green: 0.60, blue: 0.28)
const CARD_RADIUS = 16
const PIN_LIGHT = '143, 204, 247'
const PIN_DEEP = '122, 105, 237'

export function PlaceCard({ place, onAdd, added = false, onRemove, onReroute, onClose, onOpenReviews, onOpenPhotos, reloadToken = 0, fetchCard = defaultFetchCard }: PlaceCardProps) {
  const [card, setCard] = useState<PlaceCardData | null>(null)
  /** `card` 가 어느 장소의 것인가. 카드가 열린 채 다른 점을 누르면 앞 가게의 카드가 한 프레임 남는다 — 머리줄이 앞 가게 이름을 적지 않게 가른다. */
  const [cardFor, setCardFor] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const cardRef = useRef<{ card: PlaceCardData | null; for: string | null }>({ card: null, for: null })
  cardRef.current = { card, for: cardFor }

  const load = useCallback(
    async (isNewPlace: boolean) => {
      if (isNewPlace) setLoading(true)
      const loaded = await fetchCard(place)
      // 떠 있는 카드를 다시 읽다 실패했으면 가진 것을 둔다. 핀 전환은 먼저 비운다.
      const shown = cardRef.current.for === place.id ? cardRef.current.card : null
      if (!cardKeeps(shown, loaded)) {
        setCard(loaded)
        setCardFor(place.id)
      }
      setLoading(false)
    },
    [fetchCard, place],
  )

  // 핀을 갈아탈 때마다 다시 받는다 — 앞 가게 정보가 그대로 남으면 안 된다(2026-08-27).
  useEffect(() => {
    let alive = true
    setCard(null)
    setCardFor(null)
    void load(true).catch(() => alive && setLoading(false))
    return () => {
      alive = false
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [place.id])

  useEffect(() => {
    if (reloadToken > 0) void load(false)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [reloadToken])

  const fresh = cardFor === place.id ? card : null
  const label = placeLabel(place)
  /** 제목 — 상세가 왔으면 그 값이 먼저다. 가이드가 찾아 준 곳은 목록에 영어 이름이 없고 상세에만 있다. */
  const shownTitle = fresh?.title ?? label.title
  /** 영어 이름이 없는 가게는 한글 이름 아래 읽는 법을 적는다(MZ2AZ-360). 제목과 같은 쪽의 값을 쓴다. */
  const shownReading = fresh?.title == null ? label.reading : fresh.reading
  const meters = placeShownMeters(place)
  const target = targetOf(place)
  const reviewTitle = poiHeadingOf(shownTitle, shownReading)

  return (
    <VStack
      align="stretch"
      style={{
        position: 'relative',
        background: `linear-gradient(135deg, rgba(${PIN_LIGHT}, 0.16), rgba(${PIN_DEEP}, 0.07)), var(--system-background)`,
        boxShadow: `${shadow('rgba(0,0,0,0.22)', 12, 4)}, inset 0 0 0 1px rgba(${PIN_LIGHT}, 0.5)`,
        ...rr(CARD_RADIUS),
        overflow: 'hidden',
      }}
    >
      <CardHeader title={shownTitle} reading={shownReading} meters={meters} naverUrl={fresh?.naverUrl ?? null} onClose={onClose} />

      {/* 별점 한 줄 — 서버가 별점을 실어 줄 때만 보인다(MZ2AZ-363). */}
      {target && (
        <div style={{ padding: '0 14px 8px' }}>
          <RatingLine rating={fresh?.rating} onTap={() => onOpenReviews?.(target, reviewTitle)} />
        </div>
      )}

      {loading ? (
        <HStack spacing={8} pad={14}>
          <Spinner size={16} />
          <Txt size={12} color="secondary">{tr('불러오는 중입니다')}</Txt>
        </HStack>
      ) : card && cardFor === place.id ? (
        <Found card={card} onAdd={onAdd} added={added} onRemove={onRemove} onReroute={onReroute} onOpenPhotos={onOpenPhotos ? (photos, i) => onOpenPhotos(photos, i, reviewTitle) : undefined} />
      ) : (
        <Missing category={label.category} />
      )}
    </VStack>
  )
}

/**
 * 이름 · 「네이버 지도에서 보기」 · 닫기. **한 줄에 다 들어가면 한 줄, 아니면 링크가 이름 아래로 내려간다.** 가이드 창 안에서 펼친 카드는 좁다 —
 * 링크를 줄이면 「View on…」으로 잘리고, 링크 폭을 지키면 이름이 두 글자씩 세 줄로 쪼개졌다(2026-10-05 실기). 넓은 카드에서는 이름 옆에 선다.
 */
function CardHeader({ title, reading, meters, naverUrl, onClose }: { title: string; reading: string | null; meters: number | null; naverUrl: string | null; onClose: () => void }) {
  const probe = useRef<HTMLDivElement>(null)
  const [fits, setFits] = useState(true)
  useLayoutEffect(() => {
    const el = probe.current
    if (!el) return
    const measure = () => setFits(el.scrollWidth <= el.clientWidth + 0.5)
    measure()
    const ro = new ResizeObserver(measure)
    ro.observe(el)
    return () => ro.disconnect()
  }, [title, reading, meters, naverUrl])

  return (
    <div style={{ padding: '12px 14px 8px', position: 'relative' }}>
      {/* 한 줄 폭을 그대로 요구하는 재기용 — 보이지 않는다. */}
      <div ref={probe} aria-hidden style={{ position: 'absolute', visibility: 'hidden', left: 14, right: 14, height: 0, overflow: 'hidden' }}>
        <HStack align="start" style={{ width: 'max-content' }}>
          <TitleBlock title={title} reading={reading} meters={meters} wraps={false} />
          <div style={{ width: 8 }} />
          {naverUrl && <NaverLink url={naverUrl} />}
          <CloseButton onClose={onClose} />
        </HStack>
      </div>
      {fits ? (
        <HStack align="start">
          <div style={{ minWidth: 0, flex: 'none', maxWidth: '100%' }}>
            <TitleBlock title={title} reading={reading} meters={meters} wraps={false} />
          </div>
          <div style={{ flex: 1, minWidth: 8 }} />
          {naverUrl && <NaverLink url={naverUrl} />}
          <CloseButton onClose={onClose} />
        </HStack>
      ) : (
        <VStack spacing={6} align="start">
          <HStack align="start" fill>
            <div style={{ flex: 1, minWidth: 0 }}>
              <TitleBlock title={title} reading={reading} meters={meters} wraps />
            </div>
            <div style={{ width: 8 }} />
            <CloseButton onClose={onClose} />
          </HStack>
          {naverUrl && <NaverLink url={naverUrl} />}
        </VStack>
      )}
    </div>
  )
}

function TitleBlock({ title, reading, meters, wraps }: { title: string; reading: string | null; meters: number | null; wraps: boolean }) {
  return (
    <VStack spacing={2} align="start">
      <Txt size={17} weight="semibold" lines={wraps ? undefined : 1} style={wraps ? undefined : { whiteSpace: 'nowrap' }}>{title}</Txt>
      {reading && <Txt size={12} color="secondary" lines={wraps ? undefined : 1} style={wraps ? undefined : { whiteSpace: 'nowrap' }}>{reading}</Txt>}
      {/* 기준이 지도 중심일 뿐인 거리는 적지 않는다(`placeShownMeters`, MZ2AZ-367). */}
      {meters !== null && <Txt size={12} color="secondary" style={{ whiteSpace: 'nowrap' }}>{`${meters} m`}</Txt>}
    </VStack>
  )
}

/** 더 보려면 네이버로 — 사진·메뉴·예약·리뷰는 그쪽에 있다. 글자째 둔 미니 캡슐이다(아이콘만 두면 「더 보기」인지 아무도 모른다 — 2026-08-27). 갈 곳이 없으면 버튼도 없다(MZ2AZ-374). */
function NaverLink({ url }: { url: string }) {
  return (
    <a href={url} target="_blank" rel="noopener noreferrer" style={{ flex: 'none' }}>
      <HStack spacing={3} pad="5px 8px" style={{ background: NAVER_BG, borderRadius: 999, whiteSpace: 'nowrap' }}>
        <Txt size={11} weight="semibold" style={{ color: NAVER_FG }}>{tr('네이버 지도에서 보기')}</Txt>
        <Icon name="arrow.up.right" size={9} weight="semibold" color={NAVER_FG} />
      </HStack>
    </a>
  )
}

function CloseButton({ onClose }: { onClose: () => void }) {
  return (
    <Pressable plain onClick={onClose} aria-label={tr('닫기')} style={{ width: 30, height: 30, display: 'grid', placeItems: 'center', flex: 'none', cursor: 'pointer' }}>
      <Icon name="xmark" size={13} weight="semibold" color="secondary" />
    </Pressable>
  )
}

function Found({ card, onAdd, added, onRemove, onReroute, onOpenPhotos }: { card: PlaceCardData; onAdd?: () => void; added: boolean; onRemove?: () => void; onReroute?: () => void; onOpenPhotos?: (photos: PlaceCardData['photos'], index: number) => void }) {
  return (
    <>
      {card.photos.length > 0 && (
        <div style={{ paddingBottom: 10 }}>
          <CardPhotoStrip photos={card.photos} onOpen={onOpenPhotos ? (i) => onOpenPhotos(card.photos, i) : undefined} />
        </div>
      )}

      <VStack>
        <Row label={tr('분류')} value={card.category} />
        <Row label={tr('주소')} value={card.address} />
        <Row label={tr('전화')} value={card.phone} />
        <PoiInfoSection info={card.info} />
      </VStack>

      {onAdd && (
        <Pressable
          plain
          onClick={added ? onRemove : onAdd}
          disabled={added && !onRemove}
          style={{
            margin: '12px 14px 0', height: 44, display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 6, cursor: 'pointer',
            background: added ? 'var(--system-gray5)' : 'var(--accent)', ...rr(10),
          }}
        >
          <Icon name={added ? 'checkmark.circle.fill' : 'plus.circle.fill'} size={12} color={added ? 'secondary' : 'white'} style={{ ['--icon-cut' as string]: 'var(--accent)' }} />
          <Txt size={15} weight="semibold" color={added ? 'secondary' : 'white'}>{added ? tr('경로에 있음 · 누르면 빼기') : tr('경로에 추가')}</Txt>
        </Pressable>
      )}

      {onReroute && (
        // 즉석에서 목적지를 이 가게로 바꾼다 — 걷다가 배가 고프면 목적지가 바뀌는 것이 내비게이션이다.
        <Pressable
          plain
          onClick={onReroute}
          style={{
            margin: `${onAdd ? 8 : 12}px 14px 0`, height: 44, display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 6, cursor: 'pointer',
            background: onAdd ? 'color-mix(in srgb, var(--accent) 14%, transparent)' : 'var(--accent)', ...rr(10),
          }}
        >
          <Icon name="location.north.fill" size={12} color={onAdd ? 'accent' : 'white'} />
          <Txt size={15} weight="semibold" color={onAdd ? 'accent' : 'white'}>{tr('여기로 길찾기')}</Txt>
        </Pressable>
      )}

      {/* 네이버 링크는 머리줄이 맡는다. 여기 큰 단추로 두면 표 한 줄이 밀려 화면 밖으로 나간다. */}
      <div style={{ height: 14 }} />
    </>
  )
}

/** 분류·주소·전화 한 줄 — 레이블 76 폭. 영문 주소는 길다: 줄을 바꾼다. */
function Row({ label, value }: { label: string; value: string | null }) {
  if (!value) return null
  return (
    <>
      <HStack align="start" spacing={8} pad="7px 14px">
        <Txt size={12} color="secondary" style={{ width: 76, flex: 'none' }}>{label}</Txt>
        <Txt size={12} style={{ flex: 1, minWidth: 0 }}>{value}</Txt>
      </HStack>
      <div style={{ height: 1, marginLeft: 14, background: 'var(--separator)', transform: 'scaleY(0.3333)', transformOrigin: 'top' }} />
    </>
  )
}

/** 촬영지 상세를 못 받았을 때뿐이다 — 편의시설은 목록이 준 것만으로도 카드가 선다. */
function Missing({ category }: { category: string | null }) {
  return (
    <VStack spacing={6} align="start" pad={14}>
      <Txt size={15} weight="medium">{tr('정보를 불러오지 못했습니다')}</Txt>
      {category && <Txt size={11} color="tertiary">{category}</Txt>}
    </VStack>
  )
}
