/*
 * 경로여정·홈이 쓰는 SF Symbols 대체 — design/Icon.tsx 에 없는 기호를 이 폴더에서 등록한다(registerIcon).
 * 모양이 다른 곳은 S3(미세 차이)이다. 이 파일을 import 하는 것만으로 등록된다.
 */
import {
  ArrowDown, ArrowDownUp, BadgeCheck, ChevronsUpDown, CircleAlert, CircleMinus, CirclePlus, Crosshair, Leaf, PencilLine, ShoppingBag,
  Undo2, Zap,
} from 'lucide-react'
import type { ComponentType, CSSProperties } from 'react'
import { registerIcon } from '@/design/Icon'

type Props = { size: number; strokeWidth: number; color: string; style?: CSSProperties }
type Lucide = ComponentType<{ size?: number; strokeWidth?: number; color?: string; style?: CSSProperties; fill?: string }>

const wrap = (L: Lucide, fill = false) =>
  function RouteIcon({ size, strokeWidth, color, style }: Props) {
    return <L size={size} strokeWidth={strokeWidth} color={color} fill={fill ? color : 'none'} style={style} />
  }

registerIcon('hand.draw', wrap(PencilLine))
registerIcon('bolt.fill', wrap(Zap, true))
registerIcon('leaf.fill', wrap(Leaf, true))
registerIcon('exclamationmark.circle', wrap(CircleAlert))
registerIcon('arrow.uturn.left', wrap(Undo2))
registerIcon('arrow.triangle.swap', wrap(ArrowDownUp))
registerIcon('bag', wrap(ShoppingBag))
registerIcon('dot.scope', wrap(Crosshair))
registerIcon('arrow.down', wrap(ArrowDown))
registerIcon('chevron.up.chevron.down', wrap(ChevronsUpDown))
registerIcon('checkmark.seal.fill', wrap(BadgeCheck, true))
registerIcon('plus.circle', wrap(CirclePlus))
registerIcon('minus.circle', wrap(CircleMinus))
registerIcon('line.3.horizontal', function Lines({ size, strokeWidth, color, style }: Props) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" style={style} aria-hidden>
      <path d="M3 7h18M3 12h18M3 17h18" stroke={color} strokeWidth={strokeWidth} strokeLinecap="round" fill="none" />
    </svg>
  )
})
registerIcon('flag.checkered', function Checkered({ size, strokeWidth, color, style }: Props) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" style={style} aria-hidden>
      <path d="M5 3v18" stroke={color} strokeWidth={strokeWidth} strokeLinecap="round" fill="none" />
      <path d="M5 4h14v10H5z" stroke={color} strokeWidth={strokeWidth * 0.8} strokeLinejoin="round" fill="none" />
      <path d="M5 4h4.67v3.33H5zM14.33 4H19v3.33h-4.67zM9.67 7.33h4.66v3.34H9.67zM5 10.67h4.67V14H5zM14.33 10.67H19V14h-4.67z" fill={color} />
    </svg>
  )
})
registerIcon('flag.fill', function FlagFilled({ size, strokeWidth, color, style }: Props) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" style={style} aria-hidden>
      <path d="M5 3v18" stroke={color} strokeWidth={strokeWidth} strokeLinecap="round" fill="none" />
      <path d="M5 4h14l-3 4.5 3 4.5H5z" fill={color} />
    </svg>
  )
})
registerIcon('flag.checkered.fill', function CheckeredFilled({ size, strokeWidth, color, style }: Props) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" style={style} aria-hidden>
      <path d="M5 3v18" stroke={color} strokeWidth={strokeWidth} strokeLinecap="round" fill="none" />
      <path d="M5 4h14v10H5z" fill={color} />
      <path d="M9.67 4H14.33v3.33H9.67zM5 7.33h4.67v3.34H5zM14.33 7.33H19v3.34h-4.67zM9.67 10.67H14.33V14H9.67z" fill="#fff" />
    </svg>
  )
})

/** SF Symbols `sparkles` — 채워진 네 갈래 별 셋(큰 것 하나가 오른쪽 아래, 작은 둘이 왼쪽 위). */
const star = (cx: number, cy: number, r: number): string =>
  `M${cx} ${cy - r}Q${cx} ${cy} ${cx + r} ${cy}Q${cx} ${cy} ${cx} ${cy + r}Q${cx} ${cy} ${cx - r} ${cy}Q${cx} ${cy} ${cx} ${cy - r}Z`
registerIcon('sparkles', function Sparkles({ size, color, style }: Props) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" style={style} aria-hidden>
      <path d={star(14, 14, 9)} fill={color} />
      <path d={star(5.2, 7.2, 3.8)} fill={color} />
      <path d={star(5.8, 18.6, 2.3)} fill={color} />
    </svg>
  )
})
