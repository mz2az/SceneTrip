/*
 * 단추 — design/primitives 의 Pressable 과 같되 안쪽을 세로 flex 로 둔다.
 * 단추(`display:block`) 안에 글자(span)를 바로 두면 줄 높이가 단추 자신의 글꼴(17px)로 잡혀 14pt 글자의 단추가 4pt 쯤 더 커진다 —
 * SwiftUI 의 Button 은 내용 크기만 차지한다. flex 로 두면 글자가 제 크기로 줄이 선다.
 */
import { type ComponentProps, forwardRef } from 'react'
import { Pressable as Base } from '@/design/primitives'

export const Pressable = forwardRef<HTMLButtonElement, ComponentProps<typeof Base>>(function Press({ style, ...rest }, ref) {
  return <Base ref={ref} {...rest} style={{ display: 'flex', flexDirection: 'column', justifyContent: 'center', ...style }} />
})
