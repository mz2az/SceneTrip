/*
 * 커뮤니티 화면이 쓰는 SF Symbols 중 공용 표(design/Icon.tsx)에 없는 것 — 이 파일을 가져오는 것만으로 등록된다.
 */
import { ChevronsUpDown, Download, Maximize2, Pause, Route, SquarePen, ZoomIn, ZoomOut } from 'lucide-react'
import { registerIcon } from '@/design/Icon'
import type { ComponentType, CSSProperties } from 'react'

type L = ComponentType<{ size?: number; strokeWidth?: number; color?: string; style?: CSSProperties; fill?: string }>
const line = (C: L, fill = false) =>
  function Registered({ size, strokeWidth, color, style }: { size: number; strokeWidth: number; color: string; style?: CSSProperties }) {
    return <C size={size} strokeWidth={strokeWidth} color={color} fill={fill ? color : 'none'} style={style} />
  }

registerIcon('square.and.pencil', line(SquarePen))
registerIcon('arrow.up.left.and.arrow.down.right', line(Maximize2))
registerIcon('point.topleft.down.to.point.bottomright.curvepath', line(Route))
registerIcon('minus.magnifyingglass', line(ZoomOut))
registerIcon('plus.magnifyingglass', line(ZoomIn))
registerIcon('square.and.arrow.down', line(Download))
registerIcon('pause.fill', line(Pause, true))
registerIcon('chevron.up.chevron.down', line(ChevronsUpDown))
