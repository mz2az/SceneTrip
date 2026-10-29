/*
 * 가이드·길찾기·편의시설이 쓰는 SF Symbols 대체 — design/Icon.tsx 에 없는 기호를 이 폴더에서 등록한다(registerIcon).
 * 이 파일을 import 하는 것만으로 등록된다. 모양이 다른 곳은 S3(미세 차이)이다.
 */
import {
  ArrowUpRight, Binoculars, BedDouble, Bus, CircleCheck, Coffee, FerrisWheel, Footprints, Hourglass, Landmark, Languages, Leaf, Minimize2,
  MapPinOff, Plane, SquareArrowOutUpRight, TrainFront, TramFront, Umbrella, Utensils, Wrench, PersonStanding, BadgeCheck, MessageCircleMore,
  LocateOff, SquareArrowUpRight, ShoppingBag, User,
} from 'lucide-react'
import type { ComponentType, CSSProperties } from 'react'
import { registerIcon } from '@/design/Icon'

type Props = { size: number; strokeWidth: number; color: string; style?: CSSProperties }
type Lucide = ComponentType<{ size?: number; strokeWidth?: number; color?: string; style?: CSSProperties; fill?: string }>

const wrap = (L: Lucide, fill = false) =>
  function GuideIcon({ size, strokeWidth, color, style }: Props) {
    return <L size={size} strokeWidth={strokeWidth} color={color} fill={fill ? color : 'none'} style={style} />
  }

registerIcon('cup.and.saucer.fill', wrap(Coffee))
registerIcon('fork.knife', wrap(Utensils))
registerIcon('bed.double.fill', wrap(BedDouble))
registerIcon('airplane', wrap(Plane))
registerIcon('bus.fill', wrap(Bus))
registerIcon('train.side.front.car', wrap(TrainFront))
registerIcon('tram.fill', wrap(TramFront))
registerIcon('beach.umbrella.fill', wrap(Umbrella))
registerIcon('binoculars.fill', wrap(Binoculars))
registerIcon('ferriswheel', wrap(FerrisWheel))
registerIcon('leaf.fill', wrap(Leaf))
registerIcon('building.columns.fill', wrap(Landmark))
registerIcon('wrench.and.screwdriver', wrap(Wrench))
registerIcon('figure.walk', wrap(PersonStanding))
registerIcon('hourglass', wrap(Hourglass))
registerIcon('checkmark.circle', wrap(CircleCheck))
registerIcon('checkmark.seal.fill', wrap(BadgeCheck, true))
registerIcon('character.bubble', wrap(Languages))
registerIcon('arrow.down.right.and.arrow.up.left', wrap(Minimize2))
registerIcon('arrow.up.right', wrap(ArrowUpRight))
registerIcon('arrow.up.right.square', wrap(SquareArrowOutUpRight))
registerIcon('arrow.up.forward.app', wrap(SquareArrowUpRight))
registerIcon('mappin.slash', wrap(MapPinOff))
registerIcon('location.slash', wrap(LocateOff))
registerIcon('shoeprints.fill', wrap(Footprints))
registerIcon('bubble.left.and.text.bubble.right', wrap(MessageCircleMore))
registerIcon('bag', wrap(ShoppingBag))
registerIcon('person.crop.square', wrap(User))

/** 위로 가는 채워진 원 — 보내기 단추(`arrow.up.circle.fill`). */
registerIcon('arrow.up.circle.fill', function SendCircle({ size, color, style }: Props) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" style={style} aria-hidden>
      <circle cx="12" cy="12" r="12" fill={color} />
      <path d="M12 18V7M7.2 11.6L12 6.8l4.8 4.8" stroke="#fff" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round" fill="none" />
    </svg>
  )
})

/** 계단 — 「계단 있음」 표시(`stairs`). lucide 에 없어 손으로 그린다. */
registerIcon('stairs', function Stairs({ size, strokeWidth, color, style }: Props) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" style={style} aria-hidden>
      <path d="M3 20h5v-5h5v-5h5V5h3" stroke={color} strokeWidth={strokeWidth} strokeLinecap="round" strokeLinejoin="round" fill="none" />
    </svg>
  )
})
