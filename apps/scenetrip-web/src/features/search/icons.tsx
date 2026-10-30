/*
 * 검색 탭·리뷰가 쓰는 SF Symbols 대체 — design/Icon.tsx 의 기본 표에 없는 것만 이 폴더에서 등록한다.
 * 모듈 범위에서 `registerIcon` 을 부르므로 이 파일을 한 번 import 하면 된다(`import '@/features/search/icons'`).
 */
import {
  AlignLeft, ArrowUpLeft, CircleAlert, Clapperboard, Grid2x2, ImagePlus, Maximize2, Pause, ShoppingCart, SquarePen, SquareUser, Store, ZoomIn, ZoomOut,
} from 'lucide-react'
import type { ComponentType, CSSProperties } from 'react'
import { type IconComponentProps, registerIcon } from '@/design/Icon'

type Lucide = ComponentType<{ size?: number; strokeWidth?: number; color?: string; style?: CSSProperties; fill?: string }>
function lucide(L: Lucide, fill = false) {
  return function LucideIcon({ size, strokeWidth, color, style }: IconComponentProps) {
    return <L size={size} strokeWidth={strokeWidth} color={color} fill={fill ? color : 'none'} style={style} />
  }
}

function svg(draw: (p: IconComponentProps) => React.ReactNode) {
  return function Custom(p: IconComponentProps) {
    return (
      <svg width={p.size} height={p.size} viewBox="0 0 24 24" fill="none" stroke={p.color} strokeWidth={p.strokeWidth} strokeLinecap="round" strokeLinejoin="round" aria-hidden style={p.style}>
        {draw(p)}
      </svg>
    )
  }
}

/** 4 갈래 눈금이 달린 과녁 — 네이버·카카오 지도의 현위치 버튼과 같은 모양(`dot.scope`). */
const dotScope = svg(({ color }) => (
  <>
    <circle cx="12" cy="12" r="7.5" />
    <circle cx="12" cy="12" r="1.6" fill={color} stroke="none" />
    <path d="M12 2.5v4M12 17.5v4M2.5 12h4M17.5 12h4" />
  </>
))

/** 가방 + 작은 더하기 배지(`bag.badge.plus`) — 「장바구니에 담기」. */
const bagBadgePlus = svg(() => (
  <>
    {/* 가방 몸통 — 왼쪽 아래는 배지 자리라 열어 둔다 */}
    <path d="M6 13V10.5A2.5 2.5 0 0 1 8.5 8h9A2.5 2.5 0 0 1 20 10.5v8.5a2.5 2.5 0 0 1-2.5 2.5H12" />
    <path d="M9.5 8V6.8a3.5 3.5 0 0 1 7 0V8" />
    <circle cx="6.8" cy="17.6" r="3.7" />
    <path d="M6.8 15.8v3.6M5 17.6h3.6" />
  </>
))

/** 가방(`bag`) */
const bag = svg(() => (
  <>
    <path d="M5 8.5h14l-1 11.5a1.5 1.5 0 0 1-1.5 1.4h-9A1.5 1.5 0 0 1 6 20z" />
    <path d="M8.5 8.5V7a3.5 3.5 0 0 1 7 0v1.5" />
  </>
))

/** 말풍선 안의 별(`star.bubble`) — 리뷰가 없을 때의 빈 상태. */
const starBubble = svg(() => (
  <>
    <path d="M4 5.5A2.5 2.5 0 0 1 6.5 3h11A2.5 2.5 0 0 1 20 5.5v8A2.5 2.5 0 0 1 17.5 16H11l-4.2 3.6a.5.5 0 0 1-.8-.4V16h-.0A2.5 2.5 0 0 1 4 13.5z" />
    <path d="M12 6.2l1.2 2.4 2.6.4-1.9 1.8.5 2.6-2.4-1.3-2.4 1.3.5-2.6-1.9-1.8 2.6-.4z" />
  </>
))

/** 반쪽 별(`star.leadinghalf.filled`) — 왼쪽 절반만 칠한다. */
const starHalf = svg(({ color }) => (
  <>
    <defs>
      <clipPath id="st-half-left">
        <rect x="0" y="0" width="12" height="24" />
      </clipPath>
    </defs>
    <path d="M12 2.8l2.8 5.8 6.3.9-4.6 4.4 1.1 6.3L12 17.2l-5.6 3 1.1-6.3L2.9 9.5l6.3-.9z" />
    <path d="M12 2.8l2.8 5.8 6.3.9-4.6 4.4 1.1 6.3L12 17.2l-5.6 3 1.1-6.3L2.9 9.5l6.3-.9z" fill={color} stroke="none" clipPath="url(#st-half-left)" />
  </>
))

const pauseFill = lucide(Pause, true)

registerIcon('cart', lucide(ShoppingCart))
registerIcon('dot.scope', dotScope)
registerIcon('text.alignleft', lucide(AlignLeft))
registerIcon('arrow.up.left.and.arrow.down.right', lucide(Maximize2))
registerIcon('bag', bag)
registerIcon('bag.badge.plus', bagBadgePlus)
registerIcon('star.bubble', starBubble)
registerIcon('star.leadinghalf.filled', starHalf)
registerIcon('square.and.pencil', lucide(SquarePen))
registerIcon('movieclapper', lucide(Clapperboard))
registerIcon('storefront', lucide(Store))
registerIcon('person.crop.square', lucide(SquareUser))
registerIcon('square.grid.2x2', lucide(Grid2x2))
registerIcon('minus.magnifyingglass', lucide(ZoomOut))
registerIcon('plus.magnifyingglass', lucide(ZoomIn))
registerIcon('arrow.up.left', lucide(ArrowUpLeft))
registerIcon('exclamationmark.circle', lucide(CircleAlert))
registerIcon('pause.fill', pauseFill)
registerIcon('photo.badge.plus', lucide(ImagePlus))
