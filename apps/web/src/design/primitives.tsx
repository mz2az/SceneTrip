/*
 * SwiftUI 레이아웃 어휘를 그대로 쓰기 위한 기본 부품.
 *
 *   VStack(spacing: 8, alignment: .leading) { … }   →  <VStack spacing={8} align="start">…</VStack>
 *   Text("…").font(.system(size: 13, weight: .bold)) →  <Txt size={13} weight="bold">…</Txt>
 *   .padding(.horizontal, 20).padding(.top, 8)      →  pad="8px 20px 0"  (CSS 단축 표기, 또는 padX/padY/padTop…)
 *   .frame(width: 44, height: 38)                    →  style={{ width: 44, height: 38 }}
 *
 * SwiftUI 와 같은 점: 스택은 자식 사이에 spacing 만 두고, 자식은 기본으로 내용 크기만 차지한다.
 */
import {
  type CSSProperties,
  type HTMLAttributes,
  type MouseEventHandler,
  type ReactNode,
  forwardRef,
} from 'react'

export type Align = 'start' | 'center' | 'end' | 'stretch' | 'baseline'
export type Justify = 'start' | 'center' | 'end' | 'between' | 'around'

const ALIGN: Record<Align, CSSProperties['alignItems']> = {
  start: 'flex-start',
  center: 'center',
  end: 'flex-end',
  stretch: 'stretch',
  baseline: 'baseline',
}
const JUSTIFY: Record<Justify, CSSProperties['justifyContent']> = {
  start: 'flex-start',
  center: 'center',
  end: 'flex-end',
  between: 'space-between',
  around: 'space-around',
}

export interface StackProps extends Omit<HTMLAttributes<HTMLDivElement>, 'onClick'> {
  spacing?: number
  align?: Align
  justify?: Justify
  /** CSS padding 단축 표기. 숫자면 네 변 모두. */
  pad?: number | string
  padX?: number
  padY?: number
  padTop?: number
  padBottom?: number
  /** `.frame(maxWidth: .infinity)` */
  fill?: boolean
  /** flex-wrap */
  wrap?: boolean
  onClick?: MouseEventHandler<HTMLElement>
  children?: ReactNode
}

function stackStyle(dir: 'row' | 'column', p: StackProps): CSSProperties {
  const style: CSSProperties = {
    display: 'flex',
    flexDirection: dir,
    gap: p.spacing ?? (dir === 'column' ? 0 : 0),
    alignItems: ALIGN[p.align ?? (dir === 'row' ? 'center' : 'stretch')],
    justifyContent: p.justify ? JUSTIFY[p.justify] : undefined,
    flexWrap: p.wrap ? 'wrap' : undefined,
  }
  if (p.pad !== undefined) style.padding = typeof p.pad === 'number' ? p.pad : p.pad
  if (p.padX !== undefined) {
    style.paddingLeft = p.padX
    style.paddingRight = p.padX
  }
  if (p.padY !== undefined) {
    style.paddingTop = p.padY
    style.paddingBottom = p.padY
  }
  if (p.padTop !== undefined) style.paddingTop = p.padTop
  if (p.padBottom !== undefined) style.paddingBottom = p.padBottom
  if (p.fill) style.width = '100%'
  return style
}

function makeStack(dir: 'row' | 'column', displayName: string) {
  const Comp = forwardRef<HTMLDivElement, StackProps>(function Stack(props, ref) {
    const { spacing: _s, align: _a, justify: _j, pad: _p, padX: _px, padY: _py, padTop: _pt, padBottom: _pb, fill: _f, wrap: _w, style, onClick, ...rest } = props
    return (
      <div
        ref={ref}
        {...rest}
        onClick={onClick as MouseEventHandler<HTMLDivElement> | undefined}
        style={{ ...stackStyle(dir, props), ...style }}
      />
    )
  })
  Comp.displayName = displayName
  return Comp
}

/** SwiftUI `HStack` — 기본 세로 가운데 정렬. */
export const HStack = makeStack('row', 'HStack')
/** SwiftUI `VStack` — 기본 가로 늘림(`.center` 가 아니라 stretch; `alignment: .leading` 은 align="start"). */
export const VStack = makeStack('column', 'VStack')

/** SwiftUI `ZStack` — 자식을 겹친다. 자식은 position:absolute 로 두거나 `layer` 스타일을 쓴다. */
export const ZStack = forwardRef<HTMLDivElement, HTMLAttributes<HTMLDivElement> & { align?: 'center' | 'bottomLeading' | 'topLeading' | 'bottom' | 'top' | 'topTrailing' | 'bottomTrailing' }>(
  function ZStack({ align = 'center', style, ...rest }, ref) {
    const map: Record<string, CSSProperties> = {
      center: { alignItems: 'center', justifyItems: 'center' },
      bottomLeading: { alignItems: 'end', justifyItems: 'start' },
      topLeading: { alignItems: 'start', justifyItems: 'start' },
      bottom: { alignItems: 'end', justifyItems: 'center' },
      top: { alignItems: 'start', justifyItems: 'center' },
      topTrailing: { alignItems: 'start', justifyItems: 'end' },
      bottomTrailing: { alignItems: 'end', justifyItems: 'end' },
    }
    // 모든 자식이 같은 grid 칸에 겹친다.
    return (
      <div
        ref={ref}
        {...rest}
        className={`zstack ${rest.className ?? ''}`}
        style={{ display: 'grid', gridTemplateAreas: '"stack"', ...map[align], ...style }}
      />
    )
  },
)

/** ZStack 안에서 같은 칸을 쓰는 자식에게 준다. */
export const layer: CSSProperties = { gridArea: 'stack' }

/** SwiftUI `Spacer()` */
export function Spacer({ min = 0 }: { min?: number }) {
  return <div style={{ flex: 1, minWidth: min, minHeight: min }} />
}

/** SwiftUI `Divider()` — 세로 스택 안의 가는 선(0.5px 이 아니라 1 device px 로 보이도록 hairline). */
export function Divider({ style }: { style?: CSSProperties }) {
  return <div style={{ height: 1, background: 'var(--separator)', transform: 'scaleY(0.5)', transformOrigin: 'top', ...style }} />
}

// ---------------------------------------------------------------- Text

export type TextColor = 'primary' | 'secondary' | 'tertiary' | 'accent' | 'white' | 'red' | 'purple' | 'deep' | (string & {})
const COLOR: Record<string, string> = {
  primary: 'var(--label)',
  secondary: 'var(--label-secondary)',
  tertiary: 'var(--label-tertiary)',
  accent: 'var(--accent)',
  white: '#fff',
  red: 'var(--system-red)',
  purple: 'var(--home-purple)',
  deep: 'var(--pin-deep)',
}
const WEIGHT = {
  ultraLight: 200,
  light: 300,
  regular: 400,
  medium: 500,
  semibold: 600,
  bold: 700,
  heavy: 800,
  black: 900,
} as const
export type Weight = keyof typeof WEIGHT

export interface TxtProps extends Omit<HTMLAttributes<HTMLElement>, 'color'> {
  /** `.font(.system(size:))` 의 pt. 기본 17(.body). */
  size?: number
  weight?: Weight
  color?: TextColor
  /** `.lineLimit(n)` */
  lines?: number
  align?: 'left' | 'center' | 'right'
  /** `.kerning(n)` px */
  kerning?: number
  /** 줄 높이 배수 (SwiftUI `.lineSpacing` 대신) */
  leading?: number
  italic?: boolean
  /** `.monospacedDigit()` */
  mono?: boolean
  as?: 'span' | 'div' | 'p' | 'h1' | 'h2' | 'h3'
}

export function Txt({ size = 17, weight = 'regular', color = 'primary', lines, align, kerning, leading, italic, mono, as: Tag = 'span', style, ...rest }: TxtProps) {
  const css: CSSProperties = {
    fontSize: size,
    fontWeight: WEIGHT[weight],
    color: COLOR[color] ?? color,
    textAlign: align,
    letterSpacing: kerning !== undefined ? `${kerning}px` : undefined,
    lineHeight: leading ?? undefined,
    fontStyle: italic ? 'italic' : undefined,
    fontVariantNumeric: mono ? 'tabular-nums' : undefined,
    margin: 0,
    // 줄바꿈은 SwiftUI Text 처럼 단어 단위로, 개행 문자는 살린다.
    whiteSpace: 'pre-line',
    overflowWrap: 'anywhere',
    wordBreak: 'keep-all',
  }
  if (lines) {
    Object.assign(css, {
      display: '-webkit-box',
      WebkitLineClamp: lines,
      WebkitBoxOrient: 'vertical',
      overflow: 'hidden',
      whiteSpace: lines === 1 ? 'nowrap' : 'pre-line',
      textOverflow: lines === 1 ? 'ellipsis' : undefined,
    })
    if (lines === 1) {
      css.display = 'block'
      // `VStack align="start"`(= SwiftUI `alignment: .leading`)의 자식은 fit-content 폭이라, 안 줄바꿈 글은 최대 폭까지 펴져 칸 밖으로 넘쳤다.
      // SwiftUI `.lineLimit(1)` 은 칸 폭에서 끊고 「…」 를 붙인다.
      css.maxWidth = '100%'
    }
  }
  return <Tag {...rest} style={{ ...css, ...style }} />
}

// ---------------------------------------------------------------- Shapes

/** `RoundedRectangle(cornerRadius:, style: .continuous)` — 지원하는 브라우저는 squircle. */
export function rr(radius: number): CSSProperties {
  return { borderRadius: radius, cornerShape: 'squircle' } as CSSProperties
}

/** SwiftUI `.shadow(color:radius:x:y:)` — CSS blur 는 SwiftUI radius 의 두 배. */
export function shadow(color: string, radius: number, y = 0, x = 0): string {
  return `${x}px ${y}px ${radius * 2}px ${color}`
}

/** 흰 카드 바탕 — HomeCardBackground (HomeTabView.swift:120) */
export function homeCard(radius = 18): CSSProperties {
  return { background: 'var(--system-background)', boxShadow: 'var(--card-shadow)', ...rr(radius) }
}

// ---------------------------------------------------------------- Button

export interface PressableProps extends Omit<HTMLAttributes<HTMLButtonElement>, 'onClick'> {
  onClick?: MouseEventHandler<HTMLButtonElement>
  disabled?: boolean
  type?: 'button' | 'submit'
  /** 눌렀을 때 흐려지는 표시를 끈다. */
  plain?: boolean
}

/** `Button { } label: { … }.buttonStyle(.plain)` */
export const Pressable = forwardRef<HTMLButtonElement, PressableProps>(function Pressable({ plain, type = 'button', className, style, ...rest }, ref) {
  return (
    <button
      ref={ref}
      type={type}
      {...rest}
      className={`${plain ? '' : 'pressable'} ${className ?? ''}`}
      style={{ display: 'block', ...style }}
    />
  )
})

/** `ProgressView()` */
export function Spinner({ size = 20 }: { size?: number }) {
  return <span className="spinner" style={{ width: size, height: size }} role="progressbar" />
}
