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
  const closed = cart.pois.filter((p) => p.closed === true && cart.poiIds.has(p.id)).length
  return cart.placeIds.size - delisted + (cart.poiIds.size - closed)
}

/** 지금 화면에 보이는 「내 지도」 단추의 자리. 없으면 단추가 서는 자리(오른쪽 위). */
function targetRect(): { x: number; y: number } {
  const all = Array.from(document.querySelectorAll(`[${MY_MAP_TARGET}]`))
  for (let i = all.length - 1; i >= 0; i--) {
    const r = all[i]!.getBoundingClientRect()
    if (r.width > 0 && r.height > 0 && r.bottom > 0 && r.top < window.innerHeight) return { x: r.left + r.width / 2, y: r.top + r.height / 2 }
  }
  return { x: window.innerWidth - 38, y: 34 }
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
 * 머리줄 오른쪽 「내 지도」 단추 — 지도 기호와 담은 수 배지(§9-3). 랜딩 ③ 뒤 모든 화면에서 같은 자리.
 * `tone="photo"` 는 사진 위(반투명 흰 테두리), 기본은 흰 동그라미.
 */
export function MyMapButton({ onPress, tone = 'plain', style }: { onPress: () => void; tone?: 'plain' | 'photo'; style?: CSSProperties }): JSX.Element {
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
  return (
    <Pressable
      plain
      onClick={onPress}
      data-my-map-target=""
      aria-label={count > 0 ? say(`내 지도 · ${count}곳`, `My map · ${count === 1 ? '1 spot' : `${count} spots`}`) : say('내 지도', 'My map')}
      className="proto-press"
      style={{
        position: 'relative', width: 44, height: 44, flex: 'none', borderRadius: 999, display: 'grid', placeItems: 'center',
        background: photo ? 'rgba(255,255,255,0.18)' : 'var(--system-background)',
        border: photo ? '1px solid rgba(255,255,255,0.5)' : 'none',
        boxShadow: photo ? 'none' : '0 2px 10px rgba(0,0,0,0.10)',
        backdropFilter: photo ? 'blur(10px)' : undefined, WebkitBackdropFilter: photo ? 'blur(10px)' : undefined,
        ...style,
      }}
    >
      <Icon name="map" size={20} weight="semibold" color={photo ? '#fff' : color.primaryText} />
      {count > 0 && (
        <span key={pop} className={pop > 0 ? 'st-badge st-badge--pop' : 'st-badge'} aria-hidden>
          {count > 99 ? '99+' : count}
        </span>
      )}
    </Pressable>
  )
}
