/*
 * 내 지도 — 덮개 `/my-map`. 정본: docs/project/plans/web-keep-flow.md §5-3·§6.
 *
 *   머리줄 「내 지도 · N곳」 + 뒤로
 *   위 약 42% 지도 — 담은 곳 핀(좌표가 있고 목록에서 빠지지 않은 것만), 핀 전부가 보이게 맞춘다
 *   아래 스크롤 목록 — 작품별 묶음(mapGroups)마다 소제목 「작품 · N곳」 과 줄(사진·이름·주소)
 *   아래 고정 — 주 「이 N곳으로 계획 짜기」(openPlanFromSaved), 보조 「더 담기」(/trending)
 *
 * 받는 중 · 비었음 · 실패(다시 시도) · 됨. 덮개는 닫히면 내려간 뒤 빠지므로(Cover 의 usePresence) 숨은 지도가 타일을 받지 않는다.
 */
import { type JSX, useEffect, useMemo, useState } from 'react'
import { track, webEvent } from '@/analytics'
import { paths } from '@/app/routes'
import { useTabRouter } from '@/app/tabRouter'
import { Icon } from '@/design/Icon'
import { HStack, Spinner, Txt, VStack } from '@/design/primitives'
import { PrimaryButton, SecondaryButton } from '@/features/proto/protoParts'
import { color, contained, radius } from '@/features/proto/protoTokens'
import { unavailableMessage } from '@/features/route/availability'
import { Pressable } from '@/features/route/ui/Press'
import { MapView } from '@/features/search/map/MapView'
import { CAMERA_TOP_INSET } from '@/features/search/map/mapRules'
import type { PlaceSummary } from '@/features/search/sceneData'
import { say, useLang } from '@/i18n'
import { smallImage } from '@/shared/smallImage'
import { type CartItem, cartActions, useCart } from '@/state/cart'
import { spots } from '@/shared/spots'
import { mapGroups } from './keepRules'
import { useKeep } from './keepStore'

/** 지도 칸의 높이 — 덮개 높이의 약 42%(위 절반쯤, 목록이 한두 줄 더 보이게). */
const MAP_SHARE = '42%'

const hasCoords = (i: CartItem): i is CartItem & { latitude: number; longitude: number } =>
  typeof i.latitude === 'number' && typeof i.longitude === 'number' && !Number.isNaN(i.latitude) && !Number.isNaN(i.longitude)

/** 담은 항목 → 촬영지 상세·지도 핀이 받는 모양. 좌표가 없으면 0 으로 둔다(상세는 id 로 다시 받는다). */
function placeOf(i: CartItem): PlaceSummary {
  return {
    id: i.placeId,
    name: i.name,
    address: i.address ?? null,
    latitude: i.latitude ?? 0,
    longitude: i.longitude ?? 0,
    imageUrl: i.imageUrl ?? null,
    imageThumbnailUrl: i.imageThumbnailUrl ?? null,
  }
}

export function MyMapCover({ onClose }: { onClose: () => void }): JSX.Element {
  useLang()
  const router = useTabRouter()
  const cart = useCart()
  // `?keep=` 으로 넘어와 담는 중이면 빈 화면 대신 받는 중(§9-4).
  const { arriving } = useKeep()

  // 홈을 거치지 않고 주소로 바로 들어온 경우 — 아직 받은 적이 없으면 받는다.
  useEffect(() => {
    if (cart.loadState === 'notLoaded') void cartActions.refresh()
    // eslint-disable-next-line react-hooks/exhaustive-deps -- 열릴 때 한 번.
  }, [])

  const items = cart.items
  const count = items.length
  // 다시 받는 중이거나 다시 받기가 실패해도 들고 있는 목록은 그대로 보인다.
  const phase: 'loading' | 'failed' | 'empty' | 'loaded' =
    count > 0 ? 'loaded' : arriving ? 'loading' : cart.loadState === 'failed' ? 'failed' : cart.loadState === 'loaded' ? 'empty' : 'loading'

  const openPlace = (item: CartItem) => {
    track(webEvent.myMap('open_place'))
    // 상세의 「뒤로」 가 내 지도로 돌아오게(§9-9).
    router.openPlace(placeOf(item), { returnTo: paths.myMap })
  }

  return (
    <VStack spacing={0} align="stretch" style={{ height: '100%', minHeight: 0, background: 'var(--grouped-background)', paddingTop: 'var(--safe-top)' }}>
      <HStack spacing={4} style={{ padding: '4px 8px', minHeight: 52, flex: 'none' }}>
        <Pressable
          plain
          onClick={onClose}
          aria-label={say('뒤로', 'Back')}
          style={{ width: 44, height: 44, display: 'grid', placeItems: 'center', flex: 'none' }}
        >
          <Icon name="chevron.left" size={20} weight="semibold" />
        </Pressable>
        <Txt as="h1" size={19} weight="bold" lines={1} style={{ margin: 0 }}>
          {count > 0 ? say(`내 지도 · ${count}곳`, `My map · ${spots(count)}`) : say('내 지도', 'My map')}
        </Txt>
      </HStack>

      {phase === 'loading' && (
        <div style={{ flex: 1, display: 'grid', placeItems: 'center' }} aria-busy="true">
          <Spinner size={28} />
        </div>
      )}

      {phase === 'failed' && (
        <Notice text={say('못 불러왔어요', "Couldn't load your map")}>
          <PrimaryButton onClick={() => void cartActions.refresh()}>{say('다시 시도', 'Try again')}</PrimaryButton>
        </Notice>
      )}

      {phase === 'empty' && (
        <Notice text={say('아직 담은 곳이 없어요', 'Nothing saved yet')}>
          <PrimaryButton onClick={() => router.openTrending()}>{say('작품 둘러보기', 'Browse shows')}</PrimaryButton>
        </Notice>
      )}

      {phase === 'loaded' && (
        <>
          <PinMap items={items} onTapPin={(place) => {
            const item = items.find((i) => i.placeId === place.id)
            if (item) openPlace(item)
          }} />
          <GroupedList items={items} onOpen={openPlace} />
          <div
            style={{
              flex: 'none', padding: '12px 20px calc(12px + var(--safe-bottom))', background: 'var(--system-background)',
              borderTop: '0.5px solid var(--separator)',
            }}
          >
            <VStack spacing={8} align="stretch">
              <PrimaryButton
                onClick={() => {
                  track(webEvent.myMap('plan'))
                  track(webEvent.planStart('my_map'))
                  router.openPlanFromSaved()
                }}
              >
                {say(`이 ${count}곳으로 계획 짜기`, `Plan a trip with these ${spots(count)}`)}
              </PrimaryButton>
              <SecondaryButton
                onClick={() => {
                  track(webEvent.myMap('add_more'))
                  router.openTrending()
                }}
              >
                {say('더 담기', 'Add more')}
              </SecondaryButton>
            </VStack>
          </div>
        </>
      )}
    </VStack>
  )
}

function Notice({ text, children }: { text: string; children: JSX.Element }) {
  return (
    <VStack spacing={16} align="center" style={{ flex: 1, justifyContent: 'center', padding: '24px 32px calc(24px + var(--safe-bottom))' }}>
      <Icon name="mappin.and.ellipse" size={36} color="secondary" />
      <Txt size={17} weight="semibold" align="center">{text}</Txt>
      <div style={{ width: '100%', maxWidth: 320 }}>{children}</div>
    </VStack>
  )
}

/**
 * 담은 곳 핀 지도 — 검색 탭의 지도(MapView)를 그대로 쓴다. 그 지도는 카메라를 맞출 때 위쪽 CAMERA_TOP_INSET(검색바 자리)을 비우므로,
 * 지도 판을 그만큼 위로 늘려 칸 밖(잘린 곳)에 두면 핀이 칸 전체에 고르게 맞춰진다. 아래 로고·축척은 칸 안에 그대로 보인다.
 */
function PinMap({ items, onTapPin }: { items: readonly CartItem[]; onTapPin: (place: PlaceSummary) => void }) {
  // 좌표가 없거나 목록에서 빠진 촬영지는 핀을 꽂지 않는다(목록에만).
  const pins = useMemo(() => items.filter((i) => hasCoords(i) && i.delisted !== true).map(placeOf), [items])
  const pinKey = pins.map((p) => `${p.id}@${p.latitude},${p.longitude}`).join('|')
  // 핀이 바뀔 때마다 한 번 전체에 맞춘다(첫 마운트 포함 — 지도가 늦게 서도 토큰이 남아 있어 그때 맞춘다).
  const [fitToken, setFitToken] = useState(0)
  useEffect(() => setFitToken((n) => n + 1), [pinKey])

  return (
    <div style={{ position: 'relative', flex: `0 0 ${MAP_SHARE}`, minHeight: 180, overflow: 'hidden', background: color.brandFill }}>
      {pins.length > 0 ? (
        <div style={{ position: 'absolute', left: 0, right: 0, bottom: 0, top: -CAMERA_TOP_INSET }}>
          <MapView
            pins={pins}
            numbered={false}
            koreaToken={0}
            locateToken={0}
            fitToken={fitToken}
            focusToken={0}
            focus={null}
            panToken={0}
            pan={null}
            sheetHeight={0}
            onTapPin={onTapPin}
            onLocateFailure={() => {}}
            onReady={() => {}}
          />
        </div>
      ) : (
        <div style={{ position: 'absolute', inset: 0, display: 'grid', placeItems: 'center', padding: 24 }}>
          <Txt size={14} weight="semibold" align="center" style={{ color: '#fff' }}>
            {say('지도에 표시할 위치가 없어요', 'No locations to show on the map')}
          </Txt>
        </div>
      )}
    </div>
  )
}

/** 작품별 묶음 목록. 묶음 순서·제목은 mapGroups 를 따른다(출처 없는 것은 맨 뒤 「그 밖」). */
function GroupedList({ items, onOpen }: { items: readonly CartItem[]; onOpen: (item: CartItem) => void }) {
  const groups = mapGroups(items)
  return (
    <div className="scroll-y" style={{ flex: 1, minHeight: 0, ...contained }}>
      <VStack spacing={18} align="stretch" style={{ padding: '16px 20px 20px' }}>
        {groups.map((g) => {
          const rows = items.filter((i) => (i.sourceContentId ?? null) === g.contentId)
          return (
            <VStack key={g.contentId ?? 'other'} spacing={8} align="stretch" aria-label={g.title ?? say('그 밖', 'Other')} role="group">
              <Txt as="h2" size={15} weight="bold" lines={1} style={{ margin: 0 }}>
                {g.title ?? say('그 밖', 'Other')}
                <span style={{ color: color.primaryText }}>{` · ${say(`${g.count}곳`, spots(g.count))}`}</span>
              </Txt>
              <VStack spacing={0} align="stretch" style={{ borderRadius: radius.m, overflow: 'hidden', background: 'var(--system-background)' }}>
                {rows.map((item, index) => (
                  <PlaceRow key={item.placeId} item={item} first={index === 0} onOpen={() => onOpen(item)} />
                ))}
              </VStack>
            </VStack>
          )
        })}
      </VStack>
    </div>
  )
}

function PlaceRow({ item, first, onOpen }: { item: CartItem; first: boolean; onOpen: () => void }) {
  const gone = item.delisted === true
  const photo = smallImage(item.imageThumbnailUrl, item.imageUrl)
  const body = (
    <HStack spacing={12} style={{ padding: '10px 12px', borderTop: first ? undefined : '0.5px solid var(--separator)', opacity: gone ? 0.5 : 1 }}>
      <div style={{ width: 56, height: 56, flex: 'none', borderRadius: radius.s, overflow: 'hidden', background: color.brandFill }}>
        {photo && <img src={photo} alt="" loading="lazy" referrerPolicy="no-referrer" style={{ width: '100%', height: '100%', objectFit: 'cover' }} />}
      </div>
      <VStack spacing={2} align="start" style={{ flex: 1, minWidth: 0 }}>
        <Txt size={15} weight="semibold" lines={2}>{item.name}</Txt>
        {gone ? (
          <Txt size={12} color="secondary" lines={1}>{unavailableMessage('place')}</Txt>
        ) : (
          item.address && <Txt size={12} color="secondary" lines={1}>{item.address}</Txt>
        )}
      </VStack>
      {!gone && <Icon name="chevron.right" size={13} color="secondary" />}
    </HStack>
  )
  // 목록에서 빠진 촬영지는 상세가 없으므로 누를 수 없게 흐리게만 둔다.
  if (gone) return body
  return (
    <Pressable plain onClick={onOpen} style={{ width: '100%', textAlign: 'left' }}>
      {body}
    </Pressable>
  )
}
