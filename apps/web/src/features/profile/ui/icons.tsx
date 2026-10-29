/*
 * 마이페이지가 쓰는 SF Symbols 대체 — design/Icon.tsx 에 없는 이름을 이 폴더에서 등록한다(`registerIcon`).
 * 이름은 iOS 코드의 `Image(systemName:)` 그대로. 모양이 다른 곳은 S3(미세 차이).
 * 공용 이름(`location` 등)과 겹치는 것은 덮지 않고 `st.` 접두를 붙였다.
 */
import { ArrowUpRight, CircleAlert, FileText, Images, MessageSquareText, Route, ShoppingBag, SquarePen, Store, Clapperboard, UserRoundCheck, WifiOff, Navigation, Hand } from 'lucide-react'
import type { ComponentType, CSSProperties } from 'react'
import { registerIcon } from '@/design/Icon'

interface P {
  size: number
  strokeWidth: number
  color: string
  style?: CSSProperties
}
type Lucide = ComponentType<{ size?: number; strokeWidth?: number; color?: string; style?: CSSProperties; fill?: string }>

const lucide =
  (L: Lucide, fill = false) =>
  ({ size, strokeWidth, color, style }: P) => <L size={size} strokeWidth={strokeWidth} color={color} fill={fill ? color : 'none'} style={style} />

const svg = (draw: (color: string) => React.ReactNode) =>
  function Drawn({ size, color, style }: P) {
    return (
      <svg width={size} height={size} viewBox="0 0 24 24" style={style} aria-hidden>
        {draw(color)}
      </svg>
    )
  }

/** SF Symbols 는 같은 글꼴 크기에서도 기호마다 그려지는 크기가 다르다 — 실측 비율만큼 키운다. */
const scaled = (C: (p: P) => React.ReactNode, k: number) =>
  function Scaled(p: P) {
    return <span style={{ display: 'inline-flex', transform: `scale(${k})` }}>{C(p)}</span>
  }

let registered = false

/** 한 번만 등록한다 — 마이페이지가 처음 그려질 때 부른다. */
export function registerProfileIcons(): void {
  if (registered) return
  registered = true
  registerIcon('point.topleft.down.to.point.bottomright.curvepath', lucide(Route))
  registerIcon(
    'bag.fill',
    svg((c) => (
      <>
        <path d="M8.2 8.5V7a3.8 3.8 0 0 1 7.6 0v1.5" fill="none" stroke={c} strokeWidth="1.8" strokeLinecap="round" />
        <rect x="3.5" y="8" width="17" height="13.5" rx="3" fill={c} />
      </>
    )),
  )
  registerIcon('bag', lucide(ShoppingBag))
  registerIcon('square.and.pencil', lucide(SquarePen))
  registerIcon('doc.text', lucide(FileText))
  registerIcon(
    'envelope',
    scaled(svg((c) => (
      <g fill="none" stroke={c} strokeWidth="1.6" strokeLinejoin="round" strokeLinecap="round">
        <rect x="1.5" y="4.5" width="21" height="15" rx="2.6" />
        <path d="M2.5 6.5l9.5 7 9.5-7" />
      </g>
    )), 1.2),
  )
  registerIcon('star.bubble', lucide(MessageSquareText))
  registerIcon('storefront', lucide(Store))
  registerIcon('movieclapper', lucide(Clapperboard))
  registerIcon('arrow.up.right', lucide(ArrowUpRight))
  registerIcon('exclamationmark.circle', lucide(CircleAlert))
  registerIcon('photo.on.rectangle.angled', lucide(Images))
  registerIcon('person.crop.circle.badge.checkmark', lucide(UserRoundCheck))
  registerIcon('wifi.exclamationmark', lucide(WifiOff))
  // SF 의 `location`(종이비행기 모양 화살표) — 공용 `location` 은 조준점이라 따로 이름을 둔다.
  registerIcon('st.location.arrow', scaled(lucide(Navigation), 1.3))
  registerIcon(
    'st.sparkles',
    scaled(svg((c) => (
      <g fill={c}>
        <path d="M10.5 3.5c.7 4.6 2.6 6.6 7.3 7.5-4.7.9-6.6 2.9-7.3 7.5-.7-4.6-2.6-6.6-7.3-7.5 4.7-.9 6.6-2.9 7.3-7.5z" />
        <path d="M19 2c.3 1.7 1 2.4 2.7 2.7-1.7.3-2.4 1-2.7 2.7-.3-1.7-1-2.4-2.7-2.7C18 4.4 18.7 3.7 19 2z" />
        <path d="M18.6 15.4c.3 1.5.9 2.1 2.4 2.4-1.5.3-2.1.9-2.4 2.4-.3-1.5-.9-2.1-2.4-2.4 1.5-.3 2.1-.9 2.4-2.4z" />
      </g>
    )), 1.25),
  )
  registerIcon('st.hand.raised', lucide(Hand))
  registerIcon(
    'chart.bar',
    scaled(svg((c) => (
      <g fill="none" stroke={c} strokeWidth="1.8" strokeLinejoin="round">
        <rect x="2.5" y="11" width="5" height="9" rx="1.6" />
        <rect x="9.5" y="5" width="5" height="15" rx="1.6" />
        <rect x="16.5" y="8" width="5" height="12" rx="1.6" />
      </g>
    )), 1.45),
  )
  registerIcon(
    'shoeprints.fill',
    svg((c) => (
      <g fill={c}>
        <ellipse cx="8" cy="8.5" rx="3.2" ry="4.6" />
        <rect x="5.4" y="14.2" width="5.2" height="3.6" rx="1.8" />
        <ellipse cx="16.5" cy="14" rx="3.2" ry="4.6" transform="rotate(8 16.5 14)" />
        <rect x="14" y="19.4" width="5.2" height="3" rx="1.5" />
      </g>
    )),
  )
  registerIcon(
    'sparkles.rectangle.stack',
    svg((c) => (
      <g fill="none" stroke={c} strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
        <path d="M6 4.5h12" />
        <rect x="3.5" y="8" width="17" height="12.5" rx="3" />
        <path d="M12 11.2l.9 2.3 2.3.9-2.3.9-.9 2.3-.9-2.3-2.3-.9 2.3-.9z" fill={c} />
      </g>
    )),
  )
  registerIcon(
    'checkmark.seal.fill',
    svg((c) => (
      <>
        <path
          fill={c}
          d="M12 1.8l2.3 1.7 2.8-.2 1 2.6 2.4 1.5-.7 2.7 1 2.6-1.9 2.1-.3 2.8-2.7.8L14.7 20l-2.7 1.3-2.7-1.3-2-1.8-2.7-.8-.3-2.8L2.4 12.6l1-2.6-.7-2.7L5.1 5.9l1-2.6 2.8.2z"
        />
        <path d="M8 12.2l2.9 2.9 5.2-5.6" fill="none" stroke="#fff" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
      </>
    )),
  )
}
