/*
 * SF Symbols 대체. SF Symbols 는 Apple 플랫폼 밖에서 쓸 수 없으므로(라이선스) lucide 아이콘과
 * 손으로 그린 SVG 로 같은 이름을 맞춘다. iOS 코드의 `Image(systemName: "…")` 이름을 그대로 쓴다.
 * 모양이 다른 곳은 S3(미세 차이)이다.
 *
 * 새 기호가 필요하면 기능 폴더에서 `registerIcon('name', Component)` 를 모듈 범위에서 부른다.
 */
import {
  Bell, Bookmark, Calendar, Camera, Check, ChevronDown, ChevronLeft, ChevronRight, ChevronUp, Clock, Ellipsis,
  Flag, Footprints, Globe, House, Image as ImageIcon, Info, Link, List, Locate, LocateFixed, Lock, Map as MapIcon, MapPin,
  MapPinned, MessageCircle, MessagesSquare, Minus, Navigation, Pencil, Phone, Plus, Search, Send, Settings, Share,
  SlidersHorizontal, Sparkles, Star, Trash2, TriangleAlert, User, X, Film, Utensils, BedDouble, Train, Landmark,
  Coffee, Heart, ArrowUpDown, ArrowLeft, ArrowRight, Copy, Eye, EyeOff, Pin, PawPrint, Shield, LogOut, Languages,
  Route, Hand, Ban, CircleHelp, ExternalLink, Download, Upload, RefreshCw, Compass, Car, Bus, Bike, Mic, Menu,
} from 'lucide-react'
import type { ComponentType, CSSProperties, SVGProps } from 'react'

export interface IconComponentProps {
  size: number
  strokeWidth: number
  color: string
  style?: CSSProperties
}
type IconComponent = ComponentType<IconComponentProps>

const registry = new Map<string, IconComponent>()

type Lucide = ComponentType<{ size?: number; strokeWidth?: number; color?: string; style?: CSSProperties; fill?: string }>
function lucide(L: Lucide, fill = false): IconComponent {
  return function LucideIcon({ size, strokeWidth, color, style }) {
    return <L size={size} strokeWidth={strokeWidth} color={color} fill={fill ? color : 'none'} style={style} />
  }
}

function filled(path: (p: { color: string }) => React.ReactNode, viewBox = '0 0 24 24'): IconComponent {
  return function Filled({ size, color, style }) {
    return (
      <svg width={size} height={size} viewBox={viewBox} style={style} aria-hidden>
        {path({ color })}
      </svg>
    )
  }
}

export function registerIcon(name: string, component: IconComponent) {
  registry.set(name, component)
}

const base: Record<string, IconComponent> = {
  magnifyingglass: lucide(Search),
  house: lucide(House),
  'house.fill': lucide(House, true),
  'bubble.left.and.bubble.right': lucide(MessagesSquare),
  'bubble.left': lucide(MessageCircle),
  person: lucide(User),
  'person.fill': lucide(User, true),
  map: lucide(MapIcon),
  'mappin.and.ellipse': lucide(MapPinned),
  mappin: lucide(MapPin),
  'mappin.circle.fill': lucide(MapPin, true),
  'plus.circle': lucide(Plus),
  plus: lucide(Plus),
  minus: lucide(Minus),
  xmark: lucide(X),
  checkmark: lucide(Check),
  'chevron.right': lucide(ChevronRight),
  'chevron.left': lucide(ChevronLeft),
  'chevron.down': lucide(ChevronDown),
  'chevron.up': lucide(ChevronUp),
  'arrow.left': lucide(ArrowLeft),
  'arrow.right': lucide(ArrowRight),
  'arrow.up.arrow.down': lucide(ArrowUpDown),
  ellipsis: lucide(Ellipsis),
  clock: lucide(Clock),
  calendar: lucide(Calendar),
  camera: lucide(Camera),
  photo: lucide(ImageIcon),
  film: lucide(Film),
  star: lucide(Star),
  'star.fill': lucide(Star, true),
  heart: lucide(Heart),
  'heart.fill': lucide(Heart, true),
  bookmark: lucide(Bookmark),
  'bookmark.fill': lucide(Bookmark, true),
  flag: lucide(Flag),
  'flag.fill': lucide(Flag, true),
  gearshape: lucide(Settings),
  bell: lucide(Bell),
  info: lucide(Info),
  'info.circle': lucide(Info),
  'exclamationmark.triangle': lucide(TriangleAlert),
  'exclamationmark.triangle.fill': lucide(TriangleAlert, true),
  'questionmark.circle': lucide(CircleHelp),
  trash: lucide(Trash2),
  pencil: lucide(Pencil),
  'square.and.arrow.up': lucide(Share),
  'slider.horizontal.3': lucide(SlidersHorizontal),
  sparkles: lucide(Sparkles),
  'location': lucide(Locate),
  'location.fill': lucide(LocateFixed),
  'location.north.fill': lucide(Navigation, true),
  'location.north.line': lucide(Navigation),
  lock: lucide(Lock),
  link: lucide(Link),
  globe: lucide(Globe),
  list: lucide(List),
  'list.bullet': lucide(List),
  'paperplane.fill': lucide(Send, true),
  paperplane: lucide(Send),
  phone: lucide(Phone),
  footprints: lucide(Footprints),
  pawprint: lucide(PawPrint),
  pin: lucide(Pin),
  shield: lucide(Shield),
  'rectangle.portrait.and.arrow.right': lucide(LogOut),
  translate: lucide(Languages),
  'fork.knife': lucide(Utensils),
  'bed.double': lucide(BedDouble),
  tram: lucide(Train),
  'building.columns': lucide(Landmark),
  'cup.and.saucer': lucide(Coffee),
  'point.topleft.down.curvedto.point.bottomright.up': lucide(Route),
  'hand.raised': lucide(Hand),
  'nosign': lucide(Ban),
  'arrow.up.right.square': lucide(ExternalLink),
  'arrow.down.to.line': lucide(Download),
  'arrow.up.to.line': lucide(Upload),
  'arrow.clockwise': lucide(RefreshCw),
  'doc.on.doc': lucide(Copy),
  eye: lucide(Eye),
  'eye.slash': lucide(EyeOff),
  car: lucide(Car),
  bus: lucide(Bus),
  bicycle: lucide(Bike),
  mic: lucide(Mic),
  compass: lucide(Compass),
  menu: lucide(Menu),
  // SF Symbols 의 채워진 원형 기호들은 손으로 그린다.
  'checkmark.circle.fill': filled(({ color }) => (
    <>
      <circle cx="12" cy="12" r="11" fill={color} />
      <path d="M7 12.5l3.2 3.2L17 8.8" stroke="#fff" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" fill="none" />
    </>
  )),
  'xmark.circle.fill': filled(({ color }) => (
    <>
      <circle cx="12" cy="12" r="11" fill={color} />
      <path d="M8.2 8.2l7.6 7.6M15.8 8.2l-7.6 7.6" stroke="#fff" strokeWidth="2.2" strokeLinecap="round" fill="none" />
    </>
  )),
  'circle.fill': filled(({ color }) => <circle cx="12" cy="12" r="10" fill={color} />),
  circle: filled(({ color }) => <circle cx="12" cy="12" r="10" fill="none" stroke={color} strokeWidth="1.6" />),
  'plus.circle.fill': filled(({ color }) => (
    <>
      <circle cx="12" cy="12" r="11" fill={color} />
      <path d="M12 7v10M7 12h10" stroke="#fff" strokeWidth="2.2" strokeLinecap="round" fill="none" />
    </>
  )),
}
for (const [name, comp] of Object.entries(base)) registry.set(name, comp)

export interface IconProps extends Omit<SVGProps<SVGSVGElement>, 'color' | 'name'> {
  /** SF Symbols 이름 (`Image(systemName:)` 에 쓴 그대로). */
  name: string
  /** `.font(.system(size:))` 의 pt. */
  size?: number
  /** `.fontWeight` — regular 는 1.6, medium 1.9, semibold 2.1, bold 2.4 */
  weight?: 'regular' | 'medium' | 'semibold' | 'bold'
  color?: string
  style?: CSSProperties
}

const STROKE = { regular: 1.6, medium: 1.9, semibold: 2.1, bold: 2.4 }
const COLORS: Record<string, string> = {
  primary: 'var(--label)',
  secondary: 'var(--label-secondary)',
  tertiary: 'var(--label-tertiary)',
  accent: 'var(--accent)',
  white: '#fff',
  red: 'var(--system-red)',
  purple: 'var(--home-purple)',
  deep: 'var(--pin-deep)',
}

export function Icon({ name, size = 17, weight = 'regular', color = 'primary', style }: IconProps) {
  const Comp = registry.get(name)
  if (!Comp) {
    if (import.meta.env.DEV) console.warn(`[Icon] 등록되지 않은 기호: ${name} — design/Icon.tsx 나 registerIcon 으로 추가하세요`)
    return <span style={{ display: 'inline-block', width: size, height: size, ...style }} />
  }
  return (
    <span style={{ display: 'inline-flex', width: size, height: size, flex: 'none', ...style }} aria-hidden>
      <Comp size={size} strokeWidth={STROKE[weight]} color={COLORS[color] ?? color} />
    </span>
  )
}
