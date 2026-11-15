/*
 * 담기 피드백 — 정본: docs/project/plans/web-ad-landing.md §9-3.
 *
 *   (a) 담는 순간 사진·카드가 화면 위쪽 「내 지도」 단추로 날아간다      flyToMyMap(fromEl, { imageUrl })
 *   (b) 그 단추의 숫자 배지가 튀어 오른다                              <MyMapButton onPress={…} />
 *   (c) 알림은 기존 CartToast(cartActions 가 띄운다)
 *
 * 랜딩·작품 상세의 모두 담기·더 담기 칸·정보 카드 어디서 담든 이 하나를 쓴다. 날아가는 것은 CSS transform·opacity 뿐
 * (Web Animations API — 새 의존성 없음). 화면에 단추가 없으면(작품 상세 등) 단추가 서는 자리(오른쪽 위)로 날아간다.
 * 움직임 줄이기 설정이면 날지 않고 배지만 바뀐다.
 */
import { type CSSProperties, type JSX, useEffect, useRef, useState } from 'react'
import { Icon } from '@/design/Icon'
import { Pressable } from '@/features/route/ui/Press'
import { color } from '@/features/proto/protoTokens'
import { say, useLang } from '@/i18n'
import { type CartState, useCart } from '@/state/cart'
import { createStore, useStore } from '@/state/storage'
import './keep.css'

/** 「내 지도」 단추에 붙는 표식 — 날아가는 것이 이것을 찾아 내려앉는다. */
export const MY_MAP_TARGET = 'data-my-map-target'
/** 날아가는 시간(ms). */
export const FLY_MS = 620

/** 무엇이 내려앉을 때마다 하나씩 는다 — 배지가 튄다. */
export const myMapLandStore = createStore(0)

const reducedMotion = (): boolean =>
  typeof window !== 'undefined' && window.matchMedia?.('(prefers-reduced-motion: reduce)').matches === true

/**
 * 셈에 드는 담은 수(keep-poi.md §9-5) — 목록에서 빠진 촬영지·폐업한 편의시설은 뺀다.
 * 담은 직후에는 목록(items·pois)보다 id 집합이 앞서 있으므로 id 집합으로 센다(배지가 담는 그 순간 오른다).
 */
export function liveMyMapCount(cart: Pick<CartState, 'items' | 'pois' | 'placeIds' | 'poiIds'>): number {
  const delisted = cart.items.filter((i) => i.delisted === true && cart.placeIds.has(i.placeId)).length
  // 편의시설 칸이 생기기 전에 저장된 장바구니에는 pois·poiIds 가 없을 수 있다(홈은 늘 이 단추를 그리므로 여기서 견딘다).
  const pois = cart.pois ?? []
  const poiIds = cart.poiIds ?? new Set<number>()
  const closed = pois.filter((p) => p.closed === true && poiIds.has(p.id)).length
  return cart.placeIds.size - delisted + (poiIds.size - closed)
}

/**
 * 날아갈 「마이맵」 단추의 자리. 없으면 단추가 서는 자리(오른쪽 위).
 * 홈 머리줄의 단추는 늘 있으므로(web-keep-flow.md §12) 덮개(더 담기·작품 상세) 밑에 깔린 홈 단추나 숨긴 탭(visibility:hidden)의
 * 단추를 고르지 않는다 — 그 자리에서 맨 위에 실제로 보이는 단추만 후보이고, 여럿이면 예전처럼 문서에서 마지막 것(첫 화면 주머니의 순서를 지킨다).
 */
function targetRect(): { x: number; y: number } {
  const shown = Array.from(document.querySelectorAll(`[${MY_MAP_TARGET}]`))
    .filter((el) => typeof el.checkVisibility !== 'function' || el.checkVisibility({ visibilityProperty: true }))
    .map((el) => ({ el, r: el.getBoundingClientRect() }))
    .filter(({ r }) => r.width > 0 && r.height > 0 && r.bottom > 0 && r.top < window.innerHeight)
    .map(({ el, r }) => ({ el, x: r.left + r.width / 2, y: r.top + r.height / 2 }))
  const onTop = shown.filter(({ el, x, y }) => {
    const hit = typeof document.elementFromPoint === 'function' ? document.elementFromPoint(x, y) : null
    return hit !== null && (el.closest('button') ?? el).contains(hit)
  })
  const pick = (onTop.length > 0 ? onTop : shown).at(-1)
  return pick ? { x: pick.x, y: pick.y } : { x: window.innerWidth - 38, y: 34 }
}

/** 날아가기 시작하는 자리(화면 좌표). */
export interface FlyRect {
  left: number
  top: number
  width: number
  height: number
}

export interface FlyOptions {
  /** 날아가는 조각에 깔 사진(썸네일). 없으면 보라 핀 동그라미. */
  imageUrl?: string | null
  /** 이만큼 기다렸다 날아간다(ms) — 여러 장을 차례로 날릴 때. */
  delayMs?: number
}

/**
 * `from`(누른 칸·카드)에서 「내 지도」 단추로 작은 조각을 날린다. 다 날면 배지가 튄다.
 * 그리는 수단이 없는 환경(jsdom)·움직임 줄이기면 배지만 튄다. 돌려주는 약속은 내려앉으면(날지 않으면 곧바로) 풀린다 —
 * 화면을 바꾸기 전에 날던 것을 다 보이고 싶은 쪽(랜딩)이 기다린다.
 */
export function flyToMyMap(from: Element | FlyRect | null | undefined, options: FlyOptions = {}): Promise<void> {
  if (typeof document === 'undefined') return Promise.resolve()
  const land = () => myMapLandStore.set((n) => n + 1)
  const rect = from instanceof Element ? from.getBoundingClientRect() : from
  const probe = document.createElement('div')
  if (!rect || reducedMotion() || typeof probe.animate !== 'function') {
    land()
    return Promise.resolve()
  }
  const size = Math.max(36, Math.min(76, Math.min(rect.width, rect.height)))
  const startX = rect.left + rect.width / 2
  const startY = rect.top + rect.height / 2
  const end = targetRect()
  const dx = end.x - startX
  const dy = end.y - startY
  const piece = probe
  piece.className = 'st-fly-piece'
  piece.setAttribute('aria-hidden', 'true')
  Object.assign(piece.style, {
    left: `${startX - size / 2}px`,
    top: `${startY - size / 2}px`,
    width: `${size}px`,
    height: `${size}px`,
    backgroundImage: options.imageUrl ? `url("${options.imageUrl.replace(/"/g, '%22')}")` : '',
  } satisfies Partial<CSSStyleDeclaration>)
  if (!options.imageUrl) piece.classList.add('st-fly-piece--pin')
  document.body.appendChild(piece)
  // 위로 솟았다 단추로 떨어지는 포물선 — 중간에 한 번 위로 들린다.
  const lift = Math.min(90, Math.abs(dy) * 0.35 + 30)
  const animation = piece.animate(
    [
      { transform: 'translate(0, 0) scale(1)', opacity: 1 },
      { transform: `translate(${dx * 0.45}px, ${dy * 0.45 - lift}px) scale(0.72)`, opacity: 1, offset: 0.5 },
      { transform: `translate(${dx}px, ${dy}px) scale(0.22)`, opacity: 0.35 },
    ],
    { duration: FLY_MS, delay: options.delayMs ?? 0, easing: 'cubic-bezier(0.45, 0, 0.25, 1)', fill: 'both' },
  )
  return new Promise((resolve) => {
    animation.onfinish = () => {
      piece.remove()
      land()
      resolve()
    }
    animation.oncancel = () => {
      piece.remove()
      resolve()
    }
  })
}

/**
 * 저장 결과를 받은 뒤 날린다(web-ad-landing.md §10-3-9) — 새로 담겼을 때(`saved`)만. 실패·이미 담김이면 날리지 않는다.
 * 오늘의 성지·장소 상세처럼 하나를 담는 곳이 쓴다.
 */
export async function flyWhenSaved(save: Promise<string>, from: Element | null | undefined, options: FlyOptions = {}): Promise<void> {
  const result = await save
  if (result === 'saved') await flyToMyMap(from, options)
}

/**
 * 머리줄 오른쪽 「마이맵」 단추 — 지도 기호와 담은 수 배지(§9-3). 랜딩 ③ 뒤 모든 화면에서 같은 자리.
 * 담은 것이 없어도 그린다(배지는 1 이상일 때만) — 2026-11-15 사용자, web-keep-flow.md §12.
 *
 *   variant 'icon'(기본)  44 동그라미에 지도 기호만 — 첫 화면 주머니·마이맵 화면 자신(제목이 이미 「마이맵」).
 *   variant 'pill'        지도 기호 + 「마이맵 / My map」 글자 알약 — 홈·더 담기 머리줄. 「그냥 보면 지도 같아 보인다」(사용자).
 *   variant 'stacked'     지도 기호 아래 작은 글자 — 자리가 좁은 검색 캡슐·작품 상세 머리줄.
 *
 * 날아가는 조각은 지도 기호 자리에 내려앉는다(표식 data-my-map-target 이 기호에 붙는다 — 알약이면 기호, 아이콘이면 단추 전체).
 * `tone="photo"` 는 사진 위(반투명 흰 테두리), 기본은 흰 바탕.
 */
export function MyMapButton({
  onPress, tone = 'plain', variant = 'icon', style,
}: { onPress: () => void; tone?: 'plain' | 'photo'; variant?: 'icon' | 'pill' | 'stacked'; style?: CSSProperties }): JSX.Element {
  useLang()
  const cart = useCart()
  const count = Math.max(0, liveMyMapCount(cart))
  const landed = useStore(myMapLandStore, (n) => n)
  // 배지가 튀는 때 — 무엇이 내려앉았거나 수가 올랐다.
  const [pop, setPop] = useState(0)
  const last = useRef({ landed, count })
  useEffect(() => {
    if (landed !== last.current.landed || count > last.current.count) setPop((n) => n + 1)
    last.current = { landed, count }
  }, [landed, count])

  const photo = tone === 'photo'
  const ink = photo ? '#fff' : color.primaryText
  const label = say('마이맵', 'My map')
  const badgeClass = ['st-badge', variant === 'pill' ? 'st-badge--pill' : variant === 'stacked' ? 'st-badge--stacked' : '', pop > 0 ? 'st-badge--pop' : ''].filter(Boolean).join(' ')
  const badge = count > 0 && (
    <span key={pop} className={badgeClass} aria-hidden>
      {count > 99 ? '99+' : count}
    </span>
  )
  const surface: CSSProperties = {
    background: photo ? 'rgba(255,255,255,0.18)' : 'var(--system-background)',
    border: photo ? '1px solid rgba(255,255,255,0.5)' : 'none',
    boxShadow: photo ? 'none' : '0 2px 10px rgba(0,0,0,0.10)',
    backdropFilter: photo ? 'blur(10px)' : undefined, WebkitBackdropFilter: photo ? 'blur(10px)' : undefined,
  }
  const name = count > 0 ? say(`마이맵 · ${count}곳`, `My map · ${count === 1 ? '1 spot' : `${count} spots`}`) : label

  if (variant === 'icon') {
    return (
      <Pressable
        plain
        onClick={onPress}
        data-my-map-target=""
        aria-label={name}
        className="proto-press"
        style={{ position: 'relative', width: 44, height: 44, flex: 'none', borderRadius: 999, display: 'grid', placeItems: 'center', ...surface, ...style }}
      >
        <Icon name="map" size={20} weight="semibold" color={ink} />
        {badge}
      </Pressable>
    )
  }

  const stacked = variant === 'stacked'
  return (
    <Pressable
      plain
      onClick={onPress}
      aria-label={name}
      className="proto-press"
      style={{
        position: 'relative', flex: 'none', borderRadius: 999, display: 'flex', alignItems: 'center', justifyContent: 'center',
        ...(stacked
          ? { flexDirection: 'column', gap: 1, minWidth: 44, height: 40, padding: '0 4px', background: 'transparent' }
          : { flexDirection: 'row', gap: 4, height: 36, padding: '0 13px 0 10px', ...surface }),
        ...style,
      }}
    >
      <span data-my-map-target="" style={{ position: 'relative', display: 'grid', placeItems: 'center', flex: 'none' }}>
        <Icon name="map" size={stacked ? 18 : 16} weight="semibold" color={ink} />
        {/* 작은 글자 단추는 배지를 기호 오른쪽 위에 — 글자 줄 폭에 걸면 기호를 덮는다. */}
        {stacked && badge}
      </span>
      <span
        style={{ fontSize: stacked ? 10 : 12.5, lineHeight: stacked ? '12px' : '16px', fontWeight: stacked ? 700 : 800, color: ink, whiteSpace: 'nowrap', letterSpacing: stacked ? -0.1 : -0.2 }}
      >
        {label}
      </span>
      {!stacked && badge}
    </Pressable>
  )
}
